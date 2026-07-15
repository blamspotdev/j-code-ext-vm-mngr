// VM Manager — JCode extension UI (TypeScript, bundled to www/main.js by esbuild).
// Drives full-system QEMU x86_64 VMs in the Linux runtime via the JCode Extension API v1.
// Two surfaces from one bundle (by location.hash): the left-drawer VM list, and a per-VM
// interactive serial console opened as an editor tab via workbench.openView.

interface ApiResult { ok: boolean; data?: any; error?: string }
interface ExecResult { stdout: string; stderr: string; exitCode: number; error?: string }
interface Forward { guest: number; host: number }
interface VmCfg {
  name: string; ram: number; cpus: number; disk: number; iso: string; forwards: Forward[];
  // SQL Server preset extras: kind "sqlserver" boots a cloud image (no installer ISO) with a
  // cloud-init NoCloud seed attached, and auto-installs MS SQL Server on first boot.
  kind?: string; seed?: string; baseImage?: string;
}

// ---- Extension API v1 bridge ----
const pending: Record<string, (r: ApiResult) => void> = {};
let seq = 0;
function api(type: string, payload?: unknown): Promise<ApiResult> {
  return new Promise((resolve) => {
    const id = 'q' + (seq++);
    pending[id] = resolve;
    try {
      (window as any).JCodeNative.request(id, JSON.stringify({ type, payload: payload ?? {} }));
    } catch (e) {
      delete pending[id];
      resolve({ ok: false, error: 'bridge unavailable: ' + e });
    }
  });
}
(window as any).JCode = {
  request: api,
  _onResult(id: string, jsonString: string) {
    const cb = pending[id];
    if (!cb) return;
    delete pending[id];
    let r: ApiResult;
    try { r = JSON.parse(jsonString); } catch { r = { ok: false, error: jsonString }; }
    cb(r);
  },
  _onEvent() { /* no host events consumed yet */ },
};

// ---- helpers ----
function $<T extends HTMLElement = HTMLElement>(id: string): T { return document.getElementById(id) as T; }
const sh = (v: string | number) => "'" + String(v).replace(/'/g, "'\\''") + "'";
const out = (r: ExecResult) => ((r.stdout || '') + (r.stderr || '')).replace(/\s+$/, '');
const esc = (s: string) => s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] as string));
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

async function exec(command: string, timeoutMs = 60000): Promise<ExecResult> {
  const r = await api('exec.run', { command, timeoutMs });
  if (!r || !r.ok) return { stdout: '', stderr: (r && r.error) || 'request failed', exitCode: -1 };
  return r.data as ExecResult;
}

const VMDIR = '/root/vms';
const vmDir = (name: string) => VMDIR + '/' + name;
const validName = (n: string) => /^[a-zA-Z0-9-]+$/.test(n);

// ---- SQL Server preset ----
// Ubuntu 22.04 "jammy" cloud image (amd64): the newest Ubuntu that MS SQL Server 2022 officially
// supports, ships cloud-init + a serial console (console=ttyS0) and BIOS-boots under SeaBIOS.
const SQL_IMG_URL = 'https://cloud-images.ubuntu.com/releases/jammy/release/ubuntu-22.04-server-cloudimg-amd64.img';
const SQL_BASE_DIR = VMDIR + '/_base';
const SQL_BASE_IMG = SQL_BASE_DIR + '/jammy-amd64.img';
// Prefilled SA password (SQL Server refuses a passwordless SA; this passes its complexity policy).
// Mirrors the SQL Client extension's default so a fresh setup connects with zero typing.
const DEFAULT_SA_PASSWORD = 'JCodeVm2026.';
const IC_SQL = '<svg viewBox="0 0 16 16" width="14" height="14" style="vertical-align:-2px"><path fill="currentColor" d="M8 1c3.3 0 6 .9 6 2v10c0 1.1-2.7 2-6 2s-6-.9-6-2V3c0-1.1 2.7-2 6-2zm4.5 4.7C11.3 6.3 9.7 6.6 8 6.6s-3.3-.3-4.5-.9V8c0 .5 2 1 4.5 1s4.5-.5 4.5-1V5.7zM3.5 3.5C3.5 4 5.5 4.5 8 4.5s4.5-.5 4.5-1S10.5 2.5 8 2.5 3.5 3 3.5 3.5zm0 6.8V12.5c0 .5 2 1 4.5 1s4.5-.5 4.5-1v-2.2c-1.2.6-2.8.9-4.5.9s-3.3-.3-4.5-.9z"/></svg>';

// cloud-init user-data (NoCloud) that installs MS SQL Server 2022 unattended on first boot.
// The install runs as an idempotent, sentinel-gated systemd oneshot (NOT runcmd) so it self-heals
// across reboots / mid-install kills. Progress is echoed to /dev/ttyS0 as "JCODE_MSSQL: <phase>"
// tokens (the host tails serial.out). Placeholders <SA_PASSWORD> and <MEM_LIMIT> are substituted by
// the extension before writing. NOTE: kept free of ${...} / backticks so it survives as a template
// literal verbatim; the SA password is single-quoted (UI forbids a literal single quote in it).
const SQL_CLOUD_INIT = `#cloud-config
hostname: mssql-vm
package_update: true
password: ubuntu
chpasswd: { expire: false }
ssh_pwauth: true

write_files:
  - path: /usr/local/sbin/jcode-mssql-setup.sh
    permissions: '0755'
    content: |
      #!/usr/bin/env bash
      set -uo pipefail
      SENTINEL=/var/lib/jcode-mssql.done
      LOG=/var/log/jcode-mssql-setup.log
      exec > >(tee -a "$LOG") 2>&1
      say() { { echo "JCODE_MSSQL: $*" | timeout 2 tee /dev/ttyS0 >/dev/null; } 2>/dev/null || true; echo "JCODE_MSSQL: $*"; }
      [ -f "$SENTINEL" ] && { say already-done; exit 0; }
      SA_PASSWORD='<SA_PASSWORD>'
      say phase-boot
      for i in $(seq 1 60); do getent hosts packages.microsoft.com >/dev/null 2>&1 && break; sleep 5; done
      getent hosts packages.microsoft.com >/dev/null 2>&1 || { say net-fail; exit 1; }
      systemctl stop unattended-upgrades.service apt-daily.service apt-daily-upgrade.service 2>/dev/null || true
      systemctl stop apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || true
      for i in $(seq 1 120); do fuser /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock /var/cache/apt/archives/lock >/dev/null 2>&1 || break; sleep 5; done
      export DEBIAN_FRONTEND=noninteractive
      retry() { local n=0; until "$@"; do n=$((n+1)); [ $n -ge 5 ] && return 1; sleep 10; done; }
      avail=$(df --output=avail -BG / 2>/dev/null | awk 'NR==2{print $1+0}')
      [ "$avail" -lt 8 ] && { say disk-too-small; exit 1; }
      say phase-repo
      [ -s /usr/share/keyrings/microsoft-prod.asc ] || retry curl -fsSL https://packages.microsoft.com/keys/microsoft.asc -o /usr/share/keyrings/microsoft-prod.asc || { say apt-key-fail; exit 1; }
      echo 'deb [arch=amd64 signed-by=/usr/share/keyrings/microsoft-prod.asc] https://packages.microsoft.com/ubuntu/22.04/mssql-server-2022 jammy main' > /etc/apt/sources.list.d/mssql-server-2022.list
      say phase-install
      if ! dpkg -s mssql-server >/dev/null 2>&1; then retry apt-get update || { say apt-update-fail; exit 1; }; retry apt-get install -y mssql-server || { say apt-install-fail; exit 1; }; fi
      say phase-setup
      # mssql-conf setup is NOT idempotent — on an already-configured engine it exits non-zero, which
      # used to fail the whole service on EVERY reboot (the console then only ever showed the installer
      # phases, never the engine coming up). Only run setup when the engine has never been configured
      # (no master DB yet); otherwise just (re)start and verify the existing instance.
      if [ ! -f /var/opt/mssql/data/master.mdf ]; then
        MSSQL_PID=Developer MSSQL_SA_PASSWORD="$SA_PASSWORD" MSSQL_TCP_PORT=1433 MSSQL_MEMORY_LIMIT_MB=<MEM_LIMIT> /opt/mssql/bin/mssql-conf -n setup accept-eula || { say sa-password-rejected-or-setup-fail; exit 1; }
      fi
      systemctl enable mssql-server
      systemctl start mssql-server
      say phase-verify
      for i in $(seq 1 60); do timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null && break; sleep 5; done
      if command -v ss >/dev/null 2>&1; then L=$(ss -ltn 2>/dev/null | grep ':1433' || true); case "$L" in *0.0.0.0:1433*) : ;; *127.0.0.1:1433*) say bound-loopback-only ;; esac; fi
      timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null || { say not-listening; exit 1; }
      touch "$SENTINEL"
      say ready
      exit 0
  - path: /etc/systemd/system/jcode-mssql.service
    permissions: '0644'
    content: |
      [Unit]
      Description=JCode unattended MS SQL Server setup
      After=network-online.target
      Wants=network-online.target
      ConditionPathExists=!/var/lib/jcode-mssql.done
      [Service]
      Type=oneshot
      ExecStart=/usr/local/sbin/jcode-mssql-setup.sh
      RemainAfterExit=yes
      TimeoutStartSec=0
      [Install]
      WantedBy=multi-user.target
  - path: /usr/local/sbin/jcode-mssql-ready.sh
    permissions: '0755'
    content: |
      #!/usr/bin/env bash
      # Announce on the serial console when the SQL Server ENGINE is actually up and listening on 1433.
      # Runs on EVERY boot (NOT sentinel-gated) and independent of the installer, so the console always
      # shows SQL-SERVER-READY once the engine accepts connections — the installer only prints phases.
      say() { { echo "JCODE_MSSQL: $*" | timeout 2 tee /dev/ttyS0 >/dev/null; } 2>/dev/null || true; echo "JCODE_MSSQL: $*"; }
      say engine-starting
      for i in $(seq 1 360); do
        if timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null; then
          L=$(ss -ltn 2>/dev/null | grep ':1433' | awk '{print $4}' | paste -sd' ' -)
          [ -z "$L" ] && L=0.0.0.0:1433
          say "SQL-SERVER-READY listening on $L"
          exit 0
        fi
        sleep 5
      done
      say "SQL-SERVER-NOT-LISTENING after wait (check: systemctl status mssql-server)"
      exit 1
  - path: /etc/systemd/system/jcode-mssql-ready.service
    permissions: '0644'
    content: |
      [Unit]
      Description=JCode announce SQL Server ready on the serial console
      After=mssql-server.service network-online.target
      Wants=network-online.target
      [Service]
      Type=oneshot
      ExecStart=/usr/local/sbin/jcode-mssql-ready.sh
      RemainAfterExit=yes
      TimeoutStartSec=0
      [Install]
      WantedBy=multi-user.target

runcmd:
  - [ systemctl, daemon-reload ]
  - [ systemctl, enable, --now, jcode-mssql.service ]
  - [ systemctl, enable, --now, jcode-mssql-ready.service ]

final_message: "cloud-init done; waiting for the SQL Server engine — watch for JCODE_MSSQL: SQL-SERVER-READY."
`;

// ---- modal ----
interface FormField { key: string; label: string; value?: string; placeholder?: string; type?: string }
function showModal(opts: {
  title: string; body?: string; fields?: FormField[]; confirmLabel: string; danger?: boolean;
  validate?: (values: Record<string, string>) => string | null;
  onConfirm: (values: Record<string, string>) => void;
}) {
  const back = document.createElement('div'); back.className = 'modal-scrim';
  const dlg = document.createElement('div'); dlg.className = 'modal';
  let html = '<div class="modal-title">' + esc(opts.title) + '</div>';
  if (opts.body) html += '<div class="modal-body">' + opts.body + '</div>';
  (opts.fields || []).forEach((f) => {
    html += '<div class="field"><label>' + esc(f.label) + '</label>' +
      '<input id="__f_' + f.key + '" type="' + (f.type || 'text') + '"></div>';
  });
  html += '<div class="modal-err" id="__err"></div>' +
    '<div class="modal-actions"><button class="btn ghost" id="__cancel">Cancel</button>' +
    '<button class="btn ' + (opts.danger ? 'dfill' : 'primary') + '" id="__ok">' + esc(opts.confirmLabel) + '</button></div>';
  dlg.innerHTML = html; back.appendChild(dlg); document.body.appendChild(back);
  (opts.fields || []).forEach((f) => {
    const inp = $('__f_' + f.key) as HTMLInputElement;
    inp.value = f.value ?? ''; inp.placeholder = f.placeholder ?? '';
    inp.autocapitalize = 'none'; inp.spellcheck = false;
  });
  const first = opts.fields && opts.fields[0] ? ($('__f_' + opts.fields[0].key) as HTMLInputElement) : null;
  if (first) setTimeout(() => first.focus(), 30);
  const close = () => back.remove();
  $('__cancel').onclick = close;
  $('__ok').onclick = () => {
    const values: Record<string, string> = {};
    (opts.fields || []).forEach((f) => { values[f.key] = ($('__f_' + f.key) as HTMLInputElement).value.trim(); });
    if (opts.validate) {
      const err = opts.validate(values);
      if (err) { $('__err').textContent = err; return; }
    }
    close();
    opts.onConfirm(values);
  };
  back.onclick = (e) => { if (e.target === back) close(); };
}

// ==========================================================================
// DRAWER VIEW — QEMU status, VM list, create
// ==========================================================================
let qemuOk = false;
let qemuNoticeShown = false;

async function renderDrawer() {
  document.body.className = '';
  $('root').innerHTML =
    '<div class="hd"><span class="ttl">Virtual Machines</span>' +
    '<button class="ic" id="refresh" title="Refresh"><svg viewBox="0 0 16 16"><path fill="currentColor" d="M8 3a5 5 0 104.546 2.914.75.75 0 011.364-.626A6.5 6.5 0 118 1.5V.31c0-.28.32-.44.55-.28l2.3 1.65c.18.13.18.4 0 .53l-2.3 1.65a.35.35 0 01-.55-.28V3z"/></svg></button></div>' +
    '<div class="body"><div id="status" class="status"><span class="pill"><span class="dot"></span> Checking QEMU…</span></div>' +
    '<div class="acts-top">' +
      '<div class="split">' +
        '<button class="btn primary" id="create">+ Create VM</button>' +
        '<button class="btn primary caret" id="createMore" title="More setup options" aria-label="More setup options">' +
          '<svg viewBox="0 0 16 16"><path fill="currentColor" d="M4 6l4 4 4-4z"></path></svg>' +
        '</button>' +
      '</div>' +
      '<div id="createMenu" class="pop hide">' +
        '<div class="row tap" id="menuSql">' + IC_SQL + ' <span class="k">Set up SQL Server…</span></div>' +
      '</div>' +
    '</div>' +
    '<div class="sec-ttl">Machines</div><div id="vmlist"><div class="empty">Loading…</div></div></div>';
  $('refresh').onclick = () => { void boot(); };
  $('create').onclick = openCreateDialog;
  $('createMore').onclick = (e) => { e.stopPropagation(); $('createMenu').classList.toggle('hide'); };
  $('menuSql').onclick = () => { $('createMenu').classList.add('hide'); openSqlServerDialog(); };
  // Recover any provisioning still running from before this (re)load (e.g. a rotation reloaded us).
  await reconcileProvisioning();
  // Tap anywhere else closes the menu (assignment, not addEventListener — no stacking across boots).
  document.onclick = (e) => {
    const m = document.getElementById('createMenu');
    if (m && !m.classList.contains('hide') && !(e.target as HTMLElement).closest('.split, .pop')) {
      m.classList.add('hide');
    }
  };
  await checkQemu();
  await loadVms();
}

async function checkQemu(): Promise<boolean> {
  const box = $('status');
  const sys = out(await exec('command -v qemu-system-x86_64', 8000));
  const img = out(await exec('command -v qemu-img', 8000));
  qemuOk = !!sys && !!img;
  if (qemuOk) {
    const ver = out(await exec('qemu-system-x86_64 --version | head -1', 8000));
    box.innerHTML = '<span class="pill ok"><span class="dot"></span> QEMU ready</span>' +
      '<span class="note">' + esc(ver || 'qemu-system-x86_64') + '</span>';
  } else {
    box.innerHTML = '<span class="pill bad"><span class="dot"></span> QEMU not installed</span>';
    // Surface the requirement as a dialog, per the drawer flow.
    showModal({
      title: 'QEMU is required',
      body: 'VM Manager needs QEMU to run virtual machines. Install <b>x86 Virtualization (QEMU)</b> ' +
        'from <b>Toolchains</b>, then Refresh.',
      confirmLabel: 'Got it',
      onConfirm: () => { /* dismiss */ },
    });
  }
  ($('create') as HTMLButtonElement).disabled = !qemuOk;
  const more = document.getElementById('createMore') as HTMLButtonElement | null;
  if (more) more.disabled = !qemuOk;
  return qemuOk;
}

async function loadVms() {
  const list = $('vmlist');
  const paths = out(await exec('ls -1 ' + VMDIR + '/*/vm.json 2>/dev/null', 10000))
    .split('\n').map((s) => s.trim()).filter(Boolean);
  const provNames = Object.keys(provisioning);
  if (!paths.length && !provNames.length) {
    list.innerHTML = '<div class="empty">No VMs yet.' + (qemuOk ? ' Create one above.' : '') + '</div>';
    return;
  }
  list.innerHTML = '';
  for (const n of provNames) list.appendChild(renderProvisioning(n));
  for (const p of paths) {
    const name = p.replace(/\/vm\.json$/, '').split('/').pop() as string;
    const cfg = await readCfg(name);
    const running = await isRunning(name);
    list.appendChild(renderVm(name, cfg, running));
  }
}

async function readCfg(name: string): Promise<VmCfg> {
  const r = out(await exec('cat ' + sh(vmDir(name) + '/vm.json') + ' 2>/dev/null', 8000));
  try { return JSON.parse(r) as VmCfg; } catch { return { name, ram: 0, cpus: 0, disk: 0, iso: '', forwards: [] }; }
}

// A VM runs as a long-lived runtime "service" (a JVM-held proot process). A plain backgrounded
// exec would be reaped by proot --kill-on-exit the moment the launcher returns, so service.* is
// the only way to keep QEMU alive. Running state = the service is alive this session.
async function isRunning(name: string): Promise<boolean> {
  const r = await api('service.status', { id: 'vm:' + name });
  return !!(r.ok && r.data && r.data.running);
}

function fwdText(cfg: VmCfg): string {
  const f = cfg.forwards || [];
  return f.length ? f.map((x) => x.guest + '→' + x.host).join(', ') : 'no port forwards';
}

function renderVm(name: string, cfg: VmCfg, running: boolean): HTMLElement {
  const div = document.createElement('div');
  div.className = 'vm';
  const isSql = cfg.kind === 'sqlserver';
  div.innerHTML =
    '<div class="top"><span class="name">' + esc(name) + '</span>' +
    (isSql ? '<span class="tag sql">SQL Server</span>' : '') +
    (running ? '<span class="pill run"><span class="dot"></span> running</span>'
             : '<span class="pill stop"><span class="dot"></span> stopped</span>') + '</div>' +
    '<div class="meta">' + (cfg.ram || '?') + ' MB · ' + (cfg.cpus || '?') + ' CPU · ' +
      (cfg.disk || '?') + ' GB · ' + esc(fwdText(cfg)) + (cfg.iso ? ' · ISO' : '') + '</div>' +
    (isSql
      ? '<div class="sqlbox"><div class="sqlstat">' + (running ? 'Checking…' : 'Stopped — Start to run SQL Server.') + '</div>' +
        '<div class="sqlhint">In SQL Client settings: Server <b>localhost,1433</b> · Login <b>sa</b> · Trust certificate <b>on</b>.</div></div>'
      : '') +
    '<div class="acts">' +
      (running
        ? '<button class="btn sm" data-a="stop">Stop</button><button class="btn sm danger" data-a="force">Force</button>'
        : '<button class="btn sm primary" data-a="start">Start</button>') +
      '<button class="btn sm" data-a="console">Console</button>' +
      '<button class="btn sm danger" data-a="delete">Delete</button>' +
    '</div>';
  div.querySelectorAll<HTMLElement>('[data-a]').forEach((b) => {
    b.onclick = () => {
      const a = b.getAttribute('data-a');
      if (a === 'start') void startVm(name);
      else if (a === 'stop') void stopVm(name, false);
      else if (a === 'force') void stopVm(name, true);
      else if (a === 'console') void openConsole(name);
      else if (a === 'delete') confirmDelete(name);
    };
  });
  if (isSql && running) {
    const statEl = div.querySelector<HTMLElement>('.sqlstat');
    if (statEl) void watchSql(name, statEl);
  }
  return div;
}

function renderProvisioning(name: string): HTMLElement {
  const div = document.createElement('div');
  div.className = 'vm prov';
  div.innerHTML =
    '<div class="top"><span class="name">' + esc(name) + '</span><span class="tag sql">SQL Server</span>' +
    '<span class="pill work"><span class="dot"></span> preparing</span></div>' +
    '<div class="meta">Downloading the Ubuntu image &amp; building the disk (one-time). You can leave this open.</div>' +
    '<div class="sqlstat" data-prov="' + esc(name) + '">' + esc(provisioning[name] || 'Starting…') + '</div>' +
    '<div class="acts"><button class="btn sm danger" id="prov-cancel">Cancel</button></div>';
  const cancel = div.querySelector<HTMLElement>('#prov-cancel');
  if (cancel) cancel.onclick = () => void cancelProvision(name);
  return div;
}

function openCreateDialog() {
  if (!qemuOk) { void checkQemu(); return; }
  showModal({
    title: 'Create a VM',
    body: 'Full-system x86_64, software-emulated (no KVM — slow but compatible).',
    fields: [
      { key: 'name', label: 'Name (a–z, 0–9, dash)', placeholder: 'ubuntu-server' },
      { key: 'ram', label: 'RAM (MB)', value: '2048', type: 'number' },
      { key: 'cpus', label: 'CPUs', value: '2', type: 'number' },
      { key: 'disk', label: 'Disk (GB)', value: '20', type: 'number' },
      { key: 'iso', label: 'Install ISO path (optional)', placeholder: '/root/iso/ubuntu.iso' },
      { key: 'fwd', label: 'Port forwards guest:host (comma-sep, optional)', placeholder: '22:2222, 1433:1433' },
    ],
    confirmLabel: 'Create',
    onConfirm: (v) => void createVm(v),
  });
}

function parseFwds(s: string): Forward[] {
  return (s || '').split(',').map((p) => p.trim()).filter(Boolean).map((p) => {
    const m = p.split(':').map((x) => parseInt(x.trim(), 10));
    return { guest: m[0], host: m[1] };
  }).filter((f) => f.guest > 0 && f.host > 0);
}

async function createVm(v: Record<string, string>) {
  const name = (v.name || '').trim();
  if (!validName(name)) { toast('Invalid name — letters, digits and dashes only.'); return; }
  const dir = vmDir(name);
  if (out(await exec('test -e ' + sh(dir) + ' && echo yes || echo no', 8000)) === 'yes') {
    toast('A VM named "' + name + '" already exists.'); return;
  }
  const cfg: VmCfg = {
    name,
    ram: parseInt(v.ram, 10) || 2048,
    cpus: parseInt(v.cpus, 10) || 2,
    disk: parseInt(v.disk, 10) || 20,
    iso: (v.iso || '').trim(),
    forwards: parseFwds(v.fwd),
  };
  toast('Creating disk for "' + name + '"…');
  await exec('mkdir -p ' + sh(dir), 10000);
  const mk = await exec('qemu-img create -f qcow2 ' + sh(dir + '/disk.qcow2') + ' ' + cfg.disk + 'G', 300000);
  if (mk.exitCode !== 0) { toast('Disk creation failed: ' + (out(mk) || 'error')); return; }
  const json = JSON.stringify(cfg, null, 2);
  await exec('cat > ' + sh(dir + '/vm.json') + " <<'JCODE_EOF'\n" + json + '\nJCODE_EOF', 10000);
  toast('VM "' + name + '" created.');
  await loadVms();
}

async function startVm(name: string) {
  const dir = vmDir(name);
  const cfg = await readCfg(name);
  toast('Starting ' + name + '…');
  const fwds = (cfg.forwards || []).map((f) => ',hostfwd=tcp::' + f.host + '-:' + f.guest).join('');
  // -serial pty exposes the guest serial as a PTY; -display none keeps it headless. QEMU runs in the
  // FOREGROUND of the service (a JVM-held proot process), so it survives; stderr/stdout (incl. the PTY
  // path QEMU prints) go to qemu-stdout.log.
  const tail = ' -netdev user,id=n0' + fwds + ' -device virtio-net,netdev=n0' +
    ' -display none -serial pty -pidfile ' + sh(dir + '/qemu.pid') +
    ' >' + sh(dir + '/qemu-stdout.log') + ' 2>&1';
  let q: string;
  if (cfg.kind === 'sqlserver' || cfg.seed) {
    // Cloud-image path: boot the pre-installed image directly (no installer -cdrom/-boot), attach the
    // cloud-init NoCloud seed as a read-only virtio disk. CPU/accel are deliberately CONSERVATIVE for
    // correctness under emulation: SQL Server's engine is SIMD- and atomic-heavy and crashes under the
    // aggressive path (`-cpu max` exposes AVX-512 and `tcg,thread=multi`/MTTCG can mis-order atomics).
    // Single-threaded TCG (`-accel tcg`) emulates memory ordering correctly, and `-cpu Westmere` gives
    // the SSE4.2 baseline SQL requires plus AES-NI/PCLMULQDQ (both solid in TCG, used by cert-gen) while
    // staying pre-AVX. virtio-rng-pci feeds entropy so cloud-init/cert-gen don't stall on getrandom().
    q = 'qemu-system-x86_64 -accel tcg -machine q35 -cpu Westmere' +
      ' -m ' + (cfg.ram || 4096) + ' -smp ' + (cfg.cpus || 2) +
      ' -drive file=' + sh(dir + '/disk.qcow2') + ',if=virtio,format=qcow2' +
      ' -drive file=' + sh(dir + '/' + (cfg.seed || 'seed.img')) + ',if=virtio,format=raw,readonly=on' +
      ' -device virtio-rng-pci' + tail;
  } else {
    q = 'qemu-system-x86_64 -accel tcg -m ' + (cfg.ram || 2048) + ' -smp ' + (cfg.cpus || 2) +
      ' -drive file=' + sh(dir + '/disk.qcow2') + ',if=virtio';
    if (cfg.iso) q += ' -cdrom ' + sh(cfg.iso) + ' -boot d';
    q += tail;
  }
  // Reap any lingering QEMU still holding this VM's disk before launching — otherwise a Stop/Start
  // race (service.stop marks "stopped" before the OS reaps the process) or an orphan left by an app
  // force-stop keeps the qcow2 write lock and the new QEMU dies with `Failed to get "write" lock`.
  // Scan /proc for a process whose cmdline references this disk.qcow2, kill it, and wait (up to ~5s)
  // for the lock to release. Inside the proot /proc is the bind-mounted host /proc, so QEMU is visible;
  // it runs as the app's real uid so the kill is permitted.
  const disk = dir + '/disk.qcow2';
  await reapVmProcess(disk, true);
  await exec(': > ' + sh(dir + '/serial.out') + '; rm -f ' + sh(dir + '/serial.pts'), 8000);
  const s = await api('service.start', { id: 'vm:' + name, command: q });
  if (!s.ok) { toast('Start failed: ' + (s.error || 'service error')); return; }
  // Wait for QEMU to report the allocated PTY, then stream it to serial.out via a second service.
  let pts = '';
  for (let i = 0; i < 15 && !pts; i++) {
    await sleep(400);
    pts = out(await exec('grep -oE "/dev/pts/[0-9]+" ' + sh(dir + '/qemu-stdout.log') + ' 2>/dev/null | head -1', 6000));
  }
  if (pts) {
    await exec('printf "%s" ' + sh(pts) + ' > ' + sh(dir + '/serial.pts'), 6000);
    await api('service.start', { id: 'vmread:' + name, command: 'cat ' + sh(pts) + ' >> ' + sh(dir + '/serial.out') });
    toast('Started ' + name + ' — open the console.');
  } else if (await isRunning(name)) {
    toast('Started ' + name + ' (serial not attached — check the log).');
  } else {
    const err = out(await exec('tail -3 ' + sh(dir + '/qemu-stdout.log') + ' 2>/dev/null', 6000));
    toast('QEMU exited: ' + (err || 'see qemu-stdout.log'));
  }
  await loadVms();
}

// Reap the QEMU process still holding this VM's disk. `service.stop` alone doesn't reliably bring QEMU
// down (it's orphaned from the service shell — the start path already has to scan for leftovers), which
// is why a soft Stop appeared to do nothing. Scan the (host-visible, bind-mounted) /proc for a process
// whose cmdline references disk.qcow2 and signal it; running as the app's real uid, the kill is allowed.
// Soft stop sends SIGTERM (QEMU exits cleanly, flushing the qcow2), escalating to SIGKILL only if it
// hasn't exited within a few seconds; force sends SIGKILL straight away.
async function reapVmProcess(disk: string, force: boolean) {
  const sweep = (sig: string) =>
    'for p in /proc/[0-9]*/cmdline; do [ -r "$p" ] || continue; ' +
    'tr "\\000" " " < "$p" 2>/dev/null | grep -qF ' + sh(disk) + ' || continue; ' +
    'pid=${p%/cmdline}; kill -' + sig + ' "${pid##*/}" 2>/dev/null; f=1; done';
  await exec('n=0; while [ $n -lt 12 ]; do f=0; ' + sweep(force ? 'KILL' : 'TERM') + '; ' +
    '[ $f -eq 0 ] && break; sleep 0.5; n=$((n+1)); done', 20000);
  if (!force) await exec('f=0; ' + sweep('KILL'), 12000); // SIGTERM didn't take — force it so Stop always stops.
}

async function stopVm(name: string, force: boolean) {
  const dir = vmDir(name);
  toast((force ? 'Force-stopping ' : 'Stopping ') + name + '…');
  await api('service.stop', { id: 'vmread:' + name });
  await api('service.stop', { id: 'vm:' + name });
  await reapVmProcess(dir + '/disk.qcow2', force);
  await exec('rm -f ' + sh(dir + '/qemu.pid') + ' ' + sh(dir + '/serial.pts'), 8000);
  await sleep(300);
  await loadVms();
}

function confirmDelete(name: string) {
  showModal({
    title: 'Delete VM',
    body: 'Delete <b>' + esc(name) + '</b> and its disk? This can’t be undone.',
    confirmLabel: 'Delete', danger: true,
    onConfirm: async () => {
      const dir = vmDir(name);
      await api('service.stop', { id: 'vmread:' + name });
      await api('service.stop', { id: 'vm:' + name });
      await exec('rm -rf ' + sh(dir), 20000);
      toast('Deleted ' + name + '.');
      await loadVms();
    },
  });
}

// ==========================================================================
// SQL SERVER PRESET — one-click VM that auto-installs MS SQL Server 2022
// ==========================================================================
// Names of VMs currently being provisioned (cloud image download + disk build), before vm.json exists.
const provisioning: Record<string, string> = {};

function openSqlServerDialog() {
  if (!qemuOk) { void checkQemu(); return; }
  showModal({
    title: 'Set up SQL Server',
    body: 'Creates an Ubuntu 22.04 VM that <b>auto-installs Microsoft SQL Server 2022</b> (Developer edition) ' +
      'on first boot — no manual OS install. First setup downloads ~700 MB and can take <b>30–60 minutes under ' +
      'emulation</b>; you only wait once. Then connect the SQL Client to <b>localhost,1433</b> with login ' +
      '<b>sa</b>. SQL Server requires an SA password — a default is prefilled (also the SQL Client default); ' +
      'change it if this VM will hold anything sensitive.',
    fields: [
      { key: 'name', label: 'VM name', value: 'sqlserver' },
      { key: 'password', label: 'SA password (login: sa)', value: DEFAULT_SA_PASSWORD, type: 'password' },
      { key: 'confirm', label: 'Confirm SA password', value: DEFAULT_SA_PASSWORD, type: 'password' },
      { key: 'ram', label: 'RAM (MB) — min 2048', value: '2048', type: 'number' },
      { key: 'disk', label: 'Disk (GB)', value: '30', type: 'number' },
      { key: 'cpus', label: 'CPUs', value: '2', type: 'number' },
    ],
    confirmLabel: 'Set up',
    validate: (v) => {
      const name = (v.name || '').trim();
      if (!validName(name)) return 'Name: letters, digits and dashes only.';
      const p = v.password || '';
      if (p.length < 8 || p.length > 128) return 'SA password must be 8–128 characters.';
      const cats = [/[A-Z]/, /[a-z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((re) => re.test(p)).length;
      if (cats < 3) return 'SA password needs 3 of: uppercase, lowercase, digit, symbol.';
      if (p.indexOf("'") >= 0) return "SA password cannot contain a single quote ( ' ).";
      if (/sa/i.test(p)) return 'SA password cannot contain "sa".';
      if (p !== v.confirm) return 'Passwords do not match.';
      if ((parseInt(v.ram, 10) || 0) < 2048) return 'RAM must be at least 2048 MB for SQL Server.';
      return null;
    },
    onConfirm: (v) => void setupSqlServer(v),
  });
}

async function setupSqlServer(v: Record<string, string>) {
  const name = (v.name || 'sqlserver').trim();
  const password = v.password || '';
  const ram = Math.max(2048, parseInt(v.ram, 10) || 2048);
  const cpus = Math.min(4, Math.max(1, parseInt(v.cpus, 10) || 2));
  const disk = Math.max(16, parseInt(v.disk, 10) || 30);
  // Cap SQL Server's memory so the guest OS keeps ~1 GB even at the 2 GB minimum (at 2048 this is
  // 1024, not the old 2048 floor that would have starved the OS); larger VMs still reserve ram-1536.
  const memLimit = Math.max(1024, ram - 1536);
  const dir = vmDir(name);
  if (out(await exec('test -e ' + sh(dir) + ' && echo yes || echo no', 8000)) === 'yes') {
    toast('A VM named "' + name + '" already exists.'); return;
  }
  // Pre-flight: QEMU user-net can't bind host 1433 if something already holds it (the VM would die instantly).
  const busy = out(await exec("timeout 1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null && echo BUSY || echo FREE", 6000));
  if (busy.indexOf('BUSY') >= 0) { toast('Port 1433 is already in use — stop the other SQL VM first.'); return; }

  const userData = SQL_CLOUD_INIT.split('<SA_PASSWORD>').join(password).split('<MEM_LIMIT>').join(String(memLimit));
  const metaData = 'instance-id: jcode-mssql-' + name + '-' + Date.now() + '\nlocal-hostname: mssql-vm';
  await exec('mkdir -p ' + sh(dir) + ' ' + sh(SQL_BASE_DIR), 10000);
  await exec('cat > ' + sh(dir + '/user-data') + " <<'JCODE_EOF'\n" + userData + '\nJCODE_EOF', 10000);
  await exec('cat > ' + sh(dir + '/meta-data') + " <<'JCODE_EOF'\n" + metaData + '\nJCODE_EOF', 10000);
  // Persist the config so a reloaded UI (e.g. after a rotation) can re-attach to this provisioning run
  // and still write the correct vm.json when it finishes — see reconcileProvisioning().
  await exec('cat > ' + sh(dir + '/.prov.json') + " <<'JCODE_EOF'\n" + JSON.stringify({ ram, cpus, disk }) + '\nJCODE_EOF', 8000);

  // Provision as a background service (download ~700MB + build disk + seed) so the UI stays responsive;
  // touch a flag on success/failure which pollProvision watches. Download to .tmp then mv so a killed
  // download never leaves a truncated backing file behind.
  const prov =
    'export DEBIAN_FRONTEND=noninteractive; rm -f ' + sh(dir + '/.provision-ok') + ' ' + sh(dir + '/.provision-fail') + '; ' +
    '( (command -v qemu-img >/dev/null 2>&1 && command -v cloud-localds >/dev/null 2>&1 || (apt-get update && apt-get install -y qemu-utils cloud-image-utils genisoimage xorriso curl)) ' +
    '&& (test -f ' + sh(SQL_BASE_IMG) + ' || (curl -fL --retry 3 -o ' + sh(SQL_BASE_IMG + '.tmp') + ' ' + sh(SQL_IMG_URL) + ' && mv ' + sh(SQL_BASE_IMG + '.tmp') + ' ' + sh(SQL_BASE_IMG) + ')) ' +
    '&& qemu-img create -f qcow2 -F qcow2 -b ' + sh(SQL_BASE_IMG) + ' ' + sh(dir + '/disk.qcow2') + ' ' + disk + 'G ' +
    '&& cd ' + sh(dir) + ' && (cloud-localds seed.img user-data meta-data || xorriso -as mkisofs -V CIDATA -J -r -o seed.img user-data meta-data || genisoimage -V CIDATA -J -r -o seed.img user-data meta-data) ' +
    ') > ' + sh(dir + '/provision.log') + ' 2>&1 && touch ' + sh(dir + '/.provision-ok') + ' || touch ' + sh(dir + '/.provision-fail');
  await api('service.start', { id: 'sqlprov:' + name, command: prov });
  provisioning[name] = 'Downloading Ubuntu image…';
  toast('Setting up SQL Server VM "' + name + '"…');
  await loadVms();
  void pollProvision(name, ram, cpus, disk);
}

// Re-attach the UI to any SQL-Server provisioning that's still running on disk but isn't tracked in
// this (possibly just-reloaded) WebView — the classic case being a device rotation, which reloads the
// WebView while the background sqlprov service keeps downloading/building. A VM dir with a .prov.json
// but no vm.json is mid-provision; resume it if the service is still alive (or it already finished),
// otherwise leave it (a dead run from an app kill would poll forever).
async function reconcileProvisioning() {
  const names = out(await exec(
    'for d in ' + VMDIR + '/*/; do n=$(basename "$d"); [ "$n" = "_base" ] && continue; ' +
    '[ -f "$d/.prov.json" ] && [ ! -f "$d/vm.json" ] && [ ! -f "$d/.provision-fail" ] && echo "$n"; done 2>/dev/null', 10000))
    .split('\n').map((s) => s.trim()).filter(Boolean);
  for (const name of names) {
    if (name in provisioning) continue;
    const okFlag = out(await exec('test -f ' + sh(vmDir(name) + '/.provision-ok') + ' && echo yes', 6000)).trim() === 'yes';
    const st = await api('service.status', { id: 'sqlprov:' + name });
    const alive = !!(st && st.ok && st.data && st.data.running);
    if (!okFlag && !alive) continue; // interrupted (app was killed) — don't resume into an endless poll
    let meta: { ram?: number; cpus?: number; disk?: number } = {};
    try { meta = JSON.parse(out(await exec('cat ' + sh(vmDir(name) + '/.prov.json') + ' 2>/dev/null', 6000))); } catch { /* defaults below */ }
    provisioning[name] = 'Resuming setup…';
    void pollProvision(name, meta.ram || 4096, meta.cpus || 2, meta.disk || 30);
  }
}

async function pollProvision(name: string, ram: number, cpus: number, disk: number) {
  const dir = vmDir(name);
  if (!(name in provisioning)) return; // cancelled
  const flag = out(await exec(
    'if [ -f ' + sh(dir + '/.provision-ok') + ' ]; then echo OK; elif [ -f ' + sh(dir + '/.provision-fail') + ' ]; then echo FAIL; else echo WAIT; fi', 8000));
  if (flag.indexOf('OK') >= 0) {
    delete provisioning[name];
    const cfg: VmCfg = {
      name, kind: 'sqlserver', ram, cpus, disk, iso: '', seed: 'seed.img', baseImage: 'jammy-amd64.img',
      forwards: [{ guest: 1433, host: 1433 }, { guest: 22, host: 2222 }],
    };
    await exec('cat > ' + sh(dir + '/vm.json') + " <<'JCODE_EOF'\n" + JSON.stringify(cfg, null, 2) + '\nJCODE_EOF\nrm -f ' + sh(dir + '/.prov.json') + ' 2>/dev/null', 10000);
    toast('Image ready — starting the VM. First boot installs SQL Server (30–60 min); watch the console.');
    await startVm(name);
    await loadVms();
    return;
  }
  if (flag.indexOf('FAIL') >= 0) {
    delete provisioning[name];
    const err = out(await exec('tail -3 ' + sh(dir + '/provision.log') + ' 2>/dev/null', 8000));
    toast('SQL Server setup failed: ' + (err.split('\n').filter(Boolean).pop() || 'see provision.log'));
    await loadVms();
    return;
  }
  // Still waiting: update the card's status line in place — a full loadVms() here re-read every
  // vm.json + service.status and rebuilt the whole list DOM every 6s for the entire (30–60 min)
  // provisioning run, which made the app crawl on slow phones. Skip the log tail while hidden.
  if (!document.hidden) {
    const last = out(await exec('tail -1 ' + sh(dir + '/provision.log') + ' 2>/dev/null', 6000));
    provisioning[name] = (last ? last.slice(0, 90) : 'Downloading Ubuntu image…');
    const stat = document.querySelector<HTMLElement>('[data-prov="' + name.replace(/"/g, '') + '"]');
    if (stat) stat.textContent = provisioning[name];
    else await loadVms(); // card not on screen (e.g. list never rendered it) — rebuild once
  }
  // Completion still gets detected while hidden (the flag check above), just at a relaxed cadence.
  setTimeout(() => void pollProvision(name, ram, cpus, disk), document.hidden ? 15000 : 6000);
}

async function cancelProvision(name: string) {
  delete provisioning[name];
  await api('service.stop', { id: 'sqlprov:' + name });
  await exec('rm -rf ' + sh(vmDir(name)), 20000);
  toast('Cancelled ' + name + '.');
  await loadVms();
}

// SQL VM status for the card: port 1433 reachable through the hostfwd == the SQL Client can connect.
// Until then, map the newest "JCODE_MSSQL: <phase>" serial token to a human phase or a specific error.
const SQL_ERRORS: Record<string, string> = {
  'net-fail': 'no network in guest', 'apt-key-fail': 'repo key failed', 'apt-update-fail': 'apt update failed',
  'apt-install-fail': 'SQL Server install failed', 'sa-password-rejected-or-setup-fail': 'SA password rejected',
  'not-listening': 'engine not listening', 'bound-loopback-only': 'bound to loopback only', 'disk-too-small': 'guest disk too small',
};
const SQL_PHASES: Record<string, string> = {
  'phase-boot': 'Guest booting (slow under emulation)…', 'phase-repo': 'Adding Microsoft repo…',
  'phase-install': 'Installing SQL Server (~10–25 min)…', 'phase-setup': 'Configuring engine…',
  'phase-verify': 'Verifying…', 'already-done': 'Starting SQL Server…', 'ready': 'Finishing…',
};

async function sqlStatus(name: string): Promise<{ state: string; label: string; cls: string }> {
  const dir = vmDir(name);
  const port = out(await exec("timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null && echo open || echo closed", 6000));
  if (port.indexOf('open') >= 0) return { state: 'ready', label: 'SQL Server ready — connect the SQL Client.', cls: 'ok' };
  const phase = out(await exec(
    'grep -oE "JCODE_MSSQL: [a-z-]+" ' + sh(dir + '/serial.out') + ' 2>/dev/null | tail -1 | sed "s/JCODE_MSSQL: //"', 6000));
  if (SQL_ERRORS[phase]) return { state: 'error', label: 'Setup error: ' + SQL_ERRORS[phase], cls: 'bad' };
  return { state: 'work', label: SQL_PHASES[phase] || 'Booting…', cls: '' };
}

async function watchSql(name: string, el: HTMLElement) {
  if (!document.body.contains(el)) return;
  if (document.hidden) { // don't spend two execs per tick while not visible; retry later
    setTimeout(() => void watchSql(name, el), 10000);
    return;
  }
  const s = await sqlStatus(name);
  el.textContent = s.label;
  el.className = 'sqlstat ' + s.cls;
  if (s.state !== 'ready' && s.state !== 'error' && document.body.contains(el)) {
    setTimeout(() => void watchSql(name, el), 10000);
  }
}

async function openConsole(name: string) {
  await api('workbench.openView', { view: 'console:' + name });
}

// Lightweight transient toast (bottom of the drawer).
let toastTimer: ReturnType<typeof setTimeout> | null = null;
function toast(msg: string) {
  let t = $('toast');
  if (!t) {
    t = document.createElement('div'); t.id = 'toast';
    t.style.cssText = 'position:fixed;left:10px;right:10px;bottom:10px;z-index:60;padding:9px 12px;' +
      'border-radius:8px;background:var(--panel2);border:1px solid var(--line2);color:var(--fg);font-size:12px;box-shadow:0 8px 24px rgba(0,0,0,.4)';
    document.body.appendChild(t);
  }
  t.textContent = msg;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t && t.remove(); }, 3200);
}

// ==========================================================================
// CONSOLE VIEW — interactive serial terminal for one VM (editor tab)
// ==========================================================================
let consoleName = '';
let ptsPath = '';
let pollTimer: ReturnType<typeof setInterval> | null = null;
let lastSerial = '';

// ---- minimal VT/ANSI renderer so the serial console behaves like a real terminal ----
// The serial output carries ANSI escapes (colours) and \r / cursor-move redraws. We read the buffer
// as base64 (so the app's per-line output normalisation can't strip the ESC/\r bytes), decode it, and
// replay it through this tiny emulator: a per-line cell grid with a cursor + SGR state. It handles
// colours, carriage-return/erase-line overwrites, cursor moves and screen clears — enough for boot
// logs and progress bars, without a heavyweight terminal dependency.
const VT_PALETTE = [
  '#1e1e1e', '#cd3131', '#0dbc79', '#e5e510', '#2472c8', '#bc3fbc', '#11a8cd', '#cccccc',
  '#666666', '#f14c4c', '#23d18b', '#f5f543', '#3b8eea', '#d670d6', '#29b8db', '#ffffff',
];
interface VtCell { ch: string; fg: number; bg: number; b: boolean; u: boolean }
const VT_BLANK: VtCell = { ch: ' ', fg: -1, bg: -1, b: false, u: false };

function b64ToStr(b64: string): string {
  const s = b64.trim();
  if (!s) return '';
  try {
    const bin = atob(s);
    const bytes = new Uint8Array(bin.length);
    for (let k = 0; k < bin.length; k++) bytes[k] = bin.charCodeAt(k);
    return new TextDecoder('utf-8', { fatal: false }).decode(bytes);
  } catch { return ''; }
}

function renderAnsi(raw: string): string {
  const lines: VtCell[][] = [[]];
  let row = 0, col = 0;
  let fg = -1, bg = -1, bold = false, ul = false, inv = false;
  const line = (): VtCell[] => { while (lines.length <= row) lines.push([]); return lines[row]; };
  const put = (ch: string) => {
    const ln = line();
    while (ln.length < col) ln.push(VT_BLANK);
    let f = fg; let b = bg;
    if (bold && f >= 0 && f < 8) f += 8;
    if (inv) { const t = f; f = b < 0 ? 0 : b; b = t < 0 ? 7 : t; }
    ln[col] = { ch, fg: f, bg: b, b: bold, u: ul };
    col++;
  };
  const sgr = (ns: number[]) => {
    if (!ns.length) ns = [0];
    for (let k = 0; k < ns.length; k++) {
      const nn = ns[k];
      if (nn === 0) { fg = -1; bg = -1; bold = false; ul = false; inv = false; }
      else if (nn === 1) bold = true;
      else if (nn === 22) bold = false;
      else if (nn === 4) ul = true;
      else if (nn === 24) ul = false;
      else if (nn === 7) inv = true;
      else if (nn === 27) inv = false;
      else if (nn >= 30 && nn <= 37) fg = nn - 30;
      else if (nn === 39) fg = -1;
      else if (nn >= 90 && nn <= 97) fg = nn - 90 + 8;
      else if (nn >= 40 && nn <= 47) bg = nn - 40;
      else if (nn === 49) bg = -1;
      else if (nn >= 100 && nn <= 107) bg = nn - 100 + 8;
      else if (nn === 38 || nn === 48) {
        if (ns[k + 1] === 5) { const cc = ns[k + 2] ?? -1; if (nn === 38) fg = cc >= 0 && cc < 16 ? cc : -1; else bg = cc >= 0 && cc < 16 ? cc : -1; k += 2; }
        else if (ns[k + 1] === 2) k += 4;
      }
    }
  };
  let i = 0;
  const len = raw.length;
  while (i < len) {
    const ch = raw[i];
    if (ch === '\x1b') {
      const nx = raw[i + 1];
      if (nx === '[') {
        let j = i + 2; let params = '';
        while (j < len && /[0-9;?]/.test(raw[j])) { params += raw[j]; j++; }
        const cmd = raw[j] || '';
        const nums = params.replace(/\?/g, '').split(';').map((p) => (p === '' ? 0 : parseInt(p, 10) || 0));
        const p0 = nums[0] || 0;
        if (cmd === 'm') sgr(params ? nums : [0]);
        else if (cmd === 'A') row = Math.max(0, row - (p0 || 1));
        else if (cmd === 'B') { row += p0 || 1; line(); }
        else if (cmd === 'C') col += p0 || 1;
        else if (cmd === 'D') col = Math.max(0, col - (p0 || 1));
        else if (cmd === 'G') col = Math.max(0, (p0 || 1) - 1);
        else if (cmd === 'd') { row = Math.max(0, (p0 || 1) - 1); line(); }
        else if (cmd === 'H' || cmd === 'f') { row = Math.max(0, (nums[0] || 1) - 1); col = Math.max(0, (nums[1] || 1) - 1); line(); }
        else if (cmd === 'J') {
          if (p0 === 2 || p0 === 3) { lines.length = 0; lines.push([]); row = 0; col = 0; }
          else if (p0 === 0) { const ln = line(); if (ln.length > col) ln.length = col; lines.length = row + 1; }
        } else if (cmd === 'K') {
          const ln = line();
          if (p0 === 0) { if (ln.length > col) ln.length = col; }
          else if (p0 === 1) { for (let x = 0; x < col && x < ln.length; x++) ln[x] = VT_BLANK; }
          else if (p0 === 2) ln.length = 0;
        }
        i = j + 1; continue;
      } else if (nx === ']') {
        let j = i + 2;
        while (j < len && raw[j] !== '\x07' && !(raw[j] === '\x1b' && raw[j + 1] === '\\')) j++;
        i = raw[j] === '\x1b' ? j + 2 : j + 1; continue;
      } else { i += 2; continue; }
    } else if (ch === '\n') { row++; col = 0; line(); i++; }
    else if (ch === '\r') { col = 0; i++; }
    else if (ch === '\b') { col = Math.max(0, col - 1); i++; }
    else if (ch === '\t') { col = (Math.floor(col / 8) + 1) * 8; i++; }
    else if (raw.charCodeAt(i) < 32) { i++; }
    else { put(ch); i++; }
  }
  const he = (s: string) => s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] as string));
  const styleOf = (c: VtCell): string => {
    const parts: string[] = [];
    if (c.fg >= 0) parts.push('color:' + VT_PALETTE[c.fg]);
    if (c.bg >= 0) parts.push('background:' + VT_PALETTE[c.bg]);
    if (c.b) parts.push('font-weight:700');
    if (c.u) parts.push('text-decoration:underline');
    return parts.join(';');
  };
  return lines.map((ln) => {
    let html = ''; let run = ''; let cur = '\x00';
    const flush = () => { if (run) { html += cur ? '<span style="' + cur + '">' + he(run) + '</span>' : he(run); run = ''; } };
    for (const c of ln) { const s = styleOf(c); if (s !== cur) { flush(); cur = s; } run += c.ch; }
    flush();
    return html || ' ';
  }).join('\n');
}

async function renderConsole(name: string) {
  consoleName = name;
  document.body.className = 'consolepage';
  $('root').innerHTML =
    '<div class="console">' +
    '<div class="cbar"><span class="cname">▸ ' + esc(name) + '</span>' +
      '<span id="cstatus" class="pill stop"><span class="dot"></span> …</span>' +
      '<button class="btn sm" id="cstart">Start</button>' +
      '<button class="btn sm" id="cclear">Clear</button></div>' +
    '<pre class="term" id="term"></pre>' +
    '<div class="cinput">' +
      '<input id="cin" placeholder="type a command, Enter to send" autocapitalize="none" spellcheck="false">' +
      '<button class="btn sm primary" id="csend">Send</button>' +
      '<div class="keys">' +
        '<button class="btn" data-k="enter">↵</button>' +
        '<button class="btn" data-k="ctrlc">^C</button>' +
        '<button class="btn" data-k="tab">Tab</button>' +
        '<button class="btn" data-k="up">↑</button>' +
      '</div>' +
    '</div></div>';
  const cin = $('cin') as HTMLInputElement;
  $('csend').onclick = () => sendLine();
  cin.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); sendLine(); } };
  $('cclear').onclick = () => { ($('term') as HTMLElement).innerHTML = ''; };
  $('cstart').onclick = () => void startFromConsole();
  document.querySelectorAll<HTMLElement>('.keys [data-k]').forEach((b) => {
    b.onclick = () => sendKey(b.getAttribute('data-k') as string);
  });
  await refreshConsole();
  // 2s cadence (each poll is an exec → a proot spawn — expensive on budget phones); the 250ms
  // follow-up poll in writePts keeps Enter feeling instant. Paused entirely while hidden.
  pollTimer = setInterval(() => { if (!document.hidden) void pollConsole(); }, 2000);
  document.addEventListener('visibilitychange', onConsoleVisibility);
}

// Catch up as soon as the WebView becomes visible again instead of waiting out the interval.
function onConsoleVisibility() {
  if (!document.hidden && consoleName) void pollConsole();
}

async function refreshConsole() {
  ptsPath = out(await exec('cat ' + sh(vmDir(consoleName) + '/serial.pts') + ' 2>/dev/null', 8000));
  const running = await isRunning(consoleName);
  const st = $('cstatus');
  st.className = 'pill ' + (running ? 'run' : 'stop');
  st.innerHTML = '<span class="dot"></span> ' + (running ? 'running' : 'stopped');
  ($('cstart') as HTMLButtonElement).style.display = running ? 'none' : '';
  ($('cin') as HTMLInputElement).disabled = !running || !ptsPath;
  await pollConsole();
}

async function pollConsole() {
  const term = $('term') as HTMLElement;
  // Read as base64 so the app's line-based output normalisation can't strip the ESC / \r bytes we
  // need to render colours and redraws; decode + replay through the VT emulator. Compare the base64
  // itself so an unchanged screen costs no decode and no ANSI re-render (the common idle case).
  const b64 = out(await exec('tail -c 32768 ' + sh(vmDir(consoleName) + '/serial.out') + ' 2>/dev/null | base64 -w0', 8000));
  if (b64 && b64 !== lastSerial) {
    lastSerial = b64;
    const atBottom = term.scrollHeight - term.scrollTop - term.clientHeight < 40;
    term.innerHTML = renderAnsi(b64ToStr(b64));
    if (atBottom) term.scrollTop = term.scrollHeight;
  } else if (!b64 && !lastSerial) {
    term.textContent = '(no serial output yet — start the VM to boot it)';
  }
}

async function writePts(payload: string) {
  if (!ptsPath) { await refreshConsole(); if (!ptsPath) return; }
  await exec(payload + ' > ' + sh(ptsPath), 8000);
  setTimeout(() => void pollConsole(), 250);
}

function sendLine() {
  const cin = $('cin') as HTMLInputElement;
  const line = cin.value;
  cin.value = '';
  void writePts("printf '%s\\n' " + sh(line));
}

function sendKey(k: string) {
  if (k === 'enter') void writePts("printf '\\n'");
  else if (k === 'ctrlc') void writePts("printf '\\003'");
  else if (k === 'tab') void writePts("printf '\\t'");
  else if (k === 'up') void writePts("printf '\\033[A'");
}

async function startFromConsole() {
  await startVm(consoleName);
  await refreshConsole();
}

// ==========================================================================
// ROUTER
// ==========================================================================
async function boot() {
  const hash = location.hash.replace(/^#/, '');
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  document.removeEventListener('visibilitychange', onConsoleVisibility);
  lastSerial = '';
  if (hash.indexOf('console:') === 0) {
    await renderConsole(hash.slice('console:'.length));
  } else {
    await renderDrawer();
  }
}

void boot();

package dev.blamspot.jcode.ext.vm

import dev.blamspot.jcode.ext.api.NativeHost

/**
 * The one-click SQL Server VM.
 *
 * Ubuntu 22.04 "jammy" cloud image — the newest Ubuntu MS SQL Server 2022 officially supports, and
 * one that ships cloud-init and a serial console. There is no installer to sit through: the image
 * boots pre-installed and a cloud-init NoCloud seed, attached as a read-only disk, installs SQL
 * Server on first boot and narrates itself to the serial line, which is where [status] reads from.
 */
internal object SqlServer {

    const val IMG_URL = "https://cloud-images.ubuntu.com/releases/jammy/release/ubuntu-22.04-server-cloudimg-amd64.img"
    const val BASE_DIR = Vm.DIR + "/_base"
    const val BASE_IMG = "$BASE_DIR/jammy-amd64.img"

    /**
     * Prefilled SA password. SQL Server refuses a passwordless SA, and this passes its complexity
     * policy — it mirrors the SQL Client extension's default so a fresh setup connects with no typing.
     */
    const val DEFAULT_PASSWORD = "JCodeVm2026."

    /**
     * cloud-init user-data that installs SQL Server 2022 unattended on first boot.
     *
     * The install is an idempotent, sentinel-gated systemd oneshot rather than `runcmd`, so it heals
     * itself across reboots and mid-install kills. Progress is echoed to `/dev/ttyS0` as
     * `JCODE_MSSQL: <phase>` tokens, which the host reads back out of `serial.out`.
     *
     * `<SA_PASSWORD>` and `<MEM_LIMIT>` are substituted before writing. Every `$` here belongs to the
     * guest's shell, so each one is escaped out of Kotlin's own interpolation.
     */
    private val CLOUD_INIT = """
        #cloud-config
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
              exec > >(tee -a "${'$'}LOG" /dev/ttyS0) 2>&1
              say() { { echo "JCODE_MSSQL: ${'$'}*" | timeout 2 tee /dev/ttyS0 >/dev/null; } 2>/dev/null || true; echo "JCODE_MSSQL: ${'$'}*"; }
              [ -f "${'$'}SENTINEL" ] && { say already-done; exit 0; }
              SA_PASSWORD='<SA_PASSWORD>'
              say phase-boot
              for i in ${'$'}(seq 1 60); do getent hosts packages.microsoft.com >/dev/null 2>&1 && break; sleep 5; done
              getent hosts packages.microsoft.com >/dev/null 2>&1 || { say net-fail; exit 1; }
              systemctl stop unattended-upgrades.service apt-daily.service apt-daily-upgrade.service 2>/dev/null || true
              systemctl stop apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || true
              for i in ${'$'}(seq 1 120); do fuser /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock /var/cache/apt/archives/lock >/dev/null 2>&1 || break; sleep 5; done
              export DEBIAN_FRONTEND=noninteractive
              retry() { local n=0; until "${'$'}@"; do n=${'$'}((n+1)); [ ${'$'}n -ge 5 ] && return 1; sleep 10; done; }
              avail=${'$'}(df --output=avail -BG / 2>/dev/null | awk 'NR==2{print ${'$'}1+0}')
              [ "${'$'}avail" -lt 8 ] && { say disk-too-small; exit 1; }
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
                MSSQL_PID=Developer MSSQL_SA_PASSWORD="${'$'}SA_PASSWORD" MSSQL_TCP_PORT=1433 MSSQL_MEMORY_LIMIT_MB=<MEM_LIMIT> /opt/mssql/bin/mssql-conf -n setup accept-eula || { say sa-password-rejected-or-setup-fail; exit 1; }
              fi
              systemctl enable mssql-server
              systemctl start mssql-server
              say phase-verify
              for i in ${'$'}(seq 1 60); do timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null && break; sleep 5; done
              if command -v ss >/dev/null 2>&1; then L=${'$'}(ss -ltn 2>/dev/null | grep ':1433' || true); case "${'$'}L" in *0.0.0.0:1433*) : ;; *127.0.0.1:1433*) say bound-loopback-only ;; esac; fi
              timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null || { say not-listening; exit 1; }
              touch "${'$'}SENTINEL"
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
              say() { { echo "JCODE_MSSQL: ${'$'}*" | timeout 2 tee /dev/ttyS0 >/dev/null; } 2>/dev/null || true; echo "JCODE_MSSQL: ${'$'}*"; }
              say engine-starting
              for i in ${'$'}(seq 1 360); do
                if timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null; then
                  L=${'$'}(ss -ltn 2>/dev/null | grep ':1433' | awk '{print ${'$'}4}' | paste -sd' ' -)
                  [ -z "${'$'}L" ] && L=0.0.0.0:1433
                  say "SQL-SERVER-READY listening on ${'$'}L"
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
    """.trimIndent()

    /** What the guest's own tokens mean when something went wrong. */
    private val ERRORS = mapOf(
        "net-fail" to "no network in guest",
        "apt-key-fail" to "repo key failed",
        "apt-update-fail" to "apt update failed",
        "apt-install-fail" to "SQL Server install failed",
        "sa-password-rejected-or-setup-fail" to "SA password rejected",
        "not-listening" to "engine not listening",
        "bound-loopback-only" to "bound to loopback only",
        "disk-too-small" to "guest disk too small",
    )

    private val PHASES = mapOf(
        "phase-boot" to "Guest booting (slow under emulation)…",
        "phase-repo" to "Adding Microsoft repo…",
        "phase-install" to "Installing SQL Server (~10–25 min)…",
        "phase-setup" to "Configuring engine…",
        "phase-verify" to "Verifying…",
        "already-done" to "Starting SQL Server…",
        "ready" to "Finishing…",
    )

    enum class State { Working, Ready, Error }

    data class Status(val state: State, val label: String)

    /**
     * Where the SQL VM has got to, read from what the guest said on its serial line.
     *
     * Readiness is the guest's own announcement rather than a probe through the emulated NAT: the
     * guest emits `SQL-SERVER-READY` once *it* has confirmed 1433 accepts, and reading a file cannot
     * stall the poller the way the hostfwd probe could.
     */
    suspend fun status(host: NativeHost, name: String): Status {
        val log = Vm.out(
            host,
            "grep -aoE \"JCODE_MSSQL: [A-Za-z-]+\" ${Vm.sh(Vm.dirOf(name) + "/serial.out")} 2>/dev/null",
            timeoutMs = 6_000,
        )
        val tokens = log.lineSequence()
            .map { it.removePrefix("JCODE_MSSQL: ").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if ("bound-loopback-only" in tokens) {
            return Status(State.Error, "Setup error: " + ERRORS.getValue("bound-loopback-only"))
        }
        if ("SQL-SERVER-READY" in tokens || "ready" in tokens) {
            return Status(State.Ready, "SQL Server ready — connect the SQL Client.")
        }
        val phase = tokens.lastOrNull().orEmpty()
        ERRORS[phase]?.let { return Status(State.Error, "Setup error: $it") }
        return Status(State.Working, PHASES[phase] ?: "Booting…")
    }

    /** Whether anything else already holds 1433 — QEMU's user-net cannot forward it if so. */
    suspend fun portBusy(host: NativeHost): Boolean = Vm.out(
        host,
        "timeout 1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1433' 2>/dev/null && echo BUSY || echo FREE",
        timeoutMs = 6_000,
    ).contains("BUSY")

    /**
     * Starts the one-time provisioning: fetch the cloud image, build the disk from it, and make the
     * seed. It runs as a service so the panel stays live through the ~700 MB download, and touches a
     * flag either way, which is what [pollFlag] watches. The download lands on `.tmp` and is moved,
     * so a killed download never leaves a truncated backing file behind.
     */
    suspend fun provision(host: NativeHost, name: String, password: String, ram: Int, cpus: Int, disk: Int) {
        val dir = Vm.dirOf(name)
        // Cap SQL Server's memory so the guest OS keeps ~1 GB even at the 2 GB minimum.
        val memLimit = maxOf(1024, ram - 1536)
        val userData = CLOUD_INIT
            .replace("<SA_PASSWORD>", password)
            .replace("<MEM_LIMIT>", memLimit.toString())
        val metaData = "instance-id: jcode-mssql-$name-${System.currentTimeMillis()}\nlocal-hostname: mssql-vm"

        host.exec("mkdir -p ${Vm.sh(dir)} ${Vm.sh(BASE_DIR)}", timeoutMs = 10_000)
        Vm.writeFile(host, "$dir/user-data", userData)
        Vm.writeFile(host, "$dir/meta-data", metaData)
        // Persist the shape so a panel that is rebuilt mid-run (a rotation, say) can re-attach and
        // still write the right vm.json when it finishes — see [resumable].
        Vm.writeFile(host, "$dir/.prov.json", """{"ram":$ram,"cpus":$cpus,"disk":$disk}""", 8_000)

        val cmd = "export DEBIAN_FRONTEND=noninteractive; " +
            "rm -f ${Vm.sh("$dir/.provision-ok")} ${Vm.sh("$dir/.provision-fail")}; " +
            "( (command -v qemu-img >/dev/null 2>&1 && command -v cloud-localds >/dev/null 2>&1 || " +
            "(apt-get update && apt-get install -y qemu-utils cloud-image-utils genisoimage xorriso curl)) " +
            "&& (test -f ${Vm.sh(BASE_IMG)} || (curl -fL --retry 3 -o ${Vm.sh("$BASE_IMG.tmp")} ${Vm.sh(IMG_URL)} " +
            "&& mv ${Vm.sh("$BASE_IMG.tmp")} ${Vm.sh(BASE_IMG)})) " +
            "&& qemu-img create -f qcow2 -F qcow2 -b ${Vm.sh(BASE_IMG)} ${Vm.sh("$dir/disk.qcow2")} ${disk}G " +
            "&& cd ${Vm.sh(dir)} && (cloud-localds seed.img user-data meta-data || " +
            "xorriso -as mkisofs -V CIDATA -J -r -o seed.img user-data meta-data || " +
            "genisoimage -V CIDATA -J -r -o seed.img user-data meta-data) " +
            ") > ${Vm.sh("$dir/provision.log")} 2>&1 && touch ${Vm.sh("$dir/.provision-ok")} " +
            "|| touch ${Vm.sh("$dir/.provision-fail")}"
        host.serviceStart("sqlprov:$name", cmd)
    }

    /** OK / FAIL / WAIT, from the flags the provisioning service leaves behind. */
    suspend fun pollFlag(host: NativeHost, name: String): String {
        val dir = Vm.dirOf(name)
        return Vm.out(
            host,
            "if [ -f ${Vm.sh("$dir/.provision-ok")} ]; then echo OK; " +
                "elif [ -f ${Vm.sh("$dir/.provision-fail")} ]; then echo FAIL; else echo WAIT; fi",
            timeoutMs = 8_000,
        )
    }

    suspend fun logTail(host: NativeHost, name: String, lines: Int): String =
        Vm.out(host, "tail -$lines ${Vm.sh(Vm.dirOf(name) + "/provision.log")} 2>/dev/null", 6_000)

    /** The config a finished provisioning run writes: 1433 forwarded for the client, 22 for a shell. */
    fun finishedCfg(name: String, ram: Int, cpus: Int, disk: Int) = VmCfg(
        name = name,
        kind = "sqlserver",
        ram = ram,
        cpus = cpus,
        disk = disk,
        iso = "",
        seed = "seed.img",
        baseImage = "jammy-amd64.img",
        forwards = listOf(Forward(1433, 1433), Forward(22, 2222)),
    )

    /**
     * Provisioning runs that are still on disk but not tracked by this panel — the case being a panel
     * rebuilt while the background service kept downloading. A directory with a `.prov.json` and no
     * `vm.json` is mid-provision; it is resumed only if its service is still alive or it has already
     * finished, because resuming a run the app killed would poll forever.
     */
    suspend fun resumable(host: NativeHost): List<Triple<String, IntArray, Boolean>> {
        val names = Vm.out(
            host,
            "for d in ${Vm.DIR}/*/; do n=\$(basename \"\$d\"); [ \"\$n\" = \"_base\" ] && continue; " +
                "[ -f \"\$d/.prov.json\" ] && [ ! -f \"\$d/vm.json\" ] && [ ! -f \"\$d/.provision-fail\" ] " +
                "&& echo \"\$n\"; done 2>/dev/null",
            timeoutMs = 10_000,
        ).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        val out = mutableListOf<Triple<String, IntArray, Boolean>>()
        for (name in names) {
            val ok = Vm.out(host, "test -f ${Vm.sh(Vm.dirOf(name) + "/.provision-ok")} && echo yes", 6_000)
                .trim() == "yes"
            val alive = host.serviceRunning("sqlprov:$name")
            if (!ok && !alive) continue
            val meta = Vm.out(host, "cat ${Vm.sh(Vm.dirOf(name) + "/.prov.json")} 2>/dev/null", 6_000)
            val shape = runCatching {
                val o = org.json.JSONObject(meta)
                intArrayOf(o.optInt("ram", 4096), o.optInt("cpus", 2), o.optInt("disk", 30))
            }.getOrElse { intArrayOf(4096, 2, 30) }
            out += Triple(name, shape, ok)
        }
        return out
    }

    suspend fun cancel(host: NativeHost, name: String) {
        host.serviceStop("sqlprov:$name")
        host.exec("rm -rf ${Vm.sh(Vm.dirOf(name))}", timeoutMs = 20_000)
    }

    /** The SA password rules SQL Server enforces, checked before a 30-minute install finds out. */
    fun validatePassword(p: String, confirm: String): String? {
        if (p.length < 8 || p.length > 128) return "SA password must be 8–128 characters."
        val categories = listOf(
            p.any { it.isUpperCase() },
            p.any { it.isLowerCase() },
            p.any { it.isDigit() },
            p.any { !it.isLetterOrDigit() },
        ).count { it }
        if (categories < 3) return "SA password needs 3 of: uppercase, lowercase, digit, symbol."
        if (p.contains('\'')) return "SA password cannot contain a single quote ( ' )."
        if (p.contains("sa", ignoreCase = true)) return "SA password cannot contain \"sa\"."
        if (p != confirm) return "Passwords do not match."
        return null
    }
}

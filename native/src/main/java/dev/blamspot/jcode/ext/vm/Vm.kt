package dev.blamspot.jcode.ext.vm

import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.delay
import org.json.JSONObject

/** A guest→host port mapping, as QEMU's user-net `hostfwd` wants it. */
internal data class Forward(val guest: Int, val host: Int)

/**
 * The two lines into a running machine.
 *
 * [Serial] is the guest talking: its boot log, its getty, its shell. [Monitor] is QEMU talking about
 * the guest — and it still answers after the guest has stopped, which is exactly when you need to
 * ask what state the machine is in.
 *
 * Both are PTYs read by a service into a file, because that is the only shape that survives here: a
 * reader forked from an exec would be reaped the moment the exec returned.
 */
internal enum class Stream(
    val label: String,
    val outFile: String,
    val ptsFile: String,
    private val servicePrefix: String,
) {
    Serial("Serial", "serial.out", "serial.pts", "vmread"),
    Monitor("Monitor", "monitor.out", "monitor.pts", "vmmon"),
    ;

    fun service(vm: String): String = "$servicePrefix:$vm"
}

/**
 * One VM, as `vm.json` on disk records it.
 *
 * [kind] `"sqlserver"` marks the preset that boots a cloud image with a cloud-init seed attached
 * instead of an installer ISO — see [SqlServer].
 */
internal data class VmCfg(
    val name: String,
    val ram: Int = 0,
    val cpus: Int = 0,
    val disk: Int = 0,
    val iso: String = "",
    val forwards: List<Forward> = emptyList(),
    val kind: String? = null,
    val seed: String? = null,
    val baseImage: String? = null,
) {
    val isSqlServer: Boolean get() = kind == "sqlserver"

    /** What the card says under the name: sizes, then the forwards, then whether an ISO is attached. */
    fun summary(): String = buildString {
        append(if (ram > 0) ram else "?").append(" MB · ")
        append(if (cpus > 0) cpus else "?").append(" CPU · ")
        append(if (disk > 0) disk else "?").append(" GB · ")
        append(if (forwards.isEmpty()) "no port forwards" else forwards.joinToString(", ") { "${it.guest}→${it.host}" })
        if (iso.isNotBlank()) append(" · ISO")
    }

    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("ram", ram)
        put("cpus", cpus)
        put("disk", disk)
        put("iso", iso)
        put("forwards", org.json.JSONArray().apply {
            forwards.forEach { put(JSONObject().put("guest", it.guest).put("host", it.host)) }
        })
        kind?.let { put("kind", it) }
        seed?.let { put("seed", it) }
        baseImage?.let { put("baseImage", it) }
    }.toString(2)
}

/**
 * The VM store, and everything done to it through the runtime's shell.
 *
 * **A VM is a service, never a backgrounded exec.** proot's `--kill-on-exit` reaps a launcher's
 * children the moment it returns, so a plain `qemu … &` dies with the exec that started it.
 * [NativeHost.serviceStart] holds the process for the session instead, which is also what makes
 * "running" answerable: the service is alive, or it is not.
 */
internal object Vm {

    const val DIR = "/root/vms"

    fun dirOf(name: String): String = "$DIR/$name"

    /** POSIX single-quoting, so a name or a path can never be read as shell. */
    fun sh(v: Any): String = "'" + v.toString().replace("'", "'\\''") + "'"

    fun validName(n: String): Boolean = n.isNotEmpty() && n.all { it.isLetterOrDigit() || it == '-' }

    suspend fun out(host: NativeHost, command: String, timeoutMs: Long = 60_000): String {
        val r = host.exec(command, timeoutMs = timeoutMs)
        return (r.stdout + r.stderr).trimEnd()
    }

    suspend fun exists(host: NativeHost, path: String): Boolean =
        out(host, "test -e ${sh(path)} && echo yes || echo no", 8_000).contains("yes")

    /** Whether the runtime has both halves of QEMU, and which version, for the status pill. */
    suspend fun qemuVersion(host: NativeHost): String? {
        val sys = out(host, "command -v qemu-system-x86_64", 8_000)
        val img = out(host, "command -v qemu-img", 8_000)
        if (sys.isBlank() || img.isBlank()) return null
        return out(host, "qemu-system-x86_64 --version | head -1", 8_000).ifBlank { "qemu-system-x86_64" }
    }

    /** The VMs on disk, newest config wins; `_base` is the shared cloud image, not a machine. */
    suspend fun list(host: NativeHost): List<String> =
        out(host, "ls -1 $DIR/*/vm.json 2>/dev/null", 10_000)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removeSuffix("/vm.json").substringAfterLast('/') }
            .toList()

    suspend fun readCfg(host: NativeHost, name: String): VmCfg {
        val raw = out(host, "cat ${sh(dirOf(name) + "/vm.json")} 2>/dev/null", 8_000)
        return parseCfg(name, raw)
    }

    fun parseCfg(name: String, raw: String): VmCfg = runCatching {
        val o = JSONObject(raw)
        val fwds = mutableListOf<Forward>()
        o.optJSONArray("forwards")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                fwds += Forward(f.optInt("guest"), f.optInt("host"))
            }
        }
        VmCfg(
            name = o.optString("name", name),
            ram = o.optInt("ram"),
            cpus = o.optInt("cpus"),
            disk = o.optInt("disk"),
            iso = o.optString("iso", ""),
            forwards = fwds,
            kind = o.optString("kind", "").ifBlank { null },
            seed = o.optString("seed", "").ifBlank { null },
            baseImage = o.optString("baseImage", "").ifBlank { null },
        )
    }.getOrElse { VmCfg(name) }

    suspend fun isRunning(host: NativeHost, name: String): Boolean = host.serviceRunning("vm:$name")

    /** Writes a file through a quoted heredoc, so nothing in [content] is expanded on the way in. */
    suspend fun writeFile(host: NativeHost, path: String, content: String, timeoutMs: Long = 10_000) {
        host.exec("cat > ${sh(path)} <<'JCODE_EOF'\n$content\nJCODE_EOF", timeoutMs = timeoutMs)
    }

    fun parseForwards(s: String): List<Forward> =
        s.split(',').mapNotNull { part ->
            val bits = part.trim().split(':')
            if (bits.size != 2) return@mapNotNull null
            val g = bits[0].trim().toIntOrNull() ?: return@mapNotNull null
            val h = bits[1].trim().toIntOrNull() ?: return@mapNotNull null
            if (g > 0 && h > 0) Forward(g, h) else null
        }

    /** Creates the directory and the qcow2 backing it. Returns null on success, else what failed. */
    suspend fun create(host: NativeHost, cfg: VmCfg): String? {
        val dir = dirOf(cfg.name)
        host.exec("mkdir -p ${sh(dir)}", timeoutMs = 10_000)
        val mk = host.exec("qemu-img create -f qcow2 ${sh("$dir/disk.qcow2")} ${cfg.disk}G", timeoutMs = 300_000)
        if (mk.exitCode != 0) return (mk.stdout + mk.stderr).trimEnd().ifBlank { "error" }
        writeFile(host, "$dir/vm.json", cfg.toJson())
        return null
    }

    /**
     * Launches QEMU under a service and attaches its serial line.
     *
     * The two paths differ on purpose. A cloud-image VM (the SQL preset) is deliberately conservative:
     * SQL Server's engine is SIMD- and atomic-heavy, and it crashes under the aggressive one — `-cpu
     * max` exposes AVX-512, and MTTCG mis-orders atomics. Single-threaded TCG emulates memory ordering
     * correctly, and `Westmere` gives the SSE4.2 baseline SQL needs plus AES-NI, while staying pre-AVX.
     *
     * Returns a line for the snackbar.
     */
    suspend fun start(host: NativeHost, name: String): String {
        val dir = dirOf(name)
        val cfg = readCfg(host, name)
        val fwds = cfg.forwards.joinToString("") { ",hostfwd=tcp::${it.host}-:${it.guest}" }
        // QEMU runs in the FOREGROUND of the service so it survives; its stdout (including the PTY
        // paths it prints) goes to the log, which is where both lines are found afterwards.
        //
        // Two PTYs, because they answer different questions. The serial line is the guest talking:
        // its boot log, its getty, its shell. The monitor is QEMU talking about the guest, and it is
        // the only way to ask a machine that has stopped responding what state it is in, to power it
        // down the way pressing the button would, or to pause it. A guest that has wedged says
        // nothing on its serial line and still answers `info status` on the monitor.
        val tail = " -netdev user,id=n0" + fwds + " -device virtio-net,netdev=n0" +
            " -display none -serial pty -monitor pty -pidfile " + sh("$dir/qemu.pid") +
            " >" + sh("$dir/qemu-stdout.log") + " 2>&1"
        val q = if (cfg.isSqlServer || cfg.seed != null) {
            "qemu-system-x86_64 -accel tcg -machine q35 -cpu Westmere" +
                " -m ${if (cfg.ram > 0) cfg.ram else 4096} -smp ${if (cfg.cpus > 0) cfg.cpus else 2}" +
                " -drive file=" + sh("$dir/disk.qcow2") + ",if=virtio,format=qcow2" +
                " -drive file=" + sh("$dir/" + (cfg.seed ?: "seed.img")) + ",if=virtio,format=raw,readonly=on" +
                " -device virtio-rng-pci" + tail
        } else {
            buildString {
                append("qemu-system-x86_64 -accel tcg -m ${if (cfg.ram > 0) cfg.ram else 2048}")
                append(" -smp ${if (cfg.cpus > 0) cfg.cpus else 2}")
                append(" -drive file=").append(sh("$dir/disk.qcow2")).append(",if=virtio")
                if (cfg.iso.isNotBlank()) append(" -cdrom ").append(sh(cfg.iso)).append(" -boot d")
                append(tail)
            }
        }
        // Reap anything still holding this disk first: `service.stop` marks a service stopped before
        // the OS has reaped QEMU, and an app force-stop leaves an orphan — either way the qcow2 write
        // lock survives and the new QEMU dies with `Failed to get "write" lock`.
        reap(host, "$dir/disk.qcow2", force = true)
        host.exec(
            ": > ${sh("$dir/serial.out")}; : > ${sh("$dir/monitor.out")}; " +
                "rm -f ${sh("$dir/serial.pts")} ${sh("$dir/monitor.pts")}",
            timeoutMs = 8_000,
        )
        if (!host.serviceStart("vm:$name", q)) return "Start failed: service error"

        var serial = ""
        var monitor = ""
        repeat(15) {
            if (serial.isNotBlank() && monitor.isNotBlank()) return@repeat
            delay(400)
            val log = out(host, "cat ${sh("$dir/qemu-stdout.log")} 2>/dev/null", timeoutMs = 6_000)
            if (serial.isBlank()) serial = ptyFor(log, "serial0")
            if (monitor.isBlank()) monitor = ptyFor(log, "compat_monitor0")
        }
        Stream.entries.forEach { stream ->
            val pts = if (stream == Stream.Serial) serial else monitor
            if (pts.isBlank()) return@forEach
            host.exec("printf \"%s\" ${sh(pts)} > ${sh("$dir/${stream.ptsFile}")}", timeoutMs = 6_000)
            host.serviceStart(stream.service(name), "cat ${sh(pts)} >> ${sh("$dir/${stream.outFile}")}")
        }
        return when {
            serial.isNotBlank() -> "Started $name — open the console."
            isRunning(host, name) -> "Started $name (serial not attached — check the log)."
            else -> {
                val err = out(host, "tail -3 ${sh("$dir/qemu-stdout.log")} 2>/dev/null", 6_000)
                "QEMU exited: " + err.ifBlank { "see qemu-stdout.log" }
            }
        }
    }

    /**
     * The PTY QEMU gave one of its character devices.
     *
     * It announces each as `char device redirected to /dev/pts/3 (label serial0)`, and matching on
     * the label rather than on the first path in the log is the whole point: with a serial line and
     * a monitor there are two of them, and which one QEMU opens first is not ours to assume.
     */
    private fun ptyFor(log: String, label: String): String =
        log.lineSequence()
            .firstOrNull { it.contains("(label $label)") }
            ?.let { PTY_PATH.find(it)?.value }
            .orEmpty()

    private val PTY_PATH = Regex("""/dev/pts/\d+""")

    /**
     * Signals the QEMU still holding [disk] until nothing answers.
     *
     * Stopping the service is not enough on its own — QEMU is orphaned from the service's shell, which
     * is why a soft Stop looked like it did nothing. Inside the proot, `/proc` is the bind-mounted host
     * one, so QEMU is visible there and runs as the app's own uid, which makes the signal permitted.
     * Soft stop sends TERM so the qcow2 is flushed, then KILL if that did not take.
     */
    suspend fun reap(host: NativeHost, disk: String, force: Boolean) {
        fun sweep(sig: String) =
            "for p in /proc/[0-9]*/cmdline; do [ -r \"\$p\" ] || continue; " +
                "tr \"\\000\" \" \" < \"\$p\" 2>/dev/null | grep -qF ${sh(disk)} || continue; " +
                "pid=\${p%/cmdline}; kill -$sig \"\${pid##*/}\" 2>/dev/null; f=1; done"
        host.exec(
            "n=0; while [ \$n -lt 12 ]; do f=0; ${sweep(if (force) "KILL" else "TERM")}; " +
                "[ \$f -eq 0 ] && break; sleep 0.5; n=\$((n+1)); done",
            timeoutMs = 20_000,
        )
        // TERM did not take — force it, so Stop always stops.
        if (!force) host.exec("f=0; ${sweep("KILL")}", timeoutMs = 12_000)
    }

    suspend fun stop(host: NativeHost, name: String, force: Boolean) {
        val dir = dirOf(name)
        Stream.entries.forEach { host.serviceStop(it.service(name)) }
        host.serviceStop("vm:$name")
        reap(host, "$dir/disk.qcow2", force)
        val ptsFiles = Stream.entries.joinToString(" ") { sh("$dir/${it.ptsFile}") }
        host.exec("rm -f ${sh("$dir/qemu.pid")} $ptsFiles", timeoutMs = 8_000)
        delay(300)
    }

    /** The PTY behind one of a machine's lines, as recorded when it started. Blank if not attached. */
    suspend fun ptsOf(host: NativeHost, name: String, stream: Stream): String =
        out(host, "cat ${sh("${dirOf(name)}/${stream.ptsFile}")} 2>/dev/null", 8_000)

    /**
     * Sends one line to a machine's line and lets the reply arrive the usual way.
     *
     * Nothing is read back here: the reader service is already streaming that PTY into its file, and
     * the page is already polling it. Reading the reply here as well would race that service for the
     * same bytes and each would get half of them.
     */
    suspend fun send(host: NativeHost, name: String, stream: Stream, payload: String): Boolean {
        val pts = ptsOf(host, name, stream)
        if (pts.isBlank()) return false
        host.exec("$payload > ${sh(pts)}", timeoutMs = 8_000)
        return true
    }

    suspend fun delete(host: NativeHost, name: String) {
        Stream.entries.forEach { host.serviceStop(it.service(name)) }
        host.serviceStop("vm:$name")
        host.exec("rm -rf ${sh(dirOf(name))}", timeoutMs = 20_000)
    }
}

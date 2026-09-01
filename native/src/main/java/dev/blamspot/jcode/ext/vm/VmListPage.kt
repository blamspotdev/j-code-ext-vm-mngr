package dev.blamspot.jcode.ext.vm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ControlSize
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.ManagerNoticeCard
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One machine as the list knows it: what is on disk, and whether its service is up. */
private data class VmRow(val cfg: VmCfg, val running: Boolean)

/**
 * The left-drawer VM panel: what QEMU can run here, the machines that exist, and the two ways to
 * make another one.
 */
@Composable
internal fun VmListPage(host: NativeHost, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var qemu by remember { mutableStateOf<String?>(null) }
    var qemuChecked by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<VmRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** Names still being provisioned, and the last line of their log. These have no `vm.json` yet, so
     *  they cannot come from [Vm.list] — they are carried here until the run finishes. */
    val provisioning = remember { mutableStateMapOf<String, String>() }
    var dialog by remember { mutableStateOf<VmDialog?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    fun reload() { reloadKey++ }

    LaunchedEffect(reloadKey) {
        loading = true
        qemu = Vm.qemuVersion(host)
        qemuChecked = true
        rows = Vm.list(host).map { name ->
            val cfg = Vm.readCfg(host, name)
            VmRow(cfg, Vm.isRunning(host, name))
        }
        loading = false
    }

    // Re-attach to any provisioning still running from before this panel existed — a rotation, or the
    // drawer being closed and reopened, leaves the service downloading with nobody watching it.
    LaunchedEffect(Unit) {
        SqlServer.resumable(host).forEach { (name, shape, _) ->
            if (name !in provisioning) {
                provisioning[name] = "Resuming setup…"
                scope.launch { watchProvision(host, name, shape, provisioning, ::reload) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Built like Source Control's, which is the panel this one most resembles: the header block
        // carries its own padding and the rule that closes it spans the drawer, rather than sitting
        // inside the body's inset. Not the manager header the Toolchains and Extensions panels use —
        // that one is built around a catalog of installable things, and its "N installed" line and
        // search field say the wrong thing about a handful of machines you made yourself.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.ms, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Virtual Machines",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                Box(
                    modifier = Modifier.size(ControlSize.iconButtonSm),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.sm),
                        strokeWidth = StrokeWidth.thick,
                    )
                }
            } else {
                IconButton(
                    onClick = { reload() },
                    modifier = Modifier.size(ControlSize.iconButtonSm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(IconSize.sm),
                    )
                }
            }
        }
        HorizontalDivider(
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(Space.ms),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
        StatusPill(qemu, qemuChecked)

        if (qemuChecked && qemu == null) {
            ManagerNoticeCard(
                title = "QEMU is required",
                message = "VM Manager needs QEMU to run virtual machines. Install " +
                    "x86 Virtualization (QEMU) from Toolchains, then refresh.",
            )
        }

        var menu by remember { mutableStateOf(false) }
        SplitButton(
            label = "Create VM",
            onClick = { dialog = VmDialog.Create },
            enabled = qemu != null,
            menuOpen = menu,
            onMenuOpen = { menu = true },
            onMenuDismiss = { menu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Set up SQL Server…", style = MaterialTheme.typography.bodySmall) },
                onClick = { menu = false; dialog = VmDialog.SqlServer },
            )
        }

        Text(
            "Machines",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            when {
                loading && rows.isEmpty() && provisioning.isEmpty() -> Hint("Loading…")
                rows.isEmpty() && provisioning.isEmpty() ->
                    Hint("No VMs yet." + if (qemu != null) " Create one above." else "")
            }
            provisioning.forEach { (name, line) ->
                ProvisioningCard(name, line) {
                    scope.launch {
                        provisioning.remove(name)
                        SqlServer.cancel(host, name)
                        host.snackbar("Cancelled $name.")
                        reload()
                    }
                }
            }
            rows.forEach { row ->
                VmCard(
                    host = host,
                    row = row,
                    onStart = {
                        scope.launch {
                            host.snackbar("Starting ${row.cfg.name}…")
                            host.snackbar(Vm.start(host, row.cfg.name))
                            reload()
                        }
                    },
                    onStop = { force ->
                        scope.launch {
                            host.snackbar((if (force) "Force-stopping " else "Stopping ") + row.cfg.name + "…")
                            Vm.stop(host, row.cfg.name, force)
                            reload()
                        }
                    },
                    onConsole = { host.openView("console:${row.cfg.name}", "${row.cfg.name} console") },
                    onMonitor = { host.openView("monitor:${row.cfg.name}", "${row.cfg.name} monitor") },
                    onDelete = { dialog = VmDialog.ConfirmDelete(row.cfg.name) },
                )
            }
        }
        }
    }

    when (val d = dialog) {
        null -> Unit
        VmDialog.Create -> CreateVmDialog(
            onDismiss = { dialog = null },
            onCreate = { cfg ->
                dialog = null
                scope.launch {
                    if (Vm.exists(host, Vm.dirOf(cfg.name))) {
                        host.snackbar("A VM named \"${cfg.name}\" already exists.")
                        return@launch
                    }
                    host.snackbar("Creating disk for \"${cfg.name}\"…")
                    val err = Vm.create(host, cfg)
                    host.snackbar(err?.let { "Disk creation failed: $it" } ?: "VM \"${cfg.name}\" created.")
                    reload()
                }
            },
        )
        VmDialog.SqlServer -> SqlServerDialog(
            onDismiss = { dialog = null },
            onSetUp = { name, password, ram, cpus, disk ->
                dialog = null
                scope.launch {
                    if (Vm.exists(host, Vm.dirOf(name))) {
                        host.snackbar("A VM named \"$name\" already exists.")
                        return@launch
                    }
                    if (SqlServer.portBusy(host)) {
                        host.snackbar("Port 1433 is already in use — stop the other SQL VM first.")
                        return@launch
                    }
                    SqlServer.provision(host, name, password, ram, cpus, disk)
                    provisioning[name] = "Downloading Ubuntu image…"
                    host.snackbar("Setting up SQL Server VM \"$name\"…")
                    reload()
                    watchProvision(host, name, intArrayOf(ram, cpus, disk), provisioning, ::reload)
                }
            },
        )
        is VmDialog.ConfirmDelete -> ConfirmDeleteDialog(
            name = d.name,
            onDismiss = { dialog = null },
            onDelete = {
                dialog = null
                scope.launch {
                    Vm.delete(host, d.name)
                    host.snackbar("Deleted ${d.name}.")
                    reload()
                }
            },
        )
    }
}

/**
 * Follows one provisioning run to its end.
 *
 * Only the status line is rewritten while it waits — a full reload here re-read every `vm.json` and
 * asked after every service, once every six seconds, for the half-hour the download can take.
 */
private suspend fun watchProvision(
    host: NativeHost,
    name: String,
    shape: IntArray,
    provisioning: MutableMap<String, String>,
    reload: () -> Unit,
) {
    val (ram, cpus, disk) = Triple(shape[0], shape[1], shape[2])
    while (name in provisioning) {
        val flag = SqlServer.pollFlag(host, name)
        when {
            flag.contains("OK") -> {
                provisioning.remove(name)
                val cfg = SqlServer.finishedCfg(name, ram, cpus, disk)
                Vm.writeFile(host, "${Vm.dirOf(name)}/vm.json", cfg.toJson())
                host.exec("rm -f ${Vm.sh(Vm.dirOf(name) + "/.prov.json")} 2>/dev/null", timeoutMs = 8_000)
                host.snackbar(
                    "Image ready — starting the VM. First boot installs SQL Server (30–60 min); " +
                        "watch the console.",
                )
                host.snackbar(Vm.start(host, name))
                reload()
                return
            }
            flag.contains("FAIL") -> {
                provisioning.remove(name)
                val err = SqlServer.logTail(host, name, 3).lineSequence()
                    .filter { it.isNotBlank() }.lastOrNull()
                host.snackbar("SQL Server setup failed: " + (err ?: "see provision.log"))
                reload()
                return
            }
            else -> {
                val last = SqlServer.logTail(host, name, 1)
                provisioning[name] = if (last.isNotBlank()) last.take(90) else "Downloading Ubuntu image…"
                delay(6_000)
            }
        }
    }
}

private sealed interface VmDialog {
    data object Create : VmDialog
    data object SqlServer : VmDialog
    data class ConfirmDelete(val name: String) : VmDialog
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusPill(version: String?, checked: Boolean) {
    val (label, tint) = when {
        !checked -> "Checking QEMU…" to MaterialTheme.colorScheme.onSurfaceVariant
        version != null -> "QEMU ready" to MaterialTheme.colorScheme.primary
        else -> "QEMU not installed" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Dot(tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        if (version != null) {
            Text(
                version,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VmCard(
    host: NativeHost,
    row: VmRow,
    onStart: () -> Unit,
    onStop: (Boolean) -> Unit,
    onConsole: () -> Unit,
    onMonitor: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Space.ms),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(
                    row.cfg.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (row.cfg.isSqlServer) {
                    Text(
                        "SQL Server",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Dot(
                    if (row.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (row.running) "running" else "stopped",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                row.cfg.summary(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.cfg.isSqlServer) SqlStatusLine(host, row)
            // Wrapped, not a Row: with Monitor there are five of these and the drawer is narrow, so
            // the last one was squeezed into a sliver one letter wide rather than moving down a line.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                if (row.running) {
                    CompactOutlinedButton(text = "Stop", onClick = { onStop(false) })
                    CompactOutlinedButton(text = "Force", onClick = { onStop(true) })
                } else {
                    CompactFilledButton(text = "Start", onClick = onStart)
                }
                CompactOutlinedButton(text = "Console", onClick = onConsole)
                // Only while it runs: QEMU's monitor is a line into a live process, and there is
                // nothing on the other end of it once the machine has stopped.
                if (row.running) CompactOutlinedButton(text = "Monitor", onClick = onMonitor)
                CompactOutlinedButton(text = "Delete", onClick = onDelete)
            }
        }
    }
}

/**
 * The SQL VM's own account of itself, polled off the serial line while it runs.
 *
 * Stops polling once the answer is final — ready or an error — because neither changes again without
 * the VM being restarted, which rebuilds this anyway.
 */
@Composable
private fun SqlStatusLine(host: NativeHost, row: VmRow) {
    var status by remember(row.cfg.name, row.running) {
        mutableStateOf(
            if (row.running) SqlServer.Status(SqlServer.State.Working, "Checking…")
            else SqlServer.Status(SqlServer.State.Working, "Stopped — Start to run SQL Server."),
        )
    }
    if (row.running) {
        LaunchedEffect(row.cfg.name) {
            while (true) {
                val s = SqlServer.status(host, row.cfg.name)
                status = s
                if (s.state != SqlServer.State.Working) return@LaunchedEffect
                delay(10_000)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        Text(
            status.label,
            style = MaterialTheme.typography.labelSmall,
            color = when (status.state) {
                SqlServer.State.Ready -> MaterialTheme.colorScheme.primary
                SqlServer.State.Error -> MaterialTheme.colorScheme.error
                SqlServer.State.Working -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            "In SQL Client settings: Server localhost,1433 · Login sa · Trust certificate on.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProvisioningCard(name: String, line: String, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Space.ms),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "preparing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                "Downloading the Ubuntu image & building the disk (one-time). You can leave this.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(line, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            CompactOutlinedButton(text = "Cancel", onClick = onCancel)
        }
    }
}

/**
 * Create a VM, and the other ways to make one.
 *
 * One control, not two. A filled "Create VM" beside an outlined "More" read as two unrelated
 * buttons that happened to be adjacent, and the second wore a whole word to say nothing about what
 * it did. Joined along a hairline, the caret is plainly the menu belonging to the button beside it,
 * and it takes a quarter of the width doing so.
 *
 * The same control Source Control commits with, so the two panels do not each invent a shape for
 * "this button, and its variants". Written out rather than shared: an extension carries its own UI,
 * and only JCode's design system crosses between them.
 */
@Composable
private fun SplitButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    menu: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ControlSize.compactHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitHalf(
            enabled = enabled,
            shape = RoundedCornerShape(
                topStart = Radius.pill,
                bottomStart = Radius.pill,
                topEnd = Radius.none,
                bottomEnd = Radius.none,
            ),
            onClick = onClick,
            modifier = Modifier.weight(1f),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        // Hairline rather than a gap: a gap would make them two controls again.
        Box(
            modifier = Modifier
                .width(StrokeWidth.hairline)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)),
        )
        Box {
            SplitHalf(
                enabled = enabled,
                shape = RoundedCornerShape(
                    topStart = Radius.none,
                    bottomStart = Radius.none,
                    topEnd = Radius.pill,
                    bottomEnd = Radius.pill,
                ),
                onClick = onMenuOpen,
                modifier = Modifier.width(SplitCaretWidth).fillMaxHeight(),
            ) {
                Icon(
                    painter = jcIcon(JCodeIcon.ChevronDown),
                    contentDescription = "More ways to create a VM",
                    modifier = Modifier.size(IconSize.sm),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss, content = menu)
        }
    }
}

/** Wide enough for a chevron and a thumb, narrow enough to stay the smaller half. */
private val SplitCaretWidth = 40.dp

/**
 * One half of a [SplitButton], wearing the filled-tonal colours the app's compact buttons use.
 *
 * Written out rather than borrowed from `CompactFilledButton`, which owns its own shape: the whole
 * point here is that the two halves round only on their outer edges.
 */
@Composable
private fun SplitHalf(
    enabled: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (enabled) colors.secondaryContainer else colors.onSurface.copy(alpha = 0.12f),
        contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = 0.38f),
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

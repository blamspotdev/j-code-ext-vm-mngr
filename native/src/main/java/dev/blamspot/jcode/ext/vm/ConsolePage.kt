package dev.blamspot.jcode.ext.vm

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ManagerFilterChip
import dev.blamspot.jcode.design.SettingsTextFieldRow
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How far from the end still counts as watching the end, when deciding whether the log follows. */
private val FOLLOW_SLACK = 48.dp

/**
 * One monitor command worth a button.
 *
 * These are the ones you reach for when a guest has stopped answering, which is the moment typing a
 * command into a wedged machine is least appealing. Everything else is still typed.
 */
private data class MonitorAction(val label: String, val command: String)

private val MONITOR_ACTIONS = listOf(
    // ACPI power button, not a kill: the guest gets to shut itself down and flush its disk. The card's
    // Stop is the other thing — that ends QEMU.
    MonitorAction("Power off", "system_powerdown"),
    MonitorAction("Pause", "stop"),
    MonitorAction("Resume", "cont"),
    MonitorAction("Status", "info status"),
    MonitorAction("Disks", "info block"),
    MonitorAction("Network", "info network"),
)

/**
 * A VM's two lines, as an editor tab.
 *
 * The serial line is the guest talking and the monitor is QEMU talking about it; they are the same
 * kind of thing — a PTY streamed into a file — so they are the same page with a switch, rather than
 * two tabs per machine.
 *
 * Both are read as base64 and replayed through [Ansi] rather than appended as text: they carry
 * colours and `\r` redraws, and the host's line-based output handling would strip exactly the bytes
 * that matter. The base64 itself is what gets compared, so an idle machine costs no decode.
 */
@Composable
internal fun VmTerminalPage(
    host: NativeHost,
    name: String,
    initial: Stream,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var stream by remember(name) { mutableStateOf(initial) }
    var running by remember { mutableStateOf(false) }
    var pts by remember(name, stream) { mutableStateOf("") }
    var lines by remember(name, stream) { mutableStateOf<List<AnnotatedString>>(emptyList()) }
    var lastB64 by remember(name, stream) { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    val defaultColor = MaterialTheme.colorScheme.onSurface

    suspend fun poll() {
        val b64 = Vm.out(
            host,
            "tail -c 32768 ${Vm.sh("${Vm.dirOf(name)}/${stream.outFile}")} 2>/dev/null | base64 -w0",
            8_000,
        )
        if (b64.isNotBlank() && b64 != lastB64) {
            lastB64 = b64
            val text = runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }
                .getOrDefault("")
            lines = Ansi.render(text, defaultColor)
        }
    }

    suspend fun refresh() {
        pts = Vm.ptsOf(host, name, stream)
        running = Vm.isRunning(host, name)
        poll()
    }

    LaunchedEffect(name, stream) {
        refresh()
        // 2s: every poll is an exec, and every exec is a proot spawn — expensive on a budget phone.
        // Writing schedules its own follow-up so a keystroke still feels immediate.
        while (true) {
            delay(2_000)
            poll()
        }
    }

    fun send(payload: String) {
        scope.launch {
            if (!Vm.send(host, name, stream, payload)) {
                refresh()
                if (pts.isBlank()) {
                    host.snackbar("${stream.label} line is not attached — start the VM first.")
                    return@launch
                }
                Vm.send(host, name, stream, payload)
            }
            delay(250)
            poll()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Stream.entries.forEach { s ->
                ManagerFilterChip(selected = stream == s, label = s.label, onClick = { stream = s })
            }
            Box(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier.size(7.dp).background(
                    if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    CircleShape,
                ),
            )
            Text(
                if (running) "running" else "stopped",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (!running) {
                CompactFilledButton(
                    text = "Start",
                    busy = starting,
                    onClick = {
                        scope.launch {
                            starting = true
                            host.snackbar(Vm.start(host, name))
                            refresh()
                            starting = false
                        }
                    },
                )
            }
            // The buffer is the file, so this clears the view and not the machine's output. The
            // comparison key is deliberately left alone: resetting it would make the very next poll
            // decide the tail had changed and draw all of it back.
            CompactOutlinedButton(text = "Clear", onClick = { lines = emptyList() })
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        ) {
            val scroll = rememberScrollState()
            val across = rememberScrollState()
            val slack = with(LocalDensity.current) { FOLLOW_SLACK.toPx() }
            var following by remember { mutableStateOf(true) }
            LaunchedEffect(scroll.isScrollInProgress) {
                if (!scroll.isScrollInProgress && scroll.maxValue != Int.MAX_VALUE) {
                    following = scroll.value >= scroll.maxValue - slack
                }
            }
            LaunchedEffect(lines, following) {
                if (following) scroll.scrollTo(scroll.maxValue)
            }
            if (lines.isEmpty()) {
                Text(
                    when {
                        !running -> "(nothing yet — start the VM)"
                        stream == Stream.Monitor -> "(monitor attached — send a command, or tap Status)"
                        else -> "(no serial output yet)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.sm),
                )
            } else {
                Column(
                    modifier = Modifier
                        .verticalScroll(scroll)
                        .horizontalScroll(across)
                        .padding(Space.sm),
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        SettingsTextFieldRow(
            label = if (stream == Stream.Monitor) "Send a monitor command" else "Send to the console",
            value = input,
            onValueChange = { input = it },
            placeholder = if (stream == Stream.Monitor) "info status" else "a command, then Send",
            monospace = true,
            onCommit = {
                val line = input
                input = ""
                send("printf '%s\\n' ${Vm.sh(line)}")
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            CompactFilledButton(
                text = "Send",
                enabled = running && pts.isNotBlank(),
                onClick = {
                    val line = input
                    input = ""
                    send("printf '%s\\n' ${Vm.sh(line)}")
                },
            )
            if (stream == Stream.Serial) {
                CompactOutlinedButton(text = "↵", onClick = { send("printf '\\n'") })
                CompactOutlinedButton(text = "^C", onClick = { send("printf '\\003'") })
                CompactOutlinedButton(text = "Tab", onClick = { send("printf '\\t'") })
                CompactOutlinedButton(text = "↑", onClick = { send("printf '\\033[A'") })
            }
        }
        if (stream == Stream.Monitor) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                MONITOR_ACTIONS.forEach { action ->
                    CompactOutlinedButton(
                        text = action.label,
                        enabled = running && pts.isNotBlank(),
                        onClick = { send("printf '%s\\n' ${Vm.sh(action.command)}") },
                    )
                }
            }
        }
    }
}

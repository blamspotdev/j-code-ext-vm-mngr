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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.SettingsTextFieldRow
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How far from the end still counts as watching the end, when deciding whether the log follows. */
private val FOLLOW_SLACK = 48.dp

/**
 * One VM's serial console, as an editor tab.
 *
 * The line is read as base64 and replayed through [Ansi] rather than appended as text: it carries
 * colours and `\r` redraws, and the host's line-based output handling would strip exactly the bytes
 * that matter. The base64 itself is what gets compared, so an idle VM costs no decode and no re-render.
 */
@Composable
internal fun ConsolePage(host: NativeHost, name: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var pts by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<AnnotatedString>>(emptyList()) }
    var lastB64 by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    val defaultColor = MaterialTheme.colorScheme.onSurface

    suspend fun poll() {
        val b64 = Vm.out(
            host,
            "tail -c 32768 ${Vm.sh(Vm.dirOf(name) + "/serial.out")} 2>/dev/null | base64 -w0",
            timeoutMs = 8_000,
        )
        if (b64.isNotBlank() && b64 != lastB64) {
            lastB64 = b64
            val text = runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }
                .getOrDefault("")
            lines = Ansi.render(text, defaultColor)
        }
    }

    suspend fun refresh() {
        pts = Vm.out(host, "cat ${Vm.sh(Vm.dirOf(name) + "/serial.pts")} 2>/dev/null", 8_000)
        running = Vm.isRunning(host, name)
        poll()
    }

    LaunchedEffect(name) {
        refresh()
        // 2s: every poll is an exec, and every exec is a proot spawn — expensive on a budget phone.
        // Writing schedules its own follow-up so Enter still feels immediate.
        while (true) {
            delay(2_000)
            poll()
        }
    }

    suspend fun write(payload: String) {
        if (pts.isBlank()) {
            refresh()
            if (pts.isBlank()) return
        }
        host.exec("$payload > ${Vm.sh(pts)}", timeoutMs = 8_000)
        delay(250)
        poll()
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
                modifier = Modifier.weight(1f),
            )
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
            CompactOutlinedButton(text = "Clear", onClick = { lines = emptyList(); lastB64 = "" })
        }

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        ) {
            val scroll = rememberScrollState()
            val across = rememberScrollState()
            val slack = with(androidx.compose.ui.platform.LocalDensity.current) { FOLLOW_SLACK.toPx() }
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
                    "(no serial output yet — start the VM to boot it)",
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
            label = "Send to the console",
            value = input,
            onValueChange = { input = it },
            placeholder = "a command, then Send",
            monospace = true,
            onCommit = {
                val line = input
                input = ""
                scope.launch { write("printf '%s\\n' ${Vm.sh(line)}") }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            CompactFilledButton(
                text = "Send",
                enabled = running && pts.isNotBlank(),
                onClick = {
                    val line = input
                    input = ""
                    scope.launch { write("printf '%s\\n' ${Vm.sh(line)}") }
                },
            )
            CompactOutlinedButton(text = "↵", onClick = { scope.launch { write("printf '\\n'") } })
            CompactOutlinedButton(text = "^C", onClick = { scope.launch { write("printf '\\003'") } })
            CompactOutlinedButton(text = "Tab", onClick = { scope.launch { write("printf '\\t'") } })
            CompactOutlinedButton(text = "↑", onClick = { scope.launch { write("printf '\\033[A'") } })
        }
    }
}

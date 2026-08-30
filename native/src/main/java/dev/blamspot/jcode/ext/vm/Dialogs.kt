package dev.blamspot.jcode.ext.vm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.SettingsTextFieldRow
import dev.blamspot.jcode.design.Space

private val NUMBER = KeyboardOptions(keyboardType = KeyboardType.Number)

/**
 * The form fields are JCode's own [SettingsTextFieldRow], not a set built here: a dialog that came
 * from an extension should not be recognisable as one, and a change to the app's field styling moves
 * these with it.
 */
@Composable
internal fun CreateVmDialog(onDismiss: () -> Unit, onCreate: (VmCfg) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ram by remember { mutableStateOf("2048") }
    var cpus by remember { mutableStateOf("2") }
    var disk by remember { mutableStateOf("20") }
    var iso by remember { mutableStateOf("") }
    var forwards by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a VM") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(
                    "Full-system x86_64, software-emulated (no KVM — slow but compatible).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsTextFieldRow(
                    label = "Name (a–z, 0–9, dash)",
                    value = name,
                    onValueChange = { name = it; error = null },
                    placeholder = "ubuntu-server",
                )
                SettingsTextFieldRow("RAM (MB)", ram, { ram = it }, keyboardOptions = NUMBER)
                SettingsTextFieldRow("CPUs", cpus, { cpus = it }, keyboardOptions = NUMBER)
                SettingsTextFieldRow("Disk (GB)", disk, { disk = it }, keyboardOptions = NUMBER)
                SettingsTextFieldRow(
                    label = "Install ISO path (optional)",
                    value = iso,
                    onValueChange = { iso = it },
                    placeholder = "/root/iso/ubuntu.iso",
                )
                SettingsTextFieldRow(
                    label = "Port forwards guest:host (optional)",
                    value = forwards,
                    onValueChange = { forwards = it },
                    placeholder = "22:2222, 1433:1433",
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            CompactFilledButton(
                text = "Create",
                onClick = {
                    val n = name.trim()
                    if (!Vm.validName(n)) {
                        error = "Name: letters, digits and dashes only."
                        return@CompactFilledButton
                    }
                    onCreate(
                        VmCfg(
                            name = n,
                            ram = ram.trim().toIntOrNull() ?: 2048,
                            cpus = cpus.trim().toIntOrNull() ?: 2,
                            disk = disk.trim().toIntOrNull() ?: 20,
                            iso = iso.trim(),
                            forwards = Vm.parseForwards(forwards),
                        ),
                    )
                },
            )
        },
        dismissButton = { CompactOutlinedButton(text = "Cancel", onClick = onDismiss) },
    )
}

/**
 * The one-click SQL Server VM.
 *
 * Everything it asks for is checked here rather than by the guest half an hour later: SQL Server's
 * own password policy, and the 2 GB floor below which its engine will not start.
 */
@Composable
internal fun SqlServerDialog(
    onDismiss: () -> Unit,
    onSetUp: (name: String, password: String, ram: Int, cpus: Int, disk: Int) -> Unit,
) {
    var name by remember { mutableStateOf("sqlserver") }
    var password by remember { mutableStateOf(SqlServer.DEFAULT_PASSWORD) }
    var confirm by remember { mutableStateOf(SqlServer.DEFAULT_PASSWORD) }
    var ram by remember { mutableStateOf("2048") }
    var disk by remember { mutableStateOf("30") }
    var cpus by remember { mutableStateOf("2") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up SQL Server") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(
                    "Creates an Ubuntu 22.04 VM that auto-installs Microsoft SQL Server 2022 " +
                        "(Developer edition) on first boot — no manual OS install. First setup " +
                        "downloads ~700 MB and can take 30–60 minutes under emulation; you only wait " +
                        "once. Then connect the SQL Client to localhost,1433 with login sa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsTextFieldRow("VM name", name, { name = it; error = null })
                SettingsTextFieldRow(
                    label = "SA password (login: sa)",
                    value = password,
                    onValueChange = { password = it; error = null },
                    supporting = "SQL Server refuses a passwordless SA. Change this if the VM will " +
                        "hold anything sensitive.",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                SettingsTextFieldRow(
                    label = "Confirm SA password",
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                SettingsTextFieldRow("RAM (MB) — min 2048", ram, { ram = it }, keyboardOptions = NUMBER)
                SettingsTextFieldRow("Disk (GB)", disk, { disk = it }, keyboardOptions = NUMBER)
                SettingsTextFieldRow("CPUs", cpus, { cpus = it }, keyboardOptions = NUMBER)
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            CompactFilledButton(
                text = "Set up",
                onClick = {
                    val n = name.trim()
                    val ramV = ram.trim().toIntOrNull() ?: 0
                    when {
                        !Vm.validName(n) -> error = "Name: letters, digits and dashes only."
                        SqlServer.validatePassword(password, confirm) != null ->
                            error = SqlServer.validatePassword(password, confirm)
                        ramV < 2048 -> error = "RAM must be at least 2048 MB for SQL Server."
                        else -> onSetUp(
                            n,
                            password,
                            maxOf(2048, ramV),
                            (cpus.trim().toIntOrNull() ?: 2).coerceIn(1, 4),
                            maxOf(16, disk.trim().toIntOrNull() ?: 30),
                        )
                    }
                },
            )
        },
        dismissButton = { CompactOutlinedButton(text = "Cancel", onClick = onDismiss) },
    )
}

@Composable
internal fun ConfirmDeleteDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete VM") },
        text = {
            Text(
                "Delete $name and its disk? This can't be undone.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { CompactFilledButton(text = "Delete", onClick = onDelete) },
        dismissButton = { CompactOutlinedButton(text = "Cancel", onClick = onDismiss) },
    )
}

package dev.blamspot.jcode.ext.vm

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.NativeHost

/**
 * VM Manager, native.
 *
 * The entry point JCode instantiates by name and splices into its own composition. Everything here
 * runs in JCode's process against JCode's Compose runtime — hence the `compileOnly` dependency rules
 * in the build script.
 *
 * Two surfaces from one class, chosen by the view the host asks for: the drawer panel, and a per-VM
 * serial console opened as an editor tab. The console owns its own state and asks the runtime its own
 * questions, because it opens on its own — restored with a session, or reached from the panel — and
 * cannot see what the panel decided.
 */
class VmExtension : JCodeNativeExtension {

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        val view = params[JCodeNativeExtension.Params.VIEW].orEmpty()
        if (view.startsWith(CONSOLE_PREFIX)) {
            ConsolePage(host, view.removePrefix(CONSOLE_PREFIX), Modifier)
        } else {
            VmListPage(host, Modifier)
        }
    }

    private companion object {
        /** What [NativeHost.openView] is asked for; the rest of the id is the machine's name. */
        const val CONSOLE_PREFIX = "console:"
    }
}

package dev.kizzyplus.companion.discord

import android.content.Context
import android.content.Intent
import dev.kizzyplus.companion.root.RootShell

/**
 * Kizzy's own presence modes run on their own, whatever Kizzy+ does. App Detection in particular
 * shows "Playing GameNative" whenever GameNative is open. Class names are from Kizzy's
 * feature_rpc_base/services package.
 */
object KizzyServices {
    private const val PACKAGE = KizzyImport.PACKAGE
    private const val PREFIX = "$PACKAGE/.feature_rpc_base.services."

    enum class Mode(val className: String, val label: String) {
        APP_DETECTION("AppDetectionService", "App Detection"),
        MEDIA("MediaRpcService", "Media RPC"),
        EXPERIMENTAL("ExperimentalRpc", "Experimental RPC"),
        CUSTOM("CustomRpcService", "Custom RPC"),
    }

    /** Kizzy presence modes that are running right now, or null if root can't tell. */
    fun running(): Set<Mode>? {
        val r = RootShell.run("dumpsys activity services $PACKAGE | grep 'ServiceRecord{'")
        if (r.shellFailed) return null
        return Mode.entries.filter { mode -> r.lines.any { it.contains(".services.${mode.className}") } }.toSet()
    }

    /**
     * Stops them. Kizzy's exported shortcut action (ShortcutsActivity.Intents.STOP_RPC) covers App
     * Detection, Media RPC and Experimental RPC; Custom RPC isn't covered, so that one goes
     * through root.
     */
    fun stop(context: Context, modes: Set<Mode>) {
        if (modes.any { it != Mode.CUSTOM }) {
            val intent = Intent("com.my.kizzy.intent.action.STOP_RPC")
                .setClassName(PACKAGE, "$PACKAGE.feature_rpc_base.services.ShortcutsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                modes.filter { it != Mode.CUSTOM }.forEach { RootShell.run("am stopservice -n $PREFIX${it.className}") }
            }
        }
        if (Mode.CUSTOM in modes) RootShell.run("am stopservice -n $PREFIX${Mode.CUSTOM.className}")
    }
}

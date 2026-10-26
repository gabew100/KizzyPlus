package dev.kizzyplus.companion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.kizzyplus.companion.detect.ForegroundApp
import dev.kizzyplus.companion.detect.GameDetector
import dev.kizzyplus.companion.discord.KizzyServices
import dev.kizzyplus.companion.detect.Outcome
import dev.kizzyplus.companion.discord.KizzyImport
import dev.kizzyplus.companion.discord.TokenStore
import dev.kizzyplus.companion.root.RootShell
import dev.kizzyplus.companion.service.PresenceService

/** Plain-text report of what Kizzy+ can see right now. Never includes the token. */
object Diagnostics {
    fun run(context: Context): String {
        val settings = Settings(context)
        val out = ArrayList<String>()
        out += "Kizzy+ ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        val root = RootShell.isRootAvailable()
        out += "Root: " + if (root) "granted" else "not available"
        for (pkg in (settings.gameNativePackages() + settings.edenPackages() + KizzyImport.PACKAGE).distinct()) {
            version(context, pkg)?.let { out += "Installed: $pkg $it" }
        }
        out += "Presence goes through: " + if (settings.backend == Settings.BACKEND_KIZZY) "the Kizzy app" else "Kizzy+ itself"
        out += "Discord login saved in Kizzy+: " + if (TokenStore.hasToken(context)) "yes" else "no"
        out += "Service: " + if (PresenceService.running) "running" else "stopped"
        out += "Discord connection: ${StatusBus.current.discord.ifBlank { "not started" }}"
        out += ""

        if (root) {
            val screen = ForegroundApp(context)
            val onScreen = screen.current()
            out += "On screen: ${onScreen ?: "unknown"}" +
                (if (screen.hasUsageAccess()) "" else " (no usage access, read from dumpsys)") +
                ", counts as playing: " + if (onScreen == null || GameDetector(context).isWatched(settings, onScreen)) "yes" else "no"
            val kizzy = KizzyServices.running()
            out += "Kizzy presence modes running: " +
                (kizzy?.joinToString { it.label }?.ifEmpty { "none" } ?: "couldn't check")
            out += ""

            val trace = ArrayList<String>()
            val outcome = try {
                GameDetector(context).detect(settings, trace)
            } catch (e: Exception) {
                Outcome.Failed(e.toString())
            }
            out += "Detection:"
            trace.forEach { out += "  $it" }
            out += "Result: " + when (outcome) {
                is Outcome.Playing -> with(outcome.game) {
                    "$title [$sourceLine], identified by $identifiedBy\n" +
                        "  cover candidates: ${coverUrls.take(3).joinToString().ifEmpty { "none" }}"
                }
                Outcome.Idle -> "no game running"
                Outcome.NotOnScreen -> "not on screen"
                is Outcome.Failed -> "failed: ${outcome.reason}"
            }
            out += ""
        }

        out += "Recent events:"
        out += EventLog.dump().ifBlank { "  none yet" }
        return out.joinToString("\n")
    }

    private fun version(context: Context, pkg: String): String? = try {
        context.packageManager.getPackageInfo(pkg, 0).versionName ?: "(no version name)"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

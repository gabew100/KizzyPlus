package dev.kizzyplus.companion.detect

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dev.kizzyplus.companion.EventLog
import dev.kizzyplus.companion.root.RootShell

/**
 * Which app is on screen, from UsageStatsManager's activity events. That's a local call with no
 * root, cheap enough to make every few seconds. Kizzy+ grants itself usage access with root
 * (appops), so there's no settings page to visit; if that doesn't take, it asks dumpsys instead.
 *
 * Kizzy+'s own screens are ignored, so opening Kizzy+ to check on things doesn't count as leaving
 * the game.
 */
class ForegroundApp(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val ops = context.getSystemService(AppOpsManager::class.java)
    private var lastQueryMs = 0L
    private var current: String? = null
    private var grantTried = false

    /** Package name of the app on screen, or null if it can't be told. */
    fun current(): String? {
        if (!hasUsageAccess()) {
            if (!grantTried) {
                grantTried = true
                RootShell.run("appops set ${context.packageName} GET_USAGE_STATS allow")
                EventLog.add(if (hasUsageAccess()) "Granted Kizzy+ usage access through root" else "Couldn't grant usage access; using dumpsys")
            }
            if (!hasUsageAccess()) return fromDumpsys()
        }
        val now = System.currentTimeMillis()
        val from = if (lastQueryMs == 0L) now - LOOKBACK_MS else lastQueryMs - OVERLAP_MS
        val events = usage.queryEvents(from, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName != context.packageName) {
                current = event.packageName
            }
        }
        lastQueryMs = now
        return current
    }

    fun hasUsageAccess(): Boolean =
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED

    private fun fromDumpsys(): String? {
        val line = RootShell.run("dumpsys activity activities | grep -E -m 1 'topResumedActivity|mResumedActivity'")
            .lines.firstOrNull() ?: return current
        val pkg = TOP_ACTIVITY.find(line)?.groupValues?.get(1) ?: return current
        if (pkg != context.packageName) current = pkg
        return current
    }

    companion object {
        // Long enough to find the app that was opened before a long play session started.
        private const val LOOKBACK_MS = 24 * 60 * 60 * 1000L
        private const val OVERLAP_MS = 2_000L
        private val TOP_ACTIVITY = Regex("u\\d+ ([\\w.]+)/")
    }
}

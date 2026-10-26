package dev.kizzyplus.companion.detect

import android.content.Context
import android.os.SystemClock
import dev.kizzyplus.companion.Settings
import dev.kizzyplus.companion.root.RootShell

/**
 * One check costs one `ps` call. Eden additionally gets a `dumpsys activity -p <pkg>` call
 * and a log tail, but only while its process is alive.
 */
class GameDetector(context: Context) {

    private val gameNative = GameNativeDetector(context)
    private val eden = EdenDetector()

    private var uids: Map<String, Int> = emptyMap()
    private var uidsFor: List<String> = emptyList()
    private var uidsAt = 0L

    /** True if [pkg] is a GameNative or Eden package that's being watched. */
    fun isWatched(settings: Settings, pkg: String?): Boolean = pkg != null &&
        ((settings.watchGameNative && pkg in settings.gameNativePackages()) ||
            (settings.watchEden && pkg in settings.edenPackages()))

    /** Checks every watched app, or only [onlyPackage] when given (the app that's on screen). */
    fun detect(settings: Settings, trace: MutableList<String>? = null, onlyPackage: String? = null): Outcome {
        val gnPackages = (if (settings.watchGameNative) settings.gameNativePackages() else emptyList())
            .filter { onlyPackage == null || it == onlyPackage }
        val edenPackages = (if (settings.watchEden) settings.edenPackages() else emptyList())
            .filter { onlyPackage == null || it == onlyPackage }
        if (gnPackages.isEmpty() && edenPackages.isEmpty()) {
            trace?.add("Both GameNative and Eden are switched off under \"What to watch\"")
            return Outcome.Idle
        }

        if (!refreshUids(gnPackages + edenPackages)) return rootFailure()
        trace?.add("Installed: ${uids.entries.joinToString { "${it.key} (uid ${it.value})" }.ifEmpty { "none of the watched apps" }}")
        val watched = (gnPackages + edenPackages).filter { it in uids }
        if (watched.isEmpty()) return Outcome.Idle

        val pattern = watched.mapNotNull { uids[it] }.distinct().joinToString("|")
        val ps = RootShell.run("ps -A -w -o UID,PID,CMDLINE | grep -E '^ *($pattern) +[0-9]+ '")
        if (ps.shellFailed) return rootFailure()
        val procs = ps.lines.mapNotNull(::parsePs)
        val byUid = procs.groupBy { it.uid }

        for (pkg in gnPackages) {
            val uid = uids[pkg] ?: continue
            gameNative.detect(pkg, byUid[uid].orEmpty(), trace)?.let { return Outcome.Playing(it) }
        }
        for (pkg in edenPackages) {
            val uid = uids[pkg] ?: continue
            val main = byUid[uid].orEmpty().firstOrNull { it.cmdline.trim() == pkg }
            if (main == null) {
                eden.forget(pkg)
                trace?.add("$pkg: not running")
                continue
            }
            eden.detect(pkg, main.pid, trace)?.let { return Outcome.Playing(it) }
        }
        return Outcome.Idle
    }

    /** Looks up app UIDs through their data folders; refreshed every few minutes. */
    private fun refreshUids(packages: List<String>): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (packages == uidsFor && now - uidsAt < UID_TTL_MS) return true
        val list = packages.joinToString(" ")
        val r = RootShell.run("for p in $list; do u=\$(stat -c %u /data/data/\$p 2>/dev/null) && echo \"\$p \$u\"; done")
        if (r.shellFailed) return false
        uids = r.lines.mapNotNull { line ->
            val parts = line.trim().split(' ')
            val uid = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && uid != null && uid >= 10000) parts[0] to uid else null
        }.toMap()
        uidsFor = packages
        uidsAt = now
        return true
    }

    private fun rootFailure(): Outcome = Outcome.Failed(
        if (RootShell.granted == false) "Root access was denied" else "The root shell didn't answer",
    )

    companion object {
        private const val UID_TTL_MS = 5 * 60_000L
        private val PS_LINE = Regex("^\\s*(\\d+)\\s+(\\d+)\\s+(.*)$")

        fun parsePs(line: String): Proc? {
            val m = PS_LINE.find(line) ?: return null
            val cmd = m.groupValues[3].trim()
            if (cmd.isEmpty()) return null
            return Proc(m.groupValues[1].toInt(), m.groupValues[2].toInt(), cmd)
        }
    }
}

package dev.kizzyplus.companion.detect

import android.content.Context
import dev.kizzyplus.companion.EventLog

/**
 * GameNative runs every game through Wine as child processes of its own app UID. While any
 * Wine process is alive a game session is running; the session ends when they all exit.
 * GameNative itself finds these processes the same way (WineProcessSnapshotHelper.readFromProc:
 * same UID, command line containing "wine" or ".exe").
 */
class GameNativeDetector(private val context: Context) {

    private class Session(
        val key: String,
        var game: DetectedGame,
        var confirmed: Boolean,
        var exeSignature: String,
    )

    private val sessions = HashMap<String, Session>()

    fun forget(pkg: String) {
        sessions.remove(pkg)
    }

    fun detect(pkg: String, procs: List<Proc>, trace: MutableList<String>?): DetectedGame? {
        val wine = procs.filter { isWine(pkg, it.cmdline) }
        if (wine.isEmpty()) {
            if (sessions.remove(pkg) != null) EventLog.add("$pkg: Wine exited, game closed")
            trace?.add("$pkg: ${procs.size} app process(es), no Wine, so no game is running")
            return null
        }
        // wineserver lives for the whole session; fall back to the oldest Wine process.
        val anchor = (wine.firstOrNull { it.cmdline.contains("wineserver") } ?: wine.minBy { it.pid }).pid
        val key = "$pkg:$anchor"
        val paths = wine.map { normalize(it.cmdline) }
        val exes = wine.flatMap { exeNames(it.cmdline) }.filter { it !in SYSTEM_EXES }.distinct().sorted()
        val signature = exes.joinToString(",")
        trace?.let { t ->
            t.add("$pkg: ${wine.size} Wine process(es)")
            wine.forEach { t.add("  pid ${it.pid}: ${it.cmdline.take(220)}") }
            t.add("  game executables: ${exes.ifEmpty { listOf("none yet") }.joinToString()}")
        }

        val current = sessions[pkg]
        if (current != null && current.key == key) {
            // A launcher often starts first and the real game .exe appears later; re-check then.
            if (!current.confirmed && exes.isNotEmpty() && signature != current.exeSignature) {
                current.exeSignature = signature
                val (game, confirmed) = resolve(pkg, wine, paths, current.game.startedAtMs, trace)
                if (game.title != current.game.title) EventLog.add("GameNative game is now ${game.title} (${game.identifiedBy})")
                current.game = game
                current.confirmed = confirmed
            }
            trace?.add("  same session as before: ${current.game.title}")
            return current.game
        }

        val (game, confirmed) = resolve(pkg, wine, paths, null, trace)
        sessions[pkg] = Session(key, game, confirmed, signature)
        if (trace == null) EventLog.add("GameNative game started: ${game.title} (${game.identifiedBy})")
        return game
    }

    private fun resolve(
        pkg: String,
        wine: List<Proc>,
        paths: List<String>,
        keepStart: Long?,
        trace: MutableList<String>?,
    ): Pair<DetectedGame, Boolean> {
        val now = System.currentTimeMillis()
        // The troubleshooting check can run alongside the service; both share one database copy.
        val hit = synchronized(GameNativeDb) { fromLibrary(pkg, paths, keepStart, now, trace) }
        hit.game?.let { return it to hit.confirmed }

        // No library entry (a custom game): name it from SaveHop.json, the .exe's folder, or the
        // real folder behind GameNative's A: drive, in that order.
        val exe = gameExe(wine)
        val saveHop = WineNames.saveHopName(wine, trace)
        val (title, how) = when {
            saveHop != null -> saveHop to "SaveHop.json"
            exe?.folder != null -> exe.folder to "the running .exe's folder"
            exe != null -> (WineNames.folderTitle(exe.proc.pid, exe.windowsDir, trace) ?: prettify(exe.exe)) to
                "the custom game's folder"
            else -> "a PC game" to "no name found yet"
        }
        val game = DetectedGame(
            launcher = Launcher.GAMENATIVE,
            packageName = pkg,
            store = null,
            title = title,
            gameId = null,
            coverUrls = emptyList(),
            headerUrls = emptyList(),
            startedAtMs = startTime(keepStart, hit.launchTime, now),
            identifiedBy = "named from $how",
        )
        return game to false
    }

    private class LibraryHit(val game: DetectedGame?, val confirmed: Boolean, val launchTime: Long?)

    /** Matches the session against GameNative's library: running .exe folder first, then the last launch. */
    private fun fromLibrary(
        pkg: String,
        paths: List<String>,
        keepStart: Long?,
        now: Long,
        trace: MutableList<String>?,
    ): LibraryHit {
        val db = GameNativeDb.open(context, pkg, trace) ?: return LibraryHit(null, false, null)
        try {
            val launches: List<GameNativeDb.Launch> = db.recentLaunches(8)
            trace?.add("  recent launches: ${launches.joinToString { it.containerId }.ifEmpty { "none recorded" }}")
            val records = ArrayList<Pair<GameNativeDb.Launch, GnRecord>>()
            for (launch in launches) db.lookup(launch.containerId)?.let { records.add(launch to it) }

            for ((launch, record) in records) {
                if (record.matchesAny(paths)) {
                    val game = build(pkg, record, startTime(keepStart, launch.lastPlayed, now), "running .exe sits in its install folder", true)
                    return LibraryHit(game, true, launch.lastPlayed)
                }
            }
            val byFolder = db.findByInstallFolder(paths)
            if (byFolder != null) {
                return LibraryHit(build(pkg, byFolder, keepStart ?: now, "running .exe sits in its install folder", true), true, null)
            }
            val latest = launches.firstOrNull() ?: return LibraryHit(null, false, null)
            val record = records.firstOrNull { it.first == latest }?.second
                // Custom games have no library row; the caller names them after the .exe.
                ?: return LibraryHit(null, false, latest.lastPlayed)
            val game = build(pkg, record, startTime(keepStart, latest.lastPlayed, now), "last game launched in GameNative", false)
            return LibraryHit(game, false, latest.lastPlayed)
        } finally {
            db.close()
        }
    }

    private fun build(pkg: String, r: GnRecord, start: Long, how: String, confirmed: Boolean): DetectedGame =
        DetectedGame(
            launcher = Launcher.GAMENATIVE,
            packageName = pkg,
            store = r.store,
            title = r.title,
            gameId = r.gameId,
            coverUrls = r.cover + r.header,
            headerUrls = r.header + r.cover,
            startedAtMs = start,
            identifiedBy = if (confirmed) "$how (${r.containerId})" else "$how (${r.containerId}, not yet matched to a running .exe)",
        )

    companion object {
        private const val TWELVE_HOURS = 12 * 60 * 60 * 1000L

        // Wine services and GameNative's own helpers (WineProcessSnapshotHelper, WineUtils.SERVICE_DEFAULTS,
        // XServerScreen.getWineStartCommand), plus common redistributable installers.
        private val SYSTEM_EXES = setOf(
            "wineserver", "services", "start", "winhandler", "tabtip", "explorer", "winedevice", "svchost",
            "conhost", "wineconsole", "winedbg", "taskmgr", "rundll32", "msiexec", "winebrowser", "wineboot",
            "winemenubuilder", "plugplay", "rpcss", "bits", "eventlog", "http", "lanmanserver", "ndis",
            "scardsvr", "schedule", "spooler", "stisvc", "termservice", "wfm", "steam", "steamwebhelper",
            "steamclient_loader", "steamclient_loader_x64", "steamclient_loader_x86", "steamerrorreporter",
            "cmd", "reg", "regedit", "regsvr32", "iexplore", "mscorsvw", "ngen", "dxsetup", "dotnetfx",
            "vcredist_x64", "vcredist_x86", "vc_redist.x64", "vc_redist.x86", "unitycrashhandler64",
            "unitycrashhandler32", "crashreportclient",
            // SaveHop's save-sync wrapper; WineNames.saveHopName reads the real game's name instead.
            "savehop",
        )

        private val EXE = Regex("\\.exe\\b", RegexOption.IGNORE_CASE)

        fun isWine(pkg: String, cmdline: String): Boolean {
            val c = cmdline.trim()
            if (c == pkg || c.startsWith("$pkg:")) return false
            val l = c.lowercase()
            return l.contains("wine") || l.contains(".exe")
        }

        fun normalize(cmdline: String): String = cmdline.lowercase().replace('\\', '/')

        /** Lower-case executable names without ".exe", e.g. "hollow_knight". */
        fun exeNames(cmdline: String): List<String> = EXE.findAll(cmdline).mapNotNull { m ->
            val before = cmdline.substring(0, m.range.first)
            val cut = maxOf(before.lastIndexOf('\\'), before.lastIndexOf('/'), before.lastIndexOf(' '), before.lastIndexOf('"'))
            before.substring(cut + 1).trim().lowercase().ifBlank { null }
        }.toList()

        private fun startTime(keep: Long?, launched: Long?, now: Long): Long =
            keep ?: launched?.takeIf { it in (now - TWELVE_HOURS)..(now + 60_000) } ?: now

        /** The first game .exe among the Wine processes, and the folder it sits in when the path names one. */
        internal data class GameExe(val proc: Proc, val windowsDir: String, val folder: String?, val exe: String)

        internal fun gameExe(wine: List<Proc>): GameExe? {
            for (proc in wine) {
                for (m in EXE.findAll(proc.cmdline)) {
                    val before = proc.cmdline.substring(0, m.range.first).trim('"', ' ')
                    val parts = before.split('\\', '/')
                    val exe = parts.last().substringAfterLast(' ').trim('"')
                    if (exe.lowercase() in SYSTEM_EXES || exe.isBlank()) continue
                    val folder = parts.dropLast(1).asReversed()
                        .map { it.trim('"', ' ') }
                        .firstOrNull { it.isNotBlank() && !it.endsWith(":") && it.lowercase() !in WineNames.GENERIC_DIRS }
                    return GameExe(proc, before.substringBeforeLast('\\', ""), folder, exe)
                }
            }
            return null
        }

        /** Best guess from command lines alone: the game's folder, else its cleaned-up .exe name. */
        internal fun titleFromCommandLines(wine: List<Proc>): String? =
            gameExe(wine)?.let { it.folder ?: prettify(it.exe) }

        private fun prettify(exe: String): String = exe
            .replace(Regex("[-_](win64|win32|x64|x86|shipping|dx11|dx12)", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
    }
}

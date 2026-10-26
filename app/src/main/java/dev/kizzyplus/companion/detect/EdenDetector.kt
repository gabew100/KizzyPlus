package dev.kizzyplus.companion.detect

import android.net.Uri
import android.util.Xml
import dev.kizzyplus.companion.EventLog
import dev.kizzyplus.companion.root.RootShell
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Eden runs games inside org.yuzu.yuzu_emu.activities.EmulationActivity in its main process.
 * Sources for the game's identity, all from Eden's source tree:
 *  - default SharedPreferences: "Games" string set of Game JSON (title, path, programId) and
 *    "<path>_LastPlayed" longs written when a game is launched from the library (GameAdapter)
 *  - files/cache/launched.json: {"<TITLEID>": {"timestamp": seconds}} written by core Load()
 *  - files/log/eden_log.txt: "Loading <title> (<TITLEID>) ..." written by core Load()
 *  - the activity's intent data, set when a game is started from a home screen shortcut
 */
class EdenDetector {

    private class Session(val key: String, var game: DetectedGame, var logLine: String?)

    private data class EdenGame(val title: String, val path: String, val titleId: String?)
    private data class LoadLine(val raw: String, val title: String, val titleId: String)

    private val sessions = HashMap<String, Session>()

    fun forget(pkg: String) {
        sessions.remove(pkg)
    }

    fun detect(pkg: String, pid: Int, trace: MutableList<String>?): DetectedGame? {
        val dump = RootShell.run("dumpsys activity -p $pkg activities", 10_000)
        if (dump.shellFailed) return null
        val text = dump.text
        val activity = Regex.escape(pkg) + "/" + Regex.escape(EMULATION_ACTIVITY)
        val record = Regex("ActivityRecord\\{(\\w+) u\\d+ $activity").find(text)
        if (record == null) {
            if (sessions.remove(pkg) != null) EventLog.add("$pkg: emulation ended")
            trace?.add("$pkg: Eden is open (pid $pid) but no game is running")
            return null
        }
        val key = "$pkg:$pid:${record.groupValues[1]}"
        val load = latestLoadLine(pkg)
        trace?.add("$pkg: EmulationActivity ${record.groupValues[1]} is running")
        trace?.add("  log: ${load?.raw ?: "no \"Loading\" line yet"}")

        val current = sessions[pkg]
        if (current != null && current.key == key) {
            // A new "Loading" line means the core booted something else inside the same activity.
            if (load != null && load.raw != current.logLine) {
                current.logLine = load.raw
                if (!load.titleId.equals(current.game.gameId, ignoreCase = true)) {
                    val library = readLibrary(pkg)
                    val match = library.games.firstOrNull { it.titleId.equals(load.titleId, ignoreCase = true) }
                    current.game = game(pkg, match?.title ?: load.title, load.titleId, System.currentTimeMillis(), "Eden log")
                    EventLog.add("Eden game is now ${current.game.title}")
                }
            }
            trace?.add("  same session as before: ${current.game.title}")
            return current.game
        }

        val game = identify(pkg, text, activity, load, trace)
        sessions[pkg] = Session(key, game, load?.raw)
        if (trace == null) EventLog.add("Eden game started: ${game.title} (${game.identifiedBy})")
        return game
    }

    private fun identify(pkg: String, dump: String, activity: String, load: LoadLine?, trace: MutableList<String>?): DetectedGame {
        val now = System.currentTimeMillis()
        val library = readLibrary(pkg)
        val launched = readLaunched(pkg)
        trace?.add("  library: ${library.games.size} game(s), launched.json: ${launched.size} entr(ies)")

        // 1. Started from a shortcut: the intent carries the game's path.
        val data = Regex("dat=(\\S+)[^\\n]*cmp=$activity").find(dump)?.groupValues?.get(1)
            ?.takeUnless { it.endsWith("...") || it.contains("/...") }
        if (data != null) {
            val match = library.games.firstOrNull { it.path == data }
            val tid = match?.titleId ?: TITLE_ID.find(Uri.decode(data))?.value
            val title = match?.title ?: fileTitle(data)
            val start = tid?.let { launched[it.uppercase()] } ?: library.lastPlayed[data] ?: now
            return game(pkg, title, tid, freshOr(start, now), "shortcut that started the game")
        }

        // 2. Newest of "last played" (library launch) and launched.json (core boot).
        val lastPlayed = library.lastPlayed.maxByOrNull { it.value }
        val lastBoot = launched.maxByOrNull { it.value }
        if (lastBoot != null && (lastPlayed == null || lastBoot.value >= lastPlayed.value - 5_000)) {
            val match = library.games.firstOrNull { it.titleId.equals(lastBoot.key, ignoreCase = true) }
            val title = match?.title ?: load?.takeIf { it.titleId.equals(lastBoot.key, ignoreCase = true) }?.title
            if (title != null) return game(pkg, title, lastBoot.key, freshOr(lastBoot.value, now), "launched.json")
        }
        if (lastPlayed != null) {
            val match = library.games.firstOrNull { it.path == lastPlayed.key }
            if (match != null) return game(pkg, match.title, match.titleId, freshOr(lastPlayed.value, now), "last game opened in Eden's library")
        }

        // 3. The boot line in the log.
        if (load != null) {
            val match = library.games.firstOrNull { it.titleId.equals(load.titleId, ignoreCase = true) }
            return game(pkg, match?.title ?: load.title, load.titleId, now, "Eden log")
        }
        return game(pkg, "a Switch game", null, now, "no title found yet")
    }

    private fun game(pkg: String, title: String, titleId: String?, start: Long, how: String): DetectedGame {
        val tid = titleId?.uppercase()?.let(::baseTitleId)
        // Community title databases keyed by title ID; both were checked to serve images.
        val icons = tid?.let {
            listOf("https://tinfoil.media/ti/$it/512/512", "https://api.nlib.cc/nx/$it/icon/512/512")
        }.orEmpty()
        val banners = tid?.let { listOf("https://api.nlib.cc/nx/$it/banner") }.orEmpty()
        return DetectedGame(
            launcher = Launcher.EDEN,
            packageName = pkg,
            store = "Nintendo Switch",
            title = title,
            gameId = tid,
            coverUrls = icons,
            headerUrls = banners + icons,
            startedAtMs = start,
            identifiedBy = how,
        )
    }

    private class Library(val games: List<EdenGame>, val lastPlayed: Map<String, Long>)

    private fun readLibrary(pkg: String): Library {
        val xml = RootShell.readText("/data/data/$pkg/shared_prefs/${pkg}_preferences.xml")
            ?: return Library(emptyList(), emptyMap())
        val games = ArrayList<EdenGame>()
        val lastPlayed = HashMap<String, Long>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var inGames = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "set" -> inGames = parser.getAttributeValue(null, "name") == "Games"
                        "string" -> if (inGames) parseGame(parser.nextText())?.let(games::add)
                        "long" -> {
                            val name = parser.getAttributeValue(null, "name") ?: ""
                            val value = parser.getAttributeValue(null, "value")?.toLongOrNull()
                            if (name.endsWith(LAST_PLAYED_SUFFIX) && value != null) {
                                lastPlayed[name.removeSuffix(LAST_PLAYED_SUFFIX)] = value
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "set") inGames = false
                }
            }
        } catch (e: Exception) {
            EventLog.add("Couldn't read Eden's game list: ${e.message}")
        }
        return Library(games, lastPlayed)
    }

    private fun parseGame(json: String): EdenGame? = try {
        val o = JSONObject(json)
        val path = o.optString("path")
        val programId = o.optString("programId").toULongOrNull()?.takeIf { it != 0UL }
        val tid = programId?.toString(16)?.uppercase()?.padStart(16, '0')
        if (path.isEmpty()) null else EdenGame(o.optString("title").ifBlank { fileTitle(path) }, path, tid)
    } catch (e: Exception) {
        null
    }

    /** Title ID (upper-case hex) to launch time in ms. */
    private fun readLaunched(pkg: String): Map<String, Long> {
        val text = RootShell.readText("/data/media/0/Android/data/$pkg/files/cache/launched.json") ?: return emptyMap()
        return try {
            val o = JSONObject(text)
            val out = HashMap<String, Long>()
            for (key in o.keys()) {
                val v = o.get(key)
                val seconds = when (v) {
                    is JSONObject -> v.optLong("timestamp", 0L)
                    is Number -> v.toLong()
                    else -> 0L
                }
                if (seconds > 0) out[key.uppercase()] = seconds * 1000
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun latestLoadLine(pkg: String): LoadLine? {
        val log = RootShell.quote("/data/media/0/Android/data/$pkg/files/log/eden_log.txt")
        val r = RootShell.run(
            "tail -c 262144 $log | tr -d '\\000' | grep -E 'Loading .+ \\([0-9A-Fa-f]{16}\\) \\.\\.\\.' | tail -n 1",
        )
        val line = r.lines.lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val m = LOAD_LINE.find(line) ?: return null
        return LoadLine(line.trim(), m.groupValues[1].trim(), m.groupValues[2].uppercase())
    }

    companion object {
        const val EMULATION_ACTIVITY = "org.yuzu.yuzu_emu.activities.EmulationActivity"
        private const val LAST_PLAYED_SUFFIX = "_LastPlayed"
        private val LOAD_LINE = Regex("Loading (.+) \\(([0-9A-Fa-f]{16})\\) \\.\\.\\.")
        private val TITLE_ID = Regex("0100[0-9A-Fa-f]{12}")
        private const val DAY = 24 * 60 * 60 * 1000L

        /** Updates (…800) and DLC share artwork with the base game (…000). */
        fun baseTitleId(tid: String): String =
            if (tid.length == 16 && tid.endsWith("800")) tid.substring(0, 13) + "000" else tid

        private fun freshOr(start: Long, now: Long): Long = if (start in (now - DAY)..(now + 60_000)) start else now

        /** "Super Game [0100ABCD12340000][v0].nsp" becomes "Super Game". */
        private fun fileTitle(path: String): String {
            val name = Uri.decode(path).substringAfterLast('/').substringAfterLast(':')
            return name.substringBeforeLast('.')
                .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), "")
                .replace('_', ' ')
                .trim()
                .ifBlank { "a Switch game" }
        }
    }
}

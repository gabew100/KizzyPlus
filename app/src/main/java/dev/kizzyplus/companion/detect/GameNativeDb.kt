package dev.kizzyplus.companion.detect

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.kizzyplus.companion.root.RootShell
import org.json.JSONObject
import java.io.Closeable
import java.io.File

internal data class GnRecord(
    val containerId: String,
    val store: String?,
    val gameId: String,
    val title: String,
    /** Folder names the game's files live under, compared against Wine command lines. */
    val folders: List<String>,
    val cover: List<String>,
    val header: List<String>,
) {
    fun matchesAny(paths: List<String>): Boolean = folders
        .map { it.lowercase().replace('\\', '/').trim('/') }
        .filter { it.length >= 3 }
        .any { folder -> paths.any { it.contains("/$folder/") } }
}

/**
 * Read-only view of a private copy of GameNative's Room database (`pluvia.db`).
 * Tables and columns come from the entity classes in GameNative's app/gamenative/data package.
 * The original file is never opened, so GameNative's own database stays untouched.
 */
internal class GameNativeDb private constructor(private val db: SQLiteDatabase) : Closeable {

    data class Launch(val containerId: String, val lastPlayed: Long)

    /** GameNative writes a row here every time it launches a game (MainViewModel.launchAppWithArgs). */
    fun recentLaunches(limit: Int): List<Launch> = try {
        db.rawQuery(
            "SELECT app_id, last_played FROM library_play_history ORDER BY last_played DESC LIMIT $limit",
            null,
        ).use { c ->
            val out = ArrayList<Launch>()
            while (c.moveToNext()) out.add(Launch(c.getString(0), c.getLong(1)))
            out
        }
    } catch (e: SQLiteException) {
        // Older GameNative builds have no library_play_history table.
        emptyList()
    }

    /** Looks up a container ID such as STEAM_1245620, GOG_1207658924, EPIC_3 or AMAZON_7. */
    fun lookup(containerId: String): GnRecord? {
        // Duplicated containers get a "(1)" style suffix (ContainerUtils.extractGameIdFromContainerId).
        val id = containerId.substringBefore('(').trim()
        return try {
            when {
                id.startsWith("STEAM_") -> id.removePrefix("STEAM_").toIntOrNull()?.let(::steam)
                id.startsWith("GOG_") -> gog(id.removePrefix("GOG_"))
                id.startsWith("EPIC_") -> id.removePrefix("EPIC_").toIntOrNull()?.let(::epic)
                id.startsWith("AMAZON_") -> id.removePrefix("AMAZON_").toIntOrNull()?.let(::amazon)
                id.startsWith("CUSTOM_GAME_") -> null
                else -> id.toIntOrNull()?.let(::steam)
            }
        } catch (e: SQLiteException) {
            null
        }
    }

    /** Finds the library entry whose install folder appears in a running Wine command line. */
    fun findByInstallFolder(paths: List<String>): GnRecord? {
        try {
            val steamFolders = paths.flatMap { p -> STEAM_COMMON.findAll(p).map { it.groupValues[1] } }.distinct()
            for (folder in steamFolders) {
                val like = "%\"installdir\":\"$folder\"%"
                db.rawQuery(
                    "SELECT id FROM steam_app WHERE lower(install_dir) = ? OR lower(name) = ? OR lower(config) LIKE ? LIMIT 1",
                    arrayOf(folder, folder, like),
                ).use { c -> if (c.moveToFirst()) steam(c.getInt(0))?.let { return it } }
            }
            for ((table, key, prefix) in INSTALLED_TABLES) {
                val found = try {
                    db.rawQuery("SELECT $key, install_path FROM $table WHERE is_installed = 1", null).use { c ->
                        var hit: String? = null
                        while (hit == null && c.moveToNext()) {
                            val folder = c.getString(1)?.let(::lastSegment)?.lowercase() ?: continue
                            if (folder.length >= 3 && paths.any { it.contains("/$folder/") }) hit = c.getString(0)
                        }
                        hit
                    }
                } catch (e: SQLiteException) {
                    null
                }
                if (found != null) lookup(prefix + found)?.let { return it }
            }
        } catch (e: SQLiteException) {
            return null
        }
        return null
    }

    private fun steam(id: Int): GnRecord? =
        db.rawQuery("SELECT * FROM steam_app WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val name = c.str("name") ?: return null
            val configDir = c.str("config")?.let { runCatching { JSONObject(it).optString("installDir") }.getOrNull() }
            val assets = c.str("library_assets")?.let { runCatching { JSONObject(it) }.getOrNull() }
            val capsule = assets?.optJSONObject("libraryCapsule")
            val capsules = listOfNotNull(langPick(capsule?.optJSONObject("image2x")), langPick(capsule?.optJSONObject("image")))
            val header = c.str("header_image")?.let { runCatching { langPick(JSONObject(it)) }.getOrNull() }
            // Same URL scheme GameNative uses in SteamApp.getCapsuleUrl / headerUrl.
            val base = "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/"
            GnRecord(
                containerId = "STEAM_$id",
                store = "Steam",
                gameId = id.toString(),
                title = name,
                folders = listOfNotNull(configDir?.ifBlank { null }, c.str("install_dir"), name),
                cover = capsules.map { base + it } + listOf(base + "library_600x900_2x.jpg", base + "library_600x900.jpg"),
                header = listOfNotNull(header).map { base + it } + listOf(base + "header.jpg"),
            )
        }

    private fun gog(id: String): GnRecord? =
        db.rawQuery("SELECT * FROM gog_games WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            GnRecord(
                containerId = "GOG_$id",
                store = "GOG",
                gameId = id,
                title = c.str("title") ?: return null,
                folders = listOfNotNull(c.str("install_path")?.let(::lastSegment)),
                cover = urls(c.str("vertical_cover_url"), c.str("image_url"), c.str("icon_url")),
                header = urls(c.str("background_url"), c.str("image_url"), c.str("vertical_cover_url")),
            )
        }

    private fun epic(id: Int): GnRecord? =
        db.rawQuery("SELECT * FROM epic_games WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            GnRecord(
                containerId = "EPIC_$id",
                store = "Epic Games",
                gameId = c.str("app_name") ?: id.toString(),
                title = c.str("title") ?: return null,
                folders = listOfNotNull(c.str("install_path")?.let(::lastSegment)),
                cover = urls(c.str("art_cover"), c.str("art_square"), c.str("art_portrait")),
                header = urls(c.str("art_portrait"), c.str("art_cover"), c.str("art_square")),
            )
        }

    private fun amazon(id: Int): GnRecord? =
        db.rawQuery("SELECT * FROM amazon_games WHERE app_id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            GnRecord(
                containerId = "AMAZON_$id",
                store = "Amazon",
                gameId = c.str("product_id") ?: id.toString(),
                title = c.str("title") ?: return null,
                folders = listOfNotNull(c.str("install_path")?.let(::lastSegment)),
                cover = urls(c.str("art_url"), c.str("hero_url")),
                header = urls(c.str("hero_url"), c.str("art_url")),
            )
        }

    override fun close() = db.close()

    companion object {
        private val STEAM_COMMON = Regex("/steamapps/common/([^/]+)/")
        private val INSTALLED_TABLES = listOf(
            Triple("gog_games", "id", "GOG_"),
            Triple("epic_games", "id", "EPIC_"),
            Triple("amazon_games", "app_id", "AMAZON_"),
        )

        private val copiedStamp = HashMap<String, String>()

        /**
         * Copies `pluvia.db` (and its WAL, which holds the newest rows) into the app cache and opens
         * the copy. The copy is refreshed only when the original's size or modification time changes.
         */
        fun open(context: Context, pkg: String, trace: MutableList<String>?): GameNativeDb? {
            val src = "/data/data/$pkg/databases/pluvia.db"
            val wal = "$src-wal"
            val stat = RootShell.run("stat -c '%n %Y %s' ${RootShell.quote(src)} ${RootShell.quote(wal)}")
            if (stat.shellFailed) return null
            if (stat.lines.none { it.startsWith("$src ") }) {
                trace?.add("$pkg: no database at $src")
                return null
            }
            val dir = File(context.cacheDir, "gamenative/$pkg")
            val dbFile = File(dir, "pluvia.db")
            val stamp = stat.text
            if (copiedStamp[pkg] != stamp || !dbFile.exists()) {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                if (!RootShell.copyToPrivate(src, dbFile)) {
                    trace?.add("$pkg: couldn't copy pluvia.db")
                    return null
                }
                if (stat.lines.any { it.startsWith("$wal ") }) RootShell.copyToPrivate(wal, File(dir, "pluvia.db-wal"))
                copiedStamp[pkg] = stamp
                trace?.add("$pkg: copied pluvia.db (${dbFile.length() / 1024} KB)")
            }
            return try {
                // Read-write on our own copy lets SQLite fold the copied WAL into it.
                val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                GameNativeDb(SQLiteDatabase.openDatabase(dbFile.path, null, flags))
            } catch (e: SQLiteException) {
                trace?.add("$pkg: couldn't open the copied database: ${e.message}")
                copiedStamp.remove(pkg)
                null
            }
        }

        private fun Cursor.str(column: String): String? {
            val i = getColumnIndex(column)
            if (i < 0 || isNull(i)) return null
            return try {
                getString(i)?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        /** Language maps are stored as JSON like {"english":"header.jpg"}; English wins, else any. */
        private fun langPick(obj: JSONObject?): String? {
            if (obj == null) return null
            obj.optString("english").takeIf { it.isNotBlank() }?.let { return it }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val v = obj.optString(keys.next())
                if (v.isNotBlank()) return v
            }
            return null
        }

        private fun urls(vararg candidates: String?): List<String> = candidates
            .filterNotNull()
            .map { if (it.startsWith("//")) "https:$it" else it }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()

        fun lastSegment(path: String): String? =
            path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { null }
    }
}

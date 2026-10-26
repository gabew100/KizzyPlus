package dev.kizzyplus.companion

import android.content.Context
import dev.kizzyplus.companion.detect.DetectedGame
import org.json.JSONObject

/** Names you've given detected games, used every time the same game is detected again. */
class GameNames(context: Context) {
    private val prefs = context.getSharedPreferences("game_names", Context.MODE_PRIVATE)

    data class Entry(val key: String, val name: String, val detected: String)

    fun get(key: String): Entry? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val o = JSONObject(raw)
            Entry(key, o.getString("name"), o.optString("detected"))
        } catch (e: Exception) {
            null
        }
    }

    fun set(key: String, name: String, detected: String) {
        val value = JSONObject().put("name", name).put("detected", detected).toString()
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun all(): List<Entry> = prefs.all.keys.mapNotNull(::get).sortedBy { it.name.lowercase() }

    /** The game with your saved name, if you saved one. */
    fun apply(game: DetectedGame): DetectedGame =
        get(game.nameKey)?.let { game.copy(title = it.name) } ?: game

    companion object {
        const val MAX_LENGTH = 128
    }
}

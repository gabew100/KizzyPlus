package dev.kizzyplus.companion.discord

import android.content.Context
import okhttp3.Request
import java.io.IOException

/**
 * Picks the first artwork URL that actually serves an image (Discord shows a blank square for
 * a dead link), and caches the Discord asset key for each URL so each image is registered once.
 */
class Artwork(context: Context) {
    private val assets = context.getSharedPreferences("assets", Context.MODE_PRIVATE)
    private val checked = HashMap<String, Boolean>()

    fun firstWorking(urls: List<String>): String? = urls.distinct().firstOrNull(::servesImage)

    /** Discord asset key for [url], registering it through the external-assets endpoint if needed. */
    fun discordKey(token: String, applicationId: String, url: String): String? {
        val cacheKey = "$applicationId|$url"
        assets.getString(cacheKey, null)?.let { return it }
        val key = DiscordApi.externalAsset(token, applicationId, url) ?: return null
        assets.edit().putString(cacheKey, key).apply()
        return key
    }

    private fun servesImage(url: String): Boolean {
        checked[url]?.let { return it }
        val answer = probe(Request.Builder().url(url).head())
            ?: probe(Request.Builder().url(url).header("Range", "bytes=0-0"))
        // Network errors give no answer and aren't cached, so the URL is tried again next game.
        if (answer != null) checked[url] = answer
        return answer == true
    }

    /** true/false for a definite answer; null when HEAD isn't supported or the request failed. */
    private fun probe(builder: Request.Builder): Boolean? = try {
        Http.client.newCall(builder.header("User-Agent", Http.USER_AGENT).build()).execute().use { r ->
            when {
                r.code == 405 || r.code == 501 -> null
                r.isSuccessful -> r.header("Content-Type").orEmpty().startsWith("image/")
                else -> false
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        false
    }
}

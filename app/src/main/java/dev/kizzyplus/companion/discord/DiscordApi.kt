package dev.kizzyplus.companion.discord

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object DiscordApi {
    private const val BASE = "https://discord.com/api/v10"
    private val JSON = "application/json".toMediaType()

    sealed class UserResult {
        data class Ok(val displayName: String) : UserResult()
        data object Rejected : UserResult()
        data class Failed(val message: String) : UserResult()
    }

    /** GET /users/@me, used to check a token and show who is signed in. */
    fun currentUser(token: String): UserResult {
        val request = Request.Builder()
            .url("$BASE/users/@me")
            .header("Authorization", token)
            .header("User-Agent", Http.USER_AGENT)
            .build()
        return try {
            Http.client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val user = JSONObject(response.body.string())
                        val name = user.optString("global_name").takeIf { it.isNotBlank() && it != "null" }
                            ?: user.optString("username")
                        UserResult.Ok(name)
                    }
                    401, 403 -> UserResult.Rejected
                    else -> UserResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            UserResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: org.json.JSONException) {
            UserResult.Failed("unexpected reply")
        }
    }

    /**
     * Asks Discord's media proxy to host an outside image for Rich Presence, the same call Kizzy
     * makes in ImgurApiService.getImage. Returns the asset key ("mp:external/...") or null.
     */
    fun externalAsset(token: String, applicationId: String, url: String): String? {
        val body = JSONObject().put("urls", JSONArray().put(url)).toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("$BASE/applications/$applicationId/external-assets")
            .header("Authorization", token)
            .header("User-Agent", Http.USER_AGENT)
            .post(body)
            .build()
        return try {
            Http.client.newCall(request).execute().use { response ->
                if (response.code != 200) return null
                val path = JSONArray(response.body.string()).optJSONObject(0)?.optString("external_asset_path")
                path?.takeIf { it.isNotBlank() }?.let { "mp:$it" }
            }
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
    }
}

package dev.kizzyplus.companion.discord

import android.content.Context
import dev.kizzyplus.companion.EventLog
import dev.kizzyplus.companion.Settings
import dev.kizzyplus.companion.detect.DetectedGame
import dev.kizzyplus.companion.root.RootShell
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService

/** All calls happen on the service's worker thread. */
interface PresenceBackend {
    /** Short description of the Discord side for the main screen. */
    val state: String
    fun show(game: DetectedGame)
    fun clear()
}

/** What the activity card shows, before it's encoded for a specific backend. */
data class PresenceContent(
    val name: String,
    val state: String?,
    val largeImageUrl: String?,
    val largeText: String,
    val smallImageUrl: String?,
    val smallText: String?,
    val startMs: Long,
) {
    companion object {
        fun from(game: DetectedGame, settings: Settings, artwork: Artwork): PresenceContent {
            val candidates = if (settings.artwork == Settings.ART_HEADER) game.headerUrls else game.coverUrls
            val large = artwork.firstWorking(candidates)
            val small = if (settings.showLauncher) game.launcher.logoUrl else null
            return PresenceContent(
                name = game.title.take(128),
                state = if (settings.showLauncher) game.sourceLine.take(128) else null,
                // With no game art, the launcher logo is the big image instead.
                largeImageUrl = large ?: small,
                largeText = game.title.take(128),
                smallImageUrl = if (large != null) small else null,
                smallText = if (large != null && small != null) game.launcher.label else null,
                startMs = game.startedAtMs,
            )
        }
    }
}

/** Kizzy+'s own gateway connection, opened only while something is shown. */
class BuiltInBackend(
    private val context: Context,
    private val settings: Settings,
    private val worker: ScheduledExecutorService,
    private val onChange: () -> Unit,
) : PresenceBackend {
    private val artwork = Artwork(context)
    private var gateway: DiscordGateway? = null
    private var gatewayToken: String? = null

    override var state: String = "Not connected"
        private set

    override fun show(game: DetectedGame) {
        val token = TokenStore.load(context)
        if (token == null) {
            state = "Not signed in"
            onChange()
            return
        }
        val gw = gatewayFor(token)
        if (gw.rejected) return
        val content = PresenceContent.from(game, settings, artwork)
        val appId = settings.applicationId
        val activity = JSONObject()
            .put("name", content.name)
            .put("type", 0)
            .put("application_id", appId)
            .put("timestamps", JSONObject().put("start", content.startMs))
        content.state?.let { activity.put("state", it) }
        val assets = JSONObject()
        content.largeImageUrl?.let { url -> artwork.discordKey(token, appId, url)?.let { assets.put("large_image", it) } }
        if (assets.has("large_image")) assets.put("large_text", content.largeText)
        content.smallImageUrl?.let { url -> artwork.discordKey(token, appId, url)?.let { assets.put("small_image", it) } }
        if (assets.has("small_image")) content.smallText?.let { assets.put("small_text", it) }
        if (assets.length() > 0) activity.put("assets", assets)
        if (content.largeImageUrl != null && !assets.has("large_image")) {
            EventLog.add("Discord didn't accept the artwork URL; showing ${game.title} without it")
        }
        gw.show(activity, settings.status, content.startMs)
    }

    override fun clear() {
        gateway?.clear()
    }

    private fun gatewayFor(token: String): DiscordGateway {
        gateway?.let { if (gatewayToken == token) return it }
        gateway?.clear()
        val gw = DiscordGateway(token, worker) { s, detail ->
            state = when (s) {
                DiscordGateway.State.IDLE -> "Not connected (nothing to show)"
                DiscordGateway.State.CONNECTING -> detail ?: "Connecting"
                DiscordGateway.State.CONNECTED -> "Connected"
                DiscordGateway.State.REJECTED -> detail ?: "Discord refused the connection"
            }
            onChange()
        }
        gateway = gw
        gatewayToken = token
        return gw
    }
}

/**
 * Starts Kizzy's CustomRpcService with the game as its "RPC" JSON extra (RpcConfig), and stops
 * it when the game ends. The service isn't exported, so this only works because `am` runs as
 * root. Field names come from Kizzy's domain/model/rpc/RpcConfig.kt.
 */
class KizzyBackend(context: Context, private val settings: Settings) : PresenceBackend {
    private val artwork = Artwork(context)
    private val prefs = context.getSharedPreferences("kizzy_bridge", Context.MODE_PRIVATE)

    override var state: String = "Kizzy not started"
        private set

    override fun show(game: DetectedGame) {
        val content = PresenceContent.from(game, settings, artwork)
        val rpc = JSONObject()
            .put("name", content.name)
            .put("details", "")
            .put("state", content.state ?: "")
            .put("largeImg", content.largeImageUrl ?: "")
            .put("large_text", if (content.largeImageUrl != null) content.largeText else "")
            .put("smallImg", content.smallImageUrl ?: "")
            .put("small_text", content.smallText ?: "")
            .put("timestampsStart", content.startMs.toString())
            .put("type", "0")
            .put("status", settings.status)
        // Restarting the service gives Kizzy a fresh KizzyRPC; sending a second start to a running
        // instance would open another socket on top of the first.
        val cmd = "am stopservice -n $COMPONENT >/dev/null 2>&1; " +
            "am start-foreground-service -n $COMPONENT --es RPC ${RootShell.quote(rpc.toString())}"
        val r = RootShell.run(cmd, 20_000)
        val error = r.lines.firstOrNull { it.startsWith("Error") }
        state = when {
            r.shellFailed -> "Couldn't reach Kizzy: no root shell"
            error != null -> "Kizzy didn't start: $error"
            else -> "Kizzy is showing it"
        }
        if (error == null && !r.shellFailed) prefs.edit().putBoolean("started", true).apply()
        EventLog.add("Kizzy bridge: $state")
    }

    override fun clear() {
        // Only stop Kizzy's Custom RPC if Kizzy+ started it, so a manual Custom RPC is left alone.
        if (!prefs.getBoolean("started", false)) return
        RootShell.run("am stopservice -n $COMPONENT", 15_000)
        prefs.edit().putBoolean("started", false).apply()
        state = "Kizzy stopped"
        EventLog.add("Kizzy bridge: stopped Kizzy's Custom RPC")
    }

    companion object {
        const val COMPONENT = "com.my.kizzy/com.my.kizzy.feature_rpc_base.services.CustomRpcService"
    }
}

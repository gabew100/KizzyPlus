package dev.kizzyplus.companion.discord

import dev.kizzyplus.companion.EventLog
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Minimal Discord gateway session that only sets presence. Identify properties and the
 * presence shape follow Kizzy's gateway module (kizzy.gateway.DiscordWebSocketImpl).
 *
 * Every method must be called on [worker]; socket callbacks are posted back onto it.
 * The connection exists only while there is something to show: [clear] sends an empty
 * presence and closes with code 1000, which ends the session so Discord drops it at once.
 */
class DiscordGateway(
    private val token: String,
    private val worker: ScheduledExecutorService,
    private val onState: (State, String?) -> Unit,
) {
    enum class State { IDLE, CONNECTING, CONNECTED, REJECTED }

    private var socket: WebSocket? = null
    private var generation = 0
    private var seq: Long? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var ready = false
    private var awaitingAck = false
    private var heartbeat: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null
    private var backoffMs = INITIAL_BACKOFF_MS
    private var desired: JSONObject? = null

    var rejected = false
        private set

    fun show(activity: JSONObject, status: String, since: Long) {
        desired = presence(activity, status, since)
        if (rejected) return
        if (ready) {
            send(OP_PRESENCE, desired)
        } else if (socket == null && reconnect == null) {
            connect()
        }
    }

    fun clear() {
        val active = socket != null || reconnect != null || desired != null
        desired = null
        reconnect?.cancel(false)
        reconnect = null
        socket?.let { s ->
            if (ready) s.send(payload(OP_PRESENCE, presence(null, "online", 0L)))
            s.close(1000, "presence cleared")
        }
        drop(resetSession = true)
        if (active) onState(State.IDLE, null)
    }

    private fun connect() {
        val gen = ++generation
        ready = false
        awaitingAck = false
        val url = resumeUrl?.let { "$it/?v=10&encoding=json" } ?: GATEWAY_URL
        onState(State.CONNECTING, null)
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        socket = Http.gatewayClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = post(gen) { handle(text) }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                post(gen) { lost(code, reason) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(gen) { lost(code, reason) }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                post(gen) { lost(-1, t.message ?: t.javaClass.simpleName) }
        })
    }

    private fun post(gen: Int, block: () -> Unit) {
        try {
            worker.execute { if (gen == generation) block() }
        } catch (e: RejectedExecutionException) {
            // The service is shutting down.
        }
    }

    private fun handle(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return
        }
        if (msg.has("s") && !msg.isNull("s")) seq = msg.optLong("s")
        when (msg.optInt("op", -1)) {
            OP_HELLO -> {
                startHeartbeat(msg.optJSONObject("d")?.optLong("heartbeat_interval", 41_250L) ?: 41_250L)
                val sid = sessionId
                val s = seq
                if (sid != null && s != null) {
                    send(OP_RESUME, JSONObject().put("token", token).put("session_id", sid).put("seq", s))
                } else {
                    send(OP_IDENTIFY, identify())
                }
            }
            OP_HEARTBEAT_ACK -> awaitingAck = false
            OP_HEARTBEAT -> sendHeartbeat()
            OP_DISPATCH -> when (msg.optString("t")) {
                "READY" -> {
                    val d = msg.optJSONObject("d")
                    sessionId = d?.optString("session_id")?.ifBlank { null }
                    resumeUrl = d?.optString("resume_gateway_url")?.ifBlank { null }
                    onReady()
                }
                "RESUMED" -> onReady()
            }
            OP_RECONNECT -> restart(0L)
            OP_INVALID_SESSION -> {
                if (!msg.optBoolean("d", false)) {
                    sessionId = null
                    seq = null
                    resumeUrl = null
                }
                restart(1_000L + (Math.random() * 4_000).toLong())
            }
        }
    }

    private fun onReady() {
        ready = true
        backoffMs = INITIAL_BACKOFF_MS
        onState(State.CONNECTED, null)
        val d = desired
        if (d != null) send(OP_PRESENCE, d) else clear()
    }

    /** Drops the socket but keeps the session so the next connection can resume it. */
    private fun restart(delayMs: Long) {
        socket?.close(4000, "reconnecting")
        drop(resetSession = false)
        if (desired == null) return
        reconnect = worker.schedule({
            reconnect = null
            if (desired != null && socket == null && !rejected) connect()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun lost(code: Int, reason: String?) {
        // 1000/1001 end the session; 4007 (bad sequence) and 4009 (timed out) need a new one.
        drop(resetSession = code == 1000 || code == 1001 || code == 4007 || code == 4009)
        if (code == 4004) {
            rejected = true
            desired = null
            EventLog.add("Discord rejected the token (close code 4004)")
            onState(State.REJECTED, "Discord rejected the saved token. Sign in again.")
            return
        }
        if (code in 4010..4014) {
            desired = null
            EventLog.add("Discord refused the connection (close code $code)")
            onState(State.REJECTED, "Discord refused the connection (code $code)")
            return
        }
        if (desired == null) {
            onState(State.IDLE, null)
            return
        }
        val delay = backoffMs + (Math.random() * 1_000).toLong()
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        val why = reason?.takeIf { it.isNotBlank() } ?: "code $code"
        EventLog.add("Discord connection lost ($why), retrying in ${delay / 1000} s")
        onState(State.CONNECTING, "Reconnecting in ${delay / 1000} s ($why)")
        reconnect = worker.schedule({
            reconnect = null
            if (desired != null && socket == null && !rejected) connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun drop(resetSession: Boolean) {
        socket = null
        ready = false
        heartbeat?.cancel(false)
        heartbeat = null
        generation++
        if (resetSession) {
            sessionId = null
            seq = null
            resumeUrl = null
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeat?.cancel(false)
        awaitingAck = false
        val gen = generation
        val first = (intervalMs * Math.random()).toLong()
        heartbeat = worker.scheduleWithFixedDelay({
            try {
                if (gen != generation) return@scheduleWithFixedDelay
                if (awaitingAck) {
                    // No ACK since the last beat: the connection is dead even if TCP hasn't noticed.
                    socket?.cancel()
                    lost(-2, "no heartbeat reply")
                } else {
                    sendHeartbeat()
                }
            } catch (e: Exception) {
                EventLog.add("Heartbeat error: ${e.message}")
            }
        }, first, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun sendHeartbeat() {
        awaitingAck = true
        socket?.send(JSONObject().put("op", OP_HEARTBEAT).put("d", seq ?: JSONObject.NULL).toString())
    }

    private fun send(op: Int, d: JSONObject?) {
        socket?.send(payload(op, d))
    }

    private fun payload(op: Int, d: JSONObject?): String =
        JSONObject().put("op", op).put("d", d ?: JSONObject.NULL).toString()

    private fun identify(): JSONObject = JSONObject()
        .put("token", token)
        .put("capabilities", 65)
        .put("compress", false)
        .put("large_threshold", 100)
        .put(
            "properties",
            JSONObject().put("os", "Windows").put("browser", "Discord Client").put("device", "Kizzy+"),
        )

    /**
     * afk=true matches Kizzy and keeps Discord sending push notifications to the phone app,
     * since this session never reads messages.
     */
    private fun presence(activity: JSONObject?, status: String, since: Long): JSONObject = JSONObject()
        .put("since", since)
        .put("activities", JSONArray().apply { if (activity != null) put(activity) })
        .put("status", status)
        .put("afk", true)

    companion object {
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_PRESENCE = 3
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
    }
}

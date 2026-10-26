package dev.kizzyplus.companion.discord

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Talks to the real Discord gateway with a made-up token. Discord only answers 4004
 * ("Authentication failed") after it has accepted the hello/heartbeat/identify exchange, so this
 * checks the protocol handling end to end, and that a rejected token stops instead of retrying.
 */
class DiscordGatewayLiveTest {

    @Test
    fun rejectedTokenEndsInRejectedState() {
        assumeTrue(System.getProperty("kizzyplus.liveDiscord") == "true")
        val worker = ScheduledThreadPoolExecutor(1)
        val states = LinkedBlockingQueue<Pair<DiscordGateway.State, String?>>()
        val gateway = DiscordGateway("not-a-real-token-kizzyplus-test", worker) { s, d ->
            states.add(s to d)
        }
        worker.execute {
            gateway.show(JSONObject().put("name", "Test game").put("type", 0), "online", System.currentTimeMillis())
        }

        val seen = ArrayList<Pair<DiscordGateway.State, String?>>()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val s = states.poll(1, TimeUnit.SECONDS) ?: continue
            seen.add(s)
            if (s.first == DiscordGateway.State.REJECTED) break
        }
        worker.shutdownNow()
        println("Gateway states: $seen")
        assertEquals(DiscordGateway.State.CONNECTING, seen.first().first)
        assertEquals(DiscordGateway.State.REJECTED, seen.last().first)
        assertTrue(gateway.rejected)
        // No reconnect attempts between connecting and the rejection.
        assertEquals(2, seen.size)
    }
}

package dev.kizzyplus.companion.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTimerTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun freshLaunchUsesTheLaunchTime() {
        val timer = PlayTimer()
        assertEquals(1_000L, timer.startFor("game", launchedAt = 1_000L, now = 1_000L + 20_000))
    }

    @Test
    fun shortBreakKeepsTheTimerRunning() {
        val timer = PlayTimer()
        val start = timer.startFor("game", launchedAt = 0L, now = 10_000)
        timer.markActive(30 * minute) // cleared here: screen off
        assertEquals(start, timer.startFor("game", launchedAt = 0L, now = 33 * minute))
    }

    @Test
    fun longBreakStartsAgainFromTheReturn() {
        // Launched, played, then left running in the background for hours.
        val timer = PlayTimer()
        timer.startFor("game", launchedAt = 0L, now = 10_000)
        timer.markActive(20 * minute)
        val back = 4 * hour
        assertEquals(back, timer.startFor("game", launchedAt = 0L, now = back))
    }

    @Test
    fun firstSightingOfAnOldSessionStartsNow() {
        // The service started (or the game came on screen) long after the launch.
        val timer = PlayTimer()
        assertEquals(4 * hour, timer.startFor("game", launchedAt = 0L, now = 4 * hour))
    }

    @Test
    fun anotherGameGetsItsOwnTimer() {
        val timer = PlayTimer()
        timer.startFor("a", launchedAt = 0L, now = 1_000)
        assertEquals(2_000L, timer.startFor("b", launchedAt = 2_000L, now = 3_000))
    }
}

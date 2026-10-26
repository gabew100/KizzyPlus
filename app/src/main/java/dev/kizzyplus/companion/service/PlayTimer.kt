package dev.kizzyplus.companion.service

/**
 * Decides where Discord's elapsed timer starts. It counts the current stretch of play: a short
 * break (screen off for a moment, a quick look at another app) keeps it running, while a longer
 * one starts it again from when you came back, instead of from when the game was launched hours
 * earlier and left running in the background.
 */
class PlayTimer(private val breakMs: Long = DEFAULT_BREAK_MS) {
    private var game: String? = null
    private var start = 0L
    private var lastActive = 0L

    /** Start time to show for [gameId], whose launch time detection reported as [launchedAt]. */
    fun startFor(gameId: String, launchedAt: Long, now: Long): Long {
        start = when {
            gameId == game && now - lastActive <= breakMs -> start
            // A fresh launch: its own launch time is more accurate than "now".
            now - launchedAt in 0..breakMs -> launchedAt
            else -> now
        }
        game = gameId
        lastActive = now
        return start
    }

    /** Call while the game is on Discord, and once more when it's cleared. */
    fun markActive(now: Long) {
        if (game != null) lastActive = now
    }

    companion object {
        const val DEFAULT_BREAK_MS = 5 * 60_000L
    }
}

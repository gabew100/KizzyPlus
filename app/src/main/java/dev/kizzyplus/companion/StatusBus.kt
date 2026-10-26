package dev.kizzyplus.companion

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** What the service is doing, for the main screen. The service and UI share one process. */
object StatusBus {
    data class Status(
        val running: Boolean = false,
        val headline: String = "",
        val detail: String = "",
        val discord: String = "",
        val problem: String? = null,
        /** Set while a game is shown, so the screen can offer to rename it. */
        val gameKey: String? = null,
        val detectedTitle: String? = null,
    )

    @Volatile
    var current = Status()
        private set

    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun publish(status: Status) {
        current = status
        main.post { listeners.forEach { it(status) } }
    }

    fun listen(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun unlisten(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }
}

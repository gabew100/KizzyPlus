package dev.kizzyplus.companion

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Small in-memory log shown in the troubleshooting report. */
object EventLog {
    private const val MAX = 150
    private val entries = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        Log.i("KizzyPlus", message)
        entries.addLast("${time.format(Date())}  $message")
        while (entries.size > MAX) entries.removeFirst()
    }

    @Synchronized
    fun dump(): String = entries.joinToString("\n")
}

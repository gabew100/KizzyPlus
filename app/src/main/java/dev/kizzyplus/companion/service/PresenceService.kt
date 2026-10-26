package dev.kizzyplus.companion.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import dev.kizzyplus.companion.App
import dev.kizzyplus.companion.EventLog
import dev.kizzyplus.companion.GameNames
import dev.kizzyplus.companion.R
import dev.kizzyplus.companion.Settings
import dev.kizzyplus.companion.StatusBus
import dev.kizzyplus.companion.detect.DetectedGame
import dev.kizzyplus.companion.detect.ForegroundApp
import dev.kizzyplus.companion.detect.GameDetector
import dev.kizzyplus.companion.detect.Outcome
import dev.kizzyplus.companion.discord.BuiltInBackend
import dev.kizzyplus.companion.discord.KizzyBackend
import dev.kizzyplus.companion.discord.PresenceBackend
import dev.kizzyplus.companion.discord.TokenStore
import dev.kizzyplus.companion.ui.MainActivity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Checks for a running game every few seconds while the screen is on and unlocked, and keeps
 * Discord in sync. Screen off: no polling, no Discord connection, no wake lock (apart from a
 * few seconds to send the "clear" before the CPU sleeps).
 *
 * All state lives on [worker], one thread, so detection, Discord and screen events never race.
 */
class PresenceService : Service() {

    private lateinit var settings: Settings
    private lateinit var detector: GameDetector
    private lateinit var worker: ScheduledThreadPoolExecutor
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager

    private var backend: PresenceBackend? = null
    private var backendKind: String? = null
    private var tickTask: ScheduledFuture<*>? = null
    private var unlockWatch: ScheduledFuture<*>? = null
    private lateinit var foreground: ForegroundApp
    private lateinit var names: GameNames
    private val playTimer = PlayTimer()
    private var shown: DetectedGame? = null
    private var lastOutcome: Outcome? = null
    private var lastNotification: Pair<String, String>? = null
    private var lastOnScreen: String? = null
    private var lastDetectionAt = 0L

    @Volatile
    private var screenUsable = false

    @Volatile
    private var destroyed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                // A lit lock screen doesn't count as playing; wait for the unlock.
                Intent.ACTION_SCREEN_ON -> if (keyguard.isKeyguardLocked) watchForUnlock() else onScreenUsable()
                Intent.ACTION_USER_PRESENT -> onScreenUsable()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        settings = Settings(this)
        detector = GameDetector(this)
        foreground = ForegroundApp(this)
        names = GameNames(this)
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        worker = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "kizzyplus-worker") }.apply {
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }

        val first = notification(getString(R.string.notif_watching), getString(R.string.notif_watching_detail))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, first)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Exported because USER_PRESENT is sent by SystemUI, which is a separate app; a not-exported
        // receiver never gets it. All three actions are protected broadcasts only the system can send.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(screenReceiver, filter)

        screenUsable = power.isInteractive && !keyguard.isKeyguardLocked
        EventLog.add("Service started (screen ${if (screenUsable) "on" else "off or locked"})")
        submit { tick() }
        if (power.isInteractive && keyguard.isKeyguardLocked) watchForUnlock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> submit {
                // Settings or the Discord login changed: rebuild the backend and re-send.
                tickTask?.cancel(false)
                backend?.clear()
                backend = null
                backendKind = null
                shown = null
                lastDetectionAt = 0L
                tick()
            }
            ACTION_RENAMED -> submit {
                // Re-check now so the new name goes out on the open connection without a reconnect.
                tickTask?.cancel(false)
                lastDetectionAt = 0L
                tick()
            }
            ACTION_CLEAR -> submit {
                // "Clear my Discord status": a game still on screen comes back on the next check.
                backend?.clear()
                shown = null
                lastDetectionAt = 0L
                EventLog.add("Cleared Discord status on request")
                publish()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        submit {
            tickTask?.cancel(false)
            backend?.clear()
            backend = null
            shown = null
        }
        worker.shutdown()
        EventLog.add("Service stopped")
        StatusBus.publish(StatusBus.Status(running = false))
        super.onDestroy()
    }

    private fun onScreenOff() {
        screenUsable = false
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kizzyplus:clear").apply {
            setReferenceCounted(false)
            acquire(15_000)
        }
        submit {
            tickTask?.cancel(false)
            tickTask = null
            unlockWatch?.cancel(false)
            unlockWatch = null
            val had = shown
            if (had != null) playTimer.markActive(System.currentTimeMillis())
            backend?.clear()
            shown = null
            EventLog.add(if (had != null) "Screen off: cleared ${had.title} from Discord" else "Screen off: paused")
            publish()
            // Give OkHttp a moment to flush the empty presence and close frame before the CPU sleeps.
            worker.schedule({ if (lock.isHeld) lock.release() }, 3, TimeUnit.SECONDS)
        }
    }

    private fun onScreenUsable() {
        if (screenUsable) return
        screenUsable = true
        submit {
            unlockWatch?.cancel(false)
            unlockWatch = null
            EventLog.add("Screen unlocked: checking for a game")
            tickTask?.cancel(false)
            lastDetectionAt = 0L
            tick()
        }
    }

    /**
     * USER_PRESENT is the normal unlock signal. In case a ROM or unlock method skips it, check the
     * lock state every 2 s while the lock screen is lit. This is a local KeyguardManager call, no root.
     */
    private fun watchForUnlock() {
        submit {
            unlockWatch?.cancel(false)
            val started = SystemClock.elapsedRealtime()
            unlockWatch = worker.scheduleWithFixedDelay({
                guarded {
                    val giveUp = !power.isInteractive || SystemClock.elapsedRealtime() - started > 10 * 60_000L
                    if (giveUp || !keyguard.isKeyguardLocked) {
                        unlockWatch?.cancel(false)
                        unlockWatch = null
                        if (!giveUp) onScreenUsable()
                    }
                }
            }, 2, 2, TimeUnit.SECONDS)
        }
    }

    private fun tick() {
        tickTask = null
        if (destroyed) return
        if (!settings.enabled) {
            stopSelf()
            return
        }
        if (!screenUsable) return
        if (!power.isInteractive || keyguard.isKeyguardLocked) {
            // Missed broadcast safety net: never leave a presence up on a dark or locked screen.
            screenUsable = false
            val had = shown
            if (had != null) playTimer.markActive(System.currentTimeMillis())
            backend?.clear()
            shown = null
            EventLog.add(if (had != null) "Screen off or locked: cleared ${had.title} from Discord" else "Screen off or locked: paused")
            publish()
            return
        }

        val target = currentBackend()
        // Only the app on screen counts: a game left running behind the home screen isn't shared.
        val onScreen = foreground.current()
        val watchedOnScreen = onScreen == null || detector.isWatched(settings, onScreen)
        val now = SystemClock.elapsedRealtime()
        val dueForCheck = onScreen != lastOnScreen || now - lastDetectionAt >= settings.pollSeconds * 1000L
        lastOnScreen = onScreen
        // Still being shown up to this moment; the timer measures breaks from the last of these.
        if (shown != null) playTimer.markActive(System.currentTimeMillis())

        if (!watchedOnScreen) {
            if (shown != null) {
                EventLog.add("${shown?.launcher?.label} left the screen: cleared ${shown?.title}")
                target.clear()
                shown = null
            }
            lastOutcome = Outcome.NotOnScreen
        } else if (dueForCheck) {
            lastDetectionAt = now
            val outcome = try {
                detector.detect(settings, onlyPackage = onScreen)
            } catch (e: Exception) {
                EventLog.add("Detection error: $e")
                Outcome.Failed("Detection error: ${e.message}")
            }
            lastOutcome = outcome
            if (outcome is Outcome.Playing) {
                val named = names.apply(outcome.game)
                val start = playTimer.startFor(
                    "${named.packageName}|${named.nameKey}",
                    named.startedAtMs,
                    System.currentTimeMillis(),
                )
                val game = named.copy(startedAtMs = start)
                if (shown?.presenceKey != game.presenceKey) {
                    shown = game
                    EventLog.add("Showing ${game.title} (${game.sourceLine})")
                    try {
                        target.show(game)
                    } catch (e: Exception) {
                        EventLog.add("Couldn't send presence: $e")
                    }
                }
            } else if (shown != null) {
                EventLog.add("Game ended: cleared ${shown?.title}")
                target.clear()
                shown = null
            }
        }
        publish()
        // The on-screen check is a cheap local call; the root process check above runs only every
        // pollSeconds, or right away when GameNative or Eden comes to the front.
        tickTask = worker.schedule({ guarded { tick() } }, SCREEN_CHECK_SECONDS, TimeUnit.SECONDS)
    }

    private fun currentBackend(): PresenceBackend {
        val kind = settings.backend
        backend?.let { if (backendKind == kind) return it }
        backend?.clear()
        val created = if (kind == Settings.BACKEND_KIZZY) {
            KizzyBackend(this, settings)
        } else {
            BuiltInBackend(this, settings, worker) { submit { publish() } }
        }
        backend = created
        backendKind = kind
        return created
    }

    private fun publish() {
        val game = shown
        val outcome = lastOutcome
        val signedOut = settings.backend == Settings.BACKEND_BUILTIN && !TokenStore.hasToken(this)
        val discord = if (signedOut) "Not signed in to Discord" else backend?.state ?: "Not connected"
        val problem = when {
            outcome is Outcome.Failed -> outcome.reason
            signedOut -> "Sign in to Discord in Kizzy+ so it can share your game."
            discord.startsWith("Discord rejected") || discord.startsWith("Discord refused") -> discord
            else -> null
        }
        val since = DateFormat.getTimeInstance(DateFormat.SHORT)
        val status = when {
            !screenUsable -> StatusBus.Status(
                running = true,
                headline = getString(R.string.notif_paused),
                detail = getString(R.string.notif_paused_detail),
                discord = discord,
                problem = problem,
            )
            game != null -> StatusBus.Status(
                running = true,
                headline = game.title,
                detail = "${game.sourceLine} · playing since ${since.format(Date(game.startedAtMs))}",
                discord = discord,
                problem = problem,
                gameKey = game.nameKey,
                detectedTitle = game.detectedTitle,
            )
            outcome is Outcome.Failed -> StatusBus.Status(
                running = true,
                headline = "Kizzy+ can't check for games",
                detail = outcome.reason,
                discord = discord,
                problem = problem,
            )
            outcome is Outcome.NotOnScreen -> StatusBus.Status(
                running = true,
                headline = getString(R.string.now_not_on_screen),
                detail = getString(R.string.now_not_on_screen_detail),
                discord = discord,
                problem = problem,
            )
            else -> StatusBus.Status(
                running = true,
                headline = "Nothing running",
                detail = "Start a game in GameNative or Eden and it shows up here within ${settings.pollSeconds} seconds.",
                discord = discord,
                problem = problem,
            )
        }
        StatusBus.publish(status)

        val (title, text) = when {
            !screenUsable -> getString(R.string.notif_paused) to getString(R.string.notif_paused_detail)
            problem != null -> getString(R.string.notif_problem) to problem
            game != null -> getString(R.string.notif_showing, game.title) to game.sourceLine
            else -> getString(R.string.notif_watching) to getString(R.string.notif_watching_detail)
        }
        if (lastNotification != title to text && !destroyed) {
            lastNotification = title to text
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
        }
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, App.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_presence)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    private fun submit(block: () -> Unit) {
        try {
            worker.execute { guarded(block) }
        } catch (e: RejectedExecutionException) {
            // Shutting down.
        }
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            EventLog.add("Unexpected error: $e")
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.kizzyplus.companion.action.REFRESH"
        const val ACTION_CLEAR = "dev.kizzyplus.companion.action.CLEAR"
        const val ACTION_RENAMED = "dev.kizzyplus.companion.action.RENAMED"
        private const val NOTIFICATION_ID = 1
        private const val SCREEN_CHECK_SECONDS = 3L

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PresenceService::class.java))
        }

        /** Re-reads settings and the login; used after anything changes on the main screen. */
        fun refresh(context: Context) {
            if (running) context.startService(Intent(context, PresenceService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceService::class.java))
        }

        fun renamed(context: Context) {
            if (running) context.startService(Intent(context, PresenceService::class.java).setAction(ACTION_RENAMED))
        }

        fun clear(context: Context) {
            if (running) context.startService(Intent(context, PresenceService::class.java).setAction(ACTION_CLEAR))
        }
    }
}

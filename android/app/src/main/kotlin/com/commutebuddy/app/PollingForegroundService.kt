package com.commutebuddy.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PollingForegroundService : Service() {

    companion object {
        private const val TAG = "PollingService"
        const val NOTIFICATION_CHANNEL_ID = "commute_polling"
        const val ACTION_POLL_COMPLETED = "com.commutebuddy.app.POLL_COMPLETED"
        const val ACTION_WAKE_AND_POLL = "com.commutebuddy.app.WAKE_AND_POLL"
        const val ACTION_POLL_NOW = "com.commutebuddy.app.POLL_NOW"
        const val EXTRA_DIRECTION = "direction"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_COMMUTE = "commute_prefs"
        private const val KEY_DIRECTION = "current_direction"
        internal const val KEY_LAST_POLLED_DIRECTION = "last_polled_direction"
        private const val DIRECTION_TO_WORK = "TO_WORK"
        private const val DIRECTION_TO_HOME = "TO_HOME"

        /** Far-future sentinel returned when polling is effectively disabled (no active days + background OFF). */
        internal const val FAR_FUTURE_OFFSET_MS = 7 * 24 * 60 * 60_000L

        internal fun isActiveDay(dayOfWeek: Int, activeDays: Set<Int>): Boolean =
            activeDays.contains(dayOfWeek)

        /**
         * Pure scheduling function — testable without a running service.
         *
         * Three tiers:
         *  1. Active day + inside a commute window → interval-based (intensive) polling
         *  2. Background polling ON, outside intensive times → top of next hour, or sooner
         *     if an active-day window starts before then
         *  3. Background polling OFF, outside intensive times → earliest window start on
         *     the next active day; far-future sentinel if no active days or windows
         */
        internal fun computeNextAlarmTimeMs(settings: PollingSettings, nowMs: Long): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)

            val onActiveDay = isActiveDay(dayOfWeek, settings.activeDays)
            val insideWindow = settings.windows.any { it.isActive(hour, minute) }

            // Tier 1: Active day + inside window → interval-based polling
            if (onActiveDay && insideWindow) {
                return nowMs + settings.intervalMinutes * 60_000L
            }

            // Outside intensive polling times
            if (!settings.backgroundPolling) {
                // Tier 3: Background OFF → skip to earliest window start on the next active day
                if (settings.activeDays.isEmpty() || settings.windows.isEmpty()) {
                    return nowMs + FAR_FUTURE_OFFSET_MS
                }
                return settings.windows
                    .map { nextOccurrenceOnActiveDay(it.startHour, it.startMinute, nowMs, settings.activeDays) }
                    .min()
            }

            // Tier 2: Background ON → top of next hour, or sooner if an active-day window fires first
            val topOfNextHour = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val earliestActiveWindowStart = if (settings.activeDays.isNotEmpty() && settings.windows.isNotEmpty()) {
                settings.windows
                    .map { nextOccurrenceOnActiveDay(it.startHour, it.startMinute, nowMs, settings.activeDays) }
                    .filter { it < topOfNextHour }
                    .minOrNull()
            } else {
                null
            }

            return earliestActiveWindowStart ?: topOfNextHour
        }

        /**
         * Returns the next epoch ms strictly after [afterMs] at which [hour]:[minute] occurs
         * on a day contained in [activeDays]. Advances by 1 day at a time up to 7 days.
         */
        internal fun nextOccurrenceOnActiveDay(hour: Int, minute: Int, afterMs: Long, activeDays: Set<Int>): Long {
            val after = Calendar.getInstance().apply { timeInMillis = afterMs }
            val target = Calendar.getInstance().apply {
                timeInMillis = afterMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (!target.after(after)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            var attempts = 0
            while (!activeDays.contains(target.get(Calendar.DAY_OF_WEEK)) && attempts < 7) {
                target.add(Calendar.DAY_OF_YEAR, 1)
                attempts++
            }
            return target.timeInMillis
        }

        /**
         * Determines the direction for a background poll based on the active commute window.
         *
         * - Window at index 0 active → TO_WORK
         * - Window at index 1 active → TO_HOME
         * - Outside all windows → [lastPolledDirection] if non-null, else TO_WORK
         */
        internal fun resolvePollingDirection(
            settings: PollingSettings,
            hour: Int,
            minute: Int,
            lastPolledDirection: String?
        ): String {
            settings.windows.forEachIndexed { index, window ->
                if (window.isActive(hour, minute)) {
                    return if (index == 0) DIRECTION_TO_WORK else DIRECTION_TO_HOME
                }
            }
            return lastPolledDirection ?: DIRECTION_TO_WORK
        }

        /**
         * Returns true if [result] should be forwarded to watches via [notifyAll].
         *
         * Error results are suppressed so the watches keep displaying their last good
         * status rather than showing a raw exception message.
         */
        internal fun shouldNotifyWatches(result: PipelineResult): Boolean = when (result) {
            is PipelineResult.GoodService -> true
            is PipelineResult.Decision -> true
            is PipelineResult.RateLimited -> false
            is PipelineResult.Error -> false
        }

        /**
         * Exponential backoff with ±15% jitter. [attempt] is clamped to 4, giving a
         * maximum base delay of 480 s (~8 min) — within the 10-min wake lock safety margin.
         */
        internal fun computeBackoffDelayMs(attempt: Int, baseDelayMs: Long = 30_000L): Long {
            val clamped = attempt.coerceAtMost(4)
            val base = baseDelayMs shl clamped  // baseDelayMs * 2^clamped
            val jitter = (base * 0.15 * (Math.random() * 2 - 1)).toLong()
            return base + jitter
        }

        /**
         * Returns true only when [result] is a network-level MTA fetch failure
         * (IOException: DNS, timeout, socket). Gemini/parse errors and all
         * non-error results return false — only fetch errors should trigger retries.
         */
        internal fun isFetchError(result: PipelineResult): Boolean =
            result is PipelineResult.Error && result.exception is IOException

        /**
         * Runs [runPipeline] and retries on MTA fetch failures with exponential backoff.
         *
         * Retries stop when:
         *  - the pipeline returns a non-fetch-error result, OR
         *  - the next backoff would push past [nextAlarmTimeMs] (avoids duplicate polls), OR
         *  - total elapsed delay exceeds [maxRetryDurationMs] (wake lock safety margin).
         *
         * [nowMs] and [log] are injectable for testing.
         */
        internal suspend fun runWithRetry(
            runPipeline: suspend () -> PipelineResult,
            nextAlarmTimeMs: Long,
            maxRetryDurationMs: Long = 8 * 60_000L,
            nowMs: () -> Long = { System.currentTimeMillis() },
            log: (String) -> Unit = {}
        ): PipelineResult {
            var result = runPipeline()
            var attempt = 0
            val startMs = nowMs()
            while (isFetchError(result)) {
                val backoffMs = computeBackoffDelayMs(attempt)
                val elapsed = nowMs() - startMs
                val timeToAlarm = nextAlarmTimeMs - nowMs()
                if (elapsed + backoffMs > maxRetryDurationMs || backoffMs >= timeToAlarm) {
                    log("Retries exhausted (attempt $attempt), skipping watch notification")
                    break
                }
                log("Fetch error on attempt $attempt, retrying in ${backoffMs}ms")
                delay(backoffMs)
                attempt++
                result = runPipeline()
            }
            if (attempt > 0 && !isFetchError(result)) {
                log("Retry succeeded on attempt $attempt")
            }
            return result
        }

        /**
         * Runs the fetch -> pipeline -> retry sequence for an explicit [direction] — no
         * time-of-day resolution. Shared by the scheduled poll (which resolves its own
         * direction first via [resolvePollingDirection]) and the on-demand poll (whose
         * direction is already explicit), so direction is the only thing that differs
         * between the two call sites.
         */
        internal suspend fun executeDirectionalPoll(
            direction: String,
            profile: CommuteProfile,
            generateContent: suspend (String) -> String?,
            rateLimiter: ApiRateLimiter,
            nextAlarmTimeMs: Long,
            log: (String) -> Unit = {}
        ): PipelineResult = runWithRetry(
            runPipeline = {
                CommutePipeline.run(
                    direction = direction,
                    profile = profile,
                    generateContent = generateContent,
                    rateLimiter = rateLimiter
                )
            },
            nextAlarmTimeMs = nextAlarmTimeMs,
            log = log
        )

        /**
         * Runs [block] only if [mutex] is currently free, returning its result — or null,
         * invoking [onSkipped], if a poll is already in flight. Used to serialize the
         * scheduled and on-demand poll paths against the same [pollMutex] so a rapid
         * double-tap or an on-demand request racing the alarm cannot run overlapping
         * pipelines.
         */
        internal suspend fun <T> runIfNotInFlight(
            mutex: Mutex,
            onSkipped: () -> Unit = {},
            block: suspend () -> T
        ): T? {
            if (!mutex.tryLock()) {
                onSkipped()
                return null
            }
            return try {
                block()
            } finally {
                mutex.unlock()
            }
        }

        /** Returns the next epoch ms strictly after [afterMs] at which [hour]:[minute] occurs. */
        internal fun nextOccurrenceOf(hour: Int, minute: Int, afterMs: Long): Long {
            val after = Calendar.getInstance().apply { timeInMillis = afterMs }
            val target = Calendar.getInstance().apply {
                timeInMillis = afterMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (!target.after(after)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollMutex = Mutex()

    private lateinit var rateLimiter: ApiRateLimiter
    private lateinit var profileRepository: CommuteProfileRepository
    private lateinit var pollingSettingsRepository: PollingSettingsRepository

    @Volatile private var generativeModel: GenerativeModel? = null
    private lateinit var garminNotifier: GarminNotifier
    private lateinit var notifiers: List<WatchNotifier>

    private var lastPollTimeMs: Long? = null
    private var nextPollTimeMs: Long? = null
    private var initialized = false

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            Log.d(TAG, "Exact alarm permission: ${alarmManager.canScheduleExactAlarms()}")
        }

        rateLimiter = ApiRateLimiter(getSharedPreferences("rate_limiter", Context.MODE_PRIVATE))
        profileRepository = CommuteProfileRepository(
            getSharedPreferences(PREFS_COMMUTE, Context.MODE_PRIVATE)
        )
        pollingSettingsRepository = PollingSettingsRepository(
            getSharedPreferences("polling_prefs", Context.MODE_PRIVATE)
        )

        if (!initialized) {
            initialized = true
            garminNotifier = GarminNotifier.getInstance(this)
            notifiers = listOf(garminNotifier, WearOsNotifier())
            val profile = profileRepository.load()
            initGeminiFlash(profile)
            notifiers.forEach { it.initialize(this) }
        }

        // Ensure the notification channel exists before startForeground(). The channel is also
        // created in MainActivity.onCreate(), but if the service was started by BootReceiver
        // before the user has opened the app, MainActivity has never run and the channel does
        // not yet exist — causing startForeground() to silently drop the notification.
        // Creating a channel that already exists is a no-op, so this is always safe.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.polling_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_WAKE_AND_POLL) {
            val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CommuteBuddy:poll")
            wl.acquire(10 * 60 * 1000L) // 10-min safety timeout
            serviceScope.launch {
                try {
                    runIfNotInFlight(pollMutex, onSkipped = { Log.w(TAG, "Poll skipped: already in flight") }) {
                        poll()
                    }
                } finally {
                    wl.release()
                    scheduleNextAlarm()
                }
            }
        } else if (intent?.action == ACTION_POLL_NOW) {
            val direction = intent.getStringExtra(EXTRA_DIRECTION)
            if (direction == DIRECTION_TO_WORK || direction == DIRECTION_TO_HOME) {
                // On-demand poll: explicit direction, bypasses window/active-day gating and
                // resolvePollingDirection entirely. Still guarded by pollMutex + ApiRateLimiter.
                // Does NOT touch alarm scheduling — the next scheduled alarm is untouched.
                serviceScope.launch {
                    runIfNotInFlight(pollMutex, onSkipped = { Log.w(TAG, "On-demand poll skipped: already in flight") }) {
                        pollNow(direction)
                    }
                }
            } else {
                Log.w(TAG, "ACTION_POLL_NOW ignored: invalid direction=$direction")
            }
        } else {
            // null intent (OS restart) or standard start — schedule only, no immediate poll
            scheduleNextAlarm()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::garminNotifier.isInitialized) garminNotifier.unregisterForIncomingMessages()
        getSystemService(AlarmManager::class.java).cancel(buildAlarmPendingIntent())
        // WearOsNotifier uses the Wearable Data Layer API (putDataItem) which has no
        // lifecycle teardown — there are no registered receivers to unregister and
        // in-flight tasks are fire-and-forget Tasks handled by Play Services.
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Scheduling
    // -------------------------------------------------------------------------

    private fun scheduleNextAlarm() {
        val triggerAtMs = getNextAlarmTimeMs()
        nextPollTimeMs = triggerAtMs
        getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, buildAlarmPendingIntent())
        val delayMs = triggerAtMs - System.currentTimeMillis()
        val mins = delayMs / 60_000
        val secs = (delayMs % 60_000) / 1000
        Log.d(TAG, "Next alarm in ${mins}m ${secs}s")
        updateNotification()
    }

    private fun buildAlarmPendingIntent(): PendingIntent {
        val intent = Intent(this, PollingForegroundService::class.java)
            .setAction(ACTION_WAKE_AND_POLL)
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getNextAlarmTimeMs(): Long {
        val settings = pollingSettingsRepository.load()
        return computeNextAlarmTimeMs(settings, System.currentTimeMillis())
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    private suspend fun poll() {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val settings = pollingSettingsRepository.load()
        val prefs = getSharedPreferences(PREFS_COMMUTE, Context.MODE_PRIVATE)
        val lastPolledDirection = prefs.getString(KEY_LAST_POLLED_DIRECTION, null)
        val direction = resolvePollingDirection(settings, hour, minute, lastPolledDirection)
        if (settings.windows.any { it.isActive(hour, minute) }) {
            prefs.edit().putString(KEY_LAST_POLLED_DIRECTION, direction).apply()
        }
        Log.d(TAG, "Polling started (direction=$direction)")
        runPollAndNotify(direction)
    }

    /**
     * On-demand poll for an explicit [direction], requested by a watch outside the
     * configured polling windows. Shares [runPollAndNotify] with the scheduled [poll] —
     * direction is the only difference between the two paths.
     */
    private suspend fun pollNow(direction: String) {
        Log.d(TAG, "On-demand poll requested (direction=$direction)")
        runPollAndNotify(direction)
    }

    private suspend fun runPollAndNotify(direction: String) {
        val model = generativeModel
        if (model == null) {
            Log.w(TAG, "Poll skipped: model not ready")
            return
        }

        val profile = profileRepository.load()
        val result = executeDirectionalPoll(
            direction = direction,
            profile = profile,
            generateContent = { promptText -> model.generateContent(promptText).text },
            rateLimiter = rateLimiter,
            nextAlarmTimeMs = getNextAlarmTimeMs(),
            log = { msg -> Log.d(TAG, msg) }
        )

        lastPollTimeMs = System.currentTimeMillis()
        sendBroadcast(Intent(ACTION_POLL_COMPLETED))
        Log.d(TAG, "Poll result: ${result::class.simpleName}")

        when (result) {
            is PipelineResult.GoodService -> notifyAll(result.status)
            is PipelineResult.Decision -> notifyAll(result.status)
            is PipelineResult.RateLimited -> Log.w(TAG, "Poll rate limited: ${result.reason}")
            is PipelineResult.Error -> Log.e(TAG, "Poll error: ${result.message}")
        }

        updateNotification()
    }

    private suspend fun notifyAll(status: CommuteStatus) {
        notifyAll(notifiers, status) { notifier, e ->
            Log.e(TAG, "${notifier::class.simpleName} failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Gemini init
    // -------------------------------------------------------------------------

    private fun initGeminiFlash(profile: CommuteProfile) {
        val modelName = BuildConfig.GEMINI_MODEL_NAME
        val systemPrompt = SystemPromptBuilder.buildSystemPrompt(profile)
        generativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(
                modelName = modelName,
                generationConfig = generationConfig {
                    temperature = 0f
                    thinkingConfig = thinkingConfig {
                        thinkingLevel = ThinkingLevel.LOW
                    }
                },
                systemInstruction = content { text(systemPrompt) }
            )
        Log.d(TAG, "Firebase AI: model ready ($modelName)")
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        val lastStr = lastPollTimeMs?.let { "Last: ${sdf.format(Date(it))}" }
            ?: "Waiting for first poll…"
        val nextStr = nextPollTimeMs?.let { "Next: ${sdf.format(Date(it))}" } ?: ""
        val text = if (nextStr.isNotEmpty()) "$lastStr · $nextStr" else lastStr
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.polling_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}

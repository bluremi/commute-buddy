package com.commutebuddy.app

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PollingForegroundService : Service() {

    companion object {
        private const val TAG = "PollingService"
        const val NOTIFICATION_CHANNEL_ID = "commute_polling"
        private const val NOTIFICATION_ID = 1
        private const val GARMIN_APP_UUID = "e5f12c3a-7b04-4d8e-9a6f-2c1b3e5d7a9f"
        private const val PREFS_COMMUTE = "commute_prefs"
        private const val KEY_DIRECTION = "current_direction"
        private const val DIRECTION_TO_WORK = "TO_WORK"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    private lateinit var rateLimiter: ApiRateLimiter
    private lateinit var profileRepository: CommuteProfileRepository
    private lateinit var pollingSettingsRepository: PollingSettingsRepository

    @Volatile private var generativeModel: GenerativeModel? = null
    @Volatile private var sdkReady = false
    @Volatile private var connectedDevice: IQDevice? = null
    @Volatile private var targetApp: IQApp? = null
    private var connectIQ: ConnectIQ? = null

    private var lastPollTimeMs: Long? = null
    private var nextPollTimeMs: Long? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting")

        rateLimiter = ApiRateLimiter(getSharedPreferences("rate_limiter", Context.MODE_PRIVATE))
        profileRepository = CommuteProfileRepository(
            getSharedPreferences(PREFS_COMMUTE, Context.MODE_PRIVATE)
        )
        pollingSettingsRepository = PollingSettingsRepository(
            getSharedPreferences("polling_prefs", Context.MODE_PRIVATE)
        )

        val profile = profileRepository.load()
        initGeminiFlash(profile)
        initConnectIQ()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        pollingJob?.cancel()
        pollingJob = serviceScope.launch { runPollingLoop() }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            connectIQ?.shutdown(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down ConnectIQ", e)
        }
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Scheduling loop
    // -------------------------------------------------------------------------

    private suspend fun runPollingLoop() {
        while (true) {
            val delayMs = getNextDelayMs()
            nextPollTimeMs = System.currentTimeMillis() + delayMs
            updateNotification()

            val mins = delayMs / 60_000
            val secs = (delayMs % 60_000) / 1000
            Log.d(TAG, "Next poll in ${mins}m ${secs}s")

            delay(delayMs)
            poll()
        }
    }

    private fun getNextDelayMs(): Long {
        val settings = pollingSettingsRepository.load()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val inWindow = settings.windows.any { it.isActive(hour, minute) }
        return if (inWindow) settings.intervalMinutes * 60_000L else 60 * 60_000L
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    private suspend fun poll() {
        Log.d(TAG, "Polling started")
        val model = generativeModel
        if (model == null) {
            Log.w(TAG, "Poll skipped: model not ready")
            return
        }

        val direction = getSharedPreferences(PREFS_COMMUTE, Context.MODE_PRIVATE)
            .getString(KEY_DIRECTION, DIRECTION_TO_WORK) ?: DIRECTION_TO_WORK
        val profile = profileRepository.load()

        val result = CommutePipeline.run(
            direction = direction,
            profile = profile,
            generateContent = { promptText -> model.generateContent(promptText).text },
            rateLimiter = rateLimiter
        )

        lastPollTimeMs = System.currentTimeMillis()
        Log.d(TAG, "Poll result: ${result::class.simpleName}")

        when (result) {
            is PipelineResult.GoodService -> sendBle(result.status)
            is PipelineResult.Decision -> sendBle(result.status)
            is PipelineResult.RateLimited -> Log.w(TAG, "Poll rate limited: ${result.reason}")
            is PipelineResult.Error -> {
                Log.e(TAG, "Poll error: ${result.message}")
                sendBle(result.status)
            }
        }

        updateNotification()
    }

    // -------------------------------------------------------------------------
    // BLE send
    // -------------------------------------------------------------------------

    private suspend fun sendBle(status: CommuteStatus) {
        if (!sdkReady) { Log.d(TAG, "BLE send skipped: SDK not ready"); return }
        val device = connectedDevice ?: run { Log.d(TAG, "BLE send skipped: no device"); return }
        val app = targetApp ?: run { Log.d(TAG, "BLE send skipped: app not installed"); return }
        withContext(Dispatchers.Main) {
            connectIQ?.sendMessage(
                device, app, status.toConnectIQMap(),
                object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(
                        device: IQDevice,
                        app: IQApp,
                        msgStatus: ConnectIQ.IQMessageStatus
                    ) {
                        if (msgStatus == ConnectIQ.IQMessageStatus.SUCCESS) {
                            Log.d(TAG, "BLE send success")
                        } else {
                            Log.w(TAG, "BLE send failed: ${msgStatus.name}")
                        }
                    }
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // ConnectIQ init
    // -------------------------------------------------------------------------

    private fun initConnectIQ() {
        connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS)
        connectIQ?.initialize(this, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                Log.d(TAG, "ConnectIQ SDK ready")
                sdkReady = true
                discoverDevice()
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                Log.e(TAG, "ConnectIQ init error: $status")
                sdkReady = false
            }

            override fun onSdkShutDown() {
                Log.d(TAG, "ConnectIQ SDK shut down")
                sdkReady = false
            }
        })
    }

    private fun discoverDevice() {
        val iq = connectIQ ?: return
        val devices = try {
            iq.connectedDevices
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connected devices", e)
            null
        }
        val device = devices?.firstOrNull { dev ->
            try { iq.getDeviceStatus(dev) == IQDevice.IQDeviceStatus.CONNECTED } catch (e: Exception) { false }
        }
        if (device == null) {
            connectedDevice = null
            targetApp = null
            Log.d(TAG, "No connected Garmin device")
            return
        }
        connectedDevice = device
        Log.d(TAG, "Found device: ${device.friendlyName}")
        loadAppInfo(device)
    }

    private fun loadAppInfo(device: IQDevice) {
        connectIQ?.getApplicationInfo(
            GARMIN_APP_UUID, device,
            object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    Log.d(TAG, "Garmin app found on ${device.friendlyName}")
                    targetApp = app
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    Log.w(TAG, "Garmin app not installed on ${device.friendlyName}")
                    targetApp = null
                }
            }
        )
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

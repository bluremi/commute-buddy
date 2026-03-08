package com.commutebuddy.app

import android.text.SpannableStringBuilder
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CommuteBuddy"
        private const val GARMIN_APP_UUID = "e5f12c3a-7b04-4d8e-9a6f-2c1b3e5d7a9f"
        private const val PREFS_COMMUTE = "commute_prefs"
        private const val KEY_DIRECTION = "current_direction"
        private const val DIRECTION_TO_WORK = "TO_WORK"
        private const val DIRECTION_TO_HOME = "TO_HOME"
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
    }

    private lateinit var statusTextView: TextView
    private lateinit var apiUsageTextView: TextView
    private lateinit var fetchLiveButton: Button
    private lateinit var resultsTextView: TextView
    private lateinit var directionToggle: MaterialButtonToggleGroup
    private lateinit var configureCommuteButton: MaterialButton
    private lateinit var pollingSettingsButton: MaterialButton
    private lateinit var configureCommuteLauncher: ActivityResultLauncher<Intent>
    private lateinit var exactAlarmSettingsLauncher: ActivityResultLauncher<Intent>

    private lateinit var connectIQ: ConnectIQ
    private lateinit var rateLimiter: ApiRateLimiter
    private lateinit var profileRepository: CommuteProfileRepository
    private lateinit var profile: CommuteProfile
    private lateinit var commutePrefs: SharedPreferences
    private var currentDirection: String = DIRECTION_TO_WORK
    private var generativeModel: GenerativeModel? = null
    private var sdkReady = false
    private var connectedDevice: IQDevice? = null
    private var targetApp: IQApp? = null

    private val pollCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateApiUsageDisplay()
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val rootLayout = findViewById<ViewGroup>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        configureCommuteLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            profile = profileRepository.load()
            initGeminiFlash()
        }

        exactAlarmSettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            startPollingServiceIfEnabled()
        }

        statusTextView = findViewById(R.id.statusTextView)
        apiUsageTextView = findViewById(R.id.apiUsageTextView)

        fetchLiveButton = findViewById(R.id.fetchLiveButton)
        resultsTextView = findViewById(R.id.resultsTextView)
        directionToggle = findViewById(R.id.directionToggle)
        configureCommuteButton = findViewById(R.id.configureCommuteButton)
        configureCommuteButton.setOnClickListener {
            configureCommuteLauncher.launch(Intent(this, CommuteProfileActivity::class.java))
        }
        pollingSettingsButton = findViewById(R.id.pollingSettingsButton)
        pollingSettingsButton.setOnClickListener {
            startActivity(Intent(this, PollingSettingsActivity::class.java))
        }
        fetchLiveButton.setOnClickListener { onFetchLiveClicked() }

        commutePrefs = getSharedPreferences(PREFS_COMMUTE, Context.MODE_PRIVATE)
        profileRepository = CommuteProfileRepository(commutePrefs)
        profile = profileRepository.load()

        currentDirection = commutePrefs.getString(KEY_DIRECTION, DIRECTION_TO_WORK) ?: DIRECTION_TO_WORK
        val initialButtonId = if (currentDirection == DIRECTION_TO_HOME) R.id.btnToHome else R.id.btnToWork
        directionToggle.check(initialButtonId)
        directionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentDirection = if (checkedId == R.id.btnToHome) DIRECTION_TO_HOME else DIRECTION_TO_WORK
                commutePrefs.edit().putString(KEY_DIRECTION, currentDirection).apply()
            }
        }

        rateLimiter = ApiRateLimiter(
            getSharedPreferences("rate_limiter", Context.MODE_PRIVATE)
        )
        initGeminiFlash()

        createPollingNotificationChannel()
        requestBluetoothPermissionsThenStartService()

        initConnectIQ()
    }

    override fun onResume() {
        super.onResume()
        updateApiUsageDisplay()
        ContextCompat.registerReceiver(
            this, pollCompletedReceiver,
            IntentFilter(PollingForegroundService.ACTION_POLL_COMPLETED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(pollCompletedReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (sdkReady) {
            try {
                connectIQ.shutdown(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down ConnectIQ", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // ConnectIQ SDK init
    // -------------------------------------------------------------------------

    private fun initConnectIQ() {
        connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS)
        setStatus(R.string.status_sdk_initializing)

        connectIQ.initialize(this, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                Log.d(TAG, "ConnectIQ SDK ready")
                sdkReady = true
                setStatus(R.string.status_sdk_ready)
                discoverDevice()
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                Log.e(TAG, "ConnectIQ init error: $status")
                sdkReady = false
                setStatus(getString(R.string.status_sdk_error, status.name))
            }

            override fun onSdkShutDown() {
                Log.d(TAG, "ConnectIQ SDK shut down")
                sdkReady = false
                setStatus(R.string.status_sdk_shutdown)
            }
        })
    }

    // -------------------------------------------------------------------------
    // Device + app discovery
    // -------------------------------------------------------------------------

    private fun discoverDevice() {
        val devices: List<IQDevice>? = try {
            connectIQ.connectedDevices
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connected devices", e)
            null
        }

        val connectedDevice = devices?.firstOrNull { device ->
            try {
                connectIQ.getDeviceStatus(device) == IQDevice.IQDeviceStatus.CONNECTED
            } catch (e: Exception) {
                Log.e(TAG, "Error getting device status", e)
                false
            }
        }

        if (connectedDevice == null) {
            this.connectedDevice = null
            targetApp = null
            setStatus(R.string.status_no_device)
            return
        }

        val device = connectedDevice
        this.connectedDevice = device
        Log.d(TAG, "Found device: ${device.friendlyName}")
        setStatus(getString(R.string.status_device_found, device.friendlyName))
        loadAppInfo(device)
    }

    private fun loadAppInfo(device: IQDevice) {
        connectIQ.getApplicationInfo(
            GARMIN_APP_UUID,
            device,
            object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    Log.d(TAG, "App found on ${device.friendlyName}")
                    targetApp = app
                    setStatus(getString(R.string.status_app_ready, device.friendlyName))
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    Log.w(TAG, "App not installed on ${device.friendlyName}")
                    targetApp = null
                    setStatus(R.string.status_app_not_installed)
                }
            }
        )
    }

    private val allApiButtons get() = listOf(fetchLiveButton)

    private fun initGeminiFlash() {
        setAllApiButtonsEnabled(false)
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
        setAllApiButtonsEnabled(true)
        Log.d(TAG, "Firebase AI: model ready ($modelName)")
    }

    private fun setAllApiButtonsEnabled(enabled: Boolean) {
        allApiButtons.forEach { it.isEnabled = enabled }
    }

    // -------------------------------------------------------------------------
    // Live MTA Alerts pipeline (FEAT-03, refactored FEAT-08)
    // -------------------------------------------------------------------------

    private fun onFetchLiveClicked() {
        val model = generativeModel ?: run {
            resultsTextView.text = getString(R.string.ai_model_not_ready)
            return
        }

        setAllApiButtonsEnabled(false)
        resultsTextView.text = getString(R.string.live_fetching)

        lifecycleScope.launch {
            try {
                val result = CommutePipeline.run(
                    direction = currentDirection,
                    profile = profile,
                    generateContent = { promptText -> model.generateContent(promptText).text },
                    rateLimiter = rateLimiter
                )
                handlePipelineResult(result)
            } finally {
                setAllApiButtonsEnabled(true)
            }
        }
    }

    private fun handlePipelineResult(result: PipelineResult) {
        val prefix = getString(R.string.live_output_prefix)
        when (result) {
            is PipelineResult.GoodService -> {
                val routeList = profile.monitoredRoutes().sorted().joinToString(", ")
                resultsTextView.text = getString(R.string.live_no_alerts, routeList)
                sendCommuteStatus(result.status)
            }
            is PipelineResult.Decision -> {
                val parsed = result.status
                val badgeSize = resources.displayMetrics.density * 22f
                val ssb = SpannableStringBuilder()
                ssb.append(prefix).append("\n")
                if (result.warning != null) ssb.append("⚠ ${result.warning}").append("\n")
                ssb.append("\n")
                ssb.append(getString(R.string.ai_result_status, parsed.action, parsed.statusLabel)).append("\n")
                ssb.append("Affected: ").append(MtaLineColors.buildRouteBadges(parsed.affectedRoutes, badgeSize)).append("\n")
                ssb.append(getString(R.string.ai_result_reason, parsed.summary)).append("\n")
                parsed.rerouteHint?.let { ssb.append(getString(R.string.ai_result_reroute_hint, it)).append("\n") }
                ssb.append(getString(R.string.ai_result_time, parsed.timestamp))
                resultsTextView.text = ssb
                sendCommuteStatus(parsed)
            }
            is PipelineResult.RateLimited -> {
                resultsTextView.text = result.reason
            }
            is PipelineResult.Error -> {
                val displayMsg = result.exception?.let { classifyApiError(it) } ?: result.message
                resultsTextView.text = "$prefix\n$displayMsg"
                sendCommuteStatus(result.status)
            }
        }
        updateApiUsageDisplay()
    }

    // -------------------------------------------------------------------------
    // BLE send (FEAT-04)
    // -------------------------------------------------------------------------

    private fun sendCommuteStatus(status: CommuteStatus) {
        if (!sdkReady) {
            resultsTextView.append("\n" + getString(R.string.ble_send_skipped, "SDK not ready"))
            return
        }
        val device = connectedDevice ?: run {
            resultsTextView.append("\n" + getString(R.string.ble_send_skipped, "no device"))
            return
        }
        val app = targetApp ?: run {
            resultsTextView.append("\n" + getString(R.string.ble_send_skipped, "app not installed"))
            return
        }
        connectIQ.sendMessage(device, app, status.toConnectIQMap(),
            object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(
                    device: IQDevice,
                    app: IQApp,
                    msgStatus: ConnectIQ.IQMessageStatus
                ) {
                    runOnUiThread {
                        if (msgStatus == ConnectIQ.IQMessageStatus.SUCCESS) {
                            resultsTextView.append("\n" + getString(R.string.ble_send_success))
                        } else {
                            resultsTextView.append("\n" + getString(R.string.ble_send_failed, msgStatus.name))
                        }
                    }
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Walks the exception cause chain to produce a user-readable error message.
     * The GenAI SDK wraps network failures in a generic SDK exception, so we
     * need to look past the top-level message to find the real cause.
     */
    private fun classifyApiError(e: Exception): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is IOException) return getString(R.string.ai_error_network)
            cause = cause.cause
        }
        val msg = e.message ?: "Unknown error"
        return when {
            msg.contains("NOT_FOUND", ignoreCase = true) ||
            msg.contains("not found", ignoreCase = true) ->
                getString(R.string.ai_error_model_not_found, BuildConfig.GEMINI_MODEL_NAME)
            else -> getString(R.string.ai_error_api, msg)
        }
    }

    private fun updateApiUsageDisplay() {
        val (count, cap) = rateLimiter.getDailyUsage()
        apiUsageTextView.text = if (count >= cap) {
            "Daily limit reached: $count/$cap — polling paused until tomorrow"
        } else {
            "API usage: $count/$cap today"
        }
    }

    private fun createPollingNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PollingForegroundService.NOTIFICATION_CHANNEL_ID,
                getString(R.string.polling_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestBluetoothPermissionsThenStartService() {
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startPollingServiceIfEnabled()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            startPollingServiceIfEnabled()
        }
    }

    private fun startPollingServiceIfEnabled() {
        val settings = PollingSettingsRepository(
            getSharedPreferences("polling_prefs", Context.MODE_PRIVATE)
        ).load()
        if (!settings.enabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                exactAlarmSettingsLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:$packageName"))
                )
                return
            }
        }

        startForegroundService(Intent(this, PollingForegroundService::class.java))
    }

    private fun setStatus(resId: Int) {
        runOnUiThread { statusTextView.text = getString(resId) }
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusTextView.text = message }
    }
}

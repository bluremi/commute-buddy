package com.commutebuddy.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
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
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CommuteBuddy"
        private const val GARMIN_APP_UUID = "e5f12c3a-7b04-4d8e-9a6f-2c1b3e5d7a9f"

        private const val SYSTEM_PROMPT = """You are a transit alert summarizer for a smartwatch. Given a raw NYC MTA alert, respond with ONLY a JSON object — no markdown, no code fences, no explanation.

Required fields:
- "status": integer 0 (Normal service), 1 (Delays or planned work), or 2 (Major disruption or suspended service)
- "route_string": affected route(s), comma-separated, max 15 chars (e.g. "N,W,Q")
- "reason": brief reason, max 40 chars (e.g. "Signal problems at 96 St")
- "timestamp": current Unix epoch time as an integer (seconds)

Example: {"status":1,"route_string":"Q","reason":"Signal problems near 96 St","timestamp":1709312400}"""
    }

    private lateinit var statusTextView: TextView
    private lateinit var tierButton1: Button
    private lateinit var tierButton2: Button
    private lateinit var tierButton3: Button
    private lateinit var tierButton4: Button
    private lateinit var fetchLiveButton: Button
    private lateinit var resultsTextView: TextView

    private lateinit var connectIQ: ConnectIQ
    private lateinit var rateLimiter: ApiRateLimiter
    private var generativeModel: GenerativeModel? = null
    private var sdkReady = false
    private var connectedDevice: IQDevice? = null
    private var targetApp: IQApp? = null

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

        statusTextView = findViewById(R.id.statusTextView)

        tierButton1 = findViewById(R.id.tierButton1)
        tierButton2 = findViewById(R.id.tierButton2)
        tierButton3 = findViewById(R.id.tierButton3)
        tierButton4 = findViewById(R.id.tierButton4)
        fetchLiveButton = findViewById(R.id.fetchLiveButton)
        resultsTextView = findViewById(R.id.resultsTextView)
        tierButton1.setOnClickListener { onTierClicked(MtaTestData.Tier.TIER_1, 1) }
        tierButton2.setOnClickListener { onTierClicked(MtaTestData.Tier.TIER_2, 2) }
        tierButton3.setOnClickListener { onTierClicked(MtaTestData.Tier.TIER_3, 3) }
        tierButton4.setOnClickListener { onTierClicked(MtaTestData.Tier.TIER_4, 4) }
        fetchLiveButton.setOnClickListener { onFetchLiveClicked() }

        rateLimiter = ApiRateLimiter(
            getSharedPreferences("rate_limiter", Context.MODE_PRIVATE)
        )
        initGeminiFlash()

        initConnectIQ()
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

    // -------------------------------------------------------------------------
    // AI Summarization POC (FEAT-02)
    // -------------------------------------------------------------------------

    private val allApiButtons get() = listOf(tierButton1, tierButton2, tierButton3, tierButton4, fetchLiveButton)

    private fun initGeminiFlash() {
        setAllApiButtonsEnabled(false)

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "Gemini Flash: API key not configured")
            resultsTextView.text = getString(R.string.ai_api_key_missing)
            return
        }

        val modelName = BuildConfig.GEMINI_MODEL_NAME
        generativeModel = GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            systemInstruction = content { text(SYSTEM_PROMPT) }
        )
        setAllApiButtonsEnabled(true)
        Log.d(TAG, "Gemini Flash: model ready ($modelName)")
        resultsTextView.text = getString(R.string.ai_model_ready, modelName)
    }

    private fun setAllApiButtonsEnabled(enabled: Boolean) {
        allApiButtons.forEach { it.isEnabled = enabled }
    }

    private fun onTierClicked(tier: MtaTestData.Tier, tierNumber: Int) {
        val alertText = MtaTestData.getAlertText(tier)
        val model = generativeModel ?: return

        when (val result = rateLimiter.tryAcquire()) {
            is RateLimitResult.Denied -> {
                resultsTextView.text = result.reason
                return
            }
            is RateLimitResult.Allowed -> {
                val warning = result.warningMessage
                val prefix = getString(R.string.ai_output_prefix, tierNumber)
                setAllApiButtonsEnabled(false)
                resultsTextView.text = "$prefix\n${getString(R.string.ai_processing)}"
                rateLimiter.setInFlight(true)

                lifecycleScope.launch {
                    try {
                        val response = model.generateContent(alertText)
                        val rawText = response.text
                        if (rawText.isNullOrBlank()) {
                            resultsTextView.text = "$prefix\n${getString(R.string.ai_error_empty_response)}"
                            return@launch
                        }
                        try {
                            val parsed = CommuteStatus.fromJson(rawText)
                            resultsTextView.text = buildString {
                                appendLine(prefix)
                                if (warning != null) {
                                    appendLine("⚠ $warning")
                                }
                                appendLine()
                                appendLine(getString(R.string.ai_result_status, parsed.status, parsed.statusLabel))
                                appendLine(getString(R.string.ai_result_route, parsed.routeString))
                                appendLine(getString(R.string.ai_result_reason, parsed.reason))
                                append(getString(R.string.ai_result_time, parsed.timestamp))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse Gemini response", e)
                            resultsTextView.text = buildString {
                                appendLine(prefix)
                                appendLine(getString(R.string.ai_parse_error, e.message))
                                appendLine()
                                appendLine(getString(R.string.ai_result_raw_output))
                                append(rawText)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Gemini API error", e)
                        resultsTextView.text = "$prefix\n${classifyApiError(e)}"
                    } finally {
                        rateLimiter.setInFlight(false)
                        setAllApiButtonsEnabled(true)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Live MTA Alerts pipeline (FEAT-03)
    // -------------------------------------------------------------------------

    private fun onFetchLiveClicked() {
        val model = generativeModel ?: run {
            resultsTextView.text = getString(R.string.ai_api_key_missing)
            return
        }

        val prefix = getString(R.string.live_output_prefix)
        setAllApiButtonsEnabled(false)
        resultsTextView.text = getString(R.string.live_fetching)

        lifecycleScope.launch {
            try {
                // Step 1: Fetch
                val fetchResult = MtaAlertFetcher.fetchAlerts()
                if (fetchResult.isFailure) {
                    Log.e(TAG, "MTA fetch failed", fetchResult.exceptionOrNull())
                    resultsTextView.text = getString(R.string.live_fetch_error)
                    val errMsg = (fetchResult.exceptionOrNull()?.message ?: "Fetch failed").take(40)
                    sendCommuteStatus(CommuteStatus(CommuteStatus.STATUS_ERROR, MONITORED_ROUTES.joinToString(","), errMsg, System.currentTimeMillis() / 1000))
                    return@launch
                }
                val jsonString = fetchResult.getOrThrow()

                // Step 2: Parse + filter
                resultsTextView.text = getString(R.string.live_parsing)
                val alerts = MtaAlertParser.parseAlerts(jsonString)
                if (alerts.isEmpty() && jsonString.isNotBlank()) {
                    resultsTextView.text = getString(R.string.live_parse_error, "no entities parsed")
                    sendCommuteStatus(CommuteStatus(CommuteStatus.STATUS_ERROR, MONITORED_ROUTES.joinToString(","), "Feed parse error", System.currentTimeMillis() / 1000))
                    return@launch
                }
                val routeFiltered = MtaAlertParser.filterByRoutes(alerts, MONITORED_ROUTES)
                val filtered = MtaAlertParser.filterByActivePeriod(routeFiltered, System.currentTimeMillis() / 1000)
                if (filtered.isEmpty()) {
                    val routeList = MONITORED_ROUTES.joinToString(", ")
                    resultsTextView.text = getString(R.string.live_no_alerts, routeList)
                    sendCommuteStatus(CommuteStatus(CommuteStatus.STATUS_NORMAL, MONITORED_ROUTES.joinToString(","), "Good service", System.currentTimeMillis() / 1000))
                    return@launch
                }

                // Step 3: Rate-limit check
                val promptText = MtaAlertParser.buildPromptText(filtered)
                when (val rateLimitResult = rateLimiter.tryAcquire()) {
                    is RateLimitResult.Denied -> {
                        resultsTextView.text = rateLimitResult.reason
                        return@launch
                    }
                    is RateLimitResult.Allowed -> {
                        val warning = rateLimitResult.warningMessage
                        resultsTextView.text = getString(R.string.live_summarizing)
                        rateLimiter.setInFlight(true)
                        try {
                            val response = model.generateContent(promptText)
                            val rawText = response.text
                            if (rawText.isNullOrBlank()) {
                                resultsTextView.text = "$prefix\n${getString(R.string.ai_error_empty_response)}"
                                return@launch
                            }
                            try {
                                val parsed = CommuteStatus.fromJson(rawText)
                                resultsTextView.text = buildString {
                                    appendLine(prefix)
                                    if (warning != null) appendLine("⚠ $warning")
                                    appendLine()
                                    appendLine(getString(R.string.ai_result_status, parsed.status, parsed.statusLabel))
                                    appendLine(getString(R.string.ai_result_route, parsed.routeString))
                                    appendLine(getString(R.string.ai_result_reason, parsed.reason))
                                    append(getString(R.string.ai_result_time, parsed.timestamp))
                                }
                                sendCommuteStatus(parsed)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse Gemini response", e)
                                resultsTextView.text = buildString {
                                    appendLine(prefix)
                                    appendLine(getString(R.string.ai_parse_error, e.message))
                                    appendLine()
                                    appendLine(getString(R.string.ai_result_raw_output))
                                    append(rawText)
                                }
                                val errMsg = (e.message ?: "Parse failed").take(40)
                                sendCommuteStatus(CommuteStatus(CommuteStatus.STATUS_ERROR, MONITORED_ROUTES.joinToString(","), errMsg, System.currentTimeMillis() / 1000))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Gemini API error during live fetch", e)
                            resultsTextView.text = "$prefix\n${classifyApiError(e)}"
                            val errMsg = (e.message ?: "API error").take(40)
                            sendCommuteStatus(CommuteStatus(CommuteStatus.STATUS_ERROR, MONITORED_ROUTES.joinToString(","), errMsg, System.currentTimeMillis() / 1000))
                        } finally {
                            rateLimiter.setInFlight(false)
                        }
                    }
                }
            } finally {
                setAllApiButtonsEnabled(true)
            }
        }
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

    private fun setStatus(resId: Int) {
        runOnUiThread { statusTextView.text = getString(resId) }
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusTextView.text = message }
    }
}

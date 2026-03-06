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
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CommuteBuddy"
        private const val GARMIN_APP_UUID = "e5f12c3a-7b04-4d8e-9a6f-2c1b3e5d7a9f"

        private const val SYSTEM_PROMPT = """You are a commute advisor for an NYC subway rider. Your job is to analyze MTA service alerts and make a clear recommendation: proceed normally, expect minor delays, reroute, or stay home.

COMMUTE PROFILE:
TO_WORK:
  legs:
    - lines: [N, W]
      direction: Manhattan-bound
      from: Astoria
      to: 59th St
    - lines: [4, 5]
      direction: Downtown
      from: 59th St
      to: 14th St
    - lines: [6]
      direction: Downtown
      from: 14th St
      to: Spring St
  alternates: [F, R, 7]

TO_HOME:
  legs:
    - lines: [6]
      direction: Uptown
      from: Spring St
      to: 14th St
    - lines: [4, 5]
      direction: Uptown
      from: 14th St
      to: 59th St
    - lines: [N, W]
      direction: Queens-bound
      from: 59th St
      to: Astoria
  alternates: [F, R, 7]

DECISION FRAMEWORK:
- NORMAL: No alerts affect the commute, or alerts are resolved/irrelevant.
- MINOR_DELAYS: Active alerts cause delays on the primary route, but service IS STILL RUNNING. The commuter should allow extra time but does not need to change route. Use this when the alert type is "Delays" without words like "extensive", "significant", "extremely limited", or "suspended". Standard signal problems, train removal, and sick customers are typically MINOR_DELAYS unless described as severe.
- REROUTE: Primary route has significant disruption (suspended service, extensive delays, service described as "extremely limited", or skipped stops on the user's segment). At least one alternate line is running normally or with minor delays.
- STAY_HOME: Primary route is severely disrupted AND all alternate lines are also significantly impacted. Only valid when direction is TO_WORK. When direction is TO_HOME, use REROUTE or MINOR_DELAYS instead (the user must get home).

DIRECTION MATCHING RULES:
- Each commute leg has a direction (e.g., "Manhattan-bound", "Downtown").
- Only flag alerts that affect the leg's direction or explicitly say "both directions." Ignore alerts for the opposite direction on the same line.
- MTA alerts reference direction using terms like "Manhattan-bound", "Queens-bound", "Uptown", "Downtown", "Bronx-bound", "Brooklyn-bound", "northbound", "southbound", or station-pair ranges (e.g., "No [N] between Queensboro Plaza and Times Sq" implies Manhattan-bound service is affected). Match these against the leg direction.
- If an alert does not mention any direction, assume it affects both directions.

ALERT FRESHNESS RULES:
- Planned work with a defined active_period: trust the time window; if current time is outside the window, ignore the alert.
- Real-time delays posted <30 min ago: treat as active.
- Real-time delays posted >60 min ago with no update: ASSUME RESOLVED and downgrade severity by one level (REROUTE → MINOR_DELAYS, MINOR_DELAYS → NORMAL). Exception: only keep the original severity if the alert text describes an inherently long-duration incident (e.g., "person struck by train", "FDNY on scene", "structural damage", "derailment"). Routine issues like signal problems, train cleaning, and sick customers are typically resolved within 60 minutes.
- Real-time delays posted 30-60 min ago: use judgment based on severity of the incident described.

ALTERNATE LINE EVALUATION:
- When recommending REROUTE, check all alerts for the alternate lines (F, R, 7).
- If an alternate has no active alerts, mention it as clear in reroute_hint.
- If ALL alternates are also significantly disrupted, escalate to STAY_HOME (TO_WORK) or note the situation in summary (TO_HOME).
- Do not recommend a specific transfer sequence or walking route — just report which alternate lines are running.

Respond with ONLY a JSON object matching this schema. No markdown fencing, no explanation outside the JSON:
{
  "action": "NORMAL" or "MINOR_DELAYS" or "REROUTE" or "STAY_HOME",
  "summary": "<brief explanation, max 80 chars>",
  "reroute_hint": "<which alternates are clear, max 60 chars — include ONLY when action is REROUTE, omit otherwise>",
  "affected_routes": "<comma-separated impacted lines from primary legs, or empty string if NORMAL>"
}"""
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
            generationConfig = generationConfig { temperature = 0f },
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
                                appendLine(getString(R.string.ai_result_status, parsed.action, parsed.statusLabel))
                                appendLine(getString(R.string.ai_result_route, parsed.affectedRoutes))
                                appendLine(getString(R.string.ai_result_reason, parsed.summary))
                                parsed.rerouteHint?.let { appendLine(getString(R.string.ai_result_reroute_hint, it)) }
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
                    val errMsg = (fetchResult.exceptionOrNull()?.message ?: "Fetch failed").take(80)
                    sendCommuteStatus(CommuteStatus(action = CommuteStatus.ACTION_NORMAL, summary = errMsg, affectedRoutes = "", rerouteHint = null, timestamp = System.currentTimeMillis() / 1000))
                    return@launch
                }
                val jsonString = fetchResult.getOrThrow()

                // Step 2: Parse + filter
                resultsTextView.text = getString(R.string.live_parsing)
                val alerts = MtaAlertParser.parseAlerts(jsonString)
                if (alerts.isEmpty() && jsonString.isNotBlank()) {
                    resultsTextView.text = getString(R.string.live_parse_error, "no entities parsed")
                    sendCommuteStatus(CommuteStatus(action = CommuteStatus.ACTION_NORMAL, summary = "Feed parse error", affectedRoutes = "", rerouteHint = null, timestamp = System.currentTimeMillis() / 1000))
                    return@launch
                }
                val routeFiltered = MtaAlertParser.filterByRoutes(alerts, MONITORED_ROUTES)
                val filtered = MtaAlertParser.filterByActivePeriod(routeFiltered, System.currentTimeMillis() / 1000)
                if (filtered.isEmpty()) {
                    val routeList = MONITORED_ROUTES.joinToString(", ")
                    resultsTextView.text = getString(R.string.live_no_alerts, routeList)
                    sendCommuteStatus(CommuteStatus(action = CommuteStatus.ACTION_NORMAL, summary = "Good service", affectedRoutes = "", rerouteHint = null, timestamp = System.currentTimeMillis() / 1000))
                    return@launch
                }

                // Step 3: Rate-limit check
                val promptText = MtaAlertParser.buildPromptText(filtered, "TO_WORK", System.currentTimeMillis() / 1000)
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
                                    appendLine(getString(R.string.ai_result_status, parsed.action, parsed.statusLabel))
                                    appendLine(getString(R.string.ai_result_route, parsed.affectedRoutes))
                                    appendLine(getString(R.string.ai_result_reason, parsed.summary))
                                    parsed.rerouteHint?.let { appendLine(getString(R.string.ai_result_reroute_hint, it)) }
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
                                val errMsg = (e.message ?: "Parse failed").take(80)
                                sendCommuteStatus(CommuteStatus(action = CommuteStatus.ACTION_NORMAL, summary = errMsg, affectedRoutes = "", rerouteHint = null, timestamp = System.currentTimeMillis() / 1000))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Gemini API error during live fetch", e)
                            resultsTextView.text = "$prefix\n${classifyApiError(e)}"
                            val errMsg = (e.message ?: "API error").take(80)
                            sendCommuteStatus(CommuteStatus(action = CommuteStatus.ACTION_NORMAL, summary = errMsg, affectedRoutes = "", rerouteHint = null, timestamp = System.currentTimeMillis() / 1000))
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

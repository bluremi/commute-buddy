package com.commutebuddy.app

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.google.mlkit.genai.prompt.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.launch
import kotlin.random.Random

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

    private lateinit var codeTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var sendButton: Button
    private lateinit var tierLabel: TextView
    private lateinit var testAiButton: Button
    private lateinit var resultsTextView: TextView

    private lateinit var connectIQ: ConnectIQ
    private lateinit var generativeModel: GenerativeModel
    private var currentTierIndex = 0
    private var sdkReady = false
    private var connectedDevice: IQDevice? = null
    private var targetApp: IQApp? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        codeTextView = findViewById(R.id.codeTextView)
        statusTextView = findViewById(R.id.statusTextView)
        sendButton = findViewById(R.id.sendButton)
        sendButton.setOnClickListener { onSendCodeClicked() }

        tierLabel = findViewById(R.id.tierLabel)
        testAiButton = findViewById(R.id.testAiButton)
        resultsTextView = findViewById(R.id.resultsTextView)
        testAiButton.setOnClickListener { onTestAiClicked() }
        initGeminiNano()

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
    // Send flow
    // -------------------------------------------------------------------------

    private fun onSendCodeClicked() {
        val code = Random.nextInt(1000, 10000)
        codeTextView.text = code.toString()

        if (!sdkReady) {
            setStatus(R.string.status_sdk_not_ready)
            return
        }

        val device = connectedDevice
        if (device == null) {
            discoverDevice()
            setStatus(R.string.status_no_device)
            return
        }

        val app = targetApp
        if (app == null) {
            setStatus(R.string.status_app_not_installed)
            return
        }

        setStatus(R.string.status_sending)
        connectIQ.sendMessage(device, app, code, object : ConnectIQ.IQSendMessageListener {
            override fun onMessageStatus(device: IQDevice, app: IQApp, status: ConnectIQ.IQMessageStatus) {
                try {
                    Log.d(TAG, "Send status: $status")
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                        setStatus(getString(R.string.status_sent, code))
                    } else {
                        setStatus(getString(R.string.status_send_failed, status.name))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in send callback", e)
                    setStatus(getString(R.string.status_send_failed, e.message ?: "Unknown error"))
                }
            }
        })
    }

    // -------------------------------------------------------------------------
    // AI Summarization POC (FEAT-02)
    // -------------------------------------------------------------------------

    private fun initGeminiNano() {
        testAiButton.isEnabled = false
        tierLabel.text = getString(MtaTestData.tiers[currentTierIndex].labelResId)
        resultsTextView.text = getString(R.string.ai_checking_model)

        generativeModel = Generation.getClient()

        lifecycleScope.launch {
            try {
                when (generativeModel.checkStatus()) {
                    FeatureStatus.AVAILABLE -> {
                        Log.d(TAG, "Gemini Nano: available")
                        testAiButton.isEnabled = true
                        resultsTextView.text = getString(R.string.ai_model_ready)
                    }
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                        Log.w(TAG, "Gemini Nano: not yet downloaded")
                        resultsTextView.text = getString(R.string.ai_model_downloading)
                    }
                    else -> {
                        Log.w(TAG, "Gemini Nano: unavailable on this device")
                        resultsTextView.text = getString(R.string.ai_model_unavailable)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Nano availability check failed", e)
                resultsTextView.text = getString(R.string.ai_model_unavailable)
            }
        }
    }

    private fun onTestAiClicked() {
        currentTierIndex = (currentTierIndex + 1) % MtaTestData.tiers.size
        updateTierDisplay()
    }

    private fun updateTierDisplay() {
        val tier = MtaTestData.tiers[currentTierIndex]
        tierLabel.text = getString(tier.labelResId)
        resultsTextView.text = MtaTestData.getAlertText(tier)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setStatus(resId: Int) {
        runOnUiThread { statusTextView.text = getString(resId) }
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusTextView.text = message }
    }
}

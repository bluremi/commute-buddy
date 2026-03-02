package com.commutebuddy.app

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CommuteBuddy"
        private const val GARMIN_APP_UUID = "e5f12c3a-7b04-4d8e-9a6f-2c1b3e5d7a9f"
    }

    private lateinit var codeTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var sendButton: Button

    private lateinit var connectIQ: ConnectIQ
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
    // Helpers
    // -------------------------------------------------------------------------

    private fun setStatus(resId: Int) {
        runOnUiThread { statusTextView.text = getString(resId) }
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusTextView.text = message }
    }
}

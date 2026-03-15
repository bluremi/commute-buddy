package com.commutebuddy.app

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends [CommuteStatus] to a paired Garmin watch via the ConnectIQ BLE SDK.
 *
 * No-ops gracefully when Bluetooth is off, Garmin Connect is not installed,
 * no device is paired, or the Commute Buddy watch app is not installed on the device.
 *
 * Set [autoUI] to `true` when used from an Activity (allows SDK dialogs; skips pre-flight check).
 * Leave `false` (default) when used from a Service.
 *
 * Set [onStatusChanged] to receive human-readable status strings (e.g. for display in a TextView).
 * Set [onSendResult] to receive BLE send outcomes: `(success, statusName)`.
 */
class GarminNotifier : WatchNotifier {

    companion object {
        private const val TAG = "GarminNotifier"
        private const val GARMIN_APP_UUID = "e5f12c3a-7b04-4d8e-9a6f-2c1b3e5d7a9f"
    }

    /** Set to `true` before calling [initialize] when used from an Activity context. */
    var autoUI: Boolean = false

    /** Called with a status string whenever the SDK / device / app state changes. */
    var onStatusChanged: ((String) -> Unit)? = null

    /** Called after each BLE send attempt: `(success, statusName)`. */
    var onSendResult: ((Boolean, String) -> Unit)? = null

    @Volatile private var sdkReady = false
    @Volatile private var sdkShutDown = false
    @Volatile private var connectedDevice: IQDevice? = null
    @Volatile private var targetApp: IQApp? = null
    private var connectIQ: ConnectIQ? = null

    override fun initialize(context: Context) {
        if (!autoUI && !isConnectIQEnvironmentReady(context)) return
        onStatusChanged?.invoke("Initializing Garmin Connect IQ SDK…")
        connectIQ = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
        connectIQ?.initialize(context, autoUI, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                Log.d(TAG, "ConnectIQ SDK ready")
                sdkReady = true
                onStatusChanged?.invoke("Garmin Connect IQ SDK ready")
                discoverDevice()
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                Log.e(TAG, "ConnectIQ init error: $status")
                sdkReady = false
                onStatusChanged?.invoke("ConnectIQ init error: ${status.name}")
            }

            override fun onSdkShutDown() {
                Log.d(TAG, "ConnectIQ SDK shut down")
                sdkReady = false
                sdkShutDown = true
                onStatusChanged?.invoke("ConnectIQ SDK shut down")
            }
        })
    }

    override suspend fun notify(status: CommuteStatus) {
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
                        val success = msgStatus == ConnectIQ.IQMessageStatus.SUCCESS
                        if (success) Log.d(TAG, "BLE send success") else Log.w(TAG, "BLE send failed: ${msgStatus.name}")
                        onSendResult?.invoke(success, msgStatus.name)
                    }
                }
            )
        }
    }

    fun shutdown(context: Context) {
        if (sdkShutDown) return
        try {
            connectIQ?.shutdown(context)
        } catch (e: IllegalArgumentException) {
            // ConnectIQ is a singleton; another GarminNotifier instance (e.g. PollingForegroundService)
            // may have already called shutdown(), unregistering the shared receiver. Harmless.
            Log.d(TAG, "ConnectIQ receiver already unregistered — shutdown skipped")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down ConnectIQ", e)
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true only if the environment is clean enough for the ConnectIQ SDK to initialize
     * without attempting to show a dialog. SDK 2.3.0 ignores autoUI=false and shows a dialog
     * (crashing in a Service context) when Bluetooth is off or Garmin Connect is missing.
     */
    private fun isConnectIQEnvironmentReady(context: Context): Boolean {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null || !bt.isEnabled) {
            Log.w(TAG, "Pre-flight: Bluetooth disabled — skipping ConnectIQ init")
            return false
        }
        try {
            context.packageManager.getPackageInfo("com.garmin.android.apps.connectmobile", 0)
        } catch (e: NameNotFoundException) {
            Log.w(TAG, "Pre-flight: Garmin Connect not installed — skipping ConnectIQ init")
            return false
        }
        return true
    }

    private fun discoverDevice() {
        val iq = connectIQ ?: return
        val devices = try { iq.connectedDevices } catch (e: Exception) {
            Log.e(TAG, "Error getting connected devices", e); null
        }
        val device = devices?.firstOrNull { dev ->
            try { iq.getDeviceStatus(dev) == IQDevice.IQDeviceStatus.CONNECTED } catch (e: Exception) { false }
        }
        if (device == null) {
            connectedDevice = null
            targetApp = null
            Log.d(TAG, "No connected Garmin device")
            onStatusChanged?.invoke("No Garmin device connected")
            return
        }
        connectedDevice = device
        Log.d(TAG, "Found device: ${device.friendlyName}")
        onStatusChanged?.invoke("Device found: ${device.friendlyName}")
        loadAppInfo(device)
    }

    private fun loadAppInfo(device: IQDevice) {
        connectIQ?.getApplicationInfo(
            GARMIN_APP_UUID, device,
            object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    Log.d(TAG, "Garmin app found on ${device.friendlyName}")
                    targetApp = app
                    onStatusChanged?.invoke("Garmin app ready on ${device.friendlyName}")
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    Log.w(TAG, "Garmin app not installed on ${device.friendlyName}")
                    targetApp = null
                    onStatusChanged?.invoke("Garmin app not installed on ${device.friendlyName}")
                }
            }
        )
    }
}

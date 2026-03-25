package com.commutebuddy.app

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends [CommuteStatus] to a paired Garmin watch via the ConnectIQ BLE SDK.
 *
 * Singleton — obtain via [getInstance]. One SDK owner, one listener, one set of state flags.
 * Both [MainActivity] and [PollingForegroundService] share the same instance, eliminating the
 * listener-hijacking race where the Activity's init replaced the Service's listener and the
 * subsequent Activity shutdown killed the SDK the Service depended on.
 *
 * No-ops gracefully when Bluetooth is off, Garmin Connect is not installed,
 * no device is paired, or the Commute Buddy watch app is not installed on the device.
 *
 * The Activity should call [addUiListener] in `onResume()` and [removeUiListener] in `onPause()`
 * to receive status/send callbacks without touching the SDK lifecycle.
 */
class GarminNotifier private constructor(private val appContext: Context) : WatchNotifier {

    companion object {
        private const val TAG = "GarminNotifier"
        private const val GARMIN_APP_UUID = "e5f12c3a-7b04-4d8e-9a6f-2c1b3e5d7a9f"

        @Volatile private var instance: GarminNotifier? = null

        fun getInstance(context: Context): GarminNotifier =
            instance ?: synchronized(this) {
                instance ?: GarminNotifier(context.applicationContext).also { instance = it }
            }
    }

    /** Called with a status string whenever the SDK / device / app state changes. */
    var onStatusChanged: ((String) -> Unit)? = null
        private set

    /** Called after each BLE send attempt: `(success, statusName)`. */
    var onSendResult: ((Boolean, String) -> Unit)? = null
        private set

    /**
     * Attach UI callbacks. Call from Activity.onResume(). Does not touch the SDK lifecycle.
     */
    fun addUiListener(onStatus: (String) -> Unit, onSend: (Boolean, String) -> Unit) {
        onStatusChanged = onStatus
        onSendResult = onSend
    }

    /**
     * Detach UI callbacks. Call from Activity.onPause(). Does not touch the SDK lifecycle.
     */
    fun removeUiListener() {
        onStatusChanged = null
        onSendResult = null
    }

    @Volatile private var sdkReady = false
    @Volatile private var sdkShutDown = false
    @Volatile private var connectedDevice: IQDevice? = null
    @Volatile private var targetApp: IQApp? = null
    private var connectIQ: ConnectIQ? = null

    /**
     * Initializes the ConnectIQ SDK. Idempotent — if the SDK is already ready this is a no-op,
     * so the Activity calling initialize() will not replace the Service's active listener.
     * Should be called by [PollingForegroundService] on first start.
     */
    override fun initialize(context: Context) {
        if (sdkReady) return  // already ready — do not replace the active listener
        sdkShutDown = false
        if (!isConnectIQEnvironmentReady(appContext)) return
        connectIQ = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
        connectIQ?.initialize(appContext, false, object : ConnectIQ.ConnectIQListener {
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
                sdkShutDown = true
            }
        })
    }

    override suspend fun notify(status: CommuteStatus) {
        // Re-initialize if we know the SDK has shut down.
        if (sdkShutDown) reinitializeAndWait()

        if (sdkShutDown) { Log.d(TAG, "BLE send skipped: SDK shut down"); return }
        if (!sdkReady) { Log.d(TAG, "BLE send skipped: SDK not ready"); return }
        val device = connectedDevice ?: run { Log.d(TAG, "BLE send skipped: no device"); return }
        val app = targetApp ?: run { Log.d(TAG, "BLE send skipped: app not installed"); return }

        try {
            doSend(device, app, status)
        } catch (e: Exception) {
            // SDK threw despite our flags being valid — re-initialize and retry once.
            Log.w(TAG, "sendMessage threw (${e.message}) — re-initializing and retrying")
            reinitializeAndWait()
            val d = connectedDevice ?: run { Log.d(TAG, "BLE retry skipped: no device after re-init"); return }
            val a = targetApp ?: run { Log.d(TAG, "BLE retry skipped: app not found after re-init"); return }
            if (!sdkShutDown && sdkReady) doSend(d, a, status)
        }
    }

    private suspend fun reinitializeAndWait() {
        if (!isConnectIQEnvironmentReady(appContext)) return
        Log.d(TAG, "Re-initializing ConnectIQ SDK")
        sdkReady = false
        connectedDevice = null
        targetApp = null
        withContext(Dispatchers.Main) { initialize(appContext) }
        // Wait up to 3s for SDK ready + device discovery + app info (typically ~350ms)
        withTimeoutOrNull(3_000) {
            while (!sdkReady || connectedDevice == null || targetApp == null) {
                if (sdkShutDown) break
                delay(50)
            }
        }
        Log.d(TAG, "Re-init result: sdkReady=$sdkReady device=${connectedDevice?.friendlyName} app=${targetApp != null}")
    }

    private suspend fun doSend(device: IQDevice, app: IQApp, status: CommuteStatus) {
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

    /**
     * No-op. The SDK connection is owned by the singleton and persists for the app's lifetime.
     * Explicit shutdown is not needed — the OS cleans up the remote binding when the process ends.
     * This method is kept temporarily while callers are updated in subsequent increments.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shutdown(context: Context) {
        // Intentionally empty. See BUG-11 Increment 1.
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
                    onStatusChanged?.invoke("Garmin app ready on ${device.friendlyName}")
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    Log.w(TAG, "Garmin app not installed on ${device.friendlyName}")
                    targetApp = null
                }
            }
        )
    }
}

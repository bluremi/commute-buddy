# Connect IQ Android SDK — API Notes (SDK 2.3.0)

> **Purpose:** Project-specific notes to prevent common API mistakes. The official Garmin docs and LLM training data may reference outdated method names. **Use the [Comm Android sample](https://github.com/garmin/connectiq-android-sdk/tree/main/Comm%20Android) as the source of truth.**

## Device Status

### Correct API

```kotlin
// Get device connection status — use getDeviceStatus, NOT getStatus
val status = connectIQ.getDeviceStatus(device)
if (status == IQDevice.IQDeviceStatus.CONNECTED) {
    // Device is connected
}
```

### Common Mistakes (avoid these)

| Wrong | Correct |
|-------|---------|
| `connectIQ.getStatus(device)` | `connectIQ.getDeviceStatus(device)` |
| `ConnectIQ.IQDeviceStatus.CONNECTED` | `IQDevice.IQDeviceStatus.CONNECTED` |

**Note:** `IQDeviceStatus` is a nested type inside `IQDevice`, not `ConnectIQ`. The official "Mobile SDK for Android" guide may show `getStatus()` — that API does not exist in SDK 2.3.0.

## Application Info

### Correct API

```kotlin
connectIQ.getApplicationInfo(appId, device, object : ConnectIQ.IQApplicationInfoListener {
    override fun onApplicationInfoReceived(app: IQApp) {
        // App is installed — no need to check app.status
        targetApp = app
    }

    override fun onApplicationNotInstalled(applicationId: String) {
        // App not installed
    }
})
```

### Common Mistakes (avoid these)

- **Do not** check `app.status == IQApp.IQApplicationStatus.INSTALLED` — when `onApplicationInfoReceived` is called, the app is installed by definition. The `IQApplicationStatus` type may not exist or may differ across SDK versions.
- **Do not** add a null check for `app` — the callback guarantees a non-null `IQApp` when invoked.

## Reference

- **Comm Android sample:** https://github.com/garmin/connectiq-android-sdk/tree/main/Comm%20Android
- **MainActivity.kt** (device discovery): `loadDevices()` uses `connectIQ.getDeviceStatus(it)` and `IQDevice.IQDeviceStatus`
- **DeviceActivity.kt** (app info): `getApplicationInfo()` callback — no `app.status` check

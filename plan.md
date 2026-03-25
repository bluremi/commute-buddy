# BUG-11: Garmin Glance shows stale data — ConnectIQ SDK singleton conflict

## Problem Statement

Background polls successfully fetch MTA alerts and run the Gemini decision engine, but the BLE send to the Garmin watch silently fails, leaving the Glance showing stale commute data. The failure is logged as `GarminNotifier: BLE send skipped: SDK not ready`. Forcing a live fetch from the Android Activity UI successfully sends to the watch, but subsequent background polls continue to fail.

## Observed Behavior

### Logcat evidence (tags: `PollingService`, `GarminNotifier`)

Captured with 2-minute polling interval. A background poll was allowed to fire, then a manual live fetch was triggered from the Activity.

```
09:35:43 PollingService: onStartCommand: action=null
09:35:49 PollingService: onStartCommand: action=null
09:35:54 GarminNotifier: ConnectIQ SDK shut down          ← SDK dies here
09:37:49 PollingService: onStartCommand: action=com.commutebuddy.app.WAKE_AND_POLL
09:37:52 PollingService: Polling started
09:37:52 PollingService: Poll result: Decision             ← pipeline succeeds
09:37:52 GarminNotifier: BLE send skipped: SDK not ready   ← but send fails
09:37:52 PollingService: Next alarm in 1m 59s
09:38:09 PollingService: onStartCommand: action=null       ← Activity triggers service
09:38:09 GarminNotifier: ConnectIQ SDK ready               ← Activity re-inits SDK
09:38:09 GarminNotifier: Found device: Venu 3
09:38:09 GarminNotifier: Garmin app found on Venu 3
09:38:13 GarminNotifier: BLE send success                  ← Activity send works
```

Key observations:
- The SDK shuts down at 09:35:54, approximately 5 seconds after the second `onStartCommand`
- When the background poll fires at 09:37:52, the pipeline produces a valid `Decision` but the BLE send is skipped
- The Service's `reinitializeAndWait()` recovery path did NOT run (no `"Re-init result: ..."` log)
- Opening the Activity re-initializes the SDK and sends successfully

### Separate issue: Garmin Glance crash (related but distinct)

Prior to this investigation, the Glance had an intermittent crash ("Failed invoking \<symbol\>" in `onStart()` after 1-2 days). Root cause: `onStop()` didn't unregister the `Communications.registerForPhoneAppMessages` listener, leaving a dangling callback after OS process recycling. Fix applied 2026-03-24: added `Communications.registerForPhoneAppMessages(null)` in `onStop()` plus `(:glance)` annotations. That fix is still under observation and is independent of this stale-data bug.

## Root Cause Analysis

### Architecture context

`ConnectIQ.getInstance()` returns a **singleton** — there is one SDK instance per Android application process. Two separate `GarminNotifier` objects currently initialize this singleton independently:

1. **`PollingForegroundService`** (line 182) — creates `GarminNotifier()`, calls `initialize(this)` on first `onStartCommand`. Uses `autoUI = false`.
2. **`MainActivity`** (line 164) — creates `GarminNotifier().apply { autoUI = true }`, calls `initialize(this)` in `onCreate()`. Calls `garminNotifier.shutdown(this)` in `onDestroy()`.

Each `GarminNotifier` has its own `sdkReady`, `sdkShutDown`, `connectedDevice`, and `targetApp` flags. But both operate on the same underlying `ConnectIQ` singleton.

### The listener hijacking sequence

Diagnosis developed collaboratively between Claude Opus and Gemini 3.1 Pro. Gemini identified the critical insight that the reinit path was never reached (point 5):

1. **Service initializes:** `PollingForegroundService.onStartCommand` → `GarminNotifier.initialize()` registers **Listener A** on the ConnectIQ singleton. The async `onSdkReady()` callback fires on Listener A → Service's `sdkReady = true`.

2. **Activity hijacks:** User opens the app → `MainActivity.onCreate()` → second `GarminNotifier.initialize()` registers **Listener B** on the same singleton. The SDK silently discards Listener A. Service's GarminNotifier stops receiving any callbacks.

3. **Activity shuts down:** `MainActivity.onDestroy()` → `garminNotifier.shutdown(this)` → SDK fires `onSdkShutDown()` to **Listener B only** (the Activity's). The singleton is now dead.

4. **Service state is stale:** The Service's `GarminNotifier` still has `sdkShutDown = false` (it never received the shutdown callback because its listener was replaced in step 2). Its `sdkReady` may be `false` (if the listener replacement happened during async init) or stale `true`.

5. **Reinit is never triggered:** When `notify()` runs at poll time, it checks `if (sdkShutDown)` first. Since `sdkShutDown = false`, `reinitializeAndWait()` is **skipped entirely**. Then `if (!sdkReady)` evaluates to `true` → `"BLE send skipped: SDK not ready"`. This is confirmed by the **absence** of any `"Re-init result: ..."` log in the capture.

6. **Activity fixes it temporarily:** Opening the Activity again creates a new `GarminNotifier`, calls `initialize()` on the singleton, and SDK becomes ready — but this only fixes sends from the Activity's notifier. The Service's notifier remains broken.

### Why it wasn't noticed before

Before the Garmin Glance crash was fixed, the Glance would go blank after 1-2 days. The user would open the Activity to investigate → incidentally re-initializing the SDK → masking the stale data problem. With the Glance now stable, the stale data became visible.

**However**, this masking theory may not be the full explanation. The user reports the app worked consistently for 24+ hours before each Glance crash. It's possible the bug only manifests under specific Activity lifecycle conditions (e.g., Activity being destroyed by the OS vs. user-initiated close).

## Proposed Fix

### Approach: Singleton GarminNotifier

Convert `GarminNotifier` from a per-consumer instance to a shared singleton. One SDK owner, one listener, one set of state flags. Both `MainActivity` and `PollingForegroundService` share the same instance.

### Implementation plan

#### [x] Increment 1: Convert GarminNotifier to singleton

**File:** `GarminNotifier.kt`

- Add a `companion object` with a `getInstance(context: Context)` factory that returns a single `GarminNotifier` backed by `applicationContext`
- Remove the public constructor (make it private)
- The singleton holds one `ConnectIQ` instance, one set of state flags (`sdkReady`, `sdkShutDown`, `connectedDevice`, `targetApp`)
- `initialize()` becomes idempotent: if `sdkReady` is already `true`, it no-ops. This prevents the Activity from re-initializing and replacing the Service's listener
- Add an `addUiListener()` / `removeUiListener()` API for the Activity to attach `onStatusChanged` and `onSendResult` callbacks without touching the SDK lifecycle. These are nullable lambda properties that the Activity sets in `onResume()` and clears in `onPause()`
- `shutdown()` becomes a no-op when called from `MainActivity`. Only `PollingForegroundService.onDestroy()` should call it (or it can be removed entirely — the OS will clean up the process)

**Verification:** Unit test or manual check that `GarminNotifier.getInstance()` returns the same object across multiple calls. Confirm `initialize()` is idempotent (calling it twice doesn't replace the listener).

#### [x] Increment 2: Update PollingForegroundService to use singleton

**File:** `PollingForegroundService.kt`

- Replace `private val garminNotifier = GarminNotifier()` (line 182) with `GarminNotifier.getInstance(this)` (deferred to `onStartCommand` where `this` is available, or use `applicationContext`)
- The `notifiers` list construction needs to move from field init to the `initialized` block, since `getInstance()` needs a context
- `onDestroy()` continues to call `garminNotifier.shutdown()` — this is now the only legitimate shutdown call
- No changes to polling, scheduling, or pipeline logic

**Verification:** `adb logcat -s PollingService:V GarminNotifier:V` — confirm background polls log `BLE send success` without opening the Activity.

#### [x] Increment 3: Update MainActivity to use singleton + attach/detach pattern

**File:** `MainActivity.kt`

- Replace `garminNotifier = GarminNotifier().apply { ... }` (line 164) with `GarminNotifier.getInstance(this)`
- Move the `onStatusChanged` and `onSendResult` callback setup from `onCreate()` to `onResume()` using the new `addUiListener()` API
- Add `removeUiListener()` call in `onPause()`
- **Remove** `garminNotifier.shutdown(this)` from `onDestroy()` — the Activity no longer owns SDK lifecycle
- `autoUI` is no longer set per-instance. Since the Service initializes first with `autoUI = false`, and the Activity no longer calls `initialize()`, SDK dialogs won't appear. The existing `isConnectIQEnvironmentReady()` pre-flight check already prevents the crash that `autoUI` was protecting against.

**Verification:**
1. Open Activity, do a live fetch → success
2. Close Activity (swipe away or back button)
3. Wait for background poll → should log `BLE send success` (not "SDK not ready")
4. Repeat: open Activity, close it, wait for poll — should always succeed

#### Increment 4: End-to-end regression test

Full manual test matrix:
- [x] Background poll sends to Garmin without ever opening Activity
- [x] Open Activity → live fetch → close Activity → background poll succeeds
- [x] Open Activity → close Activity (no fetch) → background poll succeeds
- [x] Reboot phone → background poll sends to Garmin (BootReceiver path)
- [x] Bluetooth toggled off then on → recovery (reinit path)
- [ ] Garmin watch reboot → next background poll sends successfully *(not yet tested)*
- [ ] Wear OS still receives data in all scenarios above (no regression) *(under observation — wear for ~1 week)*

## Key Files

| File | Role |
|------|------|
| `android/app/src/main/kotlin/com/commutebuddy/app/GarminNotifier.kt` | ConnectIQ SDK wrapper — primary change target |
| `android/app/src/main/kotlin/com/commutebuddy/app/PollingForegroundService.kt` | Background polling service — update to use singleton |
| `android/app/src/main/kotlin/com/commutebuddy/app/MainActivity.kt` | Activity — remove SDK ownership, add attach/detach |
| `android/app/src/main/kotlin/com/commutebuddy/app/WatchNotifier.kt` | Interface — no changes expected |
| `garmin/source/CommuteBuddyApp.mc` | Garmin app — no changes (receives BLE, separate bug track) |

## Resolved Questions

1. **Should `shutdown()` be removed entirely?** Yes — remove it from both `MainActivity.onDestroy()` and `PollingForegroundService.onDestroy()`. Because the Service returns `START_STICKY`, it is designed to run continuously. Calling `connectIQ.shutdown()` explicitly unbinds the remote service connection to Garmin Connect. If the OS destroys the service due to memory constraints, it automatically cleans up the remote binding. The only legitimate place to call `shutdown()` would be when the user explicitly toggles polling off in the settings UI. Leaving the connection open avoids edge cases where the OS restarts the sticky service before the async shutdown fully completes. (Source: Gemini 3.1 Pro)

2. **BLE rate limiting:** The singleton pattern resolves the primary trigger. By maintaining a single persistent SDK connection rather than tearing it down and rebuilding it across Activity lifecycles, BLE stack thrashing is eliminated. No additional mitigation needed. (Source: Gemini 3.1 Pro)

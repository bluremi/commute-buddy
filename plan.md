# Active Development

## PHASE2-01: Android-side dual-broadcast infrastructure

### Description
As a commuter with both a Garmin watch and a Wear OS watch, I want the Android app to broadcast commute status to all connected watch types simultaneously, so that I can use whichever watch I'm wearing without needing to reconfigure anything.

Currently, Garmin BLE send logic is duplicated in two places — `MainActivity.sendCommuteStatus()` and `PollingForegroundService.sendBle()` — each with their own ConnectIQ init, device discovery, and app info management. This story extracts that into a `WatchNotifier` abstraction, implements a `WearOsNotifier` using the Wearable Data Layer API, and wires both notifiers into the pipeline callers so every poll/fetch broadcasts to all paired watch types.

### Acceptance Criteria

1. **`WatchNotifier` interface**
   - Defines `suspend fun notify(status: CommuteStatus)` and `fun initialize(context: Context)`
   - Implementations no-op gracefully when their watch type is not paired/available (no crashes, no exceptions thrown to callers)

2. **`GarminNotifier` extracts existing Garmin BLE logic**
   - All ConnectIQ SDK init, device discovery, app info loading, and `sendMessage()` logic moves from `PollingForegroundService` into `GarminNotifier`
   - `MainActivity` also delegates to `GarminNotifier` instead of its own inline BLE code
   - `isConnectIQEnvironmentReady()` pre-flight check moves into `GarminNotifier.initialize()`
   - Existing behavior is preserved: skip silently when BT is off, Garmin Connect is missing, or no device paired

3. **`WearOsNotifier` using Wearable Data Layer API**
   - Uses `DataClient` via `PutDataMapRequest` to sync `CommuteStatus` fields to the Wear OS data layer
   - Data path: `/commute-status` (or similar well-defined constant)
   - Includes all `CommuteStatus` fields: `action`, `summary`, `affected_routes`, `reroute_hint` (nullable), `timestamp`
   - Includes a unique write timestamp (e.g., `System.currentTimeMillis()`) to ensure `DataClient` always triggers `onDataChanged()` even when the payload content is identical
   - No-ops gracefully when Wear OS / Google Play Services is not available (e.g., catches `ApiException`)

4. **Both notifiers wired into pipeline callers**
   - `PollingForegroundService.poll()` calls all registered notifiers after a successful pipeline result
   - `MainActivity.handlePipelineResult()` calls all registered notifiers (replacing inline `sendCommuteStatus()`)
   - A notifier failing does not prevent other notifiers from running (isolate failures)

5. **Gradle dependency added**
   - `com.google.android.gms:play-services-wearable` added to `app/build.gradle.kts`
   - No new Wear OS module yet — this is Android-phone-side only

6. **Existing Garmin behavior unchanged**
   - All current BLE send paths work identically after the refactor
   - `PollingForegroundService` notification, scheduling, and alarm logic untouched
   - No changes to Garmin watch app or `shared/schema.json`

7. **Unit testable**
   - `WatchNotifier` interface allows easy mocking in pipeline tests
   - Orchestration layer tests verify: all notifiers called, one failure doesn't block others, correct `CommuteStatus` data passed

### Out of Scope
- Wear OS module (`wear/` directory) — that's PHASE2-02
- `WearableListenerService` on the watch side — that's PHASE2-02
- UI changes to indicate Wear OS connection status
- Toggling individual watch types on/off in settings
- Changes to the Garmin watch app or BLE schema

### Implementation Plan

#### Increment 1: `WatchNotifier` interface + `GarminNotifier` extraction from `PollingForegroundService`
- [ ] Create `WatchNotifier.kt` — interface with `fun initialize(context: Context)` and `suspend fun notify(status: CommuteStatus)`
- [ ] Create `GarminNotifier.kt` — extract `isConnectIQEnvironmentReady()`, `initConnectIQ()`, `discoverDevice()`, `loadAppInfo()`, and `sendBle()` from `PollingForegroundService` into this class
- [ ] Refactor `PollingForegroundService` to hold a `GarminNotifier` instance: call `initialize()` in `onStartCommand`, call `notify()` in `poll()`, remove the extracted fields/methods (`sdkReady`, `connectedDevice`, `targetApp`, `connectIQ`)
- [ ] Remove ConnectIQ `shutdown()` from `PollingForegroundService.onDestroy()` — add a `fun shutdown()` to `GarminNotifier` and call it instead

**Testing:** Build APK and deploy to phone with Garmin Venu 3 paired. Trigger a manual poll (or wait for scheduled poll). Confirm BLE send still works via logcat: `adb -s 57171FDCQ008DS logcat -s PollingService:V CommutePipeline:V`. Look for "BLE send success".

**Model: Sonnet** | Reason: Non-trivial extraction refactor — must carefully move state and callbacks without breaking ConnectIQ lifecycle.

#### Increment 2: Refactor `MainActivity` to use `GarminNotifier`
- [ ] Replace `MainActivity`'s inline ConnectIQ init (`initConnectIQ`, `discoverDevice`, `loadAppInfo`), BLE state fields (`sdkReady`, `connectedDevice`, `targetApp`, `connectIQ`), and `sendCommuteStatus()` with a shared `GarminNotifier` instance
- [ ] `MainActivity` still needs device/app status for the status text display — add a status callback or expose `isReady()`/`getStatusText()` on `GarminNotifier`
- [ ] `handlePipelineResult()` calls `GarminNotifier.notify()` instead of inline `sendCommuteStatus()`
- [ ] Debug test payload menu still sends via `GarminNotifier.notify()`

**Testing:** Open the app. Confirm SDK status text still shows device name and "App ready". Tap "Fetch Live" with Garmin paired — confirm BLE send success in results text. Send a debug test payload — confirm it arrives on Garmin. Check logcat for any errors.

**Model: Sonnet** | Reason: Must preserve UI status feedback while replacing the underlying BLE implementation — requires careful understanding of the callback flow.

#### Increment 3: `WearOsNotifier` + Gradle dependency + dual-broadcast wiring
- [ ] Add `com.google.android.gms:play-services-wearable` to `app/build.gradle.kts`
- [ ] Create `WearOsNotifier.kt` — implements `WatchNotifier`; uses `Wearable.getDataClient(context)` + `PutDataMapRequest.create("/commute-status")` to write all `CommuteStatus` fields plus a `sent_at` timestamp for uniqueness; catches `ApiException` / missing Play Services gracefully
- [ ] Wire both notifiers into `PollingForegroundService`: hold a `List<WatchNotifier>`, initialize both in `onStartCommand`, iterate + call `notify()` in `poll()` with per-notifier try/catch
- [ ] Wire both notifiers into `MainActivity`: same pattern, initialize both in `onCreate`, iterate in `handlePipelineResult()`

**Testing:** Build and deploy to phone. Confirm Garmin BLE still works (regression). Check logcat for `WearOsNotifier` — should log a graceful no-op or successful data put (depending on whether a Wear OS emulator is running). No crashes.

**Model: Sonnet** | Reason: New API integration (Wearable Data Layer) with graceful error handling and multi-notifier orchestration.

#### Increment 4: Orchestration unit tests
- [ ] Create `WatchNotifierOrchestratorTest.kt` (or add to existing test file) — tests that verify:
  - All notifiers in a list are called when a `CommuteStatus` is broadcast
  - If one notifier throws, the remaining notifiers still execute
  - Correct `CommuteStatus` data is passed to each notifier
- [ ] Use simple mock/fake `WatchNotifier` implementations (no Android dependencies needed)

**Testing:** Run `gradle :app:testDebugUnitTest`. All new and existing tests pass.

**Model: Haiku** | Reason: Straightforward test writing against a simple interface — clear pattern, no ambiguity.

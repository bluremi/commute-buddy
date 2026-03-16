## PHASE2-05: Dual-broadcast integration testing

### Description
As a commuter, I want the dual-broadcast system (Garmin + Wear OS) to be rock-solid across all pairing scenarios, so that I never miss a commute status update regardless of which watches I have connected.

The app currently broadcasts `CommuteStatus` to both `GarminNotifier` and `WearOsNotifier` via the `notifyAll()` orchestrator, with per-notifier failure isolation. However, this orchestration has only been tested with fakes (`WatchNotifierOrchestratorTest`). Real-world edge cases — single watch paired, both paired, neither paired, reconnection after disconnect, Garmin Connect absent, Play Services unavailable — have not been systematically verified. This story adds integration-level tests and any hardening needed to guarantee no crashes, no silent data loss, and no duplicate sends across all scenarios.

### Acceptance Criteria

1. **Garmin-only scenario**
   - When only a Garmin watch is paired (no Wear OS node reachable), `GarminNotifier.notify()` sends successfully and `WearOsNotifier.notify()` no-ops without throwing
   - Watch connection status displays "Garmin connected"
   - `CommuteStatus` arrives on the Garmin watch with correct payload fields

2. **Wear OS-only scenario**
   - When only a Wear OS watch is paired (Garmin Connect not installed or BT off), `WearOsNotifier.notify()` puts data successfully and `GarminNotifier.notify()` no-ops without throwing
   - Watch connection status displays "Wear OS connected"
   - `CommuteStatus` arrives on the Wear OS watch with correct payload fields

3. **Both paired simultaneously**
   - When both a Garmin and Wear OS watch are paired, both notifiers send successfully
   - Watch connection status displays "Garmin + Wear OS connected"
   - Each watch receives the same `CommuteStatus` payload (same action, summary, affected_routes, reroute_hint, timestamp)
   - No duplicate sends to either watch within a single `notifyAll()` invocation

4. **Neither paired**
   - When no watches are paired, both notifiers no-op gracefully
   - Watch connection status displays "No watch connected"
   - No exceptions in logcat, no crash, no ANR
   - Polling service continues to poll and schedule alarms normally

5. **Reconnection after disconnect**
   - When a Wear OS watch disconnects and reconnects, the next `notifyAll()` call successfully delivers the status (Data Layer API handles this via `putDataItem` sync)
   - When a Garmin watch disconnects and reconnects, `GarminNotifier` re-discovers the device and subsequent sends succeed (or gracefully skip if ConnectIQ SDK requires re-init)

6. **Failure isolation**
   - If `GarminNotifier.notify()` throws, `WearOsNotifier.notify()` still executes (and vice versa)
   - The exception is logged with the notifier class name but does not propagate to the caller
   - Unit tests validate this with a `ThrowingNotifier` preceding and following a `RecordingNotifier`

7. **Service lifecycle**
   - `PollingForegroundService` initializes both notifiers exactly once (`initialized` guard) and shuts down `GarminNotifier` in `onDestroy()`
   - After process restart (OS-initiated `START_STICKY` re-delivery), both notifiers re-initialize cleanly
   - No leaked ConnectIQ receivers on shutdown (the `IllegalArgumentException` catch in `GarminNotifier.shutdown()` handles the singleton edge case)

8. **Unit test coverage**
   - Existing `WatchNotifierOrchestratorTest` is expanded to cover: empty notifier list, all notifiers throwing, notifier called with every `CommuteStatus.ACTION_*` variant, reroute_hint null vs present
   - New test class validates `WearOsNotifier` data map construction: all fields present, `sent_at` is always unique, `reroute_hint` key absent when null

### Out of Scope
- Garmin BLE protocol-level reliability (retries, MTU negotiation) — handled by ConnectIQ SDK
- Wear OS `DataClient` sync reliability under poor Bluetooth — handled by Play Services
- Tile rendering correctness (covered by PHASE2-03)
- Detail view rendering correctness (covered by PHASE2-04)
- Automated UI/instrumentation tests (manual verification is acceptable for hardware-dependent scenarios)
- Performance benchmarking of broadcast latency

### Implementation Plan

#### Increment 1: Expand orchestrator unit tests
- [ ] Add test: empty notifier list — `notifyAll()` with `emptyList()` completes without error
- [ ] Add test: all notifiers throwing — two `ThrowingNotifier`s, `notifyAll()` completes, `onError` called twice with correct notifier references and exception types
- [ ] Add test: `ThrowingNotifier` *after* `RecordingNotifier` — confirms failure isolation works regardless of order (existing test only covers throwing-first)
- [ ] Add test: every `CommuteStatus.ACTION_*` variant (`NORMAL`, `MINOR_DELAYS`, `REROUTE`, `STAY_HOME`) is passed through correctly to all notifiers
- [ ] Add test: `rerouteHint = null` vs `rerouteHint = "Take the F train"` — both arrive unchanged
- [ ] Add test: `onError` callback receives the exact notifier instance and exception that threw

**Testing:** `GRADLE=$(ls /c/Users/blure/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle 2>/dev/null | head -1) && cd "A:/Phil/Phil Docs/Development/commute-buddy/android" && "$GRADLE" :app:testDebugUnitTest --tests "com.commutebuddy.app.WatchNotifierOrchestratorTest"`
**Model: Haiku** | Reason: Mechanical additions following existing test patterns — fakes already exist.

---

#### Increment 2: WearOsNotifier data map construction test
- [ ] Extract `internal companion fun buildDataMap(status: CommuteStatus): Map<String, Any>` in `WearOsNotifier` that returns the field map (action, summary, affected_routes, conditional reroute_hint, timestamp) — `notify()` calls this then copies into `PutDataMapRequest` and appends `sent_at`
- [ ] Create `WearOsNotifierTest.kt` with tests:
  - All five fields present for a full status (with reroute_hint)
  - `reroute_hint` key absent when `rerouteHint` is null
  - Field values match the input `CommuteStatus` exactly
  - Every `ACTION_*` variant round-trips correctly
- [ ] Add `sent_at` uniqueness assertion: call `notify()` logic twice in sequence, confirm `sent_at` values differ (test the contract, not the implementation — can test via the extracted map function by verifying `timestamp` is the status timestamp, not `System.currentTimeMillis()`)

**Testing:** `GRADLE=$(ls /c/Users/blure/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle 2>/dev/null | head -1) && cd "A:/Phil/Phil Docs/Development/commute-buddy/android" && "$GRADLE" :app:testDebugUnitTest --tests "com.commutebuddy.app.WearOsNotifierTest"`
**Model: Sonnet** | Reason: Requires extracting a function from existing code and reasoning about what's testable without Play Services mocks.

---

#### Increment 3: Production hardening + manual hardware verification
- [ ] Audit `GarminNotifier` for reconnection: after `sdkShutDown = true`, subsequent `notify()` calls must no-op (currently does via `sdkReady` check — verify this path and add a log line if missing)
- [ ] Audit `WearOsNotifier` for null `appContext`: confirm `initialize()` not called → `notify()` logs and returns (already implemented — verify log message is clear)
- [ ] Audit `PollingForegroundService.onDestroy()`: confirm `WearOsNotifier` cleanup is not needed (Data Layer API has no teardown) — add a comment if missing
- [ ] Run full unit test suite green: `:app:testDebugUnitTest`
- [ ] Manual verification matrix (build + install APK, run through each scenario):
  - **Garmin-only**: unpair Wear OS emulator, trigger Fetch Live → Garmin receives status, logcat shows `WearOsNotifier` no-op, status text shows "Garmin connected"
  - **Wear OS-only**: disable Bluetooth (blocks Garmin), trigger Fetch Live → Wear OS receives status, logcat shows `GarminNotifier` skipped, status text shows "Wear OS connected"
  - **Both paired**: trigger Fetch Live → both watches receive identical payload, status text shows "Garmin + Wear OS connected"
  - **Neither paired**: disable BT + no Wear emulator → Fetch Live completes, no crash, status text shows "No watch connected"
  - **Reconnect**: disconnect Wear OS emulator, reconnect, trigger Fetch Live → status arrives on watch

**Testing:** Run all unit tests: `"$GRADLE" :app:testDebugUnitTest`. Then manually verify each scenario in the matrix above using `adb logcat -s GarminNotifier:V WearOsNotifier:V CommuteBuddy:V`.
**Model: Sonnet** | Reason: Audit requires cross-file reasoning about failure paths and lifecycle; manual checklist requires understanding the full system.

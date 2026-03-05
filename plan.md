# Active Development

## FEAT-04: Route Status Summary Generation and BLE Push to Watch

### Description
As a commuter, I want the Android app to send summarized route status to my Garmin watch via BLE after running the live MTA alert pipeline, so that the watch has up-to-date transit data ready for glance display.

This feature wires together the existing MTA fetch/parse/filter pipeline (FEAT-03) and Gemini summarization (FEAT-02) with the proven BLE send mechanism (FEAT-01). After tapping "Fetch Live," the Android app will not only display the result on-screen but also push a `CommuteStatus` Dictionary to the watch. The watch-side message handler will be updated to accept Dictionary payloads and persist the fields in `Application.Storage`. The FEAT-01 steel thread (random code send/display) is removed from both apps — it has served its purpose and its removal simplifies the codebase. The Glance shows a simple one-line status so BLE integration is unambiguously testable. The full formatted Glance UI is deferred to FEAT-05.

### Acceptance Criteria

1. **BLE push on successful summarization**
   - After the "Fetch Live" pipeline produces a `CommuteStatus`, the app converts it to a Connect IQ Dictionary and sends it via `connectIQ.sendMessage()`
   - The Dictionary keys match `shared/schema.json`: `"status"` (Int), `"route_string"` (String), `"reason"` (String), `"timestamp"` (Long)
   - Phone UI shows BLE send result (success/failure) alongside the existing Gemini output

2. **Good Service push without API call**
   - When no alerts match the monitored routes/active periods, the app sends a `CommuteStatus` with `status=0`, `route_string` set to the monitored routes, `reason="Good service"`, and current timestamp — no Gemini call is made
   - Watch receives and stores this just like any other status

3. **Error status push on pipeline failure**
   - If the MTA feed fetch fails or Gemini returns unparseable output, the app sends a `CommuteStatus` with `status=2`, `reason` describing the error (truncated to 40 chars), and current timestamp
   - The phone UI continues to show the detailed error message locally

4. **Remove FEAT-01 steel thread from both apps**
   - Android: remove "Send Code" button, its click handler, the random code generation logic, and related UI elements
   - Watch: remove `Number`/integer handling from `onPhoneMessage()`; remove `"code"` Storage key and its Glance rendering
   - `shared/schema.json`: remove the steel-thread integer payload type

5. **Watch receives Dictionary messages**
   - `CommuteBuddyApp.onPhoneMessage()` handles `Dictionary` payloads, extracting `"status"`, `"route_string"`, `"reason"`, and `"timestamp"`
   - Dictionary fields are validated (status is 0–2, strings are non-null) before storing
   - Invalid or missing fields are silently ignored (no crash)

6. **Watch persists status in Application.Storage**
   - Status fields are stored as individual keys: `"cs_status"`, `"cs_route"`, `"cs_reason"`, `"cs_timestamp"` (prefixed to avoid collision with legacy keys)
   - `WatchUi.requestUpdate()` is called after storage write to trigger Glance refresh

7. **Glance shows simple one-line status**
   - `CommuteBuddyGlanceView` reads `cs_status` and `cs_route` from Storage and displays a single line: `"Normal"`, `"Delays — N,W"`, or `"Disrupted — N,W"` (status code mapped to label, route appended for non-normal)
   - Displays `"Waiting..."` when no status has been received yet
   - Visibly changes on every successful or erroneous BLE push, making integration unambiguously testable
   - Full multi-line Glance layout (reason text, timestamp, colors) is deferred to FEAT-05

8. **BLE send respects device/app state**
   - BLE push is skipped (with a log/UI note) if SDK is not ready, no device is connected, or the watch app is not installed
   - The pipeline result is still displayed on the phone regardless of BLE send outcome

9. **shared/schema.json stays accurate**
   - The schema file is updated to reflect removal of the integer payload and document the CommuteStatus Dictionary as the sole message type

### Out of Scope
- Full Garmin Glance UI layout with route details, colors, and multi-line formatting (FEAT-05)
- Background polling / scheduled pipeline execution (FEAT-06)
- Configurable route selection (FEAT-08)
- Automatic retry on BLE send failure
- Queuing or batching of multiple status updates

### Implementation Plan

#### Increment 1: Remove FEAT-01 steel thread from Android and update schema
- [x] Remove "Send Code" button (`sendButton`), code display (`codeTextView`), click handler (`onSendCodeClicked()`), and `import kotlin.random.Random` from `MainActivity.kt`
- [x] Remove the corresponding UI elements from `activity_main.xml`: the `codeTextView`, `sendButton`, and the first visual separator divider
- [x] Remove steel-thread string resources from `strings.xml`: `code_placeholder`, `button_send_code`, `status_sent` (keep SDK/device/send strings that will be reused)
- [x] Update `shared/schema.json`: remove the `steelThread` payload type; update `commuteStatus.notes` to reference "Gemini 2.5 Flash cloud API" instead of "Gemini Nano"; make `commuteStatus` the sole `phoneToWatch` message type

**Testing:** Build Android app in Android Studio (green play or `assembleDebug`). Verify: app launches without the Send Code section, "Fetch Live" and tier buttons still work, Gemini summarization still produces results. The watch is untouched this increment — it will simply never receive integer messages anymore.
**Model: Composer** | Reason: Mechanical deletion of UI elements, handlers, and string resources following explicit instructions.

#### Increment 2: Add BLE push of CommuteStatus from the Fetch Live pipeline
- [ ] Add `toConnectIQMap(): Map<String, Any>` method on `CommuteStatus` — returns `mapOf("status" to status, "route_string" to routeString, "reason" to reason, "timestamp" to timestamp)`
- [ ] Add private `sendCommuteStatus(status: CommuteStatus)` method in `MainActivity` — checks `sdkReady`, `connectedDevice`, `targetApp`; if any missing, appends a BLE-skip note to `resultsTextView`; otherwise calls `connectIQ.sendMessage()` with `status.toConnectIQMap()` and appends success/failure to `resultsTextView`
- [ ] Wire the success path in `onFetchLiveClicked()`: after `CommuteStatus.fromJson()` succeeds, call `sendCommuteStatus(parsed)`
- [ ] Wire the Good Service path: when `filtered.isEmpty()`, construct `CommuteStatus(STATUS_NORMAL, MONITORED_ROUTES.joinToString(","), "Good service", System.currentTimeMillis() / 1000)` and call `sendCommuteStatus()`
- [ ] Wire error paths: MTA fetch failure → construct `CommuteStatus(STATUS_ERROR, ..., reason truncated to 40 chars, ...)` and send; Gemini parse failure → same pattern with parse error reason
- [ ] Add string resources: `ble_send_success` ("Sent to watch"), `ble_send_failed` ("Watch send failed: %s"), `ble_send_skipped` ("Watch send skipped: %s") — appended below existing result text
- [ ] Add unit test for `CommuteStatus.toConnectIQMap()` verifying key names and value types

**Testing:** Run unit tests (`testDebugUnitTest`). Then on phone: tap "Fetch Live" and verify the results text now includes a BLE status line at the bottom (either "Sent to watch", "Watch send failed: ...", or "Watch send skipped: no device" depending on whether a watch is paired). The pipeline result display should be unchanged above the BLE line.
**Model: Sonnet** | Reason: New method authoring, three-path integration wiring, and error-handling logic across multiple code paths.

#### Increment 3: Update watch to receive Dictionary and display simple status
- [ ] In `CommuteBuddyApp.onPhoneMessage()`: replace the `Number`/integer handling with `Dictionary` handling — check `data instanceof Dictionary`, extract `"status"`, `"route_string"`, `"reason"`, `"timestamp"` keys, validate status is 0–2 and strings are non-null, store as `cs_status` (Number), `cs_route` (String), `cs_reason` (String), `cs_timestamp` (Number) in `Application.Storage`; call `WatchUi.requestUpdate()`
- [ ] Remove the legacy `"code"` storage key handling entirely
- [ ] In `CommuteBuddyGlanceView.onUpdate()`: read `cs_status` and `cs_route` from Storage; display `"Waiting..."` if no status stored, `"Normal"` if status==0, `"Delays — {route}"` if status==1, `"Disrupted — {route}"` if status==2
- [ ] Remove the old `"code"` display logic from the Glance

**Testing:** Full end-to-end BLE integration with physical devices. Build and sideload the Garmin app (`Ctrl+Shift+B` in VS Code, copy `.prg` to watch). Install Android app on phone. Tap "Fetch Live" on phone. Verify: (1) phone shows "Sent to watch" in results, (2) watch Glance updates from "Waiting..." to the appropriate status line (e.g., "Normal" or "Delays — N,W"). Tap "Fetch Live" again at a different time or under different conditions and verify the Glance text changes.
**Model: Sonnet** | Note: Monkey C Dictionary extraction and type-checking requires careful attention to Connect IQ SDK types — verify `instanceof Dictionary` is the correct type check against latest SDK docs.

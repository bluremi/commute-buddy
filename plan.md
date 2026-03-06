# Active Development

## FEAT-08: Background Polling Service

### Description
As a commuter, I want the Android app to automatically fetch MTA alerts and push recommendations to my watch on a schedule, so that I don't have to manually open the app and tap "Fetch Live" every time I want an update.

Currently, the entire pipeline (MTA fetch -> parse -> filter -> Gemini decision -> BLE push) is triggered only by a manual button press in `MainActivity`. This story moves that pipeline into a Foreground Service that runs on a fixed schedule: every 5 minutes (configurable) during user-defined commute windows, and once per hour outside them. The hourly off-window poll keeps the watch reasonably fresh for ad-hoc trips. The existing `ApiRateLimiter` (50/day hard cap) ensures the schedule never exceeds the daily budget regardless of user configuration.

### Acceptance Criteria

1. **Foreground Service with Persistent Notification**
   - A `PollingForegroundService` runs the fetch->decide->push pipeline on a repeating schedule
   - Displays a persistent notification (required by Android 14+ for foreground services) showing last poll time and next scheduled poll
   - Service type is `dataSync` (or `shortService` if polling completes quickly -- evaluate during implementation)
   - `FOREGROUND_SERVICE_DATA_SYNC` permission declared in `AndroidManifest.xml`

2. **Fixed-Interval Scheduling**
   - During active commute windows: polls every N minutes (default 5, user-configurable)
   - Outside commute windows: polls once per hour
   - Uses `AlarmManager` or `Handler`/coroutine-based timer within the foreground service (not `WorkManager` periodic -- too imprecise for 5-min intervals)
   - Schedule recalculates on window boundary transitions (entering/leaving a commute window adjusts the next poll delay)

3. **Configurable Commute Windows**
   - User can define commute windows (e.g., 8:00-9:30 AM, 5:30-7:00 PM) in a settings screen
   - At least two windows supported (morning and evening commute)
   - Windows are persisted in SharedPreferences
   - Default windows: 8:00-9:30 AM and 5:30-7:00 PM

4. **Configurable Polling Interval**
   - User can adjust the in-window polling interval (minimum 2 minutes, maximum 15 minutes, default 5 minutes)
   - Setting is accessible from the same settings screen as commute windows
   - Persisted in SharedPreferences

5. **Settings Screen**
   - New `PollingSettingsActivity` accessible from `MainActivity` (e.g., "Polling Settings" button or gear icon)
   - Shows: commute window start/end time pickers, polling interval selector, service on/off toggle
   - Changes take effect on the running service immediately (restart service or reschedule)

6. **Pipeline Reuse**
   - The service runs the exact same pipeline as `onFetchLiveClicked()` in `MainActivity`: fetch -> parse -> route-filter -> active-period-filter -> buildPromptText -> Gemini -> CommuteStatus -> BLE push
   - Reuses `ApiRateLimiter`, `CommuteProfileRepository`, `MtaAlertFetcher`, `MtaAlertParser`, `SystemPromptBuilder`
   - The pipeline logic is extracted from `MainActivity` into a shared class/function so both the manual button and the service call the same code

7. **Rate Limiter Integration**
   - Every scheduled poll passes through `ApiRateLimiter.tryAcquire()` before calling Gemini
   - If the daily cap is reached, the poll is skipped and the app displays a persistent warning on the `MainActivity` screen showing the current count and cap (e.g., "Daily limit reached: 50/50 -- polling paused until tomorrow")
   - During normal operation, the main screen shows the current daily usage (e.g., "API usage: 32/50 today") so the user has visibility before hitting the cap
   - If "Good Service" (no active alerts after filtering), no Gemini call is made -- this doesn't count against the daily cap
   - The 50/day cap comfortably covers a typical day: ~18 polls per 1.5-hr window x 2 windows = 36 + ~21 hourly off-window polls = 57 theoretical max, but "Good Service" short-circuits and rate limiter enforces the hard cap

8. **Service Lifecycle**
   - Service starts on app launch (if enabled) and on device boot (via `BOOT_COMPLETED` broadcast receiver)
   - Service respects the on/off toggle -- when disabled, no polling occurs
   - `MainActivity` "Fetch Live" button continues to work for manual one-off fetches regardless of service state
   - Service survives app being swiped from recents (foreground service behavior)

### Out of Scope
- Dynamic TTL / Google Maps Routes API integration (dropped -- was FEAT-09)
- Battery optimization exemption prompts (can be a follow-up if needed)
- Notification actions (e.g., "Fetch Now" from notification) -- nice-to-have for later
- Widget or tile on the Android side -- this is a background service only
- Changes to the Garmin watch app -- it already handles incoming BLE messages
- Surfacing the rate limit warning on the watch (future story)
- Letting the user adjust the daily cap up/down from the app (future story)

### Implementation Plan

#### Increment 1: Extract pipeline into shared `CommutePipeline` class
- [x] Create `CommutePipeline.kt` with a `suspend fun run(direction: String, profile: CommuteProfile, model: GenerativeModel, rateLimiter: ApiRateLimiter, clock: () -> Long): PipelineResult` that encapsulates the full fetch->parse->filter->Gemini->deserialize flow from `onFetchLiveClicked()`
- [x] Define `PipelineResult` as a sealed class: `GoodService(status: CommuteStatus)`, `Decision(status: CommuteStatus, warning: String?)`, `RateLimited(reason: String)`, `Error(status: CommuteStatus, message: String)` -- every branch produces a `CommuteStatus` for BLE send except `RateLimited`
- [x] Refactor `MainActivity.onFetchLiveClicked()` to call `CommutePipeline.run()` and handle the result (display + BLE send) -- behavior must be identical to current
- [x] Write unit tests for `CommutePipeline`: mock `MtaAlertFetcher` and verify that empty filtered alerts produce `GoodService` without touching `rateLimiter`, and that `RateLimited` is returned when `tryAcquire()` is denied

**Testing:** Run unit tests. Open the app, tap "Fetch Live", confirm output is identical to before (same display format, same BLE behavior). Test with watch connected and disconnected.
**Model: Sonnet** | Reason: Extracting async pipeline logic with multiple branches and preserving exact behavior requires careful reasoning across the original code.

#### Increment 2: Expose rate limiter daily usage on MainActivity
- [ ] Add `fun getDailyUsage(): Pair<Int, Int>` to `ApiRateLimiter` -- returns `(todayCount, DAILY_CAP)`, resetting to 0 if the stored date is not today
- [ ] Add a `TextView` to `activity_main.xml` (below the results area or in a status bar) showing "API usage: N/50 today"
- [ ] Update the usage display after every pipeline run (manual or future service) and on `onResume()`
- [ ] When daily cap is reached, display a warning: "Daily limit reached: 50/50 -- polling paused until tomorrow"
- [ ] Unit test `getDailyUsage()` -- same day returns stored count, new day returns 0

**Testing:** Run unit tests. Open the app, confirm usage counter is visible. Tap "Fetch Live" a few times, verify the counter increments. Verify "Good Service" results don't increment it.
**Model: Composer** | Reason: Mechanical additions -- new method on existing class, new TextView, straightforward wiring.

#### Increment 3: Polling settings data model, repository, and UI
- [ ] Create `PollingSettings.kt`: `data class CommuteWindow(val startHour: Int, val startMinute: Int, val endHour: Int, val endMinute: Int)` with `fun isActive(hourOfDay: Int, minute: Int): Boolean`; `data class PollingSettings(val enabled: Boolean, val windows: List<CommuteWindow>, val intervalMinutes: Int)` with defaults (enabled=false, windows=[8:00-9:30, 17:30-19:00], interval=5)
- [ ] Create `PollingSettingsRepository.kt` -- persists/loads `PollingSettings` to SharedPreferences as JSON (same pattern as `CommuteProfileRepository`)
- [ ] Create `PollingSettingsActivity` with: on/off toggle, two commute window rows (start/end `TimePickerDialog` per window), polling interval `Slider` or `NumberPicker` (2-15 min range), Save button
- [ ] Add layout `activity_polling_settings.xml`
- [ ] Add "Polling Settings" button to `activity_main.xml` and wire it in `MainActivity`
- [ ] Register `PollingSettingsActivity` in `AndroidManifest.xml`
- [ ] Unit tests for `PollingSettings`: `isActive()` boundary cases (inside window, outside, exactly on boundary, window spanning midnight -- reject or handle), round-trip JSON serialization

**Testing:** Run unit tests. Open the app, tap "Polling Settings", configure windows and interval, save, reopen -- verify values persist. Toggle on/off and verify it persists.
**Model: Sonnet** | Reason: New activity with time pickers and data model with boundary logic needs careful implementation.

#### Increment 4: Foreground Service skeleton with start/stop
- [ ] Create `PollingForegroundService.kt` extending `Service` -- `onStartCommand()` creates a persistent notification and calls `startForeground()`; `onDestroy()` cleans up
- [ ] Create notification channel `commute_polling` on app startup (in `MainActivity.onCreate()` or an `Application` subclass)
- [ ] Declare `<service android:foregroundServiceType="dataSync">` and `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS` permissions in `AndroidManifest.xml`
- [ ] Wire `PollingSettingsActivity` Save button to start/stop the service based on the enabled toggle (use `startForegroundService()` / `stopService()`)
- [ ] Also start the service from `MainActivity.onCreate()` if settings show enabled=true
- [ ] Notification shows static text for now: "Commute Buddy -- Polling active"

**Testing:** Enable polling in settings, verify persistent notification appears and service is running (check via Settings > Apps > Running Services). Disable, verify notification disappears. Kill and reopen app with toggle on -- service should restart.
**Model: Sonnet** | Reason: Foreground service lifecycle on Android 14+ has specific requirements (notification channels, service types, permissions) that need precise handling.

#### Increment 5: Scheduling logic and pipeline integration
- [ ] Add coroutine-based scheduling loop in `PollingForegroundService`: launch a `CoroutineScope` in `onStartCommand()`, loop with `delay()` based on current interval
- [ ] Implement `getNextDelayMs()`: reads `PollingSettings`, checks if current time is within any commute window -> use `intervalMinutes`, otherwise -> 60 min; recalculates on each iteration
- [ ] Initialize `ConnectIQ` SDK, device discovery, and app lookup inside the service (same pattern as `MainActivity.initConnectIQ()` / `discoverDevice()` / `loadAppInfo()`)
- [ ] Initialize `GenerativeModel` in the service (same pattern as `MainActivity.initGeminiFlash()`) using profile from `CommuteProfileRepository`
- [ ] Each loop iteration: call `CommutePipeline.run()` -> on success, send BLE via `ConnectIQ.sendMessage()` -> update notification with last poll time and next scheduled poll
- [ ] Log each poll attempt and result for debugging
- [ ] When `PollingSettings` change (service restarted), the loop picks up new intervals on next iteration

**Testing:** Enable polling during a commute window, verify polls occur at the configured interval (check logcat for poll entries). Change interval in settings, verify new interval takes effect. Verify BLE push reaches the watch. Verify the notification updates with timestamps. Test outside commute window -- verify 1-hour interval.
**Model: Sonnet** | Reason: Async scheduling loop with window-aware interval switching, ConnectIQ lifecycle in a service context, and coroutine coordination require careful multi-file reasoning.

#### Increment 6: Boot receiver and final polish
- [ ] Create `BootReceiver.kt` -- `BroadcastReceiver` that starts `PollingForegroundService` if `PollingSettings.enabled` is true
- [ ] Declare `RECEIVE_BOOT_COMPLETED` permission and `<receiver>` in `AndroidManifest.xml` with `android.intent.action.BOOT_COMPLETED` intent filter
- [ ] Update the `MainActivity` daily usage display to also refresh when the service completes a poll (use a `BroadcastReceiver` or observe SharedPreferences changes)
- [ ] Verify rate-limit-reached warning appears on `MainActivity` when the service exhausts the daily cap

**Testing:** Reboot device (or use `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED`) with polling enabled -- verify service starts and notification appears. Let service run until rate limit is approached -- verify usage counter and warning display correctly on MainActivity.
**Model: Composer** | Reason: Boot receiver is boilerplate; the SharedPreferences observer for usage refresh is a standard Android pattern.

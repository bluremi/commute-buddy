# Active Development

## ARCH-01: Hybrid Polling Architecture — AlarmManager + Foreground Service

### Description
As a commuter, I want the background polling service to execute reliably on schedule even when my phone is in deep sleep, so that I always have a fresh commute recommendation on my watch when I glance at it.

The current `PollingForegroundService` uses a Kotlin coroutine `delay()` loop to schedule pipeline executions. When the device is unplugged and the screen is off, the OS enters Doze mode and the CPU enters deep sleep — suspending the coroutine indefinitely. This causes the app to miss its 5-minute polling windows entirely, resulting in stale data on the watch. The fix replaces the coroutine scheduling with `AlarmManager.setExactAndAllowWhileIdle()`, which uses the hardware Real-Time Clock to physically wake the CPU on schedule. The active `connectedDevice` foreground service exempts the app from Doze's 9-minute alarm throttle and keeps the ConnectIQ SDK connection warm.

### Acceptance Criteria

1. **Exact alarm scheduling replaces coroutine delay**
   - The `runPollingLoop()` coroutine loop with `delay()` is removed from `PollingForegroundService`
   - After each poll (and on service start), the service schedules the next execution via `AlarmManager.setExactAndAllowWhileIdle()` with a `PendingIntent` targeting the service's own `onStartCommand()`
   - The alarm fires a dedicated intent action (`ACTION_WAKE_AND_POLL`) so `onStartCommand()` can distinguish alarm-triggered starts from other starts

2. **WakeLock keeps the CPU alive during pipeline execution**
   - On receiving an `ACTION_WAKE_AND_POLL` intent, `onStartCommand()` acquires a `PowerManager.PARTIAL_WAKE_LOCK` with a safety timeout (e.g., 10 minutes) before launching the pipeline
   - The wake lock is released in a `finally` block after `poll()` completes — regardless of success, error, or exception
   - The `WAKE_LOCK` permission is added to `AndroidManifest.xml`

3. **Service restart recovers the alarm schedule**
   - When `onStartCommand()` receives a `null` intent (OS-initiated restart after memory reclamation) or a standard start intent (no `ACTION_WAKE_AND_POLL`), it immediately calculates and schedules the next alarm to restore the polling loop
   - No poll is executed on a restart-only start — the alarm will trigger the first poll

4. **Window boundary transitions are handled**
   - When scheduling the next alarm, the service calculates the candidate poll time and checks it against upcoming commute window boundaries
   - If the app is in an off-window period and the next on-the-hour poll would land inside or after a commute window start, the delay is truncated to fire at the exact window start time
   - During an active commute window, the interval remains `intervalMinutes` (default 5 min)

5. **SCHEDULE_EXACT_ALARM permission is handled**
   - `SCHEDULE_EXACT_ALARM` is declared in `AndroidManifest.xml`
   - Before scheduling an exact alarm, the service checks `AlarmManager.canScheduleExactAlarms()`
   - If the permission is not granted, `MainActivity` guides the user to the system settings page (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`) alongside the existing Bluetooth permission flow
   - `PollingSettingsActivity` also checks this permission when the user enables polling, and directs to settings if needed

6. **Pipeline concurrency is prevented**
   - A dedicated `Mutex` in `PollingForegroundService` wraps the entire `poll()` call (not just the Gemini call)
   - If a manual "Fetch Live" triggers `CommutePipeline.run()` at the same time as an alarm-triggered poll, one waits for the other (or the alarm poll is skipped if already in-flight)
   - The existing `ApiRateLimiter` single-flight check remains as a secondary guard

7. **Notification continues to show last/next poll times**
   - The persistent notification still displays "Last: HH:mm · Next: HH:mm" after each poll
   - The "Next" time is derived from the scheduled alarm time, not a calculated delay

8. **BootReceiver works with the new architecture**
   - `BootReceiver` continues to start the service on boot (if polling is enabled and BT permission is granted)
   - The service's `onStartCommand()` handles this as a standard start: schedules the first alarm, no immediate poll

9. **Off-window polls fire on the hour**
   - When outside a commute window, the next alarm is scheduled for the top of the next hour (e.g., 11:00, 12:00, 13:00) rather than 60 minutes from the last poll
   - If the top of the next hour falls inside a commute window, the alarm fires at that time and switches to the `intervalMinutes` cadence

### Implementation Plan

#### Increment 1: Permissions — Manifest + Runtime Checks
- [ ] Add `SCHEDULE_EXACT_ALARM` and `WAKE_LOCK` permissions to `AndroidManifest.xml`
- [ ] In `MainActivity.requestBluetoothPermissionsThenStartService()`, after BT permissions are granted, check `AlarmManager.canScheduleExactAlarms()` — if denied, launch `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` intent and defer service start to `onResume()` (which re-checks)
- [ ] In `PollingSettingsActivity.onSaveClicked()`, when enabling polling, check `canScheduleExactAlarms()` — if denied, launch the settings intent and defer save/start (similar pattern to the existing notification permission flow)
- [ ] Add a log line in `PollingForegroundService.onStartCommand()` that logs whether exact alarm permission is granted (informational for now; scheduling comes in increment 2)

**Testing:** Install the APK on the phone. On first launch, verify the exact alarm permission settings page opens after BT permissions are granted (Android 14+ denies by default for new installs). Grant the permission, return to the app, confirm the service starts and logs normally. In Polling Settings, toggle polling off → save → toggle on → save — confirm the permission check fires if it was revoked in between.

**Model: Sonnet** | Reason: Permission flow has async callbacks and conditional branching across two activities; needs careful state handling for the "return from settings" path.

#### Increment 2: AlarmManager Scheduling + WakeLock + Mutex
- [ ] In `PollingForegroundService`, remove the coroutine `delay()` loop (`runPollingLoop()`, `pollingJob`, and related imports)
- [ ] Add `ACTION_WAKE_AND_POLL` constant and a `PendingIntent` factory method that targets the service with this action
- [ ] Rewrite `onStartCommand()` intent routing: if `intent?.action == ACTION_WAKE_AND_POLL` → acquire `PARTIAL_WAKE_LOCK` (10-min timeout), launch `poll()` coroutine with `finally { wakeLock.release(); scheduleNextAlarm() }`; if `null` intent or standard start → just call `scheduleNextAlarm()` (no immediate poll)
- [ ] Implement `scheduleNextAlarm()`: calculate next alarm time using existing `getNextDelayMs()` logic, call `AlarmManager.setExactAndAllowWhileIdle()` with the `PendingIntent`, update `nextPollTimeMs` and notification
- [ ] Add a `kotlinx.coroutines.sync.Mutex` field; wrap the `poll()` call in `mutex.tryLock()` — if already locked (concurrent "Fetch Live"), skip the alarm-triggered poll and just reschedule
- [ ] In `onDestroy()`, cancel any pending alarm via `AlarmManager.cancel(pendingIntent)`

**Testing:** Enable polling, unplug the phone, turn the screen off, and leave it for 15–20 minutes spanning a commute window. Check `adb logcat -s PollingService:V` for poll entries at the expected interval. Verify polls actually fire (not stalled). Tap "Fetch Live" on the phone while a poll is due — confirm no crash and logs show one execution, not two concurrent ones. Reboot the phone — confirm the service restarts and schedules its first alarm without an immediate poll.

**Model: Sonnet** | Reason: Core architectural rewrite with async lifecycle coordination (WakeLock acquire/release across coroutine boundaries, intent routing, alarm scheduling). Getting the lifecycle right is critical.

#### Increment 3: Smart Scheduling — On-the-Hour + Window Boundaries
- [ ] Replace `getNextDelayMs()` with `getNextAlarmTimeMs(): Long` that returns an absolute epoch timestamp (not a relative delay)
- [ ] When inside a commute window: return `now + intervalMinutes * 60_000`
- [ ] When outside a commute window: compute top of the next hour (e.g., if it's 10:37, target 11:00)
- [ ] After computing the candidate time, check it against all commute window start times — if the candidate falls inside or past a window start, truncate to the window start time instead
- [ ] Update `scheduleNextAlarm()` to use the absolute timestamp directly with `setExactAndAllowWhileIdle(RTC_WAKEUP, absoluteMs, pendingIntent)`
- [ ] Update notification to derive "Next: HH:mm" from the scheduled absolute time

**Testing:** Set commute windows to narrow test ranges (e.g., morning window starts in 10 minutes). With polling enabled, verify via logcat that off-window polls fire on the hour. As the window approaches, verify the last off-window alarm fires at the exact window start time (not overshooting into the window). Inside the window, verify the `intervalMinutes` cadence. Check the notification shows the correct "Next" time throughout.

**Model: Sonnet** | Reason: Time arithmetic with window boundary edge cases (midnight crossings, overlapping windows, edge-of-hour starts) requires careful reasoning.

### Out of Scope
- Changing the polling interval defaults or commute window logic (FEAT-08 settings remain as-is)
- Migrating to `WorkManager` or `JobScheduler` (AlarmManager + FGS is the chosen architecture)
- Reconnecting the ConnectIQ SDK if BLE disconnects during sleep (existing graceful handling is sufficient)
- Changes to `CommutePipeline`, `ApiRateLimiter`, or the Garmin app (this is purely a scheduling architecture change)

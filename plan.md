## FEAT-16: Garmin On-Demand Poll Trigger

### Description
As a commuter traveling **off my normal schedule** (heading in late, leaving early), I want to trigger an immediate commute update from my Garmin watch, so that I get a fresh recommendation even though the current time is outside my configured polling windows — without pulling out my phone.

Today the watch only ever shows cached status pushed by the phone during scheduled windows (or the hourly off-window poll). Off-schedule, that status can be hours stale. This story adds the app's **first watch→phone message path** (`Communications.transmit`; only phone→watch exists today) so the watch can ask the phone to run `CommutePipeline.run()` right now for a chosen direction and push the result back through the existing BLE channel.

### Acceptance Criteria

1. **Ad-hoc screen is reachable via right swipe but is not the landing screen**
   - The app still opens on the main status view (the existing `ViewLoop` detail carousel); the ad-hoc screen is never shown by default.
   - A **right swipe** from the main status view reveals a dedicated "Update now" screen.
   - If a right swipe proves disproportionately costly to implement against the `ViewLoop`, a bottom vertical page is an acceptable fallback (right swipe preferred).
   - Existing horizontal paging through summary pages is preserved (no regression to the summary carousel).

2. **Ad-hoc screen controls**
   - The screen presents two clearly labeled, tappable controls (Venu 3 is a touchscreen): **"To Work"** and **"To Home"**.
   - The screen is self-explanatory (e.g., a short title like "Fetch update").

3. **Tapping a direction triggers a request and returns to the status view**
   - Tapping a button transmits a message to the paired Android app identifying the requested direction (`TO_WORK` / `TO_HOME`) via `Communications.transmit`.
   - Immediately after the tap, the watch returns to the main status view automatically — the user does **not** manually swipe back, and the app does **not** need to auto-navigate again when the reply lands (the status refreshes in place when the new update arrives).

4. **Android runs an immediate, direction-explicit poll**
   - The Android app receives the watch command (via a new ConnectIQ incoming-message listener) and runs `CommutePipeline.run(direction = requested)` immediately, independent of the configured polling windows/active days.
   - The requested direction is honored explicitly (not re-derived from the time-of-day window logic).
   - On success, the result is pushed back to the watch through the **existing** BLE/`notifyAll()` path; the main status view updates (new timestamp, refreshed action/summary) with no special-case rendering.

5. **Rate limiting and safety are respected**
   - Ad-hoc polls go through the existing `ApiRateLimiter` (count against the 60/day cap, per-minute cap, cooldown, single-flight mutex).
   - A rapid double-tap or repeated requests cannot launch overlapping pipelines or bypass the limiter.

6. **No regressions**
   - Glance, scheduled/background polling, BUG-13 error suppression for *scheduled* polls, and the existing phone→watch schema are unchanged.
   - Uses the already-granted `Communications` permission (no manifest permission change).

### Out of Scope
- **In-flight and failure feedback** — deferred to a separate future story. This story returns to the status view and relies on the timestamp/status refreshing when the update arrives; if the request fails (phone unreachable, rate-limited, pipeline error), the watch simply shows no update — the same visible outcome as today's BUG-13 behavior. No "Updating…" indicator and no error view in this story.
- **Wear OS equivalent** — Garmin-only. An on-demand trigger for the Wear OS app is a separate future story.
- **Directions beyond To Work / To Home** — no custom/arbitrary direction selection.
- **Changes to scheduled polling** — windows, intervals, active days, and auto-direction logic are untouched.
- **Reviving a killed app from a watch request** — the Android foreground service must be running to receive the command (its normal operating state).

### Implementation Plan

#### Increment 1: Android — on-demand poll path (direction-explicit), triggerable via intent
- [x] Add `ACTION_POLL_NOW` to `PollingForegroundService` and handle it in `onStartCommand`, reading a `direction` string extra (`TO_WORK` / `TO_HOME`).
- [x] Extract a `pollNow(direction: String)` that runs `CommutePipeline.run(direction=…)` immediately + `notifyAll()`, **bypassing** window/active-day gating and `resolvePollingDirection`, but still guarded by `pollMutex` and the existing `ApiRateLimiter`. Does **not** touch alarm scheduling.
- [x] Refactor the existing scheduled `poll()` to share the pipeline-run core with `pollNow()` (single code path; direction is the only difference).
- [x] Unit test: on-demand path passes the explicit direction through to the pipeline; overlapping requests are serialized / rate-limited (extend `PollingForegroundServiceSchedulingTest` patterns).

**Testing:** Run `:app:testDebugUnitTest`. Then live: `adb shell am start-foreground-service -n com.commutebuddy.app/.PollingForegroundService -a com.commutebuddy.app.POLL_NOW --es direction TO_WORK` and confirm in logcat (`PollingService`, `CommutePipeline`) that a poll runs off-window and pushes to the watch.
**Model: Sonnet** | Reason: Real refactor of the poll path with rate-limiter/mutex interaction; must not disturb existing scheduling.

#### Increment 2: Garmin — ad-hoc screen (horizontal-swipe overlay) + transmit + synchronous return
- [x] New `AdHocPageView.mc` (title + "To Work" / "To Home" tappable regions) and `AdHocPageDelegate.mc` (`onTap` hit-tests which button by screen midpoint).
- [x] `PollRequestListener.mc`: no-op `ConnectionListener` for `Communications.transmit` (in-flight/failure feedback out of scope).
- [x] On button tap: `Communications.transmit("POLL_NOW:TO_WORK" / "POLL_NOW:TO_HOME", null, listener)`, then `WatchUi.popView(WatchUi.SLIDE_RIGHT)` to return to status.
- [x] Document the watch→phone command string in `shared/schema.json`.

**Final approach (deviated from original index-0 plan):** The `ViewLoop` is a **vertical** carousel (`ViewLoopDelegate` only exposes `onNextView`/`onPreviousView` via SWIPE_UP/DOWN and is *not* a `BehaviorDelegate`, so it has no swipe hook). Prepending the ad-hoc page as index 0 put it *above* status — disorienting. Instead: the horizontal axis is unused by the loop, so `DetailPageDelegate.onSwipe` catches a **left/right swipe** and `pushView`s the ad-hoc screen as an overlay; tapping a button transmits and `popView`s back. `DetailPageFactory`/`getInitialView` reverted to status+summary only. Confirmed on-device that the ViewLoop forwards off-axis (horizontal) swipes to the per-page delegate.

**Testing:** ✅ Verified on-device (Venu 3): app lands on status with nothing above it; horizontal swipe reveals the ad-hoc screen; both buttons tap → transmit + pop back to status; vertical swipe still pages the summary. (Actual phone delivery is verified in Increment 3.)

#### Increment 3: Android — receive the watch command and dispatch the poll
- [x] In `GarminNotifier`, register for incoming app events on the connected device/app (`registerForAppEvents` + `IQApplicationEventListener`) once the watch app is confirmed; unregister on teardown (`PollingForegroundService.onDestroy` → `unregisterForIncomingMessages`).
- [x] On message received, parse the `POLL_NOW:<DIR>` string, validate the direction, and dispatch via the existing `ACTION_POLL_NOW` intent (`startForegroundService`) — reuses increment 1's `pollMutex` + `ApiRateLimiter` path. No new permission.
- [x] Ignore malformed / unknown payloads silently (`parsePollNowDirection` returns null → logged and dropped).
- [x] Unit test the parse/validate helper (`GarminNotifierParseTest`: TO_WORK/TO_HOME accepted; junk, wrong prefix, bare direction, empty, non-string, wrong case, null all rejected).

**Testing:** ✅ Verified on hardware (Pixel 10 Pro XL + Venu 3). Logcat confirmed the full round-trip off-window (next scheduled alarm was 734 min away): `Registered for incoming app events` → `Watch requested on-demand poll: TO_WORK` → `ACTION_POLL_NOW` → `On-demand poll requested (direction=TO_WORK)` → `Poll result: Decision` → `BLE send success`. Reopening the watch app showed the fresh recommendation with a `<1 min ago` timestamp.
**Model: Sonnet** | Reason: ConnectIQ SDK receive-side integration and lifecycle (register/unregister) alongside the pipeline dispatch.

#### Increment 4: Garmin — live-refresh the status view when the on-demand update arrives
Verified mechanism: `ViewLoop` has no reload/setPage method (only `changeView(direction)` + a `:page` constructor option), and `DetailPageView` renders constructor snapshots — so the only way to show fresh data is to build a **new** `ViewLoop` and `switchToView` to it. `switchToView` throws `OperationNotAllowedException` from a glance/background context, so the rebuild must be gated to the foreground full-app process.

- [ ] Add instance flag `_fullAppForeground` to `CommuteBuddyApp`: set `true` at the end of `getInitialView()`, `false` in `onStop()`. (Never set in the glance process, which is a separate process.)
- [ ] In `onPhoneMessage`, after storing `cs_*`: if `_fullAppForeground`, **rebuild** — construct a fresh `DetailPageFactory` + `ViewLoop({:page => 1, :wrap => false})` + `ViewLoopDelegate` and `WatchUi.switchToView(loop, delegate, WatchUi.SLIDE_IMMEDIATE)`, wrapped in `try/catch` (`OperationNotAllowedException` safety). Otherwise keep the existing `requestUpdate()` (glance path, unchanged).
- [ ] Confirm the glance still refreshes normally and does **not** attempt `switchToView` (flag stays false in the glance process).

**Testing:** Hardware — tap a direction, **stay** on the status view; within a few seconds it rebuilds to the new recommendation + timestamp with no manual interaction. Simulator — with the full app open, inject a phone message → view rebuilds to new data; with only the glance open, inject a message → glance updates and does **not** crash.
**Model: Sonnet** | Note: If calling `switchToView` directly inside the message callback proves flaky, defer it via a 0 ms `Timer` started from `onPhoneMessage` (foreground) — the standard "don't do heavy nav inside the callback" pattern; the flag + `try/catch` still guard it.

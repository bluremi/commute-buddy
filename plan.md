# Active Development

## FEAT-14: Automatic Commute Direction Handling

### Description
As a commuter, I want the system to automatically determine the commute direction based on which polling window is active, so that I don't have to manually toggle between "TO WORK" and "TO HOME" every morning and evening.

Currently, direction is a global toggle in SharedPreferences that affects both the "Fetch Live" button and the background polling service equally. The user must remember to flip the toggle when their commute direction changes (morning vs evening). This is error-prone — if the user forgets to toggle, the polling service sends the wrong direction to Gemini and they get irrelevant recommendations on their watch. The fix: the polling service should automatically derive direction from the active window (morning window → TO_WORK, evening window → TO_HOME), and the UI toggle should only control the direction used by the manual "Fetch Live" button. Outside of any active window, the last polled direction should be used.

### Acceptance Criteria

1. **Automatic Direction from Active Window**
   - When the polling service fires during the morning commute window, it passes `TO_WORK` to the pipeline regardless of the UI toggle state
   - When the polling service fires during the evening commute window, it passes `TO_HOME` to the pipeline regardless of the UI toggle state
   - Outside of both windows, the polling service uses the direction from the most recent active-window poll (persisted so it survives service restarts)

2. **Manual Fetch Live Uses Toggle**
   - The "Fetch Live" button continues to use the direction selected by the TO WORK / TO HOME toggle
   - The toggle no longer affects background polling direction

3. **Home Screen UX Clarity**
   - The TO WORK / TO HOME toggle and Fetch Live button are visually grouped together so it's clear they are related
   - There is a label or helper text that communicates that the toggle controls the Fetch Live direction
   - The current automatic polling direction is displayed somewhere on the home screen (e.g., a status line showing "Polling: TO WORK" or "Polling: TO HOME") so the user always knows what direction the background service is using
   - The UX makes it obvious that background polling chooses direction automatically based on the active window

4. **Direction Persistence Across Restarts**
   - The last-polled direction is persisted in SharedPreferences so that after a reboot or service restart outside an active window, the correct direction is still used
   - This is a separate preference key from the manual toggle direction

5. **Edge Cases**
   - If the user has only one commute window configured (e.g., only morning), the service uses TO_WORK during that window and the last-polled direction otherwise
   - If no poll has ever occurred (fresh install), the default direction outside a window is TO_WORK

### Out of Scope
- Renaming or relabeling the commute windows as "morning" / "evening" in PollingSettingsActivity (they remain generic window 1 / window 2)
- Allowing the user to customize which direction maps to which window (hardcoded: window 1 = TO_WORK, window 2 = TO_HOME)
- Changes to the Garmin watch UI
- Changes to the Gemini prompt or system prompt structure

### Implementation Plan

#### Increment 1: Extract direction-resolution logic into a pure, testable function
- [ ] Add a `resolvePollingDirection()` companion function to `PollingForegroundService` that takes `settings: PollingSettings`, `hour: Int`, `minute: Int`, and `lastPolledDirection: String?` → returns a direction string. Logic: if inside window 1 → `TO_WORK`; if inside window 2 → `TO_HOME`; else → `lastPolledDirection ?: "TO_WORK"`
- [ ] Add a new SharedPreferences key `KEY_LAST_POLLED_DIRECTION` (separate from `KEY_DIRECTION`) for persisting the last auto-resolved direction
- [ ] Update `poll()` in `PollingForegroundService` to call `resolvePollingDirection()` using the current time and settings, use the result instead of reading `KEY_DIRECTION`, and persist the resolved direction to `KEY_LAST_POLLED_DIRECTION` when inside a window
- [ ] Write unit tests in `PollingForegroundServiceSchedulingTest.kt` for `resolvePollingDirection()`: morning window → TO_WORK, evening window → TO_HOME, outside both → falls back to last polled, no last polled → defaults TO_WORK

**Testing:** Run unit tests via Gradle (`testDebugUnitTest`). Manually verify on device: toggle to TO_HOME in the app, trigger a background poll during a commute window — logcat should show the window-derived direction, not the toggle value.
**Model: Sonnet** | Reason: New logic with edge cases (window matching, fallback chain) requires careful reasoning.

#### Increment 2: Home screen UX — group toggle with Fetch Live and show polling direction
- [ ] In `activity_main.xml`, add a label above the direction toggle (e.g., "Manual fetch direction:") and visually group the toggle + Fetch Live button (e.g., with a `MaterialCardView` or a subtle divider/label)
- [ ] Add a new `TextView` below the Fetch Live group showing the current automatic polling direction (e.g., "Auto-polling direction: TO WORK (morning window)") — this reads from `KEY_LAST_POLLED_DIRECTION` and the current window state
- [ ] In `MainActivity`, add logic to compute and display the current auto-polling direction on `onCreate` and `onResume` (reusing `resolvePollingDirection()` or reading the persisted value)
- [ ] Add a string resource for the explanatory label text

**Testing:** Manually verify: open the app, confirm the toggle and Fetch Live button are visually grouped with a label, confirm the auto-polling direction status line is visible and updates on resume. Toggle TO WORK / TO HOME and confirm it does NOT change the auto-polling direction display.
**Model: Composer** | Reason: Mostly XML layout changes and simple TextView binding — mechanical UI work.

#### Increment 3: Polish and edge cases
- [ ] Handle the edge case where only one commute window is configured: window 1 (first in list) always maps to TO_WORK, window 2 to TO_HOME. If there's only one window, that window maps to TO_WORK and outside it falls back to last polled direction
- [ ] Update `resolvePollingDirection()` to use window index (0 → TO_WORK, 1 → TO_HOME) rather than positional assumptions, handling lists with 0, 1, or 2 windows
- [ ] Update the auto-polling direction display to show contextual text: "TO WORK (morning window)" / "TO HOME (evening window)" / "TO WORK (last polled)" depending on state
- [ ] Add unit tests for edge cases: no windows configured, single window, outside both windows with no prior poll

**Testing:** Run unit tests. Manually verify: in Polling Settings, disable the evening window (if possible) or test with a single window — confirm the direction resolves correctly and the display text is accurate.
**Model: Sonnet** | Reason: Edge case logic and contextual display text require careful reasoning about state combinations.

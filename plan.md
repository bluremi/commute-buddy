# Active Development

## FEAT-12: Active Days Selector & Background Polling Toggle

### Description
As a commuter, I want to select which days of the week get intensive in-window polling, so that the system doesn't burn API calls at high frequency on weekends or WFH days — while still keeping hourly background polls running for ad-hoc travel days.

Currently, polling runs the same schedule every day. The commute windows and interval slider control when intensive polling happens, but there's no way to restrict that to specific days. This feature adds two controls: (1) a day-of-week selector that determines which days get intensive in-window polling, and (2) a toggle for hourly background polls outside of commute times (including inactive days), so the user can still glance at fresh-ish status on unplanned travel days without burning through the daily API cap.

### Acceptance Criteria

1. **Day-of-Week Toggle UI**
   - A horizontal row of 7 toggle buttons (M, T, W, T, F, S, S) is displayed in `PollingSettingsActivity`
   - The row is positioned below the evening commute window section and above the polling interval slider
   - Selected days have a filled background using the app's primary color
   - Unselected days have a transparent/outlined appearance
   - Each button is independently toggleable
   - Default: Monday–Friday selected, Saturday–Sunday unselected

2. **Intensive vs. Background Polling Behavior**
   - On **active days inside a commute window**: poll at the configured interval (2–15 min slider)
   - On **active days outside commute windows**: poll hourly (existing behavior, unchanged)
   - On **inactive days**: poll hourly (same as outside-window behavior), NOT silent
   - The active days setting only controls which days get intensive in-window polling — it does not suppress polling entirely

3. **Background Polling Toggle**
   - A toggle labeled something like "Hourly polls when not commuting" appears below the interval slider
   - Default: ON
   - When ON: hourly polls fire outside commute windows and on inactive days (as described above)
   - When OFF: no polls fire outside active-day commute windows (fully silent on inactive days, silent outside windows on active days)
   - Persisted in `PollingSettings` alongside the other fields

4. **Persistence & Backward Compatibility**
   - Active days and the background polling toggle are saved as part of the `PollingSettings` JSON blob in SharedPreferences
   - Existing users who upgrade (no `activeDays` or `backgroundPolling` in persisted JSON) get defaults: M–F active, background polling ON
   - JSON round-trip is lossless

5. **Alarm Scheduling**
   - `PollingForegroundService.getNextAlarmTimeMs()` implements the three-tier logic: intensive (active day + in window), hourly (background toggle ON + outside intensive times), or skip-to-next-intensive (background toggle OFF)
   - When background polling is OFF and it's an inactive day, the next alarm targets the earliest window start on the next active day
   - No infinite loops if all days are deselected with background OFF — treat as polling disabled (or prevent saving with a message)

6. **Edge Cases**
   - All 7 days deselected + background ON: only hourly polls, no intensive windows ever
   - All 7 days deselected + background OFF: effectively polling disabled — show a toast/snackbar warning on save
   - Transitioning midnight from inactive → active day: first intensive poll at window start, not midnight

7. **Visual Consistency**
   - The toggle row has a section label consistent with existing labels
   - Day abbreviations are clear (single letters, or two-letter if T/T and S/S prove confusing in testing)

8. **Unit Tests**
   - `PollingSettings` JSON round-trip updated to include `activeDays` and `backgroundPolling`
   - Backward compatibility: old JSON without new fields deserializes to M–F + background ON
   - `getNextAlarmTimeMs()` tested for: inactive day with background ON (→ hourly), inactive day with background OFF (→ next active day window), active day outside window with background OFF (→ next window start)

### Out of Scope
- Per-window day selection (e.g., different days for morning vs evening) — all windows share the same active days
- Different background polling intervals (always hourly, not configurable)
- Holiday calendar or automatic WFH detection
- Syncing settings to the Garmin watch

### Implementation Plan

#### Increment 1: Data model — add `activeDays` and `backgroundPolling` to `PollingSettings`
- [x] Add `activeDays: Set<Int>` field to `PollingSettings` (using `java.util.Calendar` day constants: `Calendar.MONDAY`..`Calendar.SUNDAY`). Default: Mon–Fri.
- [x] Add `backgroundPolling: Boolean` field to `PollingSettings`. Default: `true`.
- [x] Update `toJson()` to serialize `activeDays` as a JSON array of ints and `backgroundPolling` as a boolean.
- [x] Update `fromJson()` to deserialize the new fields, falling back to defaults when keys are missing (backward compatibility).
- [x] Update `default()` to include the new fields.
- [x] Add unit tests in `PollingSettingsTest.kt`: JSON round-trip with new fields, backward-compat deserialization of old JSON (no `activeDays`/`backgroundPolling` keys), verify default values.

**Testing:** Run unit tests via Gradle: `$gradle :app:testDebugUnitTest --tests "com.commutebuddy.app.PollingSettingsTest"`

**Model: Composer** | Reason: Mechanical field additions to an existing data class + JSON serialization following established patterns.

#### Increment 2: Scheduling logic — three-tier alarm scheduling in `getNextAlarmTimeMs()`
- [x] Add a helper `isActiveDay(dayOfWeek: Int): Boolean` that checks if the given `Calendar.DAY_OF_WEEK` is in `activeDays`.
- [x] Refactor `getNextAlarmTimeMs()` to implement three tiers:
  1. **Active day + inside window** → interval-based polling (existing behavior)
  2. **Background polling ON + outside intensive times** (inactive day, or active day outside windows) → hourly (top of next hour, or next window start if sooner on an active day)
  3. **Background polling OFF + outside intensive times** → skip to earliest window start on the next active day
- [x] Handle edge case: all days deselected + background OFF → return a far-future sentinel or stop scheduling (service will idle).
- [x] Update `nextOccurrenceOf()` to optionally constrain to active days when background polling is OFF.
- [x] Add unit tests: extract `getNextAlarmTimeMs` logic into a testable pure function (or test via a helper that accepts `now`, `settings`, and returns the next alarm time). Test cases: inactive day + background ON (→ hourly), inactive day + background OFF (→ next active day's window), active day outside window + background ON (→ hourly), active day outside window + background OFF (→ next window start), all days off + background OFF (→ no scheduling).

**Testing:** Run unit tests via Gradle: `$gradle :app:testDebugUnitTest`. Then deploy to device, verify logcat shows correct "Next alarm in Xm Ys" for various scenarios (change phone date/time to simulate active vs inactive days).

**Model: Sonnet** | Reason: Multi-branch scheduling logic with edge cases and date arithmetic — needs careful reasoning about day boundaries and Calendar math.

#### Increment 3: UI — active days toggle row and background polling switch
- [ ] Add string resources: `polling_label_active_days` ("Active Commute Days"), `polling_label_background_polling` ("Hourly polls when not commuting"), day abbreviations.
- [ ] In `activity_polling_settings.xml`, add between the evening window section and the interval slider:
  1. A bold label "Active Commute Days"
  2. A horizontal `MaterialButtonToggleGroup` with 7 `MaterialButton` children (M, T, W, T, F, S, S), each with a unique ID. Style: outlined when unchecked, filled with primary color when checked. Use `app:singleSelection="false"` for multi-select.
  3. A `SwitchMaterial` toggle for "Hourly polls when not commuting" with label, below the interval slider.
- [ ] In `PollingSettingsActivity.kt`:
  1. Bind the toggle group and background switch
  2. On load: check buttons matching `activeDays`, set background switch from `backgroundPolling`
  3. On save: read checked button IDs → map to `Calendar.DAY_OF_WEEK` values → build `Set<Int>`; read background switch state
  4. Edge case: if all days unchecked AND background OFF, show a Snackbar warning ("No polls will run — enable background polling or select at least one day") and prevent save.
- [ ] Pass the new fields through to the `PollingSettings` constructor in `onSaveClicked()`.

**Testing:** Manually verify: open Polling Settings, confirm day buttons appear between evening window and interval slider. Toggle days on/off — verify filled/outlined styling. Toggle background switch. Save, reopen — verify selections persisted. Try deselecting all days with background OFF — verify warning appears and save is blocked.

**Model: Sonnet** | Reason: UI wiring with MaterialButtonToggleGroup (non-trivial mapping between button IDs and Calendar constants) plus edge-case validation logic.

#### Increment 4: Integration test & polish
- [ ] Deploy to device and test end-to-end: set active days to weekdays only, verify intensive polling fires during commute windows on a weekday, and only hourly polls on a weekend (check logcat for alarm intervals).
- [ ] Verify background toggle OFF suppresses all polls on inactive days (no alarms fire until the next active day's window).
- [ ] Verify notification text still updates correctly after each poll.
- [ ] Verify backward compatibility: clear app data, install, confirm defaults are M–F + background ON.
- [ ] Adjust any visual spacing/alignment issues in the settings layout.

**Testing:** Full manual integration test on physical device. Verify logcat `PollingService` tag for correct scheduling behavior across all three tiers. Test reboot persistence (enable polling → reboot → check logcat for correct schedule resumption).

**Model: Composer** | Reason: Minor polish and spacing adjustments — no new logic.

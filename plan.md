## FEAT-15: Garmin Glance — show last-update timestamp

### Description
As a commuter, I want the Garmin glance to show the absolute time of the last update (e.g., "1:28pm") below the status line, so that I can tell whether I'm looking at current data or a stale snapshot from before a glance crash/recovery.

The BUG-12 crash resilience fix (deferred registration via Timer) means the glance now self-heals after crashes instead of going permanently blank. The tradeoff is that after recovery, the glance renders from cached `Application.Storage` data — which may be minutes old. There's no visual cue to distinguish "live and current" from "recovered but stale." An absolute timestamp solves this: if the glance says "1:28pm" and it's now 1:45pm, the user knows to tap through to the detail view rather than trusting the displayed action tier.

### Acceptance Criteria

1. **Timestamp displayed on all action tiers**
   - NORMAL, MINOR_DELAYS, REROUTE, and STAY_HOME glance states all show a timestamp line below the action/routes text
   - The timestamp reads the existing `cs_timestamp` value from `Application.Storage` (Unix epoch seconds, already stored by `CommuteBuddyApp.onPhoneMessage`)

2. **12-hour absolute time format**
   - Format: `h:MMam` / `h:MMpm` (e.g., "1:28pm", "12:05am", "9:00am")
   - No date component — time only
   - Uses device-local time zone (via `Time.Gregorian.info()`)

3. **Visual styling: small and unobtrusive**
   - Grey color (`Graphics.COLOR_LT_GRAY` or similar) — must not compete with the action text for attention
   - Smallest legible font available in glance context (likely `FONT_GLANCE_NUMBER` or equivalent — exact font determined during implementation)
   - Horizontally centered

4. **Layout accommodates two lines**
   - The existing action text (and route letters for MINOR_DELAYS/REROUTE) shifts upward so both lines are visually balanced on the glance
   - No text overlap or clipping on the Venu 3 glance viewport

5. **No timestamp when no data exists**
   - The "Waiting..." state (no `cs_action` in Storage) does not display a timestamp
   - If `cs_timestamp` is missing or not a Number but `cs_action` exists, the action line renders normally without a timestamp (graceful degradation, no crash)

6. **Memory budget respected**
   - The change stays well within the 32KB glance memory limit
   - No new imports or allocations that would meaningfully impact memory

### Implementation Plan

#### Increment 1: Timestamp formatting + NORMAL/STAY_HOME layout
- [ ] Add `import Toybox.Time;` to `CommuteBuddyGlanceView.mc`
- [ ] Add a `formatTimestamp(epochSeconds as Number) as String` helper method that converts Unix epoch seconds to 12-hour local time (e.g., "1:28pm", "12:05am") using `Time.Gregorian.info()`. Handle edge cases: hour 0 → "12am", hour 12 → "12pm", minutes < 10 → zero-padded.
- [ ] Read `cs_timestamp` from `Application.Storage` at the top of `onUpdate()` alongside the existing `cs_action` / `cs_affected_routes` reads
- [ ] Modify the NORMAL branch: measure the action font height and timestamp font height, compute y-offsets so both lines are vertically centered as a group. Draw "Normal" in green above, timestamp in `COLOR_LT_GRAY` with `FONT_GLANCE_NUMBER` (or smallest legible glance font) below.
- [ ] Modify the STAY_HOME branch: same two-line centered layout — "Stay Home" in light gray above, timestamp below.
- [ ] If `cs_timestamp` is missing or not a Number, fall back to the existing single-line centered layout (no timestamp, no crash)

**Testing:** Build for simulator (`Ctrl+Shift+B`). Use the Connect IQ simulator to send test payloads with `action=NORMAL` and `action=STAY_HOME`. Verify: timestamp appears below action text, both lines visually centered, correct 12-hour format. Then test with no `cs_timestamp` in the payload — confirm action renders centered with no timestamp.

**Model: Sonnet** | Reason: Time formatting edge cases (midnight/noon, zero-padding) and layout math require careful reasoning, not just mechanical changes.

#### Increment 2: MINOR_DELAYS/REROUTE timestamp + all-tier verification
- [ ] Modify the MINOR_DELAYS/REROUTE branch in `onUpdate()`: shift the existing colored prefix + route letters line upward to make room, then draw the timestamp in `COLOR_LT_GRAY` / small font below, horizontally centered
- [ ] The x-position calculation for the colored segments is unchanged — only the y-coordinate shifts up from `cy` to account for the two-line group centering
- [ ] Verify graceful degradation: if `cs_timestamp` is missing, the colored route line renders centered as before (same fallback as increment 1)

**Testing:** Build for simulator. Test all four action tiers: NORMAL, STAY_HOME, MINOR_DELAYS (e.g., routes "N,W"), REROUTE (e.g., routes "N,W,4,5"). Verify timestamp appears below each, layout is balanced, no clipping. Test with 1 route and 4+ routes to check the wider route strings don't crowd the timestamp. Sideload to physical Venu 3 to verify on-device rendering.

**Model: Sonnet** | Reason: Adjusting the multi-segment colored text layout requires understanding the existing x/y coordinate math and only changing the vertical component.

### Out of Scope
- Relative timestamps ("5 min ago") — these go stale if the glance is showing a cached render
- Staleness warnings or color changes based on timestamp age (e.g., turning red after 10 minutes)
- Date display (the user checks this multiple times per commute window — date is not useful)
- Changes to the Garmin detail view (it already has a freshness line)
- Changes to the Wear OS tile or app
- 24-hour time format option

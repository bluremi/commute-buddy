# Active Development

## FEAT-06: Garmin Glance + Full-App UI

### Description
As a commuter, I want the Garmin watch to display color-coded action tiers on the glance and show full commute details (summary, reroute hint, freshness) when I tap into the app, so that I can instantly assess my commute status at a glance and drill into details when needed.

FEAT-05 introduced the 4-tier decision engine (NORMAL/MINOR_DELAYS/REROUTE/STAY_HOME) and updated the BLE schema to send `action` (string), `summary`, `affected_routes`, `reroute_hint`, and `timestamp`. However, the watch-side code still reads the **old** schema fields (`status` as integer 0–2, `route_string`, `reason`). This means nothing from FEAT-05 currently displays correctly on the watch. This story fixes the schema mismatch, adds color-coding to the glance, and builds out the full-app detail view.

### Acceptance Criteria

1. **Watch Message Handler Updated for New Schema**
   - `CommuteBuddyApp.onPhoneMessage()` reads the new BLE keys: `action` (String), `summary` (String), `affected_routes` (String), `reroute_hint` (String, optional), `timestamp` (Number)
   - `action` must be one of `NORMAL`, `MINOR_DELAYS`, `REROUTE`, `STAY_HOME` — reject message otherwise
   - `summary` and `affected_routes` must be non-null Strings — reject message otherwise
   - `reroute_hint` is stored if present (String), ignored if absent
   - `timestamp` is stored if present (Number), ignored if absent
   - All five fields stored in `Application.Storage` with new key names (e.g., `cs_action`, `cs_summary`, `cs_affected_routes`, `cs_reroute_hint`, `cs_timestamp`)
   - Invalid or incomplete messages are silently ignored (no crash)

2. **Color-Coded Glance**
   - Glance text color changes based on action tier:
     - NORMAL → green
     - MINOR_DELAYS → yellow
     - REROUTE → red
     - STAY_HOME → gray
   - Glance text shows: `"Normal"`, `"Delays — N,W"`, `"Reroute — N,W"`, or `"Stay Home"` (using `affected_routes` for the route display)
   - When no data is stored yet (first launch), shows `"Waiting..."` in white
   - Background remains black for all states

3. **Full-App Detail View**
   - When the user taps the glance to open the full app, `CommuteBuddyView` displays:
     - **Action tier** as a prominent color-coded header (same color mapping as glance): "Normal", "Minor Delays", "Reroute", or "Stay Home"
     - **Summary** text (up to 80 chars, may wrap)
     - **Affected routes** (e.g., "N, W")
     - **Reroute hint** — shown only when action is `REROUTE` and `reroute_hint` is stored
     - **Freshness** — relative time since `timestamp` (e.g., "2 min ago", "1 hr ago", "Stale" if >2 hours)
   - When no data is stored yet, displays a centered `"Waiting for update..."` message
   - Layout fits the Venu 3's 390×390 round AMOLED screen — text is vertically stacked and horizontally centered, with appropriate font sizes for readability

4. **Full-App View Reads from Storage on Every Update**
   - `CommuteBuddyView.onUpdate()` reads all fields from `Application.Storage` (same as glance)
   - When `WatchUi.requestUpdate()` fires (from a new BLE message), the full-app view refreshes with the latest data if it is the active view

5. **Memory Budget Respected**
   - Glance-mode code stays under 32KB using `:glance` annotations
   - No JSON parsing, network requests, or heavy computation on the watch — all data comes pre-structured from Android via BLE Dictionary
   - The full-app view does not import unnecessary modules

### Implementation Plan

#### Increment 1: Message handler + color-coded glance
- [ ] Rewrite `CommuteBuddyApp.onPhoneMessage()` to read new BLE schema keys: `action` (String, must be one of NORMAL/MINOR_DELAYS/REROUTE/STAY_HOME), `summary` (String), `affected_routes` (String), `reroute_hint` (String, optional), `timestamp` (Number, optional)
- [ ] Store validated fields in `Application.Storage` with keys: `cs_action`, `cs_summary`, `cs_affected_routes`, `cs_reroute_hint`, `cs_timestamp` — silently reject messages with missing/invalid required fields
- [ ] Clear `cs_reroute_hint` from Storage when a message arrives without one (prevents stale hint from a previous REROUTE persisting into a NORMAL update)
- [ ] Rewrite `CommuteBuddyGlanceView.onUpdate()` to read `cs_action` and `cs_affected_routes` from Storage
- [ ] Map action tier to text: `"Normal"`, `"Delays — {routes}"`, `"Reroute — {routes}"`, `"Stay Home"`
- [ ] Map action tier to foreground color: NORMAL → `COLOR_GREEN`, MINOR_DELAYS → `COLOR_YELLOW`, REROUTE → `COLOR_RED`, STAY_HOME → `COLOR_LT_GRAY`
- [ ] Keep `"Waiting..."` in white as the no-data fallback

**Testing:** Build for Venu 3 simulator (`Ctrl+Shift+B`). Deploy Android app to phone, tap "Fetch Live" to trigger BLE push. Verify glance shows correct color-coded text. Test with live alerts (should show delays/reroute tier) and with no active alerts (should show green "Normal"). Confirm `"Waiting..."` appears on fresh install before first message.

**Model: Composer** | Reason: Mechanical field mapping and switch statements across two files with clear specs.

#### Increment 2: Full-app detail view
- [ ] Rewrite `CommuteBuddyView.onUpdate()` to read all five fields from `Application.Storage` (`cs_action`, `cs_summary`, `cs_affected_routes`, `cs_reroute_hint`, `cs_timestamp`)
- [ ] Display vertically stacked layout on the 390×390 round screen:
  - **Action header** — large font, color-coded (same mapping as glance): "Normal", "Minor Delays", "Reroute", "Stay Home"
  - **Summary** — medium font, white, may wrap across lines (use `Graphics.TEXT_JUSTIFY_CENTER`)
  - **Affected routes** — small font, white (e.g., "Routes: N, W"); omit line for NORMAL with empty routes
  - **Reroute hint** — small font, shown only when `cs_action` is `"REROUTE"` and `cs_reroute_hint` is stored
  - **Freshness** — small font, light gray, relative time since `cs_timestamp`: "<1 min ago", "N min ago", "N hr ago", or "Stale" if >2 hours
- [ ] When no data in Storage, display centered `"Waiting for update..."` in white
- [ ] Import `Toybox.Time` for freshness calculation (current time minus stored timestamp)

**Testing:** Build for simulator. From Android, tap "Fetch Live", then tap the Commute Buddy glance in the simulator to open the full app. Verify: (1) all fields display correctly for a NORMAL response, (2) reroute hint appears for a REROUTE response, (3) freshness shows reasonable "N min ago", (4) `"Waiting for update..."` shows on fresh install.

**Model: Sonnet** | Reason: Round-screen layout with conditional sections, text wrapping, and relative time calculation benefits from stronger reasoning.

### Out of Scope
- Android-side changes (the Android app already sends the correct new schema via `toConnectIQMap()`)
- Commute direction display or toggle (FEAT-07)
- Background polling or scheduled updates (FEAT-08)
- Haptic/vibration alerts for severe tiers
- Watch-initiated refresh (pulling new data from phone)
- Customizable color mapping or themes

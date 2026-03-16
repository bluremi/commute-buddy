# Active Development

## PHASE2-03: Wear OS Tile — ProtoLayout Glanceable Status

### Description
As a commuter with a Wear OS watch, I want a glanceable Tile that shows my commute status at a glance — without opening the app — so that I can make an instant go/no-go decision while getting ready.

The Wear OS Tile is the equivalent of the Garmin Glance: a system-rendered surface that appears in the watch's tile carousel. Unlike the Main Activity (which uses Compose), Tiles must be built with the ProtoLayout API — they cannot use Compose and cannot scroll. The tile displays the action tier in color, affected MTA route badges, and a reroute hint or summary snippet, then taps through to the Main Activity for full details.

### Acceptance Criteria

1. **Tile Renders with ProtoLayout**
   - A `TileService` subclass provides tile content using `androidx.wear.protolayout`
   - Tile appears in the watch's tile carousel when added by the user
   - Tile uses `SingleSlotLayoutRenderer` or equivalent ProtoLayout layout structure

2. **Title Slot — Action Status in Tier Color**
   - Displays the human-readable action label ("Normal", "Minor Delays", "Reroute", "Stay Home")
   - Text color matches the tier: green (`#4CAF50`) for NORMAL, yellow (`#FFD600`) for MINOR_DELAYS, red (`#F44336`) for REROUTE/STAY_HOME
   - Bold weight for immediate readability

3. **Main Slot — MTA Route Badges**
   - Displays circular color-coded route badges for `affected_routes` (same MTA trunk-line color groups as the phone app's `MtaLineColors`)
   - Each badge is a filled circle with the route letter centered in contrasting text (black on yellow lines, white on all others)
   - Badges are arranged in a horizontal row
   - When `affected_routes` is empty (NORMAL status), this slot is hidden or shows nothing

4. **Bottom Slot — Reroute Hint or Summary**
   - If `reroute_hint` is present and non-empty, display it (prioritized over summary)
   - Otherwise, display `summary`
   - Text is limited with `maxLines` ellipsis to prevent overflow on the small tile surface
   - Text color is tier-colored for reroute hint, white/light gray for summary

5. **Tap Launches Main Activity**
   - Tapping anywhere on the tile opens the Wear OS Main Activity (`com.commutebuddy.wear.MainActivity`)
   - Uses a `Clickable` with `LaunchAction` pointing to the activity

6. **Data Source — StatusStore**
   - Tile reads from `StatusStore` (SharedPreferences) — the same data source as the Main Activity
   - When no data exists yet (`has_data` = false), tile shows a "Waiting for data…" placeholder in gray

7. **Tile Freshness**
   - Tile requests a timeline update via `TileService.getRequester(context).requestUpdate()` whenever `StatusStore` saves new data (triggered from `CommuteStatusListenerService`)
   - Tile includes a reasonable `TimelineBuilders.Timeline` freshness interval so the relative timestamp stays reasonably current

8. **Relative Timestamp**
   - A small timestamp line ("just now", "N min ago", "N hr ago") appears near the action label, using the same logic as the Main Activity's `relativeTime()`

### Out of Scope
- Tile preview/screenshots for the Play Store (PHASE2 hardening)
- Complications (small slot data providers) — future consideration
- Tile settings or configuration — the tile always reflects the latest `StatusStore` data
- Custom tile background images or branding
- Compose for Tiles (Tiles must use ProtoLayout, not Compose)

### Implementation Plan

#### Increment 1: Dependencies + Skeleton TileService
- [ ] Add ProtoLayout and Tiles dependencies to `wear/build.gradle.kts`: `tiles:1.5.0`, `protolayout:1.3.0`, `protolayout-material3:1.3.0`, `protolayout-expression:1.3.0`, Guava (`com.google.guava:guava`) for `Futures.immediateFuture`
- [ ] Create `CommuteTileService.kt` extending `TileService` — return a minimal tile with hardcoded "Commute Buddy" text using `materialScope` + `primaryLayout`
- [ ] Register `CommuteTileService` in `AndroidManifest.xml` with `BIND_TILE_PROVIDER` permission, intent-filter, label, and preview metadata
- [ ] Add a placeholder tile preview drawable (`res/drawable/tile_preview.xml`) — simple solid-color circle with text
- [ ] Add `tile_label` and `tile_description` strings to `res/values/strings.xml`

**Testing:** Build `wear:assembleDebug`. Deploy to Wear OS emulator, add the tile to the carousel, verify it appears and shows the placeholder text.
**Model: Sonnet** | Reason: First use of ProtoLayout M3 DSL in this codebase — needs to get imports, materialScope pattern, and manifest registration correct.

#### Increment 2: Data-Driven Layout — Action Tier + Timestamp + Placeholder
- [ ] In `CommuteTileService.onTileRequest()`, read from `StatusStore.load(this)` to get the current `CommuteStatusSnapshot`
- [ ] When snapshot is null: render "Waiting for data…" placeholder in gray
- [ ] When snapshot exists: `titleSlot` shows action label ("Normal", "Minor Delays", etc.) in tier color (green/yellow/red) with bold weight
- [ ] Add relative timestamp text below the action label (reuse `relativeTime()` logic — extract to a shared utility or duplicate in tile)
- [ ] Set `setFreshnessIntervalMillis(10 * 60 * 1000)` (10 minutes) so the timestamp stays reasonably current

**Testing:** Deploy to emulator. Use `adb shell am broadcast` or send a test status from the phone app. Verify tile shows correct action text in tier color with timestamp. Clear app data and verify placeholder appears.
**Model: Sonnet** | Reason: Integrating StatusStore with ProtoLayout rendering logic, conditional layouts, and color mapping.

#### Increment 3: MTA Route Badges in Main Slot
- [ ] Create `MtaLineColors.kt` in the wear module — a Kotlin object with `lineColor(line: String): Int` and `isLightBackground(line: String): Boolean` (same color mapping as the phone app's `MtaLineColors`)
- [ ] In `CommuteTileService`, parse `affectedRoutes` CSV and render circular route badges in `mainSlot` using ProtoLayout `Box` (circle background fill) + `Text` (centered letter) arranged in a `Row`
- [ ] Each badge uses the MTA trunk-line color as background, with black text for yellow-line badges and white text for all others
- [ ] When `affectedRoutes` is empty, `mainSlot` shows no badges (skip or show a minimal spacer)

**Testing:** Send a status with `affected_routes = "N,W"` from the phone. Verify yellow circular badges with black "N" and "W" letters appear. Test with multiple route groups (e.g., "4,5,N") to verify mixed colors. Test NORMAL with empty routes — no badges shown.
**Model: Sonnet** | Reason: Building custom badge components in ProtoLayout DSL, managing colors and layout geometry.

#### Increment 4: Bottom Slot + Tap Action + Tile Refresh Wiring
- [ ] In `CommuteTileService`, add `bottomSlot`: display `rerouteHint` (tier-colored) if present, else `summary` (light gray), with max 2 lines and ellipsis overflow
- [ ] Make the entire tile tappable: add a `Clickable` with `LaunchAction` targeting `com.commutebuddy.wear.MainActivity`
- [ ] In `CommuteStatusListenerService.onDataChanged()`, call `TileService.getUpdater(this).requestUpdate(CommuteTileService::class.java)` after saving to StatusStore — so the tile refreshes whenever new data arrives
- [ ] Verify the full visual hierarchy: action (title) → badges (main) → hint/summary (bottom) → timestamp — matches the Garmin glance information density

**Testing:** End-to-end: trigger a poll from the phone app (or use Fetch Live), verify the tile updates automatically on the watch. Test each tier: NORMAL (green, no badges, summary only), MINOR_DELAYS (yellow, badges, summary), REROUTE (red, badges, reroute hint in red), STAY_HOME (red, badges, summary). Tap the tile — verify MainActivity opens.
**Model: Sonnet** | Reason: Multi-concern increment — clickable wiring, conditional rendering, cross-component integration (listener → tile refresh).

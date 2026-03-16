# Active Development

## PHASE2-04: Wear OS Main Activity — Polished Detail View

### Description
As a commuter wearing a Wear OS watch, I want to tap into the tile and see the full commute recommendation with all details, so that I can read the complete summary, reroute hint, and affected routes without truncation.

The current MainActivity is a minimal steel-thread placeholder: it only shows the action label and a relative timestamp. The tile already shows summary text and route badges but truncates at 4 lines. The detail view should present the complete information hierarchy — action, route badges, timestamp, reroute hint, and full summary — in a scrollable Compose for Wear OS layout, matching the Garmin detail view's information density without its pagination complexity (Compose's `ScalingLazyColumn` handles scrolling natively).

### Acceptance Criteria

1. **Full information hierarchy**
   - Action label displayed in tier color (green/yellow/red/gray), bold, prominent font size
   - MTA route badges (colored circles with line letter) displayed below the action label when `affected_routes` is non-empty
   - Relative timestamp ("just now", "N min ago", "N hr ago") displayed in gray below the badges
   - Reroute hint displayed in tier color below the timestamp when present (non-null, non-empty)
   - Summary text displayed in light gray below the reroute hint (or below the timestamp if no hint), full text with no truncation

2. **Scrollable layout**
   - Uses `ScalingLazyColumn` (the standard Wear OS scrollable container)
   - All content is reachable by scrolling — no max-lines or ellipsis on any field
   - Long summaries (800+ chars from weekend planned work alerts) scroll smoothly

3. **MTA route badges in Compose**
   - Circular badges using the same `MtaLineColors` color mapping already in the wear module
   - Badges arranged in a row, wrapping naturally for 5+ routes
   - Black text on yellow-group lines (N/Q/R/W), white text on all others

4. **Placeholder state preserved**
   - When no data has been received (`StatusStore.flow` emits null), display "Waiting for data…" in gray, centered — same as current behavior

5. **Reactive updates**
   - Activity observes `StatusStore.flow` via `collectAsState()` and recomposes when new data arrives (existing pattern, must not regress)

6. **Visual consistency with tile**
   - Same tier colors, same tier labels, same `relativeTime()` logic, same badge colors as the tile — reuse or share the existing constants/functions

### Out of Scope
- Tap actions within the detail view (e.g., launching MTA app or phone app)
- Pull-to-refresh or manual fetch from the watch
- Landscape or ambient mode layouts
- Unit tests for Compose UI (no test infrastructure exists in the wear module yet)
- Changes to the tile layout (PHASE2-03 is complete)

### Implementation Plan

#### Increment 1: ScalingLazyColumn with full text hierarchy
- [ ] Replace `Column` with `ScalingLazyColumn` in `WearApp()` composable (`wear/MainActivity.kt`)
- [ ] Add `item` for action label (tier color, bold, existing logic)
- [ ] Add `item` for relative timestamp (gray, existing logic)
- [ ] Add `item` for reroute hint — only rendered when `rerouteHint` is non-null/non-empty, displayed in tier color
- [ ] Add `item` for summary text — full text, no max-lines, light gray
- [ ] Preserve "Waiting for data…" placeholder when snapshot is null (render as centered item in the column)

**Testing:** Build wear APK (`gradle :wear:assembleDebug`). Deploy to Wear OS emulator. Send a test payload from the phone app (Fetch Live). Verify: action label, timestamp, hint (if REROUTE), and full summary all visible. Scroll down on long summaries. Verify placeholder shows when no data exists (clear app data).

**Model: Sonnet** | Reason: Straightforward Compose rewrite but needs to correctly handle ScalingLazyColumn patterns and conditional rendering.

#### Increment 2: Compose MTA route badges
- [ ] Create a `@Composable fun MtaRouteBadge(line: String)` — circular colored badge with centered line letter, using `MtaLineColors.lineColor()` and `MtaLineColors.isLightBackground()`
- [ ] Create a `@Composable fun MtaRouteBadges(affectedRoutes: String)` — parses CSV, renders badges in a `FlowRow` (from `wear.compose.foundation`) for natural wrapping
- [ ] Insert badge row as a `ScalingLazyColumn` item between the action label and the timestamp
- [ ] Only render badges when `affectedRoutes` is non-empty

**Testing:** Build and deploy to emulator. Send payloads with varying route counts: single route (e.g., "N"), typical (e.g., "N,W,4,5"), many routes (e.g., "N,W,Q,R,4,5,6") to verify wrapping. Verify badge colors match tile badges. Verify NORMAL status with empty affected_routes shows no badge row.

**Model: Sonnet** | Reason: FlowRow layout with custom circular composables requires understanding Compose sizing/drawing APIs.

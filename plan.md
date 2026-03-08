# Active Development

## FEAT-11: MTA Line Icon Badges

### Description
As a commuter, I want train lines displayed as colored circular badges (matching official MTA colors) instead of plain text, so that I can quickly identify affected lines at a glance — just like the real MTA signage.

The app already has MTA color definitions in `LinePickerBottomSheet.kt` (9 trunk-line color groups for all 23 subway lines). Currently, line identifiers appear as plain comma-separated text everywhere outside the line picker — "Affected: N,W", "Lines: N, W", "Routes: N,W", "Delays — N,W". Replacing these with small colored circle badges (white letter on colored background, matching official MTA trunk-line colors) would dramatically improve readability and bring the UI closer to what riders expect from transit apps.

### Acceptance Criteria

1. **Android: Shared MTA color + badge utility**
   - A reusable color map from line ID → MTA trunk-line hex color (extracted from `LinePickerBottomSheet`'s existing definitions) is available as a shared utility
   - A helper renders a list of line IDs as inline colored circular `Span`s (or small `Drawable`s) suitable for use in any `TextView`
   - Yellow-background lines (N, Q, R, W) use black text; all others use white text (matching existing LinePickerBottomSheet convention)

2. **Android: Results display uses line badges**
   - The "Affected: N,W" line in `MainActivity` results shows colored badges instead of plain text
   - Badges are legible at the current text size (approximately 20–24dp diameter)

3. **Android: Commute profile activity uses line badges**
   - Leg cards in `CommuteProfileActivity` show colored badges for the leg's lines instead of "Lines: N, W"
   - The alternates row shows colored badges instead of "Alternates: F, R, 7"

4. **Garmin: Detail view shows colored route labels**
   - `DetailPageView` renders each route as a colored filled circle with the line letter drawn on top (using `dc.fillCircle()` + `dc.drawText()`) instead of plain "Routes: N,W" text
   - Colors match the MTA trunk-line scheme (same 9 groups as Android)
   - Badges render correctly for 1–7 route IDs without overflowing the display width

5. **Garmin: Glance shows colored route text**
   - `CommuteBuddyGlanceView` renders individual line letters in their MTA trunk-line color (colored text, no circle background — glance memory is too constrained for filled shapes per character)
   - Falls back to white text if route ID doesn't match a known color
   - Glance memory stays within the ~32KB budget (no new image resources)

6. **No BLE schema changes**
   - `affected_routes` continues to be transmitted as a comma-separated string
   - All color mapping and rendering happens client-side on both platforms

7. **Existing unit tests pass**
   - No changes to `CommuteStatus`, `MtaAlertParser`, or `SystemPromptBuilder` logic — only display-layer changes
   - `LinePickerBottomSheet` continues to work as before (color definitions are shared, not moved)

### Out of Scope
- Changing the BLE payload format
- Adding line icons to the Gemini prompt text or system prompt
- Rendering full MTA "pill" shapes (rounded rectangles) — circles are sufficient
- Express/local line variant colors (e.g., 6 vs 6 Express are the same green)
- Adding line badges to `PollingSettingsActivity` (no lines are shown there)
- Rendering colored badges in the foreground service notification text

### Implementation Plan

#### Increment 1: Android — Extract MTA colors to shared utility + create badge span
- [x] Create `MtaLineColors.kt` — extract `lineColor(line: String): Int` and `isLightBackground(line: String): Boolean` from `LinePickerBottomSheet.kt` into a top-level `object`
- [x] Update `LinePickerBottomSheet.kt` to delegate to `MtaLineColors.lineColor()` and `MtaLineColors.isLightBackground()` (remove duplicated private functions)
- [x] Create `MtaLineBadgeSpan.kt` — a custom `ReplacementSpan` that draws a filled circle with a centered letter (background = `MtaLineColors.lineColor()`, text color = black for yellow lines, white otherwise)
- [x] Add a companion utility function `MtaLineColors.buildRouteBadges(routesCsv: String, textSizePx: Float): SpannableStringBuilder` that splits on `,`, creates one `MtaLineBadgeSpan` per line ID with small gaps between them

**Testing:** Run unit tests (`& $gradle :app:testDebugUnitTest`). Manually verify LinePickerBottomSheet still renders correctly in the commute profile screen (colors, selection stroke, sizing unchanged).

**Model: Sonnet** | Reason: Custom `ReplacementSpan` drawing logic requires understanding Android canvas measurement/draw contracts — not purely mechanical.

#### Increment 2: Android — Use badges in results display and profile activity
- [ ] Update `MainActivity.handlePipelineResult()` and `onTierClicked()`: switch from `buildString` to `SpannableStringBuilder`; replace the `getString(R.string.ai_result_route, parsed.affectedRoutes)` line with inline badge spans via `MtaLineColors.buildRouteBadges()`
- [ ] Update `CommuteProfileActivity.updateLinesSummary()`: set the `linesSummaryText` TextView with a `SpannableStringBuilder` containing "Lines: " + badge spans (instead of plain `"Lines: N, W"`)
- [ ] Update `CommuteProfileActivity.updateAlternatesSummary()`: same approach for `alternatesSummaryText` — "Alternates: " + badge spans

**Testing:** Build and deploy to device. Verify: (1) Fetch Live results show colored circle badges for affected routes, (2) Commute profile leg cards show badges instead of plain text for lines, (3) Alternates row shows badges, (4) BLE status appended text still renders correctly below the badges.

**Model: Composer** | Reason: Mechanical wiring — calling the utility created in increment 1 across three display sites. Pattern is identical each time.

#### Increment 3: Garmin — MTA color module + detail view route badges
- [ ] Create `MtaColors.mc` — a Monkey C module with `function getLineColor(line as String) as Number` returning the MTA trunk-line color (same 9 groups), defaulting to `COLOR_WHITE` for unknown lines
- [ ] Update `DetailPageView.mc`: replace the `dc.drawText(cx, y, FONT_SMALL, "Routes: " + routesStr, ...)` block with a loop that splits `routesStr` on `,`, draws a filled circle per route (using `dc.fillCircle()`) with the line letter centered on top (`dc.drawText()`), spaced horizontally and centered on screen
- [ ] Update `DetailPageFactory.mc` header height calculation to use the badge row height instead of `FONT_SMALL` height for the routes row

**Testing:** Build in VS Code (`Ctrl+Shift+B`), run in Connect IQ Simulator. Send a test payload with `affected_routes = "N,W"` and `"4,5,6"` — verify colored circle badges render centered, no overflow, correct MTA colors.

**Model: Sonnet** | Reason: Monkey C canvas positioning math (horizontal centering of variable-count badges) needs careful reasoning. Also need to verify `dc.fillCircle()` and text measurement APIs against SDK docs.

#### Increment 4: Garmin — Glance colored route text
- [ ] Update `CommuteBuddyGlanceView.mc`: for MINOR_DELAYS and REROUTE, instead of drawing one `drawText` with "Delays — N,W", draw the action label in its color, then each route letter individually in its MTA color (using `MtaColors.getLineColor()`), with `,` separators in white — calculate positions using `dc.getTextDimensions()` for proper centering
- [ ] NORMAL and STAY_HOME states remain unchanged (no route letters to colorize)

**Testing:** Build in VS Code (`Ctrl+Shift+B`), run in Connect IQ Simulator. Verify glance renders: (1) "Normal" — green, no routes, (2) "Delays — " yellow then route letters in MTA colors, (3) "Reroute — " red then route letters in MTA colors, (4) "Stay Home" — gray. Check that glance memory usage stays within budget (no new image resources, just a few extra `drawText` calls).

**Model: Sonnet** | Reason: Horizontal text centering with multi-color segments requires measuring each piece and calculating offsets — non-trivial positioning logic.

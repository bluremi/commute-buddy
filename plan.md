# Active Development

## BUG-01: Rebuild Garmin full-screen detail UX using native page navigation

### Description
As a commuter checking my watch before leaving, I want the full-screen Garmin detail view to show all status text clearly and navigate smoothly, so that I can trust the recommendation quickly without fighting the UI.

The current detail view uses manual swipe-based pixel offset and redraw logic in a single custom `WatchUi.View`. This causes two user-visible issues: wrapped summary text can still be clipped/truncated for long responses, and scrolling feels choppy because interaction is emulated rather than using native view/page navigation behavior. This bug replaces manual scrolling with native page navigation patterns and deterministic text pagination suitable for Connect IQ.

### Acceptance Criteria

1. **No summary truncation**
   - Long `cs_summary` values are fully readable across one or more pages.
   - No text is rendered outside visible page bounds.
   - Summary pagination is deterministic and stable for repeated renders of the same content.

2. **Native-feeling navigation**
   - Full-screen detail navigation uses Connect IQ native view/page handling (`ViewLoop`-based flow or equivalent native page transitions), not manual Y-offset scrolling.
   - Navigation behavior supports both touch (swipes) and button devices through framework delegates.
   - Page transitions are visually smooth and consistent with platform behavior.

3. **Existing commute data contract preserved**
   - Existing storage keys remain unchanged: `cs_action`, `cs_summary`, `cs_affected_routes`, `cs_reroute_hint`, `cs_timestamp`.
   - Existing action-to-color semantics remain unchanged:
     - `NORMAL` -> green
     - `MINOR_DELAYS` -> yellow
     - `REROUTE` -> red
     - `STAY_HOME` -> light gray
   - Existing conditional fields remain unchanged:
     - Routes omitted for `NORMAL` when empty
     - Reroute hint only shown for `REROUTE` when present

4. **Clear empty and fallback behavior**
   - If `cs_action` is missing/invalid, detail UI shows a clear waiting state.
   - Invalid optional fields do not crash rendering.

5. **Codebase guidance for lower-cost models**
   - A project doc exists under `docs/garmin/` that explicitly defines approved APIs, disallowed patterns, and implementation guardrails for BUG-01.
   - The doc includes a concrete testing checklist that can be executed in simulator/hardware validation.

### Out of Scope
- Redesigning glance UI (`CommuteBuddyGlanceView.mc`)
- Changing BLE payload schema or Android message format
- New app settings or user-configurable typography
- Broad visual redesign beyond fixing truncation and navigation quality

---

## Implementation Plan

> Follow **one increment at a time**. After each increment: implement, run relevant checks, commit, stop, and wait for explicit test confirmation.

## Review Gate (Run Before Marking Any Increment Done)

- [ ] Scope check: only files relevant to the current increment were changed.
- [ ] Contract check: no changes to storage keys (`cs_action`, `cs_summary`, `cs_affected_routes`, `cs_reroute_hint`, `cs_timestamp`) unless the increment explicitly requires it.
- [ ] API check: implementation uses approved APIs from `docs/garmin/widget-detail-view-best-practices.md`; no disallowed manual scroll-offset pattern.
- [ ] UX check (simulator): no text clipping in exercised test cases for this increment.
- [ ] Regression check: glance behavior and BLE schema remain unchanged.
- [ ] Handoff check: commit message clearly states increment number and what was validated.

### Increment 1: Add Garmin UI guardrails doc for BUG-01
**Recommended model:** Composer
- [x] Create `docs/garmin/widget-detail-view-best-practices.md` containing:
  - Problem statement for BUG-01
  - Approved APIs and patterns:
    - `WatchUi.ViewLoop`, `WatchUi.ViewLoopFactory`, `WatchUi.ViewLoopDelegate`
    - `WatchUi.pushView()` / `WatchUi.switchToView()` with `WatchUi.SLIDE_*` transitions
    - `WatchUi.TextArea`
    - `Graphics.fitTextToArea()`
  - Explicitly disallowed pattern:
    - Manual `_scrollOffset` style scrolling in a single long canvas
  - Data contract and visual contract to preserve
  - Validation checklist
- [x] Update doc wording to make it executable by lower-cost models without extra interpretation.

### Increment 2: Introduce page model + text pagination helper
**Recommended model:** Composer
- [x] Add a helper module/class for detail pagination (new file under `garmin/source/`).
- [x] Implement a deterministic summary chunking strategy that yields page-sized text chunks.
- [x] Ensure pagination logic:
  - avoids mid-word clipping where practical
  - handles empty/short/very-long summary text
  - supports constrained page body height
- [x] Add/adjust unit-like verification strategy if practical in this repo; otherwise document simulator test vectors in the new doc.

### Increment 3: Replace manual scroll view with native paged architecture
**Recommended model:** Composer (use Sonnet only if blocked on `ViewLoopFactory` wiring)
- [ ] Refactor full-screen detail from one manually scrolled view to a page-based design:
  - either page-specific `WatchUi.View` instances managed by `ViewLoopFactory`, or equivalent native paging architecture
  - no manual Y-offset scrolling
- [ ] Use `WatchUi.ViewLoop` + `WatchUi.ViewLoopDelegate` for next/previous page behavior.
- [ ] Keep message storage reading and mapping behavior intact.

### Increment 4: Wire app entry to new navigation stack
**Recommended model:** Composer
- [ ] Update `CommuteBuddyApp.getInitialView()` to return the new initial page/navigation delegate arrangement.
- [ ] Remove obsolete manual-scroll delegate plumbing (`CommuteBuddyDelegate` usage) if no longer needed.
- [ ] Preserve back/exit behavior expectations for widgets (do not trap user in non-dismissible loops).

### Increment 5: Visual parity + regression hardening
**Recommended model:** Composer
- [ ] Ensure visual parity for:
  - action color and labels
  - routes and reroute hint display rules
  - freshness text behavior
- [ ] Confirm no regressions in waiting/fallback states.
- [ ] Confirm behavior in simulator with representative payload sizes:
  - short summary
  - medium summary
  - very long summary + reroute hint

### Increment 6: Story completion updates
**Recommended model:** Composer
- [ ] Update `prd.md`:
  - mark BUG-01 complete
  - update Key Features/Technical Architecture entries to reflect the new native paged Garmin detail UI
- [ ] Clear `plan.md` after story is fully complete.

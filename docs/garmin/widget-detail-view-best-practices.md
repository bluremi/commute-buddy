# BUG-01 Garmin Detail View Best Practices

This document is the implementation contract for `BUG-01` in `prd.md`.

## Goal

Fix two UX defects in the Garmin full-screen detail view:

1. Long summary text is truncated/clipped.
2. Scrolling feels choppy because it is manually emulated.

The fix should be safe for lower-cost coding models to implement if they follow this doc exactly.

## Current Problem (What to Replace)

The existing full-screen detail UI uses a custom `WatchUi.View` with manual swipe handling and a `_scrollOffset` redraw model.

This pattern is fragile because:
- it draws one long virtual canvas and shifts Y offsets manually
- text fitting and page bounds are hard to guarantee for all message lengths
- interaction is not using native page/view navigation behavior

## Approved APIs and Patterns

Use only these UI/navigation patterns for BUG-01:

- `WatchUi.ViewLoop`
- `WatchUi.ViewLoopFactory`
- `WatchUi.ViewLoopDelegate`
- `WatchUi.pushView()` / `WatchUi.switchToView()` with `WatchUi.SLIDE_*` when needed
- `WatchUi.TextArea`
- `Graphics.fitTextToArea()`

Notes:
- `ViewLoop` provides native page transitions and system-level next/previous behavior.
- `TextArea` performs line wrapping inside defined bounds.
- `fitTextToArea()` can be used to constrain text per page body area.

## Disallowed Patterns

Do **not** do any of the following:

- Manual `_scrollOffset` style scrolling in a single `WatchUi.View` for long content
- Custom "fake scroll" by repeatedly shifting draw origin and calling `requestUpdate()`
- Inventing non-existent APIs such as `ScrollableView` or `dc.drawWrappedText()`

## Data Contract (Must Not Change)

Read from existing storage keys only:

- `cs_action`
- `cs_summary`
- `cs_affected_routes`
- `cs_reroute_hint`
- `cs_timestamp`

Do not rename keys and do not change BLE schema for this bug.

## Visual Contract (Must Not Regress)

Action mapping:
- `NORMAL` -> green -> "Normal"
- `MINOR_DELAYS` -> yellow -> "Minor Delays" or equivalent existing label
- `REROUTE` -> red -> "Reroute"
- `STAY_HOME` -> light gray -> "Stay Home"

Display rules:
- Show routes except when action is `NORMAL` and routes are empty.
- Show reroute hint only for `REROUTE` and when hint exists.
- Keep freshness display behavior ("<1 min ago", "N min ago", "N hr ago", "Stale").
- If no valid action exists, show a clear waiting state.

## Recommended Architecture

Implement detail view as page-based content:

1. Build a `DetailPageModel` list from storage content.
2. Split long summary into page-sized chunks.
3. Create page views from that model.
4. Expose views through a `ViewLoopFactory`.
5. Return a `ViewLoop` + `ViewLoopDelegate` for the detail experience.

This removes manual scrolling and lets native next/previous behavior drive navigation.

## Pagination Guidance

When summary text exceeds one page:

- Determine page body height budget after header/action metadata.
- Fit text chunk to that area (`fitTextToArea()` recommended).
- Carry overflow into next summary page(s).
- Ensure every page renders within bounds.

Good behavior:
- deterministic chunking for same input
- no mid-character clipping
- no hidden text below page bottom

## File-Level Guidance

Current BUG-01 architecture (post-increment 4):

- `garmin/source/CommuteBuddyApp.mc` — `getInitialView()` returns `[ViewLoop, ViewLoopDelegate]` with `DetailPageFactory`
- `garmin/source/DetailPageFactory.mc` — `ViewLoopFactory` that builds page model from storage and `DetailPagination`
- `garmin/source/DetailPageView.mc` — single-page view for header + summary chunk
- `garmin/source/DetailPageDelegate.mc` — minimal `BehaviorDelegate` for page views
- `garmin/source/DetailPagination.mc` — deterministic summary chunking helper

Obsolete (removed): `CommuteBuddyView.mc`, `CommuteBuddyDelegate.mc` (manual-scroll pattern)

## Pagination Test Vectors (DetailPagination.chunkSummary)

Use these in simulator to validate `DetailPagination.chunkSummary()` behavior. Call with `Graphics.FONT_SMALL`, width 310, height ~200 (page body after header).

| Case | Input | Expected |
|------|-------|----------|
| Empty | `null` or `""` | `[]` (empty array) |
| Short | `"All clear."` | `["All clear."]` (single chunk) |
| Medium | ~80 chars, fits one page | `[full text]` (single chunk) |
| Long | 300+ chars, exceeds one page | 2+ chunks; no truncation; same input → same chunks (deterministic) |
| Word boundaries | `"Word1 word2 word3 ..."` with break between words | No mid-word splits |
| Very long word | Single token longer than page height | Chunk at char boundary (fallback); no infinite loop |

## Testing Checklist

Run in simulator (`venu3_sim`) and validate:

1. **No truncation**
   - short summary
   - medium summary
   - very long summary (>2 pages)
2. **Navigation quality**
   - touch swipes move between pages naturally
   - button next/previous behavior works where applicable
3. **Conditional sections**
   - routes hidden for `NORMAL` with empty routes
   - reroute hint only shown for `REROUTE`
4. **Fallback**
   - no `cs_action` -> waiting screen shown
5. **No regression**
   - glance behavior unchanged
   - BLE schema unchanged

## Simulator Test Payloads (Increment 5)

Send these from the Android app via BLE/Connect IQ to exercise representative cases. Payload format: `{action, summary, affected_routes, reroute_hint?, timestamp}`.

| Case | action | summary | affected_routes | reroute_hint | Expected |
|------|--------|--------|-----------------|--------------|----------|
| **Short** | `NORMAL` | `"All clear."` | `""` | omit | 1 page, green "Normal", no routes |
| **Medium** | `MINOR_DELAYS` | `"N/W delays Manhattan-bound due to earlier brake activation at Queensboro Plaza."` | `"N,W"` | omit | 1–2 pages, yellow "Minor Delays", routes shown |
| **Very long + hint** | `REROUTE` | 300+ char summary (e.g. repeat a sentence) | `"N,W,Q"` | `"Use 7 train to 74 St, then N to Queensboro."` | 2+ summary pages, red "Reroute", hint in header, no clipping |
| **Waiting** | (no message) | — | — | — | "Waiting for update..." centered |
| **NORMAL empty routes** | `NORMAL` | `"On time."` | `""` | omit | Routes line omitted |

## Quick Prompt Template For Lower-Cost Models

Use this when delegating implementation:

> Implement BUG-01 by replacing manual `_scrollOffset` scrolling in Garmin detail UI with native page navigation using `WatchUi.ViewLoop`, `WatchUi.ViewLoopFactory`, and `WatchUi.ViewLoopDelegate`. Keep storage keys/schema unchanged (`cs_action`, `cs_summary`, `cs_affected_routes`, `cs_reroute_hint`, `cs_timestamp`). Preserve existing action colors and conditional display rules. Add deterministic summary pagination so long text is fully readable across pages with no clipping. Do not use custom scroll-offset redraw logic and do not use non-existent APIs like `ScrollableView` or `dc.drawWrappedText()`. Follow `docs/garmin/widget-detail-view-best-practices.md`.

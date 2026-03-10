# Active Development

## FEAT-13: Garmin Detail View UX Revamp

### Description
As a commuter, I want to immediately see actionable reroute instructions on my watch without them being cut off or mixed with background details, so I can make quick decisions while in motion. 

Currently, critical reroute hints are truncated and blend into the longer status summary. This feature introduces a clear visual hierarchy, separating the primary directive (the "hint") from the context (the "summary") with distinct color styling and smart pagination that guarantees no text is ever lost.

### Acceptance Criteria

1. **Layout Hierarchy**
   - The detail view displays information in the following vertical order: Status Title, Route Badges, Freshness Timestamp, Reroute Hint (if applicable), and Summary Text.

2. **Reroute Hint Visibility**
   - When a reroute hint exists, it is displayed in its entirety on the first screen.
   - It is styled in a color corresponding to the current action tier to stand out against the background.

3. **Color-Based Visual Separation**
   - The reroute hint uses the action tier color (red for REROUTE) while the summary text uses a neutral color (white), providing clear visual distinction without a separator line.

4. **Summary Pagination**
   - The summary text begins below the reroute hint (or below the timestamp when no hint exists) in a neutral color (white).
   - If the summary text exceeds the remaining screen space on the first page, it cleanly paginates across subsequent screens without cutting off words mid-sentence.

5. **Dynamic Adaptation**
   - If the current status does not include a reroute hint (e.g., Normal service), the hint section is entirely omitted.
   - The summary text shifts up to utilize the freed vertical space.

6. **Timestamp Placement**
   - The freshness timestamp is consistently visible on the first screen, positioned immediately below the route badges.
   - It utilizes a smaller font size to conserve screen real estate.

### Out of Scope
- Changes to the minimal glance view (the widget view before opening the full app).
- Modifying the AI decision prompt or adding new data fields to the Bluetooth transmission payload.
- Custom fonts or complex graphical animations.

### Implementation Plan

#### Increment 1: Layout reorder + hint styling + timestamp font
- [x] **`DetailPageView.mc`**: Move freshness timestamp rendering **above** the reroute hint block
- [x] **`DetailPageView.mc`**: Change timestamp font from `FONT_TINY` to `FONT_XTINY`
- [x] **`DetailPageView.mc`**: Change hint `TextArea` `:color` from `Graphics.COLOR_WHITE` to the header's `actionColor`
- [x] **`DetailPageFactory.mc`**: Reorder `headerHeight` calculation to match (timestamp before hint)
- [x] **`DetailPageFactory.mc`**: Change timestamp font measurement from `FONT_TINY` to `FONT_XTINY`

**Testing:** Build for simulator (`Ctrl+Shift+B`). Send test payloads via BLE:
- REROUTE with hint → Title → Badges → Timestamp → Hint (in red) → Summary
- MINOR_DELAYS without hint → timestamp appears, no hint
- NORMAL with empty routes → Title → Timestamp → Summary

**Model: Composer** | Reason: Mechanical reordering of existing blocks and single-property changes.

#### Increment 2: Dynamic hint height measurement
- [ ] **`DetailPageFactory.mc`**: Measure actual hint pixel height using `Graphics.fitTextToArea()` to get the fitted string, then estimate rendered height (line count × font height or similar). Store as `"hintHeight"` in `headerDict`. Replace hardcoded `80 + 14` with `measuredHintHeight + pad`
- [ ] **`DetailPageView.mc`**: Read `"hintHeight"` from header dict; use it as `:height` for the hint `TextArea` instead of hardcoded `80`
- [ ] **`DetailPageFactory.mc`**: When hint is absent, no hint height added — `bodyHeightPage1` uses the full freed space (AC #5)

**Testing:** Build for simulator. Send test payloads:
- REROUTE with short hint ("Take the 7 train.") → hint fits tightly, more room for summary
- REROUTE with max-length hint (60 chars) → hint wraps correctly, no truncation
- REROUTE with long summary (300+ chars) + hint → summary paginates correctly, no text lost
- NORMAL / MINOR_DELAYS (no hint) → no hint space reserved, summary fills available area

**Model: Sonnet** | Reason: Dynamic height measurement with `fitTextToArea` and coordinated math across factory and view.
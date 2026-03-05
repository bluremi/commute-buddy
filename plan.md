# Active Development

## FEAT-03: MTA GTFS-RT Alert Fetching & Preprocessing Pipeline

### Description
As a commuter, I want the Android app to fetch live MTA subway alerts, filter them to my routes, and summarize them via Gemini, so that I get real commute status instead of hardcoded test data.

The preprocessing pipeline replaces the FEAT-02 hardcoded test data with live data: fetch the MTA GTFS-RT subway alerts feed, parse the JSON response, filter to the user's commute routes (N, W, 4, 5, 6 — hardcoded initially), extract the English plain-text alert bodies, and pass them to Gemini 2.5 Flash for summarization. The result is displayed on the Android UI. The FEAT-02 tier buttons remain for debugging; a new "Fetch Live" button triggers the real pipeline.

### Acceptance Criteria

1. **Live MTA Feed Fetch**
   - App makes an HTTP GET to the MTA subway alerts JSON endpoint (`subway-alerts.json`)
   - Fetch runs on a background coroutine (not the main thread)
   - Network errors produce a clear user-facing message (not a crash)

2. **Alert Parsing**
   - Parses the GTFS-RT JSON response structure (`entity[] → alert`)
   - Extracts `header_text` and `description_text` (when present) using the `language: "en"` plain-text translation (not `en-html`)
   - Handles missing `description_text` gracefully (some alerts are header-only)

3. **Route Filtering**
   - Filters alerts to only those whose `informed_entity[].route_id` matches the user's hardcoded routes: N, W, 4, 5, 6
   - Routes are defined as a constant list — easy to change later but not yet user-configurable (FEAT-08)
   - If no alerts match the user's routes, displays a "Good Service" / no-alerts message instead of calling Gemini

4. **Text Extraction & Concatenation**
   - Concatenates filtered alert text (header + description per alert) into a single string for Gemini
   - Each alert's text is clearly delimited so Gemini can distinguish between separate alerts

5. **Gemini Summarization of Live Data**
   - Passes the concatenated filtered alert text to Gemini 2.5 Flash using the existing system prompt and model
   - Uses the existing `ApiRateLimiter` for all live API calls
   - Displays the parsed `CommuteStatus` result (status, route, reason, timestamp) — same format as FEAT-02

6. **UI Integration**
   - A new "Fetch Live" button triggers the full pipeline (fetch → filter → summarize → display)
   - Results display in the existing results area
   - Button is disabled while a fetch/summarize is in progress
   - FEAT-02 tier buttons remain for debugging/comparison

7. **Error Handling**
   - MTA feed unavailable → clear network error message
   - MTA feed returns unexpected structure → parse error without crashing
   - Gemini errors handled by existing `classifyApiError()` logic
   - Empty feed (no alerts at all) → "No active alerts" message

### Out of Scope
- Route selection UI (FEAT-08)
- Commute direction filtering (FEAT-08)
- Automatic/scheduled polling (FEAT-06)
- BLE push to watch (FEAT-04)
- Protobuf format — the `.json` variant provides identical data with simpler parsing; protobuf can be adopted later if performance requires it
- Caching or persistence of fetched alerts
- Deduplication across multiple fetches

---

### Implementation Plan

#### Increment 1: MtaAlertParser — JSON parsing, route filtering, text extraction
- [x] Create `MtaAlertParser.kt` in `android/app/src/main/kotlin/com/commutebuddy/app/`
- [x] Define `data class MtaAlert(headerText: String, descriptionText: String?, routeIds: Set<String>, alertType: String?)`
- [x] Define `val MONITORED_ROUTES = setOf("N", "W", "4", "5", "6")` constant
- [x] Implement `fun parseAlerts(jsonString: String): List<MtaAlert>` — iterate `entity[]`, extract `en` translation from `header_text.translation[]` and `description_text.translation[]` (skip `en-html`), collect `route_id` from `informed_entity[]` (skip stop-only entries), grab `alert_type` from `transit_realtime.mercury_alert`; skip unparseable entities rather than crashing
- [x] Implement `fun filterByRoutes(alerts: List<MtaAlert>, routes: Set<String>): List<MtaAlert>` — keep alerts where any `routeId` intersects the given routes
- [x] Implement `fun buildPromptText(alerts: List<MtaAlert>): String` — concatenate alerts with clear delimiters (e.g. `--- Alert (Delays) ---\nheaderText\ndescriptionText`), separate alerts with blank lines
- [x] Create `MtaAlertParserTest.kt` in `android/app/src/test/kotlin/com/commutebuddy/app/` with JSON fixture constants modeled on real MTA feed structure
- [x] Write parsing tests: header-only alert (no `description_text`), header+description, extracts `en` and ignores `en-html`, collects all `route_id`s from multi-route `informed_entity`, skips `informed_entity` entries with only `stop_id`, extracts `alert_type` from mercury extension, empty `entity[]` returns empty list, malformed JSON returns empty list (no exception)
- [x] Write filtering tests: matching route kept, non-matching route removed, alert with routes [N, Q] kept when filtering for {N, W} (partial match)
- [x] Write `buildPromptText` tests: single alert output, multiple alerts separated by delimiters, null description omitted (no blank line)

**Testing:** Run `gradle :app:testDebugUnitTest`. All new `MtaAlertParserTest` tests pass, all existing `ApiRateLimiterTest` tests still pass.
**Model: Sonnet** | Reason: New parsing logic against a specific JSON structure with edge cases; needs to reason about the nesting and handle missing fields correctly.

---

#### Increment 2: MtaAlertFetcher + string resources
- [x] Create `MtaAlertFetcher.kt` in `android/app/src/main/kotlin/com/commutebuddy/app/`
- [x] Define `const val MTA_SUBWAY_ALERTS_URL = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json"`
- [x] Implement `suspend fun fetchAlerts(): Result<String>` — `withContext(Dispatchers.IO)`, `HttpURLConnection`, connect timeout 10s, read timeout 15s, read response body, wrap `IOException`/other exceptions in `Result.failure()`
- [x] Add string resources to `strings.xml`: `live_section_title` ("Live MTA Alerts"), `live_fetch_button` ("Fetch Live"), `live_fetching` ("Fetching MTA alerts…"), `live_parsing` ("Parsing alerts…"), `live_no_alerts` ("Good Service — no active alerts for %s"), `live_summarizing` ("Summarizing with Gemini…"), `live_output_prefix` ("LIVE OUTPUT:"), `live_fetch_error` ("MTA feed error — check your connection"), `live_parse_error` ("Failed to parse MTA feed: %s")
- [x] Confirm `INTERNET` permission already in `AndroidManifest.xml` (no change needed)

**Testing:** Run `gradle :app:testDebugUnitTest` (no regressions). Run `gradle :app:assembleDebug` — build succeeds with no errors.
**Model: Sonnet** | Reason: Simple HTTP wrapper but uses coroutines and `Result` — Sonnet handles the suspend/Dispatchers pattern reliably.

---

#### Increment 3: UI integration — Fetch Live button + end-to-end pipeline
- [x] Add "Live MTA Alerts" section to `activity_main.xml` between the BLE section and the AI POC section: section title `TextView`, full-width "Fetch Live" `Button`, visual separator `View`
- [x] Bind the new button in `MainActivity.onCreate()`, wire `setOnClickListener` to `onFetchLiveClicked()`
- [x] Implement `onFetchLiveClicked()` in `MainActivity.kt`:
  1. Disable Fetch Live button + tier buttons, show "Fetching MTA alerts…" in results
  2. `MtaAlertFetcher.fetchAlerts()` → on failure: show `live_fetch_error`, re-enable buttons, return
  3. `MtaAlertParser.parseAlerts()` then `filterByRoutes(alerts, MONITORED_ROUTES)` → if empty: show "Good Service — no active alerts for N, W, 4, 5, 6" (skip Gemini call), re-enable buttons, return
  4. `buildPromptText(filtered)` → `rateLimiter.tryAcquire()` → on denied: show reason, re-enable, return → `generativeModel.generateContent()` → `CommuteStatus.fromJson()` → display with `LIVE OUTPUT:` prefix (same format as FEAT-02)
  5. Reuse `classifyApiError()` for Gemini exceptions; `finally` block re-enables all buttons
- [x] Ensure tier buttons are disabled during live fetch and Fetch Live is disabled during tier tests (prevent concurrent API calls)

**Testing:** Run `gradle :app:testDebugUnitTest` (no regressions). Manual test on device:
- With active alerts on user's routes → shows Gemini-summarized status with LIVE OUTPUT prefix
- With no alerts on user's routes → shows "Good Service" without calling Gemini
- With airplane mode on → shows network error message
- Tap button while pipeline is running → button is disabled, no double-fetch
**Model: Sonnet** | Reason: Wiring async pipeline across fetcher → parser → rate limiter → Gemini → UI with multiple error-handling branches; needs to coordinate state across existing patterns in MainActivity.

---

#### Increment 4: active_period filtering — exclude alerts not currently active

**Problem discovered during increment 3 testing:** The MTA feed contains standing advisories (e.g., "W train — No Scheduled Service — overnight/weekends") with `active_period` windows that specify exactly when each alert applies. The parser ignores `active_period` entirely, so these advisories are passed to Gemini even when the current time is outside all their windows — causing false positives like "Route W: Disrupted" during normal service hours.

Per the GTFS-RT spec, an alert with **no** `active_period` entries is considered always active; an alert with periods present is only active if the current time falls within at least one of them. Each period has a `start` and `end` Unix timestamp; `end == 0` (or absent) means open-ended.

- [ ] Add `data class ActivePeriod(val start: Long, val end: Long)` to `MtaAlertParser.kt` (`end == 0L` = open-ended)
- [ ] Add `val activePeriods: List<ActivePeriod> = emptyList()` to `MtaAlert` (default value keeps all existing direct `MtaAlert(...)` constructions in tests valid)
- [ ] In `parseEntity()`, parse `active_period[]`: iterate the array, extract `start` (default `0L`) and `end` (default `0L`) from each entry, build `List<ActivePeriod>` and assign to the new field
- [ ] Implement `fun filterByActivePeriod(alerts: List<MtaAlert>, nowSeconds: Long): List<MtaAlert>` in `MtaAlertParser`:
  - Alert with empty `activePeriods` → always included (GTFS-RT spec)
  - Alert is active if **any** period satisfies: `(start == 0L || nowSeconds >= start) && (end == 0L || nowSeconds <= end)`
- [ ] In `onFetchLiveClicked()` in `MainActivity.kt`, call `MtaAlertParser.filterByActivePeriod(filtered, System.currentTimeMillis() / 1000)` immediately after `filterByRoutes()`, re-assign result to `filtered`; update the "no alerts" check to use the post-period-filter list
- [ ] Add `filterByActivePeriod` tests to `MtaAlertParserTest.kt` (all tests construct `MtaAlert` with the new `activePeriods` field explicitly set):
  - Empty `activePeriods` → alert included (no active_period = always active)
  - Single period, `now` falls within it → included
  - Single period, already ended (`end < now`) → excluded
  - Single period, not yet started (`start > now`) → excluded
  - Multiple periods, none currently active → excluded
  - Multiple periods, one currently active → included
  - Open-ended period (`end == 0L`), already started → included
  - Open-ended period (`end == 0L`), not yet started → excluded
  - Boundary: `now == start` exactly → included (inclusive)
  - Boundary: `now == end` exactly → included (inclusive)

**Testing:** Run `gradle :app:testDebugUnitTest` — all new `filterByActivePeriod` tests pass, all existing tests still pass. Manual smoke test on device: tap Fetch Live and confirm the W train "No Scheduled Service" standing advisory no longer appears as a result during normal service hours.
**Model: Sonnet** | Reason: Precise boundary logic with multiple edge cases across the data class, parser, filter, and UI wiring — the same pattern as increment 1.

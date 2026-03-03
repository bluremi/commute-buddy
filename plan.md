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
- [ ] Create `MtaAlertParser.kt` in `android/app/src/main/kotlin/com/commutebuddy/app/`
- [ ] Define `data class MtaAlert(headerText: String, descriptionText: String?, routeIds: Set<String>, alertType: String?)`
- [ ] Define `val MONITORED_ROUTES = setOf("N", "W", "4", "5", "6")` constant
- [ ] Implement `fun parseAlerts(jsonString: String): List<MtaAlert>` — iterate `entity[]`, extract `en` translation from `header_text.translation[]` and `description_text.translation[]` (skip `en-html`), collect `route_id` from `informed_entity[]` (skip stop-only entries), grab `alert_type` from `transit_realtime.mercury_alert`; skip unparseable entities rather than crashing
- [ ] Implement `fun filterByRoutes(alerts: List<MtaAlert>, routes: Set<String>): List<MtaAlert>` — keep alerts where any `routeId` intersects the given routes
- [ ] Implement `fun buildPromptText(alerts: List<MtaAlert>): String` — concatenate alerts with clear delimiters (e.g. `--- Alert (Delays) ---\nheaderText\ndescriptionText`), separate alerts with blank lines
- [ ] Create `MtaAlertParserTest.kt` in `android/app/src/test/kotlin/com/commutebuddy/app/` with JSON fixture constants modeled on real MTA feed structure
- [ ] Write parsing tests: header-only alert (no `description_text`), header+description, extracts `en` and ignores `en-html`, collects all `route_id`s from multi-route `informed_entity`, skips `informed_entity` entries with only `stop_id`, extracts `alert_type` from mercury extension, empty `entity[]` returns empty list, malformed JSON returns empty list (no exception)
- [ ] Write filtering tests: matching route kept, non-matching route removed, alert with routes [N, Q] kept when filtering for {N, W} (partial match)
- [ ] Write `buildPromptText` tests: single alert output, multiple alerts separated by delimiters, null description omitted (no blank line)

**Testing:** Run `gradle :app:testDebugUnitTest`. All new `MtaAlertParserTest` tests pass, all existing `ApiRateLimiterTest` tests still pass.
**Model: Sonnet** | Reason: New parsing logic against a specific JSON structure with edge cases; needs to reason about the nesting and handle missing fields correctly.

---

#### Increment 2: MtaAlertFetcher + string resources
- [ ] Create `MtaAlertFetcher.kt` in `android/app/src/main/kotlin/com/commutebuddy/app/`
- [ ] Define `const val MTA_SUBWAY_ALERTS_URL = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json"`
- [ ] Implement `suspend fun fetchAlerts(): Result<String>` — `withContext(Dispatchers.IO)`, `HttpURLConnection`, connect timeout 10s, read timeout 15s, read response body, wrap `IOException`/other exceptions in `Result.failure()`
- [ ] Add string resources to `strings.xml`: `live_section_title` ("Live MTA Alerts"), `live_fetch_button` ("Fetch Live"), `live_fetching` ("Fetching MTA alerts…"), `live_parsing` ("Parsing alerts…"), `live_no_alerts` ("Good Service — no active alerts for %s"), `live_summarizing` ("Summarizing with Gemini…"), `live_output_prefix` ("LIVE OUTPUT:"), `live_fetch_error` ("MTA feed error — check your connection"), `live_parse_error` ("Failed to parse MTA feed: %s")
- [ ] Confirm `INTERNET` permission already in `AndroidManifest.xml` (no change needed)

**Testing:** Run `gradle :app:testDebugUnitTest` (no regressions). Run `gradle :app:assembleDebug` — build succeeds with no errors.
**Model: Sonnet** | Reason: Simple HTTP wrapper but uses coroutines and `Result` — Sonnet handles the suspend/Dispatchers pattern reliably.

---

#### Increment 3: UI integration — Fetch Live button + end-to-end pipeline
- [ ] Add "Live MTA Alerts" section to `activity_main.xml` between the BLE section and the AI POC section: section title `TextView`, full-width "Fetch Live" `Button`, visual separator `View`
- [ ] Bind the new button in `MainActivity.onCreate()`, wire `setOnClickListener` to `onFetchLiveClicked()`
- [ ] Implement `onFetchLiveClicked()` in `MainActivity.kt`:
  1. Disable Fetch Live button + tier buttons, show "Fetching MTA alerts…" in results
  2. `MtaAlertFetcher.fetchAlerts()` → on failure: show `live_fetch_error`, re-enable buttons, return
  3. `MtaAlertParser.parseAlerts()` then `filterByRoutes(alerts, MONITORED_ROUTES)` → if empty: show "Good Service — no active alerts for N, W, 4, 5, 6" (skip Gemini call), re-enable buttons, return
  4. `buildPromptText(filtered)` → `rateLimiter.tryAcquire()` → on denied: show reason, re-enable, return → `generativeModel.generateContent()` → `CommuteStatus.fromJson()` → display with `LIVE OUTPUT:` prefix (same format as FEAT-02)
  5. Reuse `classifyApiError()` for Gemini exceptions; `finally` block re-enables all buttons
- [ ] Ensure tier buttons are disabled during live fetch and Fetch Live is disabled during tier tests (prevent concurrent API calls)

**Testing:** Run `gradle :app:testDebugUnitTest` (no regressions). Manual test on device:
- With active alerts on user's routes → shows Gemini-summarized status with LIVE OUTPUT prefix
- With no alerts on user's routes → shows "Good Service" without calling Gemini
- With airplane mode on → shows network error message
- Tap button while pipeline is running → button is disabled, no double-fetch
**Model: Sonnet** | Reason: Wiring async pipeline across fetcher → parser → rate limiter → Gemini → UI with multiple error-handling branches; needs to coordinate state across existing patterns in MainActivity.

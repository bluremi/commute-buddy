# Commute Buddy (Subway Watch App)

## Problem

Living in Astoria, commuting to work requires constantly pulling up the MTA app to check for train issues. This is done to pre-emptively re-route and avoid getting stuck on the platform. Checking in a rush is painful, and checking underground is unreliable due to spotty cell service and slow MTA REST APIs. Knowing the status of the trains *before* leaving the apartment is critical for deciding whether to commute or work from home.

## Approach

A two-part system: an **Android Companion App** (the "brains") and a **Garmin Connect IQ App/Glance** (the "face").

The Android app runs in the background during a configurable commute window. It fetches the full MTA GTFS-RT alert feed over cellular/Wi-Fi, filters to the user's configured routes (primary legs + alternates), and passes the structured alerts to the **Gemini Flash** cloud API (via Google AI Studio) with a **decision prompt** that produces an actionable commute recommendation — not just a status summary, but a clear directive: proceed normally, expect minor delays, reroute (with which alternates are clear), or stay home. This decision payload is pushed to the Garmin watch via Bluetooth Low Energy (BLE).

When the watch Glance is viewed, it instantly displays the cached status — no loading screens, no network requests — completely bypassing underground connectivity issues.

### User Journeys

**1. Pre-Commute Glance ("What Should I Do?" Check)**
- User configures a morning commute window (e.g., 8:00–9:30 AM)
- Android app silently wakes and begins polling MTA API, running the decision engine, and pushing recommendations to watch
- While getting ready, user glances at watch and sees "Normal", "Minor delays — N,W", "Reroute — N,W" (with which alternates are clear), or "Stay home" — making an informed decision before putting on their coat

**2. Active Commute**
- User leaves apartment; Android app is already polling every 5 minutes
- User descends into subway and loses cell service
- Watch retains last-known state from the phone — instant glance, no spinner

**3. Background Polling (Battery-Aware)**
- Android app polls on a fixed schedule: every 5 minutes (configurable) during active commute windows, once per hour outside them
- Commute windows are user-configured (e.g., 8:00–9:30 AM, 5:30–7:00 PM)
- The hourly off-window poll keeps the watch reasonably fresh for ad-hoc trips
- `ApiRateLimiter`'s persisted 50/day hard cap is the ultimate safeguard — polling gracefully stops when the cap is reached

## Key Features

### Steel Thread (FEAT-01) — BLE Pipeline Validated (UI removed in FEAT-04)
- Validated the full BLE send/receive pipeline end-to-end: Android → Connect IQ SDK → Garmin Glance
- The "Send Code" button, random code display, and integer message handler on the watch were removed once the real `CommuteStatus` flow replaced them in FEAT-04
- BLE state management (SDK init, device discovery, `getApplicationInfo`, `sendMessage` callback) remains in `MainActivity.kt` and is used by all subsequent features

### AI Summarization POC (FEAT-02) — Validated Gemini Summarization
- **4 labeled test buttons** (2×2 grid) in the Android app, each firing a specific tier of real MTA alert text at the Gemini cloud API: Tier 1 (~100 chars, real-time delay), Tier 2 (~500 chars, reroute), Tier 3 (~900 chars, planned suspension with shuttles), Tier 4 (~2000 chars, multi-line stress test)
- Each button press sends its alert text to **Gemini 2.5 Flash** with a system prompt instructing it to return a strict JSON object (`status`, `route_string`, `reason`, `timestamp`)
- Output is deserialized into `CommuteStatus` and displayed as `TIER N OUTPUT: / Status / Route / Reason / Time`; parse failures show the raw model output and error for diagnosis
- **Validated result:** Gemini correctly classified and summarized all 4 tiers, including the 2000-char stress test — JSON was valid and within field length constraints on every run
- **Multi-layer rate limiter** (`ApiRateLimiter`) with persisted daily cap (50/day), per-minute cap (10/min), cooldown (3s), and single-flight mutex — makes runaway API costs virtually impossible; limits survive app restarts
- **Graceful error handling:** network errors (detected by walking the SDK exception cause chain for `IOException`), model-not-found (displays actionable fix), quota exceeded, and empty responses all show clear messages without crashing
- **Model name is configurable** via `local.properties` (`GEMINI_MODEL_NAME`) without a code change — just edit the property and rebuild; defaults to `gemini-2.5-flash`

### Live MTA Alert Pipeline (FEAT-03) — Validated End-to-End Alert Summarization
- **"Fetch Live" button** in the Android app triggers the full pipeline on demand: fetch → parse → route-filter → active-period-filter → Gemini summarization → display
- **`MtaAlertFetcher`** makes an authenticated-free HTTP GET to `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json` on a background coroutine (`Dispatchers.IO`), with 10s connect / 15s read timeouts, returning `Result<String>`
- **`MtaAlertParser`** parses the GTFS-RT JSON response: extracts `header_text` and `description_text` using the `language: "en"` plain-text translation (explicitly ignoring `en-html`), collects all `route_id` values from `informed_entity[]` (ignoring stop-only entries), and reads the `alert_type` from the Mercury extension
- **Route filtering:** only alerts whose `informed_entity[].route_id` intersects the hardcoded set `{N, W, 4, 5, 6}` (defined as `MONITORED_ROUTES`) are forwarded to Gemini; no matching alerts → "Good Service" message, no API call
- **Active-period filtering:** alerts carrying `active_period[]` windows are only included if the current Unix time falls within at least one window (inclusive boundaries; `end == 0` = open-ended). Alerts with no `active_period` entries are always included (GTFS-RT spec). This eliminates false positives from standing overnight/weekend advisories during normal service hours
- **Gemini summarization** of filtered, concatenated alert text uses the existing `ApiRateLimiter` and `CommuteStatus` schema — output is displayed with a `LIVE OUTPUT:` prefix in the same format as FEAT-02 tier tests
- FEAT-02 tier buttons remain in the UI for debugging and comparison
- Network errors, parse failures, and empty feeds each produce a clear user-facing message without crashing

### Route Status BLE Push + Watch Glance (FEAT-04) — Full End-to-End Integration
- After every "Fetch Live" run, the Android app pushes a `CommuteStatus` Dictionary to the watch via BLE — regardless of outcome (good service, delays, or pipeline error)
- **Three push paths:** Good Service (no active alerts → `status=0, reason="Good service"`, no Gemini call); Gemini success (parsed `CommuteStatus` sent directly); pipeline error (MTA fetch fail, feed parse fail, Gemini API error, or unparseable output → `status=2` with truncated error reason)
- Phone results display includes a BLE status line appended below the pipeline output: `"Sent to watch"`, `"Watch send failed: <reason>"`, or `"Watch send skipped: <reason>"` (skipped when SDK not ready, no device connected, or watch app not installed)
- `CommuteStatus.toConnectIQMap()` serializes the data class to a `Map<String, Any>` with keys matching `shared/schema.json`: `"status"` (Int), `"route_string"` (String), `"reason"` (String), `"timestamp"` (Long)
- **Watch receives Dictionary messages:** `CommuteBuddyApp.onPhoneMessage()` checks `instanceof Dictionary`, extracts and validates all four fields (status must be 0–2, strings must be non-null `String`), stores as `cs_status`, `cs_route`, `cs_reason`, `cs_timestamp` in `Application.Storage`, calls `WatchUi.requestUpdate()` — invalid or missing fields are silently ignored, no crash
- **Glance shows simple one-line status:** `"Waiting..."` (no data yet), `"Normal"` (status 0), `"Delays — N,W"` (status 1), `"Disrupted — N,W"` (status 2, em dash via `\u2014`)
- FEAT-01 steel thread removed from both apps as part of this story

### Decision Engine Integration (FEAT-05) — Actionable Commute Recommendations Live
- **Decision prompt fully integrated** into the live Android pipeline — replaces the 3-tier Gemini summarization with the validated 4-tier decision framework (NORMAL, MINOR_DELAYS, REROUTE, STAY_HOME)
- **`CommuteStatus` data class rewritten** with new fields: `action` (String), `summary` (max 80 chars), `affectedRoutes`, `rerouteHint` (String?, REROUTE only), `timestamp`; `fromJson()` validates all four action tiers, allows empty `affected_routes` for NORMAL, treats missing timestamp as current time (Gemini doesn't emit it); `toConnectIQMap()` emits keys matching `shared/schema.json`
- **`MtaAlertParser` restructured**: `MONITORED_ROUTES` expanded from `{N, W, 4, 5, 6}` to `{N, W, 4, 5, 6, R, 7}` to cover alternate lines; `buildPromptText()` now emits structured per-alert blocks (Routes, Type, Posted, Header, Description); `description_text` truncated at 400 chars with `…` suffix; `Active period` field removed (pre-filtered by `filterByActivePeriod()` before reaching Gemini); `MtaAlert` carries `createdAt: Long?` from the Mercury feed extension
- **Decision prompt + commute profile injected** as system prompt: hardcoded TO_WORK and TO_HOME legs with correct directions, station pairs, and alternates (F, R, 7); direction matching, alert freshness rules, and alternate line evaluation all live in the prompt
- **Gemini model configured** with `temperature=0` and `ThinkingLevel.LOW` — achieving the validated POC settings; response time dropped from 30-60s to seconds
- **BLE schema updated** (`shared/schema.json`): `action` (string), `summary`, `affected_routes`, `reroute_hint` (optional), `timestamp`; payload remains well under 1KB
- **Android results display** shows Action tier, Affected routes, Summary, and (for REROUTE) Reroute hint; all error/fallback paths produce a valid `CommuteStatus` with `action=NORMAL` and error description as summary

### Background Polling Service (FEAT-08)
- **`PollingForegroundService`** runs as a `connectedDevice` foreground service (Android 15 requires this type for BLE-connected apps; `dataSync` cannot start from `BOOT_COMPLETED` on Android 15+). Displays a persistent notification showing last poll time and next scheduled poll time, updated after every poll. Scheduling is via `AlarmManager` exact alarms (see ARCH-01 below).
- **`BootReceiver`** starts the service on device boot if polling is enabled, with a `BLUETOOTH_CONNECT` permission guard (if permissions were revoked, aborts silently rather than crashing).
- **Explicit Bluetooth permission request** in `MainActivity.onCreate()`: checks `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` before starting the service; if not yet granted, shows the system permission dialog first, then starts the service in the callback. Solves the Android 15 `connectedDevice` FGS requirement that runtime permissions must be granted before `startForeground()` is called.
- **Notification channel** created in `PollingForegroundService.onStartCommand()` (not only in `MainActivity`) so it exists when the service starts from boot before the user has ever opened the app.
- **`CommutePipeline`** extracts the full fetch→parse→filter→Gemini→deserialize pipeline from `MainActivity` into a shared `suspend fun run()` so both the manual "Fetch Live" button and the polling service run identical logic.
- **`PollingSettingsActivity`** (accessible via "Polling Settings" button): on/off toggle, two commute window rows (start/end `TimePickerDialog`), polling interval slider (2–15 min). Save button starts/stops the service immediately based on the toggle. Persisted in SharedPreferences via `PollingSettingsRepository`.
- **Rate limiter integration:** every scheduled poll passes through `ApiRateLimiter.tryAcquire()`; "Good Service" results skip the Gemini call entirely (no cap usage). `MainActivity` shows current daily usage ("API usage: N/50 today") and a warning when the cap is reached. Usage display refreshes on `onResume()` and whenever the service broadcasts `ACTION_POLL_COMPLETED`.
- **`ACTION_POLL_COMPLETED` broadcast** sent after each service poll; `MainActivity` registers a receiver in `onResume`/`onPause` to refresh the usage counter in real time.

### Active Days Selector & Background Polling Toggle (FEAT-12)
- **Day-of-week selector** in `PollingSettingsActivity`: a horizontal `MaterialButtonToggleGroup` row of 7 toggle buttons (M, T, W, T, F, S, S) positioned between the evening commute window and the interval slider. Selected days (filled with primary color) receive intensive in-window polling; unselected days get only hourly background polls. Default: Monday–Friday selected, Saturday–Sunday unselected.
- **Background polling toggle** ("Hourly polls when not commuting"): a `SwitchMaterial` below the interval slider. When ON (default), hourly polls fire outside commute windows and on inactive days; when OFF, polling is suppressed entirely outside active-day commute windows.
- **Three-tier scheduling** in `getNextAlarmTimeMs()`: (1) active day + inside window → interval-based (2–15 min); (2) background ON + outside intensive times → hourly (top of next hour, or next window start if sooner); (3) background OFF + outside intensive times → skip to the earliest window start on the next active day.
- **Edge case guard**: if all days deselected and background OFF, a Snackbar warning appears and save is blocked ("No polls will run — enable background polling or select at least one day").
- Both `activeDays` and `backgroundPolling` persisted in `PollingSettings` JSON blob; backward-compatible (existing users upgrading get M–F + background ON defaults without losing other settings).

### AlarmManager Polling Architecture (ARCH-01)
- **`AlarmManager.setExactAndAllowWhileIdle()` replaces the coroutine `delay()` loop** — the hardware Real-Time Clock fires a `PendingIntent` directly to the active Foreground Service's `onStartCommand()`, guaranteeing CPU wake on schedule even during Doze mode deep sleep.
- **`ACTION_WAKE_AND_POLL` intent routing** — `onStartCommand()` distinguishes alarm-triggered starts from OS restarts/standard starts. Alarm starts acquire a `PARTIAL_WAKE_LOCK` (10-min safety timeout), run `poll()`, then release the lock and schedule the next alarm in a `finally` block. Non-alarm starts schedule only (no immediate poll).
- **Smart scheduling — on-the-hour + window boundary truncation** — `getNextAlarmTimeMs()` returns an absolute epoch timestamp. Inside a commute window: `now + intervalMinutes`. Outside all windows: top of the next hour. If any commute window starts before that top-of-hour candidate, the alarm fires at the window start instead (ensuring the first in-window poll is never missed).
- **Concurrency guard** — a `Mutex.tryLock()` wraps the full `poll()` call. If a manual "Fetch Live" is already running when an alarm fires, the alarm-triggered poll is skipped and just reschedules.
- **`SCHEDULE_EXACT_ALARM` permission** handled in `MainActivity` and `PollingSettingsActivity`: checks `AlarmManager.canScheduleExactAlarms()` before starting the service; if denied, launches `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` and defers service start to `onResume()`.
- **Notification "Next" time** derived from the scheduled alarm absolute timestamp (not a calculated offset).
- **Active `connectedDevice` Foreground Service exempts the app from Android's 9-minute exact alarm Doze throttle**, keeping both alarm precision and the ConnectIQ SDK connection warm.

### Commute Profile Configuration (FEAT-07)
- **Data model:** `CommuteLeg` (lines, direction, fromStation, toStation) and `CommuteProfile` (toWorkLegs, toHomeLegs, alternates) with full JSON serialization; `CommuteProfile.default()` pre-populates the Astoria commute (TO_WORK: N,W→4,5→6; TO_HOME: 6→4,5→N,W; alternates: F,R,7)
- **`CommuteProfileRepository`** persists/loads the active profile in SharedPreferences as a JSON string; returns the default profile if nothing has been saved
- **`SystemPromptBuilder`** generates the complete system prompt dynamically from the saved `CommuteProfile` — replaces the 65-line hardcoded `SYSTEM_PROMPT` constant that was in `MainActivity`. The system prompt now uses an explicit **four-step decision procedure** (identify active legs → check primary legs → classify severity → evaluate alternates) that replaced the original DECISION FRAMEWORK description format, yielding more reliable NORMAL/REROUTE classification when alternate lines are disrupted but primary legs are clear
- **`MONITORED_ROUTES` removed:** the route filter set is now derived at runtime via `profile.monitoredRoutes()` — all unique lines across both directions' legs plus alternates. `MtaAlertParser.kt` no longer carries a hardcoded constant
- **Direction toggle:** `MaterialButtonToggleGroup` (To Work / To Home) above the Fetch Live button. Selection updates `currentDirection`, persists to SharedPreferences, and is passed to `buildPromptText()` on every fetch; model re-init is not needed when direction changes (direction is in the user prompt, not the system prompt)
- **Configuration Activity:** `CommuteProfileActivity` accessible via "Configure Commute" button on the main screen. Shows TO_WORK and TO_HOME legs as scrollable card lists with Add/Remove controls; each leg card has a line picker button, direction dropdown (`Spinner`), and from/to station text fields. On save, validates (at least one leg per direction; each leg must have lines + direction + both stations), persists via `CommuteProfileRepository`, and returns to main screen. `MainActivity.onResume` reloads the profile and re-initializes the `GenerativeModel` with the updated system prompt
- **Line picker bottom sheet:** `LinePickerBottomSheet` (`BottomSheetDialogFragment`) displays all 23 MTA subway lines as color-coded circular chips (44×44dp) grouped by trunk line: 1-2-3 (red `#D82233`), 4-5-6 (green `#009952`), 7 (purple `#9A38A1`), A-C-E (blue `#0062CF`), B-D-F-M (orange `#EB6800`), G (lime `#799534`), J-Z (brown `#8E5C33`), L-S (grey `#7C858C`), N-Q-R-W (yellow `#F6BC26`). Yellow chips use black text; all others use white. Selected state shown by a 3dp primary-color (`#6200EE`) stroke. Pre-selects current lines on open; Done button returns selection via callback. Same picker used for leg lines and alternates
- **Prompt test suite updated:** `docs/decision-prompt-test.md` and `docs/run-prompt-tests.py` expanded with two live-captured test cases (Live 11, 12 — B/D/F/M signal delays with primary route clear); system prompt in the runner synced to current `SystemPromptBuilder` output

### Garmin Glance + Full-App UI (FEAT-06) — Color-Coded Watch Display with Native Paged Detail
- **Schema fix:** watch message handler in `CommuteBuddyApp.onPhoneMessage()` updated from old integer `status`/`route_string`/`reason` keys (FEAT-04) to the new `action`/`summary`/`affected_routes`/`reroute_hint`/`timestamp` keys introduced by FEAT-05; invalid or incomplete messages are silently rejected
- **Color-coded glance:** `CommuteBuddyGlanceView` reads `cs_action` and `cs_affected_routes` from `Application.Storage` and renders a one-line status in color — NORMAL → green "Normal", MINOR_DELAYS → yellow "Delays — N,W", REROUTE → red "Reroute — N,W", STAY_HOME → gray "Stay Home"; falls back to white "Waiting..." before first message
- **Full-app detail view (BUG-01):** Native page navigation via `WatchUi.ViewLoop` + `ViewLoopFactory` + `ViewLoopDelegate`. `DetailPageFactory` builds a page model from storage, chunks long summaries via `DetailPagination.chunkSummary()`, and provides `DetailPageView` instances. Page 1 shows header (action, routes, reroute hint, freshness) + first summary chunk; subsequent pages show overflow summary chunks with full-page height. No manual scroll offset; swipes and buttons use framework delegates for smooth native transitions.
- **Deterministic pagination:** `DetailPagination` uses `Graphics.fitTextToArea()` and word-boundary chunking so long summaries are fully readable across pages with no truncation. Page 1 uses header-aware body height; pages 2+ use full-page height.
- **Text wrapping via `WatchUi.TextArea`:** `dc.drawWrappedText()` does not exist in the Connect IQ SDK. Native text wrapping uses `new WatchUi.TextArea({:text, :color, :font, :locX, :locY, :width, :height, :justification})` + `.draw(dc)`, available from API level 3.1.0

### MTA Line Badges (FEAT-11) — Color-Coded Line Identifiers Everywhere
- **Android: Shared color + badge utility** — `MtaLineColors` object maps all 23 subway lines to their MTA trunk-line color (9 groups: red 1-2-3, green 4-5-6, purple 7, blue A-C-E, orange B-D-F-M, lime G, brown J-Z, grey L-S, yellow N-Q-R-W). `MtaLineBadgeSpan` is a custom `ReplacementSpan` that draws a filled circle badge with a centered line letter — yellow-background lines (N, Q, R, W) use black text; all others use white. `buildRouteBadges(routesCsv, textSizePx)` builds a `SpannableStringBuilder` with one badge per route. `LinePickerBottomSheet` now delegates to `MtaLineColors` instead of maintaining its own private color functions.
- **Android: Badges in results and profile** — The Affected routes line in `MainActivity` results shows colored circle badges. Leg cards in `CommuteProfileActivity` show badges for the leg's lines ("Lines: " + badges) and the alternates row shows badges instead of plain "Alternates: F, R, 7" text.
- **Garmin: Colored route badges in detail view** — `DetailPageView` renders each affected route as a filled colored circle (`dc.fillCircle()`, radius 20) with the line letter centered on top. `MtaColors` module provides `getLineColor()`, `isLightBackground()`, and `splitCsv()` (implemented via character scan — `Lang.String` has no `indexOf`). Badges are horizontally centered and spaced 4px apart.
- **Garmin: Colored route text in glance** — `CommuteBuddyGlanceView` renders MINOR_DELAYS and REROUTE as: action label in action color, then each route letter in its MTA color, with white comma separators. Each segment is measured with `dc.getTextDimensions()` and drawn left-to-right from a centered origin. NORMAL and STAY_HOME remain single centered strings. All three `MtaColors` functions are annotated `(:glance)` so they compile into the glance bundle.

### Decision Prompt POC (pre-FEAT-05) — Validated Actionable Commute Recommendations
- **Validated that Gemini Flash can reliably produce actionable commute decisions** — not just "what's happening" but "what should you do": NORMAL, MINOR_DELAYS, REROUTE, or STAY_HOME
- **10/10 test scenarios passed** on `gemini-flash-latest` (Gemini 3 Flash Preview) with temperature=0, thinking=low — including direction matching, stale alert handling, escalation/de-escalation, and active period filtering
- **Direction matching from free text works:** MTA feeds do NOT populate `direction_id` in structured data (validated against 202 live alerts). Direction is only in alert header text ("Manhattan-bound", "Downtown", "both directions"). Flash matches this against commute leg directions natively — no fragile regex extraction needed
- **Commute modeled as directional legs**, not a flat route list. Each leg has lines + direction + station endpoints (e.g., "N,W Manhattan-bound, Astoria → 59th St"). This prevents false positives from opposite-direction disruptions
- **Reroute hint reports which alternates are clear** (or also affected) — Flash doesn't try to compute routes, just tells you "7 is clear; F and R have delays". The user knows how to take those lines
- **Four action tiers validated:** NORMAL (all clear), MINOR_DELAYS (delays but service running — standard signal problems, train cleaning), REROUTE (suspended/extensive delays, at least one alternate clear), STAY_HOME (everything impacted, TO_WORK only — TO_HOME always gets REROUTE instead)
- **Alert freshness rules work:** 105-min-old signal delay correctly downgraded to NORMAL; overnight planned work outside active period correctly ignored
- **Output schema:** `action`, `summary` (max 80 chars), `reroute_hint` (max 60 chars, REROUTE only), `affected_routes` — all fit within BLE 1KB limit
- Full prompt, schema, test data, and automated test runner documented in `docs/decision-prompt.md`, `docs/decision-prompt-test.md`, `docs/run-prompt-tests.py` (uses `google-genai` SDK, `gemini-3-flash-preview`, `ThinkingLevel.LOW`; 12/12 tests pass)

## Technical Architecture

### Tech Stack

- **Android App:** Kotlin, `minSdk = 34` (Android 14), Garmin Connect IQ Mobile SDK `2.3.0` (`com.garmin.connectiq:ciq-companion-app-sdk`)
- **AI Decision Engine:** Google Gemini Flash via **Firebase AI Logic SDK** (`com.google.firebase:firebase-ai`, BoM 34.10.0) — replaces the legacy `com.google.ai.client.generativeai` SDK which lacked `ThinkingConfig` support. Model: `gemini-flash-latest` (Gemini 3 Flash Preview); configured with `temperature=0` and `ThinkingLevel.LOW`. API key managed by Firebase project (`google-services.json`) — not stored in `local.properties`. Model name still configurable via `local.properties` (`GEMINI_MODEL_NAME`, default: `gemini-flash-latest`)
- **Garmin Watch App:** Monkey C, Connect IQ SDK `8.4.1`, Toybox.Communications, Toybox.Application.Storage
- **Target Device:** Garmin Venu 3 (`venu3`)
- **Communication:** Bluetooth Low Energy via Connect IQ Mobile SDK (`IQConnectType.WIRELESS`); minimized JSON payload, well under 1KB limit
- **Build Tools:** Android Studio (Gradle/Kotlin DSL), VS Code with Connect IQ extension (Monkey C compiler)
- **Data Sources:** MTA GTFS-RT subway alerts via `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json` (unauthenticated, no API key; also available as protobuf without `.json` suffix). Google Maps Routes API (transit mode, future).

### System Design

**Monorepo** structure — both apps are tightly coupled through the BLE message schema.

- **Android app** (`android/`): `MainActivity.kt` initializes the Connect IQ SDK on launch, discovers the paired Garmin device, verifies the watch app is installed, and sends a payload via `ConnectIQ.sendMessage()`. On startup, requests `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` runtime permissions before starting the polling service (required for `connectedDevice` FGS type on Android 15+), and also checks `AlarmManager.canScheduleExactAlarms()` — if not granted, directs the user to the system exact alarm settings page and defers service start. `CommuteProfileRepository` loads the saved (or default) `CommuteProfile` from SharedPreferences, and `SystemPromptBuilder.buildSystemPrompt(profile)` generates the full system prompt passed to the `GenerativeModel`. A `MaterialButtonToggleGroup` direction toggle (To Work / To Home) persists the selected direction across restarts and passes it to `buildPromptText()` on every fetch. The user configures their commute profile via `CommuteProfileActivity` and polling schedule via `PollingSettingsActivity`. On return, `MainActivity.onResume` reloads the profile and re-initializes `GenerativeModel` with the updated system prompt. The live data pipeline is encapsulated in `CommutePipeline.run()`: `MtaAlertFetcher.fetchAlerts()` (HTTP GET, background coroutine) → `MtaAlertParser.parseAlerts()` (JSON, extracts `en` plain-text translations) → `filterByRoutes()` (keep routes from `profile.monitoredRoutes()`) → `filterByActivePeriod()` (exclude advisories outside their scheduled windows) → `buildPromptText()` (structured per-alert blocks with ISO 8601 timestamps) → Gemini Flash decision engine (Firebase AI Logic SDK, `temperature=0`, `ThinkingLevel.LOW`) → `CommuteStatus.fromJson()` → display + BLE push. All API calls pass through `ApiRateLimiter`. `PollingForegroundService` runs the same `CommutePipeline` on an `AlarmManager` exact-alarm schedule — `setExactAndAllowWhileIdle(RTC_WAKEUP)` fires `ACTION_WAKE_AND_POLL` to the service, which acquires a `PARTIAL_WAKE_LOCK`, runs the pipeline, then releases the lock and schedules the next alarm. Off-window scheduling uses three tiers: active day + inside window → interval-based; background polling ON + outside intensive times → top of next hour (or window start if sooner); background polling OFF + outside intensive times → skip to earliest window start on the next active day. If all active days are deselected and background polling is OFF, scheduling is treated as disabled. The active `connectedDevice` FGS exempts the app from Doze's 9-minute alarm throttle. Started from `MainActivity` on launch and from `BootReceiver` on device boot.
- **Garmin app** (`garmin/`): `CommuteBuddyApp.mc` registers for phone messages in `onStart()` via `Communications.registerForPhoneAppMessages()`. On message receipt, validates the incoming `Dictionary` payload (action must be one of NORMAL/MINOR_DELAYS/REROUTE/STAY_HOME, summary and affected_routes must be non-null Strings), stores `cs_action`, `cs_summary`, `cs_affected_routes`, `cs_reroute_hint`, `cs_timestamp` in `Application.Storage`, clears `cs_reroute_hint` when absent (prevents stale hint persistence), and calls `WatchUi.requestUpdate()`. `CommuteBuddyGlanceView.mc` reads `cs_action` and `cs_affected_routes` on every `onUpdate()` — NORMAL renders green "Normal", STAY_HOME renders gray "Stay Home" (both single centered strings); MINOR_DELAYS and REROUTE draw the action label in action color followed by each route letter in its MTA trunk-line color with white comma separators, all horizontally centered by measuring each segment with `dc.getTextDimensions()`. Full-app detail uses native page navigation: `getInitialView()` returns `[ViewLoop, ViewLoopDelegate]` with `DetailPageFactory`. The factory builds a page model from storage, chunks long summaries via `DetailPagination`, and provides `DetailPageView` + `DetailPageDelegate` per page. `DetailPageView` renders affected routes as colored filled circles (`dc.fillCircle()`, radius 20) with line letters centered on top, using `MtaColors` for color lookups and CSV splitting. No manual scroll offset; `ViewLoopDelegate` handles swipes and button navigation.
- Apps do not share source code; they share a BLE message schema documented in `shared/schema.json`.

### Key Files

```
commute-buddy/
├── android/                                        # Open in Android Studio
│   ├── build.gradle.kts                            # Root Gradle config
│   ├── settings.gradle.kts
│   └── app/
│       ├── build.gradle.kts                        # minSdk 34, Connect IQ SDK, Firebase AI Logic SDK (BoM 34.10.0)
│       ├── google-services.json                    # Firebase project config (gitignored); links app to Firebase for Gemini API auth
│       └── src/main/
│           ├── AndroidManifest.xml                 # BLUETOOTH_SCAN, BLUETOOTH_CONNECT, SCHEDULE_EXACT_ALARM, WAKE_LOCK permissions
│           ├── kotlin/com/commutebuddy/app/
│           │   ├── MainActivity.kt                 # SDK init, device discovery, CommuteProfileRepository load, direction toggle, Fetch Live pipeline, sendCommuteStatus(), AI POC (4 tier buttons)
│           │   ├── CommuteLeg.kt                   # Data class: lines, direction, fromStation, toStation; toJson()/fromJson() serialization
│           │   ├── CommuteProfile.kt               # Data class: toWorkLegs, toHomeLegs, alternates; monitoredRoutes() derives unique route set; toJson()/fromJson(); default Astoria profile
│           │   ├── CommuteProfileRepository.kt     # Persists/loads CommuteProfile to SharedPreferences as JSON string; returns default profile if none saved
│           │   ├── SystemPromptBuilder.kt          # Generates full system prompt from CommuteProfile; four-step decision procedure; alternates referenced dynamically
│           │   ├── CommuteProfileActivity.kt       # Configuration screen: scrollable leg cards (Add/Remove) for TO_WORK and TO_HOME; line picker + direction spinner + station fields per leg; validates and persists on Save
│           │   ├── MtaLineColors.kt                # MTA trunk-line color map (9 groups, 23 lines); isLightBackground(); buildRouteBadges() SpannableStringBuilder helper; LinePickerBottomSheet delegates here
│           │   ├── MtaLineBadgeSpan.kt             # Custom ReplacementSpan: filled circle badge with centered line letter; background = MtaLineColors.lineColor(); black text for yellow lines, white otherwise
│           │   ├── LinePickerBottomSheet.kt        # BottomSheetDialogFragment: 23 MTA lines as color-coded 44dp circular chips, grouped by trunk line; pre-selects current lines; Done callback; delegates colors to MtaLineColors
│           │   ├── CommuteStatus.kt                # BLE schema data class; fromJson() deserialization; toConnectIQMap() for BLE send; statusLabel display
│           │   ├── ApiRateLimiter.kt               # Multi-layer rate limiter (daily cap, per-minute, cooldown, single-flight); injectable clock for unit tests
│           │   ├── MtaTestData.kt                  # Hardcoded real MTA alert strings (4 tiers) for FEAT-02 POC
│           │   ├── MtaAlertFetcher.kt              # suspend fetchAlerts(): HTTP GET subway-alerts.json feed; Result<String>; Dispatchers.IO; 10s/15s timeouts
│           │   ├── MtaAlertParser.kt               # parseAlerts(), filterByRoutes(), filterByActivePeriod(), buildPromptText(); MtaAlert + ActivePeriod data classes; MONITORED_ROUTES removed (derived from profile at runtime)
│           │   ├── CommutePipeline.kt              # suspend fun run(): shared fetch→parse→filter→Gemini→deserialize pipeline used by both MainActivity and PollingForegroundService; PipelineResult sealed class (GoodService, Decision, RateLimited, Error)
│           │   ├── PollingForegroundService.kt     # connectedDevice ForegroundService; AlarmManager exact-alarm scheduling; ACTION_WAKE_AND_POLL intent routing; PARTIAL_WAKE_LOCK for pipeline duration; smart scheduling (on-the-hour off-window + window-boundary truncation); poll Mutex; ConnectIQ + Gemini init; notification with last/next poll times; broadcasts ACTION_POLL_COMPLETED
│           │   ├── BootReceiver.kt                 # BOOT_COMPLETED receiver; checks BLUETOOTH_CONNECT permission before starting service; logs receiver invocation
│           │   ├── PollingSettings.kt              # Data classes: CommuteWindow (isActive()), PollingSettings (enabled, windows, intervalMinutes, activeDays, backgroundPolling); defaults: enabled=false, 8-9:30am + 5:30-7pm, 5min, M–F active, background ON
│           │   ├── PollingSettingsRepository.kt    # Persists/loads PollingSettings to SharedPreferences as JSON
│           │   └── PollingSettingsActivity.kt      # On/off toggle + two TimePickerDialog window rows + active days toggle row (MaterialButtonToggleGroup, M–S) + interval Slider + background polling SwitchMaterial; Save starts/stops service; blocks save if all days off + background OFF
│           ├── res/
│           │   ├── layout/activity_main.xml        # Configure Commute + Polling Settings buttons + direction toggle + Fetch Live button + API usage display + AI POC section
│           │   ├── layout/activity_polling_settings.xml  # Polling settings: on/off toggle + window pickers + active days toggle row (MaterialButtonToggleGroup) + interval slider + background polling switch + Save button
│           │   ├── layout/activity_commute_profile.xml  # Scrollable profile editor: TO_WORK/TO_HOME leg containers + Add buttons + alternates row + Save button
│           │   ├── layout/item_commute_leg.xml     # Leg card: lines summary + Select button + Remove button + direction Spinner + from/to station TextInputLayouts
│           │   ├── layout/bottom_sheet_line_picker.xml  # Bottom sheet: title + LinearLayout chipContainer + Done button
│           │   ├── layout/item_filter_chip.xml     # Single FilterChip template (Widget.MaterialComponents.Chip.Filter style)
│           │   └── values/strings.xml
│           └── test/kotlin/com/commutebuddy/app/
│               ├── PollingSettingsTest.kt          # JSON round-trip (activeDays, backgroundPolling), backward-compat (old JSON → M–F + background ON defaults), getNextAlarmTime three-tier logic
│               ├── CommuteProfileTest.kt           # Round-trip serialization, monitoredRoutes() derivation, default profile contents
│               ├── SystemPromptBuilderTest.kt      # Generated prompt contains leg data, alternates, decision procedure steps, static sections
│               ├── CommuteStatusTest.kt            # toConnectIQMap() key names, value matching, value types
│               ├── MtaAlertParserTest.kt           # parseAlerts, filterByRoutes (uses CommuteProfile.default().monitoredRoutes()), filterByActivePeriod, buildPromptText
│               └── ApiRateLimiterTest.kt
├── garmin/                                         # Open in VS Code
│   ├── monkey.jungle                               # Build config, references manifest.xml
│   ├── manifest.xml                                # Target: venu3, permission: Communications
│   └── source/
│       ├── CommuteBuddyApp.mc                      # AppBase: validates BLE schema, stores cs_* keys; getInitialView() returns [ViewLoop, ViewLoopDelegate] with DetailPageFactory
│       ├── CommuteBuddyGlanceView.mc               # GlanceView (:glance): NORMAL/STAY_HOME as single centered strings; MINOR_DELAYS/REROUTE draws prefix in action color + route letters in MTA colors + white commas, all centered via getTextDimensions()
│       ├── DetailPageFactory.mc                    # ViewLoopFactory: builds page model from storage, chunks summary via DetailPagination, provides DetailPageView per page
│       ├── DetailPageView.mc                       # Single-page view: header (action, MTA badge row, hint, freshness) + summary chunk; badges drawn with MtaColors.getLineColor() + dc.fillCircle()
│       ├── DetailPageDelegate.mc                   # Minimal BehaviorDelegate for page views; ViewLoopDelegate handles navigation
│       ├── DetailPagination.mc                     # Module: chunkSummary(), getRemainderAfterChunk(); deterministic word-boundary chunking via fitTextToArea
│       └── MtaColors.mc                            # Module: getLineColor(), isLightBackground(), splitCsv(); all (:glance)-annotated; splitCsv uses char scan (no indexOf on MC String)
├── shared/
│   └── schema.json                                 # BLE message format: action (string), summary, affected_routes, reroute_hint (optional), timestamp (long)
├── docs/
│   ├── mta-feed-research.md                        # MTA GTFS-RT feed URLs, JSON structure, alert text examples & length tiers
│   ├── decision-prompt.md                          # Decision prompt canonical reference: system prompt, schema, commute profile, API settings, test results
│   ├── decision-prompt-test.md                     # Copy-paste test script for AI Studio + actual results from manual testing; includes Live Captures section for real-world cases
│   ├── run-prompt-tests.py                         # Automated test runner: google-genai SDK, gemini-3-flash-preview, ThinkingLevel.LOW; 12 scenarios (10 synthetic + 2 live captures); system prompt kept in sync with SystemPromptBuilder.kt
│   └── garmin/
│       ├── android-sdk-api-notes.md                # Connect IQ Android SDK 2.3.0 — correct API (getDeviceStatus, IQDevice.IQDeviceStatus); prevents LLM/doc drift
│       ├── glances.md                              # Glance lifecycle, memory limits, Live vs Background UI update modes
│       ├── monkeyc-notes.md                        # Monkey C gotchas: Toybox.Lang import requirement in glance context
│       └── widget-detail-view-best-practices.md    # BUG-01: ViewLoop patterns, pagination, data/visual contract, test checklist
├── PRD.md
├── plan.md
└── CLAUDE.md
```

### Commands

**Garmin (VS Code):**
- `Ctrl+Shift+B` — Build for simulator (targets `venu3_sim`, outputs `garmin/bin/garmin.prg`)
- Command palette → `Monkey C: Build for Device` → select `venu3` — build for physical device
- Copy `garmin/bin/garmin.prg` to `GARMIN/APPS/` on the USB-connected watch to sideload

**Android (Android Studio):**
- Green ▶ Play button — build and install APK on connected device or emulator (one step for code changes)
- After any `build.gradle.kts` or `local.properties` change: sync first (File → Sync Gradle Files, or the 🐘 toolbar button), then ▶ Play

**Android (command line — Windows/PowerShell only):**
> `gradlew`/`gradlew.bat` are **not committed** to this repo. Use the cached Gradle binary directly:
```powershell
$gradle = (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin" -Recurse -Filter "gradle.bat" | Select-Object -First 1 -ExpandProperty FullName)
Set-Location "a:\Phil\Phil Docs\Development\commute-buddy\android"
& $gradle :app:testDebugUnitTest   # run unit tests
& $gradle :app:assembleDebug       # build APK
```

### Technical Notes

**Android Permissions (14+/15+):** `FOREGROUND_SERVICE_CONNECTED_DEVICE` (required for `connectedDevice` FGS type), `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `SCHEDULE_EXACT_ALARM`, `WAKE_LOCK`. On Android 15, `dataSync` FGS cannot start from `BOOT_COMPLETED` — `connectedDevice` is the correct type for a BLE-communicating service and is explicitly boot-exempt. `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` are dangerous permissions requiring runtime grants; the app explicitly requests them in `MainActivity.onCreate()` before starting the service. `SCHEDULE_EXACT_ALARM` is a special permission (not a "dangerous" permission, but not automatically granted): the app checks `AlarmManager.canScheduleExactAlarms()` in `MainActivity` and `PollingSettingsActivity` and directs the user to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` if not yet granted. `WAKE_LOCK` is a normal permission (auto-granted at install); used for `PARTIAL_WAKE_LOCK` during pipeline execution. `BootReceiver` checks `BLUETOOTH_CONNECT` before calling `startForegroundService()` and aborts silently if not granted.

**Gemini Flash (Firebase AI Logic SDK):** The decision engine uses `gemini-flash-latest` (Gemini 3 Flash Preview) via the **Firebase AI Logic SDK** (`com.google.firebase:firebase-ai`, BoM 34.10.0). The free tier is more than sufficient for personal use. Configured with `temperature=0` and `ThinkingLevel.LOW` — achieving the validated POC settings; this dropped response time from 30-60 seconds to ~5 seconds. The legacy `com.google.ai.client.generativeai:generativeai:0.9.0` SDK was replaced because it lacked `ThinkingConfig` support (maxed at 0.9.0, now deprecated by Google). The **API key is managed by Firebase** — it is stored server-side in the Firebase project and accessed via `google-services.json` (which contains only Firebase project identifiers, not the Gemini key itself). The Gemini API key is no longer in `local.properties`. Model name is still configurable via `local.properties` as `GEMINI_MODEL_NAME` (default: `gemini-flash-latest`) and injected via `BuildConfig`. The model receives structured per-alert data plus the decision system prompt (commute profile, decision framework, freshness rules — see `docs/decision-prompt.md`) and returns a strict JSON object with `action`, `summary`, `reroute_hint`, and `affected_routes`. Output is validated/deserialized before transmission. Note: `gemini-2.0-flash` was deprecated and retired on March 3, 2026; `gemini-2.5-flash` also works but `gemini-flash-latest` produces more reliable results on the decision prompt. The Android app includes `ApiRateLimiter` — a multi-layer rate limiter (persisted daily cap of 50 in SharedPreferences, per-minute limit of 10, 3s cooldown, single-flight mutex, no automatic retries) to make runaway API costs virtually impossible.

**Garmin Memory Limits:** Never parse MTA protobuf or JSON on the watch. Monkey C apps have ~32KB memory for background/glance. Keep BLE payload under 1KB. All heavy lifting (protobuf parsing, AI summarization) happens on Android.

**MTA Alert Text Characteristics:** Real MTA GTFS-RT alerts vary dramatically in length and complexity. Each alert has a `header_text` (plain text, `language: "en"`) and an optional `description_text`. Alerts use bracket notation for routes (`[A]`, `[4]`, `[shuttle bus icon]`, `[accessibility icon]`) and structured sections ("What's happening?", "Travel Alternatives:", "ADA Customers:"). Short alerts (real-time delays) are ~100 chars with no description. Medium alerts (single reroute) are ~500-600 chars. Long alerts (weekend planned work with suspensions, shuttle buses, multi-line transfers, ADA notices) are 800-1500+ chars. Weekend construction alerts affecting multiple lines can be significantly longer. **Direction is only in free text** — validated against 202 live alerts (2026-03-05): `direction_id` is NEVER populated in `informed_entity`. Direction appears in header text as "Manhattan-bound", "Queens-bound", "Downtown", "Uptown", "both directions", or terminus names ("Forest Hills-71 Av-bound"). Gemini Flash matches direction language against commute leg directions natively — no structured extraction needed. The pipeline filters by `informed_entity.route_id`, enforces `active_period` windows (standing overnight/weekend advisories carry explicit time windows; without filtering these cause false positives during normal service hours), and extracts the `en` plain-text translation before passing to Gemini Flash. Unit tests use `org.json:json:20250107` (`testImplementation`) because `org.json.JSONObject` is a stub in the Android JVM unit test environment.

**Garmin SDK Caution:** Monkey C and Connect IQ SDK update frequently. LLMs often hallucinate syntax or use deprecated methods. Always verify against latest Toybox.Communications and UI docs. **Android SDK:** The official "Mobile SDK for Android" guide may show `getStatus()` — in SDK 2.3.0 use `getDeviceStatus()`. Use `IQDevice.IQDeviceStatus`, not `ConnectIQ.IQDeviceStatus`. See `docs/garmin/android-sdk-api-notes.md` for correct API usage. **Monkey C `import Toybox.Lang`:** `Dictionary`, `Number`, and `String` are `Lang` types. When the `:glance` annotation causes `CommuteBuddyApp.mc` to be compiled into the glance process, these types are not implicitly in scope — `import Toybox.Lang;` must be added to any `.mc` file that uses them. See `docs/garmin/monkeyc-notes.md`. **`dc.drawWrappedText()` does not exist** — LLMs hallucinate this. Use `WatchUi.TextArea` for wrapped text: `new WatchUi.TextArea({:text=>"...", :color=>..., :font=>..., :locX=>x, :locY=>y, :width=>w, :height=>h, :justification=>...})` then call `.draw(dc)`. Available from API level 3.1.0. **Multi-page content:** Use `WatchUi.ViewLoop` + `ViewLoopFactory` + `ViewLoopDelegate` for native page navigation — do not implement manual `_scrollOffset` scrolling. See `docs/garmin/widget-detail-view-best-practices.md` for BUG-01 patterns.

### Testing Strategy

**Phase 1 — UI & Logic (Simulator):** Develop Garmin Glance UI/logic using Connect IQ Device Simulator in VS Code. No USB sideloading.

**Phase 2 — BLE Integration (Hardware):** Deploy Android APK to phone, sideload `.prg` to Garmin Venu 3 via USB. Test live BLE connection using IQConnectType.WIRELESS.

## Backlog

### Features
- [x] FEAT-01: Steel Thread — Phone generates random 4-digit code, watch displays it (validates build env, BLE, and background execution)
- [x] FEAT-02: AI Summarization POC — Validate Gemini Flash cloud API can reliably parse MTA alert text into strict JSON schema (with strict cost safeguards)
- [x] FEAT-03: MTA GTFS-RT data fetching, parsing, and route filtering on Android (preprocessing pipeline that feeds Gemini Flash; routes hardcoded initially)
- [x] FEAT-04: Route status summary generation and BLE push to watch
- [x] Decision Prompt POC — Validated actionable commute recommendations (NORMAL/MINOR_DELAYS/REROUTE/STAY_HOME) with direction matching, stale alert handling, and alternate line evaluation. 10/10 tests pass on gemini-flash-latest. See `docs/decision-prompt.md`.
- [x] FEAT-05: Decision engine integration — Replace simple Gemini summarization with validated decision prompt. Update CommuteStatus and BLE schema to new fields (action, summary, reroute_hint, affected_routes). Expand MONITORED_ROUTES to include alternate lines (R, 7). Restructure buildPromptText() to structured per-alert format. Migrate to Firebase AI Logic SDK for ThinkingConfig support. Hardcode commute profile for now.
- [x] FEAT-06: Garmin Glance + full-app UI — Color-coded action tiers on glance (green/yellow/red/gray), full detail (summary, reroute_hint, freshness) in app view. Update watch message handling for new schema.
- [x] FEAT-07: Commute profile configuration — UI to define commute legs (line + direction + stations) and alternate lines. Replaces hardcoded profile. Includes commute direction toggle (to work / to home).
- [x] FEAT-08: Background polling service — Fixed-schedule polling with configurable commute windows and polling interval (default 5 min during window, 1 hr outside). User configures windows and interval in Android settings. Runs as a Foreground Service. The ApiRateLimiter's persisted 50/day hard cap ensures the schedule never exceeds the daily budget regardless of configuration.
- [x] FEAT-10: Token usage optimization — Reduce Gemini input token bloat and eliminate redundant LLM reasoning. Two changes: (1) **Strip `Active period:` from prompt** — `filterByActivePeriod()` already guarantees only currently-active alerts reach Gemini, so the active_period timestamps are redundant input that forces expensive chronological math. Planned work alerts (e.g., W train "No Scheduled Service") carry 100+ discrete time windows spanning months, causing 7–12 second latency spikes. Remove the `Active period:` line from `buildPromptText()`, remove the planned-work freshness rule from `SystemPromptBuilder`, and delete the now-dead `formatActivePeriod()` helper. (2) **Cap `description_text` at ~400 chars** — the model makes decisions primarily from `header_text`; `description_text` adds nuance but has rapidly diminishing returns past ~400 chars. Real MTA descriptions for planned work (suspension notices, shuttle routes, ADA notices) can be 800–1,500 chars each. Truncate in `buildPromptText()`. Combined savings: eliminates the largest token contributor (active period arrays) and reduces description overhead by 50–70% on long alerts. Update prompt test suite (`run-prompt-tests.py`, `decision-prompt-test.md`, `decision-prompt.md`) to match.
- [x] FEAT-11: UX improvement: whenever a train line is listed in the watch or android app in plain text, use the MTA colorful icon instead of plain text to make it more readable.
- [x] ARCH-01: Implement Hybrid Polling Architecture — Replace the coroutine `delay()` scheduling loop in `PollingForegroundService` with `AlarmManager` exact alarms. The `AlarmManager` will fire a `PendingIntent` directly to the active `connectedDevice` Foreground Service. This guarantees the hardware Real-Time Clock (RTC) wakes the CPU exactly on time to run the pipeline, resolving the issue of coroutines stalling during deep sleep. Retaining the active Foreground Service exempts the app from Android's 9-minute exact alarm Doze throttling and keeps the ConnectIQ SDK connection warm.
- [x] FEAT-12: Add setting to select days of week for polling, so that the system doesn't use up API calls on weekends or dedicated WFH days. I'm imagining a togglebuttongroup selector where the days are listed in a row beneath the evening commute window but above the polling interval slider. Selected days should have a color fill with the primary app color, unselected should be transparent. Default should be M-F, S/S unselected.

### Bugs
- [x] BUG-01: Rebuild Garmin full-screen detail UX using native page navigation — replaced manual swipe/scroll with ViewLoop-based paged UI; no summary truncation; native-feeling navigation.
- [x] BUG-02: Polling service notification does not automatically appear after reboot when polling is enabled. Fixed: (1) notification channel now created in service's onStartCommand() before startForeground(); (2) FGS type changed from dataSync to connectedDevice; (3) explicit Bluetooth runtime permission request added to MainActivity before service start.
- [x] BUG-03: On android device, after a reboot, launching the app leaves it in an "Initializing SDK" state permanently. Resolved as a side-effect of BUG-02 fix (explicit BT permission request unblocked ConnectIQ SDK initialization).
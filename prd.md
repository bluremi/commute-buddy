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
- User leaves apartment; Android app is already polling every 15–30 seconds
- User descends into subway and loses cell service
- Watch retains last-known state from the phone — instant glance, no spinner

**3. Auto-Shutdown (Battery Preservation)**
- When commute starts, Android app queries Google Maps Routes API for estimated transit duration
- Sets a TTL at 150% of estimated duration
- App auto-kills the polling service and drops BLE to idle when TTL expires

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
- **`MtaAlertParser` restructured**: `MONITORED_ROUTES` expanded from `{N, W, 4, 5, 6}` to `{N, W, 4, 5, 6, R, 7}` to cover alternate lines; `buildPromptText()` now emits structured per-alert blocks with ISO 8601 timestamps (Routes, Type, Posted, Active period, Header, Description); `MtaAlert` carries `createdAt: Long?` from the Mercury feed extension
- **Decision prompt + commute profile injected** as system prompt: hardcoded TO_WORK and TO_HOME legs with correct directions, station pairs, and alternates (F, R, 7); direction matching, alert freshness rules, and alternate line evaluation all live in the prompt
- **Gemini model configured** with `temperature=0` and `ThinkingLevel.LOW` — achieving the validated POC settings; response time dropped from 30-60s to seconds
- **BLE schema updated** (`shared/schema.json`): `action` (string), `summary`, `affected_routes`, `reroute_hint` (optional), `timestamp`; payload remains well under 1KB
- **Android results display** shows Action tier, Affected routes, Summary, and (for REROUTE) Reroute hint; all error/fallback paths produce a valid `CommuteStatus` with `action=NORMAL` and error description as summary

### Decision Prompt POC (pre-FEAT-05) — Validated Actionable Commute Recommendations
- **Validated that Gemini Flash can reliably produce actionable commute decisions** — not just "what's happening" but "what should you do": NORMAL, MINOR_DELAYS, REROUTE, or STAY_HOME
- **10/10 test scenarios passed** on `gemini-flash-latest` (Gemini 3 Flash Preview) with temperature=0, thinking=low — including direction matching, stale alert handling, escalation/de-escalation, and active period filtering
- **Direction matching from free text works:** MTA feeds do NOT populate `direction_id` in structured data (validated against 202 live alerts). Direction is only in alert header text ("Manhattan-bound", "Downtown", "both directions"). Flash matches this against commute leg directions natively — no fragile regex extraction needed
- **Commute modeled as directional legs**, not a flat route list. Each leg has lines + direction + station endpoints (e.g., "N,W Manhattan-bound, Astoria → 59th St"). This prevents false positives from opposite-direction disruptions
- **Reroute hint reports which alternates are clear** (or also affected) — Flash doesn't try to compute routes, just tells you "7 is clear; F and R have delays". The user knows how to take those lines
- **Four action tiers validated:** NORMAL (all clear), MINOR_DELAYS (delays but service running — standard signal problems, train cleaning), REROUTE (suspended/extensive delays, at least one alternate clear), STAY_HOME (everything impacted, TO_WORK only — TO_HOME always gets REROUTE instead)
- **Alert freshness rules work:** 105-min-old signal delay correctly downgraded to NORMAL; overnight planned work outside active period correctly ignored
- **Output schema:** `action`, `summary` (max 80 chars), `reroute_hint` (max 60 chars, REROUTE only), `affected_routes` — all fit within BLE 1KB limit
- Full prompt, schema, test data, and automated test runner documented in `docs/decision-prompt.md`, `docs/decision-prompt-test.md`, `docs/run-prompt-tests.py`

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

- **Android app** (`android/`): `MainActivity.kt` initializes the Connect IQ SDK on launch, discovers the paired Garmin device, verifies the watch app is installed, and sends a payload via `ConnectIQ.sendMessage()`. Currently triggered by explicit button press; future stories will move this to a Foreground Service with scheduled polling. The live data pipeline is: `MtaAlertFetcher.fetchAlerts()` (HTTP GET, background coroutine) → `MtaAlertParser.parseAlerts()` (JSON, extracts `en` plain-text translations) → `filterByRoutes()` (keep `MONITORED_ROUTES = {N, W, 4, 5, 6, R, 7}`) → `filterByActivePeriod()` (exclude advisories outside their scheduled windows) → `buildPromptText()` (structured per-alert blocks with ISO 8601 timestamps, direction header, commute direction) → Gemini Flash decision engine (Firebase AI Logic SDK, `temperature=0`, `ThinkingLevel.LOW`) → `CommuteStatus.fromJson()` → display + BLE push. All API calls pass through `ApiRateLimiter` — a multi-layer rate limiter (persisted daily cap, per-minute limit, cooldown, single-flight mutex) that survives app restarts and prevents runaway costs.
- **Garmin app** (`garmin/`): `CommuteBuddyApp.mc` registers for phone messages in `onStart()` via `Communications.registerForPhoneAppMessages()`. On message receipt, validates the incoming `Dictionary` payload (status 0–2, non-null strings), stores `cs_status`, `cs_route`, `cs_reason`, `cs_timestamp` in `Application.Storage`, and calls `WatchUi.requestUpdate()`. `CommuteBuddyGlanceView.mc` reads `cs_status` and `cs_route` from Storage on every `onUpdate()` — renders `"Normal"`, `"Delays — {route}"`, `"Disrupted — {route}"`, or `"Waiting..."` as fallback.
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
│           ├── AndroidManifest.xml                 # BLUETOOTH_SCAN, BLUETOOTH_CONNECT permissions
│           ├── kotlin/com/commutebuddy/app/
│           │   ├── MainActivity.kt                 # SDK init, device discovery, Fetch Live pipeline, sendCommuteStatus(), AI POC (4 tier buttons)
│           │   ├── CommuteStatus.kt                # BLE schema data class; fromJson() deserialization; toConnectIQMap() for BLE send; statusLabel display
│           │   ├── ApiRateLimiter.kt               # Multi-layer rate limiter (daily cap, per-minute, cooldown, single-flight); injectable clock for unit tests
│           │   ├── MtaTestData.kt                  # Hardcoded real MTA alert strings (4 tiers) for FEAT-02 POC
│           │   ├── MtaAlertFetcher.kt              # suspend fetchAlerts(): HTTP GET subway-alerts.json feed; Result<String>; Dispatchers.IO; 10s/15s timeouts
│           │   └── MtaAlertParser.kt               # parseAlerts(), filterByRoutes(), filterByActivePeriod(), buildPromptText(); MtaAlert + ActivePeriod data classes; MONITORED_ROUTES constant
│           ├── res/
│           │   ├── layout/activity_main.xml        # Live MTA Alerts section (Fetch Live button) + AI POC section (2×2 tier buttons, scrollable results)
│           │   └── values/strings.xml
│           └── test/kotlin/com/commutebuddy/app/
│               ├── CommuteStatusTest.kt            # toConnectIQMap() key names, value matching, value types
│               ├── MtaAlertParserTest.kt           # parseAlerts, filterByRoutes, filterByActivePeriod, buildPromptText
│               └── ApiRateLimiterTest.kt
├── garmin/                                         # Open in VS Code
│   ├── monkey.jungle                               # Build config, references manifest.xml
│   ├── manifest.xml                                # Target: venu3, permission: Communications
│   └── source/
│       ├── CommuteBuddyApp.mc                      # AppBase: validates Dictionary payload, stores cs_status/cs_route/cs_reason/cs_timestamp, requestUpdate
│       ├── CommuteBuddyGlanceView.mc               # GlanceView: reads cs_status/cs_route, renders "Normal"/"Delays — N,W"/"Disrupted — N,W"/"Waiting..."
│       └── CommuteBuddyView.mc                     # Minimal full-app view (required by getInitialView)
├── shared/
│   └── schema.json                                 # BLE message format: action (string), summary, affected_routes, reroute_hint (optional), timestamp (long)
├── docs/
│   ├── mta-feed-research.md                        # MTA GTFS-RT feed URLs, JSON structure, alert text examples & length tiers
│   ├── decision-prompt.md                          # Decision prompt canonical reference: system prompt, schema, commute profile, API settings, test results
│   ├── decision-prompt-test.md                     # Copy-paste test script for AI Studio + actual results from manual and automated testing
│   ├── run-prompt-tests.py                         # Automated test runner: calls Gemini API for all 10 decision prompt scenarios
│   └── garmin/
│       ├── android-sdk-api-notes.md                # Connect IQ Android SDK 2.3.0 — correct API (getDeviceStatus, IQDevice.IQDeviceStatus); prevents LLM/doc drift
│       ├── glances.md                              # Glance lifecycle, memory limits, Live vs Background UI update modes
│       └── monkeyc-notes.md                       # Monkey C gotchas: Toybox.Lang import requirement in glance context
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

**Android Permissions (14+):** FOREGROUND_SERVICE_DATA_SYNC or FOREGROUND_SERVICE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT.

**Gemini Flash (Firebase AI Logic SDK):** The decision engine uses `gemini-flash-latest` (Gemini 3 Flash Preview) via the **Firebase AI Logic SDK** (`com.google.firebase:firebase-ai`, BoM 34.10.0). The free tier is more than sufficient for personal use. Configured with `temperature=0` and `ThinkingLevel.LOW` — achieving the validated POC settings; this dropped response time from 30-60 seconds to ~5 seconds. The legacy `com.google.ai.client.generativeai:generativeai:0.9.0` SDK was replaced because it lacked `ThinkingConfig` support (maxed at 0.9.0, now deprecated by Google). The **API key is managed by Firebase** — it is stored server-side in the Firebase project and accessed via `google-services.json` (which contains only Firebase project identifiers, not the Gemini key itself). The Gemini API key is no longer in `local.properties`. Model name is still configurable via `local.properties` as `GEMINI_MODEL_NAME` (default: `gemini-flash-latest`) and injected via `BuildConfig`. The model receives structured per-alert data plus the decision system prompt (commute profile, decision framework, freshness rules — see `docs/decision-prompt.md`) and returns a strict JSON object with `action`, `summary`, `reroute_hint`, and `affected_routes`. Output is validated/deserialized before transmission. Note: `gemini-2.0-flash` was deprecated and retired on March 3, 2026; `gemini-2.5-flash` also works but `gemini-flash-latest` produces more reliable results on the decision prompt. The Android app includes `ApiRateLimiter` — a multi-layer rate limiter (persisted daily cap of 50 in SharedPreferences, per-minute limit of 10, 3s cooldown, single-flight mutex, no automatic retries) to make runaway API costs virtually impossible.

**Garmin Memory Limits:** Never parse MTA protobuf or JSON on the watch. Monkey C apps have ~32KB memory for background/glance. Keep BLE payload under 1KB. All heavy lifting (protobuf parsing, AI summarization) happens on Android.

**MTA Alert Text Characteristics:** Real MTA GTFS-RT alerts vary dramatically in length and complexity. Each alert has a `header_text` (plain text, `language: "en"`) and an optional `description_text`. Alerts use bracket notation for routes (`[A]`, `[4]`, `[shuttle bus icon]`, `[accessibility icon]`) and structured sections ("What's happening?", "Travel Alternatives:", "ADA Customers:"). Short alerts (real-time delays) are ~100 chars with no description. Medium alerts (single reroute) are ~500-600 chars. Long alerts (weekend planned work with suspensions, shuttle buses, multi-line transfers, ADA notices) are 800-1500+ chars. Weekend construction alerts affecting multiple lines can be significantly longer. **Direction is only in free text** — validated against 202 live alerts (2026-03-05): `direction_id` is NEVER populated in `informed_entity`. Direction appears in header text as "Manhattan-bound", "Queens-bound", "Downtown", "Uptown", "both directions", or terminus names ("Forest Hills-71 Av-bound"). Gemini Flash matches direction language against commute leg directions natively — no structured extraction needed. The pipeline filters by `informed_entity.route_id`, enforces `active_period` windows (standing overnight/weekend advisories carry explicit time windows; without filtering these cause false positives during normal service hours), and extracts the `en` plain-text translation before passing to Gemini Flash. Unit tests use `org.json:json:20250107` (`testImplementation`) because `org.json.JSONObject` is a stub in the Android JVM unit test environment.

**Garmin SDK Caution:** Monkey C and Connect IQ SDK update frequently. LLMs often hallucinate syntax or use deprecated methods. Always verify against latest Toybox.Communications and UI docs. **Android SDK:** The official "Mobile SDK for Android" guide may show `getStatus()` — in SDK 2.3.0 use `getDeviceStatus()`. Use `IQDevice.IQDeviceStatus`, not `ConnectIQ.IQDeviceStatus`. See `docs/garmin/android-sdk-api-notes.md` for correct API usage. **Monkey C `import Toybox.Lang`:** `Dictionary`, `Number`, and `String` are `Lang` types. When the `:glance` annotation causes `CommuteBuddyApp.mc` to be compiled into the glance process, these types are not implicitly in scope — `import Toybox.Lang;` must be added to any `.mc` file that uses them. See `docs/garmin/monkeyc-notes.md`.

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
- [ ] FEAT-06: Garmin Glance + full-app UI — Color-coded action tiers on glance (green/yellow/red/gray), full detail (summary, reroute_hint, freshness) in app view. Update watch message handling for new schema.
- [ ] FEAT-07: Commute profile configuration — UI to define commute legs (line + direction + stations) and alternate lines. Replaces hardcoded profile. Includes commute direction toggle (to work / to home).
- [ ] FEAT-08: Configurable commute window with scheduled background polling
- [ ] FEAT-09: Dynamic TTL via Google Maps Routes API for auto-shutdown

### Bugs
{None yet — add as discovered.}
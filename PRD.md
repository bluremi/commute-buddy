# Commute Buddy (Subway Watch App)

## Problem

Living in Astoria, commuting to work requires constantly pulling up the MTA app to check for train issues. This is done to pre-emptively re-route and avoid getting stuck on the platform. Checking in a rush is painful, and checking underground is unreliable due to spotty cell service and slow MTA REST APIs. Knowing the status of the trains *before* leaving the apartment is critical for deciding whether to commute or work from home.

## Approach

A two-part system: an **Android Companion App** (the "brains") and a **Garmin Connect IQ App/Glance** (the "face").

The Android app runs in the background during a configurable commute window. It fetches the full MTA GTFS-RT protobuf feed over cellular/Wi-Fi, deserializes and filters it down to only the user's configured routes, then passes the filtered alert text to the **Gemini 2.5 Flash** cloud API (via Google AI Studio) for summarization into a strict JSON payload. This payload is pushed to the Garmin watch via Bluetooth Low Energy (BLE).

When the watch Glance is viewed, it instantly displays the cached status — no loading screens, no network requests — completely bypassing underground connectivity issues.

### User Journeys

**1. Pre-Commute Glance ("Stay Home" Check)**
- User configures a morning commute window (e.g., 8:00–9:30 AM)
- Android app silently wakes and begins polling MTA API, pushing status to watch
- While getting ready, user glances at watch and instantly sees if the N/W is delayed — deciding to stay home before putting on their coat

**2. Active Commute**
- User leaves apartment; Android app is already polling every 15–30 seconds
- User descends into subway and loses cell service
- Watch retains last-known state from the phone — instant glance, no spinner

**3. Auto-Shutdown (Battery Preservation)**
- When commute starts, Android app queries Google Maps Routes API for estimated transit duration
- Sets a TTL at 150% of estimated duration
- App auto-kills the polling service and drops BLE to idle when TTL expires

## Key Features

### Steel Thread (FEAT-01) — Validated BLE Pipeline
- **Android companion app** with a single-screen UI: a "Send Code" button, a code display, and a status line
- On button press, generates a random 4-digit code (1000–9999) and sends it to the paired Garmin watch via BLE using the Connect IQ Mobile SDK
- Displays real-time connection status: SDK initializing, watch connected/not connected, app installed/not installed on watch, send success/failure
- **Garmin Glance** displays "Waiting..." on first launch; updates to "Code: XXXX" when a code is received from the phone
- Code is persisted in `Application.Storage` so it survives Glance lifecycle restarts
- Both apps handle error states gracefully — no crashes on disconnection, missing app, or bad messages

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

## Technical Architecture

### Tech Stack

- **Android App:** Kotlin, `minSdk = 34` (Android 14), Garmin Connect IQ Mobile SDK `2.3.0` (`com.garmin.connectiq:ciq-companion-app-sdk`)
- **AI Summarization:** Google Gemini 2.5 Flash via Google AI Studio cloud API — massive context window, native JSON structured output, free tier (10 RPM / 500 RPD); model name configurable via `local.properties` without a code change
- **Garmin Watch App:** Monkey C, Connect IQ SDK `8.4.1`, Toybox.Communications, Toybox.Application.Storage
- **Target Device:** Garmin Venu 3 (`venu3`)
- **Communication:** Bluetooth Low Energy via Connect IQ Mobile SDK (`IQConnectType.WIRELESS`); minimized JSON payload, well under 1KB limit
- **Build Tools:** Android Studio (Gradle/Kotlin DSL), VS Code with Connect IQ extension (Monkey C compiler)
- **Data Sources:** MTA GTFS-RT subway alerts via `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json` (unauthenticated, no API key; also available as protobuf without `.json` suffix). Google Maps Routes API (transit mode, future).

### System Design

**Monorepo** structure — both apps are tightly coupled through the BLE message schema.

- **Android app** (`android/`): `MainActivity.kt` initializes the Connect IQ SDK on launch, discovers the paired Garmin device, verifies the watch app is installed, and sends a payload via `ConnectIQ.sendMessage()`. Currently triggered by explicit button press; future stories will move this to a Foreground Service with scheduled polling. The live data pipeline is: `MtaAlertFetcher.fetchAlerts()` (HTTP GET, background coroutine) → `MtaAlertParser.parseAlerts()` (JSON, extracts `en` plain-text translations) → `filterByRoutes()` (keep `MONITORED_ROUTES`) → `filterByActivePeriod()` (exclude advisories outside their scheduled windows) → `buildPromptText()` → Gemini 2.5 Flash summarization → `CommuteStatus.fromJson()` → display. All API calls pass through `ApiRateLimiter` — a multi-layer rate limiter (persisted daily cap, per-minute limit, cooldown, single-flight mutex) that survives app restarts and prevents runaway costs.
- **Garmin app** (`garmin/`): `CommuteBuddyApp.mc` registers for phone messages in `onStart()` via `Communications.registerForPhoneAppMessages()`. On message receipt, stores data in `Application.Storage` and calls `WatchUi.requestUpdate()`. `CommuteBuddyGlanceView.mc` reads from Storage on every `onUpdate()` — renders status or "Waiting..." as fallback.
- Apps do not share source code; they share a BLE message schema documented in `shared/schema.json`.

### Key Files

```
commute-buddy/
├── android/                                        # Open in Android Studio
│   ├── build.gradle.kts                            # Root Gradle config
│   ├── settings.gradle.kts
│   └── app/
│       ├── build.gradle.kts                        # minSdk 34, Connect IQ SDK, Google GenAI SDK
│       └── src/main/
│           ├── AndroidManifest.xml                 # BLUETOOTH_SCAN, BLUETOOTH_CONNECT permissions
│           ├── kotlin/com/commutebuddy/app/
│           │   ├── MainActivity.kt                 # SDK init, device discovery, send logic, status UI, AI POC (4 tier buttons), Fetch Live pipeline
    │           │   ├── CommuteStatus.kt                # BLE schema data class (status/route_string/reason/timestamp); JSON deserialization; display labels
    │           │   ├── ApiRateLimiter.kt               # Multi-layer rate limiter (daily cap, per-minute, cooldown, single-flight); injectable clock for unit tests
    │           │   ├── MtaTestData.kt                  # Hardcoded real MTA alert strings (4 tiers) for FEAT-02 POC
    │           │   ├── MtaAlertFetcher.kt              # suspend fetchAlerts(): HTTP GET subway-alerts.json feed; Result<String>; Dispatchers.IO; 10s/15s timeouts
    │           │   └── MtaAlertParser.kt               # parseAlerts(), filterByRoutes(), filterByActivePeriod(), buildPromptText(); MtaAlert + ActivePeriod data classes; MONITORED_ROUTES constant
    │           └── res/
    │               ├── layout/activity_main.xml        # Send Code UI + Live MTA Alerts section (Fetch Live button) + AI POC section (2×2 tier buttons, scrollable results)
    │               └── values/strings.xml
├── garmin/                                         # Open in VS Code
│   ├── monkey.jungle                               # Build config, references manifest.xml
│   ├── manifest.xml                                # Target: venu3, permission: Communications
│   └── source/
│       ├── CommuteBuddyApp.mc                      # AppBase: registers phone messages, Storage write, requestUpdate
│       ├── CommuteBuddyGlanceView.mc               # GlanceView: reads Storage, renders code or "Waiting..."
│       └── CommuteBuddyView.mc                     # Minimal full-app view (required by getInitialView)
├── shared/
│   └── schema.json                                 # BLE message format: JSON object (status, route_string, reason, timestamp)
├── docs/
│   ├── mta-feed-research.md                        # MTA GTFS-RT feed URLs, JSON structure, alert text examples & length tiers
│   └── garmin/
│       └── android-sdk-api-notes.md                 # Connect IQ Android SDK 2.3.0 — correct API (getDeviceStatus, IQDevice.IQDeviceStatus); prevents LLM/doc drift
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

**Gemini 2.5 Flash (cloud API):** Summarization runs via the Google AI Studio cloud API. The free tier provides 10 requests/minute and 500 requests/day — more than sufficient for personal use. The model has a massive context window; validated to handle all 4 MTA alert tiers (up to ~2000 chars) reliably in FEAT-02. The Android app includes `ApiRateLimiter` — a multi-layer rate limiter (persisted daily cap of 50 in SharedPreferences, per-minute limit of 10, 3s cooldown, single-flight mutex, no automatic retries) to make runaway API costs virtually impossible. API key is stored in `local.properties` (excluded from version control) and injected via `BuildConfig`. Model name is also stored in `local.properties` as `GEMINI_MODEL_NAME` (default: `gemini-2.5-flash`) — changing models requires only editing this property and rebuilding, not a code change. Note: `gemini-2.0-flash` was deprecated and retired on March 3, 2026. The model receives filtered alert text plus a system prompt and returns a strict JSON object. Output is validated/deserialized via `CommuteStatus.fromJson()` before transmission. If this app is ever published, the architecture supports adding a paywall or bring-your-own-key model.

**Garmin Memory Limits:** Never parse MTA protobuf or JSON on the watch. Monkey C apps have ~32KB memory for background/glance. Keep BLE payload under 1KB. All heavy lifting (protobuf parsing, AI summarization) happens on Android.

**MTA Alert Text Characteristics:** Real MTA GTFS-RT alerts vary dramatically in length and complexity. Each alert has a `header_text` (plain text, `language: "en"`) and an optional `description_text`. Alerts use bracket notation for routes (`[A]`, `[4]`, `[shuttle bus icon]`, `[accessibility icon]`) and structured sections ("What's happening?", "Travel Alternatives:", "ADA Customers:"). Short alerts (real-time delays) are ~100 chars with no description. Medium alerts (single reroute) are ~500-600 chars. Long alerts (weekend planned work with suspensions, shuttle buses, multi-line transfers, ADA notices) are 800-1500+ chars. Weekend construction alerts affecting multiple lines can be significantly longer. The pipeline filters by `informed_entity.route_id`, enforces `active_period` windows (standing overnight/weekend advisories carry explicit time windows; without filtering these cause false positives during normal service hours), and extracts the `en` plain-text translation before passing to Gemini 2.5 Flash. Unit tests use `org.json:json:20250107` (`testImplementation`) because `org.json.JSONObject` is a stub in the Android JVM unit test environment.

**Garmin SDK Caution:** Monkey C and Connect IQ SDK update frequently. LLMs often hallucinate syntax or use deprecated methods. Always verify against latest Toybox.Communications and UI docs. **Android SDK:** The official "Mobile SDK for Android" guide may show `getStatus()` — in SDK 2.3.0 use `getDeviceStatus()`. Use `IQDevice.IQDeviceStatus`, not `ConnectIQ.IQDeviceStatus`. See `docs/garmin/android-sdk-api-notes.md` for correct API usage.

### Testing Strategy

**Phase 1 — UI & Logic (Simulator):** Develop Garmin Glance UI/logic using Connect IQ Device Simulator in VS Code. No USB sideloading.

**Phase 2 — BLE Integration (Hardware):** Deploy Android APK to phone, sideload `.prg` to Garmin Venu 3 via USB. Test live BLE connection using IQConnectType.WIRELESS.

## Backlog

### Features
- [x] FEAT-01: Steel Thread — Phone generates random 4-digit code, watch displays it (validates build env, BLE, and background execution)
- [x] FEAT-02: AI Summarization POC — Validate Gemini 2.5 Flash cloud API can reliably parse MTA alert text into the strict JSON schema (with strict cost safeguards)
- [x] FEAT-03: MTA GTFS-RT data fetching, protobuf parsing, and route filtering on Android (preprocessing pipeline that feeds Gemini 2.5 Flash; routes/direction hardcoded initially)
- [ ] FEAT-04: Route status summary generation and BLE push to watch
- [ ] FEAT-05: Garmin Glance UI displaying train status from BLE messages
- [ ] FEAT-06: Configurable commute window with scheduled background polling
- [ ] FEAT-07: Dynamic TTL via Google Maps Routes API for auto-shutdown
- [ ] FEAT-08: Route & direction configuration — UI to select monitored lines, commute direction (to/from work), and alternate routes for rerouting suggestions

### Bugs
{None yet — add as discovered.}
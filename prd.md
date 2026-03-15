# Commute Buddy (Subway Watch App)

## Problem

Living in Astoria, commuting to work requires constantly pulling up the MTA app to check for train issues. This is done to pre-emptively re-route and avoid getting stuck on the platform. Checking in a rush is painful, and checking underground is unreliable due to spotty cell service and slow MTA REST APIs. Knowing the status of the trains *before* leaving the apartment is critical for deciding whether to commute or work from home.

## Approach

A multi-part system: an **Android Companion App** (the "brains") and one or more **watch apps** (the "face"). Phase I shipped with a **Garmin Connect IQ App/Glance**. Phase II adds **Wear OS** support.

The Android app runs in the background during configurable commute windows. It fetches the full MTA GTFS-RT alert feed over cellular/Wi-Fi, filters to the user's configured routes (primary legs + alternates), and passes the structured alerts to the **Gemini Flash** cloud API (via Firebase AI Logic SDK) with a **decision prompt** that produces an actionable commute recommendation — not just a status summary, but a clear directive: proceed normally, expect minor delays, reroute (with which alternates are clear), or stay home. This decision payload is pushed to paired watches via BLE (Garmin) and the Wearable Data Layer API (Wear OS).

When a watch glance/tile is viewed, it instantly displays the cached status — no loading screens, no network requests — completely bypassing underground connectivity issues.

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

## Current Capabilities

### AI Decision Engine
- Four action tiers: **NORMAL**, **MINOR_DELAYS**, **REROUTE**, **STAY_HOME** — each with a summary (max 80 chars), affected routes, and optional reroute hint (max 60 chars)
- Commute modeled as **directional legs** (e.g., "N,W Manhattan-bound, Astoria → 59th St") — prevents false positives from opposite-direction disruptions
- Direction matching from MTA alert free text works natively (MTA feeds never populate `direction_id` in structured data — direction only appears in header text as "Manhattan-bound", "Downtown", etc.)
- Alert freshness rules: stale alerts (>105 min) are downgraded; overnight planned work outside active periods is ignored
- Output fits within BLE 1KB limit

### MTA Data Pipeline
- Fetches MTA GTFS-RT subway alerts JSON feed (unauthenticated)
- Filters by `informed_entity.route_id` against monitored routes (derived from user's commute profile at runtime)
- Filters by `active_period` windows to eliminate standing overnight/weekend advisories during normal service hours
- Caps `description_text` at ~400 chars (diminishing returns past that; planned work descriptions can be 800-1500+ chars)
- Structured per-alert blocks sent to Gemini with ISO 8601 timestamps

### Background Polling
- `PollingForegroundService` runs as `connectedDevice` FGS (Android 15+ compatible; `dataSync` cannot start from `BOOT_COMPLETED`)
- `AlarmManager.setExactAndAllowWhileIdle()` (RTC_WAKEUP) fires `ACTION_WAKE_AND_POLL` with `PARTIAL_WAKE_LOCK` — guarantees CPU wake during Doze deep sleep
- Three-tier scheduling: active day + in-window → configurable interval (2-15 min); background ON + off-hours → hourly (or window start if sooner); background OFF + off-hours → skip to next active window
- Active days selector (default M-F) prevents API usage on weekends/WFH days
- Auto-direction: window 0 → TO_WORK, window 1 → TO_HOME; falls back to last-polled direction outside windows
- `BootReceiver` auto-starts on device boot (with BT permission guard)
- Active `connectedDevice` FGS exempts from Android's 9-minute exact alarm Doze throttle

### Android Companion App
- **Home screen:** Manual direction toggle (To Work / To Home) for Fetch Live only; auto-polling direction status line; API usage counter (N/50 today); watch connection status ("No watch connected" / "Garmin connected" / "Wear OS connected" / "Garmin + Wear OS connected")
- **Commute Profile editor:** Define TO_WORK and TO_HOME legs (lines + direction + stations) and alternate lines; 23 MTA lines as color-coded circular chips in a bottom sheet picker
- **Polling Settings:** On/off toggle, two commute windows (morning/evening), active days row, polling interval slider, background polling toggle
- **MTA line badges:** Color-coded circular badges (9 MTA trunk-line color groups) in results, profile editor, and commute status display via `MtaLineColors` + `MtaLineBadgeSpan`
- **Rate limiter:** Persisted daily cap (50/day), per-minute (10/min), 3s cooldown, single-flight mutex, no auto-retry

### Multi-Watch Broadcasting
- `WatchNotifier` interface abstracts watch communication; each implementation no-ops gracefully when its watch type is unavailable
- `GarminNotifier` encapsulates all ConnectIQ SDK init, device discovery, app info loading, and BLE send logic; skips SDK initialization (and suppresses the Garmin Connect install dialog) when Garmin Connect is not installed
- `WearOsNotifier` uses `DataClient.putDataItem()` with `PutDataMapRequest` at `/commute-status`; includes a `sent_at` timestamp to ensure `onDataChanged()` fires even for identical consecutive payloads
- Both notifiers wired into `PollingForegroundService` and `MainActivity`; every poll broadcasts to all watch types simultaneously
- Failure isolation: a throwing notifier is caught and logged; remaining notifiers always execute

### Garmin Watch App
- **Glance:** One-line color-coded status — NORMAL (green), MINOR_DELAYS (yellow), REROUTE (red), STAY_HOME (gray). MINOR_DELAYS/REROUTE show each route letter in its MTA trunk-line color
- **Detail view:** Native `ViewLoop` paged navigation. Page 1: action title → colored route badges → timestamp (FONT_XTINY) → reroute hint (action-tier color). Summary text follows in white. When the hint fills the screen, page 1 is header-only and summary starts on page 2. Deterministic word-boundary pagination via `fitTextToArea()`
- **BLE schema:** `action` (string), `summary`, `affected_routes`, `reroute_hint` (optional), `timestamp` (long) — documented in `shared/schema.json`

## Technical Architecture

### Tech Stack

- **Android App:** Kotlin, `minSdk = 34` (Android 14), Garmin Connect IQ Mobile SDK `2.3.0`, `com.google.android.gms:play-services-wearable`
- **AI Decision Engine:** Gemini Flash via **Firebase AI Logic SDK** (`com.google.firebase:firebase-ai`, BoM 34.10.0). Model: `gemini-flash-latest` (Gemini 3 Flash Preview); `temperature=0`, `ThinkingLevel.LOW`. API key managed by Firebase (`google-services.json`). Model name configurable via `local.properties` (`GEMINI_MODEL_NAME`).
- **Garmin Watch App:** Monkey C, Connect IQ SDK `8.4.1`, target: Garmin Venu 3 (`venu3`)
- **Communication:** BLE via Connect IQ Mobile SDK (`IQConnectType.WIRELESS`)
- **Data Source:** MTA GTFS-RT subway alerts — `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json` (unauthenticated)
- **Build Tools:** Android Studio (Gradle/Kotlin DSL), VS Code with Connect IQ extension (Monkey C compiler)

### System Design

**Monorepo** — both apps are tightly coupled through the BLE message schema.

- **Android app** (`android/`): `MainActivity.kt` initializes `GarminNotifier` and `WearOsNotifier`, manages manual fetch direction, API usage display, and watch connection status text. `CommutePipeline.run()` encapsulates the full pipeline: `MtaAlertFetcher` (HTTP GET) → `MtaAlertParser` (parse + route filter + active period filter + prompt text builder) → Gemini Flash decision engine → `CommuteStatus.fromJson()` → display + broadcast. After a successful result, `notifyAll()` (package-level function in `WatchNotifier.kt`) broadcasts to all registered `WatchNotifier` implementations with per-notifier failure isolation. `SystemPromptBuilder` generates the system prompt dynamically from the saved `CommuteProfile`. `PollingForegroundService` runs the same pipeline on `AlarmManager` exact-alarm schedule with `PARTIAL_WAKE_LOCK` and calls `notifyAll()` after each poll. Direction is resolved automatically from the active window index (0→TO_WORK, 1→TO_HOME) with SharedPreferences fallback.
- **Garmin app** (`garmin/`): `CommuteBuddyApp.mc` receives and validates BLE payloads, stores fields in `Application.Storage`. `CommuteBuddyGlanceView.mc` renders color-coded one-line status. Detail view uses `ViewLoop` + `DetailPageFactory` for native paged navigation with dynamic layout measurement.
- Apps share a BLE message schema (`shared/schema.json`) but no source code.

### Key Files

```
commute-buddy/
├── android/                                        # Open in Android Studio
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── app/
│       ├── build.gradle.kts                        # minSdk 34, Connect IQ SDK, Firebase AI Logic SDK (BoM 34.10.0)
│       ├── google-services.json                    # Firebase project config (gitignored)
│       └── src/main/
│           ├── AndroidManifest.xml                 # BLUETOOTH_SCAN, BLUETOOTH_CONNECT, SCHEDULE_EXACT_ALARM, WAKE_LOCK, FOREGROUND_SERVICE_CONNECTED_DEVICE
│           ├── kotlin/com/commutebuddy/app/
│           │   ├── MainActivity.kt                 # GarminNotifier + WearOsNotifier init, manual direction toggle, Fetch Live, watch status text, debug menu
│           │   ├── CommuteLeg.kt                   # Data class: lines, direction, fromStation, toStation
│           │   ├── CommuteProfile.kt               # Data class: toWorkLegs, toHomeLegs, alternates; monitoredRoutes(); default Astoria profile
│           │   ├── CommuteProfileRepository.kt     # SharedPreferences persistence for CommuteProfile
│           │   ├── SystemPromptBuilder.kt          # Generates system prompt from CommuteProfile; four-step decision procedure
│           │   ├── CommuteProfileActivity.kt       # Profile editor: leg cards, line picker, direction spinner, station fields
│           │   ├── MtaLineColors.kt                # MTA trunk-line color map (9 groups, 23 lines); buildRouteBadges() helper
│           │   ├── MtaLineBadgeSpan.kt             # Custom ReplacementSpan: filled circle badge with centered line letter
│           │   ├── LinePickerBottomSheet.kt        # 23 MTA lines as color-coded circular chips
│           │   ├── CommuteStatus.kt                # BLE schema data class; fromJson(); toConnectIQMap()
│           │   ├── ApiRateLimiter.kt               # Multi-layer rate limiter; injectable clock for tests
│           │   ├── MtaAlertFetcher.kt              # HTTP GET subway alerts feed; Dispatchers.IO
│           │   ├── MtaAlertParser.kt               # parseAlerts(), filterByRoutes(), filterByActivePeriod(), buildPromptText()
│           │   ├── CommutePipeline.kt              # Shared fetch→parse→filter→Gemini→deserialize pipeline
│           │   ├── WatchNotifier.kt                # WatchNotifier interface + notifyAll() package-level function (failure-isolated broadcast)
│           │   ├── GarminNotifier.kt               # ConnectIQ SDK init, device discovery, app info loading, BLE send; skips init if Garmin Connect absent
│           │   ├── WearOsNotifier.kt               # DataClient.putDataItem() to /commute-status; sent_at timestamp for change detection; no-ops without Play Services
│           │   ├── PollingForegroundService.kt     # connectedDevice FGS; AlarmManager scheduling; wake lock; auto-direction; notifyAll() after each poll
│           │   ├── BootReceiver.kt                 # BOOT_COMPLETED → startForegroundService (with BT permission check)
│           │   ├── PollingSettings.kt              # Data classes: CommuteWindow, PollingSettings (windows, interval, activeDays, backgroundPolling)
│           │   ├── PollingSettingsRepository.kt    # SharedPreferences persistence for PollingSettings
│           │   └── PollingSettingsActivity.kt      # Polling config UI: toggle, windows, active days, interval, background polling
│           ├── res/
│           │   ├── layout/activity_main.xml
│           │   ├── layout/activity_polling_settings.xml
│           │   ├── layout/activity_commute_profile.xml
│           │   ├── layout/item_commute_leg.xml
│           │   ├── layout/bottom_sheet_line_picker.xml
│           │   ├── layout/item_filter_chip.xml
│           │   └── values/strings.xml
│           └── test/kotlin/com/commutebuddy/app/
│               ├── PollingSettingsTest.kt
│               ├── PollingForegroundServiceSchedulingTest.kt
│               ├── CommuteProfileTest.kt
│               ├── CommutePipelineTest.kt
│               ├── SystemPromptBuilderTest.kt
│               ├── CommuteStatusTest.kt
│               ├── MtaAlertParserTest.kt
│               ├── ApiRateLimiterTest.kt
│               └── WatchNotifierOrchestratorTest.kt
├── garmin/                                         # Open in VS Code
│   ├── monkey.jungle
│   ├── manifest.xml                                # Target: venu3, permission: Communications
│   └── source/
│       ├── CommuteBuddyApp.mc                      # AppBase: validates BLE schema, stores cs_* keys
│       ├── CommuteBuddyGlanceView.mc               # Color-coded glance with MTA route colors
│       ├── DetailPageFactory.mc                    # ViewLoopFactory: page model, hint measurement, summary chunking
│       ├── DetailPageView.mc                       # Structured header + summary; colored route badges
│       ├── DetailPageDelegate.mc                   # BehaviorDelegate for page views
│       ├── DetailPagination.mc                     # Word-boundary chunking via fitTextToArea
│       └── MtaColors.mc                            # getLineColor(), isLightBackground(), splitCsv()
├── shared/
│   └── schema.json                                 # BLE message format: action, summary, affected_routes, reroute_hint, timestamp
├── docs/
│   ├── phase1-changelog.md                         # Historical record of all Phase I features and bugs
│   ├── monetization-plan.md                        # Deferred subscription model ($2.99/mo)
│   ├── mta-feed-research.md                        # MTA GTFS-RT feed URLs, JSON structure, alert text examples
│   ├── decision-prompt.md                          # Decision prompt reference: system prompt, schema, test results
│   ├── decision-prompt-test.md                     # Manual test script for AI Studio + results
│   ├── run-prompt-tests.py                         # Automated test runner: google-genai SDK, 12 scenarios
│   └── garmin/
│       ├── android-sdk-api-notes.md                # Connect IQ Android SDK 2.3.0 correct API
│       ├── glances.md                              # Glance lifecycle and memory limits
│       ├── monkeyc-notes.md                        # Monkey C gotchas
│       └── widget-detail-view-best-practices.md    # ViewLoop patterns and pagination
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
- Green ▶ Play button — build and install APK on connected device or emulator
- After any `build.gradle.kts` or `local.properties` change: sync first (File → Sync Gradle Files), then ▶ Play

**Android (command line):**
> `gradlew`/`gradlew.bat` are **not committed** to this repo. Use the cached Gradle binary directly.

Bash (e.g., Claude Code, WSL):
```bash
GRADLE=(/c/Users/blure/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle)
cd "A:/Phil/Phil Docs/Development/commute-buddy/android"
"${GRADLE[0]}" :app:testDebugUnitTest   # run unit tests
"${GRADLE[0]}" :app:assembleDebug       # build APK
```

PowerShell:
```powershell
$gradle = (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin" -Recurse -Filter "gradle.bat" | Select-Object -First 1 -ExpandProperty FullName)
Set-Location "a:\Phil\Phil Docs\Development\commute-buddy\android"
& $gradle :app:testDebugUnitTest   # run unit tests
& $gradle :app:assembleDebug       # build APK
```

### Technical Notes

**Android Permissions (14+/15+):** `FOREGROUND_SERVICE_CONNECTED_DEVICE` (required for `connectedDevice` FGS type), `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `SCHEDULE_EXACT_ALARM`, `WAKE_LOCK`. On Android 15, `dataSync` FGS cannot start from `BOOT_COMPLETED` — `connectedDevice` is the correct type for BLE services and is boot-exempt. `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` require runtime grants; the app requests them in `MainActivity.onCreate()` before starting the service. `SCHEDULE_EXACT_ALARM` requires the user to grant via system settings. `BootReceiver` checks `BLUETOOTH_CONNECT` before `startForegroundService()` and aborts silently if not granted.

**Gemini Flash:** The free Firebase AI tier is sufficient for personal use. Response time is ~5 seconds with `ThinkingLevel.LOW` (vs 30-60s without). Note: `gemini-2.0-flash` was deprecated and retired March 3, 2026. The `ApiRateLimiter`'s persisted 50/day cap makes runaway costs virtually impossible.

**MTA Alert Text Characteristics:** Alerts vary dramatically: short (~100 chars, real-time delays), medium (~500-600 chars, reroutes), long (800-1500+ chars, weekend planned work with suspensions/shuttles/ADA notices). **Direction is only in free text** — `direction_id` is NEVER populated in `informed_entity` (validated against 202 live alerts). Direction appears as "Manhattan-bound", "Queens-bound", "Downtown", "Uptown", etc. Gemini Flash matches these against commute leg directions natively.

**Garmin Memory Limits:** Monkey C apps have ~32KB memory for background/glance. Never parse protobuf or JSON on the watch. Keep BLE payload under 1KB. All heavy lifting happens on Android.

**Garmin SDK Caution:** LLMs frequently hallucinate Monkey C syntax or use deprecated methods. Always verify against latest Connect IQ SDK docs. Key gotchas: use `getDeviceStatus()` not `getStatus()`, use `IQDevice.IQDeviceStatus` not `ConnectIQ.IQDeviceStatus`, `dc.drawWrappedText()` does not exist (use `WatchUi.TextArea`), `import Toybox.Lang` is required in `:glance`-annotated files. See `docs/garmin/` for detailed notes.

**`org.json` in unit tests:** `org.json.JSONObject` is a stub in the Android JVM test environment. Any class using `org.json` in production needs `testImplementation("org.json:json:20250107")` in `build.gradle.kts`.

### Testing Strategy

**Garmin UI & Logic (Simulator):** Connect IQ Device Simulator in VS Code. No USB sideloading needed.

**Garmin BLE Integration (Hardware):** Deploy APK to phone, sideload `.prg` to Garmin Venu 3 via USB. Test live BLE via `IQConnectType.WIRELESS`.

**Wear OS (Phase II):** Wear OS emulator for steel thread validation. Physical testing on Pixel Watch (1st gen) for hardware validation.

## Backlog

### Phase I (Complete)
All Phase I features and bugs are resolved. See `docs/phase1-changelog.md` for the full historical record.

### Phase II: Wear OS Expansion

#### Objective
Expand the Total Addressable Market (TAM) by supporting Wear OS devices. The initial implementation follows a "steel thread" approach to validate the core infrastructure before investing in full feature development.

**The Steel Thread Concept:** A minimalist, end-to-end technical spike to validate the highest-risk integration points. For Phase II, the steel thread proves the data pipeline: broadcasting `CommuteStatus` from the Android background service via the Wearable Data Layer API, and receiving/rendering it on a Wear OS device.

#### Architectural Approach

**1. Android App: Dual-Broadcasting (No Manual Toggle)**
- Introduce a `WatchNotifier` interface to abstract the watch communication layer
- `GarminNotifier` (existing Connect IQ SDK logic) and `WearOsNotifier` (Wearable Data Layer API)
- `CommutePipeline` broadcasts to all registered notifiers; unavailable watch types no-op gracefully
- **Data Transfer:** `DataClient` via `PutDataMapRequest` — syncs latest status even after temporary disconnection (every payload includes a timestamp, so `DataClient` always triggers `onDataChanged()`)

**2. Wear OS App: Compose & ProtoLayout**
- Standalone Wear OS module in the monorepo, min Wear OS 3 (API 30) — compatible with Pixel Watch 1st gen and the vast majority of the installed base
- Two pieces: Tile (system-rendered, equivalent to Garmin Glance) and Main Activity (equivalent to Garmin Detail View)

**3. Wear OS Tile (Glanceable State)**
- Built using **ProtoLayout API** (Tiles cannot use Compose and cannot scroll)
- `titleSlot`: Action status colored to tier. `mainSlot`: Circular route badges. `bottomSlot`: `reroute_hint` (prioritized over `summary`) with `maxLines` ellipsis for overflow
- Tap anywhere launches Main Activity

**4. Wear OS Main Activity (Detail State)**
- Built using **Compose for Wear OS** with `ScalingLazyColumn`
- Full information hierarchy: action → route badges → timestamp → reroute hint → summary
- Natively scrollable, no truncation, no complex pagination

#### Phase II Backlog

**Steel Thread (end-to-end validation)**
- [x] PHASE2-01: Android-side dual-broadcast infrastructure — Create `WatchNotifier` interface, extract existing Garmin BLE push into `GarminNotifier`, implement `WearOsNotifier` using Wearable Data Layer API (`DataClient` / `PutDataMapRequest`). Wire both notifiers into `CommutePipeline` so every poll broadcasts to all connected watch types. No-op gracefully when a watch type is not paired. Android-only — no Wear OS module yet.
- [ ] PHASE2-02: Wear OS steel thread — receive + display raw status — Create `wear/` Gradle module (min Wear OS 3 / API 30). Implement `WearableListenerService` to receive `CommuteStatus` via `DataClient`. Minimal Main Activity shows one-word action status + "X min ago" timestamp. Validates the full pipeline: phone poll → DataClient → watch display. Test on Wear OS emulator.

**Wear OS UI**
- [ ] PHASE2-03: Wear OS Tile — ProtoLayout glanceable status — Build a Tile using `androidx.wear.protolayout`. Title slot: action word in tier color. Main slot: circular MTA route badges. Bottom slot: `reroute_hint` (prioritized) or `summary` with `maxLines` ellipsis. Tap anywhere launches Main Activity.
- [ ] PHASE2-04: Wear OS Main Activity — polished detail view — Compose for Wear OS with `ScalingLazyColumn`. Full information hierarchy: action → route badges → timestamp → reroute hint (tier-colored) → summary. Scrollable, no truncation.

**Hardening**
- [ ] PHASE2-05: Dual-broadcast integration testing — Verify: Garmin-only paired, Wear OS-only paired, both paired simultaneously, neither paired, reconnection after disconnect. Ensure no crashes, no silent failures, no duplicate sends.

### Out of Scope
- **Monetization:** Deferred until product-market fit is validated. See `docs/monetization-plan.md`.

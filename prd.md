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
- `WearOsNotifier` uses `DataClient.putDataItem()` with `PutDataMapRequest` at `/commute-status`; includes a `sent_at` timestamp to ensure `onDataChanged()` fires even for identical consecutive payloads; `putDataItem` success does NOT signal watch connectivity (it only confirms local datastore write) — "Wear OS connected" status is driven solely by `checkConnected()` querying reachable nodes
- Both notifiers wired into `PollingForegroundService` and `MainActivity`; every poll broadcasts to all watch types simultaneously
- Failure isolation: a throwing notifier is caught and logged; remaining notifiers always execute

### Garmin Watch App
- **Glance:** Two-line color-coded status — action text above (NORMAL green, MINOR_DELAYS yellow, REROUTE red, STAY_HOME gray), absolute last-update time below in `FONT_XTINY` light gray (e.g., "1:28pm"). MINOR_DELAYS/REROUTE show each route letter in its MTA trunk-line color. Both lines are vertically centered as a group. Falls back to single-line layout when no timestamp is available
- **Detail view:** Native `ViewLoop` paged navigation. Page 1: action title → colored route badges → timestamp (FONT_XTINY) → reroute hint (action-tier color). Summary text follows in white. When the hint fills the screen, page 1 is header-only and summary starts on page 2. Deterministic word-boundary pagination via `fitTextToArea()`
- **BLE schema:** `action` (string), `summary`, `affected_routes`, `reroute_hint` (optional), `timestamp` (long) — documented in `shared/schema.json`

### Wear OS Watch App
- **Tile (glanceable):** ProtoLayout tile in the watch carousel. Three slots: `titleSlot` shows action label in tier color (bold) + relative timestamp in gray; `mainSlot` shows summary or reroute hint (12sp, up to 4 lines, ellipsis, tier-colored for hints / light gray for summary); `bottomSlot` shows MTA route badges (18dp circles) arranged in centered rows of 4, wrapping to a second row for 5+ routes. Entire tile is tappable and launches MainActivity. Shows a "Waiting for data…" placeholder when no data exists. Refreshes automatically whenever `CommuteStatusListenerService` receives new data.
- **Main Activity (detail view):** Compose for Wear OS with `ScalingLazyColumn`. Full information hierarchy: action label (tier color, bold, 18sp) → MTA route badges (24dp colored circles, `FlowRow` wrapping for 5+ routes) → relative timestamp (gray) → reroute hint (tier color, only when present) → full summary text (light gray, no truncation). "Waiting for data…" placeholder when no status received. Scrollable — handles 800+ char weekend planned work summaries.
- **Data reception:** `CommuteStatusListenerService` extends `WearableListenerService`, receives `onDataChanged()` for `/commute-status`, extracts all `CommuteStatus` fields from `DataMap`, persists to `StatusStore`, and calls `TileService.getUpdater()` to push a tile refresh
- **`StatusStore`:** Kotlin object wrapping SharedPreferences + `StateFlow<CommuteStatusSnapshot?>`. Survives process death. `init()` always re-syncs from SharedPreferences on Activity start (so clearing app data resets to the placeholder state correctly)
- **Timestamp note:** `CommuteStatus.timestamp` is stored in seconds (Unix epoch); the watch app multiplies by 1000 before computing relative time

## Technical Architecture

### Tech Stack

- **Android App:** Kotlin, `minSdk = 34` (Android 14), Garmin Connect IQ Mobile SDK `2.3.0`, `com.google.android.gms:play-services-wearable`
- **Wear OS App:** Kotlin, `minSdk = 30` (Wear OS 3 / API 30 — Pixel Watch 1st gen compatible), Compose for Wear OS (`androidx.wear.compose:compose-material`, `compose-foundation`), `com.google.android.gms:play-services-wearable`. Same `applicationId` as the phone app so the Wearable Data Layer auto-pairs.
- **AI Decision Engine:** Gemini Flash via **Firebase AI Logic SDK** (`com.google.firebase:firebase-ai`, BoM 34.10.0). Model: `gemini-flash-latest` (Gemini 3 Flash Preview); `temperature=0`, `ThinkingLevel.LOW`. API key managed by Firebase (`google-services.json`). Model name configurable via `local.properties` (`GEMINI_MODEL_NAME`).
- **API Security:** **Firebase App Check** (`firebase-appcheck-playintegrity` for release builds, `firebase-appcheck-debug` for debug/emulator). The Firebase-managed Android API key is restricted in Google Cloud Console to the `com.commutebuddy.app` package + debug/release SHA-1 fingerprints, and scoped to Firebase AI Logic API, Firebase App Check API, Firebase Installations API, and Firebase Management API only.
- **Garmin Watch App:** Monkey C, Connect IQ SDK `8.4.1`, target: Garmin Venu 3 (`venu3`)
- **Communication:** BLE via Connect IQ Mobile SDK (`IQConnectType.WIRELESS`)
- **Data Source:** MTA GTFS-RT subway alerts — `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json` (unauthenticated)
- **Build Tools:** Android Studio (Gradle/Kotlin DSL), VS Code with Connect IQ extension (Monkey C compiler)

### System Design

**Monorepo** — both apps are tightly coupled through the BLE message schema.

- **Android app** (`android/`): `MainActivity.kt` initializes `GarminNotifier` and `WearOsNotifier`, manages manual fetch direction, API usage display, and watch connection status text. `CommutePipeline.run()` encapsulates the full pipeline: `MtaAlertFetcher` (HTTP GET) → `MtaAlertParser` (parse + route filter + active period filter + prompt text builder) → Gemini Flash decision engine → `CommuteStatus.fromJson()` → display + broadcast. After a successful result, `notifyAll()` (package-level function in `WatchNotifier.kt`) broadcasts to all registered `WatchNotifier` implementations with per-notifier failure isolation. `SystemPromptBuilder` generates the system prompt dynamically from the saved `CommuteProfile`. `PollingForegroundService` runs the same pipeline on `AlarmManager` exact-alarm schedule with `PARTIAL_WAKE_LOCK` and calls `notifyAll()` after each poll. Direction is resolved automatically from the active window index (0→TO_WORK, 1→TO_HOME) with SharedPreferences fallback.
- **Garmin app** (`garmin/`): `CommuteBuddyApp.mc` receives and validates BLE payloads, stores fields in `Application.Storage`. `CommuteBuddyGlanceView.mc` renders color-coded one-line status. Detail view uses `ViewLoop` + `DetailPageFactory` for native paged navigation with dynamic layout measurement.
- **Wear OS app** (`android/wear/`): `CommuteStatusListenerService` receives `/commute-status` data items via `WearableListenerService.onDataChanged()`, extracts fields from `DataMap`, persists to `StatusStore` (SharedPreferences + `StateFlow`), and triggers a tile refresh via `TileService.getUpdater()`. `MainActivity` observes the flow via `collectAsState()` and renders the full detail view: action label (tier color) → `MtaRouteBadges` (FlowRow of 24dp circular badges, only when routes present) → relative timestamp → reroute hint (tier color, conditional) → full summary (no truncation), all in a `ScalingLazyColumn`. `CommuteTileService` extends `TileService` and builds a ProtoLayout tile with action/timestamp in `titleSlot`, summary/hint text in `mainSlot`, and route badges in `bottomSlot`; the entire tile is wrapped in a clickable `Box` that launches `MainActivity`. `MtaLineColors` provides the same trunk-line color mapping as the phone app.
- Apps share a BLE message schema (`shared/schema.json`) but no source code.

### Key Files

```
commute-buddy/
├── android/                                        # Open in Android Studio
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── app/
│       ├── build.gradle.kts                        # minSdk 34, Connect IQ SDK, Firebase AI Logic SDK (BoM 34.10.0)
│       ├── google-services.json                    # Firebase project config (gitignored)
│       └── src/main/
│           ├── AndroidManifest.xml                 # BLUETOOTH_SCAN, BLUETOOTH_CONNECT, SCHEDULE_EXACT_ALARM, WAKE_LOCK, FOREGROUND_SERVICE_CONNECTED_DEVICE
│           ├── kotlin/com/commutebuddy/app/
│           │   ├── CommuteBuddyApplication.kt      # Custom Application class: initializes FirebaseAppCheck before any other code runs (PlayIntegrity for release, Debug provider for debug/emulator builds)
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
│               ├── WatchNotifierOrchestratorTest.kt    # Orchestrator: empty list, all throwing, failure isolation, all ACTION_* variants, rerouteHint null/present
│               └── WearOsNotifierTest.kt               # buildDataMap: all fields, reroute_hint absent when null, every ACTION_* variant, field values exact
│   └── wear/
│       ├── build.gradle.kts                        # minSdk 30, Compose for Wear OS, play-services-wearable
│       └── src/main/
│           ├── AndroidManifest.xml                 # WAKE_LOCK; WearableListenerService with DATA_CHANGED filter
│           └── kotlin/com/commutebuddy/wear/
│               ├── MainActivity.kt                 # Compose detail view: ScalingLazyColumn with action, MtaRouteBadges (FlowRow), timestamp, hint, summary; observes StatusStore flow
│               ├── StatusStore.kt                  # SharedPreferences + StateFlow<CommuteStatusSnapshot?>; always re-syncs on init()
│               ├── CommuteStatusListenerService.kt # WearableListenerService: receives /commute-status DataMap, persists via StatusStore, triggers tile refresh
│               ├── CommuteTileService.kt           # ProtoLayout TileService: titleSlot (action+timestamp), mainSlot (summary/hint text), bottomSlot (route badges); full-tile tap → MainActivity
│               └── MtaLineColors.kt                # MTA trunk-line color map (same groups as phone app); lineColor() + isLightBackground()
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
│       ├── DiagnosticsPageView.mc                  # Diagnostic data dump page (BUG-12); reads diag_* Storage keys
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
GRADLE=$(ls /c/Users/blure/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle 2>/dev/null | head -1)
cd "A:/Phil/Phil Docs/Development/commute-buddy/android"
"$GRADLE" :app:testDebugUnitTest   # run unit tests
"$GRADLE" :app:assembleDebug       # build phone APK
"$GRADLE" :wear:assembleDebug      # build Wear OS APK
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

**API Key Security:** The Firebase-managed Android API key is locked down in Google Cloud Console: restricted to `com.commutebuddy.app` package + debug/release SHA-1 fingerprints, and scoped to Firebase AI Logic API, Firebase App Check API, Firebase Installations API, and Firebase Management API. Firebase App Check (Play Integrity) is enforced at the Firebase backend for release builds — unauthorized callers (wrong package, missing attestation) are rejected server-side before any Gemini quota is consumed. Debug builds use `DebugAppCheckProviderFactory` so emulators and test devices still work. Known limitation: the API key is still client-side; server-side migration (calling Gemini from a backend) is the only 100% secure architecture.

**Gemini Flash:** The free Firebase AI tier is sufficient for personal use. Response time is ~5 seconds with `ThinkingLevel.LOW` (vs 30-60s without). Note: `gemini-2.0-flash` was deprecated and retired March 3, 2026. The `ApiRateLimiter`'s persisted 50/day cap makes runaway costs virtually impossible.

**MTA Alert Text Characteristics:** Alerts vary dramatically: short (~100 chars, real-time delays), medium (~500-600 chars, reroutes), long (800-1500+ chars, weekend planned work with suspensions/shuttles/ADA notices). **Direction is only in free text** — `direction_id` is NEVER populated in `informed_entity` (validated against 202 live alerts). Direction appears as "Manhattan-bound", "Queens-bound", "Downtown", "Uptown", etc. Gemini Flash matches these against commute leg directions natively.

**Garmin Memory Limits:** Monkey C apps have ~32KB memory for background/glance. Never parse protobuf or JSON on the watch. Keep BLE payload under 1KB. All heavy lifting happens on Android.

**Garmin SDK Caution:** LLMs frequently hallucinate Monkey C syntax or use deprecated methods. Always verify against latest Connect IQ SDK docs. Key gotchas: use `getDeviceStatus()` not `getStatus()`, use `IQDevice.IQDeviceStatus` not `ConnectIQ.IQDeviceStatus`, `dc.drawWrappedText()` does not exist (use `WatchUi.TextArea`), `import Toybox.Lang` is required in `:glance`-annotated files. See `docs/garmin/` for detailed notes.

**`org.json` in unit tests:** `org.json.JSONObject` is a stub in the Android JVM test environment. Any class using `org.json` in production needs `testImplementation("org.json:json:20250107")` in `build.gradle.kts`.

### Testing Strategy

**Garmin UI & Logic (Simulator):** Connect IQ Device Simulator in VS Code. No USB sideloading needed.

**Garmin BLE Integration (Hardware):** Deploy APK to phone, sideload `.prg` to Garmin Venu 3 via USB. Test live BLE via `IQConnectType.WIRELESS`.

**Wear OS:** Wear OS emulator for tile and activity validation (ADB port forwarding required for Data Layer connectivity to physical phone). Manual hardware verification matrix: Garmin-only, Wear OS-only, both paired, neither paired, reconnect-after-disconnect.

## Backlog

### Phase I (Complete)
All Phase I features and bugs are resolved. See `docs/phase1-changelog.md` for the full historical record.

### Phase II: Wear OS Expansion (Complete)

#### What Was Built
Phase II added Wear OS as a second supported watch platform. A "steel thread" approach was used — validating the full data pipeline end-to-end before investing in polished UI. All five stories shipped and are complete.

#### Architectural Decisions

**1. Android App: Dual-Broadcasting**
- `WatchNotifier` interface abstracts watch communication; `GarminNotifier` and `WearOsNotifier` are registered at service init
- `notifyAll()` broadcasts to all notifiers with per-notifier failure isolation; unavailable watch types no-op gracefully
- `DataClient` via `PutDataMapRequest` — syncs latest status even after temporary disconnection; `sent_at` timestamp ensures `onDataChanged()` always fires
- Watch connection status ("Wear OS connected") is driven solely by `checkConnected()` querying reachable nodes — `putDataItem` success is not a connectivity signal

**2. Wear OS App: Compose & ProtoLayout**
- Standalone `wear/` Gradle module in the monorepo, min Wear OS 3 (API 30) — compatible with Pixel Watch 1st gen
- Two surfaces: Tile (ProtoLayout, system-rendered) and Main Activity (Compose for Wear OS)

**3. Wear OS Tile**
- Built using **ProtoLayout API** (Tiles cannot use Compose and cannot scroll)
- `titleSlot`: action label in tier color + relative timestamp. `mainSlot`: summary or reroute hint (12sp, 4 lines, ellipsis). `bottomSlot`: MTA route badges (18dp, rows of 4, wrapping). Full-tile tap launches Main Activity.

**4. Wear OS Main Activity**
- **Compose for Wear OS** with `ScalingLazyColumn` — full information hierarchy, natively scrollable, no truncation

#### Phase II Backlog

**Steel Thread (end-to-end validation)**
- [x] PHASE2-01: Android-side dual-broadcast infrastructure — Create `WatchNotifier` interface, extract existing Garmin BLE push into `GarminNotifier`, implement `WearOsNotifier` using Wearable Data Layer API (`DataClient` / `PutDataMapRequest`). Wire both notifiers into `CommutePipeline` so every poll broadcasts to all connected watch types. No-op gracefully when a watch type is not paired. Android-only — no Wear OS module yet.
- [x] PHASE2-02: Wear OS steel thread — receive + display raw status — Create `wear/` Gradle module (min Wear OS 3 / API 30). Implement `WearableListenerService` to receive `CommuteStatus` via `DataClient`. Minimal Main Activity shows one-word action status + "X min ago" timestamp. Validates the full pipeline: phone poll → DataClient → watch display. Test on Wear OS emulator.

**Wear OS UI**
- [x] PHASE2-03: Wear OS Tile — ProtoLayout glanceable status — Build a Tile using `androidx.wear.protolayout`. Title slot: action word in tier color + timestamp. Main slot: summary/hint text (4 lines, 12sp). Bottom slot: route badges (18dp, rows of 4). Tap anywhere launches Main Activity.
- [x] PHASE2-04: Wear OS Main Activity — polished detail view — Compose for Wear OS with `ScalingLazyColumn`. Full information hierarchy: action → route badges → timestamp → reroute hint (tier-colored) → summary. Scrollable, no truncation.

**Hardening**
- [x] PHASE2-05: Dual-broadcast integration testing — Verify: Garmin-only paired, Wear OS-only paired, both paired simultaneously, neither paired, reconnection after disconnect. Ensure no crashes, no silent failures, no duplicate sends.

### Enhancements

- [x] FEAT-15: Garmin Glance — show last-update timestamp. Display the absolute time of the last update (e.g., "1:28pm") in tiny grey font below the action title and affected routes. Motivated by BUG-12: the glance crash resilience fix means the glance recovers silently from crashes, but the user can't tell whether they're seeing a live status or a stale snapshot from before the last crash. An absolute timestamp (not relative "5 min ago") lets the user judge freshness at a glance — if it says "1:28pm" and it's now 1:45pm, they know to tap through to the detail view.

### Out of Scope
- **Monetization:** Deferred until product-market fit is validated. See `docs/monetization-plan.md`.


**BUGS**
- [x] BUG-10: Tile picker showed grey circle and MTA vector instead of app icon/preview.
- [x] BUG-11: Android ConnectIQ SDK singleton conflict — background polls fail to send to Garmin after Activity is opened/closed. See `plan.md` for full context and implementation plan.
- [ ] BUG-12: Garmin Glance goes blank (icon only, no text) intermittently after 1-2 days of runtime. Three fix attempts so far, all recurred. See `docs/bug-12-garmin-glance-crash.md` for full investigation history.
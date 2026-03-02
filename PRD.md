# Commute Buddy (Subway Watch App)

## Problem

Living in Astoria, commuting to work requires constantly pulling up the MTA app to check for train issues. This is done to pre-emptively re-route and avoid getting stuck on the platform. Checking in a rush is painful, and checking underground is unreliable due to spotty cell service and slow MTA REST APIs. Knowing the status of the trains *before* leaving the apartment is critical for deciding whether to commute or work from home.

## Approach

A two-part system: an **Android Companion App** (the "brains") and a **Garmin Connect IQ App/Glance** (the "face").

The Android app runs in the background during a configurable commute window. It fetches the full MTA GTFS-RT protobuf feed over cellular/Wi-Fi, deserializes and filters it down to only the user's configured routes, then passes the filtered alert text to Google's on-device **Gemini Nano** model (via Android AICore / ML Kit GenAI) for summarization into a strict JSON payload. This payload is pushed to the Garmin watch via Bluetooth Low Energy (BLE).

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

## Technical Architecture

### Tech Stack

- **Android App:** Kotlin, `minSdk = 34` (Android 14), Garmin Connect IQ Mobile SDK `2.3.0` (`com.garmin.connectiq:ciq-companion-app-sdk`)
- **AI Summarization:** Google Gemini Nano via Android AICore / ML Kit GenAI — on-device inference, no network required for summarization
- **Garmin Watch App:** Monkey C, Connect IQ SDK `8.4.1`, Toybox.Communications, Toybox.Application.Storage
- **Target Device:** Garmin Venu 3 (`venu3`)
- **Communication:** Bluetooth Low Energy via Connect IQ Mobile SDK (`IQConnectType.WIRELESS`); minimized JSON payload, well under 1KB limit
- **Build Tools:** Android Studio (Gradle/Kotlin DSL), VS Code with Connect IQ extension (Monkey C compiler)
- **Data Sources:** MTA GTFS-RT subway alerts via `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json` (unauthenticated, no API key; also available as protobuf without `.json` suffix). Google Maps Routes API (transit mode, future).

### System Design

**Monorepo** structure — both apps are tightly coupled through the BLE message schema.

- **Android app** (`android/`): `MainActivity.kt` initializes the Connect IQ SDK on launch, discovers the paired Garmin device, verifies the watch app is installed, and sends a payload via `ConnectIQ.sendMessage()`. Currently triggered by explicit button press; future stories will move this to a Foreground Service with scheduled polling. The data pipeline is: fetch full GTFS-RT protobuf → deserialize with protobuf-java → filter to user's configured routes → extract alert text → pass filtered text to on-device Gemini Nano for summarization into a strict JSON schema (`status`, `route_string`, `reason`, `timestamp`) → validate/deserialize → transmit to watch. Gemini Nano never sees the raw protobuf or the full feed — only pre-filtered, route-specific alert text.
- **Garmin app** (`garmin/`): `CommuteBuddyApp.mc` registers for phone messages in `onStart()` via `Communications.registerForPhoneAppMessages()`. On message receipt, stores data in `Application.Storage` and calls `WatchUi.requestUpdate()`. `CommuteBuddyGlanceView.mc` reads from Storage on every `onUpdate()` — renders status or "Waiting..." as fallback.
- Apps do not share source code; they share a BLE message schema documented in `shared/schema.json`.

### Key Files

```
commute-buddy/
├── android/                                        # Open in Android Studio
│   ├── build.gradle.kts                            # Root Gradle config
│   ├── settings.gradle.kts
│   └── app/
│       ├── build.gradle.kts                        # minSdk 34, Connect IQ SDK dep
│       └── src/main/
│           ├── AndroidManifest.xml                 # BLUETOOTH_SCAN, BLUETOOTH_CONNECT permissions
│           ├── java/com/example/commutebuddy/
│           │   └── MainActivity.kt                 # SDK init, device discovery, send logic, status UI
│           └── res/
│               ├── layout/activity_main.xml        # Button + code TextView + status TextView
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
│   └── mta-feed-research.md                        # MTA GTFS-RT feed URLs, JSON structure, alert text examples & length tiers
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
- `Shift+F10` (Run) — build and install APK on connected device or emulator
- Gradle sync required after any `build.gradle.kts` change

### Technical Notes

**Android Permissions (14+):** FOREGROUND_SERVICE_DATA_SYNC or FOREGROUND_SERVICE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT.

**Gemini Nano (on-device AI):** Summarization runs entirely on-device via Android AICore — no network call for the LLM step. Requires a Pixel 8+ or equivalent device with AICore support. Gemini Nano has a limited context window, so it must never receive the full GTFS-RT feed. The Android preprocessing pipeline (protobuf deserialization → route filtering → text extraction) reduces the input to a small, route-specific alert string before the model sees it. The model receives this filtered text plus a system prompt and returns a strict JSON object. Output must be validated/deserialized before transmission.

**Garmin Memory Limits:** Never parse MTA protobuf or JSON on the watch. Monkey C apps have ~32KB memory for background/glance. Keep BLE payload under 1KB. All heavy lifting (protobuf parsing, AI summarization) happens on Android.

**MTA Alert Text Characteristics:** Real MTA GTFS-RT alerts vary dramatically in length and complexity. Each alert has a `header_text` (plain text, `language: "en"`) and an optional `description_text`. Alerts use bracket notation for routes (`[A]`, `[4]`, `[shuttle bus icon]`, `[accessibility icon]`) and structured sections ("What's happening?", "Travel Alternatives:", "ADA Customers:"). Short alerts (real-time delays) are ~100 chars with no description. Medium alerts (single reroute) are ~500-600 chars. Long alerts (weekend planned work with suspensions, shuttle buses, multi-line transfers, ADA notices) are 800-1500+ chars. Weekend construction alerts affecting multiple lines can be significantly longer. The preprocessing pipeline (FEAT-03) must filter by `informed_entity.route_id` and extract the `en` plain-text translation before passing to Gemini Nano.

**Garmin SDK Caution:** Monkey C and Connect IQ SDK update frequently. LLMs often hallucinate syntax or use deprecated methods. Always verify against latest Toybox.Communications and UI docs.

### Testing Strategy

**Phase 1 — UI & Logic (Simulator):** Develop Garmin Glance UI/logic using Connect IQ Device Simulator in VS Code. No USB sideloading.

**Phase 2 — BLE Integration (Hardware):** Deploy Android APK to phone, sideload `.prg` to Garmin Venu 3 via USB. Test live BLE connection using IQConnectType.WIRELESS.

## Backlog

### Features
- [x] FEAT-01: Steel Thread — Phone generates random 4-digit code, watch displays it (validates build env, BLE, and background execution)
- [ ] FEAT-02: AI Summarization POC — Validate on-device Gemini Nano can reliably parse MTA alert text into the strict JSON schema
- [ ] FEAT-03: MTA GTFS-RT data fetching, protobuf parsing, and route filtering on Android (preprocessing pipeline that feeds Gemini Nano; routes/direction hardcoded initially)
- [ ] FEAT-04: Route status summary generation and BLE push to watch
- [ ] FEAT-05: Garmin Glance UI displaying train status from BLE messages
- [ ] FEAT-06: Configurable commute window with scheduled background polling
- [ ] FEAT-07: Dynamic TTL via Google Maps Routes API for auto-shutdown
- [ ] FEAT-08: Route & direction configuration — UI to select monitored lines, commute direction (to/from work), and alternate routes for rerouting suggestions

### Bugs
{None yet — add as discovered.}
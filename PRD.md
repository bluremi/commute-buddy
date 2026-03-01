# Commute Buddy (Subway Watch App)

## Problem

Living in Astoria, commuting to work requires constantly pulling up the MTA app to check for train issues. This is done to pre-emptively re-route and avoid getting stuck on the platform. Checking in a rush is painful, and checking underground is unreliable due to spotty cell service and slow MTA REST APIs. Knowing the status of the trains *before* leaving the apartment is critical for deciding whether to commute or work from home.

## Approach

A two-part system: an **Android Companion App** (the "brains") and a **Garmin Connect IQ App/Glance** (the "face").

The Android app runs in the background during a configurable commute window. It fetches unauthenticated MTA GTFS-RT protobuf data over cellular/Wi-Fi, parses the status of specific routes (e.g., N/W trains), and pushes a lightweight summary string to the Garmin watch via Bluetooth Low Energy (BLE).

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

{Not yet built — will be filled in as development progresses.}

## Technical Architecture

### Tech Stack

- **Android App:** Kotlin, Android Foreground Service, Garmin Connect IQ Mobile SDK
- **Garmin Watch App:** Monkey C, Connect IQ SDK, Toybox.Communications
- **Data Sources:** MTA GTFS-Realtime feeds (unauthenticated, protobuf), Google Maps Routes API (transit mode)
- **Communication:** Bluetooth Low Energy via Connect IQ Mobile SDK
- **Build Tools:** Android Studio (Gradle/Kotlin), VS Code (Connect IQ extension, Monkey C compiler)

### System Design

**Monorepo** structure — both apps are tightly coupled through BLE message format.

- **Android app** handles: MTA protobuf fetch/parse → status summary → BLE push to watch. Runs as a Foreground Service during commute windows. Dynamic TTL via Google Maps Routes API + AlarmManager.
- **Garmin app** handles: BLE message listener → Application.Storage cache → UI render. Built as a Glance for instant access from the watch's scrollable menu.
- Apps do not share source code; they share a BLE message schema.

### Key Files

```
commute-buddy/
├── android/               # Open in Android Studio
│   ├── app/
│   ├── build.gradle.kts
│   └── src/main/java/...  # Kotlin foreground service & BLE logic
├── garmin/                # Open in VS Code
│   ├── monkey.jungle
│   ├── manifest.xml
│   └── source/            # Monkey C UI & BLE listener logic
├── shared/                # (Optional)
│   └── schema.json        # Documentation of BLE JSON payload
├── PRD.md
├── plan.md
└── CLAUDE.md
```

### Commands

{Will be filled in once projects are scaffolded.}

### Technical Notes

**Android Permissions (14+):** FOREGROUND_SERVICE_DATA_SYNC or FOREGROUND_SERVICE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT.

**Garmin Memory Limits:** Never parse MTA protobuf on the watch. Monkey C apps have ~32KB memory for background/glance. Keep BLE payload under 1KB.

**Garmin SDK Caution:** Monkey C and Connect IQ SDK update frequently. LLMs often hallucinate syntax or use deprecated methods. Always verify against latest Toybox.Communications and UI docs.

### Testing Strategy

**Phase 1 — UI & Logic (Simulator):** Develop Garmin Glance UI/logic using Connect IQ Device Simulator in VS Code. No USB sideloading.

**Phase 2 — BLE Integration (Hardware):** Deploy Android APK to phone, sideload `.prg` to Garmin Venu 3 via USB. Test live BLE connection using IQConnectType.WIRELESS.

## Backlog

### Features
- [ ] FEAT-01: Steel Thread — Phone generates random 4-digit code, watch displays it (validates build env, BLE, and background execution)
- [ ] FEAT-02: MTA GTFS-RT data fetching and protobuf parsing on Android
- [ ] FEAT-03: Route status summary generation and BLE push to watch
- [ ] FEAT-04: Garmin Glance UI displaying train status from BLE messages
- [ ] FEAT-05: Configurable commute window with scheduled background polling
- [ ] FEAT-06: Dynamic TTL via Google Maps Routes API for auto-shutdown
- [ ] FEAT-07: Multi-route support (configure which train lines to monitor)

### Bugs
{None yet — add as discovered.}
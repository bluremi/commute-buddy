# Active Development

## FEAT-01: Steel Thread — Phone Sends Code, Watch Displays It

### Description
As a developer, I want the Android app to generate a random 4-digit code and send it to the Garmin watch via BLE, so that I can validate the entire build toolchain, project scaffolding, BLE communication pipeline, and Garmin Glance rendering before building real features on top.

This is the foundational "steel thread" — the thinnest possible vertical slice that touches every layer of the system (Android app → Connect IQ Mobile SDK → BLE → Garmin Glance UI). Getting this working proves that both projects build, communicate, and display correctly. Every subsequent feature (MTA data, commute windows, TTL) builds on top of this proven pipeline.

### Acceptance Criteria

1. **Android Project Scaffolds and Builds**
   - A Kotlin Android project exists under `android/` with Gradle build files
   - The project includes the Connect IQ Mobile SDK dependency (`ciq-companion-app-sdk` AAR via Maven Central)
   - The project compiles and can be installed on a physical Android device or emulator
   - Minimum SDK targets Android 14+ with required permissions declared (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`)

2. **Garmin Project Scaffolds and Builds**
   - A Monkey C project exists under `garmin/` with `manifest.xml` and `monkey.jungle`
   - The project targets the Venu 3 device and declares `Communications` permission
   - The project compiles via the Connect IQ SDK compiler and produces a `.prg` file
   - The Glance entry point is implemented (`AppBase.getGlanceView()` returns a `GlanceView`)

3. **Android App Generates and Sends a 4-Digit Code**
   - On launch (or on a button press), the Android app generates a random 4-digit numeric code (1000–9999)
   - The app initializes the Connect IQ SDK (`ConnectIQ.getInstance` with `IQConnectType.WIRELESS`)
   - The app discovers the paired Garmin device, verifies the watch app is installed, and sends the code using `ConnectIQ.sendMessage()`
   - The current code and send status are displayed in the Android UI

4. **Garmin Glance Receives and Displays the Code**
   - The watch app registers for phone messages using `Communications.registerForPhoneAppMessages()`
   - When a message arrives, the code is stored in `Application.Storage` for persistence across Glance lifecycles
   - The Glance renders the code as large, centered text on a single line (e.g., "Code: 7342")
   - If no code has been received yet, the Glance displays a placeholder (e.g., "Waiting...")

5. **BLE Message Schema Is Documented**
   - A `shared/schema.json` file documents the BLE message format used between the apps
   - For this steel thread, the payload is a simple integer (the 4-digit code) — no JSON/dictionary overhead needed
   - The payload is well under the 1KB BLE limit

6. **Disconnection and Error States Are Handled Gracefully**
   - If the watch is not connected, the Android app shows a clear "Watch not connected" status
   - If the Connect IQ app is not installed on the watch, the Android app indicates this
   - If BLE send fails, the Android app shows the failure status (does not crash)
   - The Garmin Glance never crashes — it shows "Waiting..." until a valid code arrives

7. **Simulator-Testable (Phase 1)**
   - The Garmin Glance UI and message handling can be verified using the Connect IQ Device Simulator in VS Code
   - BLE communication can be tested via ADB bridge between Android device/emulator and the simulator (`IQConnectType.TETHERED` / ADB port forwarding on 7381)

### Out of Scope
- MTA data fetching or protobuf parsing — this is just a random code
- Foreground Service or background polling — the Android app sends on explicit user action only
- Commute window scheduling or TTL logic
- Multi-device support — assumes a single paired Garmin device
- Connect IQ Store publishing or production signing
- Full watch app UI beyond the Glance (no menus, settings, or multi-page views)
- iOS companion app

### Implementation Plan

#### Increment 1: Garmin Project Scaffold + Glance with Static Text ✓
- [x] Create `garmin/` directory with `manifest.xml` (target Venu 3, declare `Communications` permission, set app UUID)
- [x] Create `monkey.jungle` build file referencing the Venu 3 device
- [x] Create `garmin/source/CommuteBuddyApp.mc` — `AppBase` subclass with `getInitialView()` and `getGlanceView()` returning the GlanceView
- [x] Create `garmin/source/CommuteBuddyGlanceView.mc` — `GlanceView` subclass that renders "Waiting..." centered using `FONT_GLANCE` in `onUpdate(dc)`
- [x] Create a minimal main view (`CommuteBuddyView.mc`) so `getInitialView()` doesn't crash

**Testing:** Open `garmin/` in VS Code with the Connect IQ extension. Build the project (`Ctrl+Shift+B` or Monkey C: Build). Run in the Connect IQ Simulator targeting Venu 3. Verify the Glance renders "Waiting..." text.
**Model: Sonnet** | Reason: Monkey C scaffolding requires careful syntax — LLMs frequently hallucinate deprecated methods, so this benefits from a model that reasons over the reference docs.

#### Increment 2: Android Project Scaffold + Code Generator UI ✓
- [x] Create `android/` directory with standard Gradle/Kotlin project structure (`build.gradle.kts` for root and `app/` module)
- [x] Configure `app/build.gradle.kts` with `minSdk = 34` (Android 14), Kotlin, and the Connect IQ SDK dependency (`com.garmin.connectiq:ciq-companion-app-sdk:2.3.0@aar`)
- [x] Declare permissions in `AndroidManifest.xml`: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- [x] Create `MainActivity.kt` with a simple layout: a "Send Code" button, a TextView for the generated code, and a TextView for status messages
- [x] On button press, generate a random 4-digit code (1000–9999) and display it in the code TextView

**Testing:** Open `android/` in Android Studio. Sync Gradle, build the project. Install on a physical device or emulator. Tap "Send Code" — verify a random 4-digit number appears. Tap again — verify a different number appears.
**Model: Sonnet** | Reason: Standard Android/Kotlin scaffolding but needs correct Gradle config and SDK dependency wiring.

#### Increment 3: BLE Schema + Android Connect IQ SDK Integration ✓
- [x] Create `shared/schema.json` documenting the steel-thread BLE message format (single integer payload, 1000–9999)
- [x] Add Connect IQ SDK initialization in `MainActivity.kt` — `ConnectIQ.getInstance()` with `IQConnectType.WIRELESS`, implement `ConnectIQListener` callbacks
- [x] Implement device discovery: `getConnectedDevices()`, then `getApplicationInfo()` using the Garmin app's UUID
- [x] Wire "Send Code" button to call `ConnectIQ.sendMessage()` with the generated code as an integer
- [x] Update status TextView with connection state and send result (`SUCCESS`, `FAILURE`, device not found, app not installed)

**Testing:** Build and install on a physical Android device with Garmin Connect Mobile installed. Verify: app launches without crash, SDK initializes (status shows "SDK Ready" or similar), device discovery runs (shows "No device found" if watch isn't paired, or device name if it is). Full send test deferred to after Increment 4.
**Model: Sonnet** | Reason: Async SDK initialization with callbacks and state management requires multi-step reasoning across the Connect IQ Mobile SDK docs.

#### Increment 4: Garmin BLE Receive + Storage + Dynamic Glance Display ✓
- [x] In `CommuteBuddyApp.mc`, register for phone app messages in `onStart()` using `Communications.registerForPhoneAppMessages()`
- [x] In the phone message callback, extract the integer code from `msg.data` and persist it with `Application.Storage.setValue("code", code)`
- [x] Call `WatchUi.requestUpdate()` after storing the code to refresh the Glance
- [x] Update `CommuteBuddyGlanceView.mc` `onUpdate(dc)` to read from `Application.Storage.getValue("code")` — display "Code: XXXX" if a value exists, "Waiting..." if null

**Testing (Phase 1 — simulator only):** Build and run in the Connect IQ Simulator targeting Venu 3. Verify the Glance shows "Waiting..." on launch. Full send testing requires hardware (Phase 2).

**Testing (Phase 2 — hardware):** Sideload the `.prg` to the Venu 3 via USB using the Connect IQ VS Code extension. Install the Android APK on the Pixel. Open the Android app — it should show "Ready (device name)" now that the watch app is installed. Tap "Send Code" and verify the Glance updates to show the sent code.
**Model: Sonnet** | Reason: Wiring BLE receive → Storage → UI refresh requires understanding Monkey C callbacks, Storage API, and Glance lifecycle.

#### Increment 5: Error Handling + Polish
- [ ] Android: show "Watch not connected" when `getConnectedDevices()` returns empty or device status is `NOT_CONNECTED`
- [ ] Android: show "App not installed on watch" when `getApplicationInfo()` returns `NOT_INSTALLED` or `NOT_SUPPORTED`
- [ ] Android: show send failure reason from `IQMessageStatus` when `sendMessage()` fails (don't crash)
- [ ] Garmin: add null/type checks in the phone message callback — ignore messages that aren't a valid integer, never crash
- [ ] Garmin: ensure Glance always renders something — "Waiting..." as fallback if Storage read returns null or unexpected type

**Testing:** Android: test each error path — launch with Bluetooth off (should show connection error, not crash), launch without Garmin Connect Mobile installed (should show init error). Garmin: verify in simulator that Glance never shows a blank screen — always "Waiting..." or a valid code.
**Model: Composer** | Reason: Adding guard clauses and status string updates to existing code paths — mechanical, pattern-following work.

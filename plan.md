# Active Development

## PHASE2-02: Wear OS Steel Thread — Receive + Display Raw Status

### Description
As a commuter with a Wear OS watch, I want to see the latest commute status on my wrist, so that I can validate the full data pipeline (phone poll → DataClient → watch display) before investing in polished Wear OS UI.

This is the second half of the steel thread: PHASE2-01 established the phone-side broadcasting via `WearOsNotifier` and `DataClient.putDataItem()` at `/commute-status`. This story creates a new `wear/` Gradle module that receives that data on the watch side and renders a minimal UI. The goal is risk reduction — proving the Wearable Data Layer integration works end-to-end — not visual polish.

### Acceptance Criteria

1. **Wear OS Gradle Module**
   - A new `wear/` module exists under `android/` and is included in `settings.gradle.kts`
   - `minSdk = 30` (Wear OS 3 — Pixel Watch 1st gen compatible)
   - Uses Compose for Wear OS for the activity UI
   - Module compiles and produces an installable APK (`assembleDebug`)

2. **Data Layer Listener**
   - A `WearableListenerService` subclass receives `onDataChanged()` events for the `/commute-status` path
   - Extracts all `CommuteStatus` fields from the `DataMap`: `action`, `summary`, `affected_routes`, `reroute_hint` (optional), `timestamp`
   - Persists the latest status locally (SharedPreferences or similar) so the UI survives process death
   - Declared in the wear module's `AndroidManifest.xml` with the correct `<intent-filter>` for `com.google.android.gms.wearable.DATA_CHANGED` and `<data>` path filter for `/commute-status`

3. **Minimal Main Activity**
   - Displays the action tier as a single word (e.g., "Normal", "Reroute") in a color matching the tier (green/yellow/red/gray, consistent with Garmin)
   - Displays relative timestamp as "X min ago" (or "just now" for <1 min)
   - Shows a placeholder state (e.g., "Waiting for data…") when no status has been received yet
   - Launches as the default activity when the Wear OS app is opened

4. **Pipeline Validation**
   - The full pipeline works end-to-end: Android phone fetches alerts → runs Gemini → `WearOsNotifier.putDataItem()` → Wear OS `onDataChanged()` → UI updates
   - Testable on the Wear OS emulator (round device profile) paired with the phone app via the Wearable Data Layer emulator bridge

5. **Coexistence with Phone App**
   - The phone `app/` module continues to function independently — no regressions to Garmin notifier, polling service, or existing UI
   - `applicationId` for the wear module uses the same package (`com.commutebuddy.app`) so the Wearable Data Layer auto-pairs phone and watch apps

6. **No Crash on Missing Data**
   - If the data map is missing optional fields (`reroute_hint`), the listener handles this gracefully
   - If the watch app launches before any data has been pushed, the placeholder state appears (no crash, no blank screen)

### Implementation Plan

#### Increment 1: Wear OS Gradle module — skeleton that compiles
- [ ] Create `android/wear/build.gradle.kts` — `com.android.application` + `org.jetbrains.kotlin.android` + Compose for Wear OS plugin. `minSdk = 30`, `targetSdk = 34`, `applicationId = "com.commutebuddy.app"`. Dependencies: `androidx.wear.compose:compose-material`, `androidx.wear.compose:compose-foundation`, `androidx.activity:activity-compose`, `com.google.android.gms:play-services-wearable`
- [ ] Add Kotlin Compose compiler plugin to root `build.gradle.kts` (`org.jetbrains.kotlin.plugin.compose`)
- [ ] Add `include(":wear")` to `android/settings.gradle.kts`
- [ ] Create `android/wear/src/main/AndroidManifest.xml` — minimal manifest with `<uses-feature android:name="android.hardware.type.watch" />`, a placeholder `MainActivity`
- [ ] Create a stub `MainActivity.kt` in `android/wear/src/main/kotlin/com/commutebuddy/wear/` — Compose activity showing "Commute Buddy" text centered on screen
- [ ] Verify module compiles: `"${GRADLE[0]}" :wear:assembleDebug`

**Testing:** Build succeeds with `:wear:assembleDebug`. Install on Wear OS emulator — app opens and shows "Commute Buddy" text.
**Model: Sonnet** | Reason: Wear OS Compose module setup requires correct dependency versions and plugin wiring — easy to get wrong with version mismatches.

#### Increment 2: WearableListenerService — receive and persist status
- [ ] Create `CommuteStatusListenerService.kt` in the wear module — extends `WearableListenerService`, overrides `onDataChanged()`, filters for `/commute-status` path, extracts fields from `DataMap`, persists to SharedPreferences
- [ ] Register in wear `AndroidManifest.xml` with `DATA_CHANGED` intent filter and `/commute-status` path data filter
- [ ] Create `StatusStore.kt` — thin SharedPreferences wrapper to read/write status fields and a "has data" boolean; provides a `Flow<CommuteStatusSnapshot>` for reactive UI updates

**Testing:** Install both phone and wear APKs on emulator pair. Trigger "Fetch Live" on the phone app. Check wear logcat (`adb -s <wear-emulator> logcat -s CommuteStatusListener:V`) for received data. Verify SharedPreferences contains the status fields.
**Model: Sonnet** | Reason: Data Layer listener setup, DataMap extraction, and reactive Flow design need careful attention to lifecycle and threading.

#### Increment 3: Main Activity — display status with tier color + timestamp
- [ ] Update `MainActivity.kt` to observe `StatusStore` flow via `collectAsState()`
- [ ] Render two states: (a) "Waiting for data…" placeholder when no status received, (b) action word in tier color (green=#4CAF50, yellow=#FFD600, red=#F44336, gray=#9E9E9E) + "X min ago" relative timestamp below
- [ ] Relative timestamp logic: <1 min → "just now", 1-59 min → "N min ago", 60+ min → "N hr ago"
- [ ] Ensure UI updates reactively when new data arrives via the listener service

**Testing:** Full pipeline: phone Fetch Live → wear UI updates with colored action word and relative timestamp. Test all four tiers by modifying the phone's commute profile to trigger different MTA scenarios (or use debug menu if available). Verify placeholder state by clearing wear app data and reopening before any phone fetch.
**Model: Sonnet** | Reason: Compose state observation, relative time formatting, and tier color mapping require moderate reasoning.

### Out of Scope
- Tile / ProtoLayout glanceable UI (PHASE2-03)
- Polished detail view with route badges, summary, reroute hint (PHASE2-04)
- MTA line color badges on Wear OS
- Any scrolling or multi-page layout
- Wear OS complications
- Offline/reconnection edge cases (PHASE2-05)

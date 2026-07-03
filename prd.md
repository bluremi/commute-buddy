# Commute Buddy (Subway Watch App)

## Problem

Living in Astoria, commuting means constantly pulling up the MTA app to check for train issues, so I can pre-emptively re-route and avoid getting stuck on the platform. Checking in a rush is painful, and checking underground is unreliable (spotty cell service, slow MTA APIs). The critical need is knowing train status *before leaving the apartment* — to decide whether to commute or work from home.

## Approach

A two-part system: an **Android companion app** (the "brains") and one or more **watch apps** (the "face") — a **Garmin Connect IQ** app/glance (Phase I) and a **Wear OS** app (Phase II).

The Android app polls in the background during configurable commute windows: it fetches the full MTA GTFS-RT alert feed, filters to the user's configured routes (primary legs + alternates), and sends the structured alerts to **Gemini Flash** (via Firebase AI Logic) with a **decision prompt**. The output isn't a status summary but an actionable directive — proceed normally, expect minor delays, reroute (naming which alternates are clear), or stay home. This payload is pushed to paired watches over BLE (Garmin) and the Wearable Data Layer (Wear OS).

When a watch glance/tile is viewed it shows the cached decision instantly — no network, no spinner — sidestepping underground connectivity entirely. Off-schedule, the user can also trigger an immediate poll from the Garmin watch (see On-Demand Poll).

**User journeys:**
- **Pre-commute glance:** while getting ready, a glance shows "Normal", "Minor delays — N,W", "Reroute — N,W", or "Stay home" — an informed decision before putting on a coat.
- **Active commute:** phone keeps polling; once underground the watch still shows the last-known decision instantly.
- **Off-schedule trip:** heading in late or leaving early, the user taps "To Work"/"To Home" on the watch to force a fresh poll without pulling out the phone.

## Current Capabilities

### AI Decision Engine
- Four action tiers — **NORMAL**, **MINOR_DELAYS**, **REROUTE**, **STAY_HOME** — each with a summary (≤80 chars), affected routes, and an optional reroute hint (≤60 chars). Output fits the BLE 1KB limit.
- Commute modeled as **directional legs** (e.g. "N,W Manhattan-bound, Astoria → 59th St"), which prevents false positives from opposite-direction disruptions. MTA feeds never populate `direction_id` in structured data — direction appears only in alert free text ("Manhattan-bound", "Downtown", …), which Gemini matches against leg directions natively.
- Freshness rules: stale alerts (>105 min) are downgraded; overnight planned work outside active periods is ignored.

### MTA Data Pipeline
- Fetches the unauthenticated GTFS-RT subway-alerts JSON feed.
- Filters by `informed_entity.route_id` (against the profile's monitored routes) and by `active_period` (drops standing overnight/weekend advisories during normal service hours).
- Caps `description_text` at ~400 chars and sends structured per-alert blocks with ISO 8601 timestamps to Gemini.

### Background Polling
- `PollingForegroundService` runs as a `connectedDevice` foreground service (Android 15+ compatible; boot-exempt, unlike `dataSync`). `AlarmManager.setExactAndAllowWhileIdle()` (RTC_WAKEUP) + `PARTIAL_WAKE_LOCK` guarantee a CPU wake through Doze.
- Three-tier scheduling: active day + in-window → configurable interval (2–15 min); background ON + off-hours → hourly (or next window start if sooner); background OFF + off-hours → skip to the next active window. Active-days selector (default M–F) avoids weekend/WFH API usage.
- Auto-direction: window 0 → TO_WORK, window 1 → TO_HOME, else last-polled direction.
- **On-demand poll** (`ACTION_POLL_NOW`): a direction-explicit poll triggered off-schedule by a watch tap. Bypasses window/active-day gating but is still serialized by `pollMutex` and counted against the rate limiter, and does not disturb alarm scheduling. Shares its pipeline-run core (`executeDirectionalPoll`) with the scheduled path — direction is the only difference.
- `BootReceiver` auto-starts on boot (guarded by a runtime BT-permission check).
- **Failure handling (BUG-13):** `PipelineResult.Error`/`RateLimited` are logged but never sent, so watches keep their last good decision instead of a raw error string. MTA fetch failures (`IOException`) retry within the same cycle via exponential backoff (~30s → 480s, ±15% jitter), bounded by the next alarm or the 10-min wake-lock window; Gemini/parse errors don't retry.

### Android Companion App
- **Home:** manual direction toggle (Fetch Live only); auto-polling status line; API usage counter (N/60 today); watch-connection status.
- **Commute Profile editor:** define TO_WORK / TO_HOME legs (lines + direction + stations) and alternate lines; 23 MTA lines as color-coded chips in a bottom-sheet picker.
- **Polling Settings:** on/off, two commute windows, active days, interval slider, background-polling toggle.
- **MTA line badges:** color-coded circular badges (9 trunk-line color groups) via `MtaLineColors` + `MtaLineBadgeSpan`.
- **Rate limiter:** persisted 60/day cap, 10/min, 3s cooldown, single-flight mutex, no auto-retry.

### Multi-Watch Broadcasting
- `WatchNotifier` abstracts watch communication; each implementation no-ops when its watch type is unavailable. `notifyAll()` broadcasts to all notifiers with per-notifier failure isolation (a throwing notifier is caught; the rest still run).
- `GarminNotifier` owns all ConnectIQ SDK lifecycle (init, device discovery, app-info load, BLE send); skips init and suppresses the Garmin Connect install dialog when Garmin Connect is absent. It also **receives** watch messages: registers an `IQApplicationEventListener` once the app is confirmed and unregisters on teardown; a valid `POLL_NOW:<DIR>` re-fires `ACTION_POLL_NOW` (reusing the mutex + rate-limiter path), and malformed payloads are ignored silently.
- `WearOsNotifier` uses `DataClient.putDataItem()` at `/commute-status` with a `sent_at` timestamp so `onDataChanged()` fires even for identical payloads. `putDataItem` success is not a connectivity signal — "Wear OS connected" is driven solely by `checkConnected()` querying reachable nodes.

### Garmin Watch App
- **Glance:** two-line color-coded status — action text (tier color) above, absolute last-update time (e.g. "1:28pm", `FONT_XTINY` gray) below; MINOR_DELAYS/REROUTE render each route letter in its trunk-line color. Falls back to one line when no timestamp exists.
- **Detail view:** native `ViewLoop` paging — page 1 shows action title → route badges → timestamp → reroute hint, then white summary text with deterministic word-boundary pagination (`fitTextToArea()`).
- **On-demand poll (FEAT-16):** a horizontal swipe from the status view reveals a "Fetch update" overlay with tappable "To Work" (top) / "To Home" (bottom) halves. A tap transmits `POLL_NOW:<DIR>` to the phone and pops back to status, which **live-refreshes in place** when the fresh decision arrives. (The `ViewLoop` owns the vertical axis, so the overlay rides the free horizontal axis via `DetailPageDelegate.onSwipe` → `pushView`.)

### Wear OS Watch App
- **Tile (ProtoLayout, glanceable):** `titleSlot` = action label (tier color) + relative timestamp; `mainSlot` = summary or reroute hint (12sp, ≤4 lines, ellipsis); `bottomSlot` = MTA route badges (18dp, centered rows of 4, wrapping). Whole tile taps through to the activity; shows "Waiting for data…" when empty; refreshes on new data.
- **Main Activity (Compose, detail):** `ScalingLazyColumn` with the full hierarchy — action label → route badges (24dp, `FlowRow`) → timestamp → reroute hint (conditional) → full summary (no truncation, scrollable for 800+ char weekend advisories).
- **Data path:** `CommuteStatusListenerService` (a `WearableListenerService`) receives `/commute-status`, persists to `StatusStore` (SharedPreferences + `StateFlow`, survives process death), and triggers a tile refresh. `CommuteStatus.timestamp` is Unix seconds; the watch multiplies by 1000 for relative time.

## Technical Architecture

### Tech Stack
- **Android app:** Kotlin, `minSdk 34`. Garmin Connect IQ Mobile SDK `2.3.0`; `play-services-wearable`.
- **Wear OS app:** Kotlin, `minSdk 30` (Wear OS 3 / Pixel Watch 1st-gen). Compose for Wear OS; `play-services-wearable`. Same `applicationId` as the phone app so the Data Layer auto-pairs.
- **AI:** Gemini Flash via **Firebase AI Logic SDK** (`firebase-ai`, BoM 34.10.0). Model `gemini-flash-latest` (Gemini 3 Flash Preview), `temperature=0`, `ThinkingLevel.LOW`. Model name overridable via `local.properties` (`GEMINI_MODEL_NAME`).
- **API security:** **Firebase App Check** (Play Integrity for release, Debug provider for debug/emulator). The Firebase-managed Android API key is restricted in Cloud Console to the `com.commutebuddy.app` package + debug/release SHA-1s, scoped to Firebase AI Logic / App Check / Installations / Management APIs only.
- **Garmin app:** Monkey C, Connect IQ SDK `8.4.1`, target Garmin Venu 3 (`venu3`).
- **Communication:** BLE via the Connect IQ SDK (`IQConnectType.WIRELESS`), **bidirectional** — phone→watch `sendMessage`, watch→phone `Communications.transmit` received on Android via `registerForAppEvents` / `IQApplicationEventListener`.
- **Data source:** MTA GTFS-RT subway alerts — `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json` (unauthenticated).

### System Design

**Monorepo** — the apps are coupled only through the BLE message schema (`shared/schema.json`), not shared source.

- **Android** (`android/app`): `MainActivity` initializes both notifiers and drives manual fetch / status UI. `CommutePipeline.run()` is the pipeline core — `MtaAlertFetcher` → `MtaAlertParser` (parse + route filter + active-period filter + prompt build) → Gemini → `CommuteStatus.fromJson()` → `notifyAll()` (failure-isolated broadcast). `SystemPromptBuilder` builds the system prompt from the saved `CommuteProfile`. `PollingForegroundService` runs the same pipeline on an exact-alarm schedule with a wake lock, resolves direction from the active window, and also handles `ACTION_POLL_NOW` (direction-explicit, schedule-independent) for watch-initiated polls.
- **Garmin** (`garmin/`): `CommuteBuddyApp` validates BLE payloads into `Application.Storage` and, in the foreground full app, rebuilds the `ViewLoop` to live-refresh on new data. `CommuteBuddyGlanceView` renders the one-line status; the detail view is `ViewLoop` + `DetailPageFactory`. The ad-hoc "Fetch update" overlay (`AdHocPageView`/`AdHocPageDelegate`) transmits `POLL_NOW:<DIR>`.
- **Wear OS** (`android/wear`): `CommuteStatusListenerService` receives `/commute-status`, persists to `StatusStore`, and refreshes the tile. `MainActivity` observes the `StateFlow` and renders the Compose detail view; `CommuteTileService` builds the ProtoLayout tile. `MtaLineColors` mirrors the phone's trunk-line color map.

### Key Files

```
commute-buddy/
├── android/                                  # Android Studio project
│   ├── app/src/main/kotlin/com/commutebuddy/app/
│   │   ├── CommuteBuddyApplication.kt        # inits Firebase App Check before anything else
│   │   ├── MainActivity.kt                   # notifier init, manual fetch, watch status, debug menu
│   │   ├── CommutePipeline.kt                # fetch→parse→filter→Gemini→deserialize core
│   │   ├── MtaAlertFetcher.kt / MtaAlertParser.kt   # feed HTTP GET; parse + route/active-period filters + prompt build
│   │   ├── SystemPromptBuilder.kt            # system prompt from CommuteProfile
│   │   ├── CommuteStatus.kt                  # BLE schema; fromJson(); toConnectIQMap()
│   │   ├── CommuteProfile.kt / CommuteLeg.kt / CommuteProfileRepository.kt / CommuteProfileActivity.kt
│   │   ├── PollingSettings.kt / PollingSettingsRepository.kt / PollingSettingsActivity.kt
│   │   ├── PollingForegroundService.kt       # connectedDevice FGS; scheduling; wake lock; ACTION_POLL_NOW; BUG-13 retry/suppression
│   │   ├── BootReceiver.kt                   # BOOT_COMPLETED → startForegroundService (BT-permission guarded)
│   │   ├── ApiRateLimiter.kt                 # 60/day, 10/min, cooldown, single-flight; injectable clock
│   │   ├── WatchNotifier.kt                  # interface + notifyAll() failure-isolated broadcast
│   │   ├── GarminNotifier.kt                 # ConnectIQ send + receive (registerForAppEvents → ACTION_POLL_NOW)
│   │   ├── WearOsNotifier.kt                 # DataClient.putDataItem() to /commute-status
│   │   ├── MtaLineColors.kt / MtaLineBadgeSpan.kt / LinePickerBottomSheet.kt
│   │   └── AndroidManifest.xml               # BT_SCAN/CONNECT, SCHEDULE_EXACT_ALARM, WAKE_LOCK, FGS_CONNECTED_DEVICE
│   │   └── (test/)                           # unit tests for pipeline, parser, scheduling, retry, rate limiter,
│   │                                         #   notifiers, and GarminNotifier POLL_NOW parsing
│   └── wear/src/main/kotlin/com/commutebuddy/wear/
│       ├── MainActivity.kt                   # Compose detail view (ScalingLazyColumn)
│       ├── CommuteTileService.kt             # ProtoLayout tile
│       ├── CommuteStatusListenerService.kt   # WearableListenerService → StatusStore → tile refresh
│       ├── StatusStore.kt                    # SharedPreferences + StateFlow, survives process death
│       └── MtaLineColors.kt
├── garmin/                                   # VS Code + Connect IQ; target venu3, permission Communications
│   └── source/
│       ├── CommuteBuddyApp.mc                # BLE validate/store; foreground live-refresh; phone-listener registration
│       ├── CommuteBuddyGlanceView.mc         # color-coded glance
│       ├── DetailPageFactory.mc / DetailPageView.mc / DetailPagination.mc   # ViewLoop paging + word-boundary chunking
│       ├── DetailPageDelegate.mc             # per-page delegate; horizontal onSwipe → ad-hoc overlay
│       ├── AdHocPageView.mc / AdHocPageDelegate.mc / PollRequestListener.mc # "Fetch update" overlay + transmit
│       └── MtaColors.mc                      # trunk-line colors
├── shared/schema.json                        # BLE message format (phone→watch decision + watch→phone POLL_NOW)
├── docs/                                     # changelog, decision-prompt reference & tests, MTA feed research,
│                                             #   Garmin/Monkey C notes & API refs, plan & investigation docs
├── prd.md · plan.md · CLAUDE.md
```

### Commands

**Garmin (VS Code):** `Ctrl+Shift+B` builds for the simulator; command palette → *Monkey C: Build for Device* → `venu3` builds for hardware. Sideloading the `.prg` to the USB-connected watch is documented in `CLAUDE.md` (the Venu 3 mounts over MTP, so the copy goes through the Windows shell into `Internal Storage/GARMIN/Apps`).

**Android (`gradlew` is not committed — use the cached Gradle binary):**
```bash
GRADLE=$(ls /c/Users/blure/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle 2>/dev/null | head -1)
cd "A:/Phil/Phil Docs/Development/commute-buddy/android"
"$GRADLE" :app:testDebugUnitTest   # unit tests
"$GRADLE" :app:assembleDebug       # phone APK
"$GRADLE" :wear:assembleDebug      # Wear OS APK
```

### Technical Notes
- **Android permissions (14/15):** `FOREGROUND_SERVICE_CONNECTED_DEVICE` is required for the FGS type (and is boot-exempt, unlike `dataSync`). `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` need runtime grants (requested in `MainActivity.onCreate()` before the service starts); `SCHEDULE_EXACT_ALARM` is granted via system settings. `BootReceiver` aborts silently if BT isn't granted.
- **API-key security:** App Check (Play Integrity) is enforced server-side for release builds, so wrong-package/unattested callers are rejected before consuming Gemini quota; debug builds use the Debug provider. The key is still client-side — a backend proxy is the only fully-secure architecture (see `docs/backend-migration-plan.md`).
- **Gemini Flash:** free Firebase tier suffices for personal use; ~5s response with `ThinkingLevel.LOW` (vs 30–60s without). The 60/day rate-limiter cap makes runaway cost effectively impossible.
- **MTA alert text:** ranges from ~100 chars (real-time delays) to 1500+ (weekend planned work). Direction lives only in free text — `direction_id` is never populated (validated against 202 live alerts).
- **Garmin constraints:** ~32KB glance/background memory; keep BLE payloads <1KB and never parse JSON/protobuf on the watch. LLMs frequently hallucinate Monkey C — verify against the SDK docs; key gotchas (e.g. `import Toybox.Lang` in `:glance` files, the vertical `ViewLoop` forwarding off-axis swipes to page delegates) are captured in `docs/garmin/monkeyc-notes.md`.
- **Unit tests:** `org.json.JSONObject` is a stub in the JVM test environment — classes using it need `testImplementation("org.json:json:…")`.

### Testing Strategy
- **Garmin UI/logic:** Connect IQ simulator (`venu3_sim`).
- **Garmin BLE + Wear OS:** hardware — deploy the phone APK, sideload the `.prg`, and verify live over `IQConnectType.WIRELESS` / the Data Layer. Manual matrix: Garmin-only, Wear-only, both, neither, reconnect-after-disconnect.

## Backlog

### Phase I & II (Complete)
Phase I shipped the Android app + Garmin glance/detail view and its bug fixes (see `docs/phase1-changelog.md`). Phase II added Wear OS end-to-end via a "steel thread": dual-broadcast infrastructure (`WatchNotifier`/`notifyAll`), a `WearableListenerService` receiver, the ProtoLayout tile, the Compose detail activity, and the full dual-broadcast hardening matrix (PHASE2-01 → 05, all complete).

### Enhancements
- [x] FEAT-15: Garmin glance shows the absolute last-update time (freshness cue after silent glance-crash recovery).
- [x] FEAT-16: Garmin on-demand poll trigger. Off-schedule, a horizontal swipe on the watch opens a "Fetch update" screen; tapping "To Work"/"To Home" transmits a `POLL_NOW:<DIR>` command (the app's first watch→phone path), the phone runs an immediate direction-explicit poll, and the status view live-refreshes when the result returns.

### Future / Deferred
- [ ] On-demand poll feedback (deferred from FEAT-16): an "Updating…" indicator and a failure affordance on the Garmin watch. Today an ad-hoc request that fails (phone unreachable, rate-limited, pipeline error) simply shows no update, with no in-flight or error UI. A Wear OS equivalent of the on-demand trigger is also out of scope so far.
- **Monetization:** deferred until product-market fit; see `docs/monetization-plan.md`.

### Bugs
- [ ] BUG-12: Garmin glance goes blank (icon only, no text) intermittently after 1–2 days of runtime. Multiple fix attempts have recurred; see `docs/bug-12-garmin-glance-crash.md`.
- Resolved: BUG-10 (tile picker icon/preview), BUG-11 (ConnectIQ SDK singleton conflict on Activity open/close), BUG-13 (network failures overwriting last good watch status).

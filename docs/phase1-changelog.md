# Phase I Changelog

Historical record of all features and bugs completed during Phase I (Garmin MVP).

## Features
- **FEAT-01: Steel Thread** — Phone generates random 4-digit code, watch displays it. Validated build env, BLE pipeline, and background execution. UI removed in FEAT-04 once real data flow replaced it.
- **FEAT-02: AI Summarization POC** — Validated Gemini Flash cloud API can reliably parse MTA alert text into strict JSON schema. 4 tier-test buttons, multi-layer rate limiter (`ApiRateLimiter`), configurable model name via `local.properties`.
- **FEAT-03: MTA GTFS-RT Pipeline** — Live data fetching, parsing, route filtering, and active-period filtering on Android. Preprocessing pipeline that feeds Gemini Flash.
- **FEAT-04: BLE Push + Watch Glance** — Full end-to-end integration: pipeline result → `CommuteStatus` → BLE push → Garmin Glance display. Three push paths (good service, Gemini success, pipeline error).
- **Decision Prompt POC** — Validated actionable commute recommendations (NORMAL/MINOR_DELAYS/REROUTE/STAY_HOME) with direction matching, stale alert handling, and alternate line evaluation. 10/10 tests pass on `gemini-flash-latest`.
- **FEAT-05: Decision Engine Integration** — Replaced simple summarization with validated 4-tier decision framework. New `CommuteStatus` schema (action, summary, reroute_hint, affected_routes). Migrated to Firebase AI Logic SDK for `ThinkingConfig` support. Expanded monitored routes to include alternates (R, 7).
- **FEAT-06: Garmin Glance + Full-App UI** — Color-coded action tiers on glance (green/yellow/red/gray). Full detail view with summary, reroute_hint, and freshness timestamp.
- **FEAT-07: Commute Profile Configuration** — UI to define commute legs (line + direction + stations) and alternate lines. Replaces hardcoded profile. `SystemPromptBuilder` generates system prompt dynamically from saved profile.
- **FEAT-08: Background Polling Service** — `PollingForegroundService` with configurable commute windows and polling interval. `connectedDevice` FGS type (Android 15 compatible). `BootReceiver` for auto-start on reboot.
- **FEAT-10: Token Optimization** — Stripped redundant `Active period:` from prompt (already pre-filtered). Capped `description_text` at ~400 chars. Eliminated 7-12 second latency spikes from planned work alerts.
- **FEAT-11: MTA Line Badges** — Color-coded circular line badges everywhere: Android results, profile editor, Garmin detail view, Garmin glance. `MtaLineColors` shared utility on Android; `MtaColors` module on Garmin.
- **ARCH-01: AlarmManager Polling** — Replaced coroutine `delay()` loop with `AlarmManager.setExactAndAllowWhileIdle()`. Hardware RTC guarantees CPU wake on schedule during Doze. Active FGS exempts from 9-minute alarm throttle.
- **FEAT-12: Active Days Selector** — Day-of-week toggle for polling (default M-F). Three-tier scheduling: active day + in-window → interval; background ON + off-hours → hourly; background OFF + off-hours → skip to next active window.
- **FEAT-13: Garmin Detail View Revamp** — Structured visual hierarchy: title → badges → timestamp → hint → summary. Dynamic hint height measurement. Header-only page 1 when hint fills screen. `ViewLoop` + `DetailPageFactory` pagination.
- **FEAT-14: Auto Commute Direction** — Background polling derives direction from active window index (0→TO_WORK, 1→TO_HOME). Manual toggle only affects Fetch Live. Contextual status line on home screen.

## Bugs
- **BUG-01:** Rebuilt Garmin detail UX using native `ViewLoop` page navigation. Replaced manual swipe/scroll. No summary truncation.
- **BUG-02:** Polling service notification missing after reboot. Fixed: notification channel in `onStartCommand()`, FGS type → `connectedDevice`, explicit BT permission request.
- **BUG-03:** App stuck in "Initializing SDK" after reboot. Resolved as side-effect of BUG-02 fix (explicit BT permission request unblocked ConnectIQ SDK init).

# BUG-12: Garmin Glance Intermittent Crash

## Status

**Open.** Seven fix attempts deployed. Fix 7 (Timer-deferred registration in glance) is under observation. This fix does not prevent the crash — it makes it **recoverable** (scroll away and back to self-heal, instead of permanent blank requiring reboot).

## Environment

- **Device:** Garmin Venu 3
- **Firmware:** 17.05
- **Connect IQ:** 5.2.0
- **App type:** Widget (with Glance)

## Root Cause

**Native CIQ VM bug in the BLE message spooler.** `Communications.registerForPhoneAppMessages(cb)` intermittently crashes with an uncatchable `Unexpected Type Error: "Failed invoking <symbol>"` when the native Communications layer encounters orphaned dispatch state from hard-killed processes. This is confirmed by six systematic fix attempts that exhaustively ruled out all Monkey C-level causes (see "What We've Ruled Out" below).

The crash cannot be prevented at the application level. The current strategy is **resilience**: accept the crash (~once every 6 days) but ensure the glance self-heals.

## Crash Signature

```
Error: Unexpected Type Error
Details: 'Failed invoking <symbol>'
```

Always at `Communications.registerForPhoneAppMessages(cb)` where `cb` is a non-null `Method` reference. PC address shifts with each recompile, tracking the bytecode location. The error is uncatchable — `try/catch` with `Lang.Exception` does not fire.

## Architecture Context

Android `PollingForegroundService` pushes commute status over BLE (~hourly, 2-min intervals during commute windows). The widget receives it in `onPhoneMessage()`, writes to `Application.Storage`, and the glance/detail views read from Storage to render.

## What We've Ruled Out

| Theory | Fix # | How ruled out |
|--------|-------|--------------|
| Missing `(:glance)` annotation | 1 | Crash recurred. Glance processes BLE messages successfully, proving symbol isn't stripped. |
| Dangling callback from missed `onStop()` | 2, 4 | Null deregister in `onStop()` (fix 2) didn't help. Explicit null deregister immediately before registration (fix 4) succeeded on the line before the real registration crashed. |
| Registration too early in lifecycle | 5 | Moved to `getGlanceView()`/`getInitialView()`. Crash followed to new location. |
| Double-registration race condition | 6 | Guard flag ensured single registration per process. Crash occurred on the first and only registration, from `getGlanceView()`. |
| Memory pressure / GC | 3 | Diagnostics show ~766KB free at every start, stable across 260+ restarts. |
| `setMailboxListener()` as alternative | — | **Deprecated** since CIQ 2.2 (confirmed in Garmin API docs). Will be removed after System 4. |

## Current Fix: Timer-Deferred Registration (Fix 7)

Since the crash cannot be prevented, the strategy is to ensure `getGlanceView()` always returns a view successfully, so the crash never causes a permanent blank tile.

**How it works:**
1. `getGlanceView()` starts a 500ms `Timer.Timer` and immediately returns the view. The glance renders from `Application.Storage`.
2. After 500ms, the timer fires `onRegTimer()` which calls `registerPhoneListener()`.
3. **99.99% of the time:** Registration succeeds, BLE messages resume, app works normally.
4. **On crash (~once every 6 days):** The timer callback crashes, OS tears down the sandbox and shows the "IQ!" crash icon. User scrolls away and back to self-heal — fresh process, fresh view, fresh registration attempt.

**Key details:**
- Timer is a class-level variable (`_regTimer`) to prevent GC before it fires
- `getInitialView()` registers synchronously (fail-fast on tap is better UX than delayed crash in detail view)
- Guard flag (`_listenerRegistered`) still prevents double-registration within a process
- 500ms blind spot for BLE messages is negligible given 2-15 min polling intervals

**Expected UX after crash:**
- Glance renders normally → 500ms later, "IQ!" icon appears → user scrolls away and back → glance renders again and works normally

## Diagnostic Evidence

Instrumentation build (fix 3) tracks lifecycle counters, memory, and registration state in `Application.Storage`.

**Key findings from 20+ diagnostic snapshots (2026-03-25 to 2026-04-01):**
- **Missed `onStop()` calls are routine.** Start/stop gap grows steadily (st:264 vs sp:258 at last snapshot). OS regularly hard-kills the glance process.
- **Memory is stable.** ~766KB free at start, ~52KB minimum. No leak.
- **`method(:onPhoneMessage)` always resolves non-null.** Symbol resolution is not the issue.
- **`registerForPhoneAppMessages(null)` never crashes.** Only `Method` references trigger the error.
- **Crash frequency:** ~once every 1-6 days across seven occurrences. Fix 6 (guard flag) extended the interval to ~6 days, suggesting double-registration was one trigger but not the only one.

## Fix Attempt History

| # | Date | Change | Result |
|---|------|--------|--------|
| 1 | 2026-03-23 | `(:glance)` annotations on `onStart()` and `onPhoneMessage()` | Crashed ~24h later |
| 2 | 2026-03-24 | `registerForPhoneAppMessages(null)` in `onStop()` | Crashed ~24h later |
| 3 | 2026-03-25 | Diagnostic instrumentation build | Provided evidence; crash still occurred |
| 4 | 2026-03-26 | Defensive null deregister before registration in `onStart()` | Null succeeded; registration crashed same session |
| 5 | 2026-03-26 | Moved registration to `getGlanceView()`/`getInitialView()` | Crashed ~7h later |
| 6 | 2026-03-27 | Instance guard flag (`_listenerRegistered`) | Crashed after ~6 days (from `getGlanceView()`, first registration) |
| 7 | 2026-04-02 | **Timer-deferred registration in glance** + synchronous in detail | **Under observation** |

## Crash Log History

| # | Timestamp | PC | Location | Notes |
|---|-----------|-----|----------|-------|
| 1 | 2026-03-22T14:36:15Z | 0x10000037 | onStart:13 | Original code |
| 2 | 2026-03-24T12:42:10Z | 0x10000037 | onStart:14 | After fix 1 |
| 3 | 2026-03-25T15:04:59Z | 0x10000037 | onStart:14 | After fix 2 |
| 4 | 2026-03-25T23:03:27Z | 0x1000017e | onStart:35 | After fix 3+4 |
| 5 | 2026-03-26T19:09:45Z | 0x100001b1 | onStart:43 | After fix 4 |
| 6 | 2026-03-26T22:16:26Z | 0x1000043e | registerPhoneListener:112, from getInitialView:90 | After fix 5 |
| 7 | 2026-04-02T00:14:40Z | 0x1000044a | registerPhoneListener:118, from getGlanceView:102 | After fix 6 (first-only registration, guard flag active) |

## Remaining Options If Fix 7 UX Is Unacceptable

1. **`Application.Properties` via GCM settings sync** — Bypasses `Communications` entirely. `onSettingsChanged()` is a lifecycle override, not a registered callback pointer, so it sidesteps the native bug. Deprioritized due to unpredictable sync latency (problematic during 2-min commute polling and for testing).
2. **Garmin SDK bug report** — Strong evidence package: seven crash logs, systematic elimination of all Monkey C-level causes, proof that null calls never crash but Method references do.

## Current Source

See `garmin/source/CommuteBuddyApp.mc` for live code. Key structure after fix 7:
- `onStart()`: diagnostics only, no registration
- `getGlanceView()`: starts 500ms timer, returns view immediately
- `onRegTimer()`: deferred registration callback
- `getInitialView()`: synchronous registration (fail-fast)
- `registerPhoneListener()`: guarded by `_listenerRegistered`; registers once per process
- `onStop()`: deregisters and resets flag
- `onPhoneMessage()`: validates and writes to Storage (unchanged throughout)

## Relevant Files

| File | Purpose |
|------|---------|
| `garmin/source/CommuteBuddyApp.mc` | Lifecycle, registration, message handling |
| `garmin/source/CommuteBuddyGlanceView.mc` | Glance view (reads from Storage, unchanged) |
| `garmin/source/DiagnosticsPageView.mc` | Diagnostics page for instrumentation data |
| `garmin/manifest.xml` | App manifest |
| `troubleshooting/CIQ_LOG.YML` | Latest crash log |
| `troubleshooting/glance_diagnostic_output.md` | All diagnostic snapshots |
| `docs/bug-12-garmin-glance-crash.md` | This file |

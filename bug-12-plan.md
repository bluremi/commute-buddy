# BUG-12: Garmin Glance Blank Tile — Diagnostic Instrumentation

## User Story

**As a** developer debugging a crash with a multi-day repro cycle and no on-device debugger,
**I want** the Garmin app to record diagnostic breadcrumbs to Application.Storage before, during, and after the crash site in `onStart()`,
**So that** when the glance goes blank and I tap into the full app, I can read the exact system state that led to the failure and identify the root cause.

## Background

Three fix attempts have failed. The crash always occurs at `Communications.registerForPhoneAppMessages(method(:onPhoneMessage))` in `onStart()` after 1-2 days of operation. The identical PC address (`0x10000037`) across all three differently-compiled binaries suggests the fault is inside the CIQ VM dispatch layer, not user bytecode. Standard debugging (simulator, print statements) cannot reproduce a bug that requires 24-48 hours of real device operation with BLE message traffic.

## Acceptance Criteria

- [ ] `onStart()` writes lifecycle counter, free memory, and timestamp to Storage **before** the crash-prone line
- [ ] `method(:onPhoneMessage)` resolution and `Communications.registerForPhoneAppMessages()` are on separate lines so the crash log line number distinguishes which call fails
- [ ] A null guard on the method reference prevents the crash when resolution returns null (graceful degradation — glance shows stale data instead of going blank)
- [ ] `onStop()` writes its own lifecycle counter and free memory snapshot
- [ ] `onPhoneMessage()` writes payload metadata (timestamp, byte count) without storing the raw payload
- [ ] A "Diagnostics" page is appended to the detail ViewLoop showing all `diag_*` keys from Storage
- [ ] All diagnostic Storage keys use only primitive types (Number, String) — no Dictionary or Array values
- [ ] Total instrumentation code added to glance-annotated methods is minimal (no helper functions, no data structures) to stay within glance code memory budget

## Out of Scope

- Fixing the root cause (this is a diagnostic build to identify it)
- Changing the glance view rendering logic
- Modifying the Android-side BLE sender

---

## Implementation Plan

### Increment 1: Instrument `onStart()` with breadcrumbs, line split, and null guard

**What:** Rewrite `onStart()` to:
1. Increment a `diag_starts` counter in Storage
2. Write `diag_free_mem_start` from `System.getSystemStats().freeMemory`
3. Write `diag_last_start_ts` with the current epoch timestamp
4. Split `method(:onPhoneMessage)` resolution onto its own line
5. Write `diag_cb_resolved` (1 or 0) after the method() call
6. Guard against null — if method() returns null, write `diag_null_cb_at` timestamp and skip registration
7. After successful registration, write `diag_reg_ok = 1`
8. Wrap the entire block in try/catch — on exception, write `diag_err_phase` (e.g., "method_resolution" or "api_registration") and `diag_err_msg` with the exception string

**Files:** `garmin/source/CommuteBuddyApp.mc`

**Risk:** Try/catch may not catch VM-level type errors. The pre-crash breadcrumbs and line split provide signal regardless.

---

### Increment 2: Instrument `onStop()` and `onPhoneMessage()`

**What:**
- `onStop()`: Increment `diag_stops` counter, write `diag_free_mem_stop` and `diag_last_stop_ts`
- `onPhoneMessage()`: Increment `diag_msgs` counter, write `diag_last_msg_ts` and `diag_last_msg_bytes` (byte length of the serialized payload, not the payload itself)

**Files:** `garmin/source/CommuteBuddyApp.mc`

**Risk:** Negligible. These methods are not crash sites; instrumentation is straightforward.

---

### Increment 3: Add Diagnostics page to detail ViewLoop

**What:**
1. Create `DiagnosticsPageView.mc` — a simple `WatchUi.View` that reads all `diag_*` keys from Storage and renders them as a key-value text dump in a `TextArea`
2. Modify `DetailPageFactory.buildPageModel()` to append one additional page with a `"diagnostics" => true` flag
3. Modify `DetailPageFactory.getView()` to return a `DiagnosticsPageView` when the diagnostics flag is set
4. The diagnostics page should display: start count, stop count, message count, last start/stop/message timestamps, free memory at last start/stop, callback resolved flag, registration ok flag, null callback timestamp (if any), error phase and message (if any)

**Files:** `garmin/source/DiagnosticsPageView.mc` (new), `garmin/source/DetailPageFactory.mc`

**Risk:** Adding a page to the ViewLoop is well-understood from the existing pagination code. The diagnostics view does NOT need `(:glance)` annotation — it only runs in the full app context, so it has no impact on glance memory budget.

---

### Increment 4: Update bug doc with diagnostic strategy

**What:** Update `docs/bug-12-garmin-glance-crash.md` to document Fix Attempt 3 (diagnostic instrumentation), what data to look for after the next crash, and how to interpret the diagnostics page values. Also update prd.md with the new files.

**Files:** `docs/bug-12-garmin-glance-crash.md`

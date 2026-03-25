# BUG-12: Garmin Glance Goes Blank Intermittently

## Status

**Open.** Three fix attempts have been deployed. The bug has recurred after each. Root cause is not yet confirmed.

## Environment

- **Device:** Garmin Venu 3
- **Firmware:** 17.05
- **Connect IQ:** 5.2.0
- **App type:** Widget (with Glance)
- **Monkey C SDK:** 8.4.1

## Symptom

The Garmin Glance tile goes completely blank — app icon is present but no text is rendered. Tapping the blank tile launches the full app (detail view), which works normally. The only reliable fix has been reinstalling the app. The bug appears after 1–2 days of normal operation; it never occurs immediately after install or reboot.

## Crash Log

All three crash occurrences produced the same error signature, with only the line number shifting between fix attempt 1 and 2 (due to an annotation being inserted above the line):

**Occurrence 1 (2026-03-22, before any fixes):**
```
Error: Unexpected Type Error
Details: 'Failed invoking <symbol>'
Time: 2026-03-22T14:36:15Z
Part-Number: 006-B4260-00
Firmware-Version: '17.05'
Language-Code: eng
ConnectIQ-Version: 5.2.0
Filename: garmin
Appname: Commute Buddy
Stack:
  - pc: 0x10000037
    File: 'CommuteBuddyApp.mc'
    Line: 13
    Function: onStart
```

**Occurrence 2 (2026-03-24, after Fix Attempt 1):**
```
Error: Unexpected Type Error
Details: 'Failed invoking <symbol>'
Time: 2026-03-24T12:42:10Z
(same device/firmware/CIQ as above)
Stack:
  - pc: 0x10000037
    File: 'CommuteBuddyApp.mc'
    Line: 14         ← shifted by 1 due to (:glance) annotation inserted on line 12
    Function: onStart
```

**Occurrence 3 (2026-03-25T15:04:59Z, after Fix Attempt 2):**
```
Error: Unexpected Type Error
Details: 'Failed invoking <symbol>'
Time: 2026-03-25T15:04:59Z
Part-Number: 006-B4260-00
Firmware-Version: '17.05'
Language-Code: eng
ConnectIQ-Version: 5.2.0
Filename: garmin
Appname: Commute Buddy
Stack:
  - pc: 0x10000037
    File: 'CommuteBuddyApp.mc'
    Line: 14
    Function: onStart
```

Line 14 is the same as Occurrence 2 — no line number shift, as Fix Attempt 2 only added `onStop()` below the crash site. No new information: same error, same PC address (`0x10000037`), same function.

In all cases: same error type, same function (`onStart`), same PC address, always at the `Communications.registerForPhoneAppMessages(method(:onPhoneMessage))` call. The `onStop()` unregister fix had no effect on recurrence rate or crash signature.

## Current Source Code

### `CommuteBuddyApp.mc` (state after Fix Attempt 2)

```monkeyc
import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

class CommuteBuddyApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    (:glance)
    function onStart(state) {
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
    }

    (:glance)
    function onStop(state) {
        Communications.registerForPhoneAppMessages(null);
    }

    (:glance)
    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data == null || !(data instanceof Dictionary)) {
            return;
        }
        var dict = data as Dictionary;

        var action = dict.get("action");
        var summary = dict.get("summary");
        var affectedRoutes = dict.get("affected_routes");
        var rerouteHint = dict.get("reroute_hint");
        var timestamp = dict.get("timestamp");

        if (!(action instanceof String)) { return; }
        var actionStr = action as String;
        if (!actionStr.equals("NORMAL") && !actionStr.equals("MINOR_DELAYS")
                && !actionStr.equals("REROUTE") && !actionStr.equals("STAY_HOME")) {
            return;
        }
        if (!(summary instanceof String)) { return; }
        if (!(affectedRoutes instanceof String)) { return; }

        Application.Storage.setValue("cs_action", actionStr);
        Application.Storage.setValue("cs_summary", summary as String);
        Application.Storage.setValue("cs_affected_routes", affectedRoutes as String);
        if (rerouteHint instanceof String) {
            Application.Storage.setValue("cs_reroute_hint", rerouteHint as String);
        } else {
            Application.Storage.deleteValue("cs_reroute_hint");
        }
        if (timestamp instanceof Number) {
            Application.Storage.setValue("cs_timestamp", timestamp as Number);
        }
        WatchUi.requestUpdate();
    }

    function getInitialView() {
        var factory = new DetailPageFactory();
        var viewLoop = new WatchUi.ViewLoop(factory, {:wrap => false});
        var delegate = new WatchUi.ViewLoopDelegate(viewLoop);
        return [viewLoop, delegate];
    }

    function getGlanceView() {
        return [new CommuteBuddyGlanceView()];
    }
}
```

### `CommuteBuddyGlanceView.mc` (unchanged throughout)

```monkeyc
import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

(:glance)
class CommuteBuddyGlanceView extends WatchUi.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var action = Application.Storage.getValue("cs_action");
        var affectedRoutes = Application.Storage.getValue("cs_affected_routes");

        if (!(action instanceof String)) {
            dc.drawText(dc.getWidth()/2, dc.getHeight()/2, Graphics.FONT_GLANCE,
                "Waiting...", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var actionStr = action as String;
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var font = Graphics.FONT_GLANCE;

        if (actionStr.equals("NORMAL")) {
            dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
            dc.drawText(cx, cy, font, "Normal",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else if (actionStr.equals("STAY_HOME")) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(cx, cy, font, "Stay Home",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            // MINOR_DELAYS or REROUTE: prefix + route letters in MTA colors
            // ... (full rendering logic with MtaColors.splitCsv and per-route color)
        }
    }
}
```

### `manifest.xml`

```xml
<iq:application entry="CommuteBuddyApp" type="widget" minApiLevel="3.4.0">
    <iq:products><iq:product id="venu3"/></iq:products>
    <iq:permissions>
        <iq:uses-permission id="Communications"/>
    </iq:permissions>
</iq:application>
```

## Architecture Context

**Data flow:**
Android `PollingForegroundService` → ConnectIQ BLE → `CommuteBuddyApp.onPhoneMessage()` → `Application.Storage` → `CommuteBuddyGlanceView.onUpdate()` reads Storage and renders.

**Key behavior confirmed by user:** The Glance has been observed successfully processing multiple queued BLE messages at once, proving that `Communications` module IS accessible in glance context and the callback IS invoked.

**Glance lifecycle (Connect IQ widget with glance):** The OS runs the Glance in a constrained process. `onStart()` is called when the Glance process boots, then `getGlanceView()` returns the view. After some idle period, the OS may recycle the process. On next swipe, `onStart()` is called again on a fresh AppBase instance.

## Fix Attempt History

### Fix Attempt 1 — `(:glance)` annotations (deployed 2026-03-23, recurred 2026-03-24)

**Theory (Gemini 3.1 Pro):** The `(:glance)` annotation scopes code into the glance's memory binary. Without it on `onPhoneMessage`, the symbol `:onPhoneMessage` is stripped from the glance binary. `onStart()` runs in glance context and `method(:onPhoneMessage)` resolves to null → "Failed invoking \<symbol\>".

**Fix:** Added `(:glance)` to `onStart()` and `onPhoneMessage()`.

**Why it failed:** The crash recurred at the same line (shifted by 1 due to the annotation). The user confirmed the Glance successfully processes messages — meaning the symbol is NOT stripped. Compiler stripping would also produce an immediate, consistent crash, not an intermittent one after 1–2 days.

---

### Fix Attempt 2 — Unregister in `onStop()` (deployed 2026-03-24, recurred 2026-03-25)

**Theory (Gemini 3.1 Pro, second attempt):** `Communications.registerForPhoneAppMessages()` registers a system-level callback. `onStop()` was empty — never unregistered. After the OS recycles the Glance process, the AppBase is GC'd but the native Communications layer still holds a reference to `method(:onPhoneMessage)` on the dead object. When a BLE message arrives, the OS invokes the callback against the dead object → "Failed invoking \<symbol\>". Explains the 1–2 day delay (depends on OS recycling the process AND a BLE message arriving during the gap).

**Fix:** Added `Communications.registerForPhoneAppMessages(null)` in `onStop()`, also annotated `(:glance)`. Lifecycle is now symmetric: register on start, unregister on stop.

**Why it failed:** Bug recurred after approximately 1 day.

---

### Fix Attempt 3 — Diagnostic Instrumentation Build (deployed 2026-03-25, under observation)

**Theory:** The crash PC address `0x10000037` is identical across all three differently-compiled binaries. This rules out user-bytecode-level fixes and points to a fault inside the CIQ VM dispatch layer, triggered by some runtime condition that develops after 1–2 days of operation. The condition is unknown; standard debugging cannot reproduce a 24–48 hour repro cycle. Before any further fix attempts, we need to know the runtime state at the moment of failure.

**What was deployed:** A diagnostic instrumentation build that writes breadcrumbs to `Application.Storage` before, during, and after the crash-prone registration call, plus a Diagnostics page appended to the detail ViewLoop for reading the data without a device debugger.

**Storage keys written:**

| Key | Written in | Meaning |
|-----|-----------|---------|
| `diag_starts` | `onStart()` | Cumulative start count |
| `diag_free_mem_start` | `onStart()` | Free memory at last start (bytes) |
| `diag_min_free_mem_start` | `onStart()` | Running minimum free memory across all starts |
| `diag_last_start_ts` | `onStart()` | Epoch timestamp of last start |
| `diag_cb_resolved` | `onStart()` | 1 if `method(:onPhoneMessage)` returned non-null, 0 if null |
| `diag_null_cb_at` | `onStart()` | Epoch timestamp of first null callback resolution (if ever) |
| `diag_reg_ok` | `onStart()` | 1 after `registerForPhoneAppMessages()` returns without exception |
| `diag_err_phase` | `onStart()` | Phase active when exception caught: `"method_resolution"` or `"api_registration"` |
| `diag_err_msg` | `onStart()` | Exception message string (if caught) |
| `diag_stops` | `onStop()` | Cumulative stop count |
| `diag_free_mem_stop` | `onStop()` | Free memory at last stop (bytes) |
| `diag_last_stop_ts` | `onStop()` | Epoch timestamp of last stop |
| `diag_msgs` | `onPhoneMessage()` | Cumulative message count |
| `diag_last_msg_ts` | `onPhoneMessage()` | Epoch timestamp of last message |
| `diag_last_msg_bytes` | `onPhoneMessage()` | Byte length of last message payload |
| `diag_free_mem_msg` | `onPhoneMessage()` | Free memory at last message receipt |

**First diagnostic reading (2026-03-25, ~8 starts after deploy):**
```
starts:8  stops:7  msgs:--
mem@start:766640  min:766640
mem@stop:52080
mem@msg:--
cb:1  reg:1
```

Observations from first reading:
- `cb:1 reg:1` — registration succeeded on the last start (start 8)
- `msgs:--` — no BLE messages received yet since diagnostic build was installed
- `mem@start: 766640` (~750KB free) — healthy at last start
- `mem@stop: 52080` (~51KB free) — only ~51KB free at stop time; the glance consumes ~700KB during one lifecycle. The runtime appears to GC between stop and the next start (hence 766KB at start 8), but if GC doesn't fully run before a rapid restart, available memory at registration time could be significantly lower.

**How to read the diagnostics page:**
1. When the glance goes blank, tap through to the full app
2. Swipe to the last page (Diagnostics)
3. Record the full dump

**What to look for after the next crash:**

- **`diag_err_phase`** — this is the most important field. If set to `"method_resolution"`, the fault is in `method(:onPhoneMessage)`. If set to `"api_registration"`, the fault is inside `Communications.registerForPhoneAppMessages()`. If absent (key deleted), `diag_reg_ok` should be 1 and the crash is occurring after a successful registration.
- **`diag_err_msg`** — exception message if caught; may or may not be populated if the VM crashes below the try/catch boundary.
- **`diag_min_free_mem_start`** — if this is significantly lower than the first reading (766640), memory pressure is accumulating across restart cycles. Compare with `diag_free_mem_start` from the failing start.
- **`diag_free_mem_msg`** — if significantly lower than `diag_free_mem_start`, message handling is consuming memory that isn't being freed.
- **`diag_free_mem_stop`** — if trending downward (low 50KB range or below), the glance may be exhausting memory before it can unregister cleanly, leaving the Communications layer in a bad state for the next start.
- **`diag_starts` vs `diag_stops`** — should differ by 1 (one active start). A larger gap would indicate starts without matching stops, suggesting abnormal lifecycle termination.
- **`diag_cb_resolved`** — if 0, the method reference went null before the crash, which would be a new finding contradicting Fix Attempt 1's theory but from a different angle.

**Note on try/catch coverage:** The try/catch in `onStart()` may not catch VM-level type errors (the same error type that triggered all three previous crashes). If the crash recurs with `diag_err_phase` still set (i.e., not cleared by a successful `deleteValue`) and no `diag_err_msg`, it confirms the fault is below the try/catch layer — inside the CIQ VM — and any exception-based fix strategy will be ineffective.

## Notable: PC Address Is Identical Across All Three Crashes

All three occurrences report `pc: 0x10000037`. This is a bytecode program counter offset, not a source line number. Fix Attempt 1 added `(:glance)` annotations that shifted the source line number from 13 to 14 — meaning a new binary was compiled and deployed — yet the PC address did not change.

This is unexpected. If the crash were occurring inside user code at a specific compiled instruction, recompiling with structural changes (annotations added) would typically produce a different bytecode layout and a different PC offset. The fact that it is identical across all three binaries suggests one of two things:

1. **The crash is occurring inside the ConnectIQ runtime or SDK native layer**, not in user-compiled bytecode. The PC `0x10000037` may be a fixed address within the CIQ VM itself — for example, the instruction that dispatches a method call — rather than an offset into the compiled `.prg`. The stack frame points back to the source line as the call site, but the actual fault is inside the VM dispatch.

2. **The bytecode layout of `onStart()` happens to be identical across all compiled versions**, meaning the annotation changes didn't affect the offset of the `registerForPhoneAppMessages` call instruction. Less likely given the structural edits, but possible.

Interpretation 1 would mean the error message "Failed invoking \<symbol\>" is the VM's generic dispatch-failure message, and the root cause may be in how the CIQ runtime handles the `method(:onPhoneMessage)` call at a lower level than we've been investigating — possibly a runtime state issue in the Communications module itself rather than anything fixable with annotations or unregister calls.

## What We Know For Certain

1. The crash always occurs in `onStart()`, always at the `Communications.registerForPhoneAppMessages(method(:onPhoneMessage))` line.
2. The crash is intermittent — always after 1–2 days, never immediately.
3. The Glance successfully processes BLE messages before the crash, so `Communications` is available in glance context and symbol stripping is not the cause.
4. Tapping the blank glance launches the full app normally — the AppBase and full-app context are intact.
5. Reinstalling fixes it, ruling out corrupted `Application.Storage` as the cause.
6. The `(:glance)` annotations have no effect on recurrence rate.
7. The dangling-pointer / unregister theory (Fix Attempt 2) also did not resolve it.

## Open Questions for Next Investigation

1. Could `Communications.registerForPhoneAppMessages()` itself throw or fail in certain runtime states, rather than the issue being with the method reference? The error "Failed invoking \<symbol\>" is the VM's generic error — it may not exclusively mean a null/missing method reference.

2. Is there a known Garmin firmware 17.05 / CIQ 5.2.0 bug with `registerForPhoneAppMessages` in widget-with-glance apps?

3. Does the `(:glance)` annotation on the `AppBase` subclass work as expected? The `CommuteBuddyApp` class itself is NOT annotated `(:glance)` — only individual methods within it are. Is this a valid and fully supported pattern? Could the Garmin compiler behave differently when `(:glance)` is on individual methods of a non-glance-annotated class vs. on the class itself?

4. Should the entire `CommuteBuddyApp` class be annotated `(:glance)` instead of individual methods? Or should message registration be moved to a dedicated `(:glance)`-annotated subclass?

5. Is `onStart()` the right lifecycle hook for `registerForPhoneAppMessages` in a widget-with-glance app? The Connect IQ lifecycle for this app type means `onStart()` runs before both `getGlanceView()` and `getInitialView()`. Is there an alternative hook (`getGlanceView()` itself, for example) that would be safer?

6. What exactly does "Failed invoking \<symbol\>" mean in the CIQ VM — is it strictly a null/dead method reference, or can it also be triggered by a module API call failure or an exception within the SDK's native layer?

## Relevant Files

| File | Full Path |
|------|-----------|
| Primary suspect | `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\CommuteBuddyApp.mc` |
| Glance view | `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\CommuteBuddyGlanceView.mc` |
| MTA colors module | `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\MtaColors.mc` |
| Detail page factory | `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\DetailPageFactory.mc` |
| Detail page view | `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\DetailPageView.mc` |
| Diagnostics page view | `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\DiagnosticsPageView.mc` |
| App manifest | `A:\Phil\Phil Docs\Development\commute-buddy\garmin\manifest.xml` |
| Project overview | `A:\Phil\Phil Docs\Development\commute-buddy\prd.md` |
| This document | `A:\Phil\Phil Docs\Development\commute-buddy\docs\bug-12-garmin-glance-crash.md` |

## Files to Provide to LLM for Context

When starting a new troubleshooting session, provide:
- `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\CommuteBuddyApp.mc` (primary suspect)
- `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\CommuteBuddyGlanceView.mc`
- `A:\Phil\Phil Docs\Development\commute-buddy\garmin\source\DiagnosticsPageView.mc`
- `A:\Phil\Phil Docs\Development\commute-buddy\garmin\manifest.xml`
- `A:\Phil\Phil Docs\Development\commute-buddy\prd.md` (project overview)
- `A:\Phil\Phil Docs\Development\commute-buddy\docs\bug-12-garmin-glance-crash.md` (this file)
- The full diagnostics page dump from the device at time of crash

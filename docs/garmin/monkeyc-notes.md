# Monkey C — Project Notes (Connect IQ SDK 8.4.1)

> **Purpose:** Gotchas and correct patterns discovered during development. LLM training data frequently hallucinate Monkey C syntax or assume types are in scope when they aren't. Verify against the [Connect IQ API docs](https://developer.garmin.com/connect-iq/api-docs/) before implementing.

## `import Toybox.Lang` is required in the glance context

`Dictionary`, `Number`, `String`, `Long`, and other primitive types live in `Toybox.Lang`. In a normal widget build they resolve implicitly, but when the `:glance` annotation is present, the compiler also pulls the app's entry-point class (`CommuteBuddyApp`) into the glance compilation unit — and in that context `Lang` types are **not** automatically in scope.

**Symptom:** build succeeds without the annotation but fails with errors like:
```
ERROR: venu3: Cannot resolve type 'Dictionary'
ERROR: venu3: Cannot resolve type 'Number'
ERROR: venu3: Cannot resolve type 'String'
```
alongside the warning:
```
WARNING: venu3: The entry point '$CommuteBuddyApp' was implicitly added to the glance process
since the app contains declarations annotated with (:glance).
```

**Fix:** Add `import Toybox.Lang;` to every `.mc` file that uses `Lang` types when that file is compiled in the glance context.

```monkeyc
import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;       // required for Dictionary, Number, String, etc.
import Toybox.WatchUi;
```

This applies to both `CommuteBuddyApp.mc` (which is pulled in transitively) and `CommuteBuddyGlanceView.mc` (which uses `Number` for storage reads).

## Dictionary message handling

When Android sends a `Map<String, Any>` via `connectIQ.sendMessage()`, the watch receives it as a `Lang.Dictionary`. Use `instanceof Dictionary` to guard, then `.get(key)` to extract values. `.get()` returns `Lang.Object or Null`.

```monkeyc
function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
    var data = msg.data;
    if (data == null || !(data instanceof Dictionary)) {
        return;
    }
    var dict = data as Dictionary;
    var value = dict.get("my_key");   // returns Object or Null
    if (value instanceof String) {
        // safe to use as String
    }
}
```

**Validate before storing.** Never assume a key exists or has the right type — the Android side could send a malformed payload, or an older version of the app could send a different schema. Silent validation (return without storing on bad input) is safer than crashing.

## Storage key naming

Prefix application-specific storage keys to avoid collisions with any legacy keys left behind by previous app versions. This project uses `cs_` (CommuteStatus):

| Key | Type | Description |
|-----|------|-------------|
| `cs_status` | Number | 0 = Normal, 1 = Delays, 2 = Disrupted |
| `cs_route` | String | Affected routes, comma-separated (e.g. `"N,W"`) |
| `cs_reason` | String | Brief reason, max 40 chars |
| `cs_timestamp` | Number | Unix epoch seconds |

## Modules do not support `private`

Monkey C modules have no concept of data hiding. The `private` keyword is **not allowed** in module-level functions.

**Symptom:** `extraneous input 'private' expecting {'class', 'module', ...}`

**Fix:** Remove `private` from all functions in a `module` block. Use `function` instead of `private function`.

## String.substring requires two arguments

`Lang.String.substring(startIndex, endIndex)` requires **both** arguments. There is no single-argument overload.

**Symptom:** `Trying to call function '$.Toybox.Lang.String.substring' with wrong number of arguments`

**Fix:** For "from index to end of string", use `substring(start, null)`. The API accepts `null` for `endIndex` to mean end of string (API 3.3.2+).

```monkeyc
var rest = text.substring(chunk.length(), null);  // correct
var rest = text.substring(chunk.length());        // wrong — 1 arg
```

## ViewLoopFactory.getView return type

`ViewLoopFactory.getView(page)` must return `[View, BehaviorDelegate]` — a two-element array. Returning a bare `View` or `[View]` causes type errors.

**Fix:** Create a minimal `BehaviorDelegate` subclass (e.g. `DetailPageDelegate`) that does nothing. The `ViewLoopDelegate` handles navigation; the per-page delegate exists only to satisfy the signature.

```monkeyc
function getView(pageIndex as Number) {
    var view = new MyPageView(...);
    var delegate = new MyPageDelegate();
    return [view, delegate];
}
```

## Reference

- **Connect IQ API docs:** https://developer.garmin.com/connect-iq/api-docs/
- **`Lang.Dictionary`:** https://developer.garmin.com/connect-iq/api-docs/Toybox/Lang/Dictionary.html

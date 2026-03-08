# CLAUDE.md

This file describes how to work in this project using prd.md and plan.md.

## The Two Documents

- **prd.md** — The living product document. Starts as a requirements blueprint; grows into full documentation of what was actually built. Contains: problem statement, approach, key features, technical architecture, and the feature/bug backlog.
- **plan.md** — The active development document. Contains only the story currently being worked on: the expanded user story and its step-by-step implementation plan.

## Development Workflow

### 1. Pick a story
Choose a feature or bug from the **Backlog** in `prd.md`.

### 2. Expand the story
Run `/expand-story TASK-ID`. This reads the stub from `prd.md`, expands it into a full user story with acceptance criteria, and writes it to `plan.md`.

Review and approve the expanded story before proceeding.

### 3. Create an implementation plan
Run `/implementation-plan TASK-ID`. This reads the expanded story from `plan.md`, breaks it into small testable increments, and writes the plan into `plan.md` under the story.

Review and approve the plan before proceeding.

### 4. Execute increment by increment

**CRITICAL: ONE INCREMENT AT A TIME.**

For each increment:
1. Implement the changes described
2. Commit with a descriptive message
3. Stop — tell the user "Ready for testing" and WAIT
4. Do NOT start the next increment
5. Wait for the user to explicitly confirm testing passed
6. Only then: mark the increment complete (`[x]`) in `plan.md` and proceed to the next

**DO NOT:**
- Implement multiple increments in one response
- Assume testing passed and move on
- Continue without explicit user confirmation ("looks good", "test passed", "continue", etc.)

### 5. Update prd.md when a story is complete

**This step is mandatory. Do not skip it.**

When all increments in a story are marked complete:

1. **Update Key Features** — If the story added or changed user-facing behavior, revise the **Key Features** section in `prd.md` to accurately describe what the app now does. Add new features; update or remove features that changed.

2. **Update Technical Architecture** — Update **Tech Stack**, **System Design**, **Key Files**, and **Commands** in `prd.md` to reflect what was actually built. Add new files, libraries, patterns, and architectural decisions introduced by this story. Remove anything that's no longer accurate. This section should always describe the real current state of the codebase — not a plan.

3. **Mark the backlog item complete** — Check off the item (`[x]`) in the `prd.md` backlog.

4. **Clear plan.md** — Remove the completed story from `plan.md` so it only ever contains active work.

prd.md is the ground truth of what the project is. Keeping it accurate is as important as writing the code.

## ADB Logcat on Windows

**Always use `cmd.exe` (not PowerShell) for `adb logcat`.** PowerShell mishandles the raw byte stream — it either hangs silently, throws `InputObjectNotBound` errors, or drops output unpredictably.

**Use `-s` tag filtering** (filters by the `TAG` constant in `Log.d(TAG, ...)` calls — more reliable than `-e` content regex):
```cmd
adb -s 57171FDCQ008DS logcat -s PollingService:V CommutePipeline:V CommuteBuddy:V BootReceiver:V
```

**For post-mortem dumps** (capture to file, search locally — avoids all streaming issues):
```cmd
adb -s 57171FDCQ008DS logcat -d -s PollingService:V CommutePipeline:V > debug.txt
findstr "PollingService BootReceiver" debug.txt
```

**To clear the buffer before a test:**
```cmd
adb -s 57171FDCQ008DS logcat -c
```

**To test boot behavior without rebooting:** `BOOT_COMPLETED` is a protected broadcast — `adb shell am broadcast` is blocked on non-rooted devices with a SecurityException. Instead, reboot and capture immediately:
```cmd
adb -s 57171FDCQ008DS reboot && adb -s 57171FDCQ008DS wait-for-device && adb -s 57171FDCQ008DS logcat -d -s PollingService:V BootReceiver:V > boot.txt
```
`wait-for-device` blocks until the phone reconnects, then dumps the buffer (which still contains all boot-time logs).

**Key log tags for this project:**
- `PollingService` — background polling loop, BLE send results, pre-flight checks
- `BootReceiver` — boot-triggered service start
- `CommutePipeline` — fetch/parse/filter/Gemini pipeline steps
- `GenerativeModel` — Firebase AI SDK (Gemini calls)
- `CommuteBuddy` — MainActivity (ConnectIQ SDK, device discovery)
- `ConnectIQ` — Garmin SDK internals

## Windows / PowerShell Notes

- **No `gradlew` scripts in the repo.** The `android/gradlew` and `android/gradlew.bat` wrapper scripts are not committed. Do NOT try `.\gradlew` or `.\gradlew.bat` — they don't exist. Use the Gradle binary from the cached wrapper distribution instead:
  ```powershell
  $gradle = (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin" -Recurse -Filter "gradle.bat" | Select-Object -First 1 -ExpandProperty FullName)
  Set-Location "a:\Phil\Phil Docs\Development\commute-buddy\android"
  & $gradle :app:testDebugUnitTest
  ```
  If the version folder hash is unknown, use the `Get-ChildItem` discovery step above — it always finds the right binary.
- **No heredoc in PowerShell.** `$(cat <<'EOF' ... EOF)` is bash syntax and will fail. For multi-line `git commit` messages, use `-m "single line message"` or write to a temp file first.
- **Path spaces require quoting.** The workspace path contains spaces (`Phil Docs`). Always quote paths: `Set-Location "a:\Phil\Phil Docs\Development\commute-buddy\android"`. The `cd "path" && command` chaining pattern is unreliable in PowerShell — use separate `Set-Location` + `& command` calls.

## Project-Specific Notes

- **`org.json` in unit tests:** `org.json.JSONObject` is a stub in the Android JVM unit test environment and throws `RuntimeException("Stub!")` at runtime. Any class that uses `org.json` in production code needs `testImplementation("org.json:json:20250107")` in `build.gradle.kts` to get the real implementation for unit tests. This was discovered during FEAT-03 increment 1.

- **Two IDEs required:** Android Studio for the Kotlin/Android app, VS Code for the Garmin/Monkey C app
- **Phase 1 testing** (UI/logic): Use Connect IQ Simulator in VS Code — no hardware needed
- **Phase 2 testing** (BLE): Physical phone + Garmin Venu 3 via USB sideloading
- **BLE payload must stay under 1KB** — Garmin Glance memory limit is ~32KB
- **Never parse protobuf on the watch** — all heavy lifting happens on Android
- **Verify Monkey C syntax** against latest Connect IQ SDK docs — LLMs frequently hallucinate deprecated or nonexistent methods
- **No `dc.drawWrappedText()`** — this method does not exist in the Connect IQ SDK. Use `WatchUi.TextArea` for wrapped text: `new WatchUi.TextArea({:text=>"...", :color=>..., :font=>..., :locX=>x, :locY=>y, :width=>w, :height=>h, :justification=>...})` then call `.draw(dc)`. Requires API level 3.1.0+.
- **Connect IQ Android SDK:** Use `getDeviceStatus()` not `getStatus()`, and `IQDevice.IQDeviceStatus` not `ConnectIQ.IQDeviceStatus`. See `docs/garmin/android-sdk-api-notes.md`
- **Garmin build from command line:** The Connect IQ extension (Command palette → Monkey C: Build) uses `$env:USERPROFILE\.garmin\developer_key`. Do NOT use `$env:USERPROFILE\.garmin\connectiq\developer_key` — that path does not exist. Prefer the VS Code extension for builds; if using `monkeyc` directly, pass `-y "$env:USERPROFILE\.garmin\developer_key"`. See `docs/garmin/monkeyc-notes.md` for Monkey C gotchas (modules, substring, ViewLoopFactory).

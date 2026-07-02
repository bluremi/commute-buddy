# CLAUDE.md

Claude-specific workflow instructions. For project knowledge, see `prd.md` (the portable, LLM-agnostic project document).

## The Two Documents

- **prd.md** — The living product document: problem, approach, capabilities, architecture, and backlog. This is the source of truth for the project and is shared with multiple LLMs.
- **plan.md** — The active development document. Contains only the story currently being worked on.

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

1. **Update Current Capabilities** — If the story added or changed user-facing behavior, revise the **Current Capabilities** section in `prd.md` to accurately describe what the app now does.

2. **Update Technical Architecture** — Update **Tech Stack**, **System Design**, **Key Files**, and **Commands** in `prd.md` to reflect what was actually built. This section should always describe the real current state of the codebase.

3. **Mark the backlog item complete** — Check off the item (`[x]`) in the `prd.md` backlog.

4. **Clear plan.md** — Remove the completed story from `plan.md` so it only ever contains active work.

prd.md is the ground truth of what the project is. Keeping it accurate is as important as writing the code.

## ADB Logcat on Windows

**Always use `cmd.exe` (not PowerShell) for `adb logcat`.** PowerShell mishandles the raw byte stream.

**Use `-s` tag filtering:**
```cmd
adb -s 57171FDCQ008DS logcat -s PollingService:V CommutePipeline:V CommuteBuddy:V BootReceiver:V
```

**For post-mortem dumps:**
```cmd
adb -s 57171FDCQ008DS logcat -d -s PollingService:V CommutePipeline:V > debug.txt
findstr "PollingService BootReceiver" debug.txt
```

**To clear the buffer before a test:**
```cmd
adb -s 57171FDCQ008DS logcat -c
```

**Boot testing** (`BOOT_COMPLETED` is a protected broadcast — `am broadcast` is blocked on non-rooted devices):
```cmd
adb -s 57171FDCQ008DS reboot && adb -s 57171FDCQ008DS wait-for-device && adb -s 57171FDCQ008DS logcat -d -s PollingService:V BootReceiver:V > boot.txt
```

**Key log tags:** `PollingService`, `BootReceiver`, `CommutePipeline`, `GenerativeModel`, `CommuteBuddy`, `ConnectIQ`

## Running Gradle (Bash — Claude Code's shell)

Claude Code runs in **bash**, not PowerShell. There is no `gradlew` in the repo, so locate the cached binary with a glob and run it directly.

**IMPORTANT: Do NOT use bash array syntax `GRADLE=(...)` / `${GRADLE[0]}` — the Bash tool runs commands via `eval` and array assignment causes a syntax error every time. Use `$()` command substitution instead:**

```bash
GRADLE=$(ls /c/Users/blure/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle 2>/dev/null | head -1)
cd "A:/Phil/Phil Docs/Development/commute-buddy/android" && "$GRADLE" :app:testDebugUnitTest
```

The hash-named subdirectory (e.g. `5xuhj0ry160q40clulazy9h7d`) varies, so always use the glob `*/gradle-8.13/bin/gradle` — do **not** hardcode the hash.

Other common tasks (same pattern, different task):
- Build debug APK: `"$GRADLE" :app:assembleDebug`
- Install to device: `"$GRADLE" :app:installDebug`

## Building & Sideloading the Garmin App (Venu 3, connected via USB)

The Venu 3 connects as an **MTP device** (no drive letter), so a plain file copy won't work — sideload by copying the `.prg` into `Internal Storage\GARMIN\Apps` through the Windows Shell COM object. Claude Code can do the whole build-and-deploy itself; no need to make the user do it manually.

**1. Build the release `.prg` (Bash):**
```bash
SDK="/c/Users/blure/AppData/Roaming/Garmin/ConnectIQ/Sdks/connectiq-sdk-win-8.4.1-2026-02-03-e9f77eeaa"
KEY="/c/Users/blure/.garmin/developer_key"
cd "A:/Phil/Phil Docs/Development/commute-buddy/garmin" && mkdir -p bin
"$SDK/bin/monkeyc.bat" -f monkey.jungle -d venu3 -o "bin/CommuteBuddy.prg" -y "$KEY" -r
```
The SDK dir is date-stamped and will change on SDK updates — glob `.../ConnectIQ/Sdks/connectiq-sdk-win-*/bin/monkeyc.bat` rather than hardcoding it. Drop `-r` to compile-check only (writes to `/tmp`); it builds for `venu3` in ~seconds and prints `BUILD SUCCESSFUL`.

**2. Sideload to the watch (PowerShell — MTP via Shell.Application):**
```powershell
$src = "A:\Phil\Phil Docs\Development\commute-buddy\garmin\bin\CommuteBuddy.prg"
$sh = New-Object -ComObject Shell.Application
$venu = $sh.NameSpace(17).Items() | Where-Object { $_.Name -eq 'Venu 3' }
$internal = $venu.GetFolder.Items() | Where-Object { $_.Name -eq 'Internal Storage' }
$garmin = $internal.GetFolder.Items() | Where-Object { $_.Name -eq 'GARMIN' }
$apps = ($garmin.GetFolder.Items() | Where-Object { $_.Name -eq 'Apps' }).GetFolder
$apps.CopyHere($src, 4 -bor 16 -bor 512)   # 4=no UI, 16=Yes-to-all, 512=no new-dir confirm
# MTP copy is async — poll until it lands:
for ($i=0; $i -lt 30; $i++) { Start-Sleep 1; if (@($apps.Items()|%{$_.Name}) -match 'CommuteBuddy') { break } }
```
Notes:
- `NameSpace(17)` is "This PC"; the device shows as **`Venu 3`** → `Internal Storage` → `GARMIN` → `Apps`.
- MTP **hides `.PRG` files** from enumeration — after copying, the listing may not show `CommuteBuddy.prg` even though it's there and the app runs. The poll above catches the brief window where it *is* listed; if it times out, the copy still likely succeeded. Confirm on the watch itself.
- Garmin identifies apps by the UUID baked into the `.prg` (manifest `id`), **not** the filename, so re-copying `CommuteBuddy.prg` cleanly replaces the prior install. The user reboots/reopens the widget to pick up the new build.

## Windows / PowerShell Notes (for manual terminal use)

- **No `gradlew` scripts in the repo.** From a PowerShell terminal:
  ```powershell
  $gradle = (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin" -Recurse -Filter "gradle.bat" | Select-Object -First 1 -ExpandProperty FullName)
  Set-Location "a:\Phil\Phil Docs\Development\commute-buddy\android"
  & $gradle :app:testDebugUnitTest
  ```
- **No heredoc in PowerShell.** Use `-m "single line message"` for git commits or write to a temp file.
- **Path spaces require quoting.** The workspace path contains spaces (`Phil Docs`). Use `Set-Location` + `& command` instead of `cd && command`.
- **Garmin build:** The Connect IQ extension uses `$env:USERPROFILE\.garmin\developer_key`. Do NOT use `$env:USERPROFILE\.garmin\connectiq\developer_key`.

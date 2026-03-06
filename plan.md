# Active Development

## FEAT-07: Commute Profile Configuration

### Description
As a commuter, I want to configure my commute legs (lines, direction, stations) and alternate lines through the Android app UI, so that the decision engine uses my actual route instead of a hardcoded profile.

Currently the commute profile is hardcoded in `MainActivity.kt` as a `SYSTEM_PROMPT` constant (lines 37-69), the `MONITORED_ROUTES` set is hardcoded in `MtaAlertParser.kt` (line 18), and the direction is hardcoded to `"TO_WORK"` (line 386). This story makes all three configurable: the user defines their TO_WORK and TO_HOME legs independently via a settings screen using structured input controls (line chips, direction dropdown, station text fields), toggles direction on the main screen, and the system prompt, monitored routes, and direction are all derived dynamically from the saved profile.

### Acceptance Criteria

1. **Commute profile data model**
   - A `CommuteLeg` has: `lines: List<String>`, `direction: String`, `fromStation: String`, `toStation: String`
   - A `CommuteProfile` has: `toWorkLegs: List<CommuteLeg>`, `toHomeLegs: List<CommuteLeg>`, `alternates: List<String>`
   - The profile is persisted in SharedPreferences (JSON serialization) and survives app restarts
   - If no profile is saved, the app pre-populates the current hardcoded Astoria commute as the default (TO_WORK: N,W->4,5->6; TO_HOME: R,W->N,W)

2. **Profile configuration UI -- structured input controls**
   - A settings/configuration screen (new Activity) accessible from the main screen via a "Configure Commute" button
   - TO_WORK and TO_HOME legs are displayed as separate ordered lists, each independently configurable
   - **Line selection:** bottom sheet picker triggered by a "Select" button next to a compact summary (e.g., "Lines: N, W"). The bottom sheet displays all MTA subway lines (1, 2, 3, 4, 5, 6, 7, A, B, C, D, E, F, G, J, L, M, N, Q, R, S, W, Z) as a multi-select chip grid (`ChipGroup` with `FilterChip`). User taps selections, confirms, and the bottom sheet closes. Same approach for alternates.
   - **Direction:** dropdown/spinner with fixed options (Manhattan-bound, Queens-bound, Uptown, Downtown, Bronx-bound, Brooklyn-bound)
   - **Stations:** free-text fields for "from" and "to" -- the only typed input
   - Each leg in the form is compact: selected lines summary + direction dropdown + two station fields
   - User can add and remove legs for each direction
   - Input validation: at least one leg required for each direction; each leg must have at least one line selected, a direction chosen, and both station fields non-empty; alternates are optional
   - Save button persists the profile and returns to the main screen

3. **Commute direction toggle**
   - The main screen has a toggle (segmented button or switch) to select TO_WORK or TO_HOME
   - The selected direction is used in `buildPromptText()` and determines which leg set the system prompt references
   - Default direction is TO_WORK
   - Direction selection persists across app restarts

4. **Dynamic system prompt generation**
   - The `SYSTEM_PROMPT` constant is replaced by a function that generates the prompt from the saved `CommuteProfile` and current direction
   - The commute profile section of the prompt is dynamically built from the configured legs and alternates
   - The decision framework, direction matching rules, alert freshness rules, and alternate line evaluation sections remain unchanged (static text)
   - The `GenerativeModel` is re-initialized when the profile changes (since system instruction is set at model creation time with Firebase AI Logic SDK)

5. **Dynamic MONITORED_ROUTES**
   - `MONITORED_ROUTES` is derived at runtime from all unique lines across both directions' legs plus alternates -- no longer a hardcoded constant
   - Route filtering in the live pipeline uses the derived set
   - If the profile changes, the monitored routes update on the next fetch

6. **Existing pipeline behavior is preserved**
   - "Fetch Live" continues to work identically -- fetch -> parse -> filter -> prompt -> Gemini -> display -> BLE push
   - Tier POC buttons continue to function (they use the system prompt which now includes the configured profile)
   - All error handling paths remain unchanged
   - Unit tests for `MtaAlertParser`, `CommuteStatus`, and `ApiRateLimiter` continue to pass

### Out of Scope
- Station autocomplete or station database (~470 stations; not worth the complexity yet since station names only appear in the Gemini prompt as context -- minor variations don't break matching)
- Auto-deriving TO_HOME from TO_WORK (routes are independently different strategies)
- Multiple commute profiles (just one active profile)
- Time-based automatic direction switching (FEAT-08's commute window)
- Garmin-side profile display or configuration
- Drag-and-drop leg reordering (add-in-order is sufficient)

### Implementation Plan

#### Increment 1: Data model, persistence, and dynamic prompt generation
- [x] Create `CommuteLeg` data class (`lines: List<String>`, `direction: String`, `fromStation: String`, `toStation: String`) with `toJson()`/`fromJson()` serialization
- [x] Create `CommuteProfile` data class (`toWorkLegs`, `toHomeLegs`, `alternates`) with `toJson()`/`fromJson()` and `monitoredRoutes(): Set<String>` that derives all unique lines from both directions' legs plus alternates
- [x] Create `CommuteProfileRepository` class -- persists `CommuteProfile` to SharedPreferences as JSON string; `load()` returns saved profile or a default pre-populated with the current Astoria commute (TO_WORK: N,W->4,5->6; TO_HOME: R,W->N,W; alternates: F,R,7)
- [x] Create `SystemPromptBuilder` object with `buildSystemPrompt(profile: CommuteProfile): String` -- generates the full system prompt by inserting both TO_WORK and TO_HOME leg blocks into the commute profile section; decision framework, direction matching rules, alert freshness rules, and output schema remain static text
- [x] Write unit tests: `CommuteProfileTest.kt` -- round-trip serialization, `monitoredRoutes()` derivation (covers both directions + alternates, no duplicates), default profile contents; `SystemPromptBuilderTest.kt` -- generated prompt contains leg lines/directions/stations, alternates, static framework sections unchanged

**Testing:** Run `gradle :app:testDebugUnitTest`. Verify new tests pass and all existing tests still pass.
**Model: Sonnet** | Reason: New data model design, JSON serialization, prompt text generation -- requires understanding the existing system prompt structure and making correct design decisions.

#### Increment 2: Wire dynamic profile and direction toggle into live pipeline
- [x] In `MainActivity`, replace `SYSTEM_PROMPT` constant with call to `SystemPromptBuilder.buildSystemPrompt()` using profile from `CommuteProfileRepository`; re-initialize `GenerativeModel` in `initGeminiFlash()` using the dynamic prompt
- [x] In `MainActivity`, replace hardcoded `MONITORED_ROUTES` reference (line 376) with `profile.monitoredRoutes()`; remove the `MONITORED_ROUTES` top-level constant from `MtaAlertParser.kt`
- [x] In `MainActivity`, replace hardcoded `"TO_WORK"` (line 386) with a `currentDirection` variable
- [x] Add a TO_WORK / TO_HOME segmented button (Material `MaterialButtonToggleGroup`) above the "Fetch Live" button in `activity_main.xml`; selection updates `currentDirection` and persists to SharedPreferences; default TO_WORK
- [x] When direction changes, no model re-init needed (direction is in the user prompt, not system prompt); just update `currentDirection`
- [x] Update `MtaAlertParserTest.kt` -- tests that reference the removed `MONITORED_ROUTES` constant should use an inline set or test `CommuteProfile.monitoredRoutes()` instead

**Testing:** Run `gradle :app:testDebugUnitTest` -- all tests pass. Deploy to phone, verify "Fetch Live" works with the default profile (same behavior as before). Toggle TO_HOME and fetch again -- confirm the direction in the Gemini prompt changes (check Logcat or results output).
**Model: Sonnet** | Reason: Cross-file wiring with state management -- needs to correctly thread profile/direction through the pipeline without breaking existing behavior.

#### Increment 3: Configuration Activity with text-based line input
- [ ] Create `activity_commute_profile.xml` layout: two sections (TO_WORK, TO_HOME) each with a vertical list of leg cards + "Add Leg" button; below both sections, an alternates field; Save button at bottom; entire form scrollable
- [ ] Each leg card contains: a lines text field (comma-separated, e.g. "N,W"), a direction `Spinner` (fixed options: Manhattan-bound, Queens-bound, Uptown, Downtown, Bronx-bound, Brooklyn-bound), from/to station text fields, and a remove button
- [ ] Create `CommuteProfileActivity.kt` -- loads profile from `CommuteProfileRepository` on create, populates the form, dynamically adds/removes leg card views; Save validates (at least one leg per direction, each leg has lines + direction + stations), persists via repository, and finishes the activity
- [ ] Add "Configure Commute" button to `activity_main.xml` (above the direction toggle); launches `CommuteProfileActivity`; on return (`onResume`), reload profile from repository and re-initialize `GenerativeModel` with updated system prompt
- [ ] Register `CommuteProfileActivity` in `AndroidManifest.xml`
- [ ] Add string resources for the configuration screen labels and validation messages

**Testing:** Deploy to phone. Tap "Configure Commute", verify default Astoria legs appear. Edit a station name, save, return to main screen, "Fetch Live" -- confirm pipeline uses updated profile. Add a leg, remove a leg, verify validation catches empty fields. Kill and restart app -- verify profile persists.
**Model: Sonnet** | Reason: New Activity with dynamic view management, form validation, and lifecycle integration -- needs careful handling of view inflation, state, and data flow.

#### Increment 4: Bottom sheet line picker replaces text input
- [ ] Create `LinePickerBottomSheet` -- a `BottomSheetDialogFragment` containing a `ChipGroup` with `FilterChip` for each MTA subway line (1, 2, 3, 4, 5, 6, 7, A, B, C, D, E, F, G, J, L, M, N, Q, R, S, W, Z); pre-selects chips based on currently selected lines; "Done" button returns selections via a callback
- [ ] Create `line_picker_bottom_sheet.xml` layout -- chip grid with title ("Select Lines"), chips arranged in a flow layout, and Done button
- [ ] In `CommuteProfileActivity`, replace the lines `EditText` in each leg card with a read-only "Lines: N, W" summary `TextView` + "Select" button that opens `LinePickerBottomSheet`; on callback, update the leg's lines and refresh the summary text
- [ ] Use the same `LinePickerBottomSheet` for the alternates field -- "Select" button opens the picker, callback updates alternates
- [ ] Verify chip grid fits on screen without scrolling (~4 rows of 6-7 chips)

**Testing:** Deploy to phone. Tap "Configure Commute", tap "Select" on a leg's lines -- verify bottom sheet appears with chip grid, pre-selected chips match current lines. Tap to select/deselect, hit Done -- verify summary updates. Same for alternates. Save and "Fetch Live" -- confirm pipeline uses the new selections. Test on a smaller screen or with large font to confirm chip grid doesn't require scrolling.
**Model: Sonnet** | Note: BottomSheetDialogFragment + ChipGroup is standard Material Components usage, but wiring the callback into dynamic leg cards needs care with view references.

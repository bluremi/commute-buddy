# Active Development

## FEAT-02: AI Summarization POC

### Description
As a developer, I want to validate that on-device Gemini Nano can reliably parse MTA-style alert text into the strict commute status JSON schema, so that I can confidently build the real data pipeline on top of it.

This is a proof-of-concept isolated from BLE, the watch, and real MTA data. The goal is to answer one question: can Gemini Nano, given a system prompt and a short alert string, consistently return valid JSON matching our schema (`status`, `route_string`, `reason`, `timestamp`)? The POC uses hardcoded static alert strings sourced from the real MTA GTFS-RT feed and a dedicated test UI — no network calls, no protobuf, no watch communication.

### Acceptance Criteria

1. **Gemini Nano Dependencies Integrated**
   - `app/build.gradle.kts` includes the necessary ML Kit GenAI / AICore dependencies
   - Project builds and Gradle syncs successfully after dependency addition
   - `minSdk` remains 34; no change to existing FEAT-01 functionality

2. **Test UI Added to MainActivity**
   - A "Test AI" button is visible below the existing Send Code UI
   - A results TextView displays the model's output (or errors)
   - A label shows which test tier is currently selected
   - Existing FEAT-01 UI elements (Send Code button, code display, status line) remain functional and unchanged

3. **Hardcoded Test Inputs Using Real MTA Alert Text**
   - Test data is sourced from actual MTA GTFS-RT alert text (not synthetic), organized into four tiers:
     - **Tier 1 — Short (~100 chars):** A real-time delay header with no description body (e.g., `[Q] trains are delayed entering and leaving 96 St while we request NYPD for someone being disruptive at that station.`)
     - **Tier 2 — Medium (~500 chars):** A stops-skipped or reroute alert with one travel alternative (e.g., `[E]` skipping Briarwood with transfer instructions)
     - **Tier 3 — Long (~900 chars):** A planned suspension with shuttle buses, split service, multiple transfer points, and ADA notice (e.g., `[2]` partial suspension with GO ticket instructions)
     - **Tier 4 — Stress test (~2000+ chars):** A synthetic worst-case constructed by combining real long alerts, mimicking a weekend construction dump affecting multiple lines — designed to test behavior at or beyond Gemini Nano's context window limit
   - Each button press cycles to the next tier so all cases can be exercised
   - The current tier label updates to show which test case is active

4. **System Prompt and Model Invocation**
   - A system prompt instructs the model to return only a JSON object with the exact fields: `status` (0/1/2), `route_string` (max 15 chars), `reason` (max 40 chars), `timestamp` (epoch int)
   - The hardcoded alert string and system prompt are passed to the on-device Gemini Nano model
   - Inference runs on-device — no network call for the LLM step

5. **Response Deserialization**
   - The model's raw text response is parsed and deserialized into a Kotlin data class matching the BLE schema (`status: Int`, `routeString: String`, `reason: String`, `timestamp: Long`)
   - On success: the results TextView displays the parsed fields in a readable format
   - On failure: the results TextView displays the raw model output and the parsing error message, so the developer can diagnose prompt/output issues

6. **Model Availability Handling**
   - If AICore / Gemini Nano is not available on the device (e.g., not a Pixel 8+, model not downloaded), the app displays a clear error message instead of crashing
   - The "Test AI" button is disabled or shows an explanatory message when the model is unavailable

### Implementation Plan

#### Increment 1: Gradle dependencies and CommuteStatus data class
- [x] Add Gemini Nano on-device AI dependency to `android/app/build.gradle.kts` (`com.google.ai.edge.aicore:aicore` or current ML Kit GenAI equivalent)
- [x] Create `CommuteStatus.kt` data class in `com.commutebuddy.app` with fields matching `shared/schema.json`: `status: Int`, `routeString: String`, `reason: String`, `timestamp: Long`
- [x] Add JSON deserialization support (Kotlin `org.json` from Android stdlib — no new library needed)
- [x] Add new string resources in `strings.xml` for test UI labels (test button text, tier labels, error messages)
- [x] Verify Gradle sync succeeds and project builds without errors; run existing FEAT-01 functionality to confirm no regressions

**Testing:** Manually verify: Gradle sync completes, project builds, deploy to device/emulator and confirm existing Send Code flow still works.
**Model: Composer** | Reason: Mechanical dependency additions and simple data class creation from a clear schema.

#### Increment 2: Test UI and hardcoded MTA alert test data
- [ ] Add a visual separator and "AI Summarization POC" section to `activity_main.xml` below the existing Send Code UI: a tier label (`TextView`), a "Test AI" button, and a scrollable results area (`ScrollView` > `TextView`)
- [ ] Create `MtaTestData.kt` object containing 4 tiers of hardcoded test strings sourced from real MTA GTFS-RT alert text in `docs/mta-feed-research.md`:
  - Tier 1 (~100 chars): Real-time delay header
  - Tier 2 (~500 chars): Stops-skipped/reroute with transfer instructions
  - Tier 3 (~900 chars): Planned suspension with shuttles, split service, ADA notice
  - Tier 4 (~2000+ chars): Synthetic worst-case combining multiple long alerts
- [ ] Wire tier cycling in `MainActivity.kt`: each "Test AI" press cycles to the next tier, updates the tier label, and (for now) displays the raw input text in the results area
- [ ] Ensure all existing FEAT-01 UI elements remain visible and functional above the new section

**Testing:** Manually verify: open app, confirm new UI section visible below Send Code. Press "Test AI" button repeatedly — tier label cycles through "Tier 1: Short" → "Tier 2: Medium" → "Tier 3: Long" → "Tier 4: Stress" → back to Tier 1. Results area shows the raw alert text for each tier. Scroll the results area for long text. Confirm Send Code button still works.
**Model: Composer** | Note: Layout XML and wiring are mechanical. The test data strings in `MtaTestData.kt` should be sourced carefully from the examples in `docs/mta-feed-research.md` — provide the real MTA text inline in the prompt.

#### Increment 3: Gemini Nano initialization and availability handling
- [ ] In `MainActivity.kt`, initialize the on-device `GenerativeModel` (Gemini Nano) during `onCreate`, using an async check for model availability
- [ ] Define the system prompt as a constant: instructs the model to return **only** a JSON object with fields `status` (0=Normal, 1=Deviate, 2=Error), `route_string` (max 15 chars), `reason` (max 40 chars), `timestamp` (epoch seconds as long) — no markdown fencing, no explanation
- [ ] If model is unavailable (not a supported device, AICore not installed, model not downloaded): disable the "Test AI" button, display a clear error message in the results area explaining why (e.g., "Gemini Nano not available — requires Pixel 8+ with AICore")
- [ ] If model is available: enable the button and show "Model ready" in the results area

**Testing:** Manually verify on two scenarios: (1) On a Pixel 8+ / supported device with AICore: app launches, results area shows "Model ready", Test AI button is enabled. (2) On an unsupported device or emulator: app launches without crashing, results area shows the unavailability message, Test AI button is disabled. In both cases, existing FEAT-01 Send Code flow is unaffected.
**Model: Sonnet** | Reason: Gemini Nano API integration requires understanding async initialization patterns, AICore availability checks, and proper error state handling — not purely mechanical.

#### Increment 4: Model invocation and response deserialization
- [ ] On "Test AI" button press: send the system prompt + current tier's alert text to the Gemini Nano model via async inference call; show a "Processing..." indicator while waiting
- [ ] On model response: attempt to parse the raw text as JSON and deserialize into `CommuteStatus`
- [ ] On success: display the parsed fields in a readable format in the results area (e.g., `Status: 1 (Deviate) | Route: N/W | Reason: Signal problems at Queensboro Plaza | Time: 1709312400`)
- [ ] On failure (invalid JSON, missing fields, unexpected format): display the raw model output **and** the parsing error message in the results area, so the developer can diagnose prompt issues
- [ ] Handle model inference errors (timeout, crash, empty response) gracefully — display the error without crashing

**Testing:** Manually verify on a supported device: press "Test AI" for each of the 4 tiers. For each tier, confirm: (a) "Processing..." appears briefly, (b) results show either parsed fields or raw output + error. Specifically test: Tier 1 should reliably produce valid JSON; Tier 4 (stress) may fail or produce truncated output — that's expected and the error should be clearly displayed. Try pressing the button rapidly to verify no crashes from concurrent calls.
**Model: Sonnet** | Reason: Async inference, JSON parsing with multiple failure modes, and careful error/edge-case handling require real reasoning, not mechanical pattern-following.

### Out of Scope
- Real MTA data fetching or protobuf parsing (FEAT-03)
- BLE transmission of the AI-generated payload to the watch
- Prompt tuning or retry logic for unreliable outputs — this POC is for observing raw reliability
- Persistent storage of results
- Any changes to the Garmin watch app

# Active Development

## FEAT-02: AI Summarization POC

### Description
As a developer, I want to validate that the Gemini 1.5 Flash cloud API can reliably parse MTA-style alert text into the strict commute status JSON schema, so that I can confidently build the real data pipeline on top of it.

This is a proof-of-concept isolated from BLE, the watch, and real MTA data. The goal is to answer one question: can Gemini 1.5 Flash, given a system prompt and an alert string, consistently return valid JSON matching our schema (`status`, `route_string`, `reason`, `timestamp`)? The POC uses hardcoded static alert strings sourced from the real MTA GTFS-RT feed and a dedicated test UI — no protobuf, no watch communication.

**Architecture pivot (2026-03-02):** Originally designed for on-device Gemini Nano via AICore. Abandoned because AICore requires a ~1GB background model download before the app can function — unacceptable UX. Gemini 1.5 Flash via Google AI Studio is a better fit: massive context window (handles even the longest MTA alerts), native JSON structured output, and zero cost on the free tier (15 RPM, 1,500 RPD). This is a two-way door — if the app is ever published, a paywall or bring-your-own-key model can be added.

### Acceptance Criteria

1. **Gemini 1.5 Flash SDK Integrated**
   - `app/build.gradle.kts` includes the Google GenAI SDK (`com.google.ai.client.generativeai:generativeai`)
   - All Gemini Nano / ML Kit GenAI / AICore dependencies and code are removed
   - API key is loaded from `local.properties` via `BuildConfig` (not hardcoded, not checked into version control)
   - Project builds and Gradle syncs successfully; `minSdk` remains 34; no change to existing FEAT-01 functionality

2. **Test UI Added to MainActivity** *(unchanged from original — already built)*
   - A "Test AI" button is visible below the existing Send Code UI
   - A results TextView displays the model's output (or errors)
   - A label shows which test tier is currently selected
   - Existing FEAT-01 UI elements (Send Code button, code display, status line) remain functional and unchanged

3. **Hardcoded Test Inputs Using Real MTA Alert Text** *(unchanged from original — already built)*
   - Test data is sourced from actual MTA GTFS-RT alert text (not synthetic), organized into four tiers:
     - **Tier 1 — Short (~100 chars):** A real-time delay header with no description body
     - **Tier 2 — Medium (~500 chars):** A stops-skipped or reroute alert with one travel alternative
     - **Tier 3 — Long (~900 chars):** A planned suspension with shuttle buses, split service, multiple transfer points, and ADA notice
     - **Tier 4 — Stress test (~2000+ chars):** A synthetic worst-case combining real long alerts
   - Each button press cycles to the next tier so all cases can be exercised

4. **System Prompt and Model Invocation**
   - A system prompt instructs the model to return only a JSON object with the exact fields: `status` (0/1/2), `route_string` (max 15 chars), `reason` (max 40 chars), `timestamp` (epoch int)
   - The hardcoded alert string and system prompt are sent to Gemini 1.5 Flash via the cloud API
   - All API calls are gated by the rate limiter (see AC 6)

5. **Response Deserialization**
   - The model's raw text response is parsed and deserialized into a Kotlin data class matching the BLE schema (`status: Int`, `routeString: String`, `reason: String`, `timestamp: Long`)
   - On success: the results TextView displays the parsed fields in a readable format
   - On failure: the results TextView displays the raw model output and the parsing error message, so the developer can diagnose prompt/output issues

6. **Strict Cost Safeguards (Zero-Runaway Guarantee)**
   - A multi-layer rate limiter makes it virtually impossible for a bug, UI loop, or rapid button mashing to cause runaway API costs:
     - **Hard daily request cap** persisted in SharedPreferences (survives app restarts/crashes)
     - **Per-minute rate limit** preventing burst scenarios
     - **Minimum cooldown** between consecutive requests
     - **Single-flight mutex** — only one API call in-flight at a time
     - **No automatic retries** — failed requests require manual re-trigger
   - When any limit is hit, the UI displays a clear message explaining which limit was reached
   - Budget warning shown when approaching daily cap

7. **API Key Setup Documentation**
   - Clear instructions in the increment description for how to obtain a Google AI Studio API key and configure `local.properties`

### Implementation Plan

#### Increment 1: Gradle dependencies and CommuteStatus data class
- [x] Add Gemini Nano on-device AI dependency to `android/app/build.gradle.kts` *(will be swapped to cloud SDK in Increment 3)*
- [x] Create `CommuteStatus.kt` data class in `com.commutebuddy.app` with fields matching `shared/schema.json`: `status: Int`, `routeString: String`, `reason: String`, `timestamp: Long`
- [x] Add JSON deserialization support (Kotlin `org.json` from Android stdlib — no new library needed)
- [x] Add new string resources in `strings.xml` for test UI labels (test button text, tier labels, error messages)
- [x] Verify Gradle sync succeeds and project builds without errors; run existing FEAT-01 functionality to confirm no regressions

**Testing:** Manually verify: Gradle sync completes, project builds, deploy to device/emulator and confirm existing Send Code flow still works.
**Model: Composer** | Reason: Mechanical dependency additions and simple data class creation from a clear schema.

#### Increment 2: Test UI and hardcoded MTA alert test data
- [x] Add a visual separator and "AI Summarization POC" section to `activity_main.xml` below the existing Send Code UI: a tier label (`TextView`), a "Test AI" button, and a scrollable results area (`ScrollView` > `TextView`)
- [x] Create `MtaTestData.kt` object containing 4 tiers of hardcoded test strings sourced from real MTA GTFS-RT alert text in `docs/mta-feed-research.md`:
  - Tier 1 (~100 chars): Real-time delay header
  - Tier 2 (~500 chars): Stops-skipped/reroute with transfer instructions
  - Tier 3 (~900 chars): Planned suspension with shuttles, split service, ADA notice
  - Tier 4 (~2000+ chars): Synthetic worst-case combining multiple long alerts
- [x] Wire tier cycling in `MainActivity.kt`: each "Test AI" press cycles to the next tier, updates the tier label, and (for now) displays the raw input text in the results area
- [x] Ensure all existing FEAT-01 UI elements remain visible and functional above the new section

**Testing:** Manually verify: open app, confirm new UI section visible below Send Code. Press "Test AI" button repeatedly — tier label cycles through "Tier 1: Short" → "Tier 2: Medium" → "Tier 3: Long" → "Tier 4: Stress" → back to Tier 1. Results area shows the raw alert text for each tier. Scroll the results area for long text. Confirm Send Code button still works.
**Model: Composer** | Note: Layout XML and wiring are mechanical. The test data strings in `MtaTestData.kt` should be sourced carefully from the examples in `docs/mta-feed-research.md` — provide the real MTA text inline in the prompt.

#### Increment 3: Swap to Gemini 1.5 Flash cloud SDK and API key configuration
- [x] **Remove** the Gemini Nano dependency from `android/app/build.gradle.kts`: delete `implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")`
- [x] **Add** the Google Generative AI SDK: `implementation("com.google.ai.client.generativeai:generativeai:0.9.0")`
- [x] **Add** `INTERNET` permission to `AndroidManifest.xml` (required for cloud API calls)
- [x] **Configure API key injection via BuildConfig:**
  - Add to `android/app/build.gradle.kts` inside the `defaultConfig` block: `buildConfigField("String", "GEMINI_API_KEY", "\"${project.findProperty("GEMINI_API_KEY") ?: ""}\"")` and `buildFeatures { buildConfig = true }`
  - The key is read from `local.properties` (which is `.gitignore`'d by default)
- [x] **Strip all Nano-specific code from `MainActivity.kt`:**
  - Remove imports: `com.google.mlkit.genai.*`
  - Remove the `generativeModel` field and its ML Kit type
  - Remove `initGeminiNano()` and `downloadGeminiNano()` methods entirely
- [x] **Replace with Gemini Flash initialization:**
  - Create a `GenerativeModel` instance using `GenerativeModel(modelName = "gemini-1.5-flash", apiKey = BuildConfig.GEMINI_API_KEY)` with the existing `SYSTEM_PROMPT` passed as `systemInstruction`
  - On `onCreate`: if API key is blank, disable Test AI button and show "API key not configured — see local.properties"; otherwise enable button and show "Model ready (Gemini 1.5 Flash)"
- [x] **Update string resources** in `strings.xml`: replace Nano-specific messages (model downloading, AICore unavailable, etc.) with cloud-appropriate messages (API key missing, model ready)
- [x] **Verify** Gradle sync, build, and deploy — app launches, shows "Model ready" with a valid key, shows "API key not configured" without one; FEAT-01 Send Code still works

**Human setup (do this before testing):**
1. Go to [Google AI Studio](https://aistudio.google.com/apikey) and create an API key (free, takes 30 seconds)
2. Open `android/local.properties` and add: `GEMINI_API_KEY=your_key_here`
3. Rebuild the project (the key is injected at compile time via BuildConfig)

**Testing:** Deploy to device/emulator. (1) With a valid API key in `local.properties`: app launches, results area shows "Model ready (Gemini 1.5 Flash)", Test AI button is enabled. (2) Without a key (or blank): results area shows "API key not configured", Test AI button is disabled. (3) FEAT-01 Send Code button works in both scenarios.
**Model: Sonnet** | Reason: Dependency swap, BuildConfig injection, and stripping/replacing async init code requires careful reasoning about the existing codebase.

#### Increment 4: ApiRateLimiter with unit tests
- [x] **Add test dependencies** to `android/app/build.gradle.kts`: `testImplementation("junit:junit:4.13.2")`, `testImplementation("io.mockk:mockk:1.13.13")`
- [x] **Create `ApiRateLimiter.kt`** in `com.commutebuddy.app` — a self-contained rate limiter with injectable dependencies (`clock: () -> Long` for time, `SharedPreferences` for persistence) and the following layers:

  **Layer 1 — Hard daily cap (persisted, nuclear backstop):**
  - Uses `SharedPreferences` to store `{ "date": "2026-03-02", "count": 47 }`
  - On each `tryAcquire()`: if stored date != today, reset count to 0. If count >= `DAILY_CAP` (50), return `Denied.DailyCapExhausted`
  - This limit **survives app restarts, process kills, and crashes** — it is the absolute last line of defense
  - `DAILY_CAP = 50` (well under the free tier's 1,500/day — leaves massive headroom)

  **Layer 2 — Per-minute rate limit (in-memory, burst protection):**
  - Maintains a `MutableList<Long>` of request timestamps
  - On each `tryAcquire()`: prune entries older than 60 seconds. If remaining size >= `PER_MINUTE_CAP` (10), return `Denied.MinuteCapExhausted`
  - Catches burst scenarios like the app being restarted many times in a minute

  **Layer 3 — Minimum cooldown (rapid-fire protection):**
  - Tracks `lastRequestTimeMs: Long`
  - On each `tryAcquire()`: if `now - lastRequestTimeMs < COOLDOWN_MS` (3000), return `Denied.CooldownActive(remainingMs)`
  - Prevents accidental double-taps and UI event storms

  **Layer 4 — Single-flight mutex (concurrency protection):**
  - An `AtomicBoolean` flag: `isRequestInFlight`
  - On each `tryAcquire()`: if flag is already `true`, return `Denied.RequestInFlight`
  - Caller sets to `true` before API call, sets to `false` in a `finally` block after
  - Prevents concurrent duplicate calls from coroutine races

  **Layer 5 — No automatic retries (eliminates retry-loop class of bugs):**
  - This is an architectural decision, not code: the `onTestAiClicked()` handler calls the API exactly once. On any failure (network, parsing, rate limit), it displays the error and stops. There is no retry loop, no exponential backoff, no "try again in N seconds" automation. The user must manually press the button to retry.

  **Budget warning:**
  - When daily count exceeds 80% of `DAILY_CAP`, `tryAcquire()` returns `Allowed(warningMessage = "40/50 daily requests used")`
  - The UI displays this warning alongside the result

  **Return type:**
  ```kotlin
  sealed class RateLimitResult {
      data class Allowed(val warningMessage: String? = null) : RateLimitResult()
      sealed class Denied(val reason: String) : RateLimitResult() {
          data object DailyCapExhausted : Denied("Daily request limit reached (resets tomorrow)")
          data object MinuteCapExhausted : Denied("Too many requests this minute — wait 60s")
          data class CooldownActive(val remainingMs: Long) : Denied("Please wait ${remainingMs / 1000}s")
          data object RequestInFlight : Denied("Request already in progress")
      }
  }
  ```

- [x] **Create `ApiRateLimiterTest.kt`** in `android/app/src/test/kotlin/com/commutebuddy/app/` with unit tests covering:

  **Daily cap tests:**
  - First request of the day → `Allowed`
  - Request at exactly `DAILY_CAP` → `DailyCapExhausted`
  - Date rollover resets the counter (advance the injected clock past midnight) → `Allowed` again
  - Counter persists across new `ApiRateLimiter` instances (simulates app restart via same `SharedPreferences`)

  **Per-minute cap tests:**
  - 10 requests within 60s → 11th returns `MinuteCapExhausted`
  - After 60s elapses (advance clock) → `Allowed` again

  **Cooldown tests:**
  - Request, then immediate second request → `CooldownActive` with remaining time
  - Request, advance clock past `COOLDOWN_MS` → `Allowed`

  **Single-flight tests:**
  - Set in-flight flag, call `tryAcquire()` → `RequestInFlight`
  - Clear flag, call `tryAcquire()` → `Allowed`

  **Budget warning tests:**
  - At 80% of daily cap → `Allowed` with non-null `warningMessage`
  - Below 80% → `Allowed` with null `warningMessage`

  **Layer priority tests:**
  - In-flight check fires before cooldown check (so you don't get a misleading "wait 3s" when the real issue is a concurrent call)
  - Daily cap fires even if per-minute and cooldown are both clear

- [x] **Run tests** via `./gradlew :app:test` and confirm all pass

**Testing:** Run `./gradlew :app:test` — all tests pass. No manual testing needed for this increment; the unit tests are the deliverable.
**Model: Sonnet** | Reason: The rate limiter has multiple interacting layers with injectable dependencies and time-sensitive logic. The test suite must exercise each layer in isolation and verify their priority ordering. Requires careful reasoning.

#### Increment 5: Model invocation and response deserialization
- [ ] **Wire model invocation in `MainActivity.kt`:**
  - On "Test AI" press: first call `rateLimiter.tryAcquire()`
  - If `Denied`: display the denial reason in results area and return immediately (button stays enabled so user sees the message)
  - If `Allowed`: disable button, show "Processing…", launch coroutine to call `generativeModel.generateContent(currentTierAlertText)`, increment daily counter, set in-flight flag
  - In the `finally` block: clear in-flight flag, re-enable button after cooldown

- [ ] **Response deserialization:**
  - Extract the text from the model response
  - Attempt to parse as JSON and deserialize into `CommuteStatus` using the existing `fromJson()` method
  - On success: display parsed fields in a readable format (e.g., `Status: 1 (Delays) | Route: N,W | Reason: Signal problems at 96 St | Time: 1709312400`)
  - On failure (invalid JSON, missing fields): display the raw model output **and** the parse error, so the developer can diagnose prompt issues
  - If `Allowed` had a `warningMessage`, prepend it to the output (e.g., `⚠ 42/50 daily requests used`)

- [ ] **Handle API errors gracefully:**
  - Network errors, timeouts, empty responses, HTTP 429 (rate limited by Google) — all display a clear error message without crashing
  - None of these trigger an automatic retry

- [ ] **Update string resources** for rate limiter denial/warning messages

**Testing:** Deploy to device with a valid API key.
1. **Happy path:** Press "Test AI" for each of the 4 tiers. Confirm: "Processing…" appears, then parsed JSON fields (or raw output + parse error for unexpected formats). Tier 1 (short) should reliably produce valid JSON. All 4 tiers should return a response (Flash's context window handles even Tier 4 easily).
2. **Cooldown:** Press the button, wait for result, immediately press again — should see "Please wait Ns" denial.
3. **Single-flight:** Try to trigger two rapid presses before the first returns — should see "Request already in progress" on the second.
4. **Budget warning:** Temporarily set `DAILY_CAP = 5` in code, press 4 times — 5th attempt should show warning alongside the result. Restore after testing.
5. **No API key:** Already verified in Increment 3 — button is disabled, no API call possible.
6. **Network off:** Turn off Wi-Fi/data, press button — should see a network error message, no crash, no retry loop.
7. **Unit tests still pass:** Run `./gradlew :app:test` to confirm Increment 4 tests are not broken by the wiring.

**Model: Sonnet** | Reason: Async API invocation, JSON parsing with multiple failure modes, and integration with the rate limiter require careful reasoning about error paths and state management.

### Out of Scope
- Real MTA data fetching or protobuf parsing (FEAT-03)
- BLE transmission of the AI-generated payload to the watch
- Prompt tuning or retry logic for unreliable outputs — this POC is for observing raw reliability
- Persistent storage of results
- Any changes to the Garmin watch app

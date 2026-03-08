# Active Development

## FEAT-10: Token Usage Optimization

### Description
As a commuter using Commute Buddy, I want faster Gemini API responses, so that my watch receives timely recommendations without intermittent 7–12 second latency spikes.

The current `buildPromptText()` includes an `Active period:` line for every alert, which is redundant because `filterByActivePeriod()` has already guaranteed only currently-active alerts reach Gemini. Planned work alerts (e.g., W train "No Scheduled Service") carry 100+ discrete ISO 8601 time windows spanning months — this bloats the input token count and forces the model to do expensive chronological math that produces no additional value. Separately, `description_text` for planned work can be 800–1,500 chars per alert, but the model makes decisions primarily from `header_text`; description adds diminishing returns past ~400 chars. Removing active periods and capping descriptions should significantly reduce input tokens and eliminate the latency spikes.

### Acceptance Criteria

1. **Active period removed from user prompt**
   - `buildPromptText()` no longer emits the `Active period:` line for any alert
   - The `formatActivePeriod()` private helper is deleted (dead code)
   - Existing unit tests in `MtaAlertParserTest.kt` that assert `Active period:` presence are updated or removed

2. **Planned work freshness rule removed from system prompt**
   - `SystemPromptBuilder.buildSystemPrompt()` no longer includes the bullet "Planned work with a defined active_period: trust the time window; if current time is outside the window, ignore the alert."
   - A brief replacement note (e.g., "All alerts below are pre-filtered to currently active.") is added so the model doesn't wonder about stale planned work
   - Existing unit tests in `SystemPromptBuilderTest.kt` are updated to reflect the new prompt text

3. **Description text capped at ~400 characters**
   - `buildPromptText()` truncates `descriptionText` to 400 characters with an ellipsis (`…`) when it exceeds the limit
   - Descriptions at or under 400 characters are passed through unchanged
   - Null descriptions still render as `"none"`

4. **Unit tests cover new behavior**
   - New test: description >400 chars is truncated with `…` suffix
   - New test: description exactly 400 chars is not truncated
   - New test: description <400 chars is passed through unchanged
   - Existing `buildPromptText` tests updated: no test asserts `Active period:` in output

5. **Prompt test suite updated**
   - `run-prompt-tests.py`: `SYSTEM_PROMPT` string updated to match new `SystemPromptBuilder` output (planned work freshness rule removed, pre-filtered note added)
   - `run-prompt-tests.py`: All test case prompts in the `TESTS` array have `Active period:` lines removed
   - `decision-prompt-test.md`: System prompt and test scenarios updated to match
   - `decision-prompt.md`: Canonical system prompt updated to match
   - Test 10 ("Planned overnight work, current time is morning") is reconsidered — this scenario now tests `filterByActivePeriod()` in Kotlin, not the LLM; either remove the test, replace it with a different scenario, or keep it with the understanding that the alert would never reach the prompt in production (document the rationale)

6. **All 12 prompt tests pass**
   - `run-prompt-tests.py` runs successfully against the Gemini API with the updated prompts and produces the same expected actions as before (or documents any changed expectations with rationale)

### Out of Scope
- Changes to `filterByActivePeriod()` logic (it remains unchanged)
- Changes to `MtaAlert` or `ActivePeriod` data classes (the data is still parsed and used for filtering; it's just not serialized into the prompt)
- Changes to the BLE schema or Garmin app
- Gemini model or API settings changes (temperature, thinking budget)
- Any other system prompt rewording beyond the planned work freshness rule

### Implementation Plan

#### Increment 1: Strip active period from Kotlin prompt + update unit tests
- [ ] In `MtaAlertParser.kt`: remove line 137 (`sb.appendLine("Active period: ${formatActivePeriod(alert.activePeriods)}")`) from `buildPromptText()`
- [ ] In `MtaAlertParser.kt`: delete `formatActivePeriod()` private function (lines 146–153, now dead code)
- [ ] In `MtaAlertParser.kt`: add description truncation — cap `descriptionText` at 400 chars with `…` suffix in `buildPromptText()`
- [ ] In `MtaAlertParserTest.kt`: remove 3 tests that assert `Active period:` in output (`active period shows not specified when activePeriods is empty`, `active period shows ISO timestamps when periods are present`, `active period shows open for end=0`)
- [ ] In `MtaAlertParserTest.kt`: update `single alert contains all structured field labels` to no longer assert `Active period:`
- [ ] In `MtaAlertParserTest.kt`: add 3 new tests for description truncation: >400 chars truncated with `…`, exactly 400 chars unchanged, <400 chars unchanged

**Testing:** Run unit tests via Gradle: `& $gradle :app:testDebugUnitTest --tests "com.commutebuddy.app.MtaAlertParserTest"`. All tests pass.
**Model: Composer** | Reason: Mechanical line deletions and simple string truncation logic with clear test patterns to follow.

#### Increment 2: Update system prompt + unit tests
- [ ] In `SystemPromptBuilder.kt`: remove the planned work freshness rule bullet (line 40: `"- Planned work with a defined active_period: trust the time window..."`)
- [ ] In `SystemPromptBuilder.kt`: add a replacement note at the top of ALERT FRESHNESS RULES: `"- All alerts below are pre-filtered to currently active time windows. Focus on type and posted time, not active periods.\n"`
- [ ] In `SystemPromptBuilderTest.kt`: update `generatedPrompt_containsAlertFreshnessRulesSection` — replace `assertTrue(prompt.contains("active_period"))` with assertion on the new pre-filtered note; keep `ASSUME RESOLVED` assertion

**Testing:** Run unit tests: `& $gradle :app:testDebugUnitTest --tests "com.commutebuddy.app.SystemPromptBuilderTest"`. All tests pass.
**Model: Composer** | Reason: Single-line string replacement in prompt builder and one test assertion update.

#### Increment 3: Update prompt test suite and docs
- [ ] In `run-prompt-tests.py`: update `SYSTEM_PROMPT` string — remove planned work freshness rule, add pre-filtered note (matching SystemPromptBuilder exactly)
- [ ] In `run-prompt-tests.py`: remove `Active period:` lines from all 12 test prompts in the `TESTS` array
- [ ] In `run-prompt-tests.py`: for Test 10 ("Planned overnight work"), replace with a note comment explaining this scenario is now handled by `filterByActivePeriod()` in Kotlin and remove the test case, OR keep it but remove the `Active period:` line and update the expected to acknowledge the alert text alone ("overnight hours") should still yield NORMAL — decide based on whether the model can infer timing from "overnight track maintenance" + posted time alone
- [ ] In `decision-prompt-test.md`: update system instruction — remove planned work freshness rule, add pre-filtered note
- [ ] In `decision-prompt-test.md`: remove `Active period:` lines from all 12 test scenarios
- [ ] In `decision-prompt-test.md`: update Test 10 commentary to match the `run-prompt-tests.py` decision
- [ ] In `decision-prompt.md`: update "System Prompt (final, validated)" section — remove planned work freshness rule, add pre-filtered note
- [ ] In `decision-prompt.md`: update "User Prompt Template" section — remove `Active period:` from the template

**Testing:** Run `python docs/run-prompt-tests.py` and verify all tests pass (or all minus Test 10 if removed). Manually review doc changes for consistency.
**Model: Sonnet** | Reason: Requires judgment on Test 10 disposition and careful multi-file consistency across prompt text that must match exactly.

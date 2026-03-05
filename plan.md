# Active Development

## FEAT-05: Decision Engine Integration

### Description
As a commuter, I want the Android app to produce actionable commute recommendations (NORMAL, MINOR_DELAYS, REROUTE, STAY_HOME) instead of simple status summaries, so that I know exactly what to do — not just what's happening.

The Decision Prompt POC validated that Gemini Flash can reliably classify alerts into four action tiers with direction matching, stale alert handling, and alternate line evaluation. FEAT-05 integrates that validated prompt into the live Android pipeline — replacing the 3-tier summarization (Normal/Delays/Disrupted) with the 4-tier decision framework, expanding route monitoring to include alternates (R, 7), restructuring alert input as structured per-alert blocks, and updating the BLE payload schema. The commute profile (directional legs + alternates) is hardcoded for now.

### Acceptance Criteria

1. **Decision prompt replaces summarization prompt**
   - `SYSTEM_PROMPT` in `MainActivity.kt` is replaced with the canonical decision prompt from `docs/decision-prompt.md`
   - Gemini model settings are temperature=0, thinking=low (1024 tokens)
   - Commute profile (TO_WORK legs + TO_HOME legs + alternates) is injected into the system prompt, hardcoded to the user's actual commute

2. **CommuteStatus data class uses new schema**
   - Fields: `action` (String: NORMAL/MINOR_DELAYS/REROUTE/STAY_HOME), `summary` (String, max 80 chars), `affectedRoutes` (String, comma-separated), `rerouteHint` (String?, optional — only present for REROUTE), `timestamp` (Long)
   - `fromJson()` validates action is one of the four valid tiers, summary is non-empty, affectedRoutes is non-empty (except NORMAL which may have empty affected_routes)
   - `toConnectIQMap()` emits keys matching updated `shared/schema.json`: `action`, `summary`, `affected_routes`, `reroute_hint`, `timestamp`
   - `statusLabel` maps action tiers to display strings

3. **MONITORED_ROUTES expanded to include alternates**
   - `MONITORED_ROUTES` in `MtaAlertParser.kt` changes from `{N, W, 4, 5, 6}` to `{N, W, 4, 5, 6, R, 7}`
   - Route filtering still uses the same `filterByRoutes()` logic with the expanded set

4. **Structured per-alert format in buildPromptText()**
   - Each alert is formatted as a structured block with fields: Routes, Type, Posted, Active period, Header, Description
   - Timestamps are formatted as ISO 8601
   - Missing active periods show "not specified"; missing description shows "none"
   - Current time and commute direction are included in the prompt header

5. **Commute direction is included in the prompt**
   - A hardcoded default direction (TO_WORK) is used for now
   - The direction value is passed to `buildPromptText()` and included in the Gemini request

6. **Pipeline error handling uses decision schema**
   - When the MTA fetch fails, feed parse fails, or Gemini returns unparseable output, the app creates a fallback `CommuteStatus` with `action="NORMAL"` and a summary describing the error (matching current error-path behavior of still pushing to watch)
   - Rate limiter behavior is unchanged

7. **BLE schema updated**
   - `shared/schema.json` documents the new message format: `action` (string), `summary` (string), `affected_routes` (string), `reroute_hint` (string, optional), `timestamp` (long)
   - BLE payload remains under 1KB with the expanded fields

8. **Unit tests cover all changed components**
   - `CommuteStatusTest.kt`: new field validation, `fromJson()` with all four action tiers, `toConnectIQMap()` key names and types, optional `reroute_hint` handling
   - `MtaAlertParserTest.kt`: expanded `MONITORED_ROUTES` filtering (R, 7 alerts now pass through), structured `buildPromptText()` output format
   - All existing tests updated to match new schema (no regressions)

### Out of Scope
- Garmin watch message handler updates (FEAT-06)
- Garmin glance/app UI for new action tiers (FEAT-06)
- Configurable commute profile UI (FEAT-07)
- Background polling / foreground service (FEAT-08)
- Commute direction toggle — hardcoded to TO_WORK for now (FEAT-07)
- Removing FEAT-02 tier buttons from the Android UI (they remain for debugging)

### Implementation Plan

#### Increment 1: CommuteStatus data class + BLE schema
- [ ] Rewrite `CommuteStatus.kt`: replace `status: Int` / `routeString` / `reason` with `action: String` / `summary: String` / `affectedRoutes: String` / `rerouteHint: String?` / `timestamp: Long`
- [ ] Replace constants `STATUS_NORMAL` / `STATUS_DEVIATE` / `STATUS_ERROR` with `ACTION_NORMAL` / `ACTION_MINOR_DELAYS` / `ACTION_REROUTE` / `ACTION_STAY_HOME` string constants and a `VALID_ACTIONS` set
- [ ] Update `fromJson()`: validate `action` is one of the four valid tiers, `summary` is non-empty, `affectedRoutes` may be empty for NORMAL, parse optional `reroute_hint`
- [ ] Update `toConnectIQMap()`: emit keys `action`, `summary`, `affected_routes`, `reroute_hint` (only if non-null), `timestamp`
- [ ] Update `statusLabel` to map action strings to display labels ("Normal", "Minor Delays", "Reroute", "Stay Home")
- [ ] Update `shared/schema.json` to document the new message format (action string, summary, affected_routes, reroute_hint optional, timestamp)
- [ ] Rewrite `CommuteStatusTest.kt`: test `fromJson()` for all four action tiers, optional `reroute_hint`, NORMAL with empty `affected_routes`, invalid action rejection, `toConnectIQMap()` keys/values/types

**Testing:** Run unit tests via Gradle: `& $gradle :app:testDebugUnitTest --tests "com.commutebuddy.app.CommuteStatusTest"`. All new tests must pass.
**Model: Sonnet** | Reason: `fromJson()` validation logic needs care — optional reroute_hint, NORMAL allowing empty affected_routes, and markdown-fence stripping.

#### Increment 2: MtaAlertParser structured prompt + expanded routes
- [ ] Expand `MONITORED_ROUTES` from `{N, W, 4, 5, 6}` to `{N, W, 4, 5, 6, R, 7}`
- [ ] Add `createdAt: Long?` field to `MtaAlert` data class (Mercury extension `created_at` timestamp)
- [ ] Extract `created_at` from `transit_realtime.mercury_alert` in `parseEntity()`
- [ ] Rewrite `buildPromptText(alerts, direction, nowSeconds)` to emit the structured per-alert format from `docs/decision-prompt.md`: header line with current time (ISO 8601) + direction, then each alert as a `---` delimited block with Routes, Type, Posted (ISO 8601), Active period, Header, Description fields. Handle missing active periods ("not specified"), missing description ("none"), missing createdAt ("unknown")
- [ ] Update `MtaAlertParserTest.kt`: add tests for R/7 alerts passing route filter, verify structured `buildPromptText()` output format (ISO timestamps, field labels, delimiter structure, "none"/"not specified" fallbacks), update existing `buildPromptText` tests for new signature

**Testing:** Run unit tests via Gradle: `& $gradle :app:testDebugUnitTest --tests "com.commutebuddy.app.MtaAlertParserTest"`. All new and updated tests must pass.
**Model: Sonnet** | Reason: Structured prompt format must exactly match the validated decision-prompt.md template; ISO timestamp formatting and edge cases for missing fields.

#### Increment 3: Decision prompt + Gemini config + pipeline integration
- [ ] Replace `SYSTEM_PROMPT` in `MainActivity.kt` with the canonical decision prompt from `docs/decision-prompt.md`, with the hardcoded commute profile (TO_WORK + TO_HOME legs + alternates R, 7) embedded in the prompt text
- [ ] Configure `GenerativeModel` with `generationConfig { temperature = 0f }` — investigate whether SDK 0.9.0 supports `thinkingConfig` for thinking budget; if not, omit (temperature=0 is the critical setting)
- [ ] Update default model name in `build.gradle.kts` from `gemini-2.5-flash` to `gemini-flash-latest`
- [ ] Update `onFetchLiveClicked()` pipeline: pass direction (`"TO_WORK"`) and `nowSeconds` to `buildPromptText()`, parse response with new `CommuteStatus.fromJson()`, update display formatting to show action/summary/affectedRoutes/rerouteHint instead of status/routeString/reason
- [ ] Update all error/fallback paths in `onFetchLiveClicked()` to construct `CommuteStatus` with new fields: `action = ACTION_NORMAL`, `summary = <error description>`, `affectedRoutes = ""`, `rerouteHint = null`
- [ ] Update `onTierClicked()` display formatting (FEAT-02 tier buttons) to match new `CommuteStatus` fields — these buttons still use the old summarization prompt so they will likely fail parsing; display raw output on parse failure (existing behavior) is fine
- [ ] Update string resources in `strings.xml` if needed for new field display labels

**Testing:** Run full unit test suite via Gradle: `& $gradle :app:testDebugUnitTest`. Then manually test: build APK, open app, tap "Fetch Live", verify decision output shows action tier (NORMAL/MINOR_DELAYS/REROUTE/STAY_HOME) + summary + affected routes. Verify BLE status line appears. Verify error path (e.g., airplane mode) shows graceful fallback.
**Model: Sonnet** | Reason: Pipeline integration across multiple code paths with error handling; Gemini SDK configuration research; needs understanding of full flow.

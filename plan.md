## BUG-13: Network failures overwrite last good watch status with error text

### Description
As a commuter, I want my watch to keep showing the last valid commute recommendation when my phone loses connectivity, so that I don't see a confusing "Unable to resolve host…" error replacing the status I was relying on.

When the phone enters an area with no service (e.g., underground, dead spots), `MtaAlertFetcher.fetchAlerts()` fails with a DNS/timeout exception. Currently, `CommutePipeline` wraps the raw error message into a `CommuteStatus(action=NORMAL, summary="Unable to resolve host…")` and `PollingForegroundService.poll()` sends that error status to both watches via `notifyAll()`. This overwrites whatever useful commute recommendation was previously displayed. The user loses actionable information and sees a cryptic error string on their wrist.

The fix has two parts: (1) never push a failed-fetch result to the watches — the last good status stays visible; (2) on network failure, retry with exponential backoff within the current poll cycle rather than silently waiting for the next scheduled alarm (which could be 5–60 minutes away).

### Acceptance Criteria

1. **Failed fetches are never sent to watches**
   - When `CommutePipeline.run()` returns `PipelineResult.Error`, `PollingForegroundService.poll()` must NOT call `notifyAll()` — the watches retain whatever status they were last sent
   - This applies to all error types from the pipeline (MTA fetch failure, Gemini API failure, parse failure)
   - Successful results (`GoodService`, `Decision`) continue to be sent to watches as before

2. **Exponential backoff retry on network failure**
   - When the MTA fetch fails with a network error (DNS resolution, connection timeout, socket timeout, `IOException`), the polling service retries with exponential backoff: ~30s → ~60s → ~120s → etc.
   - Retries stop when either: (a) the fetch succeeds (pipeline runs normally, result sent to watches), or (b) the next scheduled alarm time is reached (avoids overlapping poll cycles)
   - Backoff includes jitter (±10–20%) to avoid thundering-herd patterns if multiple devices poll the same endpoint
   - Each retry attempt is logged with the attempt number and next delay

3. **Backoff does not interfere with rate limiter or wake lock**
   - Retry attempts do NOT consume Gemini API rate-limiter quota (they only retry the MTA HTTP fetch, not the full pipeline)
   - The existing wake lock (10-min safety timeout) must cover the retry window; if retries would exceed the wake lock, they stop gracefully
   - The `pollMutex` remains held during retries so a concurrent alarm cannot trigger a duplicate poll

4. **Non-network pipeline errors do not trigger retries**
   - Gemini API errors, JSON parse errors, and empty-response errors are logged but do NOT trigger the backoff retry loop — only `MtaAlertFetcher` failures retry
   - Rationale: Gemini errors are unlikely to resolve by retrying seconds later, and retries would burn rate-limiter quota

5. **`MainActivity` "Fetch Live" behavior unchanged**
   - The manual "Fetch Live" button in `MainActivity` continues to show error messages in the UI (it's useful for debugging) — the no-send-on-error change only applies to `PollingForegroundService`
   - `CommutePipeline` itself remains unchanged; the error-suppression logic lives in the polling service

6. **Logging and observability**
   - Network failures log: error type, retry attempt number, next retry delay
   - Final outcome logged: "Retry succeeded on attempt N" or "All retries exhausted, skipping watch notification"
   - Existing `ACTION_POLL_COMPLETED` broadcast still fires (so the UI updates its "Last poll" timestamp)

### Out of Scope
- Displaying a "No connectivity" indicator on the watch (separate UX feature)
- Caching the last good `CommuteStatus` on the Android side for re-send on reconnect (watches already retain their last-received status)
- Changing `MtaAlertFetcher` internals (timeout values, URL, etc.)
- Retry logic for Gemini API failures (rate limiter already handles quota management)
- Changes to `CommutePipeline` itself — all changes are in `PollingForegroundService`

### Implementation Plan

#### Increment 1: Stop sending pipeline errors to watches
- [ ] In `PollingForegroundService.kt`, change the `PipelineResult.Error` branch in `poll()` (line 347–350) to log the error but NOT call `notifyAll()` — watches retain their last good status
- [ ] Add a test in `PollingForegroundServiceSchedulingTest.kt` (or a new focused test file) that verifies the error-suppression decision: `PipelineResult.Error` → no notify, `GoodService` → notify, `Decision` → notify, `RateLimited` → no notify

**Testing:** Run `gradle :app:testDebugUnitTest`. Manually verify on device: enable airplane mode, wait for a poll cycle, confirm the watch still shows the previous status (not an error string).
**Model: Haiku** | Reason: Single-line deletion + straightforward test following existing patterns.

#### Increment 2: Exponential backoff retry on MTA fetch failure
- [ ] Add a pure companion function `computeBackoffDelayMs(attempt: Int, baseDelayMs: Long = 30_000L): Long` to `PollingForegroundService` — exponential delay with ±15% jitter, capped at ~8 min (attempt clamped to 4)
- [ ] Add a pure companion function `isFetchError(result: PipelineResult): Boolean` — returns `true` only when the error's exception is an `IOException` (DNS, timeout, HTTP — the types `MtaAlertFetcher` produces)
- [ ] Wrap the `CommutePipeline.run()` call in `poll()` with a retry loop: on fetch error, `delay()` by backoff, retry up to the earlier of next alarm time or 8 minutes (wake lock safety margin), log each attempt; on success or non-fetch error, break out. Successful retry sends to watches normally; exhausted retries skip notification.
- [ ] Add unit tests: `computeBackoffDelayMs` produces expected ranges per attempt; `isFetchError` returns `true` for `IOException`-backed errors, `false` for Gemini/parse errors and `GoodService`/`Decision`/`RateLimited`
- [ ] Add a test verifying that the retry loop re-invokes the pipeline and stops when the result is no longer a fetch error (mock `MtaAlertFetcher` to fail twice then succeed)

**Testing:** Run `gradle :app:testDebugUnitTest`. Manually verify on device: enable airplane mode mid-commute-window, watch logcat for retry attempts with increasing delays; re-enable connectivity and confirm the retry succeeds and sends to watches.
**Model: Sonnet** | Reason: Async retry loop with coroutine delay, backoff math, and wake-lock-aware termination requires multi-step reasoning across the polling lifecycle.

# Decision Prompt — Canonical Reference

> Validated 2026-03-05 against `gemini-flash-latest` (Gemini 3 Flash Preview).
> 10/10 test scenarios passed with temperature=0, thinking=low (1024 tokens).
> See `decision-prompt-test.md` for full test data and `run-prompt-tests.py` for automated runner.

> **Note (FEAT-07):** The system prompt used in the app has been updated since this document was written.
> `SystemPromptBuilder.kt` now generates the prompt dynamically from the saved `CommuteProfile` and
> replaces the DECISION FRAMEWORK section with an explicit four-step decision procedure (Steps 1–4).
> The commute profile structure, output schema, and API settings in this document remain accurate.
> The "System Prompt (final, validated)" section below reflects the original validated version for reference.

## Overview

The decision prompt replaces the simple "summarize these alerts" approach (FEAT-02) with
an actionable commute advisor. Instead of telling the user *what's happening*, it tells
them *what to do*: proceed normally, expect delays, reroute, or stay home.

## Design Decisions

1. **Four action tiers** (not three): NORMAL, MINOR_DELAYS, REROUTE, STAY_HOME
2. **No confidence score** — if confidence is low, the feature has failed
3. **Reroute hint, not route planning** — Flash reports which alternate lines are clear; the user knows how to take them
4. **Direction matching from free text** — MTA feeds do NOT populate `direction_id`; direction is only in alert header text ("Manhattan-bound", "Downtown", etc.). Flash matches this against commute leg directions natively. No preprocessing extraction needed.
5. **Alert timing as structured input** — alert type + posted time + active period go into the prompt as structured context alongside the raw text
6. **Alternate lines are a flat list** — no direction or preference ordering needed
7. **Glance is super tight** — just action + affected routes. User taps into full-app view for summary/reroute_hint detail before acting.

## Commute Profile Structure

The commute is modeled as **directional legs** — not just a list of lines. Each leg has
lines, a direction, and station endpoints. This is critical because MTA alerts are
direction-specific (e.g., "Manhattan-bound [N] trains skip...").

```
TO_WORK:
  legs:
    - lines: [N, W]
      direction: Manhattan-bound
      from: Astoria
      to: 59th St
    - lines: [4, 5]
      direction: Downtown
      from: 59th St
      to: 14th St
    - lines: [6]
      direction: Downtown
      from: 14th St
      to: Spring St
  alternates: [F, R, 7]

TO_HOME:
  legs:
    - lines: [6]
      direction: Uptown
      from: Spring St
      to: 14th St
    - lines: [4, 5]
      direction: Uptown
      from: 14th St
      to: 59th St
    - lines: [N, W]
      direction: Queens-bound
      from: 59th St
      to: Astoria
  alternates: [F, R, 7]
```

All lines from both primary legs AND alternates must be included in the alert fetch
(MONITORED_ROUTES). The current set `{N, W, 4, 5, 6}` must expand to `{N, W, 4, 5, 6, F, R, 7}`.

## Output Schema (from Gemini)

```json
{
  "action": "NORMAL | MINOR_DELAYS | REROUTE | STAY_HOME",
  "summary": "Brief explanation, max 80 chars",
  "reroute_hint": "Which alternates are clear, max 60 chars (REROUTE only)",
  "affected_routes": "N,W"
}
```

| Field | Description | When present |
|-------|-------------|-------------|
| `action` | Headline decision displayed on watch glance | Always |
| `summary` | Human-readable reason — shown in full-app view | Always |
| `reroute_hint` | Which alternates are clear or also affected | Only when action=REROUTE |
| `affected_routes` | Comma-separated impacted lines from primary legs | Always (empty string if NORMAL) |

## System Prompt (final, validated)

```
You are a commute advisor for an NYC subway rider. Your job is to analyze MTA service alerts and make a clear recommendation: proceed normally, expect minor delays, reroute, or stay home.

COMMUTE PROFILE:
{commute_profile — injected per direction, see structure above}

DECISION FRAMEWORK:
- NORMAL: No alerts affect the commute, or alerts are resolved/irrelevant.
- MINOR_DELAYS: Active alerts cause delays on the primary route, but service IS STILL RUNNING. The commuter should allow extra time but does not need to change route. Use this when the alert type is "Delays" without words like "extensive", "significant", "extremely limited", or "suspended". Standard signal problems, train removal, and sick customers are typically MINOR_DELAYS unless described as severe.
- REROUTE: Primary route has significant disruption (suspended service, extensive delays, service described as "extremely limited", or skipped stops on the user's segment). At least one alternate line is running normally or with minor delays.
- STAY_HOME: Primary route is severely disrupted AND all alternate lines are also significantly impacted. Only valid when direction is TO_WORK. When direction is TO_HOME, use REROUTE or MINOR_DELAYS instead (the user must get home).

DIRECTION MATCHING RULES:
- Each commute leg has a direction (e.g., "Manhattan-bound", "Downtown").
- Only flag alerts that affect the leg's direction or explicitly say "both directions." Ignore alerts for the opposite direction on the same line.
- MTA alerts reference direction using terms like "Manhattan-bound", "Queens-bound", "Uptown", "Downtown", "Bronx-bound", "Brooklyn-bound", "northbound", "southbound", or station-pair ranges (e.g., "No [N] between Queensboro Plaza and Times Sq" implies Manhattan-bound service is affected). Match these against the leg direction.
- If an alert does not mention any direction, assume it affects both directions.

ALERT FRESHNESS RULES:
- All alerts below are pre-filtered to currently active time windows. Focus on type and posted time, not active periods.
- Real-time delays posted <30 min ago: treat as active.
- Real-time delays posted >60 min ago with no update: ASSUME RESOLVED and downgrade severity by one level (REROUTE → MINOR_DELAYS, MINOR_DELAYS → NORMAL). Exception: only keep the original severity if the alert text describes an inherently long-duration incident (e.g., "person struck by train", "FDNY on scene", "structural damage", "derailment"). Routine issues like signal problems, train cleaning, and sick customers are typically resolved within 60 minutes.
- Real-time delays posted 30-60 min ago: use judgment based on severity of the incident described.

ALTERNATE LINE EVALUATION:
- When recommending REROUTE, check all alerts for the alternate lines (F, R, 7).
- If an alternate has no active alerts, mention it as clear in reroute_hint.
- If ALL alternates are also significantly disrupted, escalate to STAY_HOME (TO_WORK) or note the situation in summary (TO_HOME).
- Do not recommend a specific transfer sequence or walking route — just report which alternate lines are running.

Respond with ONLY a JSON object matching this schema. No markdown fencing, no explanation outside the JSON:
{
  "action": "NORMAL" or "MINOR_DELAYS" or "REROUTE" or "STAY_HOME",
  "summary": "<brief explanation, max 80 chars>",
  "reroute_hint": "<which alternates are clear, max 60 chars — include ONLY when action is REROUTE, omit otherwise>",
  "affected_routes": "<comma-separated impacted lines from primary legs, or empty string if NORMAL>"
}
```

## User Prompt Template

```
Current time: {current_time_iso}
Direction: {TO_WORK | TO_HOME}

ALERTS:
{for each alert:}
---
Routes: {route_ids, comma-separated}
Type: {alert_type from Mercury extension}
Posted: {timestamp, ISO 8601}
Header: {header_text, en plain text}
Description: {description_text, en plain text, or "none"}
---

{if no alerts:}
No active alerts for any monitored lines.
```

## API Settings

| Setting | Value | Rationale |
|---------|-------|-----------|
| Model | `gemini-flash-latest` | Points to Gemini 3 Flash Preview; 10/10 tests pass |
| Temperature | 0 | Deterministic output — same alerts should always produce same recommendation |
| Thinking | Low (1024 tokens) | Sufficient for reliable decisions; minimal gave inconsistent reroute hints |

## Test Results (gemini-flash-latest, temp=0, thinking=low)

| Test | Scenario | Expected | Actual | Pass |
|------|----------|----------|--------|------|
| 1 | No alerts | NORMAL | NORMAL | Yes |
| 2 | N/W minor delays | MINOR_DELAYS | MINOR_DELAYS | Yes |
| 3 | N/W suspended, alternates clear | REROUTE | REROUTE | Yes |
| 4 | N/W suspended, F/R delayed, 7 clear | REROUTE (hint: 7) | REROUTE (hint: 7 clear, F/R delayed) | Yes |
| 5 | Everything impacted, TO_WORK | STAY_HOME | STAY_HOME | Yes |
| 6 | Everything impacted, TO_HOME | REROUTE (not STAY_HOME) | REROUTE | Yes |
| 7 | Queens-bound only, user Manhattan-bound | NORMAL | NORMAL | Yes |
| 8 | 4/5 downtown delays (mid-leg) | MINOR_DELAYS | MINOR_DELAYS | Yes |
| 9 | Stale alert (105 min old) | NORMAL | NORMAL | Yes |
| 10 | Overnight work, morning query | NORMAL | NORMAL | Yes |

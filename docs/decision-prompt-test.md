# Decision Prompt POC — Copy-Paste Test Script

## How to test in Google AI Studio

1. Go to aistudio.google.com → "Create new prompt"
2. Select model: Gemini 2.5 Flash (or 3 Flash Preview)
3. Paste the **System Instruction** below into the "System instructions" box
4. Paste each **Test Scenario** one at a time as a user message
5. Check the JSON response against the expected result
6. Start a new chat between scenarios (or the context from previous tests may bleed in)

---

## System Instruction (paste once)

```
You are a commute advisor for an NYC subway rider. Your job is to analyze MTA service alerts and make a clear recommendation: proceed normally, expect minor delays, reroute, or stay home.

COMMUTE PROFILE:

TO_WORK:
  Leg 1: N,W — Manhattan-bound (Astoria → 59th St)
  Leg 2: 4,5 — Downtown (59th St → 14th St)
  Leg 3: 6 — Downtown (14th St → Spring St)
  Alternates: F, R, 7

TO_HOME:
  Leg 1: 6 — Uptown (Spring St → 14th St)
  Leg 2: 4,5 — Uptown (14th St → 59th St)
  Leg 3: N,W — Queens-bound (59th St → Astoria)
  Alternates: F, R, 7

DECISION FRAMEWORK:
- NORMAL: No alerts affect the commute, or alerts are resolved/irrelevant.
- MINOR_DELAYS: Active alerts cause delays on the primary route, but service is running. The commuter should allow extra time but does not need to change route.
- REROUTE: Primary route has significant disruption (suspended service, major delays >15 min, skipped stops on the user's segment). At least one alternate line is running normally or with minor delays.
- STAY_HOME: Primary route is severely disrupted AND all alternate lines are also significantly impacted. Only valid when direction is TO_WORK. When direction is TO_HOME, use REROUTE or MINOR_DELAYS instead (the user must get home).

DIRECTION MATCHING RULES:
- Each commute leg has a direction (e.g., "Manhattan-bound", "Downtown").
- Only flag alerts that affect the leg's direction or explicitly say "both directions." Ignore alerts for the opposite direction on the same line.
- MTA alerts reference direction using terms like "Manhattan-bound", "Queens-bound", "Uptown", "Downtown", "Bronx-bound", "Brooklyn-bound", "northbound", "southbound", or station-pair ranges (e.g., "No [N] between Queensboro Plaza and Times Sq" implies Manhattan-bound service is affected). Match these against the leg direction.
- If an alert does not mention any direction, assume it affects both directions.

ALERT FRESHNESS RULES:
- All alerts below are pre-filtered to currently active time windows. Focus on type and posted time, not active periods.
- Real-time delays posted <30 min ago: treat as active.
- Real-time delays posted >60 min ago with no update: likely resolved; downgrade severity (REROUTE → MINOR_DELAYS, MINOR_DELAYS → NORMAL) unless the alert text indicates ongoing severity (e.g., "person struck by train", "service remains suspended", "FDNY on scene").
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

---

## Test Scenarios (paste one per chat)

### Test 1: No alerts → expect NORMAL

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
No active alerts for any monitored lines.
```

Expected: `{"action": "NORMAL", "summary": "...", "affected_routes": ""}`

---

### Test 2: N/W minor delays both directions → expect MINOR_DELAYS

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N, W
Type: Delays
Posted: 2026-03-05T08:22:00-05:00
Header: [N][W] trains are running with delays in both directions due to signal problems at Queensboro Plaza.
Description: none
---
```

Expected: `{"action": "MINOR_DELAYS", "summary": "...", "affected_routes": "N,W"}`

---

### Test 3: N/W suspended Manhattan-bound, F clear → expect REROUTE

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: [N] service operates in two sections: 1. Between Astoria-Ditmars Blvd and Queensboro Plaza 2. Between Times Sq-42 St and Coney Island-Stillwell Av. Travel alternatives: For service between Queens and Manhattan, take the [7] at Queensboro Plaza. Transfer between the [N] and [7] at Queensboro Plaza.
---
---
Routes: W
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [W] service
Description: [W] trains are suspended during this service change. For Astoria local station stops, take the [N] between Astoria-Ditmars Blvd and Queensboro Plaza, then transfer to the [7]. What's happening? Track maintenance.
---
```

Expected: `{"action": "REROUTE", "summary": "...", "reroute_hint": "F, R, 7 running normally", "affected_routes": "N,W"}`
Actual (low thinking): {
"action": "REROUTE",
"summary": "N and W service suspended between Queens and Manhattan.",
"reroute_hint": "F, R, and 7 lines are clear.",
"affected_routes": "N,W"
}
Actual (minimal thinking): {
"action": "REROUTE",
"summary": "N/W service suspended between Queens and Manhattan for track maintenance.",
"reroute_hint": "7 train is clear for transfer at Queensboro Plaza.",
"affected_routes": "N,W"
}

---

### Test 4: N/W suspended + F delayed + R delayed + 7 clear → expect REROUTE (hint: 7)

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: [N] service operates in two sections: 1. Between Astoria-Ditmars Blvd and Queensboro Plaza 2. Between Times Sq-42 St and Coney Island-Stillwell Av. Travel alternatives: For service between Queens and Manhattan, take the [7] at Queensboro Plaza.
---
---
Routes: F
Type: Delays
Posted: 2026-03-05T08:10:00-05:00
Header: Manhattan-bound [F] trains are running with delays due to a train with mechanical problems at Jay St-MetroTech.
Description: none
---
---
Routes: R
Type: Delays
Posted: 2026-03-05T08:05:00-05:00
Header: Manhattan-bound [R] trains are running with delays after we removed a train in need of cleaning from service at 36 St.
Description: none
---
```

Expected: `{"action": "REROUTE", "summary": "...", "reroute_hint": "7 running normally; F, R have delays", "affected_routes": "N,W"}`

---

### Test 5: Everything impacted, TO_WORK → expect STAY_HOME

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: Service operates in two sections. For service between Queens and Manhattan, take the [7] at Queensboro Plaza.
---
---
Routes: W
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [W] service
Description: [W] trains are suspended during this service change.
---
---
Routes: 4, 5
Type: Delays
Posted: 2026-03-05T07:50:00-05:00
Header: [4][5] trains are running with extensive delays in both directions while FDNY responds to a person struck by a train at 125 St.
Description: Service is extremely limited. Allow additional travel time.
---
---
Routes: 6
Type: Delays
Posted: 2026-03-05T08:00:00-05:00
Header: Downtown [6] trains are running with delays due to a signal malfunction at 77 St.
Description: none
---
---
Routes: F
Type: Delays
Posted: 2026-03-05T08:10:00-05:00
Header: Manhattan-bound [F] trains are running with delays due to a train with mechanical problems at Jay St-MetroTech.
Description: none
---
---
Routes: R
Type: Delays
Posted: 2026-03-05T07:55:00-05:00
Header: [R] trains are running with extensive delays in both directions due to a switch problem at Whitehall St.
Description: Service is extremely limited. Allow additional travel time.
---
---
Routes: 7
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [7] between Queensboro Plaza, Queens and 34 St-Hudson Yards, Manhattan
Description: [7] runs in Queens between Flushing-Main St and Queensboro Plaza only. Free shuttle buses run between Queensboro Plaza and Vernon Blvd-Jackson Av. What's happening? Track maintenance.
---
```

Expected: `{"action": "STAY_HOME", "summary": "...", "affected_routes": "N,W,4,5,6"}`
Actual (minimal thinking): {
"action": "STAY_HOME",
"summary": "Severe disruptions on all primary legs and all alternate routes to Manhattan.",
"affected_routes": "N,W,4,5,6"
}

---

### Test 6: Everything impacted, TO_HOME → expect REROUTE (not STAY_HOME)

```
Current time: 2026-03-05T17:30:00-05:00
Direction: TO_HOME

ALERTS:
---
Routes: N
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: Service operates in two sections. For service between Queens and Manhattan, take the [7] at Queensboro Plaza.
---
---
Routes: W
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [W] service
Description: [W] trains are suspended during this service change.
---
---
Routes: 4, 5
Type: Delays
Posted: 2026-03-05T17:10:00-05:00
Header: [4][5] trains are running with extensive delays in both directions while FDNY responds to a person struck by a train at 125 St.
Description: Service is extremely limited. Allow additional travel time.
---
---
Routes: 6
Type: Delays
Posted: 2026-03-05T17:15:00-05:00
Header: Uptown [6] trains are running with delays due to a signal malfunction at 77 St.
Description: none
---
---
Routes: F
Type: Delays
Posted: 2026-03-05T17:10:00-05:00
Header: Queens-bound [F] trains are running with delays due to a train with mechanical problems at Jay St-MetroTech.
Description: none
---
---
Routes: R
Type: Delays
Posted: 2026-03-05T17:05:00-05:00
Header: [R] trains are running with extensive delays in both directions due to a switch problem at Whitehall St.
Description: Service is extremely limited. Allow additional travel time.
---
---
Routes: 7
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Header: No [7] between Queensboro Plaza, Queens and 34 St-Hudson Yards, Manhattan
Description: [7] runs in Queens between Flushing-Main St and Queensboro Plaza only. What's happening? Track maintenance.
---
```

Expected: `{"action": "REROUTE", "summary": "...", "affected_routes": "N,W,4,5,6"}` — NOT STAY_HOME (TO_HOME direction). REROUTE + hint showing all alternates are also bad = "it's bad out there, put your thinking hat on" signal.
Actual (minimal): {
"action": "REROUTE",
"summary": "Major disruptions on all primary legs and most alternates.",
"reroute_hint": "All alternates (F, R, 7) have delays or part suspensions.",
"affected_routes": "N,W,4,5,6"
}

---

### Test 7: N/W Queens-bound disrupted, user going TO_WORK (Manhattan-bound) → expect NORMAL

**This is the critical direction-matching test.**

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N
Type: Planned - Stops Skipped
Posted: 2026-03-04T22:00:00-05:00
Header: In Queens, Queens-bound [N] skips 39 Av, 36 Av and Broadway
Description: For service to these stations, take the [N] to Astoria-Ditmars Blvd and transfer to a Manhattan-bound [N]. What's happening? Track maintenance.
---
```

Expected: `{"action": "NORMAL", "summary": "...", "affected_routes": ""}` — disruption is Queens-bound only, user is Manhattan-bound
Actual (low thinking): {
"action": "NORMAL",
"summary": "Alert only affects Queens-bound N trains; Manhattan-bound service is normal.",
"affected_routes": ""
}
Actual (minimal): {
"action": "NORMAL",
"summary": "N train alert only affects Queens-bound service; Manhattan-bound is clear.",
"affected_routes": ""
}

---

### Test 8: 4/5 downtown delays (mid-commute leg) → expect MINOR_DELAYS

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: 4, 5
Type: Delays
Posted: 2026-03-05T08:20:00-05:00
Header: Downtown [4][5] trains are running with delays due to signal problems at Grand Central-42 St.
Description: none
---
```

Expected: `{"action": "MINOR_DELAYS", "summary": "...", "affected_routes": "4,5"}`

---

### Test 9: Stale alert (>60 min old, no update) → expect NORMAL or downgraded

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N, W
Type: Delays
Posted: 2026-03-05T06:45:00-05:00
Header: [N][W] trains are running with delays in both directions while we address a signal malfunction at Queensboro Plaza.
Description: none
---
```

Expected: `{"action": "NORMAL", "summary": "...likely resolved...", "affected_routes": ""}` — alert is 105 min old with no update, standard signal issue

---

### Test 10: Planned overnight work, current time is morning → expect NORMAL

**Note:** In production, `filterByActivePeriod()` in Kotlin filters this alert before it reaches Gemini (active window is 22:00–05:00; current time is 08:30). This test verifies the model can still infer NORMAL from the description text alone ("overnight hours") when active period metadata is absent from the prompt.

```
Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N, W
Type: Planned - Part Suspended
Posted: 2026-03-04T18:00:00-05:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: [N] service operates in two sections during overnight hours. What's happening? Overnight track maintenance.
---
```

Expected: `{"action": "NORMAL", "summary": "...", "affected_routes": ""}` — description says "overnight hours"; current time 08:30 is daytime

---

## Live Captures

Real-world alerts captured during live app testing. These are the exact user messages sent to the model; the system prompt is the variable being tested.

---

### Live 11: B/D/F/M signal delays, TO_WORK, primary legs clear → expect NORMAL

Captured: 2026-03-06 ~17:20 UTC. F train has delays (signal near Bryant Park) and planned work in Brooklyn. The 4 has a Woodlawn-bound (wrong direction) station notice. Primary TO_WORK legs (N/W Manhattan-bound, 4/5 Downtown, 6 Downtown) are unaffected. F is an alternate, not a primary leg.

```
Current time: 2026-03-06T17:20:30Z
Direction: TO_WORK

ALERTS:
---
Routes: B,D,F,M
Type: Delays
Posted: 2026-03-06T15:38:02Z
Active period: 2026-03-06T16:59:03Z — (open)
Header: [B][D][F][M] trains are running with delays in both directions while we continue to address a signal problem near 42 St-Bryant Park.
Description: Service Changes

Some downtown [B] trains are running via the [C] line from 59 St-Columbus Circle to W 4 St-Wash Sq, and then via the [F] to 2 Av, the last stop.

Some downtown [D] trains are running via the [C] line from 59 St-Columbus Circle to Jay St-MetroTech, and then via the [F] to Coney Island-Stillwell Av.

Listen to announcements on your train to hear how it will run.

As an alternative for service between Manhattan and Brooklyn, consider using nearby [N][R] trains.
---
---
Routes: 5
Type: Station Notice
Posted: 2026-02-24T13:06:17Z
Active period: 2026-02-27T11:00:00Z — 2026-02-28T01:45:00Z; 2026-03-02T11:00:00Z — 2026-03-03T01:45:00Z; 2026-03-03T11:00:00Z — 2026-03-04T01:45:00Z; 2026-03-04T11:00:00Z — 2026-03-05T01:45:00Z; 2026-03-05T11:00:00Z — 2026-03-06T01:45:00Z; 2026-03-06T11:00:00Z — 2026-03-07T01:45:00Z; 2026-03-09T10:00:00Z — 2026-03-10T00:45:00Z; 2026-03-10T10:00:00Z — 2026-03-11T00:45:00Z; 2026-03-11T10:00:00Z — 2026-03-12T00:45:00Z; 2026-03-12T10:00:00Z — 2026-03-13T00:45:00Z; 2026-03-13T10:00:00Z — 2026-03-14T00:45:00Z
Header: In Brooklyn, Manhattan-bound [5] skips Newkirk Av-Little Haiti
Description: Use nearby Beverly Rd or Flatbush Av-Brooklyn College stations.
---
---
Routes: F
Type: Reduced Service
Posted: 2026-01-30T16:43:26Z
Active period: 2026-03-06T14:15:00Z — 2026-03-06T20:30:00Z
Header: The last stop for some [F] trains headed toward Coney Island is Church Av
Description: Transfer at Church Av to continue your trip. [F] service between Church Av and Coney Island-Stillwell Av runs less frequently.
---
---
Routes: F
Type: Planned - Stops Skipped
Posted: 2026-01-30T16:40:19Z
Active period: 2026-03-06T14:15:00Z — 2026-03-06T20:30:00Z
Header: In Brooklyn, Manhattan-bound [F] skips Avenue P, Avenue N, Bay Pkwy and Avenue I
All trains at 18 Av board from the Coney Island-bound platform
Description: For service to these stations, take the [F] to 18 Av and transfer to a Coney Island-bound train.
---
---
Routes: 4
Type: Station Notice
Posted: 2026-01-30T14:42:53Z
Active period: 2026-03-02T10:00:00Z — 2026-04-04T01:45:00Z; 2026-04-06T09:00:00Z — 2026-09-21T03:59:00Z
Header: In the Bronx, Woodlawn-bound [4] skips Burnside Av
Description: Use nearby 176 St or 183 St stations.
---
---
Routes: 7
Type: Station Notice
Posted: 2025-05-27T13:23:05Z
Active period: 2025-12-17T10:00:00Z — 2026-04-11T03:59:00Z
Header: In Queens, Manhattan-bound [7] skips 69 St and 52 St
All trains at 61 St-Woodside board from the Flushing-bound platform
Description: Use nearby 74 St-Broadway, 61 St-Woodside or 46 St-Bliss St stations.
---
---
Routes: 7
Type: Station Notice
Posted: 2025-03-31T13:38:39Z
Active period: 2026-02-23T08:00:00Z — 2026-02-28T04:45:00Z; 2026-03-02T08:00:00Z — 2026-03-07T04:45:00Z; 2026-03-09T07:00:00Z — 2026-04-01T03:45:00Z
Header: In Queens, Flushing-bound [7] skips 103 St-Corona Plaza
Description: Use nearby Junction Blvd or 111 St stations.
---
```

Expected: `{"action": "NORMAL", "affected_routes": ""}` — F/7 alerts are on alternates or wrong-direction stations; N/W, 4/5, 6 primary legs unaffected.

---

### Live 12: Same alerts, TO_HOME → expect NORMAL

Same alert set as Live 11, captured ~6 minutes later with direction toggled to TO_HOME. Primary TO_HOME legs (6 Uptown, 4/5 Uptown, N/W Queens-bound) are also unaffected.

```
Current time: 2026-03-06T17:26:28Z
Direction: TO_HOME

ALERTS:
---
Routes: B,D,F,M
Type: Delays
Posted: 2026-03-06T15:38:02Z
Active period: 2026-03-06T16:59:03Z — (open)
Header: [B][D][F][M] trains are running with delays in both directions while we continue to address a signal problem near 42 St-Bryant Park.
Description: Service Changes

Some downtown [B] trains are running via the [C] line from 59 St-Columbus Circle to W 4 St-Wash Sq, and then via the [F] to 2 Av, the last stop.

Some downtown [D] trains are running via the [C] line from 59 St-Columbus Circle to Jay St-MetroTech, and then via the [F] to Coney Island-Stillwell Av.

Listen to announcements on your train to hear how it will run.

As an alternative for service between Manhattan and Brooklyn, consider using nearby [N][R] trains.
---
---
Routes: 5
Type: Station Notice
Posted: 2026-02-24T13:06:17Z
Active period: 2026-02-27T11:00:00Z — 2026-02-28T01:45:00Z; 2026-03-02T11:00:00Z — 2026-03-03T01:45:00Z; 2026-03-03T11:00:00Z — 2026-03-04T01:45:00Z; 2026-03-04T11:00:00Z — 2026-03-05T01:45:00Z; 2026-03-05T11:00:00Z — 2026-03-06T01:45:00Z; 2026-03-06T11:00:00Z — 2026-03-07T01:45:00Z; 2026-03-09T10:00:00Z — 2026-03-10T00:45:00Z; 2026-03-10T10:00:00Z — 2026-03-11T00:45:00Z; 2026-03-11T10:00:00Z — 2026-03-12T00:45:00Z; 2026-03-12T10:00:00Z — 2026-03-13T00:45:00Z; 2026-03-13T10:00:00Z — 2026-03-14T00:45:00Z
Header: In Brooklyn, Manhattan-bound [5] skips Newkirk Av-Little Haiti
Description: Use nearby Beverly Rd or Flatbush Av-Brooklyn College stations.
---
---
Routes: F
Type: Reduced Service
Posted: 2026-01-30T16:43:26Z
Active period: 2026-03-06T14:15:00Z — 2026-03-06T20:30:00Z
Header: The last stop for some [F] trains headed toward Coney Island is Church Av
Description: Transfer at Church Av to continue your trip. [F] service between Church Av and Coney Island-Stillwell Av runs less frequently.
---
---
Routes: F
Type: Planned - Stops Skipped
Posted: 2026-01-30T16:40:19Z
Active period: 2026-03-06T14:15:00Z — 2026-03-06T20:30:00Z
Header: In Brooklyn, Manhattan-bound [F] skips Avenue P, Avenue N, Bay Pkwy and Avenue I
All trains at 18 Av board from the Coney Island-bound platform
Description: For service to these stations, take the [F] to 18 Av and transfer to a Coney Island-bound train.
---
---
Routes: 4
Type: Station Notice
Posted: 2026-01-30T14:42:53Z
Active period: 2026-03-02T10:00:00Z — 2026-04-04T01:45:00Z; 2026-04-06T09:00:00Z — 2026-09-21T03:59:00Z
Header: In the Bronx, Woodlawn-bound [4] skips Burnside Av
Description: Use nearby 176 St or 183 St stations.
---
---
Routes: 7
Type: Station Notice
Posted: 2025-05-27T13:23:05Z
Active period: 2025-12-17T10:00:00Z — 2026-04-11T03:59:00Z
Header: In Queens, Manhattan-bound [7] skips 69 St and 52 St
All trains at 61 St-Woodside board from the Flushing-bound platform
Description: Use nearby 74 St-Broadway, 61 St-Woodside or 46 St-Bliss St stations.
---
---
Routes: 7
Type: Station Notice
Posted: 2025-03-31T13:38:39Z
Active period: 2026-02-23T08:00:00Z — 2026-02-28T04:45:00Z; 2026-03-02T08:00:00Z — 2026-03-07T04:45:00Z; 2026-03-09T07:00:00Z — 2026-04-01T03:45:00Z
Header: In Queens, Flushing-bound [7] skips 103 St-Corona Plaza
Description: Use nearby Junction Blvd or 111 St stations.
---
```

Expected: `{"action": "NORMAL", "affected_routes": ""}` — same reasoning as Live 11; 6/4/5/N/W primary legs unaffected.

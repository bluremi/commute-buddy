"""
Decision Prompt — Automated test runner.
Calls Gemini API with system instruction + each test scenario.
Settings: temperature=0, thinking=LOW.

Install dependency: pip install --upgrade google-genai
Run: python docs/run-prompt-tests.py
"""

import json
import os
import time

# Read API key from android/local.properties
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
LOCAL_PROPS = os.path.join(SCRIPT_DIR, "..", "android", "local.properties")


def _read_local_property(key):
    """Read a property from android/local.properties."""
    if not os.path.exists(LOCAL_PROPS):
        raise FileNotFoundError(
            f"Cannot find {LOCAL_PROPS}. Copy local.properties.example and add your GEMINI_API_KEY."
        )
    with open(LOCAL_PROPS, "r") as f:
        for line in f:
            line = line.strip()
            if line.startswith(f"{key}="):
                return line.split("=", 1)[1].strip()
    raise KeyError(f"Property '{key}' not found in {LOCAL_PROPS}")


API_KEY = _read_local_property("GEMINI_API_KEY")
MODEL = "gemini-3-flash-preview"

from google import genai
from google.genai import types

client = genai.Client(api_key=API_KEY)

SYSTEM_PROMPT = """You are a commute advisor for an NYC subway rider. Your job is to analyze MTA service alerts and make a clear recommendation: proceed normally, expect minor delays, reroute, or stay home.

COMMUTE PROFILE:
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

DECISION PROCEDURE — follow these four steps in order and stop as soon as you have an action:

Step 1 — Identify the active legs.
  Use only the legs listed under the current Direction (TO_WORK or TO_HOME). Ignore the other direction entirely.

Step 2 — Check primary legs for active, direction-matched alerts.
  Apply DIRECTION MATCHING RULES (below) to each primary leg. Apply ALERT FRESHNESS RULES (below) to determine whether each alert is still active.
  If NO primary leg is significantly impacted → action = NORMAL. Output now; do not read further.
  Alerts that affect only alternate lines are irrelevant at this step.

Step 3 — Classify primary-leg disruption severity.
  If primary legs have delays but service IS STILL RUNNING (alert type "Delays", no words like "extensive", "significant", "extremely limited", or "suspended") → action = MINOR_DELAYS. Output now.
  If primary legs have a significant disruption (suspended service, extensive delays, skipped stops on the user's segment, service described as "extremely limited") → proceed to Step 4.

Step 4 — Evaluate alternates to choose REROUTE or STAY_HOME.
  Only reached when a primary leg is significantly disrupted.
  Check alerts for alternate lines (F, R, 7).
  If at least one alternate is running normally or with minor delays → action = REROUTE. Name the clear alternate(s) in reroute_hint.
  If ALL alternates are also significantly disrupted: for TO_WORK → action = STAY_HOME; for TO_HOME → action = REROUTE (user must get home; note the situation in summary).
  Do not recommend a specific transfer sequence or walking route — just report which alternate lines are running.

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

Respond with ONLY a JSON object matching this schema. No markdown fencing, no explanation outside the JSON:
{
  "action": "NORMAL" or "MINOR_DELAYS" or "REROUTE" or "STAY_HOME",
  "summary": "<brief explanation, max 80 chars>",
  "reroute_hint": "<which alternates are clear, max 60 chars — include ONLY when action is REROUTE, omit otherwise>",
  "affected_routes": "<comma-separated impacted PRIMARY LEG lines only — never alternate lines — or empty string if NORMAL>"
}"""

TESTS = [
    {
        "name": "Test 1: No alerts",
        "expected": "NORMAL",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
No active alerts for any monitored lines."""
    },
    {
        "name": "Test 2: N/W minor delays both directions",
        "expected": "MINOR_DELAYS",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N, W
Type: Delays
Posted: 2026-03-05T08:22:00-05:00
Header: [N][W] trains are running with delays in both directions due to signal problems at Queensboro Plaza.
Description: none
---"""
    },
    {
        "name": "Test 3: N/W suspended Manhattan-bound, F clear",
        "expected": "REROUTE",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
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
---"""
    },
    {
        "name": "Test 4: N/W suspended + F delayed + R delayed + 7 clear",
        "expected": "REROUTE (hint should mention 7 as clear)",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
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
---"""
    },
    {
        "name": "Test 5: Everything impacted, TO_WORK",
        "expected": "STAY_HOME",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
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
---"""
    },
    {
        "name": "Test 6: Everything impacted, TO_HOME",
        "expected": "REROUTE (not STAY_HOME — user must get home)",
        "prompt": """Current time: 2026-03-05T17:30:00-05:00
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
---"""
    },
    {
        "name": "Test 7: N/W Queens-bound disrupted, user TO_WORK (Manhattan-bound)",
        "expected": "NORMAL (direction mismatch — ignore Queens-bound alert)",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N
Type: Planned - Stops Skipped
Posted: 2026-03-04T22:00:00-05:00
Header: In Queens, Queens-bound [N] skips 39 Av, 36 Av and Broadway
Description: For service to these stations, take the [N] to Astoria-Ditmars Blvd and transfer to a Manhattan-bound [N]. What's happening? Track maintenance.
---"""
    },
    {
        "name": "Test 8: 4/5 downtown delays (mid-commute leg 2)",
        "expected": "MINOR_DELAYS",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: 4, 5
Type: Delays
Posted: 2026-03-05T08:20:00-05:00
Header: Downtown [4][5] trains are running with delays due to signal problems at Grand Central-42 St.
Description: none
---"""
    },
    {
        "name": "Test 9: Stale alert (105 min old, no update)",
        "expected": "NORMAL (alert likely resolved)",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N, W
Type: Delays
Posted: 2026-03-05T06:45:00-05:00
Header: [N][W] trains are running with delays in both directions while we address a signal malfunction at Queensboro Plaza.
Description: none
---"""
    },
    {
        # NOTE: In production, filterByActivePeriod() in Kotlin would have excluded this alert
        # before it ever reaches Gemini (active period is 22:00–05:00, current time is 08:30).
        # This test verifies the model can still infer NORMAL from description text alone
        # ("overnight hours") when active period metadata is not present in the prompt.
        "name": "Test 10: Planned overnight work, current time is morning",
        "expected": "NORMAL (description says overnight hours; current time 08:30 is daytime)",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N, W
Type: Planned - Part Suspended
Posted: 2026-03-04T18:00:00-05:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: [N] service operates in two sections during overnight hours. What's happening? Overnight track maintenance.
---"""
    },
    # --- Live captures ---
    {
        "name": "Live 11: B/D/F/M signal delays, TO_WORK, primary legs clear",
        "expected": "NORMAL (F is alternate only; N/W, 4/5, 6 unaffected)",
        "prompt": """Current time: 2026-03-06T17:20:30Z
Direction: TO_WORK

ALERTS:
---
Routes: B,D,F,M
Type: Delays
Posted: 2026-03-06T15:38:02Z
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
Header: In Brooklyn, Manhattan-bound [5] skips Newkirk Av-Little Haiti
Description: Use nearby Beverly Rd or Flatbush Av-Brooklyn College stations.
---
---
Routes: F
Type: Reduced Service
Posted: 2026-01-30T16:43:26Z
Header: The last stop for some [F] trains headed toward Coney Island is Church Av
Description: Transfer at Church Av to continue your trip. [F] service between Church Av and Coney Island-Stillwell Av runs less frequently.
---
---
Routes: F
Type: Planned - Stops Skipped
Posted: 2026-01-30T16:40:19Z
Header: In Brooklyn, Manhattan-bound [F] skips Avenue P, Avenue N, Bay Pkwy and Avenue I
All trains at 18 Av board from the Coney Island-bound platform
Description: For service to these stations, take the [F] to 18 Av and transfer to a Coney Island-bound train.
---
---
Routes: 4
Type: Station Notice
Posted: 2026-01-30T14:42:53Z
Header: In the Bronx, Woodlawn-bound [4] skips Burnside Av
Description: Use nearby 176 St or 183 St stations.
---
---
Routes: 7
Type: Station Notice
Posted: 2025-05-27T13:23:05Z
Header: In Queens, Manhattan-bound [7] skips 69 St and 52 St
All trains at 61 St-Woodside board from the Flushing-bound platform
Description: Use nearby 74 St-Broadway, 61 St-Woodside or 46 St-Bliss St stations.
---
---
Routes: 7
Type: Station Notice
Posted: 2025-03-31T13:38:39Z
Header: In Queens, Flushing-bound [7] skips 103 St-Corona Plaza
Description: Use nearby Junction Blvd or 111 St stations.
---"""
    },
    {
        "name": "Live 12: Same alerts, TO_HOME, primary legs clear",
        "expected": "NORMAL (6, 4/5, N/W Queens-bound unaffected)",
        "prompt": """Current time: 2026-03-06T17:26:28Z
Direction: TO_HOME

ALERTS:
---
Routes: B,D,F,M
Type: Delays
Posted: 2026-03-06T15:38:02Z
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
Header: In Brooklyn, Manhattan-bound [5] skips Newkirk Av-Little Haiti
Description: Use nearby Beverly Rd or Flatbush Av-Brooklyn College stations.
---
---
Routes: F
Type: Reduced Service
Posted: 2026-01-30T16:43:26Z
Header: The last stop for some [F] trains headed toward Coney Island is Church Av
Description: Transfer at Church Av to continue your trip. [F] service between Church Av and Coney Island-Stillwell Av runs less frequently.
---
---
Routes: F
Type: Planned - Stops Skipped
Posted: 2026-01-30T16:40:19Z
Header: In Brooklyn, Manhattan-bound [F] skips Avenue P, Avenue N, Bay Pkwy and Avenue I
All trains at 18 Av board from the Coney Island-bound platform
Description: For service to these stations, take the [F] to 18 Av and transfer to a Coney Island-bound train.
---
---
Routes: 4
Type: Station Notice
Posted: 2026-01-30T14:42:53Z
Header: In the Bronx, Woodlawn-bound [4] skips Burnside Av
Description: Use nearby 176 St or 183 St stations.
---
---
Routes: 7
Type: Station Notice
Posted: 2025-05-27T13:23:05Z
Header: In Queens, Manhattan-bound [7] skips 69 St and 52 St
All trains at 61 St-Woodside board from the Flushing-bound platform
Description: Use nearby 74 St-Broadway, 61 St-Woodside or 46 St-Bliss St stations.
---
---
Routes: 7
Type: Station Notice
Posted: 2025-03-31T13:38:39Z
Header: In Queens, Flushing-bound [7] skips 103 St-Corona Plaza
Description: Use nearby Junction Blvd or 111 St stations.
---"""
    },
]


def call_gemini(user_prompt):
    try:
        response = client.models.generate_content(
            model=MODEL,
            contents=user_prompt,
            config=types.GenerateContentConfig(
                system_instruction=SYSTEM_PROMPT,
                temperature=0.0,
                thinking_config=types.ThinkingConfig(
                    thinking_level=types.ThinkingLevel.LOW
                ),
            ),
        )
        usage = response.usage_metadata
        thoughts = getattr(usage, "thoughts_token_count", None)
        if thoughts:
            print(f"  [thinking tokens used: {thoughts}]")
        return response.text.strip()
    except Exception as e:
        return f"ERROR: {e}"


def main():
    print("=" * 70)
    print("Decision Prompt — Automated Tests (google-genai SDK)")
    print(f"Model: {MODEL} | Temp: 0 | Thinking: LOW")
    print("=" * 70)

    results = []
    for i, test in enumerate(TESTS):
        print(f"\n--- {test['name']} ---")
        print(f"Expected: {test['expected']}")

        response = call_gemini(test["prompt"])
        print(f"Actual:   {response}")

        try:
            parsed = json.loads(response)
            action = parsed.get("action", "?")
            expected_action = test["expected"].split(" ")[0].split("(")[0].strip()
            passed = action == expected_action or expected_action in test["expected"]
            results.append((test["name"], expected_action, action, passed, response))
        except json.JSONDecodeError:
            results.append((test["name"], test["expected"], response[:50], False, response))

        if i < len(TESTS) - 1:
            time.sleep(3)

    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)
    for name, expected, actual, passed, full in results:
        status = "PASS" if passed else "FAIL"
        print(f"  [{status}] {name}: expected={expected}, got={actual}")


if __name__ == "__main__":
    main()

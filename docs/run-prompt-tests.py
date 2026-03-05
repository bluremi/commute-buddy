"""
Decision Prompt POC — Automated test runner.
Calls Gemini API with system instruction + each test scenario.
Settings: temperature=0, thinking budget=1024 (low).
"""

import json
import os
import re
import urllib.request
import urllib.error
import time

# Read API key and model name from android/local.properties (same source as the Android app)
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
MODEL = _read_local_property("GEMINI_MODEL_NAME")
ENDPOINT = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent?key={API_KEY}"

SYSTEM_PROMPT = """You are a commute advisor for an NYC subway rider. Your job is to analyze MTA service alerts and make a clear recommendation: proceed normally, expect minor delays, reroute, or stay home.

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
- MINOR_DELAYS: Active alerts cause delays on the primary route, but service IS STILL RUNNING. The commuter should allow extra time but does not need to change route. Use this when the alert type is "Delays" without words like "extensive", "significant", "extremely limited", or "suspended". Standard signal problems, train removal, and sick customers are typically MINOR_DELAYS unless described as severe.
- REROUTE: Primary route has significant disruption (suspended service, extensive delays, service described as "extremely limited", or skipped stops on the user's segment). At least one alternate line is running normally or with minor delays.
- STAY_HOME: Primary route is severely disrupted AND all alternate lines are also significantly impacted. Only valid when direction is TO_WORK. When direction is TO_HOME, use REROUTE or MINOR_DELAYS instead (the user must get home).

DIRECTION MATCHING RULES:
- Each commute leg has a direction (e.g., "Manhattan-bound", "Downtown").
- Only flag alerts that affect the leg's direction or explicitly say "both directions." Ignore alerts for the opposite direction on the same line.
- MTA alerts reference direction using terms like "Manhattan-bound", "Queens-bound", "Uptown", "Downtown", "Bronx-bound", "Brooklyn-bound", "northbound", "southbound", or station-pair ranges (e.g., "No [N] between Queensboro Plaza and Times Sq" implies Manhattan-bound service is affected). Match these against the leg direction.
- If an alert does not mention any direction, assume it affects both directions.

ALERT FRESHNESS RULES:
- Planned work with a defined active_period: trust the time window; if current time is outside the window, ignore the alert.
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
Active period: not specified
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
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: [N] service operates in two sections: 1. Between Astoria-Ditmars Blvd and Queensboro Plaza 2. Between Times Sq-42 St and Coney Island-Stillwell Av. Travel alternatives: For service between Queens and Manhattan, take the [7] at Queensboro Plaza. Transfer between the [N] and [7] at Queensboro Plaza.
---
---
Routes: W
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
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
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: [N] service operates in two sections: 1. Between Astoria-Ditmars Blvd and Queensboro Plaza 2. Between Times Sq-42 St and Coney Island-Stillwell Av. Travel alternatives: For service between Queens and Manhattan, take the [7] at Queensboro Plaza.
---
---
Routes: F
Type: Delays
Posted: 2026-03-05T08:10:00-05:00
Active period: not specified
Header: Manhattan-bound [F] trains are running with delays due to a train with mechanical problems at Jay St-MetroTech.
Description: none
---
---
Routes: R
Type: Delays
Posted: 2026-03-05T08:05:00-05:00
Active period: not specified
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
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: Service operates in two sections. For service between Queens and Manhattan, take the [7] at Queensboro Plaza.
---
---
Routes: W
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
Header: No [W] service
Description: [W] trains are suspended during this service change.
---
---
Routes: 4, 5
Type: Delays
Posted: 2026-03-05T07:50:00-05:00
Active period: not specified
Header: [4][5] trains are running with extensive delays in both directions while FDNY responds to a person struck by a train at 125 St.
Description: Service is extremely limited. Allow additional travel time.
---
---
Routes: 6
Type: Delays
Posted: 2026-03-05T08:00:00-05:00
Active period: not specified
Header: Downtown [6] trains are running with delays due to a signal malfunction at 77 St.
Description: none
---
---
Routes: F
Type: Delays
Posted: 2026-03-05T08:10:00-05:00
Active period: not specified
Header: Manhattan-bound [F] trains are running with delays due to a train with mechanical problems at Jay St-MetroTech.
Description: none
---
---
Routes: R
Type: Delays
Posted: 2026-03-05T07:55:00-05:00
Active period: not specified
Header: [R] trains are running with extensive delays in both directions due to a switch problem at Whitehall St.
Description: Service is extremely limited. Allow additional travel time.
---
---
Routes: 7
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Active period: 2026-03-05 06:00 — 2026-03-05 14:00
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
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: Service operates in two sections. For service between Queens and Manhattan, take the [7] at Queensboro Plaza.
---
---
Routes: W
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
Header: No [W] service
Description: [W] trains are suspended during this service change.
---
---
Routes: 4, 5
Type: Delays
Posted: 2026-03-05T17:10:00-05:00
Active period: not specified
Header: [4][5] trains are running with extensive delays in both directions while FDNY responds to a person struck by a train at 125 St.
Description: Service is extremely limited. Allow additional travel time.
---
---
Routes: 6
Type: Delays
Posted: 2026-03-05T17:15:00-05:00
Active period: not specified
Header: Uptown [6] trains are running with delays due to a signal malfunction at 77 St.
Description: none
---
---
Routes: F
Type: Delays
Posted: 2026-03-05T17:10:00-05:00
Active period: not specified
Header: Queens-bound [F] trains are running with delays due to a train with mechanical problems at Jay St-MetroTech.
Description: none
---
---
Routes: R
Type: Delays
Posted: 2026-03-05T17:05:00-05:00
Active period: not specified
Header: [R] trains are running with extensive delays in both directions due to a switch problem at Whitehall St.
Description: Service is extremely limited. Allow additional travel time.
---
---
Routes: 7
Type: Planned - Part Suspended
Posted: 2026-03-04T22:00:00-05:00
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
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
Active period: 2026-03-05 06:00 — 2026-03-05 22:00
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
Active period: not specified
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
Active period: not specified
Header: [N][W] trains are running with delays in both directions while we address a signal malfunction at Queensboro Plaza.
Description: none
---"""
    },
    {
        "name": "Test 10: Planned overnight work, current time is morning",
        "expected": "NORMAL (outside active period)",
        "prompt": """Current time: 2026-03-05T08:30:00-05:00
Direction: TO_WORK

ALERTS:
---
Routes: N, W
Type: Planned - Part Suspended
Posted: 2026-03-04T18:00:00-05:00
Active period: 2026-03-05 22:00 — 2026-03-06 05:00
Header: No [N] between Queensboro Plaza, Queens and Times Sq-42 St, Manhattan
Description: [N] service operates in two sections during overnight hours. What's happening? Overnight track maintenance.
---"""
    },
]


def call_gemini(user_prompt):
    body = {
        "system_instruction": {
            "parts": [{"text": SYSTEM_PROMPT}]
        },
        "contents": [
            {
                "role": "user",
                "parts": [{"text": user_prompt}]
            }
        ],
        "generationConfig": {
            "temperature": 0,
            "thinkingConfig": {
                "thinkingBudget": 1024
            }
        }
    }
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        ENDPOINT,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            result = json.loads(resp.read().decode("utf-8"))
            # Extract text from response
            candidates = result.get("candidates", [])
            if candidates:
                parts = candidates[0].get("content", {}).get("parts", [])
                for part in parts:
                    if "text" in part:
                        return part["text"].strip()
            return f"ERROR: Unexpected response structure: {json.dumps(result)[:200]}"
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8") if e.fp else ""
        return f"ERROR {e.code}: {error_body[:300]}"
    except Exception as e:
        return f"ERROR: {e}"


def main():
    print("=" * 70)
    print("Decision Prompt POC — Automated Tests")
    print(f"Model: {MODEL} | Temp: 0 | Thinking: low (1024 tokens)")
    print("=" * 70)

    results = []
    for i, test in enumerate(TESTS):
        print(f"\n--- {test['name']} ---")
        print(f"Expected: {test['expected']}")

        response = call_gemini(test["prompt"])
        print(f"Actual:   {response}")

        # Try to parse action for quick pass/fail
        try:
            parsed = json.loads(response)
            action = parsed.get("action", "?")
            expected_action = test["expected"].split(" ")[0].split("(")[0].strip()
            passed = action == expected_action or expected_action in test["expected"]
            results.append((test["name"], expected_action, action, passed, response))
        except json.JSONDecodeError:
            results.append((test["name"], test["expected"], response[:50], False, response))

        # Rate limit: stay under 10 RPM
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

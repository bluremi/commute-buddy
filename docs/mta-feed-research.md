# MTA GTFS-RT Feed Research

Researched 2026-03-01. All feeds are unauthenticated — no API key required.

## Feed URLs

| Feed | URL |
|------|-----|
| Subway alerts (JSON) | `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts.json` |
| Subway alerts (protobuf) | `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts` |
| All modes (JSON) | `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fall-alerts.json` |
| All modes (protobuf) | `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fall-alerts` |
| Bus alerts | `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fbus-alerts` |
| LIRR alerts | `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Flirr-alerts` |
| Metro-North alerts | `https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fmnr-alerts` |

All protobuf URLs also have `.json` variants (append `.json`).

### Bonus: Human-Readable Status API

`https://collector-otp-prod.camsys-apps.com/realtime/serviceStatus` — proprietary JSON (not GTFS-RT), used by the mta.info website. Not officially documented, may change without notice. Contains `statusDetails[]` with `statusSummary`, `statusDescription` (HTML), `direction`, `priority`.

## Alert JSON Structure

Each alert entity contains:

- `header_text.translation[]` — look for `language: "en"` (plain text) vs `language: "en-html"` (HTML)
- `description_text.translation[]` — same two languages; may be absent on short alerts
- `informed_entity[].route_id` — e.g., `"N"`, `"4"`, `"Q"`, `"GS"`, `"SI"`
- `active_period[]` — start/end timestamps
- `transit_realtime.mercury_alert.alert_type` — observed values:
  - `"Delays"`
  - `"Planned Work"`
  - `"Service Change"`
  - `"Planned - Suspended"`
  - `"Planned - Part Suspended"`
  - `"Planned - Reroute"`
  - `"Planned - Stops Skipped"`
  - `"Extra Service"`
  - `"Boarding Change"`

## Alert Text Characteristics

### Formatting Patterns

- Route references use bracket notation: `[A]`, `[4]`, `[N]`, `[shuttle bus icon]`, `[accessibility icon]`
- Structured prose sections: "What's happening?", "Travel Alternatives:", "ADA Customers:", "Schedule reminder:"
- Station names, transfer instructions, GO ticket mentions
- Some alerts reference 4-5 other lines as alternatives

### Length Tiers (observed on a weekday sample)

**Short (~100 chars):** Real-time delays — header only, no description.
> `[Q] trains are delayed entering and leaving 96 St while we request NYPD for someone being disruptive at that station.`

**Medium (~500-600 chars):** Single reroute/skip with 1-2 alternatives.
> `In Queens, Jamaica Center-bound [E] skips Briarwood. For service to this station, take the [E] to Jamaica-Van Wyck and transfer to a Manhattan-bound [E]. For service from this station, take the [E] or [F] to Kew Gardens-Union Tpke and transfer to a Jamaica Center-bound [E]. What's happening? Urgent electrical repairs`

**Long (~800-1500 chars):** Planned suspensions with shuttle buses, split service, multiple transfer points, ADA disclaimers.
> `[2] service operates in two sections: 1. Between Wakefield-241 St and 149 St-Grand Concourse 2. Between 96 St and Flatbush Av-Brooklyn College Uptown trains will skip 79 St and 86 St, take the [1] instead. [shuttle bus icon] Free shuttle buses run between 96 St and 149 St-Grand Concourse, making all [2] station stops. Transfer between [2] and [shuttle bus icon] buses at 149 St-Grand Concourse and/or 96 St [accessibility icon]. Travel alternatives: For direct service to/from midtown, take the [4] at 149 St-Grand Concourse. Transfer between [shuttle bus icon] buses and [4] at 149 St-Grand Concourse. Connect between the [2] at Times Sq-42 St and [4] at Grand Central-42 St via the [S] 42 St Shuttle or [7]. When exiting 96 St or 149 St-Grand Concourse, get a GO ticket for re-entry into the subway. What's happening? Urgent repairs. [accessibility icon] This service change affects one or more ADA accessible stations and these travel alternatives may not be fully accessible. Please contact 511 to plan your trip.`

**Extreme (2000+ chars):** Weekend construction affecting multiple lines simultaneously. Not directly observed in the weekday sample but confirmed by user experience — can span several pages of detour/shuttle information.

## Implications for the Data Pipeline

- **FEAT-03 preprocessing** must filter by `informed_entity.route_id` and extract the `en` plain-text `translation` (not `en-html`)
- Even after filtering to a single route, individual alerts can be 800-1500+ chars
- **Gemini Nano's limited context window** means the preprocessing pipeline may need to truncate or split extremely long alerts before passing to the model
- The **FEAT-02 POC** tests this boundary by including a synthetic stress-test input (~2000+ chars) alongside real alert samples

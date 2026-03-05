package com.commutebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtaAlertParserTest {

    // -------------------------------------------------------------------------
    // JSON fixture constants modeled on real MTA GTFS-RT feed structure
    // -------------------------------------------------------------------------

    // Header-only alert (no description_text), single route N
    private val HEADER_ONLY_ALERT_JSON = """
        {
          "entity": [
            {
              "id": "lmm:planned_work:1001",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "N trains are delayed", "language": "en"},
                    {"text": "<p>N trains are delayed</p>", "language": "en-html"}
                  ]
                },
                "informed_entity": [
                  {"route_id": "N"}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    // Header + description alert, route W, with mercury alert_type
    private val HEADER_AND_DESCRIPTION_ALERT_JSON = """
        {
          "entity": [
            {
              "id": "lmm:planned_work:1002",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "W train planned work", "language": "en"},
                    {"text": "<p>W train planned work</p>", "language": "en-html"}
                  ]
                },
                "description_text": {
                  "translation": [
                    {"text": "Trains skip Whitehall St this weekend.", "language": "en"},
                    {"text": "<p>Trains skip Whitehall St this weekend.</p>", "language": "en-html"}
                  ]
                },
                "informed_entity": [
                  {"route_id": "W"}
                ],
                "transit_realtime.mercury_alert": {
                  "alert_type": "Planned Work"
                }
              }
            }
          ]
        }
    """.trimIndent()

    // Alert with en-html listed first; must extract only the plain "en" translation
    private val EN_BEFORE_HTML_JSON = """
        {
          "entity": [
            {
              "id": "lmm:alert:2001",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "<b>HTML header</b>", "language": "en-html"},
                    {"text": "Plain text header", "language": "en"}
                  ]
                },
                "informed_entity": [
                  {"route_id": "4"}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    // Alert covering multiple routes in informed_entity, including a stop_id entry
    private val MULTI_ROUTE_ALERT_JSON = """
        {
          "entity": [
            {
              "id": "lmm:alert:3001",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "4 and 5 trains delayed", "language": "en"}
                  ]
                },
                "informed_entity": [
                  {"route_id": "4"},
                  {"route_id": "5"},
                  {"stop_id": "640"}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    // Alert whose informed_entity contains only stop_id entries (no route_id at all)
    private val STOP_ONLY_ENTITIES_JSON = """
        {
          "entity": [
            {
              "id": "lmm:alert:4001",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "Elevator outage at Grand Central", "language": "en"}
                  ]
                },
                "informed_entity": [
                  {"stop_id": "631"},
                  {"stop_id": "632"}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    // Alert with mercury alert_type = "Delays"
    private val DELAYS_ALERT_JSON = """
        {
          "entity": [
            {
              "id": "lmm:alert:5001",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "6 trains are delayed uptown", "language": "en"}
                  ]
                },
                "informed_entity": [
                  {"route_id": "6"}
                ],
                "transit_realtime.mercury_alert": {
                  "alert_type": "Delays"
                }
              }
            }
          ]
        }
    """.trimIndent()

    // Alert with mercury alert_type + created_at timestamp
    private val ALERT_WITH_CREATED_AT_JSON = """
        {
          "entity": [
            {
              "id": "lmm:alert:7001",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "N trains delayed at Queens", "language": "en"}
                  ]
                },
                "informed_entity": [
                  {"route_id": "N"}
                ],
                "transit_realtime.mercury_alert": {
                  "alert_type": "Delays",
                  "created_at": 1709312400
                }
              }
            }
          ]
        }
    """.trimIndent()

    // R train alert (new alternate route)
    private val R_TRAIN_ALERT_JSON = """
        {
          "entity": [
            {
              "id": "lmm:alert:8001",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "R trains are delayed", "language": "en"}
                  ]
                },
                "informed_entity": [
                  {"route_id": "R"}
                ],
                "transit_realtime.mercury_alert": {
                  "alert_type": "Delays"
                }
              }
            }
          ]
        }
    """.trimIndent()

    // 7 train alert (new alternate route)
    private val SEVEN_TRAIN_ALERT_JSON = """
        {
          "entity": [
            {
              "id": "lmm:alert:8002",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "7 trains suspended", "language": "en"}
                  ]
                },
                "informed_entity": [
                  {"route_id": "7"}
                ],
                "transit_realtime.mercury_alert": {
                  "alert_type": "Suspended"
                }
              }
            }
          ]
        }
    """.trimIndent()

    // Feed with empty entity array
    private val EMPTY_ENTITY_JSON = """{"entity": []}"""

    // Malformed JSON
    private val MALFORMED_JSON = """{"entity": [{ broken ]}}"""

    // Feed with two alerts: N train (matches MONITORED_ROUTES) and Q train (does not)
    private val MULTI_ALERT_JSON = """
        {
          "entity": [
            {
              "id": "lmm:alert:6001",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "N trains are delayed", "language": "en"}
                  ]
                },
                "informed_entity": [{"route_id": "N"}],
                "transit_realtime.mercury_alert": {
                  "alert_type": "Delays"
                }
              }
            },
            {
              "id": "lmm:alert:6002",
              "alert": {
                "header_text": {
                  "translation": [
                    {"text": "Q train planned work", "language": "en"}
                  ]
                },
                "description_text": {
                  "translation": [
                    {"text": "Q trains skip Canal St.", "language": "en"}
                  ]
                },
                "informed_entity": [{"route_id": "Q"}],
                "transit_realtime.mercury_alert": {
                  "alert_type": "Planned Work"
                }
              }
            }
          ]
        }
    """.trimIndent()

    // -------------------------------------------------------------------------
    // parseAlerts: parsing tests
    // -------------------------------------------------------------------------

    @Test
    fun `header-only alert parses correctly with null descriptionText`() {
        val alerts = MtaAlertParser.parseAlerts(HEADER_ONLY_ALERT_JSON)
        assertEquals(1, alerts.size)
        val alert = alerts[0]
        assertEquals("N trains are delayed", alert.headerText)
        assertNull(alert.descriptionText)
        assertTrue(alert.routeIds.contains("N"))
    }

    @Test
    fun `header and description alert parses both fields and alert_type`() {
        val alerts = MtaAlertParser.parseAlerts(HEADER_AND_DESCRIPTION_ALERT_JSON)
        assertEquals(1, alerts.size)
        val alert = alerts[0]
        assertEquals("W train planned work", alert.headerText)
        assertEquals("Trains skip Whitehall St this weekend.", alert.descriptionText)
        assertEquals("Planned Work", alert.alertType)
    }

    @Test
    fun `extracts en translation and ignores en-html even when en-html appears first`() {
        val alerts = MtaAlertParser.parseAlerts(EN_BEFORE_HTML_JSON)
        assertEquals(1, alerts.size)
        assertEquals("Plain text header", alerts[0].headerText)
        assertFalse("Header must not contain HTML tags", alerts[0].headerText.contains("<"))
    }

    @Test
    fun `collects all route_ids from multi-route informed_entity`() {
        val alerts = MtaAlertParser.parseAlerts(MULTI_ROUTE_ALERT_JSON)
        assertEquals(1, alerts.size)
        val routeIds = alerts[0].routeIds
        assertTrue(routeIds.contains("4"))
        assertTrue(routeIds.contains("5"))
        assertEquals("Only route_id entries counted, not stop_id", 2, routeIds.size)
    }

    @Test
    fun `skips informed_entity entries with only stop_id`() {
        val alerts = MtaAlertParser.parseAlerts(STOP_ONLY_ENTITIES_JSON)
        assertEquals(1, alerts.size)
        assertTrue("Route IDs should be empty for stop-only entity", alerts[0].routeIds.isEmpty())
    }

    @Test
    fun `extracts alert_type from mercury extension`() {
        val alerts = MtaAlertParser.parseAlerts(DELAYS_ALERT_JSON)
        assertEquals(1, alerts.size)
        assertEquals("Delays", alerts[0].alertType)
    }

    @Test
    fun `alert without mercury extension has null alertType`() {
        val alerts = MtaAlertParser.parseAlerts(HEADER_ONLY_ALERT_JSON)
        assertEquals(1, alerts.size)
        assertNull(alerts[0].alertType)
    }

    @Test
    fun `extracts createdAt from mercury extension`() {
        val alerts = MtaAlertParser.parseAlerts(ALERT_WITH_CREATED_AT_JSON)
        assertEquals(1, alerts.size)
        assertNotNull(alerts[0].createdAt)
        assertEquals(1709312400L, alerts[0].createdAt)
    }

    @Test
    fun `alert without createdAt has null createdAt`() {
        val alerts = MtaAlertParser.parseAlerts(DELAYS_ALERT_JSON)
        assertEquals(1, alerts.size)
        assertNull(alerts[0].createdAt)
    }

    @Test
    fun `empty entity array returns empty list`() {
        val alerts = MtaAlertParser.parseAlerts(EMPTY_ENTITY_JSON)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `malformed JSON returns empty list without throwing`() {
        val alerts = MtaAlertParser.parseAlerts(MALFORMED_JSON)
        assertTrue(alerts.isEmpty())
    }

    // -------------------------------------------------------------------------
    // filterByRoutes tests
    // -------------------------------------------------------------------------

    @Test
    fun `matching route is kept`() {
        val alerts = MtaAlertParser.parseAlerts(HEADER_ONLY_ALERT_JSON) // N train
        val filtered = MtaAlertParser.filterByRoutes(alerts, setOf("N", "W"))
        assertEquals(1, filtered.size)
    }

    @Test
    fun `non-matching route is removed`() {
        val alerts = MtaAlertParser.parseAlerts(MULTI_ALERT_JSON) // N and Q
        val filtered = MtaAlertParser.filterByRoutes(alerts, MONITORED_ROUTES)
        assertEquals(1, filtered.size)
        assertEquals("N trains are delayed", filtered[0].headerText)
    }

    @Test
    fun `alert with routes N and Q is kept when filtering for N and W (partial match)`() {
        val mixedAlert = MtaAlert(
            headerText = "N and Q trains affected",
            descriptionText = null,
            routeIds = setOf("N", "Q"),
            alertType = null
        )
        val filtered = MtaAlertParser.filterByRoutes(listOf(mixedAlert), setOf("N", "W"))
        assertEquals(1, filtered.size)
    }

    @Test
    fun `filtering empty list returns empty list`() {
        val filtered = MtaAlertParser.filterByRoutes(emptyList(), MONITORED_ROUTES)
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `R train alert passes expanded MONITORED_ROUTES filter`() {
        val alerts = MtaAlertParser.parseAlerts(R_TRAIN_ALERT_JSON)
        val filtered = MtaAlertParser.filterByRoutes(alerts, MONITORED_ROUTES)
        assertEquals(1, filtered.size)
        assertEquals("R trains are delayed", filtered[0].headerText)
    }

    @Test
    fun `7 train alert passes expanded MONITORED_ROUTES filter`() {
        val alerts = MtaAlertParser.parseAlerts(SEVEN_TRAIN_ALERT_JSON)
        val filtered = MtaAlertParser.filterByRoutes(alerts, MONITORED_ROUTES)
        assertEquals(1, filtered.size)
        assertEquals("7 trains suspended", filtered[0].headerText)
    }

    @Test
    fun `MONITORED_ROUTES contains R and 7`() {
        assertTrue("R must be in MONITORED_ROUTES", "R" in MONITORED_ROUTES)
        assertTrue("7 must be in MONITORED_ROUTES", "7" in MONITORED_ROUTES)
    }

    @Test
    fun `Q train alert is excluded by MONITORED_ROUTES`() {
        val alert = MtaAlert("Q train work", null, setOf("Q"), null)
        val filtered = MtaAlertParser.filterByRoutes(listOf(alert), MONITORED_ROUTES)
        assertTrue(filtered.isEmpty())
    }

    // -------------------------------------------------------------------------
    // buildPromptText tests
    // -------------------------------------------------------------------------

    private val NOW_SECONDS = 1709312400L  // 2024-03-01T17:00:00Z

    @Test
    fun `output starts with current time and direction headers`() {
        val alert = MtaAlert("N trains are delayed", null, setOf("N"), "Delays")
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.startsWith("Current time:"))
        assertTrue(text.contains("Direction: TO_WORK"))
    }

    @Test
    fun `current time is formatted as ISO 8601`() {
        val alert = MtaAlert("N trains delayed", null, setOf("N"), "Delays")
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        // 1709312400 = 2024-03-01T17:00:00Z
        assertTrue("ISO timestamp must appear in output", text.contains("2024-03-01T17:00:00Z"))
    }

    @Test
    fun `single alert contains all structured field labels`() {
        val alert = MtaAlert("N trains are delayed", "Signal problems at 34 St.", setOf("N"), "Delays",
            createdAt = NOW_SECONDS)
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("Routes:"))
        assertTrue(text.contains("Type:"))
        assertTrue(text.contains("Posted:"))
        assertTrue(text.contains("Active period:"))
        assertTrue(text.contains("Header:"))
        assertTrue(text.contains("Description:"))
    }

    @Test
    fun `alert block is wrapped with --- delimiters`() {
        val alert = MtaAlert("N trains delayed", null, setOf("N"), "Delays")
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        val delimCount = text.lines().count { it.trim() == "---" }
        assertEquals("Single alert should have 2 --- delimiters", 2, delimCount)
    }

    @Test
    fun `two alerts produce four --- delimiters`() {
        val alert1 = MtaAlert("N trains delayed", null, setOf("N"), "Delays")
        val alert2 = MtaAlert("W train work", "Skips stop.", setOf("W"), "Planned Work")
        val text = MtaAlertParser.buildPromptText(listOf(alert1, alert2), "TO_HOME", NOW_SECONDS)
        val delimCount = text.lines().count { it.trim() == "---" }
        assertEquals("Two alerts should have 4 --- delimiters", 4, delimCount)
    }

    @Test
    fun `routes are comma-separated and sorted`() {
        val alert = MtaAlert("4 and 5 delayed", null, setOf("5", "4"), "Delays")
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("Routes: 4,5"))
    }

    @Test
    fun `posted shows ISO timestamp when createdAt is present`() {
        val alert = MtaAlert("N trains delayed", null, setOf("N"), "Delays",
            createdAt = NOW_SECONDS)
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue("Posted must contain ISO timestamp", text.contains("Posted: 2024-03-01T17:00:00Z"))
    }

    @Test
    fun `posted shows unknown when createdAt is null`() {
        val alert = MtaAlert("N trains delayed", null, setOf("N"), "Delays", createdAt = null)
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("Posted: unknown"))
    }

    @Test
    fun `active period shows not specified when activePeriods is empty`() {
        val alert = MtaAlert("N trains delayed", null, setOf("N"), "Delays")
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("Active period: not specified"))
    }

    @Test
    fun `active period shows ISO timestamps when periods are present`() {
        val alert = MtaAlert(
            headerText = "N trains delayed",
            descriptionText = null,
            routeIds = setOf("N"),
            alertType = "Delays",
            activePeriods = listOf(ActivePeriod(start = NOW_SECONDS, end = NOW_SECONDS + 3600))
        )
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue("Active period must contain start ISO timestamp", text.contains("2024-03-01T17:00:00Z"))
        assertTrue("Active period must contain end ISO timestamp", text.contains("2024-03-01T18:00:00Z"))
    }

    @Test
    fun `active period shows open for end=0`() {
        val alert = MtaAlert(
            headerText = "N trains delayed",
            descriptionText = null,
            routeIds = setOf("N"),
            alertType = "Delays",
            activePeriods = listOf(ActivePeriod(start = NOW_SECONDS, end = 0L))
        )
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("(open)"))
    }

    @Test
    fun `description shows none when null`() {
        val alert = MtaAlert("N trains delayed", null, setOf("N"), "Delays")
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("Description: none"))
    }

    @Test
    fun `description shows actual text when present`() {
        val alert = MtaAlert("W train work", "Trains skip Whitehall St.", setOf("W"), "Planned Work")
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("Description: Trains skip Whitehall St."))
    }

    @Test
    fun `type shows Unknown when alertType is null`() {
        val alert = MtaAlert("Some header", null, setOf("N"), null)
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("Type: Unknown"))
    }

    @Test
    fun `empty alerts list produces no active alerts message`() {
        val text = MtaAlertParser.buildPromptText(emptyList(), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("No active alerts for any monitored lines."))
        assertFalse(text.contains("---"))
    }

    @Test
    fun `header text appears after Header label`() {
        val alert = MtaAlert("N trains are delayed", null, setOf("N"), "Delays")
        val text = MtaAlertParser.buildPromptText(listOf(alert), "TO_WORK", NOW_SECONDS)
        assertTrue(text.contains("Header: N trains are delayed"))
    }

    // -------------------------------------------------------------------------
    // filterByActivePeriod tests
    // -------------------------------------------------------------------------

    private fun makeAlert(activePeriods: List<ActivePeriod> = emptyList()) =
        MtaAlert("Header", null, setOf("N"), null, activePeriods)

    @Test
    fun `empty activePeriods means alert is always active`() {
        val alert = makeAlert(emptyList())
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 1_000_000L)
        assertEquals(1, result.size)
    }

    @Test
    fun `single period and now falls within it is included`() {
        val alert = makeAlert(listOf(ActivePeriod(start = 1_000L, end = 2_000L)))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 1_500L)
        assertEquals(1, result.size)
    }

    @Test
    fun `single period already ended is excluded`() {
        val alert = makeAlert(listOf(ActivePeriod(start = 1_000L, end = 2_000L)))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 3_000L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single period not yet started is excluded`() {
        val alert = makeAlert(listOf(ActivePeriod(start = 5_000L, end = 10_000L)))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 1_000L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple periods none currently active is excluded`() {
        val alert = makeAlert(listOf(
            ActivePeriod(start = 1_000L, end = 2_000L),
            ActivePeriod(start = 5_000L, end = 6_000L)
        ))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 3_500L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple periods one currently active is included`() {
        val alert = makeAlert(listOf(
            ActivePeriod(start = 1_000L, end = 2_000L),
            ActivePeriod(start = 5_000L, end = 6_000L)
        ))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 5_500L)
        assertEquals(1, result.size)
    }

    @Test
    fun `open-ended period already started is included`() {
        val alert = makeAlert(listOf(ActivePeriod(start = 1_000L, end = 0L)))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 9_999_999L)
        assertEquals(1, result.size)
    }

    @Test
    fun `open-ended period not yet started is excluded`() {
        val alert = makeAlert(listOf(ActivePeriod(start = 9_000L, end = 0L)))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 1_000L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `boundary now equals start is included`() {
        val alert = makeAlert(listOf(ActivePeriod(start = 5_000L, end = 10_000L)))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 5_000L)
        assertEquals(1, result.size)
    }

    @Test
    fun `boundary now equals end is included`() {
        val alert = makeAlert(listOf(ActivePeriod(start = 5_000L, end = 10_000L)))
        val result = MtaAlertParser.filterByActivePeriod(listOf(alert), nowSeconds = 10_000L)
        assertEquals(1, result.size)
    }
}

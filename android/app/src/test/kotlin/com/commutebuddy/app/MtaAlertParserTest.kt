package com.commutebuddy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val filtered = MtaAlertParser.filterByRoutes(alerts, MONITORED_ROUTES) // N, W, 4, 5, 6
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

    // -------------------------------------------------------------------------
    // buildPromptText tests
    // -------------------------------------------------------------------------

    @Test
    fun `single alert without description produces delimiter and header`() {
        val alert = MtaAlert("N trains are delayed", null, setOf("N"), "Delays")
        val text = MtaAlertParser.buildPromptText(listOf(alert))
        assertTrue(text.contains("--- Alert (Delays) ---"))
        assertTrue(text.contains("N trains are delayed"))
    }

    @Test
    fun `single alert with description contains both header and description`() {
        val alert = MtaAlert("W train planned work", "Trains skip Whitehall St.", setOf("W"), "Planned Work")
        val text = MtaAlertParser.buildPromptText(listOf(alert))
        assertTrue(text.contains("--- Alert (Planned Work) ---"))
        assertTrue(text.contains("W train planned work"))
        assertTrue(text.contains("Trains skip Whitehall St."))
    }

    @Test
    fun `multiple alerts are separated by blank lines`() {
        val alert1 = MtaAlert("N trains delayed", null, setOf("N"), "Delays")
        val alert2 = MtaAlert("W train work", "Skips stop.", setOf("W"), "Planned Work")
        val text = MtaAlertParser.buildPromptText(listOf(alert1, alert2))
        assertTrue("Alerts must be separated by a double newline", text.contains("\n\n"))
        val parts = text.split("\n\n")
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("N trains delayed"))
        assertTrue(parts[1].contains("W train work"))
    }

    @Test
    fun `null description is omitted with no blank line within the alert block`() {
        val alert = MtaAlert("Header only", null, setOf("N"), null)
        val text = MtaAlertParser.buildPromptText(listOf(alert))
        assertFalse("No internal blank line for null description", text.contains("\n\n"))
        assertTrue(text.contains("Header only"))
    }

    @Test
    fun `null alertType falls back to Alert label in delimiter`() {
        val alert = MtaAlert("Some header", null, setOf("N"), null)
        val text = MtaAlertParser.buildPromptText(listOf(alert))
        assertTrue(text.contains("--- Alert (Alert) ---"))
    }
}

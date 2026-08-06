package com.montauk.voicecapture.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OrganizationDocumentParser]'s parsing of `organization.json` (bead
 * vn-edu.57), against the schema at `voice-vault/docs/organization-json.md`
 * (vn-vlt.2). [FULL_FIXTURE] is inlined rather than read from the voice-vault
 * submodule's `tests/fixtures/organization-json/2026-08-05_0005_guem.sample.json`
 * -- this app repo's own CI clone doesn't check that submodule out, so its
 * unit tests can't depend on a path into it. Kept byte-for-byte equivalent to
 * that fixture's shape; see `voice-vault/docs/organization-json.md`'s "Worked
 * example" section for the source of truth.
 */
class OrganizationDocumentParserTest {

    private val fullFixture = """
        {
          "session_id": "2026-08-05_0005_guem",
          "organized_at": "2026-08-05T01:55:00-07:00",
          "source": "inbox/2026-08-05_0005_guem/",
          "fragments": [
            {
              "index": 1,
              "t_start_ms": 5000,
              "t_end_ms": 28000,
              "speaker": "A",
              "summary": "Austin customer visit went well.",
              "tags": [
                {"name": "ideas", "tag_id": "t_01KZ8AQPFPAZJ4YCMNXGW5DRYX"},
                {"name": "work/mashgin", "tag_id": "t_01KZ8AQPFP71ENN6QR37Y5E9DC"}
              ],
              "destination": {
                "path": "notes/ideas/receipt-free-kiosk-mode.md",
                "section": "2026-08-05 (session 2026-08-05_0005_guem)",
                "mode": "appended"
              },
              "notes": "real-world validation for the receipt-free idea."
            },
            {
              "index": 2,
              "t_start_ms": 28000,
              "t_end_ms": 45000,
              "speaker": "A",
              "summary": "A pleasant, unrelated airport conversation.",
              "tags": [],
              "destination": {
                "path": "notes/journal/2026-08-05.md",
                "section": "session 2026-08-05_0005_guem",
                "mode": "appended"
              },
              "notes": null
            },
            {
              "index": 3,
              "t_start_ms": 45000,
              "t_end_ms": 63000,
              "speaker": "A",
              "summary": "Half-formed changelog idea.",
              "tags": [
                {"name": "ideas", "tag_id": "t_01KZ8AQPFPAZJ4YCMNXGW5DRYX"}
              ],
              "destination": {
                "path": "notes/ideas/customer-changelog.md",
                "section": "2026-08-05 (session 2026-08-05_0005_guem)",
                "mode": "new_file"
              },
              "notes": null
            }
          ],
          "unassigned_spans": [
            {"t_start_ms": 0, "t_end_ms": 5000, "reason": "opening chatter -- no content."},
            {"t_start_ms": 63000, "t_end_ms": 65000, "reason": "closing sign-off -- no new content."}
          ],
          "totals": {
            "fragments": 3,
            "notes_created": 1,
            "notes_appended": 2,
            "new_tags": 0,
            "excluded": 0
          },
          "restructure_candidates": [
            "notes/ideas/receipt-free-kiosk-mode.md -- promote to notes/projects/."
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the full worked-example shape`() {
        val document = OrganizationDocumentParser.parse(fullFixture)

        requireNotNull(document)
        assertEquals("2026-08-05_0005_guem", document.sessionId)
        assertEquals(3, document.fragments.size)
        assertEquals(2, document.unassignedSpans.size)

        val first = document.fragments[0]
        assertEquals(1, first.index)
        assertEquals(5000L, first.tStartMs)
        assertEquals(28000L, first.tEndMs)
        assertEquals("A", first.speaker)
        assertEquals(2, first.tags.size)
        assertEquals("work/mashgin", first.tags[1].name)
        assertEquals("t_01KZ8AQPFP71ENN6QR37Y5E9DC", first.tags[1].tagId)
        assertEquals("notes/ideas/receipt-free-kiosk-mode.md", first.destination?.path)
        assertEquals("appended", first.destination?.mode)

        val excluded = document.fragments[2]
        assertEquals("new_file", excluded.destination?.mode)
        assertNull(excluded.notes)

        assertEquals(3, document.totals?.fragments)
        assertEquals(1, document.totals?.notesCreated)
        assertEquals(2, document.totals?.notesAppended)
    }

    @Test
    fun `an excluded fragment with no destination parses with a null destination`() {
        val raw = """
            {
              "fragments": [
                {"index": 1, "t_start_ms": 0, "t_end_ms": 1000, "summary": "off-topic", "notes": "excluded -- non-owner speech"}
              ]
            }
        """.trimIndent()

        val document = OrganizationDocumentParser.parse(raw)

        requireNotNull(document)
        assertNull(document.fragments[0].destination)
    }

    @Test
    fun `null input returns null`() {
        assertNull(OrganizationDocumentParser.parse(null))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(OrganizationDocumentParser.parse("   "))
    }

    @Test
    fun `malformed JSON returns null rather than throwing`() {
        assertNull(OrganizationDocumentParser.parse("{not valid json"))
    }

    @Test
    fun `a minimal document with only fragments still parses, with empty defaults elsewhere`() {
        val document = OrganizationDocumentParser.parse("""{"fragments": []}""")

        requireNotNull(document)
        assertTrue(document.fragments.isEmpty())
        assertTrue(document.unassignedSpans.isEmpty())
        assertNull(document.totals)
    }

    @Test
    fun `unknown top-level fields -- e g restructure_candidates -- are ignored without error`() {
        val document = OrganizationDocumentParser.parse(fullFixture)

        requireNotNull(document)
        // restructure_candidates isn't modelled -- parsing succeeding at all is the assertion.
        assertEquals(3, document.fragments.size)
    }
}

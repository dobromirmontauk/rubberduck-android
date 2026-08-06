package com.montauk.voicecapture.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FiledToRenderModel.from]'s JSON-preferred, markdown-fallback selection logic (bead vn-edu.57) -- pure, no I/O. */
class FiledToRenderModelTest {

    @Test
    fun `valid organization json wins even when organization md is also present`() {
        val model = FiledToRenderModel.from(
            organizationJsonText = """{"fragments": []}""",
            organizationMdText = "# Organization\n\nsome legacy text",
        )

        assertTrue(model is FiledToRenderModel.Structured)
    }

    @Test
    fun `no organization json but a present organization md falls back to legacy markdown`() {
        val model = FiledToRenderModel.from(
            organizationJsonText = null,
            organizationMdText = "# Organization\n\nsome legacy text",
        )

        assertEquals(FiledToRenderModel.LegacyMarkdown("# Organization\n\nsome legacy text"), model)
    }

    @Test
    fun `malformed organization json falls back to organization md, not Unavailable`() {
        val model = FiledToRenderModel.from(
            organizationJsonText = "{not valid json",
            organizationMdText = "legacy text",
        )

        assertEquals(FiledToRenderModel.LegacyMarkdown("legacy text"), model)
    }

    @Test
    fun `both artifacts missing resolves to Unavailable`() {
        val model = FiledToRenderModel.from(organizationJsonText = null, organizationMdText = null)

        assertEquals(FiledToRenderModel.Unavailable, model)
    }

    @Test
    fun `a blank organization md is treated as absent, resolving to Unavailable`() {
        val model = FiledToRenderModel.from(organizationJsonText = null, organizationMdText = "   ")

        assertEquals(FiledToRenderModel.Unavailable, model)
    }
}

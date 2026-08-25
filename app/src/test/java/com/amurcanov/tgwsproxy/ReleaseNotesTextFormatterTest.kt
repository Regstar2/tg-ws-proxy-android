package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTextFormatterTest {
    @Test
    fun `markdown is converted to readable plain text`() {
        val markdown = """
            # Release notes v1.10.12

            **Main changes**
            - MTProto uses `cf_proxy_ws`.
            - See [documentation](https://example.com/docs).

            ## Details
            > Proxy runtime stays unchanged.
        """.trimIndent()

        val plain = ReleaseNotesTextFormatter.toPlainText(markdown)

        assertEquals(
            """
                Release notes v1.10.12

                Main changes
                • MTProto uses cf_proxy_ws.
                • See documentation.

                Details
                Proxy runtime stays unchanged.
            """.trimIndent(),
            plain,
        )
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("`"))
        assertFalse(plain.contains("#"))
    }

    @Test
    fun `preview is short and reports truncation`() {
        val markdown = (1..8).joinToString("\n") { "- Release note item $it with useful details" }

        val preview = ReleaseNotesTextFormatter.preview(markdown, maxLines = 3, maxChars = 500)

        assertTrue(preview.truncated)
        assertTrue(preview.text.lines().size <= 3)
        assertTrue(preview.text.endsWith("…"))
        assertFalse(preview.text.contains("item 4"))
    }

    @Test
    fun `short release notes are not marked as truncated`() {
        val preview = ReleaseNotesTextFormatter.preview("**Fixed** update screen.")

        assertFalse(preview.truncated)
        assertEquals("Fixed update screen.", preview.text)
    }

    @Test
    fun `fenced code keeps content but hides fence markers`() {
        val plain = ReleaseNotesTextFormatter.toPlainText(
            """
                ```text
                versionName=1.10.13
                ```
            """.trimIndent(),
        )

        assertEquals("versionName=1.10.13", plain)
    }
}

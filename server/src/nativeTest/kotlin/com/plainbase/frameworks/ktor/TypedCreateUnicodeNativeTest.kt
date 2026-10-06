package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.ktor.routes.composeDocument
import com.plainbase.frameworks.ktor.routes.invalidTypedCreateField
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Real route validator and UTF-8 composition seam; no JVM-only YAML parser in this image. */
@Tag("native")
class TypedCreateUnicodeNativeTest {
    @Test
    fun `typed metadata rejects forbidden Unicode while paired surrogates survive UTF-8 composition`() {
        for (character in listOf('\u0000', '\u0085', '\uFFFE', '\uFFFF', '\u2028', '\u2029', '\uD800', '\uDC00')) {
            val bad = "a${character}b"
            assertEquals("title", invalidTypedCreateField(bad, null, "Reference", null))
            assertEquals("slug", invalidTypedCreateField("Good", bad, "Reference", null))
            assertEquals("type", invalidTypedCreateField("Good", null, bad, null))
            assertFailsWith<IllegalArgumentException> { composeDocument(ID, bad, null, null, "Reference") }
        }
        assertEquals("type", invalidTypedCreateField("Good", null, "\u2003", null))
        assertEquals("body", invalidTypedCreateField("Good", null, "Reference", "\uD800"))
        assertEquals("body", invalidTypedCreateField("Good", null, "Reference", "\uDC00"))
        for (bad in listOf("\uD800\uD800\uDC00", "\uD800\uDC00\uDC00", "\uDC00\uD800\uDC00")) {
            assertEquals("body", invalidTypedCreateField("Good", null, "Reference", bad))
        }
        assertNull(invalidTypedCreateField("Join\u200D", "Bidi\u202E", "Type\u200D\u202E", "😀😀"))
        assertNull(invalidTypedCreateField("Pair 😀", "pair-😀", "Type 😀", "😀\u2028\u2029\uFFFE\uFFFF"))
        val emoji = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())
        val bodyTail = byteArrayOf(
            0xE2.toByte(), 0x80.toByte(), 0xA8.toByte(), 0xE2.toByte(), 0x80.toByte(), 0xA9.toByte(),
            0xEF.toByte(), 0xBF.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0xBF.toByte(), 0xBF.toByte(),
        )
        assertContentEquals(
            "---\nid: $ID\ntype: \"Type ".toByteArray() + emoji + "\"\ntitle: \"Pair ".toByteArray() + emoji +
                "\"\nslug: \"pair-".toByteArray() + emoji + "\"\n---\n\n".toByteArray() + emoji + bodyTail,
            composeDocument(ID, "Pair 😀", "pair-😀", "😀\u2028\u2029\uFFFE\uFFFF", "Type 😀"),
        )
    }

    @Test
    fun `legacy null type composition keeps its exact bytes natively`() {
        val expected = "---\nid: $ID\ntitle: \"Legacy\"\n---\n\nbody".toByteArray()
        assertContentEquals(expected, composeDocument(ID, "Legacy", null, "body"))
        assertContentEquals(expected, composeDocument(ID, "Legacy", null, "body", null))
    }

    companion object {
        private const val ID = "01900000-0000-7000-8000-000000000001"
    }
}

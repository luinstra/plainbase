package com.plainbase.domain.discussion

import com.plainbase.domain.render.SourceBlock
import com.plainbase.domain.render.SourceBlockKind
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@Tag("native")
class SelectionResolverNativeTest {

    @Test
    fun loneSurrogateSelectionIsInvalidAnchorNotReplacementMatch() {
        val raw = "a ? b \uFFFD c\n".encodeToByteArray()
        val blocks = listOf(SourceBlock(0, raw.size, SourceBlockKind.PARAGRAPH))

        val result = SelectionResolver.resolve(raw, blocks, emptyList(), SelectionRequest.Agent("\uD800"))

        val refusal = result as? SelectionResult.Refused ?: fail("expected an invalid anchor refusal")
        assertEquals(SelectionRefusal.INVALID_ANCHOR, refusal.refusal)
    }

    @Test
    fun blocklessNonStrictBodyIsInvalidAnchorForBothForms() {
        val raw = byteArrayOf(0xFF.toByte())

        val agentResult = SelectionResolver.resolve(raw, emptyList(), emptyList(), SelectionRequest.Agent("x"))
        val agentRefusal = agentResult as? SelectionResult.Refused ?: fail("expected an Agent refusal")
        assertEquals(SelectionRefusal.INVALID_ANCHOR, agentRefusal.refusal)

        val spaResult = SelectionResolver.resolve(raw, emptyList(), emptyList(), SelectionRequest.Spa(0, 1, "x"))
        val spaRefusal = spaResult as? SelectionResult.Refused ?: fail("expected an SPA refusal")
        assertEquals(SelectionRefusal.INVALID_ANCHOR, spaRefusal.refusal)
    }

    @Test
    fun multibyteNarrowedQuoteAndContextDecodeExactly() {
        val cases = listOf("é", "漢", "😀")
        cases.forEach { quote ->
            val characterBytes = quote.encodeToByteArray().size
            val prefixSource = "x".repeat(65 - characterBytes) + quote + "x".repeat(63)
            val suffixSource = "x".repeat(65 - characterBytes) + quote + "x".repeat(63)
            val raw = (prefixSource + quote + suffixSource).encodeToByteArray()
            val start = prefixSource.encodeToByteArray().size
            val end = start + characterBytes

            val capture = QuoteCapture.at(raw, start, end, emptyList(), AnchorSelection.NARROWED)
            val prefixSize = capture.prefix.encodeToByteArray().size
            val suffixSize = capture.suffix.encodeToByteArray().size
            val contextStart = start - prefixSize
            val contextEnd = end + suffixSize
            val expected = raw.decodeToString(contextStart, contextEnd, throwOnInvalidSequence = true)

            assertEquals(63, prefixSize)
            assertEquals(65 - characterBytes, suffixSize)
            assertTrue(prefixSize <= AnchorLimits.MAX_CONTEXT_BYTES)
            assertTrue(suffixSize <= AnchorLimits.MAX_CONTEXT_BYTES)
            assertEquals(expected, capture.prefix + capture.quote + capture.suffix)
            assertEquals(quote, capture.quote)
        }
    }
}

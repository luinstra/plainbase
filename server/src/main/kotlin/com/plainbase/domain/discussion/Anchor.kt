package com.plainbase.domain.discussion

import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.Heading
import java.nio.charset.CharacterCodingException
import kotlin.time.Instant

private const val UTF8_CONTINUATION_MASK = 0xC0
private const val UTF8_CONTINUATION_PREFIX = 0x80

/** A page-level or quote-level target captured from a page revision. */
sealed interface Anchor {
    /** The caller-verified content hash for the revision that supplied the target. */
    val contentHash: String

    /** Optional revision provenance; it does not participate in matching. */
    val commit: String?

    /** An anchor that refers to the page as a whole. */
    data class Page(override val contentHash: String, override val commit: String?) : Anchor

    /** An anchor that refers to a captured quote and its surrounding context. */
    data class Quote(
        override val contentHash: String,
        override val commit: String?,
        val capture: QuoteCapture,
    ) : Anchor
}

/** Whether a captured quote was narrowed to verbatim text or snapped to a rendered source block. */
enum class AnchorSelection(val wire: String) {
    NARROWED("narrowed"),
    SNAPPED("snapped"),
}

/**
 * A strict UTF-8 quote and its bounded context, with absolute byte offsets and the original line and heading path.
 * Offsets refer to the raw page bytes, including any frontmatter and BOM.
 */
data class QuoteCapture(
    val quote: String,
    val prefix: String,
    val suffix: String,
    val byteStart: Long,
    val byteEnd: Long,
    val bodyStart: Long,
    val line: Long,
    val selection: AnchorSelection,
    val headingPath: HeadingPath,
) {
    init {
        val quoteBytes = strictUtf8(quote)
        val prefixBytes = strictUtf8(prefix)
        val suffixBytes = strictUtf8(suffix)
        require(quoteBytes.size in 1..AnchorLimits.MAX_QUOTE_BYTES)
        require(prefixBytes.size <= AnchorLimits.MAX_CONTEXT_BYTES)
        require(suffixBytes.size <= AnchorLimits.MAX_CONTEXT_BYTES)
        require(bodyStart >= 0 && byteStart >= bodyStart && byteEnd >= byteStart)
        require(byteEnd - byteStart == quoteBytes.size.toLong())
        require(line >= 1)
    }

    companion object {
        fun at(
            raw: ByteArray,
            start: Int,
            end: Int,
            headings: List<Heading>,
            selection: AnchorSelection,
        ): QuoteCapture {
            require(start in 0 until end && end <= raw.size)
            val bodyStart = FrontmatterBlock.detect(raw).bodyStart
            require(start >= bodyStart)
            val left = start - minOf(AnchorLimits.MAX_CONTEXT_BYTES, start - bodyStart)
            var contextStart = left
            while (contextStart < start && isContinuation(raw[contextStart])) contextStart++
            val right = end + minOf(AnchorLimits.MAX_CONTEXT_BYTES, raw.size - end)
            var contextEnd = right
            while (contextEnd > end && contextEnd < raw.size && isContinuation(raw[contextEnd])) contextEnd--
            return QuoteCapture(
                quote = raw.decodeToString(startIndex = start, endIndex = end, throwOnInvalidSequence = true),
                prefix = raw.decodeToString(startIndex = contextStart, endIndex = start, throwOnInvalidSequence = true),
                suffix = raw.decodeToString(startIndex = end, endIndex = contextEnd, throwOnInvalidSequence = true),
                byteStart = start.toLong(),
                byteEnd = end.toLong(),
                bodyStart = bodyStart.toLong(),
                line = SourceLines.lineOf(raw, start),
                selection = selection,
                headingPath = HeadingPath.capture(headings, start),
            )
        }

        private fun isContinuation(byte: Byte): Boolean =
            byte.toInt() and UTF8_CONTINUATION_MASK == UTF8_CONTINUATION_PREFIX
    }
}

/** A later quote target that supersedes the original quote for matching while preserving the original anchor. */
data class Reattachment(val by: Actor, val at: Instant, val anchor: Anchor.Quote)

/** Returns the latest quote target when present, otherwise the original anchor. */
fun effectiveAnchor(anchor: Anchor, reattachment: Reattachment?): Anchor = reattachment?.anchor ?: anchor

/** Size bounds shared by capture, selection resolution, and re-anchoring. */
object AnchorLimits {
    const val MAX_QUOTE_BYTES = 16_384
    const val MAX_CONTEXT_BYTES = 64
    const val MAX_CANDIDATES = 20
}

internal fun strictUtf8(value: String): ByteArray = try {
    value.encodeToByteArray(throwOnInvalidSequence = true)
} catch (exception: CharacterCodingException) {
    throw IllegalArgumentException("text must be valid UTF-8", exception)
}

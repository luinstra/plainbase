package com.plainbase.domain.discussion

import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.Heading

/** A half-open byte range for a matched quote. */
data class MatchRange(val byteStart: Int, val byteEnd: Int)

/** The outcome of comparing an anchor with the current page. */
sealed interface AnchorMatch {
    data object PageLevel : AnchorMatch
    data class Exact(val range: MatchRange) : AnchorMatch
    data class Moved(val range: MatchRange) : AnchorMatch
    data class Ambiguous(val count: Int, val candidates: List<MatchRange>, val truncated: Boolean) : AnchorMatch
    data class Changed(val placement: Placement) : AnchorMatch
}

/** A page snapshot and derived location metadata used to re-anchor quote targets. */
class ReanchorPage private constructor(
    /** The source bytes, which callers treat as immutable for the lifetime of this page view. */
    internal val raw: ByteArray,
    val bodyStart: Int,
    val lineCount: Long,
    private val headings: List<Heading>,
) {
    companion object {
        fun of(raw: ByteArray, headings: List<Heading>): ReanchorPage = ReanchorPage(
            raw,
            FrontmatterBlock.detect(raw).bodyStart,
            SourceLines.lineCount(raw),
            headings,
        )
    }

    internal fun uniqueHeadingId(path: HeadingPath): String? {
        if (path.entries.isEmpty()) return null
        val stack = mutableListOf<HeadingPath.Entry>()
        var match: String? = null
        var count = 0
        for (heading in headings) {
            while (stack.lastOrNull()?.level?.let { it >= heading.level } == true) stack.removeLast()
            stack += HeadingPath.Entry(heading.level, heading.text)
            if (stack == path.entries) {
                count++
                if (count == 1) match = heading.id
            }
        }
        return match.takeIf { count == 1 }
    }
}

object Reanchor {
    fun match(anchor: Anchor, page: ReanchorPage): AnchorMatch = when (anchor) {
        is Anchor.Page -> AnchorMatch.PageLevel
        is Anchor.Quote -> matchQuote(anchor.capture, page)
    }

    private fun matchQuote(capture: QuoteCapture, page: ReanchorPage): AnchorMatch {
        val quote = strictUtf8(capture.quote)
        val prefix = strictUtf8(capture.prefix)
        val suffix = strictUtf8(capture.suffix)
        val relativeOffset = capture.byteStart - capture.bodyStart
        val expected = if (relativeOffset > Long.MAX_VALUE - page.bodyStart.toLong()) {
            Long.MAX_VALUE
        } else {
            page.bodyStart.toLong() + relativeOffset
        }
        val contextual = KmpMatcher.scan(page.raw, page.bodyStart, page.raw.size, prefix + quote + suffix)
        if (contextual.count > 1) return contextual.toAmbiguous(prefix.size, quote.size)
        if (contextual.count == 1) {
            val start = contextual.first.single() + prefix.size
            val range = MatchRange(start, start + quote.size)
            return if (start.toLong() == expected) AnchorMatch.Exact(range) else AnchorMatch.Moved(range)
        }

        val bare = KmpMatcher.scan(page.raw, page.bodyStart, page.raw.size, quote)
        if (bare.count > 1) return bare.toAmbiguous(0, quote.size)
        if (bare.count == 1) {
            val start = bare.first.single()
            val range = MatchRange(start, start + quote.size)
            return if (start.toLong() == expected) AnchorMatch.Exact(range) else AnchorMatch.Moved(range)
        }
        return AnchorMatch.Changed(Placement.of(capture.headingPath, capture.line, page))
    }

    private fun KmpMatcher.Occurrences.toAmbiguous(prefixSize: Int, quoteSize: Int): AnchorMatch.Ambiguous {
        val candidates = first.map { start ->
            val quoteStart = start + prefixSize
            MatchRange(quoteStart, quoteStart + quoteSize)
        }
        return AnchorMatch.Ambiguous(count, candidates, count > candidates.size)
    }
}

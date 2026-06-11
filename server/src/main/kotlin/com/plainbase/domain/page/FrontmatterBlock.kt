package com.plainbase.domain.page

/**
 * The single, authoritative frontmatter-block detector (§A3 "Detection grammar, shared, single
 * definition"). This is the SAME code the renderer (chunk 3) and the surgical patcher (chunk 4a)
 * use — there is exactly one frontmatter grammar in the tree, and it is this one.
 *
 * Grammar (§A3): after an optional UTF-8 BOM (`EF BB BF`), the first line is exactly `---` (then an
 * EOL); the block ends at the next line that is exactly `---` or `...` (then EOL or EOF). No closing
 * delimiter ⇒ the file has **no frontmatter** (the leading `---` is an ordinary thematic break, for
 * patcher and renderer alike).
 *
 * Works on raw **bytes**, not a decoded string: the patcher's byte-fidelity guarantee (§A3) needs
 * exact offsets, and the renderer's M2 bridging slices the raw input at these offsets before either
 * flexmark parser sees it. Offsets are byte indices into the original input; [Detection.bodyStart]
 * always points after the closing delimiter line (or to the post-BOM offset when there is no
 * block), so the input from `bodyStart` onward is exactly the Markdown body.
 *
 * Pure domain code: no framework or Markdown-library type.
 */
object FrontmatterBlock {

    /** The UTF-8 byte-order-mark, stripped (in offset accounting only) before delimiter detection. */
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private const val DASH = '-'.code.toByte()
    private const val DOT = '.'.code.toByte()
    private const val LF = '\n'.code.toByte()
    private const val CR = '\r'.code.toByte()

    /**
     * The result of running the grammar: either a [Present] block (a valid opener AND a closing
     * delimiter were both found) or an [Absent] one. The sentinel-free sealed shape makes the
     * invalid state — "absent yet carrying an inner region" — unrepresentable, so call sites no
     * longer guard a `present` flag before trusting the offsets.
     *
     * [bomLength] (3 when a UTF-8 BOM precedes the opener, else 0) and [bodyStart] (the offset
     * where the Markdown body begins) are common to both: chunk 4a's patcher reads `bodyStart`
     * uniformly, which for [Absent] is just the post-BOM offset.
     */
    sealed interface Detection {

        /** 3 when a UTF-8 BOM precedes the content, else 0. */
        val bomLength: Int

        /** The byte offset where the Markdown body begins (the input from here on is exactly the body). */
        val bodyStart: Int

        /**
         * A detected frontmatter block. The block region (delimiters included) spans the half-open
         * byte range `bomLength until bodyStart`; the inner value region (between the delimiter
         * lines, exclusive) spans `innerStart until innerEnd`; [bodyStart] points just past the
         * closing delimiter line.
         */
        data class Present(
            override val bomLength: Int,
            val innerStart: Int,
            val innerEnd: Int,
            override val bodyStart: Int,
        ) : Detection

        /**
         * No frontmatter block. [bodyStart] equals [bomLength] — the whole input after any BOM is
         * body — so a call site that only needs "where does the body start" treats both cases alike.
         */
        data class Absent(override val bomLength: Int) : Detection {
            override val bodyStart: Int get() = bomLength
        }
    }

    /** Runs the §A3 grammar over [input], returning the detected block (or an [Detection.Absent] result). */
    fun detect(input: ByteArray): Detection {
        val bomLength = if (input.startsWithBom()) BOM.size else 0
        val absent = Detection.Absent(bomLength = bomLength)

        val opener = openerEnd(input, bomLength) ?: return absent
        var lineStart = opener
        while (lineStart < input.size) {
            val lineEnd = lineEnd(input, lineStart)
            if (isDelimiterLine(input, lineStart, lineEnd.contentEnd)) {
                return Detection.Present(
                    bomLength = bomLength,
                    innerStart = opener,
                    innerEnd = lineStart,
                    bodyStart = lineEnd.nextLineStart,
                )
            }
            lineStart = lineEnd.nextLineStart
        }
        // Opener present but no closing delimiter ⇒ not frontmatter (the `---` is a thematic break).
        return absent
    }

    /**
     * The end-of-opener offset: the start of the second line iff the first line (after the BOM) is
     * exactly `---` terminated by an EOL. Returns null when the opener is not exactly `---{EOL}`
     * (e.g. a trailing space — `---␠` — is NOT a frontmatter opener per the grammar; M2 agreement
     * test). A `---` at EOF (no terminator) is no opener either: a block needs a body region.
     */
    private fun openerEnd(input: ByteArray, from: Int): Int? {
        val line = lineEnd(input, from)
        if (!isExactlyThreeDashes(input, from, line.contentEnd)) return null
        // The opener must be terminated by an EOL (a bare `---` at EOF is a thematic break, not an opener).
        if (line.nextLineStart == line.contentEnd) return null
        return line.nextLineStart
    }

    private fun isExactlyThreeDashes(input: ByteArray, start: Int, contentEnd: Int): Boolean =
        contentEnd - start == 3 && (start until contentEnd).all { input[it] == DASH }

    /** A closing delimiter line is exactly `---` or `...` (§A3): three identical dashes or dots. */
    private fun isDelimiterLine(input: ByteArray, start: Int, contentEnd: Int): Boolean {
        if (contentEnd - start != 3) return false
        val first = input[start]
        return (first == DASH || first == DOT) && (start until contentEnd).all { input[it] == first }
    }

    /** The extent of the line beginning at [start]: where its content ends (before any CR/LF) and where the next line begins. */
    private fun lineEnd(input: ByteArray, start: Int): LineExtent {
        var i = start
        while (i < input.size && input[i] != LF && input[i] != CR) i++
        val contentEnd = i
        // Consume the terminator: \r\n, \r, or \n. nextLineStart == contentEnd when at EOF (no terminator).
        if (i < input.size && input[i] == CR) i++
        if (i < input.size && input[i] == LF) i++
        return LineExtent(contentEnd, i)
    }

    private fun ByteArray.startsWithBom(): Boolean =
        size >= BOM.size && BOM.indices.all { this[it] == BOM[it] }

    /** [contentEnd] = offset just past the line's content (before CR/LF); [nextLineStart] = offset of the next line (or EOF). */
    private data class LineExtent(val contentEnd: Int, val nextLineStart: Int)
}

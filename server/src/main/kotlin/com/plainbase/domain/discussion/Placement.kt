package com.plainbase.domain.discussion

/** Maps byte offsets to one-based source lines and counts CR, LF, and CRLF lines. */
object SourceLines {
    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()

    fun lineOf(raw: ByteArray, offset: Int): Long {
        require(offset in 0..raw.size)
        var line = 1L
        var index = 0
        while (index < offset) {
            when (raw[index]) {
                CR -> {
                    val halfCrLf = index + 1 == offset && index + 1 < raw.size && raw[index + 1] == LF
                    if (!halfCrLf) line++
                    index += if (!halfCrLf && index + 1 < offset && raw[index + 1] == LF) 2 else 1
                }
                LF -> {
                    line++
                    index++
                }
                else -> index++
            }
        }
        return line
    }

    fun lineCount(raw: ByteArray): Long {
        val count = lineOf(raw, raw.size)
        val endsWithTerminator = raw.lastOrNull() == CR || raw.lastOrNull() == LF
        return maxOf(1, count - if (endsWithTerminator) 1 else 0)
    }
}

/** A fallback location represented by a heading anchor or source line. */
sealed interface Placement {
    data class Heading(val id: String) : Placement
    data class Line(val line: Long) : Placement

    companion object {
        fun of(path: HeadingPath, line: Long, page: ReanchorPage): Placement {
            if (path.entries.isNotEmpty()) {
                val matches = page.headingPaths.filter { (candidate, _) -> candidate == path }
                if (matches.size == 1) return Heading(matches.single().second)
            }
            return Line(minOf(line, page.lineCount))
        }
    }
}

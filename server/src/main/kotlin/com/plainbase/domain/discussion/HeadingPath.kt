package com.plainbase.domain.discussion

import com.plainbase.domain.page.Heading

private const val MIN_HEADING_LEVEL = 1
private const val MAX_HEADING_LEVEL = 6

/** A heading hierarchy used as a fallback location; capture requires `byteStart` and ignores headings without it. */
data class HeadingPath(val entries: List<Entry>) {
    data class Entry(val level: Int, val text: String) {
        init {
            require(level in MIN_HEADING_LEVEL..MAX_HEADING_LEVEL)
        }
    }

    companion object {
        val EMPTY = HeadingPath(emptyList())

        fun capture(headings: List<Heading>, byteStart: Int): HeadingPath =
            from(headings.filter { heading -> heading.byteStart?.let { it <= byteStart } == true })

        fun pathsOf(headings: List<Heading>): List<Pair<HeadingPath, String>> {
            val stack = mutableListOf<Entry>()
            return headings.map { heading ->
                while (stack.lastOrNull()?.level?.let { it >= heading.level } == true) stack.removeLast()
                val entry = Entry(heading.level, heading.text)
                stack.add(entry)
                HeadingPath(stack.toList()) to heading.id
            }
        }

        private fun from(headings: List<Heading>): HeadingPath = pathsOf(headings).lastOrNull()?.first ?: EMPTY
    }
}

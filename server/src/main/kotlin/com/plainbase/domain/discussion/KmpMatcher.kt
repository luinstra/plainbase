package com.plainbase.domain.discussion

/** Finds overlapping byte matches within a bounded source range. */
object KmpMatcher {
    data class Occurrences(val count: Int, val first: List<Int>)

    /** Finds overlapping byte matches wholly within the half-open window [from, until). */
    fun scan(text: ByteArray, from: Int, until: Int, pattern: ByteArray, keep: Int = AnchorLimits.MAX_CANDIDATES): Occurrences {
        require(pattern.isNotEmpty())
        require(from in 0..until && until <= text.size)
        require(keep >= 0)

        if (pattern.size > until - from) return Occurrences(0, emptyList())

        val failure = IntArray(pattern.size)
        var matched = 0
        for (i in 1 until pattern.size) {
            while (matched > 0 && pattern[i] != pattern[matched]) matched = failure[matched - 1]
            if (pattern[i] == pattern[matched]) matched++
            failure[i] = matched
        }

        var count = 0
        val first = ArrayList<Int>(minOf(keep, until - from))
        matched = 0
        for (i in from until until) {
            while (matched > 0 && text[i] != pattern[matched]) matched = failure[matched - 1]
            if (text[i] == pattern[matched]) matched++
            if (matched == pattern.size) {
                val start = i - pattern.size + 1
                count++
                if (first.size < keep) first.add(start)
                matched = failure[matched - 1]
            }
        }
        return Occurrences(count, first)
    }
}

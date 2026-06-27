package com.plainbase.domain.service

/**
 * The Phase-5 (P1a) server-computed review diff: a STABLE base->proposed unified diff string
 * (PB-DIFF-1, FROZEN). Pure domain, kotlin stdlib only, NO diff library (the allowlist bans one) — a
 * bounded Myers O(ND) line diff over the two byte buffers, decoded LENIENTLY as UTF-8 for LINE
 * SPLITTING ONLY (the [com.plainbase.domain.page.IndexedPage.markdown] / `WritePipeline.conflict`
 * lenient-decode idiom). The diff is a presentation artifact; the byte-fidelity authority is the
 * stored `proposed_content` BLOB, never this string.
 *
 * **FROZEN FORMAT (PB-DIFF-1 — a change here is a contract break, not a fix):**
 *  1. 3 lines of unchanged context around each hunk; hunks within <=6 unchanged lines coalesce.
 *  2. Hunk header `@@ -<a>,<b> +<c>,<d> @@` — 1-based start + line count, the `,<n>` ALWAYS explicit
 *     (even when 1) for determinism. No section heading after the second `@@`.
 *  3. Line prefixes: ` ` context, `-` base-only, `+` proposed-only — one char per line.
 *  4. A side not ending in a trailing newline emits git's literal `\ No newline at end of file` marker
 *     immediately after the affected line.
 *  5. File-header lines (`--- a`/`+++ b`/`diff --git`) are ELIDED — a buffer-pair diff has no filenames.
 *  6. A byte-identical base/proposed pair is the EMPTY STRING (`""`).
 *  7. CRLF / a leading BOM are LITERAL content WITHIN a line — never normalized (splitting is on `\n`;
 *     a trailing `\r` rides as content on the line it terminates).
 *
 * **Bounded (G2):** Myers O((n+m)*D) time with O(n+m) working memory (the V-array bands), so two
 * `maxWriteBodyBytes`-bounded documents stay tractable — no n*m LCS matrix, no OOM, no stall. There is
 * no size threshold and no cap parameter.
 */
fun unifiedDiff(base: ByteArray, proposed: ByteArray): String {
    if (base.contentEquals(proposed)) return ""

    val baseLines = splitLines(base.decodeToString())
    val proposedLines = splitLines(proposed.decodeToString())

    val script = if (baseLines.text == proposedLines.text) {
        // The line arrays are equal but the byte buffers differ (already proven above): a pure final-newline change
        // (e.g. `…c\n` vs `…c`). git represents it as the last line deleted + re-inserted so the `\ No newline`
        // marker attaches; build that minimal script. (Both sides have >=1 line — an empty side is never equal to a
        // non-empty one, and two empty sides are byte-identical, already returned above.)
        finalNewlineOnlyScript(baseLines.text)
    } else {
        // When the sides disagree on the FINAL newline and Myers ends on a shared (Keep) last line, that context
        // line would emit the marker based on the BASE side only — losing the proposed side's state. Split that
        // trailing Keep into Delete+Insert so the marker attaches correctly (git's behavior).
        val myers = myers(baseLines.text, proposedLines.text)
            // FALLBACK (G2): Myers' trace memory is O(D*(n+m)); two large MAXIMALLY-different documents have a huge
            // edit distance, so we cap D and degrade to a single replace-everything hunk (still fully inspectable —
            // every base line as `-`, every proposed line as `+`), NEVER an opaque sentinel, NEVER an OOM/stall.
            ?: replaceEverythingScript(baseLines.text, proposedLines.text)
        splitTrailingNewlineDisagreement(myers, baseLines, proposedLines)
    }
    if (script.isEmpty()) return ""

    return render(script, baseLines, proposedLines)
}

/** The bounded-fallback script: delete every base line, insert every proposed line — one inspectable replace hunk. */
private fun replaceEverythingScript(a: List<String>, b: List<String>): List<Edit> = buildList {
    for (i in a.indices) add(Edit.Delete(i))
    for (j in b.indices) add(Edit.Insert(j))
}

/** The minimal script for a pure final-newline change: keep all but the last line, then delete+insert the last. */
private fun finalNewlineOnlyScript(lines: List<String>): List<Edit> {
    val last = lines.lastIndex
    return buildList {
        for (i in 0 until last) add(Edit.Keep(i, i))
        add(Edit.Delete(last))
        add(Edit.Insert(last))
    }
}

/**
 * If the sides disagree on the trailing newline AND [script]'s last edit is a Keep of both sides' final line, split
 * that Keep into Delete(base-last) + Insert(proposed-last) so the no-newline marker attaches to the right side.
 * Otherwise [script] already ends on a Delete/Insert that carries the marker faithfully.
 */
private fun splitTrailingNewlineDisagreement(script: List<Edit>, base: Lines, proposed: Lines): List<Edit> {
    if (base.endsWithNewline == proposed.endsWithNewline) return script
    val last = script.lastOrNull()
    if (last !is Edit.Keep || last.baseIndex != base.text.lastIndex || last.proposedIndex != proposed.text.lastIndex) return script
    return script.dropLast(1) + listOf(Edit.Delete(last.baseIndex), Edit.Insert(last.proposedIndex))
}

/** One edit-script step over the two line arrays. */
private sealed interface Edit {
    data class Keep(val baseIndex: Int, val proposedIndex: Int) : Edit
    data class Delete(val baseIndex: Int) : Edit
    data class Insert(val proposedIndex: Int) : Edit
}

/**
 * The bound (G2) on the Myers trace memory: it records ONE V-array (length `2*(n+m)+1`) per D-step, so total memory
 * is O(D*(n+m)) ints. We cap that product at [MAX_TRACE_INTS] (~128 MB of `int`), deriving the allowed edit
 * distance from `(n+m)`: beyond it the walk gives up (returns null) and the caller degrades to the inspectable
 * replace-everything hunk. Two large MAXIMALLY-different documents (huge D) hit the cap and fall back without OOM;
 * normal edits (small D) always land the real minimal diff.
 */
private const val MAX_TRACE_INTS = 32_000_000L

/**
 * Myers' greedy O(ND) edit-graph walk: records each forward frontier (the V band) and back-traces the shortest edit
 * script. Returns null when the edit distance exceeds the G2 trace-memory cap (the caller falls back).
 */
private fun myers(a: List<String>, b: List<String>): List<Edit>? {
    val n = a.size
    val m = b.size
    if (n == 0 && m == 0) return emptyList()
    val max = n + m
    val offset = max
    // The most D-steps we can record without exceeding the trace-memory budget (each step stores 2*max+1 ints).
    val cap = minOf(max.toLong(), MAX_TRACE_INTS / (2L * max + 1)).toInt()
    if (cap < 0) return null
    // V[offset + k] is the furthest-reaching x on diagonal k after the current D-step.
    val v = IntArray(2 * max + 1)
    val trace = ArrayList<IntArray>(cap + 1)

    var editDistance = -1
    outer@ for (d in 0..cap) {
        trace.add(v.copyOf())
        var k = -d
        while (k <= d) {
            val x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                v[offset + k + 1] // down (an insertion)
            } else {
                v[offset + k - 1] + 1 // right (a deletion)
            }
            var px = x
            var py = x - k
            while (px < n && py < m && a[px] == b[py]) {
                px++
                py++
            }
            v[offset + k] = px
            if (px >= n && py >= m) {
                editDistance = d
                break@outer
            }
            k += 2
        }
    }

    if (editDistance < 0) return null // exceeded the G2 cap — the caller degrades to the replace-everything hunk
    return backtrack(a, b, trace, editDistance, offset)
}

/** Walks the recorded frontiers backwards to the origin, emitting Keep/Delete/Insert in forward order. */
private fun backtrack(a: List<String>, b: List<String>, trace: List<IntArray>, editDistance: Int, offset: Int): List<Edit> {
    val edits = ArrayList<Edit>()
    var x = a.size
    var y = b.size
    for (d in editDistance downTo 1) {
        val v = trace[d]
        val k = x - y
        val prevK = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) k + 1 else k - 1
        val prevX = v[offset + prevK]
        val prevY = prevX - prevK
        while (x > prevX && y > prevY) {
            edits.add(Edit.Keep(x - 1, y - 1))
            x--
            y--
        }
        if (x > prevX) {
            edits.add(Edit.Delete(x - 1))
            x--
        } else {
            edits.add(Edit.Insert(y - 1))
            y--
        }
    }
    while (x > 0 && y > 0) {
        edits.add(Edit.Keep(x - 1, y - 1))
        x--
        y--
    }
    edits.reverse()
    return edits
}

private const val CONTEXT = 3

/** Groups the edit script into coalesced hunks (rule 1) and renders each with its `@@` header (rules 2-5). */
private fun render(script: List<Edit>, base: Lines, proposed: Lines): String {
    val hunks = coalesce(script)
    return buildString {
        for (hunk in hunks) {
            appendHunk(this, hunk, script, base, proposed)
        }
    }.trimEnd('\n').let { if (it.isEmpty()) "" else it + "\n" }
}

/** A contiguous span of the edit script (indices into [script]) that renders as one `@@` hunk. */
private data class Hunk(val from: Int, val to: Int)

/**
 * Coalesces the script into hunks: a run of changes plus [CONTEXT] context lines on each side; two runs
 * within <=2*CONTEXT unchanged lines merge (standard unified-diff coalescing).
 */
private fun coalesce(script: List<Edit>): List<Hunk> {
    val changed = script.indices.filter { script[it] !is Edit.Keep }
    if (changed.isEmpty()) return emptyList()

    val hunks = ArrayList<Hunk>()
    var start = (changed.first() - CONTEXT).coerceAtLeast(0)
    var end = (changed.first() + CONTEXT).coerceAtMost(script.lastIndex)
    for (index in changed.drop(1)) {
        if (index - CONTEXT <= end + 1) {
            end = (index + CONTEXT).coerceAtMost(script.lastIndex)
        } else {
            hunks.add(Hunk(start, end))
            start = (index - CONTEXT).coerceAtLeast(0)
            end = (index + CONTEXT).coerceAtMost(script.lastIndex)
        }
    }
    hunks.add(Hunk(start, end))
    return hunks
}

private fun appendHunk(sb: StringBuilder, hunk: Hunk, script: List<Edit>, base: Lines, proposed: Lines) {
    val slice = script.subList(hunk.from, hunk.to + 1)
    val baseStart = firstBaseLine(slice)
    val proposedStart = firstProposedLine(slice)
    val baseCount = slice.count { it is Edit.Keep || it is Edit.Delete }
    val proposedCount = slice.count { it is Edit.Keep || it is Edit.Insert }

    sb.append("@@ -")
        .append(headerStart(baseStart, baseCount)).append(',').append(baseCount)
        .append(" +")
        .append(headerStart(proposedStart, proposedCount)).append(',').append(proposedCount)
        .append(" @@\n")

    for (edit in slice) {
        when (edit) {
            is Edit.Keep -> {
                // A context line is shared content; the no-newline marker fires if EITHER side's FINAL line is this
                // one and that side lacks a trailing newline (the proposed side can lack it even when base does not).
                sb.append(' ').append(base.text[edit.baseIndex]).append('\n')
                val noNewline = (edit.baseIndex == base.text.lastIndex && !base.endsWithNewline) ||
                    (edit.proposedIndex == proposed.text.lastIndex && !proposed.endsWithNewline)
                if (noNewline) sb.append(NO_NEWLINE_MARKER)
            }
            is Edit.Delete -> appendBodyLine(sb, '-', base, edit.baseIndex)
            is Edit.Insert -> appendBodyLine(sb, '+', proposed, edit.proposedIndex)
        }
    }
}

private const val NO_NEWLINE_MARKER = "\\ No newline at end of file\n"

/** A side with zero lines in the hunk reports start 0 (the unified-diff convention); else the 1-based line. */
private fun headerStart(zeroBasedFirst: Int, count: Int): Int = if (count == 0) 0 else zeroBasedFirst + 1

private fun firstBaseLine(slice: List<Edit>): Int = slice.firstNotNullOfOrNull {
    when (it) {
        is Edit.Keep -> it.baseIndex
        is Edit.Delete -> it.baseIndex
        is Edit.Insert -> null
    }
} ?: 0

private fun firstProposedLine(slice: List<Edit>): Int = slice.firstNotNullOfOrNull {
    when (it) {
        is Edit.Keep -> it.proposedIndex
        is Edit.Delete -> null
        is Edit.Insert -> it.proposedIndex
    }
} ?: 0

/** Emits `<prefix><line>\n`, plus git's no-newline marker when this is the final line of a side that lacks one. */
private fun appendBodyLine(sb: StringBuilder, prefix: Char, lines: Lines, index: Int) {
    sb.append(prefix).append(lines.text[index]).append('\n')
    if (index == lines.text.lastIndex && !lines.endsWithNewline) {
        sb.append(NO_NEWLINE_MARKER)
    }
}

/** A decoded side: its lines (split on `\n`, trailing `\r` kept as content) + whether it ended in `\n`. */
private class Lines(val text: List<String>, val endsWithNewline: Boolean)

/**
 * Splits on `\n` ONLY (rule 7 — a trailing `\r` rides as content). A trailing newline does NOT yield a
 * spurious empty final line; instead [Lines.endsWithNewline] records it, so the no-newline marker (rule 4)
 * is emitted exactly when a side lacks a final newline. An empty string is zero lines.
 */
private fun splitLines(text: String): Lines {
    if (text.isEmpty()) return Lines(emptyList(), endsWithNewline = false)
    val endsWithNewline = text.endsWith('\n')
    val body = if (endsWithNewline) text.substring(0, text.length - 1) else text
    return Lines(body.split('\n'), endsWithNewline)
}

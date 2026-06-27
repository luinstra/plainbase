package com.plainbase.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * PB-DIFF-1 INVARIANTS over the adversarial tail (CRLF / no-final-newline / leading BOM / non-UTF8 byte sequences)
 * + random line-sets — the freeze-surface discipline: benign-rare inputs get INVARIANTS, not exact-output goldens.
 * For EVERY generated pair assert:
 *  1. every hunk header is well-formed `@@ -<a>,<b> +<c>,<d> @@`, the counts equal the body's actual line counts;
 *  2. every changed line is accounted for (the applier consumes exactly the `-`/`+` lines);
 *  3. APPLIES-CLEANLY: applying the diff's hunks to the base reproduces the proposed (a test-only patch-applier
 *     oracle, the differential-oracle pattern — the helper is the subject, the applier the oracle).
 *
 * When a review finds a hole the generator missed, widen the GENERATOR (not just the code).
 */
class UnifiedDiffInvariantTest : FunSpec({

    val bom = "﻿"

    test("the three invariants hold for the canonical adversarial-tail cases") {
        val cases = listOf(
            // CRLF line endings ride as literal content (a trailing \r on each line).
            "a\r\nb\r\nc\r\n" to "a\r\nB\r\nc\r\n",
            // No final newline on either side.
            "a\nb\nc" to "a\nB\nc",
            // No final newline on one side only.
            "a\nb\nc\n" to "a\nb\nc",
            // A leading BOM as content.
            "${bom}title\nbody\n" to "${bom}title\nBODY\n",
            // BOM + CRLF + no final newline.
            "${bom}a\r\nb\r\nc" to "${bom}a\r\nB\r\nc",
            // Empty -> content and content -> empty.
            "" to "x\ny\n",
            "x\ny\n" to "",
            // A blank-line-only change.
            "a\n\nb\n" to "a\nb\n",
        )
        for ((base, proposed) in cases) {
            assertInvariants(base.toByteArray(), proposed.toByteArray())
        }
    }

    test("a non-UTF8 tail decodes leniently and still satisfies the invariants") {
        val base = "header\n".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\nbody\n".toByteArray()
        val proposed = "header\n".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\nBODY\n".toByteArray()
        // The diff operates on the lenient UTF-8 decode (presentation), so the oracle compares the decoded sides.
        assertInvariants(base, proposed)
    }

    test("randomized line-sets satisfy the invariants") {
        val rng = Random(20260623)
        repeat(300) {
            val base = randomLines(rng)
            val proposed = mutate(base, rng)
            assertInvariants(base.toByteArray(), proposed.toByteArray())
        }
    }
})

private fun randomLines(rng: Random): String {
    val count = rng.nextInt(0, 12)
    if (count == 0) return ""
    val sb = StringBuilder()
    repeat(count) { sb.append(('a' + rng.nextInt(0, 6))).append(rng.nextInt(0, 4)).append('\n') }
    // Half the time drop the final newline.
    return if (rng.nextBoolean() && sb.isNotEmpty()) sb.dropLast(1).toString() else sb.toString()
}

/** Randomly inserts/deletes/changes lines to produce a different proposed text. */
private fun mutate(base: String, rng: Random): String {
    val lines = if (base.isEmpty()) mutableListOf() else base.removeSuffix("\n").split('\n').toMutableList()
    repeat(rng.nextInt(0, 5)) {
        when (rng.nextInt(0, 3)) {
            0 -> lines.add(rng.nextInt(0, lines.size + 1), "ins" + rng.nextInt(0, 100))
            1 -> if (lines.isNotEmpty()) lines.removeAt(rng.nextInt(0, lines.size))
            else -> if (lines.isNotEmpty()) lines[rng.nextInt(0, lines.size)] = "chg" + rng.nextInt(0, 100)
        }
    }
    if (lines.isEmpty()) return ""
    return lines.joinToString("\n") + if (rng.nextBoolean()) "\n" else ""
}

private val HUNK_HEADER = Regex("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@")

private fun assertInvariants(base: ByteArray, proposed: ByteArray) {
    val diff = unifiedDiff(base, proposed)
    val baseDecoded = base.decodeToString()
    val proposedDecoded = proposed.decodeToString()
    if (baseDecoded == proposedDecoded) {
        diff shouldBe ""
        return
    }
    // Invariant 1 + 2: well-formed headers with consistent counts, and the body matches. Invariant 3: applies cleanly.
    applyPatch(baseDecoded, diff) shouldBe proposedDecoded
}

/**
 * The test-only patch-applier ORACLE: re-derives the proposed text from the base + the unified diff hunks. It
 * validates each hunk header's counts against the actual body (invariants 1+2) and reconstructs the proposed lines
 * (invariant 3). Mirrors the helper's split (on `\n`, trailing `\r` as content; final-newline tracked separately).
 */
private fun applyPatch(base: String, diff: String): String {
    val baseLines = splitForOracle(base)
    if (diff.isEmpty()) return base

    val result = ArrayList<String>()
    var baseCursor = 0 // 0-based index into baseLines.text
    // Default: an unchanged final line inherits the base's trailing-newline state. A proposed line emitted INSIDE a
    // hunk overrides this to "has newline" unless the marker immediately follows it (then "no newline").
    var proposedNoNewline = !baseLines.endsWithNewline

    val lines = diff.split('\n').let { if (it.isNotEmpty() && it.last() == "") it.dropLast(1) else it }
    var i = 0
    while (i < lines.size) {
        val header = lines[i]
        val match = requireNotNull(HUNK_HEADER.matchEntire(header)) { "malformed hunk header: '$header'\nfull diff:\n$diff" }
        val (aStart, aCount, _, bCount) = match.destructured
        i++

        // Copy unchanged base lines BEFORE this hunk.
        val hunkBaseStart = if (aCount.toInt() == 0) aStart.toInt() else aStart.toInt() - 1
        while (baseCursor < hunkBaseStart) {
            result.add(baseLines.text[baseCursor])
            baseCursor++
        }

        var seenBase = 0
        var seenProposed = 0
        while (i < lines.size && !lines[i].startsWith("@@")) {
            val bodyLine = lines[i]
            i++
            if (bodyLine == "\\ No newline at end of file") continue // handled by the look-ahead below
            val nextIsMarker = i < lines.size && lines[i] == "\\ No newline at end of file"
            when {
                bodyLine.startsWith(" ") -> {
                    result.add(bodyLine.substring(1))
                    baseCursor++
                    seenBase++
                    seenProposed++
                    proposedNoNewline = nextIsMarker
                }
                bodyLine.startsWith("-") -> {
                    baseCursor++
                    seenBase++
                }
                bodyLine.startsWith("+") -> {
                    result.add(bodyLine.substring(1))
                    seenProposed++
                    proposedNoNewline = nextIsMarker
                }
                else -> error("malformed body line: '$bodyLine'")
            }
        }
        // Invariant 1+2: the header counts equal the actual body line counts on each side.
        seenBase shouldBeTrueEq aCount.toInt()
        seenProposed shouldBeTrueEq bCount.toInt()
    }
    // Copy the remaining unchanged tail. If any tail line is copied, the proposed's FINAL line is an unchanged
    // line outside every hunk, so its trailing-newline state inherits the base's (no marker can apply to it).
    if (baseCursor < baseLines.text.size) {
        proposedNoNewline = !baseLines.endsWithNewline
        while (baseCursor < baseLines.text.size) {
            result.add(baseLines.text[baseCursor])
            baseCursor++
        }
    }
    if (result.isEmpty()) return ""
    return result.joinToString("\n") + if (proposedNoNewline) "" else "\n"
}

private class OracleLines(val text: List<String>, val endsWithNewline: Boolean)

private fun splitForOracle(text: String): OracleLines {
    if (text.isEmpty()) return OracleLines(emptyList(), endsWithNewline = false)
    val endsWithNewline = text.endsWith('\n')
    val body = if (endsWithNewline) text.substring(0, text.length - 1) else text
    return OracleLines(body.split('\n'), endsWithNewline)
}

private infix fun Int.shouldBeTrueEq(other: Int) {
    (this == other).shouldBeTrue()
}

package com.plainbase.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The G2 worst-case bound: two LARGE, MAXIMALLY-different documents (the quadratic worst case for a naive n*m LCS
 * matrix) must produce a real, inspectable diff WITHOUT OOM or stall. The linear-space Myers walk keeps this
 * tractable; the test proves TERMINATION (a generous-but-finite timeout), not a perf SLA.
 */
class UnifiedDiffLargeInputTest : FunSpec({

    test("two large maximally-different documents diff within a bounded envelope (no OOM, no stall)") {
        // ~40k lines per side, every line different — a large edit distance. Each line is short so the byte size
        // stays well within maxWriteBodyBytes while the line count stresses the edit-graph walk.
        val base = buildString { repeat(40_000) { append("base-line-").append(it).append('\n') } }.toByteArray()
        val proposed = buildString { repeat(40_000) { append("proposed-line-").append(it).append('\n') } }.toByteArray()

        var result = ""
        eventuallyCompletes(60.seconds) { result = unifiedDiff(base, proposed) }

        // A real, inspectable diff (every base line deleted, every proposed line inserted) — never an opaque sentinel.
        result.contains("@@ ").shouldBeTrue()
        result.contains("-base-line-0").shouldBeTrue()
        result.contains("+proposed-line-0").shouldBeTrue()
    }
})

/** Runs [block] on a worker thread, failing if it does not finish within [within] (proves termination, not speed). */
private fun eventuallyCompletes(within: kotlin.time.Duration, block: () -> Unit) {
    val worker = Thread { block() }
    worker.isDaemon = true
    worker.start()
    worker.join(within.inWholeMilliseconds)
    check(!worker.isAlive) { "unifiedDiff did not complete within $within (possible stall/quadratic blowup)" }
}

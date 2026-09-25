package com.plainbase.domain.discussion

import com.plainbase.domain.page.Heading
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * The measurement reports 200 Discussions on an 8 MiB page. MEETS keeps re-anchoring on the read path.
 * MISSES is valid evidence to add page-change pre-warming in a later slice. The owner also rules on pages larger
 * than 1 MiB. The per-pass budget is `D x N / 1,048,576` milliseconds, or 1 ms per Discussion-MiB.
 * Reaching MEETS must not involve replacing KMP, skipping the bare pass, special-casing the fixture, moving
 * per-Discussion work into `ReanchorPage`, or shrinking the workload.
 */
class ReanchorPerfProbeTest : FunSpec({
    test("linear matching finishes the adversarial quote inside its deadline") {
        val capture = adversarialCapture(HeadingPath.EMPTY)
        val anchor = Anchor.Quote("sha256:${"0".repeat(64)}", null, capture)
        val page = ReanchorPage.of("a".repeat(1_048_576).encodeToByteArray(), emptyList())
        val result = AtomicReference<AnchorMatch?>()

        runOnDaemon("reanchor-linear-check", 5_000) {
            result.set(Reanchor.match(anchor, page))
        }

        result.get() shouldBe AnchorMatch.Changed(Placement.Line(1))
    }

    test("reanchor probe 200 discussions on 8 MiB").config(enabled = System.getenv("PLAINBASE_REANCHOR_PROBE") == "1") {
        val sections = mutableListOf<String>()
        val headings = mutableListOf<Heading>()
        repeat(256) { index ->
            val number = "%04d".format(Locale.ROOT, index)
            val header = "## Section $number\n\n"
            val section = header + "a".repeat(32_768 - header.length - 2) + "\n\n"
            check(section.encodeToByteArray().size == 32_768)
            sections.add(section)
            headings.add(Heading("section-$number", 2, "Section $number"))
        }
        val raw = sections.joinToString("").encodeToByteArray()
        raw.size shouldBe 8_388_608
        val page = ReanchorPage.of(raw, headings)
        val path = HeadingPath(listOf(HeadingPath.Entry(1, "Gone")))
        val capture = adversarialCapture(path)
        val discussions = List(200) { Anchor.Quote("sha256:${"0".repeat(64)}", null, capture) }
        val durations = mutableListOf<Long>()
        var lastPassResults = emptyList<AnchorMatch>()

        runOnDaemon("reanchor-probe", 64_000) {
            repeat(4) { pass ->
                if (Thread.interrupted()) throw InterruptedException("probe interrupted between passes")
                val started = System.nanoTime()
                lastPassResults = discussions.map { Reanchor.match(it, page) }
                val elapsed = System.nanoTime() - started
                if (pass > 0) durations.add(elapsed)
            }
        }

        val expected = AnchorMatch.Changed(Placement.Line(1))
        lastPassResults shouldBe List(200) { expected }
        val medianNanos = durations.sorted()[1]
        val medianMs = medianNanos / 1_000_000.0
        val budgetMs = 200.0 * raw.size / 1_048_576.0
        val throughput = 2.0 * 200 * raw.size / 1_048_576.0 / (medianMs / 1_000.0)
        val verdict = if (medianMs <= budgetMs) "MEETS" else "MISSES"
        println(
            "reanchor-probe: D=200 N=${raw.size} H=256 " +
                "median=${"%.1f".format(Locale.ROOT, medianMs)} budget=${"%.0f".format(Locale.ROOT, budgetMs)} ms " +
                "throughput=${"%.1f".format(Locale.ROOT, throughput)} MiB/s verdict=$verdict",
        )
    }
})

private fun adversarialCapture(path: HeadingPath): QuoteCapture = QuoteCapture(
    quote = "a".repeat(16_383) + "b",
    prefix = "a".repeat(64),
    suffix = "a".repeat(64),
    byteStart = 64,
    byteEnd = 16_448,
    bodyStart = 0,
    line = 1,
    selection = AnchorSelection.NARROWED,
    headingPath = path,
)

private fun runOnDaemon(name: String, deadlineMs: Long, body: () -> Unit) {
    val failure = AtomicReference<Throwable?>()
    val thread = Thread({
        try {
            body()
        } catch (throwable: Throwable) {
            failure.set(throwable)
        }
    }, name)
    thread.isDaemon = true
    thread.start()
    thread.join(deadlineMs)
    if (thread.isAlive) {
        thread.interrupt()
        error("$name did not finish within $deadlineMs ms")
    }
    failure.get()?.let { throw it }
}

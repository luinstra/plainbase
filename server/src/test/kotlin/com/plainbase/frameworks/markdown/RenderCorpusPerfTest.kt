package com.plainbase.frameworks.markdown

import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.pageContent
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.system.measureNanoTime

/**
 * Master criterion: render p95 < 300 ms, measured AT INDEX TIME (C2 item 1). This is the budget
 * [com.plainbase.domain.service.IndexBuilder] pays per page inside `rebuild()`/`reindex()` — a GET
 * serves the precomputed `page.html` straight off the published snapshot (`PageHtmlPayload.toDto()`,
 * RestDtos.kt) and is a lookup, not a render, so this number never bounds GET latency.
 *
 * The corpus is the shared 1,000-page generator PLUS ~20 large (~100 KiB) pages seeded in-tree
 * before the single rebuild, so they join the published snapshot (and the renderer's URL context)
 * like every other page. Each 200-sample window is 180 generated pages in stable path order + ALL
 * 20 large pages — large pages are 10% of samples, comfortably above the 5% p95 tail, so a slow
 * large-page render lands in the p95 by construction. R14 flake policy (the shipping FTS5 gate's):
 * measure after a >= 50-sample warmup, p95 over >= 200 samples, ONE auto-retry before failing.
 */
class RenderCorpusPerfTest : FunSpec({

    test("1,000-page + 20-large corpus: render-at-index warm p95 < 300 ms (R14 policy)") {
        val pageCount = 1000
        val largeCount = 20
        withTempTree(seed = { root ->
            repeat(pageCount) { n -> writePage(root, "section-%02d/page-%03d.md".format(n % 10, n), pageContent(n)) }
            repeat(largeCount) { n -> writePage(root, "large/large-%02d.md".format(n), largePageContent(n, largeCount)) }
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()
                snapshot.pages.size shouldBe pageCount + largeCount

                // ONE renderer over the published snapshot — exactly what a rebuild's rendererFactory
                // binds per pass (IndexModule/IndexBuilder). The sampled bytes are the UTF-8 round-trip
                // of the same bytes production renders (IndexedPage.markdown IS the decode of them).
                val renderer = FlexmarkRenderer(snapshot.view(RootName.PRIMARY))
                val (large, generated) = snapshot.pages.sortedBy { it.path.value }.partition { it.path.value.startsWith("large/") }
                large.size shouldBe largeCount

                // The deterministic mixed window: 180 generated pages cycled in stable path order + all 20 large.
                val window = (0 until 180).map { generated[it % generated.size] } + large
                fun p95(): Double {
                    val times = window.map { page ->
                        measureNanoTime { renderer.render(page.path, page.markdown.toByteArray()) } / 1e6
                    }
                    return times.sorted()[(window.size * 95) / 100]
                }

                repeat(50) { renderer.render(window[it % window.size].path, window[it % window.size].markdown.toByteArray()) } // warmup
                var p95Millis = p95()
                if (p95Millis >= 300.0) p95Millis = p95() // R14: one auto-retry, then it is real

                println("render-perf: warm p95 %.2f ms over ${window.size} samples".format(p95Millis))
                p95Millis shouldBeLessThan 300.0
            }
        }
    }
})

/**
 * A ~100 KiB page (the honesty strengthener): the generated corpus's ~10-line pages alone would let
 * the p95 flatter the render budget; these pay real parse/render work — links included — at scale.
 */
private fun largePageContent(n: Int, largeCount: Int): String = buildString {
    appendLine("---")
    appendLine("title: Large %02d".format(n))
    appendLine("---")
    appendLine()
    appendLine("# Large %02d".format(n))
    val sentence = "Body text for large page $n with a [sibling link](large-%02d.md), some *emphasis*, and enough prose " +
        "to make the render pay real parsing work across paragraphs and inline runs. ".format((n + 1) % largeCount)
    val paragraph = sentence.repeat(3).trim()
    repeat(200) {
        appendLine()
        appendLine(paragraph)
    }
}

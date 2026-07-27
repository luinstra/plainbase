package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.search.withProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Does splitting the SAME corpus across N roots cost anything? One `IndexBuilder` scans every source into one
 * published snapshot, so the answer should be "no" — this MEASURES it instead of assuming it, over the same
 * 1,000 pages in ONE root and in THREE.
 *
 * ONE test, one run, one machine, deliberately: a 3-root number is only worth what its 1-root pair is worth,
 * and both are only worth what the run-to-run noise on THIS machine says they are — so the paired shapes and a
 * second, independent 1-root run all happen inside the same case.
 *
 * GATED (these can fail, in CI, on the spot): the 3-root shape clears the SAME absolute literals the 1-root
 * tests already assert — render p95 < 300 ms (RenderCorpusPerfTest) and the write-path tripwires create < 15 s
 * / solo save < 1 s (WritePathCorpusPerfTest). A regression big enough to breach those is a hard failure. The
 * RATIOS are PRINTED and never asserted: a cross-shape ratio on a ~2x-slower shared runner is exactly the flake
 * the R14 policy exists to prevent, and a perf gate that flakes gets deleted.
 *
 * **THE PRE-COMMITTED DECISION RULE, applied to the printed numbers at chunk verification, never here:** if the
 * 3-root/1-root ratio exceeds **1.20** on ANY of the three metrics (rebuild median, create median, render p95),
 * the per-root partition-rebuild follow-up is KEPT and the result is ESCALATED before any production code is
 * written. At or below 1.20 on all three, the full-corpus-rebuild scope cut STANDS and the follow-up is retired.
 * If the 1-root-vs-1-root NOISE FLOOR itself exceeds 1.20, the measurement is INVALID: raise the repetition
 * counts until it does not, and say so. (The rule is written here, ahead of the numbers, so that it cannot be
 * rationalized once they are in.)
 *
 * Production-shaped search wiring, as in WritePathCorpusPerfTest: the serve graph registers `SearchIndexer.sync`
 * as a publication listener AND hands the indexer to IndexBuilder, so a rebuild pays the real engine-truth-diff
 * share and a create's reindex the real single-page upsert. Without it the create numbers would not be
 * comparable to the tripwires they are asserted against.
 */
class MultiRootCorpusPerfTest : FunSpec({

    test("1,000 pages in 1 root vs 3: rebuild / create / render (ratios recorded, the 1-root literals gated)") {
        // Contiguous slices, so every page's generated sibling link resolves exactly as it does in the 1-root
        // corpus: the two shapes render the same work, not merely the same page count.
        val single = measure(listOf("main" to (0 until 1000)))
        val singleAgain = measure(listOf("main" to (0 until 1000)))
        val triple = measure(listOf("main" to (0 until 334), "extra" to (334 until 667), "archive" to (667 until 1000)))

        report("rebuild median ms", single.rebuildMedian, singleAgain.rebuildMedian, triple.rebuildMedian)
        report("create median ms", single.createMedian, singleAgain.createMedian, triple.createMedian)
        report("render p95 ms", single.renderP95, singleAgain.renderP95, triple.renderP95)
        // No ratio: an O(1) reindex does not scale with the root count, and a ratio over single-digit
        // milliseconds reports the scheduler, not the topology. Recorded because it is a gated tripwire.
        println(
            "multiroot-perf: solo-save median ms 1-root %d / 3-root %d (tripwire only)"
                .format(single.soloMedian, triple.soloMedian),
        )

        // The 1-root literals, unchanged, against the 3-root shape.
        triple.renderP95 shouldBeLessThan 300.0
        triple.createMedian shouldBeLessThan 15_000L
        triple.createMin shouldBeLessThan 15_000L
        triple.soloMedian shouldBeLessThan 1_000L
    }
})

/** One shape's numbers. [createMin] is the write-path tripwire's second leg; [soloMedian] its O(1) contrast. */
private class Metrics(
    val rebuildMedian: Long,
    val createMedian: Long,
    val createMin: Long,
    val soloMedian: Long,
    val renderP95: Double,
)

/**
 * `multiroot-perf: <metric> 1-root <a> / 3-root <b> (ratio b/a)`, and under it the SECOND 1-root run — a ratio
 * compared against nothing is a number compared against nothing, so the noise floor of the machine that
 * produced the numbers is printed beside every one of them.
 */
private fun report(metric: String, single: Number, singleAgain: Number, triple: Number) {
    val a = single.toDouble()
    println("multiroot-perf: %s 1-root %.2f / 3-root %.2f (ratio %.2f)".format(metric, a, triple.toDouble(), triple.toDouble() / a))
    println(
        "multiroot-perf: %s 1-root %.2f / 1-root %.2f (noise floor %.2f)"
            .format(metric, a, singleAgain.toDouble(), singleAgain.toDouble() / a),
    )
}

/**
 * Every metric for ONE topology, in one harness so all four numbers describe the same corpus. The sampling
 * ORDER is load-bearing: the mutating cases (solo saves, then creates) run LAST, after the rebuild and render
 * samples have been taken over the pristine corpus.
 */
private fun measure(slices: List<Pair<String, IntRange>>): Metrics = withSeededTrees(slices.map { it.second }) { trees ->
    val registry = RootRegistry.of(slices.mapIndexed { index, (name, _) -> localRoot(name, trees[index]) })
    val sources = registry.roots.mapIndexed { index, root ->
        IndexBuilder.Source(root, LocalContentStore(trees[index]), NoOpHistoryProvider)
    }
    withProvider { provider, _ ->
        val indexer = SearchIndexer(provider, SectionSplitter())
        IndexHarness(
            trees.first(),
            listeners = listOf(
                IndexBuilder.PublicationListener { snap, retired ->
                    indexer.sync(snap, retired)
                },
            ),
            searchIndexer = indexer,
            rootRegistry = registry,
            sources = sources,
        ).use { harness ->
            harness.builder.rebuild() // warmup: the first pass pays cold class loading and an empty-checkpoint diff
            val rebuilds = (0 until 5).map { measureTimeMillis { harness.builder.rebuild() } }.sorted()
            val snapshot = harness.builder.current
            snapshot.pages.size shouldBe TOTAL_PAGES

            val renderP95 = renderP95(snapshot)

            val pipeline = harness.writePipeline()
            // The O(1) contrast, on a page that lives in MAIN under both shapes. Append-only edits, so the
            // materialized frontmatter (id included) never changes and classifyEdit stays green.
            val target = TreePath.require("section-00/page-000.md")
            val saves = (0 until 20).map { round ->
                val current = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, target))
                val bytes = (current.markdown + "\nsave round $round.\n").toByteArray()
                val outcome: WriteOutcome
                val millis = measureTimeMillis {
                    outcome = pipeline.write(
                        grantForTests(),
                        WriteIntent(current.id, RootName.PRIMARY, current.path, current.contentHash, bytes),
                    )
                }
                outcome.shouldBeInstanceOf<WriteOutcome.Written>()
                millis
            }.sorted()

            // Into MAIN under both shapes: a create runs a FULL rebuild over EVERY source, so its cost is the
            // question the split poses, and pinning the target root is what keeps the two shapes comparable.
            val creates = (0 until 3).map { i -> timedCreate(pipeline, "multiroot-perf-%02d/created.md".format(i)) }.sorted()

            Metrics(
                rebuildMedian = rebuilds[2],
                createMedian = creates[1],
                createMin = creates.first(),
                soloMedian = saves[10],
                renderP95 = renderP95,
            )
        }
    }
}

/**
 * Warm render p95 at index time — the R14 policy RenderCorpusPerfTest gates on: >= 50-sample warmup, p95 over
 * >= 200 samples, ONE auto-retry before it counts. The window strides the published snapshot in stable path
 * order and renders each page through ITS OWN root's view; a cross-root renderer would resolve that page's
 * links against the wrong tree.
 */
private fun renderP95(snapshot: PageIndex): Double {
    val renderers = snapshot.sections.associate { it.root to FlexmarkRenderer(snapshot.view(it.root)) }
    val pages = snapshot.pages.sortedBy { it.path.value }
    val window = (0 until RENDER_SAMPLES).map { pages[it * pages.size / RENDER_SAMPLES] }

    fun render(page: IndexedPage): Double =
        measureNanoTime { renderers.getValue(page.root).render(page.path, page.markdown.toByteArray()) } / 1e6

    fun p95(): Double = window.map { render(it) }.sorted()[(window.size * 95) / 100]

    repeat(50) { render(window[it % window.size]) }
    val first = p95()
    return if (first < 300.0) first else p95()
}

/**
 * Nests one [withTempTree] per root, each seeded with its slice of the shared 1,000-page corpus — the same
 * generator both shipped perf tests use, so these shapes are measured over the very pages their literals were.
 */
private fun <T> withSeededTrees(slices: List<IntRange>, block: (List<Path>) -> T): T =
    if (slices.isEmpty()) {
        block(emptyList())
    } else {
        withTempTree(seed = { tree -> slices.first().forEach { n -> writePage(tree, pagePath(n), pageContent(n)) } }) { tree ->
            withSeededTrees(slices.drop(1)) { rest -> block(listOf(tree) + rest) }
        }
    }

/** One pipeline create into main at [path], with a production-minted UUIDv7 id materialized into the bytes. */
private fun timedCreate(pipeline: WritePipeline, path: String): Long {
    val pageId = UuidV7IdProvider().next()
    val bytes = "---\nid: ${pageId.value}\ntitle: Created\n---\n\n# Created\n\nbody.\n".toByteArray()
    val outcome: WriteOutcome
    val millis = measureTimeMillis {
        outcome = pipeline.create(createGrantForTests(), CreateIntent(pageId, RootName.PRIMARY, TreePath.require(path), bytes))
    }
    outcome.shouldBeInstanceOf<WriteOutcome.Written>()
    return millis
}

private fun pagePath(n: Int): String = "section-%02d/page-%03d.md".format(n % 10, n)

private const val TOTAL_PAGES = 1000

private const val RENDER_SAMPLES = 200

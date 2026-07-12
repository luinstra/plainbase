package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.search.withProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * C2 item 4 — MEASURE the O(corpus) full-rebuild-per-create (WritePipeline.create runs a FULL
 * `indexBuilder.rebuild()` + the targeted O(1) `reindex()`, both under the pipeline monitor every
 * save serializes on). The `write-perf:` printed numbers are the deliverable — the tripwire asserts
 * are generous regression bounds (3-30x headroom), NOT the contract; the pre-committed 8 s
 * defer-vs-escalate rule is applied to the recorded numbers at chunk verification, never here.
 *
 * Production-shaped search wiring: the serve graph registers `SearchIndexer.sync` as a publication
 * listener AND hands the indexer to IndexBuilder (SearchModule/IndexModule), so the create's
 * rebuild pays the real engine-truth-diff share and its reindex the real single-page upsert.
 *
 * C2 item 5 rides in the second case — the serve-mode watcher ECHO: every pipeline create lands in
 * CONTENT_DIR and re-enters via the watcher, so a create costs TWO full O(corpus) passes (its own
 * direct rebuild + one coalesced echo ~500 ms after quiet). Full passes are counted at the ONE
 * chokepoint every full pass enters — `ContentStore.scan()`, called exactly once per `rebuild()`
 * and never by the targeted `reindex` — because a scheduler-lambda counter would MISS create's
 * direct rebuild and mis-answer the question. The `create-echo:` count is a BINDING local
 * observation (recorded + escalate-on-surprise in the chunk report), never a CI gate: on deadline
 * expiry the case prints what it saw and returns normally (watcher timing is platform-noisy).
 */
class WritePathCorpusPerfTest : FunSpec({

    test("write-path ceilings: create/solo-save/blocked-save at 1,000 pages + the 250-page slope (recorded, tripwired)") {
        // --- The 1,000-page contract corpus: measurements (a)-(c). ---
        val (createMedian, createMin, soloMedian) = withTempTree(seed = corpusSeed(1000)) { root ->
            val store = LocalContentStore(root)
            val armed = AtomicBoolean(false)
            val createScanEntered = CountDownLatch(1)
            val observing = object : ContentStore by store {
                override fun scan(): ScanResult {
                    if (armed.get()) createScanEntered.countDown()
                    return store.scan()
                }
            }
            withProvider { provider, _ ->
                val indexer = SearchIndexer(provider, SectionSplitter())
                IndexHarness(
                    root,
                    contentStore = observing,
                    listeners = listOf(IndexBuilder.PublicationListener(indexer::sync)), // rebuild's serve-shape engine sync
                    searchIndexer = indexer, // reindex's propagating syncPage
                ).use { harness ->
                    harness.builder.rebuild()
                    val pipeline = harness.writePipeline()

                    // (a) Create end-to-end: full rebuild + targeted reindex under the monitor, distinct folders.
                    val createTimes = (0 until 5).map { i -> timedCreate(pipeline, "perf-%02d/created.md".format(i)) }.sorted()

                    // (b) The O(1) contrast: 20 saves of one page, fresh baseHash each round (append-only edits
                    // so the materialized frontmatter — id included — never changes and classifyEdit stays green).
                    val target = TreePath.require("section-00/page-000.md")
                    val saveTimes = (0 until 20).map { round ->
                        val current = harness.builder.current.byPath.getValue(RootedPath(RootName.MAIN, target))
                        val bytes = (current.markdown + "\nsave round $round.\n").toByteArray()
                        val outcome: WriteOutcome
                        val millis = measureTimeMillis {
                            outcome =
                                pipeline.write(
                                    grantForTests(),
                                    WriteIntent(current.id, RootName.MAIN, current.path, current.contentHash, bytes),
                                )
                        }
                        outcome.shouldBeInstanceOf<WriteOutcome.Written>()
                        millis
                    }.sorted()

                    // (c) Save-blocked-by-create: thread A creates; once A is inside its rebuild's scan (the
                    // IndexBuilderConcurrencyTest gating idiom, latch-armed so the earlier passes don't trip it),
                    // the save below blocks on the shared pipeline monitor for the create's remainder.
                    val current = harness.builder.current.byPath.getValue(RootedPath(RootName.MAIN, target))
                    val blockedBytes = (current.markdown + "\nblocked save.\n").toByteArray()
                    armed.set(true)
                    val createResult = AtomicReference<Result<WriteOutcome>>()
                    val creator = thread(name = "perf-create") {
                        createResult.set(
                            runCatching {
                                val pageId = UuidV7IdProvider().next()
                                val bytes = "---\nid: ${pageId.value}\ntitle: Created\n---\n\n# Created\n\nbody.\n".toByteArray()
                                pipeline.create(
                                    createGrantForTests(),
                                    CreateIntent(pageId, RootName.MAIN, TreePath.require("perf-blocked/created.md"), bytes),
                                )
                            },
                        )
                    }
                    createScanEntered.await(10, TimeUnit.SECONDS) shouldBe true
                    val blockedOutcome: WriteOutcome
                    val blockedWall = measureTimeMillis {
                        blockedOutcome =
                            pipeline.write(
                                grantForTests(),
                                WriteIntent(current.id, RootName.MAIN, current.path, current.contentHash, blockedBytes),
                            )
                    }
                    // The blocked-save number only means anything if the create it blocked on genuinely
                    // completed: fail loud on a hung join, rethrow a creator-thread exception.
                    creator.join(30_000)
                    creator.isAlive shouldBe false
                    createResult.get().getOrThrow().shouldBeInstanceOf<WriteOutcome.Written>()
                    blockedOutcome.shouldBeInstanceOf<WriteOutcome.Written>()

                    val median = createTimes[2]
                    val soloMedian = saveTimes[10]
                    println("write-perf: create median %d ms, min %d ms over 5 creates".format(median, createTimes.first()))
                    println("write-perf: solo save median %d ms".format(soloMedian))
                    println("write-perf: blocked-save delta %d ms".format(blockedWall - soloMedian))
                    Triple(median, createTimes.first(), soloMedian)
                }
            }
        }

        // --- (d) Per-page slope: the same create at a 250-page corpus, so the defer-trigger corpus size
        // is computable from the test output. Median of 3 (a single create would put raw noise in the slope).
        val create250 = withTempTree(seed = corpusSeed(250)) { root ->
            withProvider { provider, _ ->
                val indexer = SearchIndexer(provider, SectionSplitter())
                IndexHarness(
                    root,
                    listeners = listOf(IndexBuilder.PublicationListener(indexer::sync)),
                    searchIndexer = indexer,
                ).use { harness ->
                    harness.builder.rebuild()
                    val pipeline = harness.writePipeline()
                    (0 until 3).map { i -> timedCreate(pipeline, "perf-slope-%02d/created.md".format(i)) }.sorted()[1]
                }
            }
        }
        val slope = (createMedian - create250).toDouble() / 750
        println("write-perf: slope %.2f ms/page (250 → 1000)".format(slope))
        // The v0.1.x deferred-fix trigger: the corpus size where the measured slope projects create > ~5 s
        // (a UX ceiling, deliberately tighter than the 8 s escalation threshold — a projection pays no noise).
        if (slope > 0.0) {
            val deferTrigger = (250 + (5_000 - create250) / slope).toLong()
            println("write-perf: defer-trigger ≈ %d pages at the measured %.2f ms/page".format(deferTrigger, slope))
        } else {
            println("write-perf: defer-trigger not projectable (non-positive slope %.2f ms/page)".format(slope))
        }

        // Regression tripwires only (3x the gated < 5 s rebuild ceiling / ~30x a solo save) — the recorded
        // numbers above are the deliverable, and the 8 s decision rule is applied to THEM, not asserted here.
        createMedian shouldBeLessThan 15_000L
        createMin shouldBeLessThan 15_000L
        soloMedian shouldBeLessThan 1_000L
    }

    test("serve-shape create: full passes at the scan chokepoint — binding LOCAL observation, never a CI gate") {
        withTempTree(seed = corpusSeed(10)) { root ->
            val store = LocalContentStore(root)
            val scans = AtomicInteger()
            val counting = object : ContentStore by store {
                override fun scan(): ScanResult {
                    scans.incrementAndGet()
                    return store.scan()
                }
            }
            withProvider { provider, _ ->
                val indexer = SearchIndexer(provider, SectionSplitter())
                IndexHarness(
                    root,
                    contentStore = counting,
                    listeners = listOf(IndexBuilder.PublicationListener(indexer::sync)),
                    searchIndexer = indexer,
                ).use { harness ->
                    // The serve shape, assembled as WatchingRestHarness/Application.serve() wire it:
                    // scheduler over the builder, watch registered FIRST, then the startup rebuild (§B2).
                    RebuildScheduler(rebuild = { harness.builder.rebuild() }, alarm = ExecutorAlarm()).use { scheduler ->
                        store.watch(onChange = { scheduler.schedule() }).use {
                            harness.builder.rebuild()
                            scans.set(0)

                            val pageId = UuidV7IdProvider().next()
                            val bytes = "---\nid: ${pageId.value}\ntitle: Created\n---\n\n# Created\n\nbody.\n".toByteArray()
                            val outcome = harness.writePipeline()
                                .create(
                                    createGrantForTests(),
                                    CreateIntent(pageId, RootName.MAIN, TreePath.require("section-00/created.md"), bytes),
                                )
                            outcome.shouldBeInstanceOf<WriteOutcome.Written>()

                            // Record-not-throw await (the WatcherPipelineTest awaitUntil idiom, modified): the
                            // case must never fail or hang CI on watcher timing — the observed count is what binds.
                            // Expected echo ≈ DEBOUNCE_MILLIS * 3 (~1.5 s); 15 s is ~10x safety while keeping a
                            // watcher MISS a seconds-scale cost per build, not minutes — expiry still records + passes.
                            val deadline = System.nanoTime() + 15_000L * 1_000_000
                            while (scans.get() < 2 && System.nanoTime() < deadline) Thread.sleep(25)
                            if (scans.get() < 2) println("create-echo: await for a second full pass timed out after 15 s (inconclusive)")
                            Thread.sleep(RebuildScheduler.DEBOUNCE_MILLIS * 3) // let any trailing rebuild land
                            println("create-echo: ${scans.get()} full passes observed (expected 2, coalesced)")
                        }
                    }
                }
            }
        }
    }
})

/** The Phase-1 generated corpus at [pageCount] pages (the Fts5CorpusPerfTest seed, parameterized). */
private fun corpusSeed(pageCount: Int): (Path) -> Unit = { root ->
    repeat(pageCount) { n -> writePage(root, "section-%02d/page-%03d.md".format(n % 10, n), pageContent(n)) }
}

/** One pipeline create into [path] with a fresh production-minted UUIDv7 id materialized into the bytes, asserted Written. */
private fun timedCreate(pipeline: WritePipeline, path: String): Long {
    val pageId = UuidV7IdProvider().next()
    val bytes = "---\nid: ${pageId.value}\ntitle: Created\n---\n\n# Created\n\nbody.\n".toByteArray()
    val outcome: WriteOutcome
    val millis = measureTimeMillis {
        outcome = pipeline.create(createGrantForTests(), CreateIntent(pageId, RootName.MAIN, TreePath.require(path), bytes))
    }
    outcome.shouldBeInstanceOf<WriteOutcome.Written>()
    return millis
}

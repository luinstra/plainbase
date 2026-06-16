package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.search.SearchQuery
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * PB-WRITE-1 named test 7 (fix R4): a watcher `rebuild()` racing a `pipeline.write()` cannot deadlock.
 * Mirrors [IndexBuilderReindexConcurrencyTest]: park a watcher rebuild on its gated scan; fire a save;
 * observe the save's thread BLOCKED at the shared IndexBuilder monitor (where `reindex` waits); release;
 * both finish within a bounded join. The one-directional lock order (pipeline → IndexBuilder, never
 * back) is what makes the contention a simple wait, not a cycle.
 *
 * Plus the structural guard: reindex re-syncs the saved page into search via `syncPage` (the engine
 * agrees with the saved bytes) and never calls back into [WritePipeline] (no such edge exists).
 */
class WritePipelineLockOrderingTest : FunSpec({

    test("a watcher rebuild racing a save cannot deadlock, and the save re-syncs search") {
        val dir = Files.createTempDirectory("pb-write-lock")
        val searchDir = Files.createTempDirectory("pb-write-lock-search")
        try {
            writePage(dir, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n\nstaleterm only here.\n")

            SearchDb(searchDir.resolve("search.db")).use { searchDb ->
                val provider = Fts5SearchProvider(searchDb)
                val indexer = SearchIndexer(provider, SectionSplitter())
                val store = LocalContentStore(dir)

                val secondScanEntered = CountDownLatch(1)
                val releaseSecondScan = CountDownLatch(1)
                var scans = 0
                val gating = object : ContentStore by store {
                    override fun scan(): ScanResult {
                        if (scans++ == 1) {
                            secondScanEntered.countDown()
                            releaseSecondScan.await(10, TimeUnit.SECONDS)
                        }
                        return store.scan()
                    }
                }

                IndexHarness(
                    dir,
                    contentStore = gating,
                    listeners = listOf(IndexBuilder.PublicationListener(indexer::sync)),
                    searchIndexer = indexer,
                ).use { harness ->
                    val builder = harness.builder
                    builder.rebuild() // initial publish + sync
                    val page = builder.current.byPath.getValue(com.plainbase.domain.content.TreePath.require("doc.md"))
                    val pipeline = harness.writePipeline()
                    val saveBytes = "---\ntitle: Doc\n---\n\n# Doc\n\nfreshterm only now.\n".toByteArray()

                    // Park the watcher rebuild inside its scan, holding nothing yet.
                    val watcher = thread(name = "watcher-rebuild") { builder.rebuild() }
                    secondScanEntered.await(10, TimeUnit.SECONDS) shouldBe true

                    // Fire the save; its reindex must BLOCK at the @Synchronized IndexBuilder monitor.
                    var outcome: WriteOutcome? = null
                    val saver = thread(name = "save") {
                        outcome = pipeline.write(WriteIntent(page.id, page.path, page.contentHash, saveBytes))
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (saver.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
                    saver.state shouldBe Thread.State.BLOCKED

                    releaseSecondScan.countDown()
                    watcher.join(10_000)
                    saver.join(10_000)

                    // Both finished — no deadlock. The save's reindex re-synced search to the saved bytes.
                    (outcome is WriteOutcome.Written) shouldBe true
                    provider.search(SearchQuery(text = "freshterm", limit = 20, offset = 0)).total shouldBe 1L
                }
            }
        } finally {
            listOf(dir, searchDir).forEach { it.toFile().deleteRecursively() }
        }
    }
})

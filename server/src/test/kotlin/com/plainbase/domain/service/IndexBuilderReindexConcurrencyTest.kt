package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
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
 * S8 acceptance criterion 13 — the reindex atomicity regression guard (mirrors
 * [IndexBuilderConcurrencyTest]'s serialization proof). `rebuildSearchIndex()` is `@Synchronized`
 * and reads `holder.load()` + rebuilds the engine inside that lock, so a concurrent watcher
 * rebuild either fully precedes it (the reindex sees the watcher's snapshot) or fully follows it
 * (the watcher's own sync runs after). It can NEVER roll the engine back to a stale generation —
 * the defect a naive read-`current`-then-`rebuild` would reopen (a reindex from snapshot N landing
 * its `rebuild(N)` AFTER a watcher synced N+1, leaving search stale indefinitely).
 *
 * The proof is deterministic by construction: a watcher rebuild is observed BLOCKED at the same
 * monitor while a reindex holds it, then released. Whichever ran first, after both finish the
 * engine MUST agree with the final published snapshot — never the older one.
 */
class IndexBuilderReindexConcurrencyTest : FunSpec({

    test("a reindex cannot regress the engine behind a concurrent watcher rebuild's newer snapshot") {
        val dir = Files.createTempDirectory("pb-reindex-atomic")
        val searchDir = Files.createTempDirectory("pb-reindex-atomic-search")
        try {
            writePage(dir, "old.md", "---\ntitle: Old\n---\n\n# Old\n\nstaleterm only here.\n")

            SearchDb(searchDir.resolve("search.db")).use { searchDb ->
                val provider = Fts5SearchProvider(searchDb)
                val indexer = SearchIndexer(provider, SectionSplitter())
                val store = LocalContentStore(dir)

                // A scan gate: the SECOND scan (the watcher rebuild) parks on a latch so the reindex
                // can be observed blocked at the shared monitor while that rebuild is mid-flight.
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
                    builder.rebuild() // initial publish + sync: engine indexes "staleterm"

                    // The content changes on disk (the watcher's world): "freshterm" replaces "staleterm".
                    writePage(dir, "old.md", "---\ntitle: Old\n---\n\n# Old\n\nfreshterm only now.\n")

                    // Start the watcher rebuild; it parks inside its scan, holding nothing yet.
                    val watcher = thread(name = "watcher-rebuild") { builder.rebuild() }
                    secondScanEntered.await(10, TimeUnit.SECONDS) shouldBe true

                    // Fire the reindex; it must BLOCK at the @Synchronized monitor the watcher will
                    // take to publish — proving snapshot-read + engine-rebuild can't interleave.
                    val reindex = thread(name = "reindex") { builder.rebuildSearchIndex() }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (reindex.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
                    reindex.state shouldBe Thread.State.BLOCKED

                    releaseSecondScan.countDown()
                    watcher.join(10_000)
                    reindex.join(10_000)

                    // The engine agrees with the final published snapshot — fresh, never rolled back.
                    provider.search(SearchQuery(text = "freshterm", limit = 20, offset = 0)).total shouldBe 1L
                    provider.search(SearchQuery(text = "staleterm", limit = 20, offset = 0)).total shouldBe 0L
                }
            }
        } finally {
            listOf(dir, searchDir).forEach { it.toFile().deleteRecursively() }
        }
    }
})

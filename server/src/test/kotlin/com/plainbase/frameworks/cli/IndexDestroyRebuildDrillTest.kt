package com.plainbase.frameworks.cli

import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.pageContent
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.search.ReindexEquivalence
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * C3 disaster drill 1 — the §9 invariant-(a) destroy-and-recover through the OPERATOR entry point.
 * [SearchEquivalenceTest] proves the ENGINE recovers; this proves the documented stop → delete →
 * `plainbase reindex` runbook recovers: CLI entry, DATA_DIR lock acquisition, a file-backed app DB
 * whose id_map/checkpoint rows survive the nuke. Because `plainbase.db` survives, every page id is
 * identical across the destroy, so the deterministic tiebreak (score DESC, page_id, heading_id)
 * makes the STRICT exact-ordered comparator the right one here — contrast [LostDataDirRecoveryTest],
 * where fresh ids force a tie-tolerant projection.
 */
class IndexDestroyRebuildDrillTest : FunSpec({

    val querySet = listOf("rolling", "deploy", "rollback", "treasury", "twin", "deplo", "xyzzy-no-such-term")

    fun capture(provider: SearchProvider): List<ReindexEquivalence.QueryAnswer> = querySet.map { text ->
        val results = provider.search(SearchQuery(text = text, limit = 50, offset = 0))
        ReindexEquivalence.QueryAnswer(query = text, total = results.total, hits = results.hits)
    }

    test("destroy search.db, recover via `plainbase reindex`: hashes, ordered answers, and the summary count all match") {
        withDrillTree { config ->
            // Build the pre-disaster install: both DBs file-backed, populated through the real CLI graph.
            captureStdout { ReindexCommand.run(emptyList(), config) shouldBe 0 }
            val before: List<ReindexEquivalence.QueryAnswer>
            val beforeState: Map<RootedPageId, PageSearchState>
            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                before = capture(provider)
                beforeState = provider.indexedState()
            }
            beforeState.size shouldBe PAGE_COUNT
            // Guards a silent-empty-search regression: without this, an engine that indexes but answers
            // nothing would make before/after trivially equal as empty lists.
            withClue("baseline queries must return hits") { before.any { it.total > 0L } shouldBe true }

            // Index-only loss, every handle closed first: plainbase.db stays — that boundary IS this drill's claim.
            deleteIndexFiles(config.dataDir)
            Files.notExists(config.searchDatabasePath) shouldBe true
            Files.exists(config.appDatabasePath) shouldBe true

            val out = captureStdout { ReindexCommand.run(emptyList(), config) shouldBe 0 }
            out.lineSequence().toList() shouldContain
                "reindex: rebuilt the search index for $PAGE_COUNT page(s) under ${config.contentDir}"

            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.indexedState() shouldBe beforeState
                // Ids survive the search.db-only nuke, so the shared contract comparator (exact ordered
                // sequence via the deterministic score/page_id/heading_id tiebreak) is the right one here.
                ReindexEquivalence.exactOrderedSequence.compare(before, capture(provider))
            }
        }
    }
})

private const val PAGE_COUNT = 8

/** The equivalence tree (varied fields + the tie cluster) padded to [PAGE_COUNT] pages, plus a fresh DATA_DIR; both cleaned up. */
private fun withDrillTree(block: (PlainbaseConfig) -> Unit) {
    val content = Files.createTempDirectory("pb-destroy-drill-content")
    val data = Files.createTempDirectory("pb-destroy-drill-data")
    try {
        writePage(
            content,
            "deploy-guide.md",
            "---\ntitle: Deploy Guide\ntags: ops\nowner: platform\n---\n\n" +
                "# Deploy Guide\n\nRolling deploy checklist.\n\n" +
                "## Rollback\n\nRollback restores the previous release.\n",
        )
        writePage(
            content,
            "report.md",
            "---\ntitle: Quarterly Report\ntags: finance\nowner: treasury\n---\n\n# Quarterly Report\n\nNumbers for the quarter.\n",
        )
        writePage(content, "clone-a.md", "---\ntitle: Clone\n---\n\n# Clone\n\ntwin payload.\n")
        writePage(content, "clone-b.md", "---\ntitle: Clone\n---\n\n# Clone\n\ntwin payload.\n")
        writePage(content, "clone-c.md", "---\ntitle: Clone\n---\n\n# Clone\n\ntwin payload.\n")
        (0..2).forEach { n -> writePage(content, "page-%03d.md".format(n), pageContent(n)) }
        block(PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0))
    } finally {
        listOf(content, data).forEach { it.toFile().deleteRecursively() }
    }
}

private fun deleteIndexFiles(dataDir: Path) {
    listOf("search.db", "search.db-wal", "search.db-shm").forEach { Files.deleteIfExists(dataDir.resolve(it)) }
}

private fun captureStdout(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val previous = System.out
    System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setOut(previous)
    }
    return buffer.toString(Charsets.UTF_8)
}

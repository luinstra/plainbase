package com.plainbase.frameworks.search

import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.writePage
import com.plainbase.search.ReindexEquivalence
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * §Verification criterion 4 (embedded) — derived-state rebuildability over the file-backed FTS5
 * engine, both variants, exercising the REAL index pass + search stack (not synthetic
 * PageDocuments). The exact-ordered-sequence comparator is the S3 one ([ReindexEquivalence]); the
 * fixed query set is defined here against this tree (the §A6 self-validating ordered-sequence pin).
 *
 *  - **Variant A (delete + reindex):** capture the fixed query set, CLOSE the SearchDb, DELETE the
 *    `search.db` file, reopen a fresh one, `provider.rebuild(...)` from the published snapshot (the
 *    reindex path) → sequences must be equivalent.
 *  - **Variant B (self-healing sync alone, NO reindex):** same delete+reopen, but trigger
 *    `SearchIndexer.sync(snapshot)` against the now-empty engine (empty state ⇒ full upsert,
 *    §B4 self-healing) → sequences must be equivalent — engine-truth diffing reaches the same
 *    state without an explicit reindex.
 */
class SearchEquivalenceTest : FunSpec({

    val querySet = listOf("rolling", "deploy", "rollback", "treasury", "twin", "deplo", "xyzzy-no-such-term")

    fun capture(provider: SearchProvider): List<ReindexEquivalence.QueryAnswer> = querySet.map { text ->
        val results = provider.search(SearchQuery(text = text, limit = 50, offset = 0))
        ReindexEquivalence.QueryAnswer(query = text, total = results.total, hits = results.hits)
    }

    fun equivalent(before: List<ReindexEquivalence.QueryAnswer>, after: List<ReindexEquivalence.QueryAnswer>) {
        after.zip(before).forEach { (got, expected) -> withClue(expected.query) { got shouldBe expected } }
        after.size shouldBe before.size
    }

    test("criterion 4 variant A: delete search.db + reindex yields the same fixed-query-set sequences") {
        withEquivalenceTree { content, dir ->
            val dbPath = dir.resolve("search.db")
            val before: List<ReindexEquivalence.QueryAnswer>
            val snapshot: com.plainbase.domain.page.PageIndex

            SearchDb(dbPath).use { db ->
                val provider = Fts5SearchProvider(db)
                val indexer = SearchIndexer(provider, SectionSplitter())
                IndexHarness(content, listeners = listOf(listenerOf(indexer)), searchIndexer = indexer).use { harness ->
                    snapshot = harness.builder.rebuild()
                    before = capture(provider)
                }
            }

            deleteIndexFiles(dir)

            SearchDb(dbPath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.rebuild(snapshot.pages.asSequence().map(SectionSplitter()::split)) // the reindex path
                equivalent(before, capture(provider))
            }
        }
    }

    test("criterion 4 variant B: delete search.db + self-healing sync alone yields the same sequences") {
        withEquivalenceTree { content, dir ->
            val dbPath = dir.resolve("search.db")
            val before: List<ReindexEquivalence.QueryAnswer>
            val snapshot: com.plainbase.domain.page.PageIndex

            SearchDb(dbPath).use { db ->
                val provider = Fts5SearchProvider(db)
                val indexer = SearchIndexer(provider, SectionSplitter())
                IndexHarness(content, listeners = listOf(listenerOf(indexer)), searchIndexer = indexer).use { harness ->
                    snapshot = harness.builder.rebuild()
                    before = capture(provider)
                }
            }

            deleteIndexFiles(dir)

            SearchDb(dbPath).use { db ->
                val provider = Fts5SearchProvider(db)
                // No reindex — only the engine-truth diff sync against the empty (deleted) engine.
                SearchIndexer(provider, SectionSplitter()).sync(snapshot)
                equivalent(before, capture(provider))
            }
        }
    }
})

private fun listenerOf(indexer: SearchIndexer) =
    com.plainbase.domain.service.IndexBuilder.PublicationListener(indexer::sync)

private fun deleteIndexFiles(dir: Path) {
    listOf("search.db", "search.db-wal", "search.db-shm").forEach { Files.deleteIfExists(dir.resolve(it)) }
}

/** A small content tree (varied fields + a tie cluster) plus a temp search dir; both cleaned up. */
private fun withEquivalenceTree(block: (content: Path, searchDir: Path) -> Unit) {
    val content = Files.createTempDirectory("pb-equivalence-content")
    val searchDir = Files.createTempDirectory("pb-equivalence-search")
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
        block(content, searchDir)
    } finally {
        listOf(content, searchDir).forEach { it.toFile().deleteRecursively() }
    }
}

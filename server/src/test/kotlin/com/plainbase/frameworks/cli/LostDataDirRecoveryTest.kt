package com.plainbase.frameworks.cli

import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.search.Highlight
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import com.plainbase.frameworks.sqldelight.queryLong
import com.plainbase.search.ReindexEquivalence
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/**
 * C3 disaster drill 2 — the ENTIRE `DATA_DIR` gone, `CONTENT_DIR` intact. [BootStack] mirrors the
 * serve-boot recovery seams (Application.kt: lock → app DB → search engine → rebuild → reconcile),
 * so the drill asserts recovery SEMANTICS, not just a clean start: the lock re-creates the
 * directory, `plainbase.db` is created and migrated, a MATERIALIZED page (frontmatter `id:`)
 * keeps its exact id, an UNMATERIALIZED page mints a fresh one (the §5.2 trade-off, pinned
 * honestly), and search repopulates at boot via the production sync listener.
 *
 * The search comparison is deliberately TIE-TOLERANT: per-query total plus score tiers compared IN
 * order, each tier an unordered set of id-free hit facets (path/heading/snippet/highlights). It
 * asserts cross-tier ranking and section identity while tolerating ONLY the intra-tie reorder the
 * tie cluster's freshly minted ids force — that reorder is the drill PROVING the trade-off, not a
 * regression.
 */
class LostDataDirRecoveryTest : FunSpec({

    val materializedId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val querySet = listOf("rolling", "deploy", "rollback", "treasury", "twin", "deplo", "xyzzy-no-such-term")

    fun capture(provider: SearchProvider): List<ReindexEquivalence.QueryAnswer> = querySet.map { text ->
        val results = provider.search(SearchQuery(text = text, limit = 50, offset = 0))
        ReindexEquivalence.QueryAnswer(query = text, total = results.total, hits = results.hits)
    }

    // Across a DATA_DIR loss ONLY pageId is fresh: headingId is a content slug and snippet/highlights/
    // score are content-derived, so all are boot-stable (bm25 takes no page-id input). The engine
    // tiebreaks equal scores by page_id, so the one legitimate difference is the intra-tie order of
    // freshly minted ids. Each tier is keyed by its (asserted) score and holds the unordered SET of
    // id-free hit facets — so cross-tier ranking, absolute score, and section identity are all asserted,
    // and ONLY the tie-local id shuffle is tolerated. Hit ids are excluded on purpose: id-bearing
    // equality would contradict the fresh-id claim this drill proves.
    fun projected(answers: List<ReindexEquivalence.QueryAnswer>, snapshot: PageIndex) = answers.associate { answer ->
        answer.query to (
            answer.total to
                answer.hits.groupBy { it.score }.entries.sortedByDescending { it.key }.map { (score, tier) ->
                    score to tier.map { hit ->
                        val page = snapshot.byId.getValue(hit.pageId)
                        HitFacet(page.path.value, hit.headingId, hit.snippet, hit.highlights)
                    }.toSet()
                }
            )
    }

    test("a lost DATA_DIR boots clean: fresh migrated DB, materialized id survives, fresh id minted, search answering") {
        withLostDataDirTree(materializedId) { config ->
            val snapshotA: PageIndex
            val answersA: List<ReindexEquivalence.QueryAnswer>
            BootStack(config).use { boot ->
                snapshotA = boot.builder.rebuild()
                boot.pipeline.reconcileDirtyPages()
                answersA = capture(boot.provider)
            }
            val idsA = snapshotA.pages.associate { it.path.value to it.id }
            idsA.getValue("deploy-guide.md") shouldBe materializedId
            // Guards a silent-empty-search regression: the boot-A/boot-B answer sets must not both be empty.
            withClue("baseline queries must return hits") { answersA.any { it.total > 0L } shouldBe true }
            // Locks the cross-tier teeth: `rollback` must actually span >1 score tier (deploy-guide's
            // Rollback section outranks report's mention), else the tier-ordering assertion is vacuous.
            val rollbackTiers = answersA.first { it.query == "rollback" }.hits.map { it.score }.distinct().size
            withClue("`rollback` must span >1 score tier to exercise cross-tier ranking") { (rollbackTiers > 1) shouldBe true }

            config.dataDir.toFile().deleteRecursively()
            Files.notExists(config.dataDir) shouldBe true

            BootStack(config).use { boot ->
                // The lock seam re-created the directory; the driver created + migrated a fresh app DB.
                Files.isDirectory(config.dataDir) shouldBe true
                Files.exists(config.appDatabasePath) shouldBe true
                boot.driver.queryLong("PRAGMA user_version;") shouldBe PlainbaseDb.Schema.version

                val snapshotB = boot.builder.rebuild()
                boot.pipeline.reconcileDirtyPages()
                val idsB = snapshotB.pages.associate { it.path.value to it.id }

                // Identity boundary: the frontmatter id re-adopts; every unmaterialized page mints fresh (§5.2).
                idsB.getValue("deploy-guide.md") shouldBe materializedId
                idsB.getValue("report.md") shouldNotBe idsA.getValue("report.md")
                idsB.getValue("clone-a.md") shouldNotBe idsA.getValue("clone-a.md")

                projected(capture(boot.provider), snapshotB) shouldBe projected(answersA, snapshotA)

                // Search fully populated as a side effect of the boot rebuild — no manual reindex step.
                boot.provider.indexedState().keys shouldBe snapshotB.pages.map { it.id }.toSet()
            }
        }
    }
})

/** An id-free projection of a search hit — everything a DATA_DIR loss preserves; only [com.plainbase.domain.search.SearchHit.pageId] is dropped. */
private data class HitFacet(val path: String, val headingId: String?, val snippet: String, val highlights: List<Highlight>)

/**
 * One serve-boot lifetime over the recovery-relevant seams, in Application.kt's order: DATA_DIR
 * lock (the seam that creates a missing dir) → file-backed app DB (create-or-migrate) → search
 * engine → an [IndexBuilder] in the SERVE shape — BOTH production listeners (checkpoint replace and
 * searchSync), so `rebuild()` repopulates search exactly as serve does. Deliberately NOT
 * ReindexCommand's offline wiring (checkpoint-only listeners + explicit engine rebuild).
 *
 * This replays the recovery SEMANTICS of those seams: it wires the two listeners directly rather
 * than resolving them from Koin, so it does not pin that `Application.kt`/`SearchModule` still
 * registers searchSync — the live serve graph is exercised by the CI fresh-DATA_DIR boot. The two
 * listeners are independent side effects over the same immutable snapshot, so their order is
 * immaterial to every assertion here.
 */
private class BootStack(config: PlainbaseConfig) : AutoCloseable {

    private val lock = requireNotNull(DataDirLock.tryAcquire(config.dataDir)) { "DATA_DIR lock refused: ${config.dataDir}" }
    val driver = DatabaseFactory.createDriver(config.appDatabasePath)
    private val searchDb = SearchDb(config.searchDatabasePath)
    val provider = Fts5SearchProvider(searchDb)

    private val database = DatabaseFactory.createDatabase(driver)
    private val store = LocalContentStore(root = config.contentDir, ignoreRules = IgnoreRules())
    private val idMap = SqlDelightIdMapRepository(database)
    private val registry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
    private val checkpoint = SqlDelightPageCheckpointRepository(database)
    private val dirtyPages = SqlDelightDirtyPageRepository(database)
    private val citations = CitationFactory()
    private val frontmatter = FrontmatterReader()
    private val searchIndexer = SearchIndexer(provider, SectionSplitter())

    val builder = IndexBuilder(
        contentStore = store,
        frontmatterParser = frontmatter,
        rendererFactory = { view -> FlexmarkRenderer(view) },
        identity = PageIdentityService(UuidV7IdProvider()),
        patcher = FrontmatterPatcher(),
        idMap = idMap,
        aliasRegistry = registry,
        checkpoint = checkpoint,
        citations = citations,
        history = NoOpHistoryProvider, // git state lives under CONTENT_DIR, untouched by the loss
        listeners = listOf(
            IndexBuilder.PublicationListener(checkpoint::replaceFrom),
            IndexBuilder.PublicationListener(searchIndexer::sync),
        ),
        searchIndexer = searchIndexer,
    )

    val pipeline = WritePipeline(
        contentStore = store,
        indexBuilder = builder,
        citations = citations,
        frontmatterParser = frontmatter,
        dirtyPages = dirtyPages,
        idMap = idMap,
        aliasRegistry = registry,
        historyHook = WriteHistoryHook { _, _, _, _ -> null },
    )

    override fun close() {
        searchDb.close()
        driver.close()
        lock.close()
    }
}

/** The equivalence tree with `deploy-guide.md` MATERIALIZED (literal `id:`) and the rest id-less; both dirs cleaned up. */
private fun withLostDataDirTree(materializedId: PageId, block: (PlainbaseConfig) -> Unit) {
    val content = Files.createTempDirectory("pb-lost-datadir-content")
    val data = Files.createTempDirectory("pb-lost-datadir-data")
    try {
        writePage(
            content,
            "deploy-guide.md",
            "---\nid: ${materializedId.value}\ntitle: Deploy Guide\ntags: ops\nowner: platform\n---\n\n" +
                "# Deploy Guide\n\nRolling deploy checklist.\n\n" +
                "## Rollback\n\nRollback restores the previous release.\n",
        )
        writePage(
            content,
            "report.md",
            "---\ntitle: Quarterly Report\ntags: finance\nowner: treasury\n---\n\n# Quarterly Report\n\n" +
                // A single `rollback` mention here vs. the deploy guide's heading + body makes the shared
                // `rollback` query hit two DISTINCT pages at DIFFERENT scores — giving the score-tier
                // comparator real cross-tier-ranking teeth (a reverse-ranked engine would fail).
                "Numbers for the quarter. Rollback procedures are reviewed each quarter.\n",
        )
        writePage(content, "clone-a.md", "---\ntitle: Clone\n---\n\n# Clone\n\ntwin payload.\n")
        writePage(content, "clone-b.md", "---\ntitle: Clone\n---\n\n# Clone\n\ntwin payload.\n")
        writePage(content, "clone-c.md", "---\ntitle: Clone\n---\n\n# Clone\n\ntwin payload.\n")
        block(PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0))
    } finally {
        listOf(content, data).forEach { it.toFile().deleteRecursively() }
    }
}

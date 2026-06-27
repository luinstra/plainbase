package com.plainbase.frameworks.ktor

import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path

/**
 * The W3a (B2) write-test harness: like [RestHarness], but it COPIES the fixture tree into a fresh
 * temp dir per test before wiring the [LocalContentStore], so a `PUT` (which mutates content on
 * disk) NEVER dirties the committed `Fixtures.demoDocs`. Read-only tests keep using [RestHarness];
 * every W3a write/golden test uses this.
 *
 * [historyHook] is injectable so the WrittenButUnindexed test can wire a throwing post-write hook
 * (the same seam [IndexHarness.writePipeline] exposes).
 */
class WriteRestHarness(
    fixtureRoot: Path,
    seed: (IdMapRepository) -> Unit = {},
    historyHook: WriteHistoryHook? = null,
    idProvider: IdProvider = UuidV7IdProvider(),
    private val storeOverride: ((LocalContentStore) -> com.plainbase.domain.content.ContentStore)? = null,
    historyFactory: (Path) -> HistoryProvider = { NoOpHistoryProvider },
) : AutoCloseable {

    /** A private, mutable copy of the fixture tree — deleted on [close]; the committed tree is never touched. */
    val root: Path = Files.createTempDirectory("plainbase-write-root")

    /**
     * The history provider over THIS harness's own root (F4 Git-mode write tests). The hook used by the
     * pipeline defaults to this provider's `commit(...).sha` — so a real Git provider drives BOTH the
     * commit a save records AND the read-side citations. An explicit [historyHook] override wins (the
     * recording/throwing seams the WrittenButUnindexed tests need).
     */
    val history: HistoryProvider = historyFactory(root)
    private val store = LocalContentStore(root)
    private val pipelineStore = storeOverride?.invoke(store) ?: store
    private val searchDir = Files.createTempDirectory("plainbase-write-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))
    val searchProvider = Fts5SearchProvider(searchDb)
    private val searchIndexer = SearchIndexer(searchProvider, SectionSplitter())

    // The index builder runs over the SAME (possibly wrapped) store the route + pipeline see, so a
    // storeOverride can model BOTH a failing write (writeAssetExclusive Unreadable) AND a failing post-
    // write rebuild (a scan that throws). Existing overrides delegate scan/read to the real copy via
    // `by real`, so the snapshot stays genuine; with no override, pipelineStore === store.
    private val harness = IndexHarness(
        root,
        contentStore = pipelineStore,
        history = history,
        listeners = listOf(IndexBuilder.PublicationListener(searchIndexer::sync)),
        searchIndexer = searchIndexer,
    )

    val idMap: IdMapRepository get() = harness.idMap
    val builder get() = harness.builder
    val registry get() = harness.registry
    val dirtyPages get() = harness.dirtyPages

    val services: RouteContext

    init {
        copyTree(fixtureRoot, root)
        seed(harness.idMap)
        // Ready the history store before the first rebuild (the production startup order): a Git provider
        // git-inits its content-root repo here, so rebuild's `lastCommits` reads a real (if unborn) repo
        // instead of failing exit-128. NoOp: a no-op.
        history.prepare()
        harness.builder.rebuild()
        // The pipeline, the route-facing contentStore, AND the index builder all run over the same
        // (possibly wrapped) store. Override wrappers delegate scan/read to the real copy via `by real`,
        // so the snapshot stays genuine; with no override, pipelineStore === store (a no-op). With no
        // explicit hook, the pipeline commits through `history` (NoOp → null; a Git provider → the SHA).
        val hook = historyHook ?: WriteHistoryHook { path, bytes, author, committer -> history.commit(path, bytes, author, committer)?.sha }
        val pipeline = harness.writePipeline(hook, store = pipelineStore)
        // A3: auth ON, loopback-dev (OFF) open behavior — the write/golden suites run byte-identically to pre-auth.
        services = harness.testRouteContext(
            contentStore = pipelineStore,
            writePipeline = pipeline,
            searchProvider = searchProvider,
            history = history,
            idProvider = idProvider,
        )
    }

    /** Reads the current on-disk bytes at a fixture-relative [relativePath] (byte-fidelity assertions). */
    fun diskBytes(relativePath: String): ByteArray = Files.readAllBytes(root.resolve(relativePath))

    override fun close() {
        harness.close()
        searchDb.close()
        searchDir.toFile().deleteRecursively()
        root.toFile().deleteRecursively()
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target)
                }
            }
        }
    }
}

/**
 * Runs [block] inside a `testApplication` serving [plainbaseModule] over a [WriteRestHarness] built
 * from a temp COPY of [fixtureRoot]. Every W3a write/golden test uses this so it is idempotent and
 * never dirties the committed fixture tree.
 */
fun writeRestTest(
    fixtureRoot: Path,
    seed: (IdMapRepository) -> Unit = {},
    historyHook: WriteHistoryHook? = null,
    idProvider: IdProvider = UuidV7IdProvider(),
    storeOverride: ((LocalContentStore) -> com.plainbase.domain.content.ContentStore)? = null,
    historyFactory: (Path) -> HistoryProvider = { NoOpHistoryProvider },
    block: suspend ApplicationTestBuilder.(WriteRestHarness) -> Unit,
) {
    WriteRestHarness(fixtureRoot, seed, historyHook, idProvider, storeOverride, historyFactory).use { harness ->
        testApplication {
            application { plainbaseModule(harness.services) }
            block(harness)
        }
    }
}

/** A non-redirect-following client (parallels [restClient]) for write tests that need raw responses. */
fun ApplicationTestBuilder.writeClient(): HttpClient = createClient { followRedirects = false }

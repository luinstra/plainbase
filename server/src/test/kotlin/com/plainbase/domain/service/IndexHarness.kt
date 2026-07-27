package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.SessionTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightProposalRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSetupTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightTransactionRunner
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUserRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * The chunk-5 integration harness: a real [IndexBuilder] over a real tree ([LocalContentStore]),
 * real rendering ([FlexmarkRenderer]), and real persistence (in-memory SQLite repos) — the same
 * wiring `indexModule` produces, minus HTTP. One harness = one DATA_DIR lifetime, so successive
 * [IndexBuilder.rebuild]s exercise rescan semantics (move aliases, issue accumulation) for real.
 *
 * [contentStore], [frontmatterParser], and [rendererFactory] are injectable so the one-pass
 * counting test can wrap them.
 */
class IndexHarness(
    root: Path,
    private val contentStore: ContentStore = LocalContentStore(root),
    frontmatterParser: FrontmatterParser = FrontmatterReader(),
    rendererFactory: (PageIndexView) -> MarkdownRenderer = { view -> FlexmarkRenderer(view) },
    history: HistoryProvider = NoOpHistoryProvider,
    listeners: List<IndexBuilder.PublicationListener> = emptyList(),
    searchIndexer: SearchIndexer? = null,
    // C2 multi-root knobs (the defaults keep every single-root test on the main-only shape): the
    // registry seats every configured root in D7 order - the rank source and the D16 registeredRoots
    // both derive from it - and [sources] is the subset this builder actually scans.
    val rootRegistry: RootRegistry = RootRegistry.of(listOf(localRoot("main", root))),
    sources: List<IndexBuilder.Source>? = null,
    /** C4: the availability holder the builder probes/marks through. Empty (every root serving) by default. */
    val availability: RootAvailability = RootAvailability(Clock.System),
) : AutoCloseable {

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    private val citations = CitationFactory()

    val idMap = SqlDelightIdMapRepository(database)
    val aliases = SqlDelightUrlAliasRepository(database)
    val registry = UrlAliasRegistry(aliases)
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    val dirtyPages = SqlDelightDirtyPageRepository(database)

    /**
     * The proof-apply transaction (C0) - the ONE deleter. Exposed because a test that wants to drive a PROVEN
     * absence (the only kind the server may call a deletion) has to be able to mint the licence by hand: no
     * production code mints one until C2/C4, which is the safety floor working as designed.
     */
    val retirements = SqlDelightRetirementRepository(database)

    // A3 auth substrate over the SAME in-memory DB (the schema includes subject_role/audit_log via 5.sqm). The
    // route-test harnesses build a PolicyService over these + seed a role; ApiTokenService mints test bearers.
    val roleRepository = SqlDelightRoleRepository(database)
    val auditRepository = SqlDelightAuditRepository(database)
    val apiTokenRepository = SqlDelightApiTokenRepository(database)
    val proposalRepository = SqlDelightProposalRepository(database)
    val apiTokens = ApiTokenService(minter = ApiTokenMinter(), hasher = TokenHasher(), tokens = apiTokenRepository, clock = Clock.System)

    // A4a human-auth substrate over the SAME in-memory DB (the v7 schema includes users/sessions/setup_tokens).
    val userRepository = SqlDelightUserRepository(database)
    val sessionRepository = SqlDelightSessionRepository(database)
    val setupTokenRepository = SqlDelightSetupTokenRepository(database)
    val transactionRunner = SqlDelightTransactionRunner(database)
    val sessionService = SessionService(
        minter = SessionTokenMinter(TokenHasher()),
        hasher = TokenHasher(),
        sessions = sessionRepository,
        clock = Clock.System,
    )
    private val frontmatter = frontmatterParser
    private val patcher = FrontmatterPatcher()

    /** The builder's sources, kept so [writePipeline] can resolve a store per root without a second wiring. */
    private val sourceList: List<IndexBuilder.Source> =
        sources ?: listOf(IndexBuilder.Source(rootRegistry.primary, contentStore, history))

    /** The per-root store lookup the C4 write path takes — over the SAME sources the builder scans. */
    val stores: (RootName) -> ContentStore = { name ->
        requireNotNull(sourceList.firstOrNull { it.root.name == name }?.store) { "no store for root '$name' in this harness" }
    }

    /** The ONE id→root / root→status resolver, shared by every facade the harness wires (as production does). */
    val resolver = PageRootResolver(idMap, rootRegistry)

    /** The ONE 404-vs-503 absence rule (C1), over the SAME durable index the builder binds into. */
    val absence = AbsenceClassifier(idMap)

    /** The DERIVED limbo set the builder republishes each pass and `/healthz` reports (C1). */
    val limbo = RootLimbo()

    /** Watch coverage, as `serve()` wires it: the watchers write it, `/healthz` and the epoch read it. */
    val convergence = RootConvergence()

    /**
     * The observation epochs (C2), over the SAME retirement repository the builder applies proofs through.
     *
     * A root starts UNOBSERVED and earns nothing, exactly as in production - so a test that wants the epoch has to
     * declare the root watched ([observe]), which is the honest precondition: without a watcher there is no
     * observation, and two scans with an `rm` between them prove nothing at all.
     */
    val epochs = ObservationEpoch(retirements, convergence)

    /** Declares [root] under continuous observation - what `serve()` does when it installs the root's watcher. */
    fun observe(root: String = "main"): IndexHarness = apply { epochs.observing(RootName.require(root)) }

    val builder = IndexBuilder(
        sources = sourceList,
        availability = availability,
        limbo = limbo,
        // Wired, where C0 left it defaulted to NoRetirements: the harness minted a real repository and then handed the
        // builder one that could not cash a proof, so no test could have observed a reap even once a source minted one.
        retirements = retirements,
        epochs = epochs,
        frontmatterParser = frontmatterParser,
        rendererFactory = rendererFactory,
        identity = PageIdentityService(UuidV7IdProvider()),
        patcher = patcher,
        idMap = idMap,
        aliasRegistry = registry,
        checkpoint = checkpoints,
        citations = citations,
        rootRank = rootRegistry::rank,
        registeredRoots = rootRegistry.roots.map { it.name }.toSet(),
        // The §B3 checkpoint-replace listener is part of the production graph (checkpointModule),
        // so the harness always registers it first — callers' listeners follow, as in `getAll()`.
        listeners = listOf(IndexBuilder.PublicationListener(checkpoints::replaceFrom)) + listeners,
        searchIndexer = searchIndexer,
    )

    /**
     * A real [WritePipeline] over [store] (defaulting to the harness's own content store) + repos —
     * the production wiring minus HTTP. The [store] override lets a test point the pipeline's CAS at
     * a failing/wrapping stand-in while the index/search wiring keeps using the real copy.
     */
    fun writePipeline(
        historyHook: WriteHistoryHook = WriteHistoryHook { _, _, _, _, _ -> null },
        store: ContentStore? = null,
    ): WritePipeline =
        WritePipeline(
            // A [store] override stands in for MAIN's tree (the failing/wrapping stand-in case); every other root
            // resolves through the harness's own sources, so a multi-root pipeline writes into the right disk.
            stores = { name -> if (store != null && name == rootRegistry.primary.name) store else stores(name) },
            indexBuilder = builder,
            citations = citations,
            frontmatterParser = frontmatter,
            dirtyPages = dirtyPages,
            idMap = idMap,
            aliasRegistry = registry,
            availability = availability,
            historyHook = historyHook,
        )

    override fun close() = driver.close()
}

/** A local test root: [name] over [path]. Histories ride the per-[IndexBuilder.Source] provider, so the mode is inert. */
fun localRoot(name: String, path: Path, editable: Boolean = true): Root =
    Root(RootName.require(name), RootBackend.Local(path), editable = editable, history = HistoryMode.OFF)

/** Runs [block] with a fresh temp content tree seeded by [seed]; always cleans up. */
fun <T> withTempTree(seed: (Path) -> Unit, block: (Path) -> T): T {
    val root = Files.createTempDirectory("plainbase-index-test")
    return try {
        seed(root)
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

/** Writes a page file (creating parents) under [root]. */
fun writePage(root: Path, relativePath: String, content: String) {
    val target = root.resolve(relativePath)
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
}

/**
 * The Phase-1 generated-corpus page (the 1,000-page scale tests): small but realistic —
 * frontmatter, two headings, one internal link to a sibling. Shared by the chunk-5 index-pass
 * scale test and the S2 search-corpus perf test (which reuses this generator by plan).
 */
fun pageContent(n: Int): String = buildString {
    appendLine("---")
    appendLine("title: Page %03d".format(n))
    appendLine("---")
    appendLine()
    appendLine("# Page %03d".format(n))
    appendLine()
    appendLine("Body text for page $n with a [sibling link](page-%03d.md).".format((n / 10) * 10))
    appendLine()
    appendLine("## Details")
    appendLine()
    appendLine("More text.")
}

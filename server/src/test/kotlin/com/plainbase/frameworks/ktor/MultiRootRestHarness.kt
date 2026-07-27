package com.plainbase.frameworks.ktor

import com.plainbase.RootGateVerdict
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.content.WatchCoverage
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.koin.HistoryProviders
import com.plainbase.frameworks.koin.RootStores
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.rootGateVerdicts
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * The N-root, possibly-degraded route fixture — a SERVE-SHAPED harness, which is the thing the existing ones
 * cannot be.
 *
 * [RestHarness] builds exactly ONE store over a directory that is always there and rebuilds in its init; bending
 * that into an N-root, maybe-missing-path, availability-seeded fixture would complicate the harness every existing
 * REST test uses in order to serve a handful of new ones. So this is its own class, sharing `testRouteContext`.
 *
 * What it can express that nothing else can:
 *  - a root whose PATH IS MISSING (construction is inert by design — the store's init only normalizes paths), so a
 *    boot-degraded server is reproducible without a process-level serve harness;
 *  - [seedBootAvailability], which mirrors `serve()`'s gate loop EXACTLY (probe each extra; mark MISSING_AT_BOOT),
 *    so the boot-arm rows exercise production's own seeding semantics rather than a hand-set flag;
 *  - [detachedRoot], which persists rows under a root name the registry does NOT know — the state a restart after
 *    an edited `roots {}` leaves behind, and which no existing harness can produce (they all derive their rows from
 *    roots they also register);
 *  - [idMapOnly], which binds a page id under a root the fixture registers but never scans — the boot arm, where
 *    the persisted binding is the ONLY thing that can tell a 503 from a 404.
 */
class MultiRootRestHarness(
    private val roots: List<Root>,
    /** The direct-commit globs the agent gate consults, each carrying the root its config key declared it under. */
    globs: List<CommitGlob> = emptyList(),
    enforced: Boolean = false,
    extract: (io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction)? = null,
    /** The per-root history providers (C4): history is per-root topology, so a test may give each root its own. */
    private val histories: ((RootName) -> HistoryProvider)? = null,
    /**
     * Wire REAL watchers (`serve()`'s own `store.watch { scheduler.schedule() }`), off by default because most rows
     * here drive the rebuild explicitly and a live watch thread would only add nondeterminism to them. It is ON for
     * the rows whose whole subject is what happens with NOBODY driving anything - an idle root that goes away.
     */
    private val liveWatchers: Boolean = false,
    /**
     * C4 window fixtures: build the resolver over an [AmbiguousIdMap] FAKE (posing the Ambiguous arm / a cross-root
     * MOVE the real adapter cannot make). Receives the [IndexHarness] AFTER the first rebuild, so the factory can read
     * the seeded page id off `index.builder.current`. Defaults to the real resolver.
     */
    private val resolverFactory: ((IndexHarness) -> PageRootResolver)? = null,
    /** The classifier twin of [resolverFactory] (FIX 1): inject the SAME FAKE so the 503 limbo path fires. */
    private val absenceFactory: ((IndexHarness) -> AbsenceClassifier)? = null,
) : AutoCloseable {

    val registry: RootRegistry = RootRegistry.of(roots)
    val availability = RootAvailability(Clock.System)

    /**
     * Availability's non-sticky twin, wired exactly as `serve()` wires it: the watchers write it, `/healthz` reads it -
     * and, since C2, the observation epoch READS it (a tree with an unwatched subtree in it cannot earn one). It is the
     * INDEX harness's holder, deliberately: a second instance here would let a test degrade coverage on the wire while
     * the epoch that has to honor it never heard.
     */
    val convergence: RootConvergence get() = index.convergence

    /** WHICH roots actually got a `watch()` — the harness-side assertion for the watcher-skip rule. */
    val watched = mutableListOf<RootName>()

    private val watchers = mutableListOf<AutoCloseable>()
    private var scheduler: RebuildScheduler? = null

    private val searchDir = Files.createTempDirectory("plainbase-multiroot-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))
    val searchProvider = Fts5SearchProvider(searchDb)
    private val searchIndexer = SearchIndexer(searchProvider, SectionSplitter())

    /**
     * One store per root — constructed for EVERY configured root, including one whose path is missing. That is the
     * production rule and it is load-bearing: construction is allowed and INERT; what availability suppresses is
     * OPERATION (a missing root is never scanned and never watched).
     */
    private val storesByRoot: Map<RootName, LocalContentStore> = roots.associate { root ->
        root.name to LocalContentStore(
            root = requireNotNull(root.localPath),
            rootName = root.name,
            onRootUnavailable = { availability.markUnavailable(root.name, UnavailableCause.VANISHED) },
            // The production pairing again (ContentModule): a tree SWAPPED at the root's path is a healthy root and a
            // NEW universe, so it keeps serving and its epoch dies. The lambda resolves [index] lazily - the probe
            // cannot fire before the store it belongs to has been handed to a builder.
            onIdentityRebind = { index.epochs.broke(root.name, BreakCause.IDENTITY_REBIND) },
        )
    }

    val index = IndexHarness(
        root = requireNotNull(registry.primary.localPath),
        rootRegistry = registry,
        availability = availability,
        sources = roots.map { IndexBuilder.Source(it, storesByRoot.getValue(it.name), NoOpHistoryProvider) },
        listeners = listOf(
            IndexBuilder.PublicationListener { snap, retired ->
                searchIndexer.sync(snap, retired)
            },
        ),
        searchIndexer = searchIndexer,
    )

    val builder get() = index.builder
    val idMap get() = index.idMap
    val checkpoints get() = index.checkpoints
    val dirtyPages get() = index.dirtyPages
    val proposals get() = index.proposalRepository
    val audit get() = index.auditRepository

    fun store(root: String): LocalContentStore = storesByRoot.getValue(RootName.require(root))

    lateinit var services: RouteContext
        private set

    private val globList = globs
    private val enforcedMode = enforced
    private val extractor = extract

    /**
     * Boots the fixture in serve()'s own ORDER: seed availability from the gate loop, register watchers only for the
     * roots that are actually there, THEN run the first rebuild. Order matters — a rebuild that ran before the
     * seeding would probe a missing root itself and mark it VANISHED rather than MISSING_AT_BOOT, which is a
     * different cause on the health wire and a different story for the operator.
     */
    fun boot(): MultiRootRestHarness {
        seedBootAvailability()
        registerWatchers()
        builder.rebuild()
        services = index.testRouteContext(
            searchProvider = searchProvider,
            historiesByRoot = histories,
            enforced = enforcedMode,
            agentDirectCommitGlobs = globList,
            extract = extractor,
            convergence = convergence,
            resolver = resolverFactory?.invoke(index) ?: PageRootResolver(index.idMap, registry),
            absence = absenceFactory?.invoke(index) ?: index.absence,
        )
        return this
    }

    /**
     * serve()'s gate loop — literally, now: [rootGateVerdicts] IS the loop `serve()` walks, so this harness cannot
     * drift from it. (It used to say "serve()'s gate loop, exactly" and then re-implement the probe half. A test-only
     * copy cannot brick production, but it can make a multi-root REST test pass while `serve` diverges.)
     *
     * Seeding only: the gate DECIDES, the caller ACTS. `markUnavailable` mutates a runtime singleton, which is why it
     * lives out here and not inside the gate — `plainbase root` calls the same gate and must not mutate anything.
     */
    fun seedBootAvailability() {
        val providers = HistoryProviders(roots.associate { it.name to (histories?.invoke(it.name) ?: NoOpHistoryProvider) })
        rootGateVerdicts(registry, RootStores(storesByRoot), providers)
            .filterIsInstance<RootGateVerdict.Unavailable>()
            .forEach { availability.markUnavailable(it.root, UnavailableCause.MISSING_AT_BOOT) }
    }

    /**
     * One watcher per AVAILABLE root; an unavailable one gets none (there is nothing to watch). With [liveWatchers]
     * these are the production article - real `store.watch()` over one debounced [RebuildScheduler], which is what
     * lets a test observe the watcher's OWN root-liveness detection with no write and no manual rebuild to prod it.
     */
    private fun registerWatchers() {
        val serving = availability.current()
        val servingRoots = roots.filter { serving.isAvailable(it.name) }
        servingRoots.forEach { watched += it.name }
        // Every serving root is under OBSERVATION, watch THREAD or not - which is what `serve()` declares at exactly
        // this point, and it is the precondition for earning an epoch (C2). The rows here drive their own rebuilds
        // rather than waiting on a live watch thread, but the fact being declared is the same one: this root is being
        // watched, so a page it stops showing us is a page that went away. A row that wants the BREAK half calls
        // `epochs.broke(...)` itself - which is precisely what a real watcher does.
        servingRoots.forEach { index.epochs.observing(it.name) }
        if (!liveWatchers) return
        val alarmed = RebuildScheduler(rebuild = { builder.rebuild() }, alarm = ExecutorAlarm())
        scheduler = alarmed
        servingRoots.forEach { root ->
            watchers += storesByRoot.getValue(root.name).watch(
                onChange = { alarmed.schedule() },
                // The production pairing (Application.kt): a watcher that DIED marks the root unavailable, while a
                // tree it cannot fully SEE only degrades convergence. Wiring only one of the two here would let a
                // test pass while `serve` crossed the wires.
                onCoverage = { coverage -> convergence.record(root.name, whole = coverage == WatchCoverage.WHOLE) },
                onBreak = { cause -> index.epochs.broke(root.name, cause) },
            )
        }
    }

    /**
     * Persists rows under a root name the registry does NOT know — a DETACHED root. This is exactly the state a
     * restart after an edited `roots {}` leaves behind: `plainbase.db` outlives the config, so a durable row can
     * name a root the runtime has never heard of, and every path that reads a root off a row has to survive it.
     */
    fun detachedRoot(name: String, path: String, id: PageId) {
        val root = RootName.require(name)
        require(registry.byName(root) == null) { "'$name' must NOT be in the registry - that is what makes it detached" }
        val rooted = RootedPath(root, TreePath.require(path))
        idMap.bind(rooted, id, materialized = false)
        checkpoints.replace(checkpoints.load() + (RootedPageId(root, id) to TreePath.require(path.removeSuffix(".md"))))
    }

    /**
     * Binds a page id under a REGISTERED root the fixture never scans (because it is unavailable) — the BOOT arm.
     * Its page is therefore in no snapshot section, so only this binding can tell "the disk is unmounted" (503) from
     * "this page never existed" (404). Without it the whole boot-arm row set is unwritable.
     */
    fun idMapOnly(root: String, path: String, id: PageId) {
        idMap.bind(RootedPath(RootName.require(root), TreePath.require(path)), id, materialized = false)
    }

    /**
     * Creates a TOMBSTONE for [id] under a DETACHED root (C4, the CLASS-A line-280 case): bind [id] at
     * ([name], [path]), then bind a DIFFERENT id at the SAME (root, path) - the displacement retires [id]. The root is
     * not in the registry, so the resolver filters it out and the permalink answers 404 (never the old 410). Uses
     * only the PUBLIC double-`bind`, no raw `retire`.
     */
    fun detachedTombstone(name: String, path: String, id: PageId) {
        val root = RootName.require(name)
        require(registry.byName(root) == null) { "'$name' must NOT be in the registry - that is what makes it detached" }
        val rooted = RootedPath(root, TreePath.require(path))
        idMap.bind(rooted, id, materialized = false)
        idMap.bind(rooted, UuidV7IdProvider().next(), materialized = false)
        require(idMap.retiredAt(root, id) != null) { "expected '$id' to be tombstoned by the displacing bind" }
    }

    override fun close() {
        watchers.forEach { it.close() } // watchers first: a live one can still schedule a rebuild through the index
        scheduler?.close()
        index.close()
        searchDb.close()
        searchDir.toFile().deleteRecursively()
    }
}

/**
 * Test-only: the rooted path holding [id] when EXACTLY ONE root holds it live (per-root identity, C5). The mechanical
 * replacement for the deleted bare `IdMapRepository.pathOf(id)` at genuinely single-claimant assertion sites. NEVER
 * for a dual-root assertion - those pin BOTH roots' `bindingInRoot` explicitly.
 */
fun IdMapRepository.livePathOf(id: PageId): RootedPath? =
    rootsHoldingId(id).singleOrNull()?.let { root -> bindingInRoot(root, id)?.path }

/** A local root over [path] with its per-root knobs — the fixture twin of one `roots { <name> { … } }` block. */
fun testRoot(
    name: String,
    path: Path,
    editable: Boolean = true,
    history: HistoryMode = HistoryMode.OFF,
): Root = Root(RootName.require(name), RootBackend.Local(path), editable = editable, history = history)

/** Runs [block] against a booted N-root app. */
fun multiRootTest(
    roots: List<Root>,
    globs: List<CommitGlob> = emptyList(),
    enforced: Boolean = false,
    extract: (io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction)? = null,
    histories: ((RootName) -> HistoryProvider)? = null,
    liveWatchers: Boolean = false,
    resolverFactory: ((IndexHarness) -> PageRootResolver)? = null,
    absenceFactory: ((IndexHarness) -> AbsenceClassifier)? = null,
    block: suspend ApplicationTestBuilder.(MultiRootRestHarness) -> Unit,
) {
    MultiRootRestHarness(roots, globs, enforced, extract, histories, liveWatchers, resolverFactory, absenceFactory).use { harness ->
        harness.boot()
        testApplication {
            application { plainbaseModule(harness.services) }
            block(harness)
        }
    }
}

/** Writes a page into [root] (creating parents). */
fun seedPage(root: Path, relativePath: String, title: String, body: String = "body.") {
    val target = root.resolve(relativePath)
    Files.createDirectories(target.parent ?: root)
    Files.writeString(target, "---\ntitle: $title\n---\n\n# $title\n\n$body\n")
}

/** The per-root store lookup, for the tests that drive the domain layer directly. */
fun MultiRootRestHarness.stores(): (RootName) -> ContentStore = { store(it.value) }

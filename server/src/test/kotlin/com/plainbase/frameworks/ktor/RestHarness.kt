package com.plainbase.frameworks.ktor

import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.runtime.HistoryProviders
import com.plainbase.frameworks.runtime.ObservedIndexRuntime
import com.plainbase.frameworks.runtime.RootStores
import com.plainbase.frameworks.runtime.ServingRuntime
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.security.ProxyCsrf
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * The chunk-6 route-test harness: the chunk-5 [IndexHarness] (real store, real renderer, real
 * in-memory SQLite) plus the [RestServices] bundle `plainbaseModule` serves from — the production
 * graph minus Koin. [seed] runs against the id_map BEFORE the first rebuild, which is how the
 * golden tests inject their stable UUID literals (§A4 golden policy).
 *
 * Since chunk S4 the bundle also carries the real search stack ([SearchDb] in a temp dir behind
 * [Fts5SearchProvider], synced by the same [SearchIndexer] publication listener `searchModule`
 * registers), so route tests exercise PB-SEARCH-1 against the embedded engine for real.
 */
class RestHarness(
    root: Path,
    seed: (IdMapRepository) -> Unit = {},
    private val history: HistoryProvider = NoOpHistoryProvider,
    private val authMode: AuthMode = AuthMode.BUILTIN,
    private val proxySecret: String? = null,
) : AutoCloseable {

    private val store = LocalContentStore(root)
    private val searchDir = Files.createTempDirectory("plainbase-rest-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))
    val searchProvider = Fts5SearchProvider(searchDb)
    private lateinit var harness: IndexHarness
    private val searchIndexer = SearchIndexer(
        searchProvider,
        SectionSplitter(),
        { harness.idMap.retiredUnboundIds() },
        { harness.idMap.isRetiredUnbound(it) },
    )

    init {
        harness = IndexHarness(
        root,
        contentStore = store,
        history = history,
        listeners = listOf(
            IndexBuilder.PublicationListener { snap, _ ->
                searchIndexer.sync(snap)
            },
        ),
        searchIndexer = searchIndexer,
        )
    }

    val idMap: IdMapRepository get() = harness.idMap

    /** Durable checkpoint state for route tests that prove search-only operations are read-only. */
    val checkpoints get() = harness.checkpoints

    /** The proof-apply transaction (C0): the ONE way a test can make an absence PROVEN rather than merely observed. */
    val retirements get() = harness.retirements
    val builder get() = harness.builder
    val registry get() = harness.registry

    /** The availability holder - so a test can drive the OTHER 503 and prove the two are not the same answer (C1). */
    val availability get() = harness.availability

    /** The DERIVED limbo set `/healthz` reports (C1). */
    val limbo get() = harness.limbo

    /** The A3 route holder `plainbaseModule` serves from. Auth ON, loopback-dev (OFF) open behavior by default. */
    val services: RouteContext

    /**
     * A [TreeJsonCache] over the harness's builder for the §C4 memoization test. The route path's own memo lives
     * privately inside [GuardedReadFacade]; this exposes the SAME cache type for the per-snapshot-identity assertion.
     */
    val treeJson: TreeJsonCache by lazy { TreeJsonCache(harness.builder, harness.rootRegistry, harness.availability) }

    init {
        seed(harness.idMap)
        harness.builder.rebuild()
        services = harness.testRouteContext(
            searchProvider = searchProvider,
            history = history,
            authMode = authMode,
            proxySecret = proxySecret,
        )
    }

    override fun close() {
        harness.close()
        searchDb.close()
        searchDir.toFile().deleteRecursively()
    }
}

/**
 * Runs [block] inside a `testApplication` serving [plainbaseModule] over a [RestHarness] for
 * [root]. Redirect tests need raw 30x responses, so [restClient] builds the non-following client.
 */
fun restTest(
    root: Path,
    seed: (IdMapRepository) -> Unit = {},
    history: HistoryProvider = NoOpHistoryProvider,
    block: suspend ApplicationTestBuilder.(RestHarness) -> Unit,
) {
    RestHarness(root, seed, history).use { harness ->
        testApplication {
            application { plainbaseModule(harness.services) }
            block(harness)
        }
    }
}

/** A test client that surfaces 30x responses instead of following them (the redirect assertions). */
fun ApplicationTestBuilder.restClient(): HttpClient = createClient { followRedirects = false }

/**
 * Builds the A3 [RouteContext] the route-test harnesses serve from, over an [IndexHarness]'s repos. Default
 * [enforced] = false (loopback-dev — the OPEN behavior under which the existing read/write golden suites run
 * byte-identically to pre-auth, NEVER "auth off as a bypass" — the choke point is real, the mode is dev). An
 * auth-behavior suite passes `enforced = true` + a seeded role to exercise the 401/403 matrix.
 */
@Suppress("LongParameterList")
fun IndexHarness.testRouteContext(
    writePipeline: com.plainbase.domain.service.WritePipeline = writePipeline(),
    searchProvider: com.plainbase.domain.search.SearchProvider,
    history: HistoryProvider = NoOpHistoryProvider,
    /** The PER-ROOT providers, when a test needs roots whose history differs (C4); defaults to main-only. */
    historiesByRoot: ((com.plainbase.domain.root.RootName) -> HistoryProvider)? = null,
    idProvider: com.plainbase.domain.service.IdProvider = identityProvider,
    enforced: Boolean = false,
    authMode: AuthMode = AuthMode.BUILTIN,
    trustedProxyCidrs: List<String> = emptyList(),
    proxySecret: String? = null,
    proxyIdentityHeader: String = PlainbaseConfig.DEFAULT_PROXY_IDENTITY_HEADER,
    secureCookie: Boolean = false,
    proxyCsrf: ProxyCsrf = ProxyCsrf(ByteArray(32) { 7 }),
    mcpAllowedHosts: List<String> = listOf("127.0.0.1", "localhost"),
    mcpAllowedOrigins: List<String> = listOf("http://127.0.0.1", "http://localhost"),
    // P5: a defaulted glob list the enforced-mode tests set non-empty (the production wiring threads
    // config.agentDirectCommitGlobs()); forwarded into buildGuardedApplication so the harness can exercise the gate.
    agentDirectCommitGlobs: List<com.plainbase.domain.service.CommitGlob> = emptyList(),
    extract: (io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction)? = null,
    /** The watch-coverage holder `/healthz` reads. Defaults to all-whole: a harness with no watcher degrades nothing. */
    convergence: com.plainbase.domain.root.RootConvergence = this.convergence,
    /**
     * The id->root resolver (C4). Defaults to the real one over the harness idMap; a window test injects a
     * PageRootResolver over an [AmbiguousIdMap] FAKE to pose the Ambiguous arm / a cross-root move it cannot make real.
     */
    resolver: com.plainbase.domain.service.PageRootResolver = com.plainbase.domain.service.PageRootResolver(idMap, rootRegistry),
    /**
     * The 404-vs-503 classifier (C4, FIX 1). Defaults to the harness's own over the REAL idMap; a window test injects
     * an AbsenceClassifier over the SAME [AmbiguousIdMap] FAKE the resolver uses, so the limbo (503) path fires by
     * construction rather than reading the real rootsHoldingId and answering 404.
     */
    absence: com.plainbase.domain.service.AbsenceClassifier = this.absence,
): RouteContext {
    val policy = PolicyService(
        roles = roleRepository,
        apiTokens = apiTokenRepository,
        audit = auditRepository,
        idProvider = UuidV7IdProvider(),
        clock = Clock.System,
        enforced = enforced,
        editableOf = { rootRegistry.byName(it)?.editable == true },
    )
    // Only roots backed by the builder's actual source subset enter the view; history overrides remain per-root.
    val historiesByName: (RootName) -> HistoryProvider =
        historiesByRoot ?: { if (it == rootRegistry.primary.name) history else NoOpHistoryProvider }
    val sourceByRoot = actualSources.associateBy { it.root.name }
    val rootStores = RootStores(
        rootRegistry.roots.mapNotNull { root -> sourceByRoot[root.name]?.let { root.name to it.store } }.toMap(),
    )
    val historyProviders = HistoryProviders(
        rootRegistry.roots.mapNotNull { root -> sourceByRoot[root.name]?.let { root.name to historiesByName(root.name) } }.toMap(),
    )
    val proposalReader =
        com.plainbase.frameworks.ktor.IndexProposalBaseReader(indexBuilder = builder, stores = stores, absence = absence)
    val proposalService = com.plainbase.domain.service.ProposalService(
        repository = proposalRepository,
        citations = CitationFactory(),
        baseReader = proposalReader,
        proposalIdProvider = com.plainbase.domain.service.UuidV7ProposalIdProvider(),
        clock = Clock.System,
        rootStatus = { root -> resolver.statusOf(root, availability.current()) },
    )
    val pageService = PageService(builder, registry, CitationFactory())
    val searchService = SearchService(provider = searchProvider, indexBuilder = builder, availability = availability)
    val proposalLabeler = com.plainbase.domain.service.ProposalAuthorLabeler(tokens = apiTokenRepository, users = userRepository)
    val auth = authServices(policy)
    val serving = ServingRuntime(
        index = ObservedIndexRuntime(
            builder = builder,
            registry = rootRegistry,
            stores = rootStores,
            histories = historyProviders,
            availability = availability,
            convergence = convergence,
            limbo = limbo,
            epochs = epochs,
            bindings = bindings,
            identity = identity,
            idProvider = idProvider,
            aliasRegistry = registry,
        ),
        pageService = pageService,
        searchService = searchService,
        writePipeline = writePipeline,
        resolver = resolver,
        absence = absence,
        proposalService = proposalService,
        proposalLabeler = proposalLabeler,
        agentDirectCommitGlobs = agentDirectCommitGlobs,
    )
    val config = PlainbaseConfig(
        contentDir = java.nio.file.Path.of("."),
        dataDir = java.nio.file.Path.of("."),
        host = "127.0.0.1",
        port = 8080,
        auth = AuthConfig(
            mode = authMode,
            trustedProxyCidrs = trustedProxyCidrs,
            proxySecret = proxySecret,
            proxyIdentityHeader = proxyIdentityHeader,
        ),
    )
    val context = buildGuardedApplication(
        serving = serving,
        security = securityAssembly(
            config = config,
            policy = policy,
            tokens = apiTokens,
            auth = auth,
            proxyCsrf = proxyCsrf,
        ),
        transport = TransportSettings(
            maxWriteBodyBytes = PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
            maxAssetBytes = PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
            mcpAllowedHosts = mcpAllowedHosts,
            mcpAllowedOrigins = mcpAllowedOrigins,
            secureCookie = secureCookie,
        ),
    )
    return extract?.let(context::withExtract) ?: context
}

/** Builds the A4a [AuthServices] over the harness's in-memory repos, sharing [policy] for the admin checkManage. */
fun IndexHarness.authServices(policy: PolicyService): com.plainbase.frameworks.ktor.AuthServices {
    val setup = com.plainbase.domain.service.SetupService(
        minter = com.plainbase.frameworks.security.SetupTokenMinter(com.plainbase.frameworks.security.TokenHasher()),
        hasher = com.plainbase.frameworks.security.TokenHasher(),
        setupTokens = setupTokenRepository,
        users = userRepository,
        roles = roleRepository,
        sessions = sessionService,
        passwordHasher = com.plainbase.frameworks.security.Argon2PasswordHasher(),
        idProvider = UuidV7IdProvider(),
        transactions = transactionRunner,
        clock = Clock.System,
    )
    val admin = com.plainbase.frameworks.ktor.GuardedAdminFacade(
        policy = policy,
        users = userRepository,
        roles = roleRepository,
        setup = setup,
        sessions = sessionService,
        passwordHasher = com.plainbase.frameworks.security.Argon2PasswordHasher(),
        idProvider = UuidV7IdProvider(),
        transactions = transactionRunner,
        clock = Clock.System,
        tokens = apiTokens,
        audit = auditRepository,
    )
    val login = com.plainbase.domain.service.LoginService(
        users = userRepository,
        passwordHasher = com.plainbase.frameworks.security.Argon2PasswordHasher(),
        sessions = sessionService,
        transactions = transactionRunner,
        dummyHash = com.plainbase.frameworks.security.dummyPasswordHash(com.plainbase.frameworks.security.Argon2PasswordHasher()),
    )
    return com.plainbase.frameworks.ktor.AuthServices(
        session = sessionService,
        login = login,
        setup = setup,
        admin = admin,
        rateLimiter = com.plainbase.frameworks.ktor.LoginRateLimiter(),
    )
}

package com.plainbase.frameworks.ktor

import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
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
) : AutoCloseable {

    private val store = LocalContentStore(root)
    private val searchDir = Files.createTempDirectory("plainbase-rest-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))
    val searchProvider = Fts5SearchProvider(searchDb)
    private val searchIndexer = SearchIndexer(searchProvider, SectionSplitter())
    private val harness = IndexHarness(
        root,
        contentStore = store,
        history = history,
        listeners = listOf(IndexBuilder.PublicationListener(searchIndexer::sync)),
        searchIndexer = searchIndexer,
    )

    val idMap: IdMapRepository get() = harness.idMap
    val builder get() = harness.builder
    val registry get() = harness.registry

    /** The A3 route holder `plainbaseModule` serves from. Auth ON, loopback-dev (OFF) open behavior by default. */
    val services: RouteContext

    /**
     * A [TreeJsonCache] over the harness's builder for the §C4 memoization test. The route path's own memo lives
     * privately inside [GuardedReadFacade]; this exposes the SAME cache type for the per-snapshot-identity assertion.
     */
    val treeJson: TreeJsonCache by lazy { TreeJsonCache(harness.builder) }

    init {
        seed(harness.idMap)
        harness.builder.rebuild()
        services = harness.testRouteContext(
            contentStore = store,
            searchProvider = searchProvider,
            history = history,
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
    contentStore: com.plainbase.domain.content.ContentStore,
    writePipeline: com.plainbase.domain.service.WritePipeline = writePipeline(),
    searchProvider: com.plainbase.domain.search.SearchProvider,
    history: HistoryProvider = NoOpHistoryProvider,
    idProvider: com.plainbase.domain.service.IdProvider = UuidV7IdProvider(),
    enforced: Boolean = false,
    trustedProxyCidrs: List<String> = emptyList(),
    builtinAuthEnabled: Boolean = true,
    proxyAuthEnabled: Boolean = false,
    proxySecret: String? = null,
    proxyIdentityHeader: String = PlainbaseConfig.DEFAULT_PROXY_IDENTITY_HEADER,
    secureCookie: Boolean = false,
    proxyCsrf: com.plainbase.frameworks.security.ProxyCsrf = com.plainbase.frameworks.security.ProxyCsrf(ByteArray(32) { 7 }),
    extract: (io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction)? = null,
): RouteContext {
    val policy = PolicyService(
        roles = roleRepository,
        apiTokens = apiTokenRepository,
        audit = auditRepository,
        idProvider = UuidV7IdProvider(),
        clock = Clock.System,
        enforced = enforced,
    )
    val proposalReader = com.plainbase.frameworks.ktor.IndexProposalBaseReader(indexBuilder = builder, contentStore = contentStore)
    val proposalService = com.plainbase.domain.service.ProposalService(
        repository = proposalRepository,
        citations = CitationFactory(),
        baseReader = proposalReader,
        proposalIdProvider = com.plainbase.domain.service.UuidV7ProposalIdProvider(),
        clock = Clock.System,
    )
    val proposalFacade = com.plainbase.frameworks.ktor.GuardedProposalFacade(
        policy = policy,
        proposals = proposalService,
        labeler = com.plainbase.domain.service.ProposalAuthorLabeler(tokens = apiTokenRepository, users = userRepository),
    )
    return buildRouteContext(
        policy = policy,
        indexBuilder = builder,
        pageService = PageService(builder, registry, CitationFactory()),
        searchService = SearchService(provider = searchProvider, indexBuilder = builder),
        aliasRegistry = registry,
        contentStore = contentStore,
        writePipeline = writePipeline,
        history = history,
        idProvider = idProvider,
        proposals = proposalFacade,
        tokens = apiTokens,
        auth = authServices(policy),
        trustedProxyCidrs = trustedProxyCidrs,
        maxWriteBodyBytes = PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
        maxAssetBytes = PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
        builtinAuthEnabled = builtinAuthEnabled,
        proxyAuthEnabled = proxyAuthEnabled,
        proxySecret = proxySecret,
        proxyIdentityHeader = proxyIdentityHeader,
        secureCookie = secureCookie,
        proxyCsrf = proxyCsrf,
        extract = extract,
    )
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

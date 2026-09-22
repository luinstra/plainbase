package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.NoRetirements
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.LoginService
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.SessionService
import com.plainbase.domain.service.SetupService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.localRoot
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.runtime.HistoryProviders
import com.plainbase.frameworks.runtime.ObservedIndexRuntime
import com.plainbase.frameworks.runtime.RootStores
import com.plainbase.frameworks.runtime.ServingRuntime
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.Argon2PasswordHasher
import com.plainbase.frameworks.security.ProxyCsrf
import com.plainbase.frameworks.security.SessionTokenMinter
import com.plainbase.frameworks.security.SetupTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.security.dummyPasswordHash
import com.plainbase.frameworks.security.loadOrCreateProxyCsrfKey
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightProposalRepository
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
 * The full production wiring of the REST read path over a runtime temp tree, for the native-smoke
 * tests: real [LocalContentStore], real renderer, real in-memory SQLite repos, and (since S4) the
 * real search stack — a file-backed [SearchDb] synced through the same publication listener
 * `searchModule` registers — `restModule`'s graph minus Koin. kotlin.test-compatible (no
 * Kotest/MockK; this source set feeds the native test image).
 */
fun withRestServices(
    pages: Map<String, String> = emptyMap(),
    rawPages: Map<String, ByteArray> = emptyMap(),
    seedAdmin: Pair<String, String>? = null, // (username, password) — seeds a builtin ADMIN before the block runs
    authMode: AuthMode = AuthMode.BUILTIN,
    seedProxyAdmin: String? = null, // A4b: grant ADMIN to a proxy/<subject> identity (the grant-role first-admin seam)
    agentDirectCommitGlobs: List<com.plainbase.domain.service.CommitGlob> = emptyList(), // P5: the direct-commit globs
    block: (RouteContext) -> Unit,
) {
    // A4b: in proxy mode the loopback test client (127.0.0.1) counts as loopback-secure, so a request can present the
    // identity header + secret and authenticate.
    val proxySecret = if (authMode == AuthMode.PROXY) "native-proxy-secret" else null
    val content = Files.createTempDirectory("pb-native-rest")
    val data = Files.createTempDirectory("pb-native-rest-data")
    try {
        for ((relativePath, body) in pages) {
            val target = content.resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.writeString(target, body)
        }
        for ((relativePath, body) in rawPages) {
            val target = content.resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.write(target, body)
        }
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val database = DatabaseFactory.createDatabase(driver)
            val store = LocalContentStore(content)
            val registry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
            // ONE id_map feeds both the IndexBuilder and the new WritePipeline.create, so the create's
            // bind and the index see the same map (W2).
            val idMap = SqlDelightIdMapRepository(database)
            SearchDb(data.resolve("search.db")).use { searchDb ->
                val searchProvider = Fts5SearchProvider(searchDb)
                val rootRegistry = RootRegistry.of(listOf(localRoot("docs", content)))
                val policies = com.plainbase.domain.service.allowAllNativePolicies(rootRegistry.roots.map { it.name })
                val searchIndexer = SearchIndexer(
                    searchProvider,
                    SectionSplitter(),
                    idMap::retiredUnboundIds,
                    idMap::isRetiredUnbound,
                    policies,
                )
                val availability = com.plainbase.domain.root.RootAvailability(Clock.System)
                val limbo = RootLimbo()
                val convergence = RootConvergence()
                val epochs = ObservationEpoch(NoRetirements, convergence)
                val bindings = BindingLatch(com.plainbase.domain.repository.NoTopology)
                val identityProvider = UuidV7IdProvider()
                val identity = PageIdentityService(identityProvider)
                val builder = IndexBuilder(
                    sources = listOf(IndexBuilder.Source(rootRegistry.primary, store, NoOpHistoryProvider)),
                    frontmatterParser = FrontmatterReader(),
                    rendererFactory = { view -> FlexmarkRenderer(view) },
                    identity = identity,
                    patcher = FrontmatterPatcher(),
                    idMap = idMap,
                    aliasRegistry = registry,
                    checkpoint = SqlDelightPageCheckpointRepository(database),
                    citations = CitationFactory(),
                    rootRank = rootRegistry::rank,
                    registeredRoots = rootRegistry.roots.map { it.name }.toSet(),
                    listeners = listOf(
                        IndexBuilder.PublicationListener { snap, _ ->
                            searchIndexer.sync(snap)
                        },
                    ),
                    searchIndexer = searchIndexer,
                    availability = availability,
                    limbo = limbo,
                    epochs = epochs,
                    bindings = bindings,
                    policies = policies,
                )
                builder.rebuild()
                val writeCitations = CitationFactory()
                // A3 auth substrate over the SAME in-memory DB (the schema includes subject_role/audit_log). BUILTIN,
                // loopback-dev open policy — the native REST/write/asset/search smokes run byte-identically to pre-auth.
                // The grant constructors stay reachable via the public src/main grantForTests* path.
                val apiTokens = ApiTokenService(
                    minter = ApiTokenMinter(),
                    hasher = TokenHasher(),
                    tokens = SqlDelightApiTokenRepository(database),
                    clock = Clock.System,
                )
                val policy = PolicyService(
                    roles = SqlDelightRoleRepository(database),
                    apiTokens = SqlDelightApiTokenRepository(database),
                    audit = SqlDelightAuditRepository(database),
                    idProvider = UuidV7IdProvider(),
                    clock = Clock.System,
                    // Proxy mode enforces the matrix (so a no-role proxy human is denied); default BUILTIN native
                    // smokes keep the open dev behavior.
                    enforced = authMode == AuthMode.PROXY,
                )
                // A4a auth substrate over the SAME in-memory DB (the v7 schema includes users/sessions/setup_tokens).
                val passwordHasher = Argon2PasswordHasher()
                val sessionService = SessionService(
                    minter = SessionTokenMinter(TokenHasher()),
                    hasher = TokenHasher(),
                    sessions = SqlDelightSessionRepository(database),
                    clock = Clock.System,
                )
                val setupService = SetupService(
                    minter = SetupTokenMinter(TokenHasher()),
                    hasher = TokenHasher(),
                    setupTokens = SqlDelightSetupTokenRepository(database),
                    users = SqlDelightUserRepository(database),
                    roles = SqlDelightRoleRepository(database),
                    sessions = sessionService,
                    passwordHasher = passwordHasher,
                    idProvider = UuidV7IdProvider(),
                    transactions = SqlDelightTransactionRunner(database),
                    clock = Clock.System,
                )
                val authServices = AuthServices(
                    session = sessionService,
                    login = LoginService(
                        users = SqlDelightUserRepository(database),
                        passwordHasher = passwordHasher,
                        sessions = sessionService,
                        transactions = SqlDelightTransactionRunner(database),
                        dummyHash = dummyPasswordHash(passwordHasher),
                    ),
                    setup = setupService,
                    admin = GuardedAdminFacade(
                        policy = policy,
                        users = SqlDelightUserRepository(database),
                        roles = SqlDelightRoleRepository(database),
                        setup = setupService,
                        sessions = sessionService,
                        passwordHasher = passwordHasher,
                        idProvider = UuidV7IdProvider(),
                        transactions = SqlDelightTransactionRunner(database),
                        clock = Clock.System,
                        tokens = apiTokens,
                        audit = SqlDelightAuditRepository(database),
                    ),
                    rateLimiter = LoginRateLimiter(),
                )
                seedAdmin?.let { (username, password) ->
                    val now = Clock.System.now()
                    val userId = UuidV7IdProvider().next().value
                    SqlDelightUserRepository(database).insert(
                        com.plainbase.domain.repository.UserRow(
                            id = userId,
                            username = username,
                            passwordHash = passwordHasher.hash(password.toCharArray()),
                            displayName = null,
                            disabled = false,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    SqlDelightRoleRepository(database).upsert("builtin", userId, com.plainbase.domain.repository.Role.ADMIN, now)
                }
                seedProxyAdmin?.let { subject ->
                    SqlDelightRoleRepository(
                        database,
                    ).upsert("proxy", subject, com.plainbase.domain.repository.Role.ADMIN, Clock.System.now())
                }
                val stores: (com.plainbase.domain.root.RootName) -> com.plainbase.domain.content.ContentStore = { store }
                val resolver = com.plainbase.domain.service.PageRootResolver(idMap, rootRegistry, policies)
                val absence = com.plainbase.domain.service.AbsenceClassifier(idMap, policies)
                val proposalBaseReader = IndexProposalBaseReader(
                    indexBuilder = builder,
                    stores = stores,
                    absence = absence,
                    policies = policies,
                )
                val proposalService = com.plainbase.domain.service.ProposalService(
                    repository = SqlDelightProposalRepository(database),
                    citations = CitationFactory(),
                    baseReader = proposalBaseReader,
                    proposalIdProvider = com.plainbase.domain.service.UuidV7ProposalIdProvider(),
                    clock = Clock.System,
                    rootStatus = { root -> resolver.statusOf(root, availability.current()) },
                    proposalEligibility = resolver::proposalEligible,
                )
                val pageService = PageService(builder, registry, CitationFactory())
                val searchService = SearchService(
                    provider = searchProvider,
                    indexBuilder = builder,
                    availability = availability,
                    policies = policies,
                )
                val writePipeline = WritePipeline(
                    stores = stores,
                    indexBuilder = builder,
                    citations = writeCitations,
                    frontmatterParser = FrontmatterReader(),
                    dirtyPages = SqlDelightDirtyPageRepository(database),
                    idMap = idMap,
                    aliasRegistry = registry,
                    availability = availability,
                    policies = policies,
                )
                val proposalLabeler = com.plainbase.domain.service.ProposalAuthorLabeler(
                    tokens = SqlDelightApiTokenRepository(database),
                    users = SqlDelightUserRepository(database),
                )
                val services = buildGuardedApplication(
                    serving = ServingRuntime(
                        index = ObservedIndexRuntime(
                            builder = builder,
                            registry = rootRegistry,
                            stores = RootStores(rootRegistry.roots.associate { it.name to store }),
                            histories = HistoryProviders(rootRegistry.roots.associate { it.name to NoOpHistoryProvider }),
                            availability = availability,
                            convergence = convergence,
                            limbo = limbo,
                            epochs = epochs,
                            bindings = bindings,
                            identity = identity,
                            idProvider = identityProvider,
                            aliasRegistry = registry,
                            policies = policies,
                        ),
                        pageService = pageService,
                        searchService = searchService,
                        writePipeline = writePipeline,
                        resolver = resolver,
                        absence = absence,
                        proposalService = proposalService,
                        proposalLabeler = proposalLabeler,
                        agentDirectCommitGlobs = agentDirectCommitGlobs,
                    ),
                    security = securityAssembly(
                        config = PlainbaseConfig(
                            contentDir = content,
                            dataDir = data,
                            host = "127.0.0.1",
                            port = 8080,
                            auth = AuthConfig(mode = authMode, proxySecret = proxySecret),
                        ),
                        policy = policy,
                        tokens = apiTokens,
                        auth = authServices,
                        // Exercise the real app_meta key load + persistence path in the native image.
                        proxyCsrf = ProxyCsrf(loadOrCreateProxyCsrfKey(database)),
                    ),
                    transport = TransportSettings(
                        maxWriteBodyBytes = PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
                        maxAssetBytes = PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
                        mcpAllowedHosts = listOf("127.0.0.1", "localhost"),
                        mcpAllowedOrigins = listOf("http://127.0.0.1", "http://localhost"),
                        secureCookie = false,
                    ),
                )
                block(services)
            }
        }
    } finally {
        deleteRecursively(content)
        deleteRecursively(data)
    }
}

private fun deleteRecursively(root: Path) {
    Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

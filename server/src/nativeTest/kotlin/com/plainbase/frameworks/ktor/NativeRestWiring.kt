package com.plainbase.frameworks.ktor

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
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.Argon2PasswordHasher
import com.plainbase.frameworks.security.SessionTokenMinter
import com.plainbase.frameworks.security.SetupTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.security.dummyPasswordHash
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
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
    seedAdmin: Pair<String, String>? = null, // (username, password) — seeds a builtin ADMIN before the block runs
    block: (RouteContext) -> Unit,
) {
    val content = Files.createTempDirectory("pb-native-rest")
    val data = Files.createTempDirectory("pb-native-rest-data")
    try {
        for ((relativePath, body) in pages) {
            val target = content.resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.writeString(target, body)
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
                val searchIndexer = SearchIndexer(searchProvider, SectionSplitter())
                val builder = IndexBuilder(
                    contentStore = store,
                    frontmatterParser = FrontmatterReader(),
                    rendererFactory = { view -> FlexmarkRenderer(view) },
                    identity = PageIdentityService(UuidV7IdProvider()),
                    patcher = FrontmatterPatcher(),
                    idMap = idMap,
                    aliasRegistry = registry,
                    checkpoint = SqlDelightPageCheckpointRepository(database),
                    citations = CitationFactory(),
                    history = NoOpHistoryProvider,
                    listeners = listOf(IndexBuilder.PublicationListener(searchIndexer::sync)),
                    searchIndexer = searchIndexer,
                )
                builder.rebuild()
                val writeCitations = CitationFactory()
                // A3 auth substrate over the SAME in-memory DB (the schema includes subject_role/audit_log). Auth
                // ON, loopback-dev (OFF) open mode — the native REST/write/asset/search smokes run byte-identically
                // to pre-auth. The grant constructors stay reachable via the public src/main grantForTests* path.
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
                    enforced = false,
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
                val services = buildRouteContext(
                    policy = policy,
                    indexBuilder = builder,
                    pageService = PageService(builder, registry, CitationFactory()),
                    searchService = SearchService(provider = searchProvider, indexBuilder = builder),
                    aliasRegistry = registry,
                    contentStore = store,
                    writePipeline = WritePipeline(
                        contentStore = store,
                        indexBuilder = builder,
                        citations = writeCitations,
                        frontmatterParser = FrontmatterReader(),
                        dirtyPages = SqlDelightDirtyPageRepository(database),
                        idMap = idMap,
                        aliasRegistry = registry,
                    ),
                    history = NoOpHistoryProvider,
                    idProvider = UuidV7IdProvider(),
                    tokens = apiTokens,
                    auth = authServices,
                    trustedProxyCidrs = emptyList(),
                    maxWriteBodyBytes = PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
                    maxAssetBytes = PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
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

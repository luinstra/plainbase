package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.UserRow
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.SessionService
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
import com.plainbase.frameworks.security.Argon2PasswordHasher
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.time.Clock

/**
 * A dedicated route-test harness for the A4a auth surface: a real [IndexHarness] (real in-memory v7 SQLite repos)
 * wired through [authServices], served by `plainbaseModule`. Exposes seeding (users + roles) and the raw services so
 * tests can assert sessions/CSRF directly. [enforced] toggles the PolicyService matrix (the public-allowlist test
 * runs enforced; login itself works in either mode).
 */
class AuthRouteHarness(
    authMode: AuthMode = AuthMode.BUILTIN,
    private val enforced: Boolean = authMode != AuthMode.OFF,
    proxySecret: String? = null,
    trustedProxyCidrs: List<String> = emptyList(),
    val proxyCsrf: com.plainbase.frameworks.security.ProxyCsrf =
        com.plainbase.frameworks.security.ProxyCsrf(ByteArray(32) { 7 }),
    extract: (io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction)? = null,
) : AutoCloseable {

    private val root = Files.createTempDirectory("plainbase-auth-route")
    private val store = LocalContentStore(root)
    private val harness = IndexHarness(root, contentStore = store)
    private val fastHasher = Argon2PasswordHasher(memoryKb = 256, iterations = 1)

    val sessionService: SessionService get() = harness.sessionService

    val context: RouteContext = run {
        val policies = harness.policies
        val policy = PolicyService(
            roles = harness.roleRepository,
            apiTokens = harness.apiTokenRepository,
            audit = harness.auditRepository,
            idProvider = UuidV7IdProvider(),
            clock = Clock.System,
            enforced = enforced,
        )
        val auth = harness.authServices(policy)
        val resolver = PageRootResolver(harness.idMap, harness.rootRegistry, policies)
        val absence = AbsenceClassifier(harness.idMap, policies)
        val stores = RootStores(
            mapOf(harness.rootRegistry.primary.name to harness.stores(harness.rootRegistry.primary.name)),
        )
        val histories = HistoryProviders(mapOf(harness.rootRegistry.primary.name to NoOpHistoryProvider))
        val pageService = PageService(harness.builder, harness.registry, CitationFactory())
        val searchService = SearchService(
            provider = harness.fts(),
            indexBuilder = harness.builder,
            availability = harness.availability,
            policies = policies,
        )
        val proposalService = ProposalService(
            repository = harness.proposalRepository,
            citations = CitationFactory(),
            baseReader = IndexProposalBaseReader(harness.builder, harness.stores, absence, policies),
            proposalIdProvider = com.plainbase.domain.service.UuidV7ProposalIdProvider(),
            clock = Clock.System,
            rootStatus = { root -> resolver.statusOf(root, harness.availability.current()) },
        )
        val proposalLabeler = ProposalAuthorLabeler(harness.apiTokenRepository, harness.userRepository)
        val actual = buildGuardedApplication(
            serving = ServingRuntime(
                index = ObservedIndexRuntime(
                    builder = harness.builder,
                    registry = harness.rootRegistry,
                    stores = stores,
                    histories = histories,
                    availability = harness.availability,
                    convergence = harness.convergence,
                    limbo = harness.limbo,
                    epochs = harness.epochs,
                    bindings = harness.bindings,
                    identity = harness.identity,
                    idProvider = harness.identityProvider,
                    aliasRegistry = harness.registry,
                    policies = policies,
                ),
                pageService = pageService,
                searchService = searchService,
                writePipeline = harness.writePipeline(),
                resolver = resolver,
                absence = absence,
                proposalService = proposalService,
                proposalLabeler = proposalLabeler,
                agentDirectCommitGlobs = emptyList(),
            ),
            security = securityAssembly(
                config = PlainbaseConfig(
                    contentDir = root,
                    dataDir = root.resolve("auth-fixture-data"),
                    host = "127.0.0.1",
                    port = 8080,
                    auth = AuthConfig(
                        mode = authMode,
                        trustedProxyCidrs = trustedProxyCidrs,
                        proxySecret = proxySecret,
                    ),
                ),
                policy = policy,
                tokens = harness.apiTokens,
                auth = auth,
                proxyCsrf = proxyCsrf,
            ),
            transport = TransportSettings(
                maxWriteBodyBytes = PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
                maxAssetBytes = PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
                mcpAllowedHosts = listOf("127.0.0.1", "localhost"),
                mcpAllowedOrigins = listOf("http://127.0.0.1", "http://localhost"),
                secureCookie = false,
            ),
        )
        extract?.let(actual::withExtract) ?: actual
    }

    init {
        harness.builder.rebuild()
    }

    /** Seed an enabled user with [username]/[password] and a [role] grant (builtin issuer). Returns the user id. */
    fun seedUser(username: String, password: String, role: Role, disabled: Boolean = false): String {
        val id = com.plainbase.domain.service.UuidV7IdProvider().next().value
        val now = Clock.System.now()
        harness.userRepository.insert(
            UserRow(
                id = id,
                username = username,
                passwordHash = fastHasher.hash(password.toCharArray()),
                displayName = null,
                disabled = disabled,
                createdAt = now,
                updatedAt = now,
            ),
        )
        harness.roleRepository.upsert("builtin", id, role, now)
        return id
    }

    /** Grant [role] to a PROXY identity `(issuer="proxy", externalId=subject)` — the grant-role first-admin seam. */
    fun seedProxyRole(subject: String, role: Role) {
        harness.roleRepository.upsert("proxy", subject, role, Clock.System.now())
    }

    /** Grant [role] to an arbitrary `(issuer, externalId)` identity — for a fixed-principal route test. */
    fun grantRole(issuer: String, externalId: String, role: Role) {
        harness.roleRepository.upsert(issuer, externalId, role, Clock.System.now())
    }

    /** Mint a fresh proxy double-submit token (the cookie/header value a test echoes on a proxy-Human mutation). */
    fun issueProxyCsrf(): String = proxyCsrf.issue()

    /** Mint a bootstrap setup token directly (for the setup-consume route tests). */
    fun mintBootstrapToken(): String = context.auth.setup.mintBootstrapToken().plaintext

    /** Mint an agent bearer token with [mode]; returns the `pb_...` plaintext (for the bearer-exempt CSRF test). */
    fun mintAgentToken(mode: com.plainbase.domain.repository.AgentMode): String =
        harness.apiTokens.mint(label = "ci", mode = mode).plaintext

    /** Write a markdown page under the content root and rebuild; returns its assigned page id + current content hash. */
    fun seedPage(relativePath: String, body: String): Pair<String, String> {
        Files.createDirectories(root.resolve(relativePath).parent)
        Files.writeString(root.resolve(relativePath), body)
        harness.builder.rebuild()
        // The harness seeds exactly one page; match on the filename stem either with or without the .md suffix.
        val stem = relativePath.removeSuffix(".md")
        val page = harness.builder.current.pages.first { it.path.value == stem || it.path.value == relativePath }
        return page.id.value to page.contentHash
    }

    override fun close() {
        harness.close()
        root.toFile().deleteRecursively()
    }
}

/** Runs [block] inside a `testApplication` serving the auth surface over an [AuthRouteHarness]. */
fun authRouteTest(
    authMode: AuthMode = AuthMode.BUILTIN,
    enforced: Boolean = authMode != AuthMode.OFF,
    proxySecret: String? = null,
    trustedProxyCidrs: List<String> = emptyList(),
    extract: (io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction)? = null,
    block: suspend ApplicationTestBuilder.(AuthRouteHarness) -> Unit,
) {
    AuthRouteHarness(
        authMode = authMode,
        enforced = enforced,
        proxySecret = proxySecret,
        trustedProxyCidrs = trustedProxyCidrs,
        extract = extract,
    ).use { harness ->
        testApplication {
            application { plainbaseModule(harness.context) }
            block(harness)
        }
    }
}

/** A test client that keeps cookies across requests (for the login → cookie → next-request flow). */
fun ApplicationTestBuilder.cookieClient(): HttpClient = createClient { install(HttpCookies) }

/** The harness's FTS provider over a temp search db, mirroring AuthMatrixTest's `fts`. */
private fun IndexHarness.fts(): com.plainbase.domain.search.SearchProvider {
    val dir = Files.createTempDirectory("plainbase-auth-search")
    val db = com.plainbase.frameworks.search.SearchDb(dir.resolve("search.db"))
    return com.plainbase.frameworks.search.Fts5SearchProvider(db)
}

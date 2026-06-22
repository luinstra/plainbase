package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.UserRow
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SessionService
import com.plainbase.frameworks.filesystem.LocalContentStore
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
    private val enforced: Boolean = true,
    builtinAuthEnabled: Boolean = true,
    extract: (io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction)? = null,
) : AutoCloseable {

    private val root = Files.createTempDirectory("plainbase-auth-route")
    private val store = LocalContentStore(root)
    private val harness = IndexHarness(root, contentStore = store)
    private val fastHasher = Argon2PasswordHasher(memoryKb = 256, iterations = 1)

    val sessionService: SessionService get() = harness.sessionService

    private val policy = com.plainbase.domain.service.PolicyService(
        roles = harness.roleRepository,
        apiTokens = harness.apiTokenRepository,
        audit = harness.auditRepository,
        idProvider = com.plainbase.domain.service.UuidV7IdProvider(),
        clock = Clock.System,
        enforced = enforced,
    )

    private val auth = harness.authServices(policy)

    val context: RouteContext = buildRouteContext(
        policy = policy,
        indexBuilder = harness.builder,
        pageService = com.plainbase.domain.service.PageService(
            harness.builder,
            harness.registry,
            com.plainbase.domain.service.CitationFactory(),
        ),
        searchService = com.plainbase.domain.service.SearchService(provider = harness.fts(), indexBuilder = harness.builder),
        aliasRegistry = harness.registry,
        contentStore = store,
        writePipeline = harness.writePipeline(),
        history = com.plainbase.frameworks.git.NoOpHistoryProvider,
        idProvider = com.plainbase.domain.service.UuidV7IdProvider(),
        tokens = harness.apiTokens,
        auth = auth,
        trustedProxyCidrs = emptyList(),
        maxWriteBodyBytes = com.plainbase.frameworks.config.PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
        maxAssetBytes = com.plainbase.frameworks.config.PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
        builtinAuthEnabled = builtinAuthEnabled,
        extract = extract,
    )

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

    /** Mint a bootstrap setup token directly (for the setup-consume route tests). */
    fun mintBootstrapToken(): String = auth.setup.mintBootstrapToken().plaintext

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
    enforced: Boolean = true,
    builtinAuthEnabled: Boolean = true,
    extract: (io.ktor.server.application.ApplicationCall.() -> PrincipalExtraction)? = null,
    block: suspend ApplicationTestBuilder.(AuthRouteHarness) -> Unit,
) {
    AuthRouteHarness(enforced, builtinAuthEnabled, extract).use { harness ->
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

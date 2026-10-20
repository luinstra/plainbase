package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.root.RootName
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyMode
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.domain.theming.BundledThemeStylesheets
import com.plainbase.domain.theming.ThemeStylesheets
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.ktor.routes.embeddedThemeBuiltins
import com.plainbase.frameworks.runtime.HistoryProviders
import com.plainbase.frameworks.runtime.ObservedIndexRuntime
import com.plainbase.frameworks.runtime.RootStores
import com.plainbase.frameworks.runtime.ServingRuntime
import com.plainbase.frameworks.security.ProxyCsrf
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path

/** Verifies the guarded application's identity, cycle, transport and derived-auth assembly. */
class GuardedApplicationFactoryTest : FunSpec({

    test("the returned graph keeps the supplied holders") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val graph = GuardedTestGraph(root, harness)
                val themes = mockk<ThemeStylesheets>()
                val context = graph.context(AuthMode.BUILTIN, themes)
                val pageId = harness.builder.current.pages.single().id

                context.availability shouldBeSameInstanceAs harness.availability
                context.convergence shouldBeSameInstanceAs harness.convergence
                context.limbo shouldBeSameInstanceAs harness.limbo
                context.themes shouldBeSameInstanceAs themes
                context.read.pageById(Principal.Anonymous, pageId) shouldNotBe null
            }
        }
    }

    test("a known page refuses after the supplied root becomes unavailable") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val context = GuardedTestGraph(root, harness).context(AuthMode.BUILTIN)
                val rootName = RootName.PRIMARY
                val pageId = harness.builder.current.pages.single().id
                context.read.pageById(Principal.Anonymous, pageId) shouldNotBe null

                harness.availability.markUnavailable(rootName, com.plainbase.domain.root.UnavailableCause.VANISHED)
                shouldThrow<RootUnavailable> {
                    context.read.pageById(Principal.Anonymous, pageId)
                }.root shouldBe rootName
            }
        }
    }

    test("convergence and limbo changes remain visible through the returned context") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val context = GuardedTestGraph(root, harness).context(AuthMode.BUILTIN)
                val rootName = RootName.PRIMARY
                val pageId = harness.builder.current.pages.single().id

                context.convergence.record(rootName, whole = false)
                context.convergence.isWhole(rootName) shouldBe false
                context.convergence.record(rootName, whole = true)
                context.convergence.isWhole(rootName) shouldBe true

                Files.delete(root.resolve("doc.md"))
                harness.builder.rebuild()
                context.limbo.holds(rootName, pageId) shouldBe true

                writePage(root, "doc.md", "---\nid: ${pageId.value}\ntitle: Doc\n---\n\n# Doc\n")
                harness.builder.rebuild()
                context.limbo.holds(rootName, pageId) shouldBe false
            }
        }
    }

    test("construction does not invoke the deferred proposal cycle or external writer") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val graph = GuardedTestGraph(root, harness)
                graph.context(AuthMode.BUILTIN)
                confirmVerified(graph.writePipeline, graph.proposalService)
            }
        }
    }

    test("the real session cookie is honored only by the BUILTIN-derived context") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val graph = GuardedTestGraph(root, harness)
                val userId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
                val session = harness.sessionService.create(userId)
                val builtin = graph.context(AuthMode.BUILTIN)
                val off = graph.context(AuthMode.OFF)

                builtin.builtinAuthEnabled shouldBe true
                builtin.proxyAuthEnabled shouldBe false
                val headers = mapOf(HttpHeaders.Cookie to "$SESSION_COOKIE_NAME=${session.plaintext}")
                val builtinExtraction = captureExtraction(builtin, headers)
                val builtinResolved = builtinExtraction as PrincipalExtraction.Resolved
                builtinResolved.principal shouldBe Principal.Human("builtin", userId)
                builtinResolved.source shouldBe Source.COOKIE
                builtinResolved.csrfToken?.contentEquals(session.csrfToken) shouldBe true

                off.builtinAuthEnabled shouldBe false
                off.proxyAuthEnabled shouldBe false
                captureExtraction(off, headers) shouldBe
                    PrincipalExtraction.Resolved(Principal.Anonymous)
            }
        }
    }

    test("workspace OFF resolves the valid bearer to an agent") {
        withTempTree(seed = {}) { root ->
            IndexHarness(root).use { harness ->
                val minted = harness.apiTokens.mint("probe", AgentMode.COMMIT)
                val tokens = spyk(harness.apiTokens)
                val context = GuardedTestGraph(root, harness).context(AuthMode.OFF, tokens = tokens)

                requireNotNull(harness.apiTokenRepository.findById(minted.id)).lastUsedAt shouldBe null
                val extraction = captureExtraction(context, mapOf(HttpHeaders.Authorization to "Bearer ${minted.plaintext}"))
                    .shouldBeInstanceOf<PrincipalExtraction.Resolved>()
                extraction.principal.shouldBeInstanceOf<Principal.Agent>().tokenId shouldBe minted.id
                extraction.source shouldBe null
                extraction.csrfToken shouldBe null
                verify(exactly = 1) { tokens.authenticate(minted.plaintext) }
                requireNotNull(harness.apiTokenRepository.findById(minted.id)).lastUsedAt shouldNotBe null
            }
        }
    }

    test("workspace PROXY resolves the valid trusted identity") {
        withTempTree(seed = {}) { root ->
            IndexHarness(root).use { harness ->
                val context = GuardedTestGraph(root, harness).context(AuthMode.PROXY, authConfig = trustedProxyAuth())
                val resolved = captureExtraction(
                    context,
                    mapOf("X-Forwarded-User" to "alice", PROXY_SECRET_HEADER to "s"),
                ) as PrincipalExtraction.Resolved

                resolved.principal shouldBe Principal.Human("proxy", "alice")
                resolved.source shouldBe Source.PROXY
            }
        }
    }

    listOf(
        ReaderExtractionProbe("OFF bearer", AuthMode.OFF, setOf(HttpHeaders.Authorization)),
        ReaderExtractionProbe(
            "OFF combined credentials",
            AuthMode.OFF,
            setOf(HttpHeaders.Authorization, HttpHeaders.Cookie, "X-Forwarded-User", PROXY_SECRET_HEADER),
        ),
        ReaderExtractionProbe("BUILTIN cookie", AuthMode.BUILTIN, setOf(HttpHeaders.Cookie)),
        ReaderExtractionProbe("PROXY identity", AuthMode.PROXY, setOf("X-Forwarded-User", PROXY_SECRET_HEADER)),
    ).forEach { probe ->
        test("reader ${probe.label} resolves anonymously without consulting credential services") {
            withTempTree(seed = {}) { root ->
                IndexHarness(root).use { harness ->
                    val graph = GuardedTestGraph(root, harness)
                    val token = harness.apiTokens.mint("probe", AgentMode.COMMIT)
                    val session = harness.sessionService.create("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
                    val tokenSpy = spyk(harness.apiTokens)
                    val sessionSpy = spyk(harness.sessionService)
                    val context = graph.context(
                        probe.mode,
                        authConfig = if (probe.mode == AuthMode.PROXY) trustedProxyAuth() else AuthConfig(mode = probe.mode),
                        profile = DeploymentProfile.READER,
                        tokens = tokenSpy,
                        auth = AuthServices(sessionSpy, graph.auth.login, graph.auth.setup, graph.auth.admin, graph.auth.rateLimiter),
                    )
                    val headers = mapOf(
                        HttpHeaders.Authorization to "Bearer ${token.plaintext}",
                        HttpHeaders.Cookie to "$SESSION_COOKIE_NAME=${session.plaintext}",
                        "X-Forwarded-User" to "alice",
                        PROXY_SECRET_HEADER to "s",
                    ).filterKeys { it in probe.headers }

                    val extraction = captureExtraction(context, headers)
                    val tokenUntouched = runCatching { verify { tokenSpy wasNot Called } }.isSuccess
                    val sessionUntouched = runCatching { verify { sessionSpy wasNot Called } }.isSuccess
                    assertSoftly {
                        extraction shouldBe PrincipalExtraction.Resolved(Principal.Anonymous)
                        (extraction as PrincipalExtraction.Resolved).principal shouldBeSameInstanceAs Principal.Anonymous
                        withClue("token spy untouched") { tokenUntouched shouldBe true }
                        withClue("session spy untouched") { sessionUntouched shouldBe true }
                        requireNotNull(harness.apiTokenRepository.findById(token.id)).lastUsedAt shouldBe null
                    }
                }
            }
        }
    }
})

private data class ReaderExtractionProbe(val label: String, val mode: AuthMode, val headers: Set<String>)

private fun trustedProxyAuth(): AuthConfig = AuthConfig(
    mode = AuthMode.PROXY,
    trustedProxyCidrs = listOf("127.0.0.1/32"),
    proxySecret = "s",
)

private fun captureExtraction(
    context: RouteContext,
    headers: Map<String, String>,
): PrincipalExtraction {
    var extraction: PrincipalExtraction? = null
    testApplication {
        application {
            plainbaseModule(context)
            routing {
                get("/capture") {
                    extraction = context.extract.invoke(call)
                    call.respondText("ok")
                }
            }
        }
        val response = client.get("/capture") {
            headers.forEach { (name, value) -> header(name, value) }
        }
        response.status shouldBe HttpStatusCode.OK
        response.headers.getAll(HttpHeaders.SetCookie) shouldBe null
        response.bodyAsText() shouldBe "ok"
    }
    return requireNotNull(extraction)
}

private class GuardedTestGraph(
    private val root: Path,
    private val harness: IndexHarness,
) {
    private val policy = PolicyService(
        roles = harness.roleRepository,
        apiTokens = harness.apiTokenRepository,
        audit = harness.auditRepository,
        idProvider = com.plainbase.domain.service.UuidV7IdProvider(),
        clock = kotlin.time.Clock.System,
        mode = PolicyMode.OPEN,
        editableOf = { harness.rootRegistry.byName(it)?.editable == true },
        discussionsEnabledOf = { harness.rootRegistry.byName(it)?.discussionsEnabled == true },
    )
    val auth = harness.authServices(policy)
    val writePipeline: WritePipeline = mockk(relaxed = true)
    val proposalService: ProposalService = mockk(relaxed = true)
    private val searchProvider: SearchProvider = mockk(relaxed = true)
    private val proposalLabeler: ProposalAuthorLabeler = mockk(relaxed = true)
    private val policies = harness.policies
    private val serving = ServingRuntime(
        index = ObservedIndexRuntime(
            builder = harness.builder,
            registry = harness.rootRegistry,
            stores = RootStores(mapOf(RootName.PRIMARY to harness.stores(RootName.PRIMARY))),
            histories = HistoryProviders(mapOf(RootName.PRIMARY to NoOpHistoryProvider)),
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
        pageService = PageService(harness.builder, harness.registry, CitationFactory()),
        searchService = SearchService(searchProvider, harness.builder, harness.availability, policies),
        writePipeline = writePipeline,
        resolver = PageRootResolver(harness.idMap, harness.rootRegistry, policies),
        absence = AbsenceClassifier(harness.idMap, policies),
        proposalService = proposalService,
        proposalLabeler = proposalLabeler,
        agentDirectCommitGlobs = emptyList(),
    )

    fun context(
        mode: AuthMode,
        themes: ThemeStylesheets = BundledThemeStylesheets(embeddedThemeBuiltins()),
        authConfig: AuthConfig = AuthConfig(mode = mode),
        profile: DeploymentProfile = DeploymentProfile.WORKSPACE,
        tokens: ApiTokenService = harness.apiTokens,
        auth: AuthServices = this.auth,
    ): RouteContext = buildGuardedApplication(
        serving = serving,
        security = securityAssembly(
            config = PlainbaseConfig(
                contentDir = root,
                dataDir = root.resolve("guarded-test-data"),
                host = "127.0.0.1",
                port = 8080,
                auth = authConfig,
                profile = profile,
            ),
            policy = policy,
            tokens = tokens,
            auth = auth,
            proxyCsrf = ProxyCsrf(ByteArray(32) { 7 }),
        ),
        transport = TransportSettings(
            maxWriteBodyBytes = PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
            maxAssetBytes = PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
            mcpAllowedHosts = listOf("127.0.0.1", "localhost"),
            mcpAllowedOrigins = listOf("http://127.0.0.1", "http://localhost"),
            secureCookie = false,
        ),
        themes = themes,
    )
}

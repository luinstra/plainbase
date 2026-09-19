package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootName
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.runtime.HistoryProviders
import com.plainbase.frameworks.runtime.ObservedIndexRuntime
import com.plainbase.frameworks.runtime.RootStores
import com.plainbase.frameworks.runtime.ServingRuntime
import com.plainbase.frameworks.security.ProxyCsrf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import io.mockk.confirmVerified
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path

/** Verifies the guarded application's identity, cycle, transport and derived-auth assembly. */
class GuardedApplicationFactoryTest : FunSpec({

    test("the returned graph keeps the supplied holders") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val graph = GuardedTestGraph(root, harness)
                val context = graph.context(AuthMode.BUILTIN)
                val pageId = harness.builder.current.pages.single().id

                context.availability shouldBeSameInstanceAs harness.availability
                context.convergence shouldBeSameInstanceAs harness.convergence
                context.limbo shouldBeSameInstanceAs harness.limbo
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
                val builtinExtraction = captureExtraction(builtin, session.plaintext)
                val builtinResolved = builtinExtraction as PrincipalExtraction.Resolved
                builtinResolved.principal shouldBe Principal.Human("builtin", userId)
                builtinResolved.source shouldBe Source.COOKIE
                builtinResolved.csrfToken?.contentEquals(session.csrfToken) shouldBe true

                off.builtinAuthEnabled shouldBe false
                off.proxyAuthEnabled shouldBe false
                captureExtraction(off, session.plaintext) shouldBe
                    PrincipalExtraction.Resolved(Principal.Anonymous)
            }
        }
    }
})

private fun captureExtraction(
    context: RouteContext,
    cookie: String,
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
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=$cookie")
        }
        response.status shouldBe HttpStatusCode.OK
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
        enforced = false,
        editableOf = { harness.rootRegistry.byName(it)?.editable == true },
    )
    private val auth = harness.authServices(policy)
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

    fun context(mode: AuthMode): RouteContext = buildGuardedApplication(
        serving = serving,
        security = securityAssembly(
            config = PlainbaseConfig(
                contentDir = root,
                dataDir = root.resolve("guarded-test-data"),
                host = "127.0.0.1",
                port = 8080,
                auth = AuthConfig(mode = mode),
            ),
            policy = policy,
            tokens = harness.apiTokens,
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
    )
}

package com.plainbase.frameworks.mcp

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.ProposalFacade
import com.plainbase.domain.service.ReadFacade
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.testRouteContext
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import io.ktor.client.engine.cio.CIO as ClientCio
import io.ktor.client.plugins.sse.SSE as ClientSse
import io.ktor.server.cio.CIO as ServerCio
import io.ktor.server.sse.SSE as ServerSse

private const val UNAUTHORIZED_BODY = "{\"error\":{\"code\":\"unauthorized\",\"message\":\"Authentication required\"}}"

class McpFacadeIdentityTest : FunSpec({
    test("should reject an unauthenticated SSE connection before factory construction") {
        McpFacadeIdentityFixture().use { fixture ->
            val response = fixture.unauthenticatedGet()

            response.status shouldBe 401
            response.body shouldBe UNAUTHORIZED_BODY
            fixture.factoryInvocations shouldBe 0
        }
    }

    test("should pass the captured agent and existing guarded facades through the real SSE mount") {
        McpFacadeIdentityFixture().use { fixture ->
            val result = fixture.listChanges()

            fixture.factoryInvocations shouldBe 1
            result.isErr() shouldBe false
            result.text() shouldBe "{\"proposals\":[]}"
        }
    }
})

private class McpFacadeIdentityFixture : AutoCloseable {
    private val root = Files.createTempDirectory("plainbase-mcp-identity")
    private val searchDir = Files.createTempDirectory("plainbase-mcp-identity-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mcp-identity").apply { isDaemon = true }
    }
    private lateinit var index: IndexHarness
    private lateinit var context: RouteContext
    private lateinit var tokenId: String
    private lateinit var bearer: String
    private var server: EmbeddedServer<*, *>? = null
    private var resolvedPort = 0
    private val recordedInvocations = AtomicInteger()
    private val recordingFailure = AtomicReference<Throwable?>()

    val port: Int
        get() = resolvedPort

    val factoryInvocations: Int
        get() = recordedInvocations.get()

    init {
        try {
            index = IndexHarness(root)
            val minted = index.apiTokens.mint(label = "mcp-identity", mode = AgentMode.PROPOSE)
            tokenId = minted.id
            bearer = minted.plaintext
            context = index.testRouteContext(
                searchProvider = Fts5SearchProvider(searchDb),
                enforced = true,
            )
            server = onThread {
                embeddedServer(ServerCio, host = "127.0.0.1", port = 0) {
                    install(ServerSse)
                    routing { plainbaseMcp(context, ::recordingFactory) }
                }.start(wait = false)
            }
            resolvedPort = blocking { requireNotNull(server).engine.resolvedConnectors().first().port }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun unauthenticatedGet(): HttpResponseSnapshot = blocking {
        HttpClient(ClientCio).use { http ->
            val response = http.get("http://127.0.0.1:$port$MCP_PATH")
            HttpResponseSnapshot(response.status.value, response.bodyAsText())
        }
    }

    fun listChanges() = blocking {
        val http = HttpClient(ClientCio) { install(ClientSse) }
        try {
            val transport = http.mcpSseTransport("http://127.0.0.1:$port$MCP_PATH") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
            }
            val client = Client(Implementation(name = "plainbase-identity-test", version = "0.0.1"))
            try {
                withTimeout(15_000) { client.connect(transport) }
                val result = withTimeout(15_000) { client.call("list_changes") }
                recordingFailure.get()?.let { throw it }
                result
            } finally {
                runCatching { client.close() }
            }
        } finally {
            http.close()
        }
    }

    private fun recordingFactory(
        principal: Principal.Agent,
        read: ReadFacade,
        proposals: ProposalFacade,
        roots: Set<RootName>,
    ): Server {
        recordedInvocations.incrementAndGet()
        runCatching {
            check(principal.tokenId == tokenId) { "MCP factory received a different token id" }
            check(read === context.read) { "MCP factory replaced the guarded read facade" }
            check(proposals === context.proposals) { "MCP factory replaced the guarded proposal facade" }
            check(roots == context.roots) { "MCP factory received a different root-name set" }
        }.onFailure { failure -> recordingFailure.compareAndSet(null, failure) }
        return buildPlainbaseMcpServer(principal, read, proposals, roots)
    }

    private fun <T> onThread(block: () -> T): T = executor.submit(Callable(block)).get()

    private fun <T> blocking(block: suspend CoroutineScope.() -> T): T = onThread { runBlocking(block = block) }

    override fun close() {
        server?.let { bound ->
            runCatching { onThread { bound.stop(gracePeriodMillis = 100, timeoutMillis = 1_000) } }
            server = null
        }
        executor.shutdownNow()
        if (this::index.isInitialized) runCatching { index.close() }
        runCatching { searchDb.close() }
        listOf(searchDir, root).forEach { directory ->
            runCatching {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }
}

private data class HttpResponseSnapshot(val status: Int, val body: String)

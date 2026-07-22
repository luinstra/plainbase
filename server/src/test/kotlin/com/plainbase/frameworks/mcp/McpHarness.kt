package com.plainbase.frameworks.mcp

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.localRoot
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.AmbiguousIdMap
import com.plainbase.frameworks.ktor.plainbaseModule
import com.plainbase.frameworks.ktor.testRouteContext
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

/**
 * The WI-7 enforced-mode MCP harness: a real CIO server serving `plainbaseModule` over an `enforced = true`
 * RouteContext (the `ci-runs-auth-off-blind` rule — under auth.mode=off the whole gate is invisible), driven by a
 * REAL SSE MCP client. There is NO existing SSE `testApplication` harness, so this boots `embeddedServer(CIO, port=0)`
 * on an ephemeral port (the `NativeSpike.mcpSseHandshake` embedded-CIO-server idiom) and drives it end-to-end. Mints a PROPOSE (→ EDITOR)
 * and a READ_ONLY (→ VIEWER) agent token, seeds ONE page (with a broken link, for validate_links), and syncs the
 * search engine so the read tools return real data.
 */
class McpHarness(
    /** Whether the seeded root accepts page writes (ADR-0011 D6). `false` exercises the `root_not_editable` deny. */
    editable: Boolean = true,
    /**
     * C4 window fixture: the roots a FAKE [AmbiguousIdMap] reports as holding the SEEDED page id. Non-empty wraps the
     * real idMap for that one id, which is the only way to pose Ambiguity under `UNIQUE(id)`. Threaded into BOTH the
     * resolver AND the classifier deliberately - a resolver-only fake leaves `requireVerifiedAbsence` reading the real
     * list, and the two would answer from different facts (the REV14 FIX-1 defect).
     */
    ambiguousRoots: List<RootName> = emptyList(),
) : AutoCloseable {

    private val root = Files.createTempDirectory("plainbase-mcp-test")

    /** The (empty) directories backing any EXTRA roots [ambiguousRoots] names - see the registry note in `init`. */
    private val extraDir = Files.createTempDirectory("plainbase-mcp-extra-roots")
    private val searchDir = Files.createTempDirectory("plainbase-mcp-search")
    private val searchDb = SearchDb(searchDir.resolve("search.db"))
    private val index: IndexHarness
    private val server: EmbeddedServer<*, *>

    // All coroutine / blocking-engine work (the client round-trips AND `server.stop`, which itself uses `runBlocking`
    // internally) runs on this dedicated daemon pool — NEVER on the Kotest test coroutine, where a nested
    // `runBlocking` on the shared event loop deadlocks under the full parallel suite (passes in isolation, fails in the
    // suite). A CACHED pool (not single-thread) so a nested `onThread` — e.g. `restGet` called INSIDE a `session`
    // block in the parity test — gets a fresh thread instead of deadlocking on the one busy thread.
    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "mcp-harness").apply { isDaemon = true } }

    private fun <T> onThread(block: () -> T): T = exec.submit(Callable(block)).get()

    private fun <T> blocking(block: suspend CoroutineScope.() -> T): T = onThread { runBlocking(block = block) }

    val port: Int
    val proposeBearer: String
    val proposeTokenId: String
    val readOnlyBearer: String
    val seedPageId: String
    val seedBaseHash: String

    init {
        Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\nSome body with a [broken](missing.md) link.\n")
        val store = LocalContentStore(root)
        val searchProvider = Fts5SearchProvider(searchDb)
        val searchIndexer = SearchIndexer(searchProvider, SectionSplitter())
        index = IndexHarness(
            root,
            contentStore = store,
            listeners = listOf(
                IndexBuilder.PublicationListener { snap, retired ->
                    searchIndexer.sync(snap, retired)
                },
            ),
            searchIndexer = searchIndexer,
            // Every root [ambiguousRoots] names is REGISTERED over an empty directory, because the resolver filters
            // its claimant list to registered roots: an unregistered candidate is dropped and the Ambiguous arm
            // collapses back to One, so the fake alone cannot pose ambiguity.
            rootRegistry = RootRegistry.of(
                listOf(localRoot("main", root, editable = editable)) +
                    ambiguousRoots.filter { it != RootName.MAIN }
                        .map { localRoot(it.value, Files.createDirectories(extraDir.resolve(it.value))) },
            ),
        )
        index.builder.rebuild()
        val page = index.builder.current.pages.single()
        seedPageId = page.id.value
        seedBaseHash = page.contentHash
        val propose = index.apiTokens.mint(label = "propose", mode = AgentMode.PROPOSE)
        proposeBearer = propose.plaintext
        proposeTokenId = propose.id
        readOnlyBearer = index.apiTokens.mint(label = "ro", mode = AgentMode.READ_ONLY).plaintext
        val idMap = if (ambiguousRoots.isEmpty()) index.idMap else AmbiguousIdMap(index.idMap, page.id, ambiguousRoots)
        val ctx = index.testRouteContext(
            searchProvider = searchProvider,
            enforced = true,
            resolver = PageRootResolver(idMap, index.rootRegistry),
            absence = AbsenceClassifier(idMap),
        )
        server = onThread { embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) { plainbaseModule(ctx) }.start(wait = false) }
        port = blocking { server.engine.resolvedConnectors().first().port }
    }

    /** Revoke the PROPOSE token mid-session (the live `modeOf` re-read → denied on the next facade call). */
    fun revokeProposeToken() = index.apiTokens.revoke(proposeTokenId)

    /** The stored proposal summary rows. */
    fun proposalRows() = index.proposalRepository.all()

    /** The fully-typed stored proposal row (carrying the raw `proposedContent` bytes) for the round-trip assertion. */
    fun proposalContentBytes() = requireNotNull(index.proposalRepository.findById(proposalRows().single().id)).proposedContent

    /** Drives the sticky runtime-outage state without depending on an OS-specific unmount in an MCP contract test. */
    fun markMainUnavailable() = index.availability.markUnavailable(RootName.MAIN, UnavailableCause.VANISHED)

    /**
     * Leaves the seeded page durably bound but absent from the next snapshot. A second live page keeps this from
     * becoming the separate empty-corpus tripwire case, so the honest result is absence_unverified rather than
     * root_unavailable.
     */
    fun moveSeedPageToLimbo() {
        Files.writeString(root.resolve("keeper.md"), "---\ntitle: Keeper\n---\n\n# Keeper\n\nstill here\n")
        Files.delete(root.resolve("doc.md"))
        index.builder.rebuild()
    }

    /** Opens an authed SSE MCP session with [bearer] and runs [block] against the connected client. */
    fun <T> session(bearer: String, block: suspend (Client) -> T): T = blocking {
        val http = HttpClient(ClientCIO) { install(SSE) }
        val transport = http.mcpSseTransport("http://127.0.0.1:$port$MCP_PATH") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
        }
        val client = Client(Implementation(name = "plainbase-test", version = "0.0.1"))
        try {
            withTimeout(15_000) { client.connect(transport) }
            block(client)
        } finally {
            runCatching { client.close() }
            http.close()
        }
    }

    /** A raw HTTP GET to the MCP endpoint (the connect-reject assertions need the pre-upgrade status, no stream). */
    fun rawMcpGet(bearer: String? = null, origin: String? = null): HttpResponse = blocking {
        HttpClient(ClientCIO).use { http ->
            http.get("http://127.0.0.1:$port$MCP_PATH") {
                if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                if (origin != null) header(HttpHeaders.Origin, origin)
            }
        }
    }

    /** A REST GET with the bearer (for the REST↔MCP byte-parity comparison). */
    fun restGet(path: String, bearer: String): String = blocking {
        HttpClient(ClientCIO).use { http ->
            http.get("http://127.0.0.1:$port$path") { header(HttpHeaders.Authorization, "Bearer $bearer") }.bodyAsText()
        }
    }

    /** A REST JSON POST with the bearer (for the propose_change structural-parity comparison). */
    fun restPost(path: String, bearer: String, json: String): String = blocking {
        HttpClient(ClientCIO).use { http ->
            http.post("http://127.0.0.1:$port$path") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                contentType(ContentType.Application.Json)
                setBody(json)
            }.bodyAsText()
        }
    }

    override fun close() {
        runCatching { onThread { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) } } // stop's runBlocking off the test thread
        exec.shutdownNow()
        index.close()
        searchDb.close()
        listOf(searchDir, root, extraDir).forEach { dir ->
            runCatching { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }
}

/** The text payload of a (non-structured) tool result — the single `TextContent` block all seven tools emit. */
fun CallToolResult.text(): String = (content.first() as TextContent).text

/** Whether the result is an error result (`isError == true`). */
fun CallToolResult.isErr(): Boolean = isError == true

/** callTool, asserting a non-null result (the SDK returns a nullable CallToolResult). */
suspend fun Client.call(name: String, args: Map<String, Any?> = emptyMap()): CallToolResult =
    requireNotNull(callTool(name, args)) { "callTool($name) returned null" }

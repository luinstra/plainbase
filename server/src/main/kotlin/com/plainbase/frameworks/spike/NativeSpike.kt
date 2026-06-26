package com.plainbase.frameworks.spike

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SectionDocument
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.koin.checkpointModule
import com.plainbase.frameworks.koin.contentModule
import com.plainbase.frameworks.koin.historyModule
import com.plainbase.frameworks.koin.indexModule
import com.plainbase.frameworks.koin.repositoryModule
import com.plainbase.frameworks.koin.restModule
import com.plainbase.frameworks.koin.searchModule
import com.plainbase.frameworks.koin.securityModule
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.plainbaseModule
import com.plainbase.frameworks.mcp.MCP_PATH
import com.plainbase.frameworks.mcp.McpTools
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.security.Argon2PasswordHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.yaml.front.matter.AbstractYamlFrontMatterVisitor
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Full-stack native dependency spike (Phase 0, task 3) — the go/no-go gate for
 * the GraalVM native-image bet. Every load-bearing dependency is exercised with
 * a real assertion, not just "it starts":
 *
 *  1. Ktor CIO HTTP round-trip (server + client) with kotlinx.serialization
 *  2. Koin constructor-DSL wiring resolution
 *  3. SQLDelight query against SQLite
 *  4. SQLite FTS5 through the PRODUCTION SearchDb + Fts5SearchProvider against a
 *     temp-file DB (the search.db access path, ADR-0004): WAL, generation rebuild,
 *     bm25 ordering, MATCH-builder prefix queries, snippet sentinels -> A3 offsets,
 *     a direct highlight() round-trip (the S0-proven aux surface stays in the gate),
 *     per-page replace + indexedState, and the trigram CJK-rescue fallback
 *  5. flexmark Markdown render (frontmatter + GFM + anchors)
 *  6. argon2 hash + verify (Bouncy Castle, pure Java)
 *  7. MCP Kotlin SDK stub handshake (initialize + listTools)
 *  8. MCP SSE-on-CIO server handshake (P3): the in-binary `plainbaseMcp` mount over a real CIO server +
 *     a real SSE MCP client under ENFORCED auth — initialize + listTools (== the seven) + callTool ×2 over
 *     ONE open stream (proving keep-alive/flush work natively, not a single round-trip)
 *
 * The Git layer is NOT spiked here — it ships as the system `git` binary (ADR-0006), not a bundled
 * library, so its native proof is the real-round-trip GitNativeSmokeTest (@Tag("native")), not this set.
 *
 * Runs inside the binary via `plainbase spike`; prints PASS/FAIL per check and
 * exits non-zero on any failure. Also executed by the JVM test suite and by the
 * CI native gate against the native binary.
 */
object NativeSpike {

    data class CheckResult(val name: String, val passed: Boolean, val detail: String)

    fun runAll(): List<CheckResult> = listOf(
        check("ktor-cio-roundtrip") { ktorCioRoundTrip() },
        check("koin-dsl-wiring") { koinWiring() },
        check("sqldelight-query") { sqlDelightQuery() },
        check("sqlite-fts5-match") { fts5Match() },
        check("flexmark-render") { flexmarkRender() },
        check("argon2-hash-verify") { argon2() },
        check("mcp-stub-handshake") { mcpHandshake() },
        check("mcp-sse-handshake") { mcpSseHandshake() },
    )

    fun runAsMain(): Int {
        println("Plainbase full-stack native dependency spike (v${PlainbaseConfig.VERSION})")
        val vm = System.getProperty("java.vm.name")
        val vmVersion = System.getProperty("java.vendor.version") ?: System.getProperty("java.version")
        println("runtime: $vm / $vmVersion")
        val results = runAll()
        for (r in results) {
            println("${if (r.passed) "PASS" else "FAIL"}  ${r.name.padEnd(28)} ${r.detail}")
        }
        val failed = results.count { !it.passed }
        return if (failed == 0) {
            println("SPIKE OK — ${results.size}/${results.size} checks passed")
            0
        } else {
            println("SPIKE FAILED — $failed/${results.size} checks failed")
            1
        }
    }

    private fun check(name: String, block: () -> String): CheckResult = try {
        CheckResult(name, true, block())
    } catch (t: Throwable) {
        val causes = generateSequence(t) { it.cause }.toList()
        val chain = causes.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
        val origin = causes.last().stackTrace.take(12).joinToString("") { "\n        at $it" }
        CheckResult(name, false, chain + origin)
    }

    // ---- 1. Ktor CIO round-trip ----------------------------------------------------------

    @Serializable
    data class EchoPayload(val message: String, val n: Int)

    private fun ktorCioRoundTrip(): String = runBlocking {
        val expected = EchoPayload("plainbase", 42)
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            install(ServerContentNegotiation) { json() }
            routing { get("/spike/echo") { call.respond(expected) } }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            HttpClient(ClientCIO) {
                install(ClientContentNegotiation) { json() }
            }.use { client ->
                val actual: EchoPayload = client.get("http://127.0.0.1:$port/spike/echo").body()
                require(actual == expected) { "expected $expected, got $actual" }
            }
            "CIO server + CIO client + kotlinx.serialization round-trip on port $port"
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
        }
    }

    // ---- 2. Koin DSL wiring --------------------------------------------------------------

    class SpikeGreeter(private val config: PlainbaseConfig) {
        fun greet(): String = "plainbase:${config.port}"
    }

    private fun koinWiring(): String {
        val app = koinApplication {
            modules(
                module {
                    single { PlainbaseConfig.fromEnv(emptyMap()) }
                    single { SpikeGreeter(get()) }
                },
            )
        }
        try {
            val greeting = app.koin.get<SpikeGreeter>().greet()
            require(greeting == "plainbase:${PlainbaseConfig.DEFAULT_PORT}") { "unexpected wiring result: $greeting" }
        } finally {
            app.close()
        }
        return "constructor-DSL graph resolved transitively (Config -> Greeter)"
    }

    // ---- 3. SQLDelight query -------------------------------------------------------------

    private fun sqlDelightQuery(): String {
        val driver = DatabaseFactory.createInMemoryDriver()
        driver.use {
            val db = DatabaseFactory.createDatabase(driver)
            db.appMetaQueries.upsert("spike.key", "spike-value")
            db.appMetaQueries.upsert("spike.key", "spike-value-2") // exercises ON CONFLICT
            val value = db.appMetaQueries.selectByKey("spike.key").executeAsOne()
            require(value == "spike-value-2") { "expected spike-value-2, got $value" }
        }
        return "generated typesafe query + upsert against sqlite-jdbc"
    }

    // ---- 4. FTS5 via the production search stack (search.db access path, ADR-0004) -------

    /**
     * Chunk-S2 retarget: the check exercises the PRODUCTION `SearchDb` + `Fts5SearchProvider`
     * (raw JDBC over a temp-file DB) instead of inline SQL — proving inside the native image the
     * exact classes the server runs: WAL open, generation rebuild, bm25 weights ranking a title
     * hit over repeated body hits, the MATCH builder's quoting/prefix-star, snippet sentinels
     * converted to A3 offsets, the `highlight()` round-trip (S0 surface, kept proven), per-page
     * replace + indexedState, and the trigram CJK-rescue fallback (S0 verdict PASS, now
     * production code). Check name kept (`sqlite-fts5-match`).
     */
    private fun fts5Match(): String {
        val dir = Files.createTempDirectory("plainbase-spike-search")
        val dbPath = dir.resolve("search.db")
        try {
            SearchDb(dbPath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.rebuild(sequenceOf(spikeDeployPage(), spikeWelcomePage(), spikeCjkPage()))

                // bm25 column weights steer ordering: the title-hit page outranks the page whose
                // BODY repeats the term; scores are finite (negated bm25), descending.
                val deploy = provider.search(SearchQuery("deploy", limit = 10, offset = 0))
                require(deploy.total == 2L) { "expected 2 'deploy' hits, got ${deploy.total}" }
                require(deploy.hits.first().pageId.value == SPIKE_DEPLOY_ID) { "title weight did not win: ${deploy.hits}" }
                require(deploy.hits.all { it.score.isFinite() }) { "non-finite score in ${deploy.hits}" }
                require(deploy.hits.zipWithNext().all { (a, b) -> a.score >= b.score }) { "scores not descending: ${deploy.hits}" }

                // Prefix matching: the MATCH builder stars the final token ("deplo"*).
                require(provider.search(SearchQuery("deplo", 10, 0)).total == 2L) { "prefix query missed" }

                // Snippet sentinels -> A3 offsets: markers never escape, ranges extract the match.
                val kube = provider.search(SearchQuery("kubernetes", 10, 0)).hits.single()
                require('\u0001' !in kube.snippet && '\u0002' !in kube.snippet) { "sentinels leaked: ${kube.snippet}" }
                require(kube.highlights.isNotEmpty()) { "no highlight offsets for the kubernetes hit" }
                val marked = kube.highlights.map { kube.snippet.substring(it.start, it.end) }
                require(marked.all { it.equals("kubernetes", ignoreCase = true) }) { "offsets marked $marked" }

                // Trigram CJK rescue: unicode61 cannot match ガイド inside 日本語ガイド; the fallback can.
                val cjk = provider.search(SearchQuery("ガイド", 10, 0))
                require(cjk.hits.singleOrNull()?.pageId?.value == SPIKE_CJK_ID) { "CJK rescue missed: ${cjk.hits}" }

                // highlight() aux-function round-trip (the S0-proven surface stays in the gate even
                // though the provider serves snippets via snippet() — plan §B5 names all three):
                // a second WAL connection reads the same file the provider just populated.
                val highlighted = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { probe ->
                    probe.createStatement().use { statement ->
                        val sql = "SELECT highlight(section_fts, 2, char(1), char(2)) FROM section_fts " +
                            "WHERE section_fts MATCH '\"kubernetes\"'"
                        statement.executeQuery(sql).use { rows ->
                            rows.next()
                            rows.getString(1)
                        }
                    }
                }
                require("\u0001kubernetes\u0002" in highlighted) { "highlight() marked $highlighted" }
                require(highlighted.replace("\u0001", "").replace("\u0002", "") == spikeWelcomePage().sections.single().body) {
                    "highlight() round-trip mangled the body: $highlighted"
                }

                // Per-page replace + indexedState (the engine-truth diff base).
                provider.index(listOf(spikeWelcomePage(contentHash = "sha256:replaced", body = "deploy on metal only")))
                require(provider.search(SearchQuery("kubernetes", 10, 0)).total == 0L) { "replaced section still matches" }
                val state = provider.indexedState().mapKeys { it.key.value }
                require(state[SPIKE_WELCOME_ID]?.contentHash == "sha256:replaced") { "indexedState stale: $state" }
            }
            val journalMode = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { probe ->
                probe.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode").use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                }
            }
            require(journalMode.equals("wal", ignoreCase = true)) { "journal_mode=WAL not persisted, got $journalMode" }
            return "production SearchDb + Fts5SearchProvider on a temp-file DB: WAL, bm25 ordering, MATCH builder prefix, " +
                "snippet->A3 offsets, highlight() round-trip, per-page replace, indexedState; " +
                "trigram fallback=PASS (ガイド found in 日本語ガイド)"
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private const val SPIKE_DEPLOY_ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    private const val SPIKE_WELCOME_ID = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01"
    private const val SPIKE_CJK_ID = "0197c2d1-6f3b-7c45-8d2e-3a7b9f5c8e02"

    private fun spikeDeployPage() =
        spikePage(SPIKE_DEPLOY_ID, "guides/deploy-guide.md", "Deploy Guide", "sha256:deploy", "Introduction to the knowledge base")

    private fun spikeWelcomePage(
        contentHash: String = "sha256:welcome",
        body: String = "deploy everywhere: deploy with kubernetes, deploy with docker, deploy on metal",
    ) = spikePage(SPIKE_WELCOME_ID, "index.md", "Welcome", contentHash, body)

    private fun spikeCjkPage() = spikePage(SPIKE_CJK_ID, "notes/cjk.md", "日本語ガイド", "sha256:cjk", "これは 日本語ガイド のサンプルページです")

    private fun spikePage(id: String, path: String, title: String, contentHash: String, body: String): PageDocuments {
        val pageId = PageId.require(id)
        val treePath = TreePath.require(path)
        val section = SectionDocument(
            pageId = pageId,
            headingId = null,
            title = title,
            heading = null,
            headingPath = emptyList(),
            body = body,
            tags = emptyList(),
            owner = null,
            aliases = emptyList(),
            path = treePath,
            status = "active",
        )
        return PageDocuments(pageId = pageId, contentHash = contentHash, path = treePath, sections = listOf(section))
    }

    // ---- 5. flexmark ---------------------------------------------------------------------

    private fun flexmarkRender(): String {
        val options = MutableDataSet()
            .set(
                Parser.EXTENSIONS,
                listOf(
                    YamlFrontMatterExtension.create(),
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    AnchorLinkExtension.create(),
                ),
            )
        val parser = Parser.builder(options).build()
        val renderer = HtmlRenderer.builder(options).build()
        val markdown = """
            |---
            |title: Spike Page
            |tags: [spike]
            |---
            |# Deploy Guide
            |
            || engine | tier |
            ||--------|------|
            || fts5   | default |
            |
            |~~old~~ new
        """.trimMargin()
        val document = parser.parse(markdown)
        val frontmatter = AbstractYamlFrontMatterVisitor().apply { visit(document) }.data
        require(frontmatter["title"]?.firstOrNull() == "Spike Page") { "frontmatter not parsed: $frontmatter" }
        val html = renderer.render(document)
        require(html.contains("<h1")) { "missing h1: $html" }
        require(html.contains("<table>")) { "missing GFM table: $html" }
        require(html.contains("<del>old</del>")) { "missing strikethrough: $html" }
        require(html.contains("id=\"deploy-guide\"")) { "missing heading anchor id: $html" }
        require(!html.contains("title: Spike Page")) { "frontmatter leaked into body: $html" }
        return "frontmatter + GFM table + strikethrough + anchor ids rendered"
    }

    // ---- 6. argon2 -----------------------------------------------------------------------

    private fun argon2(): String {
        val hasher = Argon2PasswordHasher(memoryKb = 16384, iterations = 2)
        val password = "correct horse battery staple".toCharArray()
        val encoded = hasher.hash(password)
        require(encoded.startsWith("\$argon2id\$v=19\$")) { "unexpected encoding: $encoded" }
        require(hasher.verify(password, encoded)) { "verify(correct password) returned false" }
        require(!hasher.verify("wrong password".toCharArray(), encoded)) { "verify(wrong password) returned true" }
        require(hasher.hash(password) != encoded) { "two hashes of same password were identical (salt missing?)" }
        return "argon2id hash + verify + reject + salted uniqueness (Bouncy Castle)"
    }

    // ---- 7. MCP stub handshake -----------------------------------------------------------

    private fun mcpHandshake(): String = runBlocking {
        val server = Server(
            Implementation(name = "plainbase-spike", version = PlainbaseConfig.VERSION),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        server.addTool(name = "ping", description = "Spike liveness tool") { _ ->
            CallToolResult(content = listOf(TextContent("pong")))
        }

        // Wire client <-> server through in-process pipes (stdio transports over PipedStreams).
        val clientToServer = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient)

        val serverTransport = StdioServerTransport(
            serverIn.asSource().buffered(),
            serverToClient.asSink().buffered(),
        ) { /* defaults */ }
        val clientTransport = StdioClientTransport(
            clientIn.asSource().buffered(),
            clientToServer.asSink().buffered(),
        )

        val session = server.createSession(serverTransport)
        val client = Client(Implementation(name = "spike-client", version = "0.0.1"))
        try {
            withTimeout(15_000) { client.connect(clientTransport) }
            val serverInfo = client.serverVersion
            require(serverInfo?.name == "plainbase-spike") { "unexpected server info: $serverInfo" }
            val tools = withTimeout(15_000) { client.listTools() }
            require(tools.tools.any { it.name == "ping" }) { "ping tool not listed: ${tools.tools.map { it.name }}" }
            "initialize handshake + listTools over in-process stdio transport"
        } finally {
            runCatching { client.close() }
            runCatching { session.close() }
            runCatching { server.close() }
        }
    }

    // ---- 8. MCP SSE-on-CIO server handshake (P3) -----------------------------------------

    /**
     * The P3 native bet: the in-binary `plainbaseMcp` mount, served over a REAL CIO server under ENFORCED auth, driven
     * by a REAL SSE MCP client. Wires the production Koin graph (config pointed at a temp tree, auth.mode=builtin so
     * `enforced=true`, git off) so the spike exercises the SAME `buildRouteContext`/`plainbaseMcp` the server uses;
     * mints a PROPOSE (-> EDITOR) agent token, opens an authed SSE stream, and asserts initialize + listTools(== the
     * seven) + TWO callTool round-trips (list_changes + read_page) over ONE open stream — proving keep-alive / SSE
     * flush work in the native image, not just a single round-trip. The SSE/MCP-server reflection this reaches is what
     * the committed `traceMcpSseMetadata` delta covers.
     */
    private fun mcpSseHandshake(): String = runBlocking {
        val contentDir = Files.createTempDirectory("plainbase-spike-mcp-content")
        val dataDir = Files.createTempDirectory("plainbase-spike-mcp-data")
        Files.writeString(contentDir.resolve("index.md"), "---\ntitle: Spike Home\n---\n\n# Spike Home\n\nMCP SSE spike body.\n")
        val config = PlainbaseConfig.fromEnv(
            mapOf(
                "CONTENT_DIR" to contentDir.toString(),
                "DATA_DIR" to dataDir.toString(),
                "PLAINBASE_HOST" to "127.0.0.1",
                "PLAINBASE_AUTH_MODE" to "builtin", // -> enforced=true, so the connect gate + facade gate are REAL
                "PLAINBASE_GIT_ENABLED" to "false", // no git gate in the spike
            ),
        )
        val app = koinApplication {
            modules(
                module { single { config } },
                contentModule, repositoryModule, securityModule, indexModule, checkpointModule, searchModule, historyModule, restModule,
            )
        }
        val koin = app.koin
        try {
            val builder = koin.get<IndexBuilder>()
            builder.rebuild()
            val seedPageId = builder.current.pages.first().id.value
            val minted = koin.get<ApiTokenService>().mint(label = "spike", mode = AgentMode.PROPOSE)
            val ctx = koin.get<RouteContext>()
            val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
                plainbaseModule(ctx)
            }.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().first().port
                HttpClient(ClientCIO) { install(ClientSSE) }.use { httpClient ->
                    val transport = httpClient.mcpSseTransport("http://127.0.0.1:$port$MCP_PATH") {
                        headers.append("Authorization", "Bearer ${minted.plaintext}")
                    }
                    val client = Client(Implementation(name = "plainbase-sse-spike", version = "0.0.1"))
                    try {
                        withTimeout(15_000) { client.connect(transport) }
                        require(client.serverVersion?.name == "plainbase") { "unexpected server info: ${client.serverVersion}" }
                        val names = withTimeout(15_000) { client.listTools() }.tools.map { it.name }.toSet()
                        require(names == McpTools.ALL) { "MCP tool surface drift: $names (expected ${McpTools.ALL})" }
                        // TWO calls over the SAME open stream (keep-alive / SSE-flush proof, not one round-trip).
                        val listText = (
                            withTimeout(15_000) { client.callTool("list_changes", emptyMap<String, Any?>()) }
                                ?.content?.firstOrNull() as? TextContent
                            )?.text
                        require(listText?.contains("\"proposals\"") == true) { "list_changes over SSE failed: $listText" }
                        val readText = (
                            withTimeout(15_000) { client.callTool("read_page", mapOf("id" to seedPageId)) }
                                ?.content?.firstOrNull() as? TextContent
                            )?.text
                        require(readText?.contains(seedPageId) == true) { "read_page over SSE failed: $readText" }
                        "SSE-on-CIO MCP: initialize + listTools(==${names.size}) + list_changes + read_page over one stream (port $port)"
                    } finally {
                        runCatching { client.close() }
                    }
                }
            } finally {
                server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
            }
        } finally {
            app.close()
            Files.walk(contentDir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            Files.walk(dataDir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

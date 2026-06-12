package com.plainbase.frameworks.spike

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SectionDocument
import com.plainbase.frameworks.config.PlainbaseConfig
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
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
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
 *  5. JGit init/commit/log/diff in a temp dir
 *  6. flexmark Markdown render (frontmatter + GFM + anchors)
 *  7. argon2 hash + verify (Bouncy Castle, pure Java)
 *  8. MCP Kotlin SDK stub handshake (initialize + listTools)
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
        check("jgit-init-commit-log-diff") { jgit() },
        check("flexmark-render") { flexmarkRender() },
        check("argon2-hash-verify") { argon2() },
        check("mcp-stub-handshake") { mcpHandshake() },
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

    // ---- 5. JGit -------------------------------------------------------------------------

    private fun jgit(): String {
        val dir = Files.createTempDirectory("plainbase-spike-git").toFile()
        try {
            Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
                fun commit(message: String) = git.commit()
                    .setMessage(message)
                    .setAuthor("Spike", "spike@plainbase.local")
                    .setCommitter("Spike", "spike@plainbase.local")
                    .setSign(false)
                    .call()
                val file = dir.resolve("notes.md")
                file.writeText("# Notes\n\nhello\n")
                git.add().addFilepattern("notes.md").call()
                val c1 = commit("init")
                file.writeText("# Notes\n\nhello\nworld\n")
                git.add().addFilepattern("notes.md").call()
                val c2 = commit("update")
                val log = git.log().call().toList()
                require(log.size == 2) { "expected 2 commits, got ${log.size}" }
                val out = ByteArrayOutputStream()
                DiffFormatter(out).use { df ->
                    df.setRepository(git.repository)
                    df.scan(c1.tree, c2.tree).forEach { df.format(it) }
                }
                val diff = out.toString(Charsets.UTF_8)
                require(diff.contains("+world")) { "diff missing '+world':\n$diff" }
            }
            return "init -> 2 commits -> log -> diff (+world) in temp repo"
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 6. flexmark ---------------------------------------------------------------------

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

    // ---- 7. argon2 -----------------------------------------------------------------------

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

    // ---- 8. MCP stub handshake -----------------------------------------------------------

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
}

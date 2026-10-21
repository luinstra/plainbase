package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.DocumentResolution
import com.plainbase.domain.service.ReaderPage
import com.plainbase.domain.service.ReaderPages
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.domain.service.TreeBuilder
import com.plainbase.domain.service.assertReaderCorpus
import com.plainbase.domain.service.readerCorpus
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.config.SiteConfig
import com.plainbase.frameworks.ktor.routes.ReaderDocument
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

class ReaderDocumentTest : FunSpec({
    test("reader builds each root document tree once per snapshot when roots alternate") {
        withTempTree({ writePage(it, "index.md", ReaderPages.R.markdown) }) { docs ->
            withTempTree({ writePage(it, "index.md", ReaderPages.G.markdown) }) { extra ->
                MultiRootRestHarness(listOf(testRoot("docs", docs), testRoot("extra", extra))).use { harness ->
                    harness.boot()
                    val read = harness.index.testRouteContext(
                        searchProvider = harness.searchProvider,
                        authMode = AuthMode.OFF,
                        profile = DeploymentProfile.READER,
                    ).read
                    val roots = listOf(RootName.PRIMARY, RootName.require("extra"))
                    val first = harness.builder.current
                    mockkObject(TreeBuilder)
                    try {
                        repeat(2) { publication ->
                            if (publication > 0) harness.builder.rebuild()
                            val snapshot = harness.builder.current
                            if (publication > 0) snapshot shouldNotBeSameInstanceAs first
                            val initial = read.document(Principal.Anonymous, roots[0], null).shouldBeInstanceOf<DocumentResolution.Serve>()
                            initial.page shouldBe snapshot.section(roots[0]).pages.single()
                            verify(exactly = 0) { TreeBuilder.build(refEq(snapshot), roots[1], any()) }
                            listOf(roots[1], roots[0], roots[1], roots[0]).forEach { root ->
                                val served = read.document(Principal.Anonymous, root, null).shouldBeInstanceOf<DocumentResolution.Serve>()
                                served.page shouldBe snapshot.section(root).pages.single()
                            }
                            roots.forEach { root ->
                                verify(exactly = 1) { TreeBuilder.build(refEq(snapshot), root, any()) }
                            }
                        }
                    } finally {
                        unmockkObject(TreeBuilder)
                    }
                }
            }
        }
    }

    listOf("<title>Plainbase</title>", "</head>", "<div id=\"root\"></div>").forEach { anchor ->
        test("reader template refuses a missing $anchor at construction") {
            val shell = requireNotNull(bundledShellHtml())
            val position = shell.indexOf(anchor)
            val missing = shell.substring(0, position) + shell.substring(position + anchor.length)
            shouldThrow<IllegalArgumentException> { ReaderDocument(missing, documentSite) }
        }

        test("reader template refuses a repeated $anchor at construction") {
            val shell = requireNotNull(bundledShellHtml())
            shouldThrow<IllegalArgumentException> { ReaderDocument(shell + anchor, documentSite) }
        }
    }

    fun corpusTest(
        site: SiteConfig = documentSite,
        block: suspend ApplicationTestBuilder.(RestHarness) -> Unit,
    ) = withTempTree(::readerCorpus) { root ->
        readerTest(root, site = site) { harness ->
            assertReaderCorpus(harness.builder.current, harness.registry)
            block(harness)
        }
    }

    fun hostileTest(
        site: SiteConfig = documentSite,
        block: suspend ApplicationTestBuilder.(RestHarness) -> Unit,
    ) = withTempTree(::hostileCorpus) { root ->
        readerTest(root, site = site) { harness ->
            DocumentPages.sources.forEach { (page, source) ->
                val indexed = requireNotNull(harness.builder.current.pageAt(page.rooted))
                indexed.path shouldBe TreePath.require(page.path)
                indexed.markdown shouldBe source
            }
            block(harness)
        }
    }

    val served = listOf(
        "/docs/guides/alpha" to ReaderPages.A,
        "/docs/shadow/index" to ReaderPages.SI,
        "/docs/named" to ReaderPages.N,
        "/docs/guides" to ReaderPages.G,
        "/docs" to ReaderPages.R,
    )
    served.forEach { (path, page) ->
        test("reader document $path carries the exact indexed article and page identity") {
            corpusTest {
                val client = restClient()
                val response = client.get(path)
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().assertInitialArticle(client.htmlFor(page.id), page.id)
            }
        }

        test("reader document $path uses the authored title and configured site name") {
            corpusTest {
                restClient().get(path).bodyAsText() shouldContain "<title>${page.title} \u00b7 Example Docs</title>"
            }
        }

        if (page.description == null) {
            test("reader document $path omits an absent description") {
                corpusTest {
                    restClient().get(path).bodyAsText().substringBefore("<body>") shouldNotContain "name=\"description\""
                }
            }
        }
    }

    test("reader root document includes the authored description") {
        corpusTest {
            restClient().get("/docs").bodyAsText() shouldContain "<meta name=\"description\" content=\"Root page\">"
        }
    }

    listOf("/docs/guides", "/docs/shadow/index", "/docs/renamed").forEach { path ->
        test("reader document $path uses only the configured canonical origin") {
            corpusTest {
                restClient().get(path).bodyAsText() shouldContain "<link rel=\"canonical\" href=\"https://docs.example.test$path\">"
            }
        }
    }

    test("reader document falls back to Plainbase when the site name is unset") {
        corpusTest(site = SiteConfig()) {
            restClient().get("/docs/guides/alpha").bodyAsText() shouldContain "<title>Alpha \u00b7 Plainbase</title>"
        }
    }

    test("reader document omits canonical and absolute head URLs when origin is unset") {
        corpusTest(site = SiteConfig()) {
            val head = restClient().get("/docs/guides/alpha").bodyAsText().substringBefore("<body>")
            head shouldNotContain "rel=\"canonical\""
            head shouldNotContain "https://"
            head shouldNotContain "http://"
        }
    }

    listOf("/docs/guides/alpha", "/docs/guides/index").forEach { path ->
        test("reader document and redirect $path ignore hostile host headers") {
            corpusTest {
                val hosts = mutableListOf<Pair<String?, String?>>()
                application {
                    install(
                        createApplicationPlugin("ReaderHostProbe") {
                            onCall { call ->
                                hosts += call.request.headers[HttpHeaders.Host] to call.request.headers["X-Forwarded-Host"]
                            }
                        },
                    )
                }
                val client = restClient()
                val bare = client.get(path)
                val hostile = client.get(path) {
                    header(HttpHeaders.Host, "evil.example")
                    header("X-Forwarded-Host", "evil.example")
                }
                hosts.last() shouldBe ("evil.example" to "evil.example")
                hostile.status shouldBe bare.status
                hostile.headers.entries().associate { it.key.lowercase() to it.value }.minus("date") shouldBe
                    bare.headers.entries().associate { it.key.lowercase() to it.value }.minus("date")
                hostile.bodyAsText() shouldBe bare.bodyAsText()
                if (path.endsWith("/index")) {
                    hostile.status shouldBe HttpStatusCode.MovedPermanently
                    hostile.headers[HttpHeaders.Location] shouldBe "/docs/guides"
                }
            }
        }
    }

    test("reader tricky article preserves replacement syntax and template anchor text byte for byte") {
        hostileTest {
            val client = restClient()
            val html = client.htmlFor(DocumentPages.tricky.id)
            html shouldContain "\$1"
            html shouldContain "\\path"
            client.get("/docs/tricky").bodyAsText().assertInitialArticle(html, DocumentPages.tricky.id)
        }
    }

    test("reader title escapes hostile metadata without introducing document markup") {
        hostileTest {
            val client = restClient()
            client.assertHostileMetadata()
            val document = client.get("/docs/hostile").bodyAsText()
            document.split("</title>").size - 1 shouldBe 1
            document shouldNotContain "<script>x()"
            val title = document.substringAfter("<title>").substringBefore("</title>")
            title shouldContain "&lt;/title&gt;"
            title shouldContain "&quot;&gt;&lt;script&gt;x()"
            title shouldBe "$ESCAPED_PAYLOAD \u00b7 Example Docs"
        }
    }

    test("reader description escapes hostile metadata within its quoted attribute") {
        hostileTest {
            val client = restClient()
            client.assertHostileMetadata()
            val document = client.get("/docs/hostile").bodyAsText()
            document.split("</title>").size - 1 shouldBe 1
            document shouldNotContain "<script>x()"
            val description = document.substringAfter("<meta name=\"description\" content=\"", "").substringBefore("\">")
            description shouldContain "&lt;/title&gt;"
            description shouldContain "&quot;&gt;&lt;script&gt;x()"
            description shouldBe ESCAPED_PAYLOAD
        }
    }

    test("reader title escapes the configured site name") {
        hostileTest(site = SiteConfig(name = "A & B <docs>")) {
            restClient().get("/docs").bodyAsText() shouldContain "<title>Root \u00b7 A &amp; B &lt;docs&gt;</title>"
        }
    }

    test("reader page retains the exact bootstrap bytes and shell CSP") {
        corpusTest {
            val client = restClient()
            client.get("/docs/guides/alpha").assertBootstrap(client.get("/index.html"))
        }
    }

    test("reader escaped inline script content adds no executable inline script") {
        hostileTest {
            val client = restClient()
            client.get("/docs/inline").assertBootstrap(client.get("/index.html"))
        }
    }
})

private val documentSite = SiteConfig(canonicalOrigin = "https://docs.example.test", name = "Example Docs")
private const val HOSTILE_PAYLOAD = "</title>\"><script>x()</script>"
private const val ESCAPED_PAYLOAD = "&lt;/title&gt;&quot;&gt;&lt;script&gt;x()&lt;/script&gt;"

private object DocumentPages {
    private val ids = TestIdProvider()
    val root = ReaderPage(ids.next(), "index.md", "Root")
    val hostile = ReaderPage(ids.next(), "hostile.md", HOSTILE_PAYLOAD, description = HOSTILE_PAYLOAD)
    val inline = ReaderPage(ids.next(), "inline.md", "Inline")
    val tricky = ReaderPage(ids.next(), "tricky.md", "Tricky")
    val sources = mapOf(
        root to root.markdown,
        hostile to "---\nid: ${hostile.id.value}\ntitle: '$HOSTILE_PAYLOAD'\ndescription: '$HOSTILE_PAYLOAD'\n---\n\n# Hostile\n",
        inline to inline.markdown + "\n<script>z()</script>\n",
        tricky to tricky.markdown + "\nLiteral \$1 and \\path, </head> and <div id=\"root\"></div>.\n\n" +
            "Inline `\$1`, `\\path`, `</head>` and `<div id=\"root\"></div>`.\n",
    )
}

private fun hostileCorpus(root: Path) {
    DocumentPages.sources.forEach { (page, source) -> writePage(root, page.path, source) }
}

private suspend fun HttpClient.pageJson(id: PageId, suffix: String = ""): JsonObject {
    val response = get("/api/v1/pages/${id.value}$suffix")
    response.status shouldBe HttpStatusCode.OK
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
}

private suspend fun HttpClient.htmlFor(id: PageId): String = pageJson(id, "/html").getValue("html").jsonPrimitive.content

private suspend fun HttpClient.assertHostileMetadata() {
    val page = pageJson(DocumentPages.hostile.id)
    page.getValue("title").jsonPrimitive.content shouldBe HOSTILE_PAYLOAD
    page.getValue("frontmatter").jsonObject.getValue("description").jsonPrimitive.content shouldBe HOSTILE_PAYLOAD
}

private fun String.assertInitialArticle(expected: String, id: PageId) {
    val marker = "<article data-pb-initial>"
    split(marker).size - 1 shouldBe 1
    val article = substringAfter(marker).substringBeforeLast("</article></div>")
    article.toByteArray(Charsets.UTF_8) shouldBe expected.toByteArray(Charsets.UTF_8)
    initialPageIds(this) shouldBe listOf(id.value)
}

internal fun initialPageIds(document: String): List<String> =
    Regex("<meta name=\"plainbase-page-id\" content=\"([^\"]+)\">").findAll(document).map { it.groupValues[1] }.toList()

private suspend fun HttpResponse.assertBootstrap(shell: HttpResponse) {
    val document = bodyAsText()
    val shellHtml = shell.bodyAsText()
    inlineScriptHash(document) shouldBe inlineScriptHash(shellHtml)
    document.substringAfter("<script>").substringBefore("</script>").toByteArray(Charsets.UTF_8) shouldBe
        shellHtml.substringAfter("<script>").substringBefore("</script>").toByteArray(Charsets.UTF_8)
    headers["Content-Security-Policy"] shouldBe shell.headers["Content-Security-Policy"]
}

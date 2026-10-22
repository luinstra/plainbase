package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.ReaderPage
import com.plainbase.domain.service.ReaderPages
import com.plainbase.domain.service.assertReaderCorpus
import com.plainbase.domain.service.bareCorpus
import com.plainbase.domain.service.limboCorpus
import com.plainbase.domain.service.readerCorpus
import com.plainbase.domain.service.withTempTree
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.nio.file.Files

class ReaderCacheControlTest : FunSpec({
    fun corpusTest(block: suspend ApplicationTestBuilder.(RestHarness) -> Unit) = withTempTree(::readerCorpus) { root ->
        readerTest(root) { harness ->
            assertReaderCorpus(harness.builder.current, harness.registry)
            block(harness)
        }
    }

    val documents = listOf(
        CacheDocument("/docs/guides/alpha", ReaderPages.A),
        CacheDocument("/docs/guides/index", ReaderPages.G, "/docs/guides"),
        CacheDocument("/docs/index", ReaderPages.R, "/docs"),
        CacheDocument("/docs/renamed/index", ReaderPages.CU, "/docs/renamed"),
        CacheDocument("/docs/shadow/index", ReaderPages.SI),
        CacheDocument("/docs/old/alpha", ReaderPages.A, "/docs/guides/alpha"),
        CacheDocument("/docs/old-guides", ReaderPages.G, "/docs/guides"),
        CacheDocument("/docs/named", ReaderPages.N),
        CacheDocument("/docs/named/index", ReaderPages.N, "/docs/named"),
        CacheDocument("/docs/ref", ReaderPages.O, "/docs/other"),
        CacheDocument("/docs/ref/index", ReaderPages.RF),
        CacheDocument("/docs/guides", ReaderPages.G),
        CacheDocument("/docs/renamed", ReaderPages.CU),
        CacheDocument("/docs", ReaderPages.R),
        CacheDocument("/docs/guides/alpha/", ReaderPages.A),
        CacheDocument("/docs/guides/%61lpha", ReaderPages.A),
    )
    listOf("text/html", "text/markdown").forEach { accept ->
        documents.forEach { row ->
            test("document cache ${row.path} for $accept") {
                corpusTest {
                    val response = restClient().get(row.path) { header(HttpHeaders.Accept, accept) }
                    val body = response.bodyAsText()
                    val redirect = accept == "text/html" && row.location != null
                    assertSoftly {
                        response.status shouldBe if (redirect) HttpStatusCode.MovedPermanently else HttpStatusCode.OK
                        response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe
                            setOf(if (redirect || accept == "text/markdown") "no-store" else "no-cache")
                        if (redirect) {
                            response.headers[HttpHeaders.Location] shouldBe row.location
                        } else if (accept == "text/markdown") {
                            body shouldBe row.page.markdown
                        } else {
                            body shouldContain "<meta name=\"plainbase-page-id\" content=\"${row.page.id.value}\">"
                        }
                    }
                }
            }
        }

        listOf("/docs/deep", "/docs/Guides/Alpha", "/docs/nope", "/docs/custom/index", "/nope/x", "/docs/%2e%2e", "/docs/%00")
            .forEach { path ->
                test("missing or listing cache $path for $accept") {
                    corpusTest {
                        val response = restClient().get(path) { header(HttpHeaders.Accept, accept) }
                        val body = response.bodyAsText()
                        val html = accept == "text/html"
                        response.status shouldBe if (html && path == "/docs/deep") HttpStatusCode.OK else HttpStatusCode.NotFound
                        response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf(if (html) "no-cache" else "no-store")
                        if (html) body shouldBe requireNotNull(bundledShellHtml()) else body shouldContain "\"code\":\"page_not_found\""
                    }
                }
            }

        test("bare root without landing cache for $accept") {
            withTempTree(::bareCorpus) { root ->
                readerTest(root) { harness ->
                    assertReaderCorpus(harness.builder.current, harness.registry, listOf(ReaderPages.top))
                    val response = restClient().get("/docs") { header(HttpHeaders.Accept, accept) }
                    val body = response.bodyAsText()
                    val html = accept == "text/html"
                    response.status shouldBe if (html) HttpStatusCode.OK else HttpStatusCode.NotFound
                    response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf(if (html) "no-cache" else "no-store")
                    if (html) body shouldBe requireNotNull(bundledShellHtml()) else body shouldContain "\"code\":\"page_not_found\""
                }
            }
        }

        listOf("/docs/guides/alpha", "/docs").forEach { path ->
            test("unavailable root cache $path for $accept") {
                corpusTest { harness ->
                    harness.availability.markUnavailable(RootName.PRIMARY, UnavailableCause.VANISHED)
                    val response = restClient().get(path) { header(HttpHeaders.Accept, accept) }
                    val body = response.bodyAsText()
                    response.status shouldBe HttpStatusCode.ServiceUnavailable
                    response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe
                        setOf(if (accept == "text/html") "no-cache" else "no-store")
                    if (accept == "text/html") {
                        body shouldBe requireNotNull(bundledShellHtml())
                    } else {
                        body shouldContain "\"code\":\"root_unavailable\""
                    }
                }
            }
        }

        test("bound missing alias and its live landing child cache for $accept") {
            withTempTree(::limboCorpus) { root ->
                readerTest(root, seed = { idMap ->
                    idMap.bind(RootedPath(RootName.PRIMARY, TreePath.require("gone.md")), ReaderPages.goneId, materialized = false)
                }) { harness ->
                    assertReaderCorpus(harness.builder.current, harness.registry, listOf(ReaderPages.R, ReaderPages.RL))
                    val gone = RootedPageId(RootName.PRIMARY, ReaderPages.goneId)
                    val alias = RootedPath(RootName.PRIMARY, TreePath.require("ref2"))
                    harness.builder.current.pageAt(gone)?.path shouldBe TreePath.require("gone.md")
                    harness.registry.find(alias) shouldBe gone
                    Files.delete(root.resolve("gone.md"))
                    harness.builder.rebuild()
                    harness.builder.current.pageAt(gone) shouldBe null
                    harness.idMap.livePathOf(ReaderPages.goneId) shouldBe RootedPath(RootName.PRIMARY, TreePath.require("gone.md"))
                    harness.registry.find(alias) shouldBe gone
                    val client = restClient()
                    val parent = client.get("/docs/ref2") { header(HttpHeaders.Accept, accept) }
                    val parentBody = parent.bodyAsText()
                    val child = client.get("/docs/ref2/index") { header(HttpHeaders.Accept, accept) }
                    val childBody = child.bodyAsText()
                    assertSoftly {
                        parent.status shouldBe HttpStatusCode.ServiceUnavailable
                        child.status shouldBe HttpStatusCode.OK
                        listOf(parent, child).forEach {
                            it.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe
                                setOf(if (accept == "text/html") "no-cache" else "no-store")
                        }
                        if (accept == "text/html") {
                            parentBody shouldBe requireNotNull(bundledShellHtml())
                            childBody shouldContain "<meta name=\"plainbase-page-id\" content=\"${ReaderPages.RL.id.value}\">"
                        } else {
                            parentBody shouldContain "\"code\":\"absence_unverified\""
                            childBody shouldBe ReaderPages.RL.markdown
                        }
                    }
                }
            }
        }

        listOf(
            "/" to "/docs",
            "/p/docs/${ReaderPages.A.id.value}" to "/docs/guides/alpha",
            "/p/docs/${ReaderPages.G.id.value}" to "/docs/guides",
            "/p/${ReaderPages.CU.id.value}" to "/docs/renamed",
            "/browse/docs/guides/alpha.md" to "/docs/guides/alpha",
            "/browse/docs/custom/index.md" to "/docs/renamed",
        ).forEach { (path, location) ->
            test("redirect cache $path for $accept") {
                corpusTest {
                    val response = restClient().get(path) { header(HttpHeaders.Accept, accept) }
                    assertSoftly {
                        response.status shouldBe HttpStatusCode.Found
                        response.headers[HttpHeaders.Location] shouldBe location
                        response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-store")
                    }
                }
            }
        }

        test("absent permalink cache for $accept") {
            corpusTest {
                val response = restClient().get("/p/docs/${ReaderPages.absentId.value}") { header(HttpHeaders.Accept, accept) }
                val body = response.bodyAsText()
                response.status shouldBe HttpStatusCode.NotFound
                body shouldContain "\"code\":\"page_not_found\""
                response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-store")
            }
        }

        test("malformed escape cache over the socket for $accept") {
            withTempTree(::readerCorpus) { root ->
                runBlocking {
                    wireServer(harness = { RestHarness(root, authMode = AuthMode.OFF, profile = DeploymentProfile.READER) }) { port ->
                        val raw = Socket("127.0.0.1", port).use { socket ->
                            socket.soTimeout = 5_000
                            socket.getOutputStream().write(
                                "GET /docs/%GG HTTP/1.1\r\nHost: localhost\r\nAccept: $accept\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                        }
                        raw.lineSequence().first() shouldContain " 400 "
                        raw shouldContain "\"code\":\"invalid_path\""
                        raw.substringBefore("\r\n\r\n").lineSequence().filter {
                            it.startsWith("Cache-Control:", ignoreCase = true)
                        }.map { it.substringAfter(':').trim() }.toSet() shouldBe setOf("no-store")
                    }
                }
            }
        }

        val staticPaths = listOf(
            "/favicon.svg", "/plainbase-logo.svg", "/plainbase-logo-dark.svg", "/apple-touch-icon.png",
            "/fonts/ibm-plex-sans-400.woff2", "/plainbase-theme-generic.css",
        )
        val servicePaths = listOf(
            "/api/v1/session", "/api/v1/tree", "/api/v1/search?q=Alpha", "/api/x",
            "/assets/docs/diagram.svg",
        )
        (staticPaths + servicePaths).forEach { path ->
            test("static or service cache $path for $accept") {
                withTempTree({ root ->
                    readerCorpus(root)
                    Files.writeString(root.resolve("diagram.svg"), "<svg/>")
                }) { root ->
                    readerTest(root) {
                        val response = restClient().get(path) { header(HttpHeaders.Accept, accept) }
                        response.status shouldBe if (path == "/api/x") HttpStatusCode.NotFound else HttpStatusCode.OK
                        response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-cache")
                    }
                }
            }
        }

        test("the shell's script and stylesheet cache for $accept") {
            corpusTest {
                val shell = requireNotNull(bundledShellHtml())
                val assets = Regex("(?:src|href)=\"(/assets/index-[^\"]+\\.(?:js|css))\"").findAll(shell)
                    .map { it.groupValues[1] }.toList()
                assets.map { it.substringAfterLast('.') }.toSet() shouldBe setOf("js", "css")
                val client = restClient()
                val responses = assets.map { path -> path to client.get(path) { header(HttpHeaders.Accept, accept) } }
                assertSoftly {
                    responses.forEach { (path, response) ->
                        withClue(path) {
                            response.status shouldBe HttpStatusCode.OK
                            response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("public, max-age=31536000, immutable")
                        }
                    }
                }
            }
        }
    }

    test("health response cannot be stored") {
        corpusTest {
            val response = restClient().get("/healthz")
            response.status shouldBe HttpStatusCode.OK
            response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-store")
        }
    }

    test("percent encoded health response cannot be stored") {
        corpusTest {
            val response = restClient().get("/he%61lthz")
            response.status shouldBe HttpStatusCode.OK
            response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-store")
        }
    }

    test("percent encoded permalink prefix remains malformed and cannot be stored") {
        corpusTest {
            val response = restClient().get("/%70/docs/${ReaderPages.absentId.value}")
            assertSoftly {
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "\"code\":\"invalid_page_id\""
                response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-store")
            }
        }
    }

    test("every declared reader surface carries its own cache directive in both representations") {
        readerTest(Fixtures.demoDocs) { harness ->
            val page = harness.builder.current.pages.single { it.path.value == "guides/deploy-guide.md" }
            val probes = listOf(
                CacheProbe("GET /healthz", "/healthz", directive = "no-store"),
                CacheProbe("GET /api/v1/session", "/api/v1/session"),
                CacheProbe(
                    "GET /api/v1/pages/by-path/{path...}",
                    "/api/v1/pages/by-path/docs/${requireNotNull(page.urlPath).value}", negotiates = true,
                ),
                CacheProbe("GET /api/v1/pages/{id}", "/api/v1/pages/${page.id.value}", negotiates = true),
                CacheProbe("GET /api/v1/pages/{id}/html", "/api/v1/pages/${page.id.value}/html"),
                CacheProbe("GET /api/v1/pages/{id}/metadata", "/api/v1/pages/${page.id.value}/metadata"),
                CacheProbe("GET /api/v1/tree", "/api/v1/tree"),
                CacheProbe("GET /api/v1/search", "/api/v1/search?q=deploy"),
                CacheProbe("GET /assets/{path...}", "/assets/docs/infra/assets/diagram.svg"),
                CacheProbe("GET /p/{segments...}", "/p/docs/${page.id.value}", HttpStatusCode.Found, "no-store"),
                CacheProbe("GET /browse/{path...}", "/browse/docs/${page.path.value}", HttpStatusCode.Found, "no-store"),
                CacheProbe("GET /{root}", "/docs", negotiates = true),
                CacheProbe("GET /{root}/{path...}", requireNotNull(page.url), negotiates = true),
                CacheProbe("GET /", "/", HttpStatusCode.Found, "no-store"),
                CacheProbe("GET /apple-touch-icon.png", "/apple-touch-icon.png"),
                CacheProbe("GET /favicon.svg", "/favicon.svg"),
                CacheProbe("GET /index.html", "/index.html"),
                CacheProbe("GET /plainbase-logo-dark.svg", "/plainbase-logo-dark.svg"),
                CacheProbe("GET /plainbase-logo.svg", "/plainbase-logo.svg"),
                CacheProbe("GET /plainbase-theme-generic.css", "/plainbase-theme-generic.css"),
                CacheProbe("GET /fonts/{file}", "/fonts/ibm-plex-sans-400.woff2"),
            ) + listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Options).flatMap { method ->
                listOf(
                    CacheProbe("ANY /api/{...}", "/api/x", HttpStatusCode.NotFound, method = method),
                    CacheProbe("ANY /_plainbase/{...}", "/_plainbase/x", HttpStatusCode.NotFound, "no-store", method),
                ) + listOf("theme-light.css", "theme-dark.css", "custom.css").map { file ->
                    CacheProbe(
                        "ANY /_plainbase/$file", "/_plainbase/$file",
                        if (method == HttpMethod.Get) HttpStatusCode.OK else HttpStatusCode.MethodNotAllowed,
                        "no-store", method,
                    )
                }
            }
            probes.map { it.label }.toSet() shouldBe ReaderSurfaceContract
            val client = restClient()
            val observations = listOf(null, "text/html", "text/markdown").flatMap { accept ->
                probes.map { probe ->
                    val response = client.request(probe.path) {
                        method = probe.method
                        if (accept != null) header(HttpHeaders.Accept, accept)
                    }
                    Triple(probe, accept, response)
                }
            }
            assertSoftly {
                observations.forEach { (probe, accept, response) ->
                    withClue("${probe.method.value} ${probe.path} $accept") {
                        response.status shouldBe if (probe.path == "/healthz" && accept != null) {
                            HttpStatusCode.NotAcceptable
                        } else {
                            probe.status
                        }
                        response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe
                            setOf(if (accept == "text/markdown" && probe.negotiates) "no-store" else probe.directive)
                    }
                }
            }
        }
    }

    test("reader session GET and HEAD carry no cache on the real wire") {
        wireServer(harness = { RestHarness(Fixtures.demoDocs, authMode = AuthMode.OFF, profile = DeploymentProfile.READER) }) { port ->
            val get = observeWire(port, "GET", "/api/v1/session")
            val head = observeWire(port, "HEAD", "/api/v1/session")
            assertSoftly {
                get.head.statusLine shouldBe "HTTP/1.1 200 OK"
                head.head.statusLine shouldBe get.head.statusLine
                get.head.header(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-cache")
                head.head.header(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-cache")
            }
        }
    }
})

private data class CacheDocument(val path: String, val page: ReaderPage, val location: String? = null)

private data class CacheProbe(
    val label: String,
    val path: String,
    val status: HttpStatusCode = HttpStatusCode.OK,
    val directive: String = "no-cache",
    val method: HttpMethod = HttpMethod.Get,
    val negotiates: Boolean = false,
)

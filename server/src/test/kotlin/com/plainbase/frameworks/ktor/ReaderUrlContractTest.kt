package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.ReaderPage
import com.plainbase.domain.service.ReaderPages
import com.plainbase.domain.service.TreeBuilder
import com.plainbase.domain.service.TreeNode
import com.plainbase.domain.service.assertReaderCorpus
import com.plainbase.domain.service.bareCorpus
import com.plainbase.domain.service.limboCorpus
import com.plainbase.domain.service.readerCorpus
import com.plainbase.domain.service.withTempTree
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.config.SiteConfig
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Socket
import java.nio.file.Files

class ReaderUrlContractTest : FunSpec({
    fun corpusTest(block: suspend ApplicationTestBuilder.(RestHarness) -> Unit) = withTempTree(::readerCorpus) { root ->
        readerTest(root, site = readerSite) { harness ->
            assertReaderCorpus(harness.builder.current, harness.registry)
            harness.services.reader?.site shouldBe readerSite
            block(harness)
        }
    }

    fun limboTest(block: suspend ApplicationTestBuilder.() -> Unit) = withTempTree(::limboCorpus) { root ->
        readerTest(root, site = readerSite, seed = { idMap ->
            idMap.bind(RootedPath(RootName.PRIMARY, TreePath.require("gone.md")), ReaderPages.goneId, materialized = false)
        }) { harness ->
            assertReaderCorpus(harness.builder.current, harness.registry, listOf(ReaderPages.R, ReaderPages.RL))
            val gone = RootedPageId(RootName.PRIMARY, ReaderPages.goneId)
            val alias = RootedPath(RootName.PRIMARY, TreePath.require("ref2"))
            harness.builder.current.pageAt(gone)?.path shouldBe TreePath.require("gone.md")
            harness.registry.find(alias) shouldBe gone
            val before = restClient().get("/docs/ref2") { header(HttpHeaders.Accept, "text/markdown") }
            before.status shouldBe HttpStatusCode.OK
            before.bodyAsBytes() shouldBe ReaderPages.GONE_MARKDOWN.toByteArray(Charsets.UTF_8)

            Files.delete(root.resolve("gone.md"))
            harness.builder.rebuild()
            harness.builder.current.pageAt(gone) shouldBe null
            harness.idMap.livePathOf(ReaderPages.goneId) shouldBe RootedPath(RootName.PRIMARY, TreePath.require("gone.md"))
            harness.registry.find(alias) shouldBe gone
            block()
        }
    }

    listOf(
        "/docs/guides/alpha",
        "/docs/readme",
        "/docs/shadow/index",
        "/docs/ref/index",
        "/docs/guides",
        "/docs",
        "/docs/deep",
        "/docs/guides/alpha/",
        "/docs/guides/%61lpha",
    ).forEach { path ->
        test("reader HTML $path serves an HTML response with Accept variance") {
            corpusTest {
                restClient().get(path) { header(HttpHeaders.Accept, "text/html") }.assertHtmlPin(HttpStatusCode.OK)
            }
        }
    }

    listOf(
        "/docs/old/alpha" to "/docs/guides/alpha",
        "/docs/ref" to "/docs/other",
    ).forEach { (path, location) ->
        test("reader HTML $path redirects to $location") {
            corpusTest {
                val response = restClient().get(path) { header(HttpHeaders.Accept, "text/html") }
                response.assertRedirectPin(HttpStatusCode.MovedPermanently, location)
                response.assertAcceptVary()
            }
        }
    }

    listOf(
        "/docs/guides/index" to "/docs/guides",
        "/docs/guides/index?mode=read" to "/docs/guides?mode=read",
        "/docs/index" to "/docs",
        "/docs/renamed/index" to "/docs/renamed",
        "/docs/old-guides" to "/docs/guides",
        "/docs/named/index" to "/docs/named",
    ).forEach { (path, location) ->
        test("reader HTML landing $path redirects directly to $location") {
            corpusTest {
                val response = restClient().get(path) { header(HttpHeaders.Accept, "text/html") }
                response.assertRedirectPin(HttpStatusCode.MovedPermanently, location)
                response.assertAcceptVary()
            }
        }
    }

    test("reader HTML landing alias serves at its own destination") {
        corpusTest {
            restClient().get("/docs/named") { header(HttpHeaders.Accept, "text/html") }.assertHtmlPin(HttpStatusCode.OK)
        }
    }

    listOf("/docs/Guides/Alpha", "/docs/nope", "/docs/custom/index").forEach { path ->
        test("reader HTML missing document $path is the shell at 404") {
            corpusTest {
                restClient().get(path) { header(HttpHeaders.Accept, "text/html") }.assertShellPin(HttpStatusCode.NotFound)
            }
        }
    }

    test("reader HTML bare root without a landing remains an HTML response") {
        withTempTree(::bareCorpus) { root ->
            readerTest(root, site = readerSite) { harness ->
                assertReaderCorpus(harness.builder.current, harness.registry, listOf(ReaderPages.top))
                restClient().get("/docs") { header(HttpHeaders.Accept, "text/html") }.assertHtmlPin(HttpStatusCode.OK)
            }
        }
    }

    test("reader Markdown bare root without a landing is page not found") {
        withTempTree(::bareCorpus) { root ->
            readerTest(root, site = readerSite) { harness ->
                assertReaderCorpus(harness.builder.current, harness.registry, listOf(ReaderPages.top))
                val response = restClient().get("/docs") { header(HttpHeaders.Accept, "text/markdown") }
                response.assertErrorPin(HttpStatusCode.NotFound, "page_not_found")
                response.assertAcceptVary()
                response.assertMarkdownCachePin()
            }
        }
    }

    listOf("/docs/guides" to ReaderPages.G, "/docs" to ReaderPages.R).forEach { (path, page) ->
        test("reader Markdown folder $path returns its landing source bytes") {
            corpusTest {
                restClient().get(path) { header(HttpHeaders.Accept, "text/markdown") }.assertMarkdownPin(page)
            }
        }
    }

    listOf(
        "/docs/guides/alpha" to ReaderPages.A,
        "/docs/guides/index" to ReaderPages.G,
        "/docs/index" to ReaderPages.R,
        "/docs/readme" to ReaderPages.M,
        "/docs/shadow/index" to ReaderPages.SI,
        "/docs/old/alpha" to ReaderPages.A,
        "/docs/old-guides" to ReaderPages.G,
        "/docs/named" to ReaderPages.N,
        "/docs/named/index" to ReaderPages.N,
        "/docs/ref" to ReaderPages.O,
        "/docs/ref/index" to ReaderPages.RF,
        "/docs/guides/alpha/" to ReaderPages.A,
        "/docs/guides/%61lpha" to ReaderPages.A,
    ).forEach { (path, page) ->
        test("reader Markdown $path returns the indexed source bytes") {
            corpusTest {
                restClient().get(path) { header(HttpHeaders.Accept, "text/markdown") }.assertMarkdownPin(page)
            }
        }
    }

    listOf("/docs/deep", "/docs/Guides/Alpha", "/docs/nope", "/docs/custom/index").forEach { path ->
        test("reader Markdown $path is a page not found error") {
            corpusTest {
                val response = restClient().get(path) { header(HttpHeaders.Accept, "text/markdown") }
                response.assertErrorPin(HttpStatusCode.NotFound, "page_not_found")
                response.assertAcceptVary()
                response.assertMarkdownCachePin()
            }
        }
    }

    listOf("/nope/x", "/docs/%2e%2e", "/docs/%00").forEach { path ->
        test("reader unknown or undecodable $path is 404 in both representations") {
            corpusTest {
                val client = restClient()
                client.get(path) { header(HttpHeaders.Accept, "text/html") }.assertHtmlPin(HttpStatusCode.NotFound)
                val markdown = client.get(path) { header(HttpHeaders.Accept, "text/markdown") }
                markdown.assertErrorPin(HttpStatusCode.NotFound, "page_not_found")
                markdown.assertAcceptVary()
                markdown.assertMarkdownCachePin()
            }
        }
    }

    test("reader Markdown on an unavailable root returns a root unavailable error") {
        corpusTest { harness ->
            harness.availability.markUnavailable(RootName.PRIMARY, UnavailableCause.VANISHED)
            val response = restClient().get("/docs/guides/alpha") { header(HttpHeaders.Accept, "text/markdown") }
            response.assertErrorPin(HttpStatusCode.ServiceUnavailable, "root_unavailable")
            response.assertAcceptVary()
            response.assertMarkdownCachePin()
        }
    }

    listOf("/docs/guides/alpha", "/docs").forEach { path ->
        test("reader HTML unavailable root at $path is the shell at 503") {
            corpusTest { harness ->
                harness.availability.markUnavailable(RootName.PRIMARY, UnavailableCause.VANISHED)
                restClient().get(path) { header(HttpHeaders.Accept, "text/html") }.assertShellPin(HttpStatusCode.ServiceUnavailable)
            }
        }
    }

    test("reader Markdown bare unavailable root is a root unavailable error") {
        corpusTest { harness ->
            harness.availability.markUnavailable(RootName.PRIMARY, UnavailableCause.VANISHED)
            val response = restClient().get("/docs") { header(HttpHeaders.Accept, "text/markdown") }
            response.assertErrorPin(HttpStatusCode.ServiceUnavailable, "root_unavailable")
            response.assertAcceptVary()
            response.assertMarkdownCachePin()
        }
    }

    test("reader bound but missing alias shadows the folder while its landing child stays readable") {
        limboTest {
            val client = restClient()
            val missing = client.get("/docs/ref2") { header(HttpHeaders.Accept, "text/markdown") }
            missing.assertErrorPin(HttpStatusCode.ServiceUnavailable, "absence_unverified")
            missing.assertAcceptVary()
            missing.assertMarkdownCachePin()
            client.get("/docs/ref2/index") { header(HttpHeaders.Accept, "text/markdown") }.assertMarkdownPin(ReaderPages.RL)
            client.get("/docs/ref2/index") { header(HttpHeaders.Accept, "text/html") }.assertHtmlPin(HttpStatusCode.OK)
        }
    }

    test("reader HTML bound but missing alias is the shell at 503") {
        limboTest {
            restClient().get("/docs/ref2") { header(HttpHeaders.Accept, "text/html") }.assertShellPin(HttpStatusCode.ServiceUnavailable)
        }
    }

    listOf("text/html", "text/markdown").forEach { accept ->
        listOf(
            "/p/docs/${ReaderPages.G.id.value}" to "/docs/guides",
            "/p/docs/${ReaderPages.CU.id.value}" to "/docs/renamed",
            "/p/${ReaderPages.G.id.value}" to "/docs/guides",
            "/p/docs/${ReaderPages.C.id.value}" to "/docs/caf%C3%A9",
        ).forEach { (path, location) ->
            test("reader landing permalink $path targets $location for $accept") {
                corpusTest {
                    val response = restClient().get(path) { header(HttpHeaders.Accept, accept) }
                    response.assertRedirectPin(HttpStatusCode.Found, location)
                    response.assertAcceptVary()
                    if (accept == "text/markdown") response.assertMarkdownCachePin()
                }
            }
        }

        test("reader landing browse targets its folder for $accept") {
            corpusTest {
                restClient().get("/browse/docs/guides/index.md") { header(HttpHeaders.Accept, accept) }
                    .assertRedirectPin(HttpStatusCode.Found, "/docs/guides")
            }
        }

        test("reader corpus redirects terminate in both representations when requested as $accept") {
            corpusTest { harness ->
                val snapshot = harness.builder.current
                val pages = ReaderPages.all.map { requireNotNull(snapshot.pageAt(it.rooted)) }
                val folderPages = buildMap<String, PageId> {
                    fun visit(folder: TreeNode.Folder) {
                        TreeBuilder.landingPage(folder)?.let { put(requireNotNull(folder.url), it.id) }
                        folder.children.filterIsInstance<TreeNode.Folder>().forEach(::visit)
                    }
                    visit(TreeBuilder.build(snapshot, RootName.PRIMARY))
                }
                val expectedIds = buildMap {
                    putAll(folderPages)
                    ReaderPages.all.forEach { page -> page.aliases.forEach { put("/docs$it", page.id) } }
                    pages.forEach { page ->
                        put(requireNotNull(page.url), page.id)
                        put("/p/docs/${page.id.value}", page.id)
                        put("/p/${page.id.value}", page.id)
                        put("/browse/docs/${PercentCoding.encodePath(page.path.value)}", page.id)
                    }
                    put("/", ReaderPages.R.id)
                }
                expectedIds.size shouldBe 58
                val client = restClient()
                val identities = mutableListOf<Triple<String, List<String>, String>>()
                val observations = buildList {
                    expectedIds.forEach { (url, id) ->
                        val response = client.get(url) { header(HttpHeaders.Accept, accept) }
                        if (response.status.value in 300..399) {
                            val location = response.headers[HttpHeaders.Location].shouldNotBeNull()
                            listOf("text/html", "text/markdown").forEach { targetAccept ->
                                val target = client.get(location) { header(HttpHeaders.Accept, targetAccept) }
                                val label = "$accept $url -> $location as $targetAccept"
                                add(label to target.status)
                                if (targetAccept == "text/html") {
                                    identities += Triple(label, initialPageIds(target.bodyAsText()), id.value)
                                }
                            }
                        } else {
                            add("$accept $url" to response.status)
                            if (accept == "text/html") identities += Triple(url, initialPageIds(response.bodyAsText()), id.value)
                        }
                    }
                }
                assertSoftly {
                    observations.forEach { (label, status) ->
                        withClue(label) { status shouldBe HttpStatusCode.OK }
                    }
                    identities.forEach { (label, actual, expected) ->
                        withClue(label) { actual shouldBe listOf(expected) }
                    }
                }
            }
        }

        test("reader root redirects to the primary root for $accept") {
            corpusTest {
                restClient().get("/") { header(HttpHeaders.Accept, accept) }.assertRedirectPin(HttpStatusCode.Found, "/docs")
            }
        }

        test("reader non landing permalink and absent id retain their answers for $accept") {
            corpusTest {
                val client = restClient()
                val found = client.get("/p/docs/${ReaderPages.A.id.value}") { header(HttpHeaders.Accept, accept) }
                found.assertRedirectPin(HttpStatusCode.Found, "/docs/guides/alpha")
                found.assertAcceptVary()
                val absent = client.get("/p/docs/${ReaderPages.absentId.value}") { header(HttpHeaders.Accept, accept) }
                absent.assertErrorPin(HttpStatusCode.NotFound, "page_not_found")
                absent.assertAcceptVary()
                if (accept == "text/markdown") {
                    found.assertMarkdownCachePin()
                    absent.assertMarkdownCachePin()
                }
            }
        }

        test("reader non landing browse target redirects for $accept") {
            corpusTest {
                restClient().get("/browse/docs/guides/alpha.md") { header(HttpHeaders.Accept, accept) }
                    .assertRedirectPin(HttpStatusCode.Found, "/docs/guides/alpha")
            }
        }

        test("reader malformed escape is invalid path over the socket for $accept") {
            withTempTree(::readerCorpus) { root ->
                runBlocking {
                    wireServer(harness = {
                        RestHarness(root, authMode = AuthMode.OFF, profile = DeploymentProfile.READER, site = readerSite).also {
                            assertReaderCorpus(it.builder.current, it.registry)
                        }
                    }) { port ->
                        val response = Socket("127.0.0.1", port).use { socket ->
                            socket.soTimeout = 5_000
                            socket.getOutputStream().write(
                                ("GET /docs/%GG HTTP/1.1\r\nHost: localhost\r\nAccept: $accept\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                        }
                        response.lineSequence().first() shouldContain " 400 "
                        response shouldContain "\"code\":\"invalid_path\""
                    }
                }
            }
        }
    }

    listOf("theme-light.css", "theme-dark.css", "custom.css").forEach { file ->
        test("reader theme $file retains no store") {
            corpusTest {
                val response = restClient().get("/_plainbase/$file")
                response.status shouldBe HttpStatusCode.OK
                response.headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-store")
            }
        }
    }
})

private val readerSite = SiteConfig(canonicalOrigin = "https://docs.example.test", name = "Example Docs")

private fun HttpResponse.assertAcceptVary() {
    headers.getAll(HttpHeaders.Vary).orEmpty().flatMap { it.split(',') }.any { it.trim().equals("Accept", ignoreCase = true) }
        .shouldBe(true)
}

private fun HttpResponse.assertHtmlPin(status: HttpStatusCode) {
    this.status shouldBe status
    contentType()?.withoutParameters() shouldBe ContentType.Text.Html
    assertAcceptVary()
}

private suspend fun HttpResponse.assertShellPin(status: HttpStatusCode) {
    assertHtmlPin(status)
    bodyAsText() shouldBe requireNotNull(bundledShellHtml())
    headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-cache")
    headers[HttpHeaders.RetryAfter] shouldBe null
}

private fun HttpResponse.assertRedirectPin(status: HttpStatusCode, location: String) {
    this.status shouldBe status
    headers[HttpHeaders.Location] shouldBe location
}

private fun HttpResponse.assertMarkdownCachePin() {
    headers.getAll(HttpHeaders.CacheControl)?.toSet() shouldBe setOf("no-store")
}

private suspend fun HttpResponse.assertMarkdownPin(page: ReaderPage) {
    status shouldBe HttpStatusCode.OK
    contentType()?.withoutParameters() shouldBe ContentType("text", "markdown")
    bodyAsBytes() shouldBe page.markdown.toByteArray(Charsets.UTF_8)
    assertAcceptVary()
    assertMarkdownCachePin()
}

private suspend fun HttpResponse.assertErrorPin(status: HttpStatusCode, code: String) {
    this.status shouldBe status
    contentType()?.withoutParameters() shouldBe ContentType.Application.Json
    Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe code
}

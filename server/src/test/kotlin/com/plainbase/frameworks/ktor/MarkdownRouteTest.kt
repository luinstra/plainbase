package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.AmbiguousPageId
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PermalinkResolution
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
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
import io.ktor.http.withCharset
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

class MarkdownRouteTest : FunSpec({
    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val seed: (com.plainbase.domain.repository.IdMapRepository) -> Unit = { idMap ->
        idMap.bind(
            com.plainbase.domain.root.RootedPath(
                RootName.PRIMARY,
                com.plainbase.domain.content.TreePath.require("guides/deploy-guide.md"),
            ),
            PageId.require(deployGuideId),
            materialized = false,
        )
    }

    fun HttpResponse.hasHeaderToken(name: String, expected: String): Boolean =
        headers.getAll(name).orEmpty()
            .flatMap { it.split(',') }
            .any { it.trim().equals(expected, ignoreCase = true) }

    suspend fun io.ktor.client.statement.HttpResponse.markdownBody(): ByteArray {
        status shouldBe HttpStatusCode.OK
        contentType() shouldBe ContentType("text", "markdown").withCharset(Charsets.UTF_8)
        hasHeaderToken(HttpHeaders.Vary, HttpHeaders.Accept) shouldBe true
        hasHeaderToken(HttpHeaders.CacheControl, "no-store") shouldBe true
        headers["X-Content-Type-Options"] shouldBe "nosniff"
        headers[HttpHeaders.ETag] shouldBe null
        return bodyAsBytes()
    }

    test("REST Markdown is the indexed source while JSON stays byte-identical") {
        restTest(Fixtures.demoDocs, seed) {
            val source = Files.readAllBytes(Fixtures.demoDocs.resolve("guides/deploy-guide.md"))
            val json = client.get("/api/v1/pages/$deployGuideId")
            val explicitJson = client.get("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.Accept, "application/json")
            }
            val conditionalJson = client.get("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfNoneMatch, json.headers[HttpHeaders.ETag].orEmpty())
            }
            val markdown = client.get("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.Accept, "text/markdown")
                header(HttpHeaders.IfNoneMatch, json.headers[HttpHeaders.ETag].orEmpty())
            }
            val byPath = client.get("/api/v1/pages/by-path/docs/old/deployment") {
                header(HttpHeaders.Accept, "text/markdown")
            }

            json.status shouldBe HttpStatusCode.OK
            explicitJson.status shouldBe HttpStatusCode.OK
            json.hasHeaderToken(HttpHeaders.Vary, HttpHeaders.Accept) shouldBe true
            explicitJson.hasHeaderToken(HttpHeaders.Vary, HttpHeaders.Accept) shouldBe true
            conditionalJson.status shouldBe HttpStatusCode.OK
            conditionalJson.bodyAsBytes().contentEquals(json.bodyAsBytes()) shouldBe true
            explicitJson.bodyAsBytes().contentEquals(json.bodyAsBytes()) shouldBe true
            explicitJson.headers[HttpHeaders.ETag] shouldBe json.headers[HttpHeaders.ETag]
            markdown.markdownBody().contentEquals(source) shouldBe true
            byPath.markdownBody().contentEquals(source) shouldBe true
        }
    }

    test("a Markdown REST failure remains structured and carries the local cache policy") {
        restTest(Fixtures.demoDocs, seed) {
            val response = client.get("/api/v1/pages/a3bb189e-8bf9-4888-9912-ace4e6543002") {
                header(HttpHeaders.Accept, "text/markdown")
            }
            response.status shouldBe HttpStatusCode.NotFound
            Json.parseToJsonElement(response.bodyAsText()).jsonObject
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "page_not_found"
            response.headers[HttpHeaders.ETag] shouldBe null
            response.hasHeaderToken(HttpHeaders.Vary, HttpHeaders.Accept) shouldBe true
            response.hasHeaderToken(HttpHeaders.CacheControl, "no-store") shouldBe true
        }
    }

    test("ordinary document URLs serve Markdown directly but keep shells and aliases for HTML") {
        restTest(Fixtures.demoDocs, seed) {
            val source = Files.readAllBytes(Fixtures.demoDocs.resolve("guides/deploy-guide.md"))
            val markdown = client.get("/docs/guides/deploy-guide") {
                header(HttpHeaders.Accept, "text/markdown")
            }
            markdown.markdownBody().contentEquals(source) shouldBe true

            val alias = client.get("/docs/old/deployment") {
                header(HttpHeaders.Accept, "text/markdown")
            }
            alias.markdownBody().contentEquals(source) shouldBe true

            val browser = client.get("/docs/guides/deploy-guide") {
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
            browser.status shouldBe HttpStatusCode.OK
            browser.hasHeaderToken(HttpHeaders.Vary, HttpHeaders.Accept) shouldBe true
            browser.bodyAsText() shouldContain "<div id=\"root\">"

            val landing = client.get("/docs") {
                header(HttpHeaders.Accept, "text/markdown")
            }
            landing.status shouldBe HttpStatusCode.OK
            landing.hasHeaderToken(HttpHeaders.Vary, HttpHeaders.Accept) shouldBe true
            landing.contentType() shouldBe ContentType.Text.Html.withCharset(Charsets.UTF_8)
            landing.bodyAsText() shouldContain "<div id=\"root\">"

            val unknownRoot = client.get("/not-a-root") {
                header(HttpHeaders.Accept, "text/markdown")
            }
            unknownRoot.status shouldBe HttpStatusCode.NotFound
            unknownRoot.hasHeaderToken(HttpHeaders.CacheControl, "no-store") shouldBe true
            Json.parseToJsonElement(unknownRoot.bodyAsText()).jsonObject
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "page_not_found"

            val undecodable = client.get("/%FF") {
                header(HttpHeaders.Accept, "text/markdown")
            }
            undecodable.status shouldBe HttpStatusCode.NotFound
            undecodable.hasHeaderToken(HttpHeaders.CacheControl, "no-store") shouldBe true
        }
    }

    test("a found permalink keeps its redirect, while a followed Markdown URL serves the source") {
        restTest(Fixtures.demoDocs, seed) {
            val redirect = restClient().get("/p/$deployGuideId?mode=read") {
                header(HttpHeaders.Accept, "text/markdown")
            }
            redirect.status shouldBe HttpStatusCode.Found
            redirect.headers[HttpHeaders.Location] shouldBe "/docs/guides/deploy-guide?mode=read"
            redirect.hasHeaderToken(HttpHeaders.Vary, HttpHeaders.Accept) shouldBe true
            redirect.hasHeaderToken(HttpHeaders.CacheControl, "no-store") shouldBe true
        }
    }

    test("a permalink loser maps a second-read ambiguity to permalink 300 links") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val base = harness.testRouteContext(searchProvider = mockk(relaxed = true))
                val id = harness.builder.current.pages.single().id
                val delegated = mockk<com.plainbase.domain.service.ReadFacade>(relaxed = true)
                every { delegated.permalink(any(), id) } returns PermalinkResolution.LoserNoUrl
                every { delegated.pageById(any(), id, null) } throws
                    AmbiguousPageId(id, listOf(RootName.PRIMARY, RootName.require("extra")))
                val context = base.withRead(delegated)

                testApplication {
                    application { plainbaseModule(context) }
                    val response = client.get("/p/${id.value}") {
                        header(HttpHeaders.Accept, "text/markdown")
                    }
                    response.status shouldBe HttpStatusCode.MultipleChoices
                    response.hasHeaderToken(HttpHeaders.CacheControl, "no-store") shouldBe true
                    response.headers.getAll(HttpHeaders.Link).orEmpty() shouldContain
                        "</p/docs/${id.value}>; rel=\"alternate\""
                    response.headers.getAll(HttpHeaders.Link).orEmpty() shouldContain
                        "</p/extra/${id.value}>; rel=\"alternate\""
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        .getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "ambiguous_page_id"
                }
            }
        }
    }

    test("a permalink loser second-read miss and outage remain structured") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val id = harness.builder.current.pages.single().id
                listOf(
                    null to HttpStatusCode.NotFound,
                    RootUnavailable(RootName.PRIMARY, com.plainbase.domain.root.UnavailableCause.VANISHED) to
                        HttpStatusCode.ServiceUnavailable,
                ).forEach { (failure, expected) ->
                    val base = harness.testRouteContext(searchProvider = mockk(relaxed = true))
                    val delegated = mockk<com.plainbase.domain.service.ReadFacade>(relaxed = true)
                    every { delegated.permalink(any(), id) } returns PermalinkResolution.LoserNoUrl
                    if (failure == null) {
                        every { delegated.pageById(any(), id, null) } returns null
                    } else {
                        every { delegated.pageById(any(), id, null) } throws failure
                    }
                    testApplication {
                        application { plainbaseModule(base.withRead(delegated)) }
                        val response = client.get("/p/${id.value}") {
                            header(HttpHeaders.Accept, "text/markdown")
                        }
                        response.status shouldBe expected
                        response.hasHeaderToken(HttpHeaders.CacheControl, "no-store") shouldBe true
                        response.hasHeaderToken(HttpHeaders.Vary, HttpHeaders.Accept) shouldBe true
                    }
                }
            }
        }
    }
})

private fun RouteContext.withRead(read: com.plainbase.domain.service.ReadFacade): RouteContext = RouteContext(
    read = read,
    mutate = mutate,
    proposals = proposals,
    registry = registry,
    availability = availability,
    convergence = convergence,
    limbo = limbo,
    tokens = tokens,
    auth = auth,
    trustedProxyCidrs = trustedProxyCidrs,
    idProvider = idProvider,
    maxWriteBodyBytes = maxWriteBodyBytes,
    maxAssetBytes = maxAssetBytes,
    mcpAllowedHosts = mcpAllowedHosts,
    mcpAllowedOrigins = mcpAllowedOrigins,
    builtinAuthEnabled = builtinAuthEnabled,
    proxyAuthEnabled = proxyAuthEnabled,
    proxySecret = proxySecret,
    proxyIdentityHeader = proxyIdentityHeader,
    secureCookie = secureCookie,
    proxyCsrf = proxyCsrf,
    extract = extract,
)

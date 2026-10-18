package com.plainbase.frameworks.ktor

import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.IndexHarness
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.testing.testApplication
import io.mockk.mockk

internal fun assertShellCachePolicy(response: HttpResponse) {
    response.contentType() shouldBe ContentType.Text.Html.withCharset(Charsets.UTF_8)
    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-cache")
    response.headers[HttpHeaders.ETag].shouldBeNull()
    response.headers[HttpHeaders.LastModified].shouldBeNull()
    assertShellHeaders(response)
}

class ShellCachePolicyTest : FunSpec({

    listOf(
        "/index.html" to HttpStatusCode.OK,
        "/admin" to HttpStatusCode.OK,
        "/review/0197c4d5-1234-7abc-8def-0123456789ab" to HttpStatusCode.OK,
        "/docs" to HttpStatusCode.OK,
        "/docs/anything" to HttpStatusCode.OK,
        "/nope/x" to HttpStatusCode.NotFound,
        "/docs/%C3%28" to HttpStatusCode.NotFound,
        "/docs/a%2Fb" to HttpStatusCode.NotFound,
        "/review/a/b" to HttpStatusCode.NotFound,
        "/browse/docs/a%2Fb.mmd" to HttpStatusCode.NotFound,
        "/browse/nope/x.mmd" to HttpStatusCode.NotFound,
        "/browse/docs/a%5Cb.mmd" to HttpStatusCode.NotFound,
        "/browse/docs/x.mmd" to HttpStatusCode.OK,
    ).forEach { (path, status) ->
        test("$path serves a shell with exactly one no-cache and no validators") {
            restTest(Fixtures.demoDocs) {
                val response = restClient().get(path)
                response.status shouldBe status
                assertShellCachePolicy(response)
            }
        }
    }

    test("the bare root remains a no-cache shell when Markdown is requested") {
        restTest(Fixtures.demoDocs) {
            val response = restClient().get("/docs") { header(HttpHeaders.Accept, "text/markdown") }
            response.status shouldBe HttpStatusCode.OK
            assertShellCachePolicy(response)
        }
    }

    test("an anonymous document request in enforced builtin mode serves a no-cache shell") {
        IndexHarness(Fixtures.demoDocs, contentStore = LocalContentStore(Fixtures.demoDocs)).use { harness ->
            harness.builder.rebuild()
            val ctx = harness.testRouteContext(searchProvider = mockk<SearchProvider>(), enforced = true)
            testApplication {
                application { plainbaseModule(ctx) }
                val client = restClient()
                client.get("/api/v1/pages/by-path/docs/guides/deploy-guide").status shouldBe HttpStatusCode.Unauthorized
                val response = client.get("/docs/guides/deploy-guide")
                response.status shouldBe HttpStatusCode.OK
                assertShellCachePolicy(response)
            }
        }
    }

    listOf(
        Triple("/", HttpStatusCode.Found, "/docs"),
        Triple("/docs/old/deployment", HttpStatusCode.MovedPermanently, "/docs/guides/deploy-guide"),
        Triple("/browse/docs/guides/deploy-guide.md", HttpStatusCode.Found, "/docs/guides/deploy-guide"),
    ).forEach { (path, status, location) ->
        test("$path redirects without Cache-Control") {
            restTest(Fixtures.demoDocs) {
                val response = restClient().get(path)
                response.status shouldBe status
                response.headers[HttpHeaders.Location] shouldBe location
                response.headers.getAll(HttpHeaders.CacheControl).shouldBeNull()
            }
        }
    }

    listOf("/favicon.svg", "/fonts/ibm-plex-sans-400.woff2").forEach { path ->
        test("$path is served without Cache-Control") {
            restTest(Fixtures.demoDocs) {
                val response = restClient().get(path)
                response.status shouldBe HttpStatusCode.OK
                response.headers.getAll(HttpHeaders.CacheControl).shouldBeNull()
            }
        }
    }

    test("the shell's hashed script is served without Cache-Control") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val shell = client.get("/docs").bodyAsText()
            val jsRef = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(shell)?.groupValues?.get(1)
            jsRef.shouldNotBeNull()
            val response = client.get(jsRef)
            response.status shouldBe HttpStatusCode.OK
            response.headers.getAll(HttpHeaders.CacheControl).shouldBeNull()
        }
    }
})

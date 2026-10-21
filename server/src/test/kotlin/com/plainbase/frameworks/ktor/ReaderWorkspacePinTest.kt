package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.ReaderPages
import com.plainbase.domain.service.assertReaderCorpus
import com.plainbase.domain.service.readerCorpus
import com.plainbase.domain.service.withTempTree
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder

class ReaderWorkspacePinTest : FunSpec({
    fun workspaceTest(block: suspend ApplicationTestBuilder.(RestHarness) -> Unit) = withTempTree(::readerCorpus) { root ->
        restTest(root) { harness ->
            assertReaderCorpus(harness.builder.current, harness.registry)
            harness.services.reader shouldBe null
            block(harness)
        }
    }

    test("workspace landing child remains the shell at its own URL") {
        workspaceTest {
            restClient().get("/docs/guides/index").assertWorkspaceShell()
        }
    }

    test("workspace bare root remains the shell when Markdown is requested") {
        workspaceTest {
            restClient().get("/docs") { header(HttpHeaders.Accept, "text/markdown") }.assertWorkspaceShell()
        }
    }

    listOf(
        Triple("/docs/old-guides", HttpStatusCode.MovedPermanently, "/docs/guides/index"),
        Triple("/p/docs/${ReaderPages.G.id.value}", HttpStatusCode.Found, "/docs/guides/index"),
        Triple("/p/${ReaderPages.G.id.value}", HttpStatusCode.Found, "/docs/guides/index"),
        Triple("/browse/docs/guides/index.md", HttpStatusCode.Found, "/docs/guides/index"),
    ).forEach { (path, status, location) ->
        test("workspace $path retains its page URL destination") {
            workspaceTest {
                val response = restClient().get(path)
                response.status shouldBe status
                response.headers[HttpHeaders.Location] shouldBe location
            }
        }
    }

    test("workspace health tree bundle favicon and alias retain absent cache control") {
        workspaceTest {
            val client = restClient()
            val shell = client.get("/docs").bodyAsText()
            val script = requireNotNull(Regex("src=\"(/assets/[^\"]+\\.js)\"").find(shell)).groupValues[1]
            listOf(
                "/healthz" to HttpStatusCode.OK,
                "/api/v1/tree" to HttpStatusCode.OK,
                script to HttpStatusCode.OK,
                "/favicon.svg" to HttpStatusCode.OK,
                "/docs/old/alpha" to HttpStatusCode.MovedPermanently,
            ).forEach { (path, status) ->
                withClue(path) {
                    val response = client.get(path)
                    response.status shouldBe status
                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe null
                }
            }
        }
    }

    test("workspace HTML miss remains the shell at 200") {
        workspaceTest {
            restClient().get("/docs/nope").assertWorkspaceShell()
        }
    }
})

private suspend fun HttpResponse.assertWorkspaceShell() {
    status shouldBe HttpStatusCode.OK
    contentType()?.withoutParameters() shouldBe ContentType.Text.Html
    bodyAsText() shouldBe requireNotNull(bundledShellHtml())
}

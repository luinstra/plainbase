package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.ktor.routes.FrontendBundle
import com.plainbase.frameworks.ktor.routes.assetContentType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import java.nio.file.Files

private const val SHELL_CSP = "Content-Security-Policy"
private const val REFERRER_POLICY = "Referrer-Policy"
private const val NOSNIFF = "X-Content-Type-Options"

private fun assertShellHeaders(response: io.ktor.client.statement.HttpResponse) {
    response.headers[SHELL_CSP].shouldNotBeNull()
    response.headers[REFERRER_POLICY] shouldBe "strict-origin-when-cross-origin"
    response.headers[NOSNIFF] shouldBe "nosniff"
    // EXACTLY one. `response.header()` is `headers.append` and never dedupes, so a shell arm that
    // stamps nosniff itself AND lets `shellSecurityHeadersPlugin` stamp it emits the header twice.
    // `headers[...]` returns only the FIRST value, so the single-value assertion above cannot see
    // that; only `getAll` can. This is the row that makes "the plugin owns the shell arm" falsifiable.
    response.headers.getAll(NOSNIFF)?.size shouldBe 1
}

class StaticSurfaceTest : FunSpec({

    test("every top-level bundle entry is served or ledgered to its owning route") {
        restTest(Fixtures.demoDocs) {
            val expectedOwned = FrontendBundle.directories + FrontendBundle.ownedElsewhere.map { it.name }
            val client = restClient()
            // Only the SERVED half carries assertions. FrontendBundleTest proves the delegated half against
            // the mounted route tree and H2 proves the ledger against the served bundle; restating
            // `name in expectedOwned` inside its own branch would assert nothing and could never fail.
            bundleTopLevel().filterNot { it in expectedOwned }.forEach { name ->
                withClue(name) {
                    run {
                        val response = client.get("/$name")
                        response.status shouldBe HttpStatusCode.OK
                        val expectedType = if (name == FrontendBundle.SHELL) {
                            ContentType.Text.Html.withCharset(Charsets.UTF_8)
                        } else {
                            assetContentType(name)
                        }
                        response.contentType() shouldBe expectedType
                        response.bodyAsBytes() shouldBe Files.readAllBytes(bundleDir().resolve(name))
                    }
                }
            }
        }
    }

    test("bundle directory files and the shell's hashed script are directly reachable") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            // CONTENT TYPE is asserted alongside status and bytes on both arms. These are hand-written
            // routes now that `staticResources` is gone, and that mount inferred the type for free: a
            // route serving the right bytes under `application/octet-stream` would break the browser
            // while a status-and-bytes-only gate stayed green.
            val font = client.get("/fonts/ibm-plex-sans-400.woff2")
            font.status shouldBe HttpStatusCode.OK
            font.contentType() shouldBe assetContentType("ibm-plex-sans-400.woff2")
            font.bodyAsBytes() shouldBe Files.readAllBytes(bundleDir().resolve("fonts/ibm-plex-sans-400.woff2"))

            val shell = client.get("/docs").bodyAsText()
            val jsRef = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(shell)?.groupValues?.get(1)
            jsRef.shouldNotBeNull()
            val script = client.get(jsRef)
            script.status shouldBe HttpStatusCode.OK
            script.contentType() shouldBe assetContentType(jsRef)
            script.bodyAsBytes() shouldBe Files.readAllBytes(
                bundleDir().resolve("assets").resolve(jsRef.removePrefix("/assets/")),
            )
        }
    }

    test("index.html is the shell with HTML content type and shell security headers") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val response = client.get("/index.html")
            response.status shouldBe HttpStatusCode.OK
            response.contentType() shouldBe ContentType.Text.Html.withCharset(Charsets.UTF_8)
            response.bodyAsBytes() shouldBe client.get("/docs").bodyAsBytes()
            assertShellHeaders(response)
        }
    }

    test("bundle directory traversal probes return JSON 404 envelopes") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            listOf(
                "/fonts/..",
                "/fonts/.",
                "/fonts/..%2Findex.html",
                "/fonts/..%2F..%2Flogback.xml",
                "/fonts/..%2F..%2F",
            ).forEachIndexed { index, path ->
                withClue(path) {
                    val response = client.get(path)
                    response.status shouldBe HttpStatusCode.NotFound
                    response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                    response.bodyAsText().contains("<div id=\"root\">") shouldBe false
                    if (index >= 2) {
                        response.bodyAsText().contains("<configuration>") shouldBe false
                        response.bodyAsText().contains("<!doctype html>") shouldBe false
                    }
                }
            }
        }
    }

    test("SPA-owned routes serve the same shell with shell security headers") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val shell = client.get("/docs").bodyAsBytes()
            listOf("/new", "/admin", "/review", "/review/0197c4d5-1234-7abc-8def-0123456789ab").forEach { path ->
                withClue(path) {
                    val response = client.get(path)
                    response.status shouldBe HttpStatusCode.OK
                    response.contentType() shouldBe ContentType.Text.Html.withCharset(Charsets.UTF_8)
                    response.bodyAsBytes() shouldBe shell
                    assertShellHeaders(response)
                }
            }
        }
    }

    test("SPA route boundaries are 404 shell responses") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            listOf("/review/a/b", "/new/", "/admin/", "/review/").forEach { path ->
                withClue(path) {
                    val response = client.get(path)
                    response.status shouldBe HttpStatusCode.NotFound
                    response.bodyAsBytes() shouldBe client.get("/docs").bodyAsBytes()
                    assertShellHeaders(response)
                }
            }
        }
    }
})

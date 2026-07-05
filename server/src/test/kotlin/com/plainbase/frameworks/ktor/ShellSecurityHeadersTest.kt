package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

/**
 * C1a items 3-7: the SPA shell Content-Security-Policy, stamped on every text/html response by the
 * [shellSecurityHeadersPlugin] choke point, and the served-bytes inline-script hash ([inlineScriptHash])
 * its `script-src` is built from. Pins the directive set, the natural text/html gate (B4 prefix-collision
 * literals + B1 conditional re-request), and the extractor's fail-closed ≠1 gate.
 */
class ShellSecurityHeadersTest : FunSpec({

    val expectedHash = inlineScriptHash(bundledShellHtml().shouldNotBeNull())

    fun assertFullShellCsp(csp: String?) {
        csp.shouldNotBeNull()
        csp shouldContain "default-src 'self'"
        csp shouldContain "script-src 'self' '$expectedHash'"
        csp shouldContain "style-src 'self' 'unsafe-inline'"
        csp shouldContain "img-src 'self' data: https:"
        csp shouldContain "font-src 'self'"
        csp shouldContain "connect-src 'self'"
        csp shouldContain "object-src 'none'"
        csp shouldContain "base-uri 'self'"
        csp shouldContain "frame-ancestors 'self'"
    }

    test("the shell CSP is stamped on BOTH shell-serving paths with the full directive set + companion headers") {
        restTest(Fixtures.demoDocs) {
            for (path in listOf("/", "/docs/anything")) {
                val response = client.get(path)
                response.status shouldBe HttpStatusCode.OK
                assertFullShellCsp(response.headers["Content-Security-Policy"])
                response.headers["Referrer-Policy"] shouldBe "strict-origin-when-cross-origin"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                // item 7: frame-ancestors is the single source of truth; NO legacy X-Frame-Options.
                response.headers["X-Frame-Options"] shouldBe null
            }
        }
    }

    test("the script-src hash equals a fresh sha256 of the served shell's inline block (not a constant)") {
        restTest(Fixtures.demoDocs) {
            val csp = client.get("/docs/anything").headers["Content-Security-Policy"]
            csp.shouldNotBeNull()
            expectedHash shouldStartWith "sha256-"
            csp shouldContain "'$expectedHash'"
        }
    }

    test("[B4] the text/html gate covers prefix-collision shell paths and skips api/asset responses") {
        restTest(Fixtures.demoDocs) {
            // /apiary and /assetsx SHARE the /api and /assets prefixes but are served the shell HTML by
            // staticResources — a startsWith() exclusion would wrongly strip their CSP. They MUST carry it.
            assertFullShellCsp(client.get("/apiary").headers["Content-Security-Policy"])
            assertFullShellCsp(client.get("/assetsx").headers["Content-Security-Policy"])

            // An unknown /api path is a JSON 404 (not text/html) → no shell document CSP. Same response used
            // for the StatusPages-error assertion below.
            val apiMiss = client.get("/api/v1/nope")
            apiMiss.status shouldBe HttpStatusCode.NotFound
            apiMiss.headers["Content-Security-Policy"] shouldBe null

            // A real bundle asset is not text/html → no shell document CSP (and bundle-wins sets none). Pin BOTH
            // shell trust slots: the js bundle (text/javascript) AND the css bundle (text/css).
            val shell = client.get("/docs/anything").bodyAsText()
            val jsRef = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(shell)?.groupValues?.get(1)
            val cssRef = Regex("href=\"(/assets/[^\"]+\\.css)\"").find(shell)?.groupValues?.get(1)
            jsRef.shouldNotBeNull()
            cssRef.shouldNotBeNull()
            client.get(jsRef).headers["Content-Security-Policy"] shouldBe null
            client.get(cssRef).headers["Content-Security-Policy"] shouldBe null
        }
    }

    test("[B1] a non-HTML static asset carries no document CSP, including on a conditional re-request") {
        restTest(Fixtures.demoDocs) {
            val first = client.get("/favicon.svg")
            first.status shouldBe HttpStatusCode.OK
            first.headers["Content-Security-Policy"] shouldBe null

            val etag = first.headers[HttpHeaders.ETag]
            val lastModified = first.headers[HttpHeaders.LastModified]
            when {
                etag != null -> {
                    val reReq = client.get("/favicon.svg") { header(HttpHeaders.IfNoneMatch, etag) }
                    reReq.headers["Content-Security-Policy"] shouldBe null
                }
                lastModified != null -> {
                    val reReq = client.get("/favicon.svg") { header(HttpHeaders.IfModifiedSince, lastModified) }
                    reReq.headers["Content-Security-Policy"] shouldBe null
                }
                // No validator emitted → no conditional-request 304 path exists, so the no-CSP invariant is
                // already fully covered by the base GET assertion above; nothing further to exercise here.
                else -> Unit
            }
        }
    }

    test("a StatusPages JSON error envelope carries no shell CSP (it is JSON, not text/html)") {
        restTest(Fixtures.demoDocs) {
            val response = client.get("/api/v1/nope")
            response.status shouldBe HttpStatusCode.NotFound
            response.headers["Content-Security-Policy"] shouldBe null
        }
    }

    test("the inline-script extractor fails closed on 0 and on 2 inline scripts, and yields one hash for the real shell") {
        shouldThrow<IllegalArgumentException> { inlineScriptHash("<html><head></head><body></body></html>") }
        shouldThrow<IllegalArgumentException> {
            inlineScriptHash("<script>doThing()</script>\n<script>doOther()</script>")
        }
        // A module src= script alongside the one inline bootstrap is the real-shell shape → exactly one hash.
        inlineScriptHash(bundledShellHtml().shouldNotBeNull()) shouldStartWith "sha256-"
    }
})

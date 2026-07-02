package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * `GET /assets/{path}` (§A4): membership-gated serving, the extension content-type map, and the
 * frozen decode-once/traversal rejections — plus bundle-wins (C1a item 1): a request that names a real
 * `static/assets/` bundle file is served the embedded bundle BEFORE the content-tree lookup, so a content
 * asset can never shadow the shell's own js/css. The per-asset sandbox CSP (item 2) is keyed to the served
 * MIME for content-tree assets only.
 */
class AssetRouteTest : FunSpec({

    test("a content-tree asset serves with its mapped content type") {
        restTest(Fixtures.demoDocs) {
            val response = client.get("/assets/infra/assets/diagram.svg")
            response.status shouldBe HttpStatusCode.OK
            response.contentType()?.withoutParameters() shouldBe ContentType.Image.SVG
        }
    }

    test("traversal and malformed encoding are rejected; .md files are not assets") {
        restTest(Fixtures.demoDocs) {
            fun errorCode(body: String): String =
                Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

            // Literal traversal: TreePath structurally cannot express `..` -> 400 invalid_path.
            val literal = client.get("/assets/../secrets")
            literal.status shouldBe HttpStatusCode.BadRequest
            errorCode(literal.bodyAsText()) shouldBe "invalid_path"

            // Encoded traversal: decode-once yields `..`, same law -> 400 invalid_path.
            val encoded = client.get("/assets/%2e%2e/secrets")
            encoded.status shouldBe HttpStatusCode.BadRequest
            errorCode(encoded.bodyAsText()) shouldBe "invalid_path"

            // Over-encoding decodes ONCE to the literal `%2e%2e` name — never re-scanned, so it is
            // just an unknown asset, not a traversal (PB-LINK-1 decode-once semantics).
            client.get("/assets/%252e%252e/secrets").status shouldBe HttpStatusCode.NotFound

            // A page is never an asset: .md is outside asset space -> 404 not_found.
            val markdown = client.get("/assets/guides/deploy-guide.md")
            markdown.status shouldBe HttpStatusCode.NotFound
            errorCode(markdown.bodyAsText()) shouldBe "not_found"

            client.get("/assets/no/such/file.png").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("the SPA's own bundle files fall back to the embedded static assets") {
        restTest(Fixtures.demoDocs) {
            // The shell names its hashed bundle; resolve it from the served HTML rather than
            // hardcoding a Vite hash.
            val shell = client.get("/docs/anything").bodyAsText()
            val src = Regex("src=\"(/assets/[^\"]+)\"").find(shell)?.groupValues?.get(1)
            src.shouldNotBeNull()
            client.get(src).status shouldBe HttpStatusCode.OK
        }
    }

    test("a NON-bundle indexed content asset whose on-disk file vanished is 404") {
        // A non-bundle name never hits the bundle-wins check (no static/assets/ file at that key), so it reaches
        // assetRead. An indexed asset whose file vanished is IndexedButMissing → a plain 404 (disk is source of
        // truth — not a torn 200). `orphan.bin` is not a bundle name, so bundle-wins never applies to it.
        withTempTree(seed = { root ->
            writePage(root, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n")
            Files.writeString(root.resolve("orphan.bin"), "real bytes")
        }) { root ->
            restTest(root) { harness ->
                // Indexed + present on disk → served.
                client.get("/assets/orphan.bin").status shouldBe HttpStatusCode.OK
                // Delete the file on disk but keep the STALE snapshot (no rebuild) so it stays in current.assets.
                Files.delete(root.resolve("orphan.bin"))
                // IndexedButMissing → 404 (disk is source of truth). The bundle-wins inversion (200 + embedded bundle)
                // applies ONLY to bundle paths; a vanished NON-bundle upload stays a 404.
                client.get("/assets/orphan.bin").status shouldBe HttpStatusCode.NotFound
            }
        }
    }

    test("a content asset at a bundle path is shadowed BY the bundle (bundle-wins integrity), across the js AND css slots") {
        // C1a item 1 (owner-locked, inverts the old content-wins shadow law for static/assets/ ONLY): a writer-planted
        // content asset that collides with the SPA's own hashed bundle name can NEVER be served in a <script src>/<link
        // href> slot the shell trusts (the bundle-shadow stored-XSS class). The bundle-wins check precedes assetRead, so
        // the bundle's REAL bytes win regardless of any indexed content asset at that path. Resolve both the js and css
        // slot names from the served shell (never a hardcoded Vite hash).
        withTempTree(seed = { root -> writePage(root, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            restTest(root) { harness ->
                val shell = client.get("/docs/anything").bodyAsText()
                val jsRef = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(shell)?.groupValues?.get(1)
                val cssRef = Regex("href=\"(/assets/[^\"]+\\.css)\"").find(shell)?.groupValues?.get(1)
                jsRef.shouldNotBeNull()
                cssRef.shouldNotBeNull()

                // The REAL bundle bytes, captured before anything is planted.
                val jsBytes = client.get(jsRef).bodyAsText()
                val cssBytes = client.get(cssRef).bodyAsText()

                // Plant a content asset at BOTH bundle tree paths, then rebuild so they are indexed (the shadow).
                Files.writeString(root.resolve(jsRef.removePrefix("/assets/")), "SHADOW-JS")
                Files.writeString(root.resolve(cssRef.removePrefix("/assets/")), "SHADOW-CSS")
                harness.builder.rebuild()

                // Bundle-wins: the served bytes are the REAL bundle, NOT the planted shadow — for BOTH trust slots.
                val js = client.get(jsRef)
                js.status shouldBe HttpStatusCode.OK
                js.bodyAsText() shouldBe jsBytes
                js.bodyAsText() shouldNotBe "SHADOW-JS"
                val css = client.get(cssRef)
                css.status shouldBe HttpStatusCode.OK
                css.bodyAsText() shouldBe cssBytes

                // [B2] Delete the planted js on disk WITHOUT rebuilding (it stays indexed): the bundle-wins check
                // precedes assetRead, so the route NEVER reaches IndexedButMissing for a bundle path → 200 + the
                // embedded bundle bytes, NOT 404. The explicit inversion of the old IndexedButMissing→404 expectation
                // for a bundle-collision path.
                Files.delete(root.resolve(jsRef.removePrefix("/assets/")))
                val vanished = client.get(jsRef)
                vanished.status shouldBe HttpStatusCode.OK
                vanished.bodyAsText() shouldBe jsBytes
            }
        }
    }

    test("enforced anonymous: a shadow at a bundle path still serves the PUBLIC bundle bytes, never the planted content") {
        // The bundle-wins check runs BEFORE the read gate, so an anonymous caller under enforced auth gets the public
        // bundle (the shell + login page must load) and NEVER the planted content bytes. Pre-seed the shadow using the
        // bundle names read straight from the embedded shell (the app isn't booted yet, so we can't resolve from a live
        // GET — the embedded resource is the same source of truth).
        val (jsRef, cssRef) = embeddedShellBundleRefs()
        enforcedAnonApp(builtinAuthEnabled = true, proxyAuthEnabled = false, seed = { root ->
            Files.writeString(root.resolve(jsRef.removePrefix("/assets/")), "SHADOW-JS")
            Files.writeString(root.resolve(cssRef.removePrefix("/assets/")), "SHADOW-CSS")
        }) { app ->
            val js = app.client.get(jsRef)
            js.status shouldBe HttpStatusCode.OK
            js.bodyAsText() shouldBe embeddedBundleBytes(jsRef).toString(Charsets.UTF_8)
            val css = app.client.get(cssRef)
            css.status shouldBe HttpStatusCode.OK
            css.bodyAsText() shouldBe embeddedBundleBytes(cssRef).toString(Charsets.UTF_8)
        }
    }

    test("the per-asset sandbox CSP is keyed to the served content type") {
        // C1a item 2: a scriptable served type (svg, pdf) gets `Content-Security-Policy: sandbox; script-src 'none'`;
        // an inert one (png) gets none. An uploaded evil.html is already inert at HEAD — .html is not in the asset map
        // → application/octet-stream + nosniff → a browser DOWNLOADS it, never executes — so octet-stream needs (and
        // gets) no sandbox. Stated explicitly so the no-CSP-on-html assertion reads as deliberate, not a miss.
        withTempTree(seed = { root ->
            writePage(root, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n")
            Files.writeString(root.resolve("diagram.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
            Files.write(root.resolve("photo.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            Files.writeString(root.resolve("paper.pdf"), "%PDF-1.4\n")
            Files.writeString(root.resolve("evil.html"), "<script>alert(1)</script>")
        }) { root ->
            restTest(root) { _ ->
                val svg = client.get("/assets/diagram.svg")
                svg.status shouldBe HttpStatusCode.OK
                svg.headers["Content-Security-Policy"] shouldBe "sandbox; script-src 'none'"

                val pdf = client.get("/assets/paper.pdf")
                pdf.status shouldBe HttpStatusCode.OK
                pdf.headers["Content-Security-Policy"] shouldBe "sandbox; script-src 'none'"

                val png = client.get("/assets/photo.png")
                png.status shouldBe HttpStatusCode.OK
                png.headers["Content-Security-Policy"] shouldBe null

                val html = client.get("/assets/evil.html")
                html.status shouldBe HttpStatusCode.OK
                html.contentType()?.withoutParameters() shouldBe ContentType.Application.OctetStream
                html.headers["X-Content-Type-Options"] shouldBe "nosniff"
                html.headers["Content-Security-Policy"] shouldBe null
            }
        }
    }

    // B1: under ENFORCED auth the asset route is gated, but the SPA's OWN bundle (its hashed js/css) must stay
    // PUBLIC so the app shell — including the login page — loads for an anonymous user. The gated content read
    // still 401s for an anonymous content asset (no existence leak: an absent non-bundle 401s identically).
    test("enforced builtin: anonymous GET /assets/<bundle.js> → 200 (the shell + login page load)") {
        enforcedAnonApp(builtinAuthEnabled = true, proxyAuthEnabled = false) { app ->
            val bundle = app.resolveBundleRef()
            app.client.get(bundle).status shouldBe HttpStatusCode.OK
        }
    }

    test("enforced proxy: anonymous GET /assets/<bundle.js> → 200") {
        enforcedAnonApp(builtinAuthEnabled = false, proxyAuthEnabled = true, proxySecret = "s") { app ->
            val bundle = app.resolveBundleRef()
            app.client.get(bundle).status shouldBe HttpStatusCode.OK
        }
    }

    test("enforced: anonymous GET /assets/<non-colliding content asset> → 401 (NOT 404 — no existence leak)") {
        // Seed a content asset whose name is NOT any bundle filename, so it cannot fall through to the public
        // bundle. An anonymous read must 401 (gate denies before membership), never 404 (which would leak that
        // the path is absent vs. present-but-unauthorized).
        enforcedAnonApp(builtinAuthEnabled = true, proxyAuthEnabled = false, seed = { root ->
            Files.writeString(root.resolve("orphan-asset.bin"), "secret content bytes")
        }) { app ->
            val bundle = app.resolveBundleRef()
            (bundle == "/assets/orphan-asset.bin") shouldBe false // the seed demonstrably does not match the bundle
            app.client.get("/assets/orphan-asset.bin").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("enforced: anonymous GET /assets/<absent non-bundle> → 401 (same as a content asset — the oracle is closed)") {
        enforcedAnonApp(builtinAuthEnabled = true, proxyAuthEnabled = false) { app ->
            app.client.get("/assets/no/such/file.png").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})

/** The embedded shell's js + css bundle refs, read from the classpath resource (never a hardcoded Vite hash). */
private fun embeddedShellBundleRefs(): Pair<String, String> {
    val html = AssetRouteTest::class.java.classLoader.getResourceAsStream("static/index.html")!!
        .use { it.readBytes().toString(Charsets.UTF_8) }
    val js = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(html)!!.groupValues[1]
    val css = Regex("href=\"(/assets/[^\"]+\\.css)\"").find(html)!!.groupValues[1]
    return js to css
}

/** The embedded bundle bytes behind a served `/assets/<name>` ref — read from the classpath, the same source
 *  bundle-wins serves, so an equality assertion proves the PUBLIC bundle (not planted content) was returned. */
private fun embeddedBundleBytes(ref: String): ByteArray =
    AssetRouteTest::class.java.classLoader.getResourceAsStream("static$ref")!! // Test: the embedded bundle always exists
        .use { it.readBytes() }

/** The shell's own hashed bundle `src`, resolved from the served HTML (never a hardcoded Vite hash). */
private suspend fun ApplicationTestBuilder.resolveBundleRef(): String {
    val shell = client.get("/docs/anything").bodyAsText()
    val src = Regex("src=\"(/assets/[^\"]+)\"").find(shell)?.groupValues?.get(1)
    src.shouldNotBeNull()
    return src
}

/**
 * Boots the route stack under ENFORCED auth with a fixed [Principal.Anonymous] over a real seeded tree (mirrors
 * `AuthMatrixTest.withApp`). The default seed is a single `doc.md` so the shell renders; [seed] can add assets.
 */
private fun enforcedAnonApp(
    builtinAuthEnabled: Boolean,
    proxyAuthEnabled: Boolean,
    proxySecret: String? = null,
    seed: (Path) -> Unit = {},
    block: suspend (ApplicationTestBuilder) -> Unit,
) {
    val root = Files.createTempDirectory("plainbase-asset-enforced")
    try {
        Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n")
        seed(root)
        IndexHarness(root, contentStore = LocalContentStore(root)).use { harness ->
            harness.builder.rebuild()
            val searchDb = SearchDb(Files.createTempDirectory("asset-enforced-search").resolve("search.db"))
            val ctx = harness.testRouteContext(
                contentStore = LocalContentStore(root),
                searchProvider = Fts5SearchProvider(searchDb),
                enforced = true,
                builtinAuthEnabled = builtinAuthEnabled,
                proxyAuthEnabled = proxyAuthEnabled,
                proxySecret = proxySecret,
                extract = fixedPrincipal(Principal.Anonymous),
            )
            testApplication {
                application { plainbaseModule(ctx) }
                block(this)
            }
        }
    } finally {
        root.toFile().deleteRecursively()
    }
}

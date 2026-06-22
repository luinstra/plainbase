package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * `GET /assets/{path}` (§A4): membership-gated serving, the extension content-type map, and the
 * frozen decode-once/traversal rejections — plus the SPA-bundle fallback that keeps the shell's
 * own js/css loadable behind the content-asset route.
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

    test("an indexed content asset whose on-disk file vanished is 404 — NOT the bundled-static fallback") {
        // BLOCKING 3: `assetRead` must separate content-tree MEMBERSHIP from the disk READ. An indexed asset whose
        // file vanished is IndexedButMissing (→ 404), NOT NotContentAsset (which alone may fall through to bundled
        // static). Otherwise a vanished upload would unmask a bundled-static name it shadowed (disk is truth).
        withTempTree(seed = { root ->
            writePage(root, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n")
            Files.writeString(root.resolve("orphan.bin"), "real bytes")
        }) { root ->
            restTest(root) { harness ->
                // Indexed + present on disk → served.
                client.get("/assets/orphan.bin").status shouldBe HttpStatusCode.OK
                // Delete the file on disk but keep the STALE snapshot (no rebuild) so it stays in current.assets.
                Files.delete(root.resolve("orphan.bin"))
                // IndexedButMissing → 404 (not bundled, not a torn 200). `orphan.bin` isn't a bundled name anyway,
                // but the law is structural: an indexed-but-vanished asset never reaches the bundled fallback.
                client.get("/assets/orphan.bin").status shouldBe HttpStatusCode.NotFound
            }
        }
    }

    test("a content asset shadowing a bundled-static name wins; once it vanishes on disk it 404s, NOT the bundled file") {
        // The exploit shape: upload an asset whose path collides with a bundled-static name, then delete it on disk.
        // The content asset wins while present; once gone it must 404 (IndexedButMissing), never serve the BUNDLED
        // version. Resolve the bundled name from the shell so no Vite hash is hardcoded.
        withTempTree(seed = { root -> writePage(root, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            restTest(root) { harness ->
                val shell = client.get("/docs/anything").bodyAsText()
                val bundledRef = Regex("src=\"/assets/([^\"]+)\"").find(shell)?.groupValues?.get(1)
                bundledRef.shouldNotBeNull()
                val bundledBytes = client.get("/assets/$bundledRef").bodyAsText()

                // Plant a content asset at the SAME tree path as the bundled name, then rebuild so it is indexed.
                val shadow = root.resolve(bundledRef)
                Files.createDirectories(shadow.parent)
                Files.writeString(shadow, "SHADOW-CONTENT")
                harness.builder.rebuild()

                // Content wins while present (disk is source of truth): the served bytes are the shadow, not bundled.
                val shadowed = client.get("/assets/$bundledRef")
                shadowed.status shouldBe HttpStatusCode.OK
                shadowed.bodyAsText() shouldBe "SHADOW-CONTENT"

                // Delete on disk WITHOUT rebuilding (still indexed) → IndexedButMissing → 404, NOT the bundled file.
                Files.delete(shadow)
                val vanished = client.get("/assets/$bundledRef")
                vanished.status shouldBe HttpStatusCode.NotFound
                (vanished.bodyAsText() == bundledBytes) shouldBe false // never the unmasked bundled bytes
            }
        }
    }
})

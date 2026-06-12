package com.plainbase.frameworks.ktor

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
})

package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.withTempTree
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

/**
 * §A4 `markdown` verbatim guarantee: the field is the lenient UTF-8 decode of the EXACT raw file
 * bytes — BOM char included, CRLF preserved, frontmatter included — because Phase 5's proposal
 * model hashes exactly what the agent read. `content_hash` covers the same raw (pre-decode) bytes.
 */
class VerbatimMarkdownTest : FunSpec({

    test("markdown round-trips a BOM + CRLF file verbatim, and content_hash covers the raw bytes") {
        val text = "---\r\ntitle: CRLF Page\r\n---\r\n\r\n# CRLF Page\r\n\r\nA body line.\r\n"
        val rawBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)

        withTempTree(seed = { root ->
            Files.write(root.resolve("crlf.md"), rawBytes)
        }) { root ->
            restTest(root) {
                val response = client.get("/api/v1/pages/by-path/crlf")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                // BOM decodes to a leading U+FEFF char — included, never stripped.
                body.getValue("markdown").jsonPrimitive.content shouldBe "\uFEFF$text"

                val expectedHash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes))
                body.getValue("content_hash").jsonPrimitive.content shouldBe expectedHash
                body.getValue("title").jsonPrimitive.content shouldBe "CRLF Page"
            }
        }
    }
})

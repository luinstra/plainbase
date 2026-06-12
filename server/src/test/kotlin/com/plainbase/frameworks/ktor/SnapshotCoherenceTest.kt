package com.plainbase.frameworks.ktor

import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Page payloads are SNAPSHOT-COHERENT: markdown, html, `content_hash`, and the citation all come
 * from the one published index world, with zero disk reads at request time. An on-disk edit
 * between rescans therefore changes NOTHING a page endpoint serves — the old failure mode was
 * stale html under a freshly recomputed hash, an incoherent citation (the exact value Phase 5
 * heading-citations lean on). A rescan publishes the new world atomically; assets deliberately
 * stay live-disk (see `AssetRoute`).
 */
class SnapshotCoherenceTest : FunSpec({

    fun sha256(text: String): String =
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    test("an on-disk edit after the index build changes nothing until rescan — markdown, html, and hash stay matched") {
        val original = "---\ntitle: Coherent\n---\n\n# Coherent\n\nOriginal body.\n"
        val edited = "---\ntitle: Coherent\n---\n\n# Coherent\n\nEdited body, longer than before.\n"

        withTempTree(seed = { root -> writePage(root, "coherent.md", original) }) { root ->
            restTest(root) {
                val id = Json.parseToJsonElement(client.get("/api/v1/pages/by-path/coherent").bodyAsText())
                    .jsonObject.string("id")

                // Edit the file on disk AFTER the snapshot published.
                Files.writeString(root.resolve("coherent.md"), edited)

                val page = Json.parseToJsonElement(client.get("/api/v1/pages/$id").bodyAsText()).jsonObject
                page.string("markdown") shouldBe original
                page.string("content_hash") shouldBe sha256(original)
                page.getValue("citation").jsonObject.string("content_hash") shouldBe sha256(original)

                val html = Json.parseToJsonElement(client.get("/api/v1/pages/$id/html").bodyAsText()).jsonObject
                html.string("html") shouldContain "Original body."
                html.string("content_hash") shouldBe sha256(original) // the hash matches the html it travels with
                html.getValue("citation").jsonObject.string("content_hash") shouldBe sha256(original)

                // Rescan publishes the edited world — both endpoints flip together.
                client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.OK
                val rescanned = Json.parseToJsonElement(client.get("/api/v1/pages/$id").bodyAsText()).jsonObject
                rescanned.string("markdown") shouldBe edited
                rescanned.string("content_hash") shouldBe sha256(edited)
                val rescannedHtml = Json.parseToJsonElement(client.get("/api/v1/pages/$id/html").bodyAsText()).jsonObject
                rescannedHtml.string("html") shouldContain "Edited body"
                rescannedHtml.string("content_hash") shouldBe sha256(edited)
            }
        }
    }
})

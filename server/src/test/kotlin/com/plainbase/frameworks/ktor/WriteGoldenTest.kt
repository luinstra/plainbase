package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.ktor.dto.WriteConflictReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PB-WRITE-1 (W3a) FOREVER golden corpus — the four tier-1 `write-*.json` snapshots plus the
 * adversarial byte-identical round-trip (the thing JSON transport could NOT do) and the frozen
 * `reason`-enum assertion. Runs over [writeRestTest] (a temp COPY of the fixture tree), so a write
 * never dirties the committed `Fixtures.demoDocs`. Comparison is parsed-JSON-tree equality;
 * `content_hash`/`current_hash` are recomputed from the bytes at test time (never committed).
 *
 * These shapes froze when W3a landed — see the NEVER-CHANGE POLICY in `WriteDtos.kt` and the
 * freeze ledger in `acceptance/ForeverApiGoldenSuite.kt`.
 */
class WriteGoldenTest : FunSpec({

    val citations = CitationFactory()
    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("guides/deploy-guide.md"), PageId.require(deployGuideId), materialized = false)
    }

    fun markdown(): ContentType = ContentType.parse("text/markdown")
    fun etag(hash: String) = "\"$hash\""
    suspend fun HttpResponse.tree(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    test("write-put-ok.json — a 200 Written is exactly {content_hash, commit:null}; warning ABSENT") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val edited = original + "\ngolden ok.\n".toByteArray()
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                contentType(markdown())
                setBody(edited)
            }
            put.status shouldBe HttpStatusCode.OK
            put.tree() shouldBe RestGolden.load("write-put-ok.json", mapOf("content_hash" to citations.contentHash(edited)))
        }
    }

    test("write-conflict-content-changed.json — a 409 drift carries reason + current_* (structural)") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val aBytes = original + "\nlanded.\n".toByteArray()
            client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(aBytes)
            }.status shouldBe HttpStatusCode.OK

            val drift = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(original + "\nlost.\n".toByteArray())
            }
            drift.status shouldBe HttpStatusCode.Conflict
            // Structural, hash/path/content recomputed — current_content is asserted present-non-null,
            // never coupled to a fixture literal (the golden carries the placeholders).
            drift.tree() shouldBe RestGolden.load(
                "write-conflict-content-changed.json",
                mapOf(
                    "current_content" to String(aBytes, Charsets.UTF_8),
                    "current_hash" to citations.contentHash(aBytes),
                    "current_path" to "guides/deploy-guide.md",
                ),
            )
        }
    }

    // Codex P1 (golden robustness): a `current_content` carrying JSON-special chars (`"` and `\`)
    // must round-trip through the golden substitution. RestGolden.load substitutes into the PARSED
    // tree (auto-escaping the value), so a future fixture edit adding a quote/backslash can never
    // silently break the FOREVER suite by producing invalid JSON via raw text replace.
    test("write-conflict-content-changed.json — a current_content with quotes and backslashes round-trips") {
        val pageId = "0190aaaa-bbbb-7ccc-8ddd-0000000000c1"
        // The landed (A) on-disk content deliberately holds a `"` and a `\` — the chars that would
        // corrupt a raw pre-parse text substitution.
        val original = "---\nid: $pageId\ntitle: Special\n---\n\n# Special\n\nplain body.\n".toByteArray()
        val seedSpecial: (
            IdMapRepository,
        ) -> Unit = { it.bind(TreePath.require("special.md"), PageId.require(pageId), materialized = true) }
        val tree = java.nio.file.Files.createTempDirectory("plainbase-write-special")
        try {
            java.nio.file.Files.write(tree.resolve("special.md"), original)
            writeRestTest(tree, seedSpecial) { _ ->
                val hBase = citations.contentHash(original)
                val aBytes = "---\nid: $pageId\ntitle: Special\n---\n\n# Special\n\nsay \"hello\" and a path C:\\tmp\\x.\n".toByteArray()
                client.put("/api/v1/pages/$pageId") {
                    header(HttpHeaders.IfMatch, etag(hBase))
                    contentType(markdown())
                    setBody(aBytes)
                }.status shouldBe HttpStatusCode.OK

                val drift = client.put("/api/v1/pages/$pageId") {
                    header(HttpHeaders.IfMatch, etag(hBase))
                    contentType(markdown())
                    setBody(original + "\nlost.\n".toByteArray())
                }
                drift.status shouldBe HttpStatusCode.Conflict
                drift.tree() shouldBe RestGolden.load(
                    "write-conflict-content-changed.json",
                    mapOf(
                        "current_content" to String(aBytes, Charsets.UTF_8),
                        "current_hash" to citations.contentHash(aBytes),
                        "current_path" to "special.md",
                    ),
                )
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    test("write-conflict-page-deleted.json — a 409 page_deleted has current_* all null") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            java.nio.file.Files.delete(harness.root.resolve("guides/deploy-guide.md"))
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(original)
            }
            put.status shouldBe HttpStatusCode.Conflict
            put.tree() shouldBe RestGolden.load("write-conflict-page-deleted.json")
        }
    }

    test("write-unsupported-slug.json — a 422 slug change is code+field, NO reason key") {
        val pageId = "0190aaaa-bbbb-7ccc-8ddd-000000000099"
        val original = "---\nid: $pageId\ntitle: Slugged\nslug: original-slug\n---\n\n# Slugged\n\nbody.\n".toByteArray()
        val seedMat: (IdMapRepository) -> Unit = { it.bind(TreePath.require("slugged.md"), PageId.require(pageId), materialized = true) }
        val tree = java.nio.file.Files.createTempDirectory("plainbase-write-golden")
        try {
            java.nio.file.Files.write(tree.resolve("slugged.md"), original)
            writeRestTest(tree, seedMat) { _ ->
                val reslug = String(original, Charsets.UTF_8).replace("slug: original-slug", "slug: new-slug").toByteArray()
                val put = client.put("/api/v1/pages/$pageId") {
                    header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                    contentType(markdown())
                    setBody(reslug)
                }
                put.status shouldBe HttpStatusCode.UnprocessableEntity
                put.tree() shouldBe RestGolden.load("write-unsupported-slug.json")
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    test("the drift reason enum is EXACTLY {content_changed, page_moved, page_deleted}; id_changed is NOT a member") {
        WriteConflictReason.ALL shouldBe setOf("content_changed", "page_moved", "page_deleted")
        ("id_changed" in WriteConflictReason.ALL) shouldBe false
    }

    test("adversarial RAW fidelity — a BOM file and an invalid-UTF-8 file round-trip GET→PUT byte-identically") {
        val bomId = "0190aaaa-bbbb-7ccc-8ddd-0000000000a1"
        val badId = "0190aaaa-bbbb-7ccc-8ddd-0000000000a2"
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "---\nid: $bomId\ntitle: BOM\n---\n\n# BOM\n\nbody.\n".toByteArray()
        val badBytes = "---\nid: $badId\ntitle: Bad\ndesc: ".toByteArray() +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\n---\n\n# Bad\n\nbody.\n".toByteArray()
        val seedAdv: (IdMapRepository) -> Unit = { idMap ->
            idMap.bind(TreePath.require("bom.md"), PageId.require(bomId), materialized = true)
            idMap.bind(TreePath.require("bad.md"), PageId.require(badId), materialized = true)
        }
        val tree = java.nio.file.Files.createTempDirectory("plainbase-write-adversarial")
        try {
            java.nio.file.Files.write(tree.resolve("bom.md"), bomBytes)
            java.nio.file.Files.write(tree.resolve("bad.md"), badBytes)
            writeRestTest(tree, seedAdv) { harness ->
                for ((id, original, name) in listOf(Triple(bomId, bomBytes, "bom.md"), Triple(badId, badBytes, "bad.md"))) {
                    val got = client.get("/api/v1/pages/$id")
                    got.status shouldBe HttpStatusCode.OK
                    val tag = got.headers[HttpHeaders.ETag] // GET's ETag IS the accepted If-Match
                    tag shouldBe etag(citations.contentHash(original))
                    val put = client.put("/api/v1/pages/$id") {
                        header(HttpHeaders.IfMatch, tag!!)
                        contentType(markdown())
                        setBody(original)
                    }
                    put.status shouldBe HttpStatusCode.OK
                    // The whole point: on-disk bytes == original bytes, byte-for-byte (a byte comparison,
                    // not a JSON golden — JSON could never round-trip these).
                    harness.diskBytes(name) shouldBe original
                    val body = Json.parseToJsonElement(put.bodyAsText()).jsonObject
                    body.getValue("content_hash").jsonPrimitive.content shouldBe citations.contentHash(original)
                }
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }
})

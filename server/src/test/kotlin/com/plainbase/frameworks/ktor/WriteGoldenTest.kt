package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.ktor.dto.CreatedButUnindexedResponse
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.WriteConflictReason
import com.plainbase.frameworks.ktor.dto.WriteWarning
import com.plainbase.frameworks.ktor.dto.WriteWarningCode
import com.plainbase.frameworks.ktor.routes.createUrl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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

    test("write-post-ok.json — a 201 create returns {id, url, content_hash, commit:null}; warning ABSENT") {
        // The composed bytes are deterministic given a seeded id, so the recompute is stable. The route
        // composes: ---\nid:<id>\ntitle:"<title>"\n---\n\n (empty body) — recompute over the same bytes.
        val createdId = "01900000-0000-7000-8000-000000000001"
        val composed = "---\nid: $createdId\ntitle: \"Golden Create\"\n---\n\n".toByteArray()
        writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider()) { harness ->
            val post = client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody("""{"folder":"guides","title":"Golden Create"}""")
            }
            post.status shouldBe HttpStatusCode.Created
            // The 201 GAINS the minted `id` + the SERVER-AUTHORITATIVE canonical `url` (W6, additive
            // PB-WRITE-1 revision). The golden pins both; `url` is the published `IndexedPage.url`, NOT a
            // client-/path-derived slug — asserted against the snapshot below so the literal can't drift.
            post.tree() shouldBe RestGolden.load("write-post-ok.json", mapOf("content_hash" to citations.contentHash(composed)))
            harness.builder.current.byId[PageId.require(createdId)]?.url shouldBe "/docs/guides/golden-create"
        }
    }

    test("write-post-ok-unicode.json — a 201 create's url is the slugified urlPath, NOT the raw on-disk path") {
        // The divergence guard (house rule). The slug "Café Ω" slugifies to the NON-ASCII `café-ω`, so the
        // on-disk filename is `café-ω.md` (raw unicode bytes) while the canonical url percent-encodes the
        // urlPath: `/docs/guides/caf%C3%A9-%CF%89`. A naive re-compose from the file path would give a
        // DIFFERENT string (raw é/ω, or a title slug) — proving `url` is the published IndexedPage.url.
        val createdId = "01900000-0000-7000-8000-000000000001"
        val composed = "---\nid: $createdId\ntitle: \"Report\"\nslug: \"Café Ω\"\n---\n\n".toByteArray()
        writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider()) { harness ->
            val post = client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody("""{"folder":"guides","title":"Report","slug":"Café Ω"}""")
            }
            post.status shouldBe HttpStatusCode.Created
            post.tree() shouldBe RestGolden.load("write-post-ok-unicode.json", mapOf("content_hash" to citations.contentHash(composed)))
            // The response url IS the published IndexedPage.url: the PercentCoding.encodePath(urlPath) form,
            // never a percent-encode of the raw on-disk filename nor a slug of the title.
            harness.builder.current.byId[PageId.require(createdId)]?.url shouldBe "/docs/guides/caf%C3%A9-%CF%89"
        }
    }

    test("createUrl falls back to the /p/{id} permalink for a path-space loser (no fabricated /docs/<raw path>)") {
        // A published page whose canonical url is null (a same-slug collision loser) is reachable ONLY via
        // its permalink. createUrl must return that permalink — NEVER a fabricated `/docs/<raw path>` that
        // points at a 404. Both `a b.md` (0x20 wins on raw-byte order) and `a-b.md` slugify to `a-b`, so the
        // latter is the loser (url = null) — the same induction RestRedirectTest uses.
        val winnerId = "0190aaaa-bbbb-7ccc-8ddd-0000000000d1"
        val loserId = "0190aaaa-bbbb-7ccc-8ddd-0000000000d2"
        val seedCollision: (IdMapRepository) -> Unit = { idMap ->
            idMap.bind(TreePath.require("a b.md"), PageId.require(winnerId), materialized = true)
            idMap.bind(TreePath.require("a-b.md"), PageId.require(loserId), materialized = true)
        }
        val tree = java.nio.file.Files.createTempDirectory("plainbase-write-loser")
        try {
            java.nio.file.Files.write(tree.resolve("a b.md"), "---\nid: $winnerId\ntitle: Winner\n---\n\n# Winner\n".toByteArray())
            java.nio.file.Files.write(tree.resolve("a-b.md"), "---\nid: $loserId\ntitle: Loser\n---\n\n# Loser\n".toByteArray())
            writeRestTest(tree, seedCollision) { harness ->
                val loser = PageId.require(loserId)
                // The induction held: the loser really has a null canonical url.
                harness.builder.current.byId[loser]?.url shouldBe null
                // So createUrl serves the permalink — the SAME `/p/{id}` shape PermalinkRoute resolves and
                // RestRedirectTest's loser alias lands on — not a `/docs/<raw path>` fabrication.
                createUrl(loser, harness.builder.current) shouldBe "/p/$loserId"
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    test("a created-but-unindexed 201 carries url:null (no fabricated /docs/… from the raw path)") {
        // The page is unpublished on this branch, so there is NO reliable canonical url until
        // reconciliation — fabricating one from the raw on-disk path would diverge from the eventual
        // canonical (a `_folder.yaml` slug override / unicode / collision-de-dup shifts the segment).
        // The honest wire is `url: null` (present-null via RestJson's explicitNulls), and the clean
        // create's non-null url shape stays untouched (its golden is asserted above).
        val response = CreatedButUnindexedResponse(
            id = "01900000-0000-7000-8000-000000000001",
            url = null,
            contentHash = "sha256:" + "0".repeat(64),
            commit = null,
            warning = WriteWarning(code = WriteWarningCode.REINDEX_DEFERRED, message = "deferred"),
        )
        val tree = RestJson.encodeToString(CreatedButUnindexedResponse.serializer(), response).let(Json::parseToJsonElement).jsonObject
        // url is PRESENT and explicitly null — never a fabricated `/docs/…` string.
        ("url" in tree) shouldBe true
        (tree.getValue("url") is JsonNull) shouldBe true
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

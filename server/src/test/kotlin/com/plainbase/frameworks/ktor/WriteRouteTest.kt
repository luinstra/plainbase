package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PB-WRITE-1 (W3a) behavioral named tests for `PUT /api/v1/pages/{id}` — the WriteOutcome→wire
 * mapping, the `If-Match`/`ETag` round-trip, the body cap, retry-idempotency, and the two id-tamper
 * layers. All tests run over [writeRestTest] (a temp COPY of the fixture tree) so a write never
 * dirties the committed `Fixtures.demoDocs`.
 */
class WriteRouteTest : FunSpec({

    val citations = CitationFactory()
    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val welcomeId = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("guides/deploy-guide.md"), PageId.require(deployGuideId), materialized = false)
        idMap.bind(TreePath.require("index.md"), PageId.require(welcomeId), materialized = false)
    }

    fun markdown(): ContentType = ContentType.parse("text/markdown")
    fun etag(hash: String) = "\"$hash\""

    suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    suspend fun HttpResponse.errorJson(): JsonObject = json().getValue("error").jsonObject

    // 1. Round-trip byte-fidelity PUT (master criterion 1).
    test("a GET ETag round-trips byte-for-byte through PUT (200, on-disk identical, hash == ETag)") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val got = client.get("/api/v1/pages/$deployGuideId")
            got.status shouldBe HttpStatusCode.OK
            val tag = got.headers[HttpHeaders.ETag].shouldNotBeNull()
            val original = harness.diskBytes("guides/deploy-guide.md")
            tag shouldBe etag(citations.contentHash(original))

            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, tag)
                contentType(markdown())
                setBody(original)
            }
            put.status shouldBe HttpStatusCode.OK
            val body = put.json()
            body.getValue("content_hash").jsonPrimitive.content shouldBe citations.contentHash(original)
            body.getValue("commit") shouldBe JsonNull
            body.containsKey("warning") shouldBe false
            harness.diskBytes("guides/deploy-guide.md") shouldBe original
        }
    }

    // 2. Adversarial round-trip — BOM + invalid-UTF-8 each survive GET→PUT byte-identically.
    test("a BOM file and an invalid-UTF-8 file each round-trip byte-identically (RAW proves fidelity)") {
        val bomId = "0190aaaa-bbbb-7ccc-8ddd-000000000001"
        val badId = "0190aaaa-bbbb-7ccc-8ddd-000000000002"
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "---\nid: $bomId\ntitle: BOM\n---\n\n# BOM\n\nbody.\n".toByteArray()
        val badBytes = "---\nid: $badId\ntitle: Bad\ndesc: ".toByteArray() +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\n---\n\n# Bad\n\nbody.\n".toByteArray()
        val adversarialSeed: (IdMapRepository) -> Unit = { idMap ->
            idMap.bind(TreePath.require("bom.md"), PageId.require(bomId), materialized = true)
            idMap.bind(TreePath.require("bad.md"), PageId.require(badId), materialized = true)
        }
        writeWithFiles(adversarialSeed, "bom.md" to bomBytes, "bad.md" to badBytes) { harness ->
            for ((id, bytes, name) in listOf(Triple(bomId, bomBytes, "bom.md"), Triple(badId, badBytes, "bad.md"))) {
                val got = client.get("/api/v1/pages/$id")
                got.status shouldBe HttpStatusCode.OK
                val tag = got.headers[HttpHeaders.ETag].shouldNotBeNull()
                tag shouldBe etag(citations.contentHash(bytes))
                val put = client.put("/api/v1/pages/$id") {
                    header(HttpHeaders.IfMatch, tag)
                    contentType(markdown())
                    setBody(bytes)
                }
                put.status shouldBe HttpStatusCode.OK
                put.json().getValue("content_hash").jsonPrimitive.content shouldBe citations.contentHash(bytes)
                harness.diskBytes(name) shouldBe bytes
            }
        }
    }

    // 3. Two-sessions drift 409.
    test("a stale If-Match after another save is 409 content_changed with current_* and no clobber") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val aBytes = original + "\nA's edit.\n".toByteArray()
            val a = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(aBytes)
            }
            a.status shouldBe HttpStatusCode.OK
            val hPrime = citations.contentHash(aBytes)

            val bBytes = original + "\nB's edit.\n".toByteArray()
            val b = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(bBytes)
            }
            b.status shouldBe HttpStatusCode.Conflict
            val err = b.errorJson()
            err.getValue("code").jsonPrimitive.content shouldBe "conflict"
            err.getValue("reason").jsonPrimitive.content shouldBe "content_changed"
            err.getValue("current_hash").jsonPrimitive.content shouldBe hPrime
            err.getValue("current_content").jsonPrimitive.content shouldBe String(aBytes, Charsets.UTF_8)
            err.getValue("current_path").jsonPrimitive.content shouldBe "guides/deploy-guide.md"
            harness.diskBytes("guides/deploy-guide.md") shouldBe aBytes // A's bytes survive
        }
    }

    // 4. page_deleted 409 — indexed-but-gone file.
    test("an indexed page deleted on disk is 409 page_deleted with current_* null") {
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
            val err = put.errorJson()
            err.getValue("reason").jsonPrimitive.content shouldBe "page_deleted"
            err.getValue("current_content") shouldBe JsonNull
            err.getValue("current_hash") shouldBe JsonNull
            err.getValue("current_path") shouldBe JsonNull
        }
    }

    // 5. Retry-idempotency — stale If-Match but on-disk already == submitted → 200 no-op.
    test("a retried PUT with a stale If-Match but identical on-disk bytes is a 200 no-op, not 409") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val newBytes = original + "\nlanded once.\n".toByteArray()
            val first = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(newBytes)
            }
            first.status shouldBe HttpStatusCode.OK
            val hPrime = citations.contentHash(newBytes)

            // The retry uses the SAME stale base hash but the same bytes already on disk → 200 no-op.
            val retry = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(newBytes)
            }
            retry.status shouldBe HttpStatusCode.OK
            val body = retry.json()
            body.getValue("content_hash").jsonPrimitive.content shouldBe hPrime
            body.containsKey("warning") shouldBe false
            harness.diskBytes("guides/deploy-guide.md") shouldBe newBytes
        }
    }

    // 6. ID-tamper, route layer (R1) — incl. the lenient-decode trap.
    test("a buffer whose frontmatter id differs from (or removes) the path-param id is 422 id_change_unsupported") {
        val pageId = "0190aaaa-bbbb-7ccc-8ddd-000000000010"
        val original = "---\nid: $pageId\ntitle: Stable\n---\n\n# Stable\n\nbody.\n".toByteArray()
        val seedMat: (IdMapRepository) -> Unit = { it.bind(TreePath.require("stable.md"), PageId.require(pageId), materialized = true) }
        writeWithFiles(seedMat, "stable.md" to original) { harness ->
            val hBase = citations.contentHash(original)

            // (a) a different id.
            val changed = "---\nid: 0190ffff-bbbb-7ccc-8ddd-000000000010\ntitle: Stable\n---\n\n# Stable\n\nbody.\n".toByteArray()
            val a = client.put("/api/v1/pages/$pageId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(changed)
            }
            a.status shouldBe HttpStatusCode.UnprocessableEntity
            a.errorJson().getValue("code").jsonPrimitive.content shouldBe "id_change_unsupported"
            a.errorJson().getValue("field").jsonPrimitive.content shouldBe "id"
            a.errorJson().containsKey("reason") shouldBe false

            // (b) the id line removed.
            val removed = "---\ntitle: Stable\n---\n\n# Stable\n\nbody.\n".toByteArray()
            val b = client.put("/api/v1/pages/$pageId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(removed)
            }
            b.status shouldBe HttpStatusCode.UnprocessableEntity

            // (c) the lenient-decode trap: a buffer with a clean id line but an invalid-UTF-8 byte
            // elsewhere in the block must NOT spuriously 400/422 from strict decoding — it round-trips.
            val withBadByte = "---\nid: $pageId\ndesc: ".toByteArray() + byteArrayOf(0xFF.toByte()) +
                "\ntitle: Stable\n---\n\n# Stable\n\nbody.\n".toByteArray()
            val cHash = citations.contentHash(withBadByte)
            val c = client.put("/api/v1/pages/$pageId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(withBadByte)
            }
            c.status shouldBe HttpStatusCode.OK
            c.json().getValue("content_hash").jsonPrimitive.content shouldBe cHash
        }
    }

    // 6b. Quoted-id round-trip (Codex P2a): a page whose on-disk frontmatter has a QUOTED id
    // (`id: "<uuid>"`) — a byte-identical PUT must be 200, not a false 422. The route id-check runs
    // through the identity-ASSIGNMENT grammar (`PageId.of(readIdValue(...))`), so a quoted vs
    // unquoted id denoting the same UUID compare equal; the old raw-string-vs-parsed-scalar compare
    // wrongly rejected this. A genuinely-different id stays 422.
    test("a byte-identical PUT of a page whose on-disk id is QUOTED is 200, not a false 422") {
        val pageId = "0190aaaa-bbbb-7ccc-8ddd-000000000030"
        val original = "---\nid: \"$pageId\"\ntitle: Quoted\n---\n\n# Quoted\n\nbody.\n".toByteArray()
        val seedQuoted: (IdMapRepository) -> Unit = { it.bind(TreePath.require("quoted.md"), PageId.require(pageId), materialized = false) }
        writeWithFiles(seedQuoted, "quoted.md" to original) { harness ->
            val hBase = citations.contentHash(original)

            // Byte-identical PUT — nothing changes — must succeed (200), proving the quoted id is not
            // a false identity tamper.
            val same = client.put("/api/v1/pages/$pageId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(original)
            }
            same.status shouldBe HttpStatusCode.OK
            same.json().getValue("content_hash").jsonPrimitive.content shouldBe hBase
            harness.diskBytes("quoted.md") shouldBe original

            // A genuinely different id (now an honored, unquoted UUID where there was none) is a real
            // identity change → 422 at the route layer (a non-null assigned id vs the page's null
            // honored id).
            val tampered = "---\nid: 0190ffff-bbbb-7ccc-8ddd-000000000030\ntitle: Quoted\n---\n\n# Quoted\n\nbody.\n".toByteArray()
            val bad = client.put("/api/v1/pages/$pageId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(tampered)
            }
            bad.status shouldBe HttpStatusCode.UnprocessableEntity
            bad.errorJson().getValue("code").jsonPrimitive.content shouldBe "id_change_unsupported"
        }
    }

    // 6c. SW-4: a DUPLICATE/adopted page that resolves NON-materialized (its in-file `id:` claim was
    // rejected, the page minted a fresh id) still carries an on-disk `id:` line. A pure-body edit that
    // PRESERVES that line must be 200, not a false 422 — the route id-check compares the file's CURRENT
    // frontmatter id (`current.frontmatter.scalar("id")`), never the assigned pageId, matching
    // WritePipeline.classifyEdit. Changing that on-disk id line is still a real tamper → 422.
    test("a pure-body edit to a non-materialized duplicate page (on-disk id preserved) is 200; changing it is 422") {
        val claimedId = "0190aaaa-bbbb-7ccc-8ddd-000000000040"
        val owner = "---\nid: $claimedId\ntitle: Owner\n---\n\n# Owner\n\nbody.\n".toByteArray()
        // `z-dup.md` sorts AFTER `a-owner.md`, so the owner wins the id and the dup is minted a fresh one.
        val dup = "---\nid: $claimedId\ntitle: Dup\n---\n\n# Dup\n\nbody.\n".toByteArray()
        writeWithFiles({}, "a-owner.md" to owner, "z-dup.md" to dup) { harness ->
            val dupPage = harness.builder.current.byPath.getValue(TreePath.require("z-dup.md"))
            dupPage.materialized shouldBe false // the rejected claim → non-materialized, fresh id
            val dupId = dupPage.id.value
            val hBase = citations.contentHash(dup)

            // Pure-body edit that KEEPS the on-disk `id:` line verbatim — legitimate, must be 200.
            val edited = "---\nid: $claimedId\ntitle: Dup\n---\n\n# Dup\n\nedited body.\n".toByteArray()
            val ok = client.put("/api/v1/pages/$dupId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(edited)
            }
            ok.status shouldBe HttpStatusCode.OK
            String(harness.diskBytes("z-dup.md")) shouldBe String(edited) // id: line intact, body changed

            // Negative: changing that on-disk id line IS a real identity tamper → 422.
            val hEdited = citations.contentHash(edited)
            val tampered = "---\nid: 0190ffff-bbbb-7ccc-8ddd-000000000040\ntitle: Dup\n---\n\n# Dup\n\nedited body.\n".toByteArray()
            val bad = client.put("/api/v1/pages/$dupId") {
                header(HttpHeaders.IfMatch, etag(hEdited))
                contentType(markdown())
                setBody(tampered)
            }
            bad.status shouldBe HttpStatusCode.UnprocessableEntity
            bad.errorJson().getValue("code").jsonPrimitive.content shouldBe "id_change_unsupported"
        }
    }

    // 7. ID-tamper, pipeline layer — slug + redirect_from changes.
    test("a slug change is 422 slug_change_unsupported; a redirect_from change is 422 redirect_from_change_unsupported") {
        val pageId = "0190aaaa-bbbb-7ccc-8ddd-000000000020"
        val original = "---\nid: $pageId\ntitle: Slugged\nslug: original-slug\nredirect_from:\n  - old/path\n---\n\n# Slugged\n\nbody.\n"
        val bytes = original.toByteArray()
        val seedMat: (IdMapRepository) -> Unit = { it.bind(TreePath.require("slugged.md"), PageId.require(pageId), materialized = true) }
        writeWithFiles(seedMat, "slugged.md" to bytes) { _ ->
            val hBase = citations.contentHash(bytes)
            val reslug = original.replace("slug: original-slug", "slug: new-slug").toByteArray()
            val s = client.put("/api/v1/pages/$pageId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(reslug)
            }
            s.status shouldBe HttpStatusCode.UnprocessableEntity
            s.errorJson().getValue("code").jsonPrimitive.content shouldBe "slug_change_unsupported"
            s.errorJson().getValue("field").jsonPrimitive.content shouldBe "slug"
            s.errorJson().containsKey("reason") shouldBe false

            val rewrite = original.replace("- old/path", "- other/path").toByteArray()
            val r = client.put("/api/v1/pages/$pageId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(rewrite)
            }
            r.status shouldBe HttpStatusCode.UnprocessableEntity
            r.errorJson().getValue("code").jsonPrimitive.content shouldBe "redirect_from_change_unsupported"
            r.errorJson().getValue("field").jsonPrimitive.content shouldBe "redirect_from"
        }
    }

    // 8. Path-invariant — a legal title edit is 200 at the SAME path, no new file appears.
    test("a legal title edit is 200 at the same path; no new file appears") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val edited = String(original, Charsets.UTF_8).replace("title: Deploy Guide", "title: Deploy Guide v2").toByteArray()
            val before = countFiles(harness.root)
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(edited)
            }
            put.status shouldBe HttpStatusCode.OK
            harness.diskBytes("guides/deploy-guide.md") shouldBe edited
            countFiles(harness.root) shouldBe before
        }
    }

    // 9. WrittenButUnindexed (R2) — a throwing post-write hook → 200 + warning, bytes on disk.
    test("a post-write hook failure is 200 with warning reindex_deferred; the bytes are on disk") {
        val throwingHook = WriteHistoryHook { _, _, _, _ -> throw RuntimeException("history boom") }
        writeRestTest(Fixtures.demoDocs, seed, historyHook = throwingHook) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val edited = original + "\ndeferred reindex.\n".toByteArray()
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(edited)
            }
            put.status shouldBe HttpStatusCode.OK
            val body = put.json()
            body.getValue("content_hash").jsonPrimitive.content shouldBe citations.contentHash(edited)
            val warning = body.getValue("warning").jsonObject
            warning.getValue("code").jsonPrimitive.content shouldBe "reindex_deferred"
            harness.diskBytes("guides/deploy-guide.md") shouldBe edited
            harness.dirtyPages.all().isEmpty() shouldBe false
        }
    }

    // 10. Unreadable (R3) — a CAS-failing store → 503, nothing written.
    test("a CAS Unreadable is 503 content_unreadable; nothing written") {
        val failing: (ContentStore) -> ContentStore = { real ->
            object : ContentStore by real {
                override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String) =
                    CasResult.Unreadable("simulated permission denied")
            }
        }
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = failing) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(original + "x".toByteArray())
            }
            put.status shouldBe HttpStatusCode.ServiceUnavailable
            put.errorJson().getValue("code").jsonPrimitive.content shouldBe "content_unreadable"
            harness.diskBytes("guides/deploy-guide.md") shouldBe original
        }
    }

    // 11. 404 / 400-id gate.
    test("an unknown shape-valid id is 404 page_not_found; a shape-invalid id is 400 invalid_page_id") {
        writeRestTest(Fixtures.demoDocs, seed) { _ ->
            val tag = etag(citations.contentHash("x".toByteArray()))
            val unknown = client.put("/api/v1/pages/a3bb189e-8bf9-4888-9912-ace4e6543002") {
                header(HttpHeaders.IfMatch, tag)
                contentType(markdown())
                setBody("x".toByteArray())
            }
            unknown.status shouldBe HttpStatusCode.NotFound
            unknown.errorJson().getValue("code").jsonPrimitive.content shouldBe "page_not_found"

            val invalid = client.put("/api/v1/pages/1-1-1-1-1") {
                header(HttpHeaders.IfMatch, tag)
                contentType(markdown())
                setBody("x".toByteArray())
            }
            invalid.status shouldBe HttpStatusCode.BadRequest
            invalid.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_page_id"
        }
    }

    // 11b. Non-canonical (hex-32, no-hyphen) id gate (Codex P2b): `Uuid.parseOrNull` accepts the
    // hyphenless 32-hex form, but the §A4 HTTP boundary admits ONLY the canonical hyphenated shape.
    // A hex-32 id must be 400 invalid_page_id on PUT — and on GET too (the gate is shared), pinning
    // that the shared gate decides 400-vs-404 by the canonical regex, never JDK leniency.
    test("a hex-32 no-hyphen id is 400 invalid_page_id on PUT and on GET (the shared canonical gate)") {
        writeRestTest(Fixtures.demoDocs, seed) { _ ->
            val hex32 = deployGuideId.replace("-", "") // 0197a3f28c4d7e91b3a24f8e9d1c6b5a — a valid Uuid, NOT canonical
            val put = client.put("/api/v1/pages/$hex32") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash("x".toByteArray())))
                contentType(markdown())
                setBody("x".toByteArray())
            }
            put.status shouldBe HttpStatusCode.BadRequest
            put.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_page_id"

            val get = client.get("/api/v1/pages/$hex32")
            get.status shouldBe HttpStatusCode.BadRequest
            get.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_page_id"
        }
    }

    // 12. Header-shape 400.
    test("a missing, garbage, or short If-Match is 400 invalid_base_hash; a shape-valid non-matching hash is NOT 400") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")

            val missing = client.put("/api/v1/pages/$deployGuideId") {
                contentType(markdown())
                setBody(original)
            }
            missing.status shouldBe HttpStatusCode.BadRequest
            missing.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_base_hash"

            val garbage = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, "garbage")
                contentType(markdown())
                setBody(original)
            }
            garbage.status shouldBe HttpStatusCode.BadRequest

            val short = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, "\"sha256:short\"")
                contentType(markdown())
                setBody(original)
            }
            short.status shouldBe HttpStatusCode.BadRequest

            val unquoted = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, citations.contentHash(original))
                contentType(markdown())
                setBody(original)
            }
            unquoted.status shouldBe HttpStatusCode.BadRequest // bare unquoted value is non-conformant → 400

            // A shape-valid but wrong hash is the drift path (409), NEVER a 400.
            val wrong = "sha256:" + "0".repeat(64)
            val drift = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(wrong))
                contentType(markdown())
                setBody(original + "z".toByteArray())
            }
            drift.status shouldBe HttpStatusCode.Conflict
        }
    }

    // 13. Body cap — an over-cap body (streamed, Content-Length never trusted) → 413 with the
    // configured max_bytes; nothing written.
    test("an over-cap body is 413 body_too_large with the configured max_bytes; nothing written") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val cap = harness.services.maxWriteBodyBytes
            val oversize = ByteArray((cap + 1).toInt()) { 'a'.code.toByte() }
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(oversize)
            }
            put.status shouldBe HttpStatusCode.PayloadTooLarge
            val err = put.errorJson()
            err.getValue("code").jsonPrimitive.content shouldBe "body_too_large"
            err.getValue("max_bytes").jsonPrimitive.content.toLong() shouldBe cap
            harness.diskBytes("guides/deploy-guide.md") shouldBe original
        }
    }

    // 13b. Body cap — NO Content-Length (chunked/streamed) over-cap → 413. Proves the cap is enforced
    // by the streamed read to limit+1, not by an early Content-Length check (there is no CL to check).
    test("an over-cap body with NO Content-Length is 413 body_too_large with the configured max_bytes") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val cap = harness.services.maxWriteBodyBytes
            val oversize = ByteArray((cap + 1).toInt()) { 'a'.code.toByte() }
            // A ByteReadChannel body has no known length, so the client sends chunked transfer encoding
            // (no Content-Length header) — the server can ONLY learn the size by streaming.
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(ByteReadChannel(oversize))
            }
            put.status shouldBe HttpStatusCode.PayloadTooLarge
            val err = put.errorJson()
            err.getValue("code").jsonPrimitive.content shouldBe "body_too_large"
            err.getValue("max_bytes").jsonPrimitive.content.toLong() shouldBe cap
            harness.diskBytes("guides/deploy-guide.md") shouldBe original
        }
    }

    // 13c. Body cap — a LYING Content-Length: 100 but an actual streamed body over the cap → 413.
    // Proves Content-Length is advisory only: the streamed read to limit+1 is the authority, NOT a
    // CL-trusting early-reject. A future refactor to receive<ByteArray>() / a CL-trusting check would
    // pass the honest test above but FAIL here.
    test("a lying Content-Length under the cap with an actual over-cap body is still 413 body_too_large") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val cap = harness.services.maxWriteBodyBytes
            val oversize = ByteArray((cap + 1).toInt()) { 'a'.code.toByte() }
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                // A body that advertises only 100 bytes but actually streams the full over-cap buffer.
                setBody(LyingLengthContent(oversize, advertisedLength = 100L))
            }
            put.status shouldBe HttpStatusCode.PayloadTooLarge
            val err = put.errorJson()
            err.getValue("code").jsonPrimitive.content shouldBe "body_too_large"
            err.getValue("max_bytes").jsonPrimitive.content.toLong() shouldBe cap
            harness.diskBytes("guides/deploy-guide.md") shouldBe original
        }
    }

    // 14. Media type — non-text/markdown → 415.
    test("a PUT without text/markdown is 415 unsupported_media_type") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                contentType(ContentType.Text.Plain)
                setBody(original)
            }
            put.status shouldBe HttpStatusCode.UnsupportedMediaType
            put.errorJson().getValue("code").jsonPrimitive.content shouldBe "unsupported_media_type"
        }
    }
})

/**
 * An outgoing body that ADVERTISES [advertisedLength] (sent as the `Content-Length` header) but
 * actually streams all of [bytes] — a lying Content-Length. The route must enforce the cap by the
 * streamed read, never by trusting this advertised number.
 */
private class LyingLengthContent(
    private val bytes: ByteArray,
    private val advertisedLength: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType get() = ContentType.parse("text/markdown")
    override val contentLength: Long get() = advertisedLength
    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(bytes)
    }
}

/** Counts the regular files under [root] (the path-invariant "no new file" check). */
private fun countFiles(root: java.nio.file.Path): Int =
    java.nio.file.Files.walk(root).use { stream -> stream.filter { java.nio.file.Files.isRegularFile(it) }.count().toInt() }

/**
 * Runs [block] over a [WriteRestHarness] seeded by [seed] whose temp tree contains exactly [files]
 * (relative-path → bytes), written before the first rebuild. For adversarial fixtures (BOM,
 * invalid-UTF-8) the committed demo tree cannot host: a fresh empty tree + just these files.
 */
private fun writeWithFiles(
    seed: (IdMapRepository) -> Unit,
    vararg files: Pair<String, ByteArray>,
    block: suspend io.ktor.server.testing.ApplicationTestBuilder.(WriteRestHarness) -> Unit,
) {
    val tree = java.nio.file.Files.createTempDirectory("plainbase-write-fixture")
    try {
        for ((rel, bytes) in files) {
            val target = tree.resolve(rel)
            java.nio.file.Files.createDirectories(target.parent ?: tree)
            java.nio.file.Files.write(target, bytes)
        }
        writeRestTest(tree, seed, block = block)
    } finally {
        tree.toFile().deleteRecursively()
    }
}

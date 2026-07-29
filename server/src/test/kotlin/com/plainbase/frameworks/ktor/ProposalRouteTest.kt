package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PB-PROPOSE-1 route behavior over the real `GuardedProposalFacade`/`ProposalService`/`IndexProposalBaseReader`
 * stack: the happy propose/list/get/reject round-trip, the FULL F4 invalid-request matrix (all 15 rows), the E2
 * reject wire contract, the C3 path-resolution, the §0.13(i) freeze-proof, and the §WI-9 base_drifted/stale_base
 * seams. Enforced-mode + off-mode authz variants live in [ProposalAuthzRouteTest].
 */
class ProposalRouteTest : FunSpec({

    val json = ContentType.Application.Json
    suspend fun HttpResponse.body(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /**
     * Reads an existing page's id + content_hash via the REST read path (the edit base the agent proposes
     * against). [slug] is root-relative; `by-path` needs the root segment, and these fixtures are single-root.
     */
    suspend fun io.ktor.server.testing.ApplicationTestBuilder.page(slug: String): Pair<String, String> {
        val body = client.get("/api/v1/pages/by-path/docs/$slug").body()
        return body.getValue("id").jsonPrimitive.content to body.getValue("content_hash").jsonPrimitive.content
    }

    fun proposeEdit(id: String, hash: String, content: String, rationale: String = "tighten it") =
        """{"operation":"edit","page_id":"$id","base_hash":"$hash","proposed_content":${json(content)},"rationale":${json(rationale)}}"""

    test("the happy propose(edit)/list/get/reject round-trip") {
        withTempTree(seed = { writePage(it, "guides/deploy.md", "---\ntitle: Deploy\n---\n\n# Deploy\n\nOld.\n") }) { root ->
            restTest(root) {
                val (id, hash) = page("guides/deploy")
                val proposed = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(proposeEdit(id, hash, "---\ntitle: Deploy\n---\n\n# Deploy\n\nNew.\n"))
                }
                proposed.status shouldBe HttpStatusCode.Created
                val proposalId = proposed.body().getValue("id").jsonPrimitive.content
                proposed.body().getValue("status").jsonPrimitive.content shouldBe "PENDING"
                proposed.body().getValue("unified_diff").jsonPrimitive.content shouldContain "+New."

                // list_changes is a WRAPPER object; the summary carries base_drifted + page_id.
                val list = client.get("/api/v1/changes").body()
                val summary = list.getValue("proposals").jsonArray.single().jsonObject
                summary.getValue("id").jsonPrimitive.content shouldBe proposalId
                summary.containsKey("base_drifted").shouldBeTrue()
                summary.getValue("page_id").jsonPrimitive.content shouldBe id

                // get_change carries the stable unified_diff + the live base_drifted.
                val detail = client.get("/api/v1/changes/$proposalId").body()
                detail.getValue("unified_diff").jsonPrimitive.content shouldContain "+New."
                detail.getValue("base_drifted").jsonPrimitive.boolean shouldBe false

                // reject -> 200 ChangeDetail (status REJECTED, decision fields populated).
                val rejected = client.post("/api/v1/changes/$proposalId/reject") {
                    contentType(json)
                    setBody("""{"comment":"not now"}""")
                }
                rejected.status shouldBe HttpStatusCode.OK
                rejected.body().getValue("status").jsonPrimitive.content shouldBe "REJECTED"
                rejected.body().getValue("decision_comment").jsonPrimitive.content shouldBe "not now"

                // a second reject of the now-terminal row -> 409 not_pending.
                val again = client.post("/api/v1/changes/$proposalId/reject") {
                    contentType(json)
                    setBody("{}")
                }
                again.status shouldBe HttpStatusCode.Conflict
                again.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "not_pending"

                // reject an unknown id -> 404 not_found.
                val unknown = client.post("/api/v1/changes/01900000-0000-7000-9000-000000000099/reject") {
                    contentType(json)
                    setBody("{}")
                }
                unknown.status shouldBe HttpStatusCode.NotFound
                unknown.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "not_found"
            }
        }
    }

    test("the happy propose(create) path stores a PENDING create proposal over an empty base") {
        withTempTree(seed = { writePage(it, "guides/deploy.md", "---\ntitle: Deploy\n---\n\n# Deploy\n") }) { root ->
            restTest(root) {
                val created = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(
                        """{"operation":"create","root":"docs","target_path":"guides/new.md","proposed_content":"# New\n\nx\n","rationale":"add a page"}""",
                    )
                }
                created.status shouldBe HttpStatusCode.Created
                created.body().getValue("unified_diff").jsonPrimitive.content shouldContain "+# New"
            }
        }
    }

    test("proposal writes reject the wrong media type and malformed UTF-8 without persisting") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            restTest(root) {
                val wrongType = client.post("/api/v1/changes") {
                    contentType(ContentType.Text.Plain)
                    setBody("{}")
                }
                wrongType.status shouldBe HttpStatusCode.UnsupportedMediaType
                wrongType.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                    "unsupported_media_type"

                val malformedUtf8 = client.post("/api/v1/changes") {
                    header("Content-Type", "application/json")
                    setBody(byteArrayOf(0x7B, 0x22, 0xFF.toByte(), 0x22, 0x7D))
                }
                malformedUtf8.status shouldBe HttpStatusCode.BadRequest
                malformedUtf8.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                    "invalid_propose_request"

                client.get("/api/v1/changes").body().getValue("proposals").jsonArray.size shouldBe 0
            }
        }
    }

    test("decision routes reject non-canonical proposal ids before invoking proposal state transitions") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            restTest(root) {
                suspend fun assertInvalid(response: HttpResponse) {
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                        "invalid_propose_request"
                }

                assertInvalid(client.get("/api/v1/changes/not-a-uuid"))
                assertInvalid(client.post("/api/v1/changes/not-a-uuid/approve"))
                assertInvalid(client.post("/api/v1/changes/not-a-uuid/rebase"))
                assertInvalid(
                    client.post("/api/v1/changes/not-a-uuid/reject") {
                        contentType(json)
                        setBody("{}")
                    },
                )
            }
        }
    }

    test("reject requires JSON and rejects a malformed body") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            restTest(root) {
                val id = "01900000-0000-7000-9000-000000000099"
                val wrongType = client.post("/api/v1/changes/$id/reject") {
                    contentType(ContentType.Text.Plain)
                    setBody("{}")
                }
                wrongType.status shouldBe HttpStatusCode.UnsupportedMediaType

                val malformed = client.post("/api/v1/changes/$id/reject") {
                    contentType(json)
                    setBody("{")
                }
                malformed.status shouldBe HttpStatusCode.BadRequest
                malformed.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                    "invalid_propose_request"
            }
        }
    }

    test("decision routes expose the terminal-state matrix and cap reject bodies") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            restTest(root) { harness ->
                val (pageId, hash) = page("doc")
                val proposalId = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(proposeEdit(pageId, hash, "---\ntitle: Doc\n---\n\n# Changed\n"))
                }.body().getValue("id").jsonPrimitive.content

                val pendingRebase = client.post("/api/v1/changes/$proposalId/rebase")
                pendingRebase.status shouldBe HttpStatusCode.Conflict
                pendingRebase.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                    "not_conflicted"

                client.post("/api/v1/changes/$proposalId/reject") {
                    contentType(json)
                    setBody("{}")
                }.status shouldBe HttpStatusCode.OK
                val terminalApprove = client.post("/api/v1/changes/$proposalId/approve")
                terminalApprove.status shouldBe HttpStatusCode.Conflict
                terminalApprove.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                    "not_pending"

                val unknownId = "01900000-0000-7000-9000-000000000099"
                for (response in listOf(
                    client.get("/api/v1/changes/$unknownId"),
                    client.post("/api/v1/changes/$unknownId/approve"),
                    client.post("/api/v1/changes/$unknownId/rebase"),
                )) {
                    response.status shouldBe HttpStatusCode.NotFound
                    response.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                        "not_found"
                }

                val oversizedReject = client.post("/api/v1/changes/$unknownId/reject") {
                    contentType(json)
                    setBody(ByteArray((harness.services.maxWriteBodyBytes + 1).toInt()) { 'x'.code.toByte() })
                }
                oversizedReject.status shouldBe HttpStatusCode.PayloadTooLarge
                oversizedReject.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe
                    "body_too_large"
            }
        }
    }

    test("§0.13(i) freeze-proof: the stored diff survives the live page converging on the proposed content") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n\nOld.\n") }) { root ->
            restTest(root) { harness ->
                val (id, hash) = page("doc")
                val newBody = "---\ntitle: Doc\n---\n\n# Doc\n\nConverged.\n"
                val proposalId = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(proposeEdit(id, hash, newBody))
                }.body().getValue("id").jsonPrimitive.content

                // Make the live page EQUAL the proposed content, then republish the snapshot.
                java.nio.file.Files.writeString(root.resolve("doc.md"), newBody)
                harness.builder.rebuild()

                // The stored diff is STILL the non-empty base->proposed diff, not recomputed-empty.
                val detail = client.get("/api/v1/changes/$proposalId").body()
                detail.getValue("unified_diff").jsonPrimitive.content shouldContain "+Converged."
            }
        }
    }

    test("base_drifted (create, hole #1): false when the target is free, true once a file occupies it") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            restTest(root) { harness ->
                val proposalId = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(
                        """{"operation":"create","root":"docs","target_path":"new.md","proposed_content":"# New\n","rationale":"add"}""",
                    )
                }.body().getValue("id").jsonPrimitive.content
                client.get("/api/v1/changes/$proposalId").body().getValue("base_drifted").jsonPrimitive.boolean shouldBe false

                // A file now occupies the create target — the live flag flips, WITHOUT the stored status changing.
                java.nio.file.Files.writeString(root.resolve("new.md"), "---\ntitle: N\n---\n\n# N\n")
                harness.builder.rebuild()
                val detail = client.get("/api/v1/changes/$proposalId").body()
                detail.getValue("base_drifted").jsonPrimitive.boolean shouldBe true
                detail.getValue("status").jsonPrimitive.content shouldBe "PENDING"
            }
        }
    }

    // ---- The F4 invalid-request matrix (ALL 15 rows) — each maps to its pinned code; nothing persisted -------------

    test("F4 matrix: every invalid combination maps to its pinned code/status and persists nothing") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n\nBody.\n") }) { root ->
            restTest(root) {
                val (id, hash) = page("doc")
                suspend fun propose(body: String) = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(body)
                }
                suspend fun assertCode(resp: HttpResponse, status: HttpStatusCode, code: String) {
                    resp.status shouldBe status
                    resp.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe code
                }
                val bad = "invalid_propose_request"

                // Row 1: edit with no page_id.
                assertCode(
                    propose("""{"operation":"edit","base_hash":"$hash","proposed_content":"x","rationale":"r"}"""),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 2: create with a page_id.
                assertCode(
                    propose(
                        """{"operation":"create","root":"docs","page_id":"$id","target_path":"a.md","proposed_content":"x","rationale":"r"}""",
                    ),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 3: edit with a base_hash that != the live hash (well-formed but stale) -> stale_base.
                assertCode(propose(proposeEdit(id, "sha256:" + "0".repeat(64), "y")), HttpStatusCode.BadRequest, "stale_base")
                // Row 4: edit with no base_hash.
                assertCode(
                    propose("""{"operation":"edit","page_id":"$id","proposed_content":"x","rationale":"r"}"""),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 5: edit whose client target_path disagrees with pathOf(page_id).
                assertCode(
                    propose(
                        """{"operation":"edit","page_id":"$id","base_hash":"$hash","target_path":"other.md","proposed_content":"x","rationale":"r"}""",
                    ),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 6: edit whose page_id resolves to no published page (a fresh-but-unknown id) -> stale_base.
                assertCode(
                    propose(
                        """{"operation":"edit","page_id":"0190dead-0000-7000-8000-000000000000","base_hash":"$hash","proposed_content":"x","rationale":"r"}""",
                    ),
                    HttpStatusCode.BadRequest,
                    "stale_base",
                )
                // Row 7: create with no target_path.
                assertCode(
                    propose("""{"operation":"create","root":"docs","proposed_content":"x","rationale":"r"}"""),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 8: create with a base_hash.
                assertCode(
                    propose(
                        """{"operation":"create","root":"docs","target_path":"a.md","base_hash":"$hash","proposed_content":"x","rationale":"r"}""",
                    ),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 9: empty/blank proposed_content.
                assertCode(
                    propose("""{"operation":"create","root":"docs","target_path":"a.md","proposed_content":"   ","rationale":"r"}"""),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 10: blank rationale.
                assertCode(
                    propose("""{"operation":"create","root":"docs","target_path":"a.md","proposed_content":"x","rationale":"  "}"""),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 11: unknown operation.
                assertCode(
                    propose("""{"operation":"delete","target_path":"a.md","proposed_content":"x","rationale":"r"}"""),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 12: malformed JSON envelope.
                assertCode(propose("""{"operation":"create","root":"docs","""), HttpStatusCode.BadRequest, bad)
                // Row 13: SECURITY — a traversal target_path is rejected via TreePath.of (no ../ reaches the store).
                assertCode(
                    propose(
                        """{"operation":"create","root":"docs","target_path":"../../etc/passwd","proposed_content":"x","rationale":"r"}""",
                    ),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 14: a non-UUID page_id on an edit.
                assertCode(
                    propose("""{"operation":"edit","page_id":"not-a-uuid","base_hash":"$hash","proposed_content":"x","rationale":"r"}"""),
                    HttpStatusCode.BadRequest,
                    bad,
                )
                // Row 15: a malformed base_hash shape (not sha256:+hex), distinct from row 3's well-formed-but-stale.
                assertCode(
                    propose("""{"operation":"edit","page_id":"$id","base_hash":"deadbeef","proposed_content":"x","rationale":"r"}"""),
                    HttpStatusCode.BadRequest,
                    bad,
                )

                // Nothing persisted across the whole matrix.
                client.get("/api/v1/changes").body().getValue("proposals").jsonArray.size shouldBe 0
            }
        }
    }

    // RE-SPEC'd in C1. The second branch used to assert that a target deleted on disk is `stale_base`, and that is
    // the propose surface's flavor of the 404 lie: "your base moved" is a lie when the truth is "we could not read
    // your base", and it is the kind of lie that makes an agent re-read a base that is not there and re-propose
    // against whatever it finds instead. A page whose binding is still LIVE and whose bytes are missing is in limbo:
    // 503, come back. `stale_base` survives for what it always meant - a base that really is gone (a PROVEN absence,
    // below) or a hash that really did move (the row above and the `base_drifted` rows).
    test("stale_base both branches: a matching base_hash succeeds; an UNPROVEN deletion is 503, a PROVEN one is stale_base") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n\nBody.\n") }) { root ->
            restTest(root) { harness ->
                val (id, hash) = page("doc")
                // A matching base_hash -> 201 with a non-empty diff.
                val ok = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(proposeEdit(id, hash, "---\ntitle: Doc\n---\n\n# Doc\n\nEdited.\n"))
                }
                ok.status shouldBe HttpStatusCode.Created
                ok.body().getValue("unified_diff").jsonPrimitive.content shouldContain "+Edited."

                // Delete the page on disk + republish. The binding is untouched (nothing proved a deletion), so the
                // page is in LIMBO and a propose against it must not be told its base moved.
                java.nio.file.Files.delete(root.resolve("doc.md"))
                harness.builder.rebuild()
                val limbo = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(proposeEdit(id, hash, "x"))
                }
                limbo.status shouldBe HttpStatusCode.ServiceUnavailable
                limbo.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "absence_unverified"

                // Now PROVE the absence (C2's epoch / C4's git history mint these for real; an OPERATOR proof stands
                // in). The binding retires, the index no longer holds the page - and `stale_base` becomes the honest
                // answer it always claimed to be.
                harness.retirements.applyProofs(
                    listOf(
                        AbsenceProof(
                            root = RootName.PRIMARY,
                            source = ProofSource.OPERATOR,
                            observationId = harness.retirements.observation(RootName.PRIMARY),
                            bindingEpoch = harness.retirements.bindingEpoch(RootName.PRIMARY),
                            covers = setOf(BindingRef(TreePath.require("doc.md"), PageId.require(id))),
                        ),
                    ),
                    witnessed = emptySet(), // setup: no scan ran, and OPERATOR is not an inference, so nothing refutes it
                    unavailableNow = { emptySet() },
                )
                val gone = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(proposeEdit(id, hash, "x"))
                }
                gone.status shouldBe HttpStatusCode.BadRequest
                gone.body().getValue("error").jsonObject.getValue("code").jsonPrimitive.content shouldBe "stale_base"
            }
        }
    }

    test("BOM/CRLF in the source survives verbatim through the proposed_content BLOB (diff well-formed over it)") {
        withTempTree(seed = { writePage(it, "doc.md", "---\ntitle: Doc\n---\n\n# Doc\n") }) { root ->
            restTest(root) {
                // The JSON string carries a leading U+FEFF (BOM) + CRLF; the route encodes it to bytes verbatim.
                val withBomCrlf = "\\uFEFF# Title\\r\\nbody\\r\\n"
                val created = client.post("/api/v1/changes") {
                    contentType(json)
                    setBody(
                        """{"operation":"create","root":"docs","target_path":"bom.md","proposed_content":"$withBomCrlf","rationale":"bom"}""",
                    )
                }
                created.status shouldBe HttpStatusCode.Created
                // The diff renders the BOM + CRLF as literal content within a line (a well-formed hunk).
                created.body().getValue("unified_diff").jsonPrimitive.content shouldContain "@@ -0,0"
            }
        }
    }
})

/** JSON-encodes a string value (escapes quotes/newlines) for inlining into a request body literal. */
private fun json(s: String): String = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.unifiedDiff
import com.plainbase.frameworks.ktor.dto.ApplyResultResponse
import com.plainbase.frameworks.ktor.dto.ChangeDetail
import com.plainbase.frameworks.ktor.dto.ChangeSummary
import com.plainbase.frameworks.ktor.dto.ConflictedResponse
import com.plainbase.frameworks.ktor.dto.ErrorBody
import com.plainbase.frameworks.ktor.dto.ErrorEnvelope
import com.plainbase.frameworks.ktor.dto.ListChangesResponse
import com.plainbase.frameworks.ktor.dto.ProposalOperationWire
import com.plainbase.frameworks.ktor.dto.ProposalStatusWire
import com.plainbase.frameworks.ktor.dto.ProposeChangeRequest
import com.plainbase.frameworks.ktor.dto.ProposeChangeResponse
import com.plainbase.frameworks.ktor.dto.RebasedResponse
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.toWire
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * PB-PROPOSE-1 FOREVER golden corpus — the frozen propose/get/list/reject wire shapes + the append-only status
 * vocabulary + the `operation` casing round-trip. The DTOs are constructed with stable seeded values and encoded
 * through the scoped [RestJson]; comparison is parsed-JSON-tree equality (the [com.plainbase.frameworks.ktor.RestGolden]
 * idiom), with `unified_diff`/`base_hash` recomputed from fixture bytes at test time (never committed).
 *
 * NEVER-CHANGE: these shapes + the append-only codes `stale_base`/`invalid_propose_request`/`not_pending` froze
 * when P1a landed. PB-PROPOSE-1 GREW in P1b: the apply shapes (200 [ApplyResultResponse] / 409 [ConflictedResponse]
 * / 409 not_pending / 404 not_found / 422 apply_failed) + the rebase shapes (200 [RebasedResponse] / 409
 * not_conflicted / 422 apply_failed) froze when P1b landed. C1 RETIRED the P1b stop-gap `create_apply_unsupported`
 * (create-apply now lands — a create FAILED reuses 422 apply_failed with a stable create_* status_reason) and ADDED
 * the 400 `invalid_create_content` code. A field is never removed or retyped; the vocabularies only grow. See
 * `ForeverApiGoldenSuite.kt`.
 */
class ProposalGoldenTest : FunSpec({

    val citations = CitationFactory()
    val proposalId1 = "01900000-0000-7000-9000-000000000001"
    val proposalId2 = "01900000-0000-7000-9000-000000000002"
    val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val createdAt = "2023-11-14T22:13:20Z"

    val editBase = "# Deploy Guide\n\nOld body.\n".toByteArray()
    val editProposed = "# Deploy Guide\n\nNew body.\n".toByteArray()
    val createProposed = "# New Page\n\nFresh content.\n".toByteArray()
    val editDiff = unifiedDiff(editBase, editProposed)
    val createDiff = unifiedDiff(ByteArray(0), createProposed)
    val baseHash = citations.contentHash(editBase)

    fun <T> goldenMatches(fixture: String, serializer: KSerializer<T>, value: T, substitutions: Map<String, String>) {
        val encoded = Json.parseToJsonElement(RestJson.encodeToString(serializer, value))
        encoded shouldBe RestGolden.load(fixture, substitutions)
    }

    test("propose-edit-pending.json — a 201 ProposeChangeResponse (edit)") {
        goldenMatches(
            "propose-edit-pending.json",
            ProposeChangeResponse.serializer(),
            ProposeChangeResponse(id = proposalId1, status = "PENDING", unifiedDiff = editDiff),
            mapOf("unified_diff" to editDiff),
        )
    }

    test("propose-create-pending.json — a 201 ProposeChangeResponse (create)") {
        goldenMatches(
            "propose-create-pending.json",
            ProposeChangeResponse.serializer(),
            ProposeChangeResponse(id = proposalId2, status = "PENDING", unifiedDiff = createDiff),
            mapOf("unified_diff" to createDiff),
        )
    }

    test("change-detail.json — a get_change ChangeDetail (all fields, decision fields null while pending)") {
        goldenMatches(
            "change-detail.json",
            ChangeDetail.serializer(),
            ChangeDetail(
                id = proposalId1, operation = "edit", status = "PENDING", targetPath = "guides/deploy-guide.md",
                pageId = pageId, baseHash = baseHash, baseDrifted = false, authorLabel = "ci-bot",
                authorIssuer = "agent", authorExternalId = "pb_token", createdAt = createdAt,
                rationale = "tighten the deploy steps", unifiedDiff = editDiff, approverIssuer = null,
                approverExternalId = null, decisionComment = null, decidedAt = null, appliedCommit = null,
                statusReason = null,
            ),
            mapOf("unified_diff" to editDiff, "base_hash" to baseHash),
        )
    }

    test("list-changes.json — a list_changes wrapper object {proposals:[…]}") {
        goldenMatches(
            "list-changes.json",
            ListChangesResponse.serializer(),
            ListChangesResponse(
                proposals = listOf(
                    ChangeSummary(
                        id = proposalId1, operation = "edit", status = "PENDING", targetPath = "guides/deploy-guide.md",
                        pageId = pageId, baseDrifted = false, authorLabel = "ci-bot", createdAt = createdAt,
                        rationale = "tighten the deploy steps",
                    ),
                ),
            ),
            emptyMap(),
        )
    }

    test("reject-rejected.json — a 200 reject ChangeDetail (status REJECTED, decision fields populated)") {
        goldenMatches(
            "reject-rejected.json",
            ChangeDetail.serializer(),
            ChangeDetail(
                id = proposalId1, operation = "edit", status = "REJECTED", targetPath = "guides/deploy-guide.md",
                pageId = pageId, baseHash = baseHash, baseDrifted = false, authorLabel = "ci-bot",
                authorIssuer = "agent", authorExternalId = "pb_token", createdAt = createdAt,
                rationale = "tighten the deploy steps", unifiedDiff = editDiff, approverIssuer = "builtin",
                approverExternalId = "alice", decisionComment = "not now", decidedAt = createdAt, appliedCommit = null,
            ),
            mapOf("unified_diff" to editDiff, "base_hash" to baseHash),
        )
    }

    test("error-invalid-propose-request.json — the 400 invalid_propose_request envelope") {
        goldenMatches(
            "error-invalid-propose-request.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("invalid_propose_request", "an edit requires page_id")),
            emptyMap(),
        )
    }

    test("error-stale-base.json — the 400 stale_base envelope") {
        goldenMatches(
            "error-stale-base.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("stale_base", "The base you proposed against is no longer current; re-read the page and re-propose.")),
            emptyMap(),
        )
    }

    test("error-not-pending.json — the 409 not_pending envelope") {
        goldenMatches(
            "error-not-pending.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("not_pending", "Change $proposalId1 is no longer pending")),
            emptyMap(),
        )
    }

    test("error-not-found.json — the 404 not_found envelope (reuses ErrorCodes.NOT_FOUND)") {
        goldenMatches(
            "error-not-found.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("not_found", "No change with id 01900000-0000-7000-9000-000000000099")),
            emptyMap(),
        )
    }

    // ---- P1b apply/rebase wire shapes (append-only to PB-PROPOSE-1) -----------------------------------
    val appliedHash = citations.contentHash(editProposed)
    val conflictHash = citations.contentHash(editBase)
    val rebaseDiff = unifiedDiff("# Deploy Guide\n\nIntervening edit.\n".toByteArray(), editProposed)
    val rebaseBaseHash = citations.contentHash("# Deploy Guide\n\nIntervening edit.\n".toByteArray())

    test("apply-applied.json — a 200 ApplyResultResponse (no warnings)") {
        goldenMatches(
            "apply-applied.json",
            ApplyResultResponse.serializer(),
            ApplyResultResponse(newHash = appliedHash, commitSha = "abc1234", appliedAt = createdAt, warnings = null),
            mapOf("new_hash" to appliedHash),
        )
    }

    test("apply-conflicted.json — a 409 ConflictedResponse (code=conflicted + current_hash)") {
        goldenMatches(
            "apply-conflicted.json",
            ConflictedResponse.serializer(),
            ConflictedResponse(currentHash = conflictHash, currentPath = "guides/deploy-guide.md"),
            mapOf("current_hash" to conflictHash),
        )
    }

    test("apply-not-pending.json — the 409 not_pending envelope (the double-approve loser / already-terminal)") {
        goldenMatches(
            "apply-not-pending.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("not_pending", "Change $proposalId1 is no longer pending")),
            emptyMap(),
        )
    }

    test("apply-failed.json — the 422 apply_failed envelope (STABLE 'unreadable' message, no raw cause)") {
        goldenMatches(
            "apply-failed.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("apply_failed", "unreadable")),
            emptyMap(),
        )
    }

    test("apply-not-found.json — the 404 not_found envelope (apply of an unknown id)") {
        goldenMatches(
            "apply-not-found.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("not_found", "No change with id 01900000-0000-7000-9000-000000000099")),
            emptyMap(),
        )
    }

    test("invalid-create-content.json — the 400 invalid_create_content envelope (a create blob the patcher refused)") {
        goldenMatches(
            "invalid-create-content.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(
                ErrorBody("invalid_create_content", "a create proposal must not supply its own frontmatter id; the server mints it"),
            ),
            emptyMap(),
        )
    }

    test("rebase-pending.json — a 200 RebasedResponse (status=PENDING, re-pinned base + recomputed diff)") {
        goldenMatches(
            "rebase-pending.json",
            RebasedResponse.serializer(),
            RebasedResponse(newBaseHash = rebaseBaseHash, unifiedDiff = rebaseDiff),
            mapOf("new_base_hash" to rebaseBaseHash, "unified_diff" to rebaseDiff),
        )
    }

    test("rebase-not-conflicted.json — the 409 not_conflicted envelope") {
        goldenMatches(
            "rebase-not-conflicted.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("not_conflicted", "Change $proposalId1 is not in a conflicted state")),
            emptyMap(),
        )
    }

    test("rebase-gone.json — the 422 apply_failed envelope for a deleted rebase target") {
        goldenMatches(
            "rebase-gone.json",
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody("apply_failed", "target page was deleted; rebase is impossible")),
            emptyMap(),
        )
    }

    test("the status string set is the frozen append-only six") {
        ProposalStatusWire.ALL shouldBe setOf("PENDING", "APPLYING", "APPLIED", "REJECTED", "CONFLICTED", "FAILED")
    }

    test("operation casing round-trips wire <-> domain <-> wire for BOTH edit and create") {
        for (wire in listOf(ProposalOperationWire.EDIT, ProposalOperationWire.CREATE)) {
            // wire -> request DTO -> back: the discriminator stays lowercase on the wire.
            val json = """{"operation":"$wire","proposed_content":"x","rationale":"r","target_path":"a.md"}"""
            val decoded = RestJson.decodeFromString(ProposeChangeRequest.serializer(), json)
            decoded.operation shouldBe wire
            // domain enum maps explicitly back to the lowercase wire form.
            ProposalOperation.valueOf(wire.uppercase()).toWire() shouldBe wire
        }
    }
})

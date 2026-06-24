package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.service.ApplyOutcome
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.RebaseOutcome
import com.plainbase.domain.service.RejectOutcome
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ApplyResultResponse
import com.plainbase.frameworks.ktor.dto.ChangeDetail
import com.plainbase.frameworks.ktor.dto.ConflictedResponse
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.ListChangesResponse
import com.plainbase.frameworks.ktor.dto.ProposalOperationWire
import com.plainbase.frameworks.ktor.dto.ProposeChangeRequest
import com.plainbase.frameworks.ktor.dto.ProposeChangeResponse
import com.plainbase.frameworks.ktor.dto.RebasedResponse
import com.plainbase.frameworks.ktor.dto.RejectChangeRequest
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.toDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException

/**
 * PB-PROPOSE-1 (Phase 5, chunks P1a + P1b): the agent-facing proposal surface under `/api/v1/changes`. P1a is the
 * propose/read/reject surface; P1b adds the EDIT-only apply (`approve`) + `rebase` decisions that DO mutate the
 * content tree. The `HistoryRoutes`/`PageCreateRoutes` idiom: JSON request via the scoped [RestJson] (a
 * malformed/bad-UTF-8 envelope is a 400 `invalid_propose_request`, the `parseCreateRequest` pattern),
 * `call.guarded { }` mapping `AccessDenied` to 401/403, `respondRest`/`respondError`.
 *
 *  - `POST /api/v1/changes` — propose_change. 201 [ProposeChangeResponse]. The full F4 invalid-request matrix is
 *    enforced HERE / in `ProposalService` BEFORE any insert; nothing persisted on rejection.
 *  - `GET /api/v1/changes` — list_changes (checkRead). Returns ALL rows (pagination deferred — the wrapper object
 *    admits an additive `limit`/`cursor` later without a contract break).
 *  - `GET /api/v1/changes/{id}` — get_change (checkRead). 404 on unknown id (existence not leaked — checkRead first).
 *  - `POST /api/v1/changes/{id}/reject` — reject (checkApprove). Rejected->200 ChangeDetail, NotPending->409, NotFound->404.
 *  - `POST /api/v1/changes/{id}/approve` — apply an EDIT (checkApprove). Applied->200 [ApplyResultResponse],
 *    Conflicted->409 [ConflictedResponse], Failed/CreateUnsupported->422, NotPending->409, NotFound->404.
 *  - `POST /api/v1/changes/{id}/rebase` — rebase a CONFLICTED edit (checkApprove). Rebased->200 [RebasedResponse],
 *    NotConflicted->409, Gone->422, NotFound->404.
 */
fun Route.proposalRoutes(ctx: RouteContext) {
    route("/api/v1/changes") {
        post {
            val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
            call.guarded {
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                    return@guarded call.respondError(
                        HttpStatusCode.UnsupportedMediaType,
                        ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                        "POST requires Content-Type: application/json",
                    )
                }
                val rawBody = call.receiveBodyCapped(ctx.maxWriteBodyBytes)
                    ?: return@guarded call.respondBodyTooLarge(ctx.maxWriteBodyBytes)
                val request = parseProposeRequest(rawBody)
                    ?: return@guarded call.invalidProposeRequest(
                        "Request body must be JSON: {operation, page_id?, base_hash?, target_path?, proposed_content, rationale}",
                    )

                val command = call.toCommand(request) ?: return@guarded // the route already answered 400
                when (val outcome = ctx.proposals.propose(principal, command)) {
                    is ProposeOutcome.Created -> call.respondRest(
                        ProposeChangeResponse.serializer(),
                        ProposeChangeResponse(id = outcome.id.value, status = "PENDING", unifiedDiff = outcome.unifiedDiff),
                        HttpStatusCode.Created,
                    )
                    ProposeOutcome.StaleBase -> call.respondError(
                        HttpStatusCode.BadRequest,
                        ErrorCodes.STALE_BASE,
                        "The base you proposed against is no longer current; re-read the page and re-propose.",
                    )
                    ProposeOutcome.InvalidRequest -> call.invalidProposeRequest(
                        "target_path disagrees with the page_id-resolved path; the server resolves the path from page_id.",
                    )
                }
            }
        }

        get {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val proposals = ctx.proposals.list(principal).map { it.toDto() }
                call.respondRest(ListChangesResponse.serializer(), ListChangesResponse(proposals = proposals))
            }
        }

        get("/{id}") {
            val principal = ctx.principalOrRefuse(call) ?: return@get
            call.guarded {
                val id = call.proposalId() ?: return@guarded
                val view = ctx.proposals.get(principal, id)
                    ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No change with id ${id.value}")
                call.respondRest(ChangeDetail.serializer(), view.toDto())
            }
        }

        post("/{id}/reject") {
            val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
            call.guarded {
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                    return@guarded call.respondError(
                        HttpStatusCode.UnsupportedMediaType,
                        ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                        "POST requires Content-Type: application/json",
                    )
                }
                val id = call.proposalId() ?: return@guarded
                val rawBody = call.receiveBodyCapped(ctx.maxWriteBodyBytes)
                    ?: return@guarded call.respondBodyTooLarge(ctx.maxWriteBodyBytes)
                val request = parseRejectRequest(rawBody)
                    ?: return@guarded call.invalidProposeRequest("Request body must be JSON: {comment?}")

                when (val outcome = ctx.proposals.reject(principal, id, request.comment)) {
                    is RejectOutcome.Rejected ->
                        call.respondRest(ChangeDetail.serializer(), outcome.view.toDto())
                    RejectOutcome.NotPending ->
                        call.respondError(HttpStatusCode.Conflict, ErrorCodes.NOT_PENDING, "Change ${id.value} is no longer pending")
                    RejectOutcome.NotFound ->
                        call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No change with id ${id.value}")
                }
            }
        }

        post("/{id}/approve") {
            val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
            call.guarded {
                val id = call.proposalId() ?: return@guarded
                when (val outcome = ctx.proposals.approve(principal, id)) {
                    is ApplyOutcome.Applied -> call.respondRest(
                        ApplyResultResponse.serializer(),
                        ApplyResultResponse(
                            newHash = outcome.newHash,
                            commitSha = outcome.commit,
                            // The Applied terminal ALWAYS stamps decided_at; assert it rather than emit the literal "null".
                            appliedAt = requireNotNull(outcome.view.row.decidedAt) {
                                "Applied proposal ${id.value} has no decided_at"
                            }.toString(),
                            warnings = if (outcome.reindexDeferred) listOf("reindex_deferred") else null,
                        ),
                        HttpStatusCode.OK,
                    )
                    is ApplyOutcome.Conflicted -> call.respondRest(
                        ConflictedResponse.serializer(),
                        ConflictedResponse(currentHash = outcome.currentHash, currentPath = outcome.currentPath?.value),
                        HttpStatusCode.Conflict,
                    )
                    is ApplyOutcome.Failed ->
                        call.respondError(HttpStatusCode.UnprocessableEntity, ErrorCodes.APPLY_FAILED, outcome.reason)
                    ApplyOutcome.CreateUnsupported -> call.respondError(
                        HttpStatusCode.UnprocessableEntity,
                        ErrorCodes.CREATE_APPLY_UNSUPPORTED,
                        "create-apply is not supported in this release (deferred)",
                    )
                    ApplyOutcome.NotPending ->
                        call.respondError(HttpStatusCode.Conflict, ErrorCodes.NOT_PENDING, "Change ${id.value} is no longer pending")
                    ApplyOutcome.NotFound ->
                        call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No change with id ${id.value}")
                }
            }
        }

        post("/{id}/rebase") {
            val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
            call.guarded {
                val id = call.proposalId() ?: return@guarded
                when (val outcome = ctx.proposals.rebase(principal, id)) {
                    is RebaseOutcome.Rebased -> call.respondRest(
                        RebasedResponse.serializer(),
                        // rebaseToPending ALWAYS re-pins a non-null base_hash; assert it rather than emit "".
                        RebasedResponse(
                            newBaseHash = requireNotNull(outcome.view.row.baseHash) { "Rebased proposal ${id.value} has no base_hash" },
                            unifiedDiff = outcome.view.row.diffArtifact,
                        ),
                        HttpStatusCode.OK,
                    )
                    RebaseOutcome.NotConflicted ->
                        call.respondError(
                            HttpStatusCode.Conflict,
                            ErrorCodes.NOT_CONFLICTED,
                            "Change ${id.value} is not in a conflicted state",
                        )
                    RebaseOutcome.Gone -> call.respondError(
                        HttpStatusCode.UnprocessableEntity,
                        ErrorCodes.APPLY_FAILED,
                        "target page was deleted; rebase is impossible",
                    )
                    RebaseOutcome.NotFound ->
                        call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No change with id ${id.value}")
                }
            }
        }
    }
}

/**
 * Validates a parsed [ProposeChangeRequest] into a typed [ProposeCommand], OR responds the matching 400 itself and
 * returns null. The full F4 malformed-shape matrix lives here (every wire value parsed through its typed constructor
 * BEFORE the semantic checks); rows 3/5/6 (well-formed but stale / path-mismatch) are the `ProposalService` outcomes.
 */
private suspend fun ApplicationCall.toCommand(request: ProposeChangeRequest): ProposeCommand? {
    // Shared field validation.
    if (request.proposedContent.isBlank()) {
        invalidProposeRequest("proposed_content must not be empty")
        return null
    }
    if (request.rationale.isBlank()) {
        invalidProposeRequest("rationale must not be blank")
        return null
    }
    return when (request.operation) {
        ProposalOperationWire.EDIT -> toEditCommand(request)
        ProposalOperationWire.CREATE -> toCreateCommand(request)
        else -> {
            invalidProposeRequest("operation must be one of edit, create")
            null
        }
    }
}

private suspend fun ApplicationCall.toEditCommand(request: ProposeChangeRequest): ProposeCommand.Edit? {
    val rawPageId = request.pageId
    if (rawPageId.isNullOrBlank()) {
        invalidProposeRequest("an edit requires page_id")
        return null
    }
    val pageId = PageId.of(rawPageId)
    if (pageId == null) {
        invalidProposeRequest("page_id is not a valid UUID")
        return null
    }
    val baseHash = request.baseHash
    if (baseHash.isNullOrBlank()) {
        invalidProposeRequest("an edit requires base_hash")
        return null
    }
    if (!isContentHash(baseHash)) {
        invalidProposeRequest("base_hash must be the sha256:<64-hex> form")
        return null
    }
    // The optional client target_path is non-authoritative; if present it MUST be a valid TreePath (a traversal is a 400).
    val clientTargetPath = request.targetPath?.let { raw ->
        TreePath.of(raw) ?: run {
            invalidProposeRequest("target_path is not a valid content-relative path: '$raw'")
            return null
        }
    }
    return ProposeCommand.Edit(
        pageId = pageId,
        baseHash = baseHash,
        clientTargetPath = clientTargetPath,
        proposedContent = request.proposedContent.encodeToByteArray(),
        rationale = request.rationale,
    )
}

private suspend fun ApplicationCall.toCreateCommand(request: ProposeChangeRequest): ProposeCommand.Create? {
    if (request.pageId != null) {
        invalidProposeRequest("a create has no existing page; page_id is contradictory")
        return null
    }
    if (request.baseHash != null) {
        invalidProposeRequest("a new page has no base; base_hash is contradictory")
        return null
    }
    val rawTargetPath = request.targetPath
    if (rawTargetPath.isNullOrBlank()) {
        invalidProposeRequest("a create requires target_path")
        return null
    }
    // SECURITY: the wire target_path goes through TreePath.of — a `..`/absolute/empty/NUL is structurally
    // unrepresentable, so a traversal is a deterministic 400, never a 500 or a raw-string store.
    val targetPath = TreePath.of(rawTargetPath)
    if (targetPath == null) {
        invalidProposeRequest("target_path is not a valid content-relative path: '$rawTargetPath'")
        return null
    }
    return ProposeCommand.Create(
        targetPath = targetPath,
        proposedContent = request.proposedContent.encodeToByteArray(),
        rationale = request.rationale,
    )
}

/** The `sha256:` + 64-lowercase-hex content-hash shape (the CitationFactory form) — a malformed base_hash is a 400. */
private val CONTENT_HASH = Regex("sha256:[0-9a-f]{64}")

private fun isContentHash(value: String): Boolean = CONTENT_HASH.matches(value)

/** Parses the `{id}` path param via the canonical UUID shape, or responds 400 / returns null. */
private suspend fun ApplicationCall.proposalId(): ProposalId? {
    val raw = parameters["id"].orEmpty()
    val id = raw.takeIf(CANONICAL_PROPOSAL_ID::matches)?.let(ProposalId::of)
    if (id == null) invalidProposeRequest("Not a canonical-shape UUID: '$raw'")
    return id
}

private val CANONICAL_PROPOSAL_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private suspend fun ApplicationCall.invalidProposeRequest(message: String) =
    respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PROPOSE_REQUEST, message)

/** Strict-decode the JSON envelope (the `parseCreateRequest` idiom): null on bad UTF-8 OR malformed JSON for the DTO. */
private fun parseProposeRequest(body: ByteArray): ProposeChangeRequest? {
    val text = strictUtf8(body) ?: return null
    return try {
        RestJson.decodeFromString(ProposeChangeRequest.serializer(), text)
    } catch (_: SerializationException) {
        null
    }
}

private fun parseRejectRequest(body: ByteArray): RejectChangeRequest? {
    val text = strictUtf8(body) ?: return null
    return try {
        RestJson.decodeFromString(RejectChangeRequest.serializer(), text)
    } catch (_: SerializationException) {
        null
    }
}

private fun strictUtf8(bytes: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}

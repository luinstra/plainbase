package com.plainbase.frameworks.ktor.dto

import com.plainbase.domain.model.WriteOutcome
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * PB-WRITE-1 (chunk W3a) — the FROZEN wire shapes for `PUT /api/v1/pages/{id}`, kept beside (not
 * inside) the frozen PB-REST-1 RestDtos so the day-one read shapes never grow a field. The whole
 * point of this file is byte-precision on the wire: the variant 200 DTOs (WrittenResponse vs
 * WrittenButUnindexedResponse) mean a "doesn't-apply" field is ABSENT from the DTO (never
 * `"x": null`); only a genuinely-pending nullable (`commit`) is present-`null` on the DTO it
 * belongs to (encoded through the scoped RestJson, `explicitNulls = true`).
 *
 * ============================== NEVER-CHANGE POLICY ==============================
 * These shapes froze when W3a landed. They are append-only: a field is never removed or retyped,
 * the `reason` set (WriteConflictReason) only grows, and `commit`/`warning`/`current_*`/`max_bytes`
 * are forever. See `ForeverApiGoldenSuite.kt` for the freeze ledger.
 * =================================================================================
 */

/**
 * 200 (`WriteOutcome.Written`): exactly two keys; `commit` is populated with the save's commit SHA in
 * Git mode (W5) and null off Git / no history. NO `warning` key.
 */
@Serializable
data class WrittenResponse(
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
)

/**
 * 200 (`WriteOutcome.WrittenButUnindexed`, R2): a DISTINCT type carrying a non-null [warning]. The
 * bytes are durably on disk; `content_hash` is the next CAS token, so a warning-blind client stays
 * safe. "200 means *saved*, not *saved-and-indexed*."
 */
@Serializable
data class WrittenButUnindexedResponse(
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
    val warning: WriteWarning,
)

@Serializable
data class WriteWarning(val code: String, val message: String)

/**
 * 202 Accepted (P5): an agent COMMIT write fell OUTSIDE `agentDirectCommit.globs` and was degraded to a proposal. A
 * NEW shape — NEVER a field on the frozen two-key [WrittenResponse] (`encodeDefaults=true` would emit any added field
 * on EVERY PUT-200, breaking the golden corpus + the frontend type). [degraded] is always true — the discriminator a
 * client checks before treating a PUT response as an applied write. [status] is always [ProposalStatusWire.PENDING];
 * [unifiedDiff] is NON-NULL (`ProposeOutcome.Created.unifiedDiff` is always populated).
 */
@Serializable
data class DegradedToProposalResponse(
    val degraded: Boolean = true,
    @SerialName("proposal_id") val proposalId: String,
    val status: String,
    @SerialName("unified_diff") val unifiedDiff: String,
)

/**
 * 201 (`POST /api/v1/pages`, `WriteOutcome.Written`): the clean-create response. It conforms to
 * PB-WRITE-1's `WrittenResponse` shape (`content_hash` + present-`null` `commit`) and ADDS the two
 * fields a create owes the client (W6, owner+debate-approved additive revision): the minted [id] and
 * the SERVER-AUTHORITATIVE canonical [url]. `url` is the published `IndexedPage.url` (the slugified,
 * collision-de-duped canonical path), NEVER re-composed from the on-disk file path. Distinct from
 * the PUT-shared [WrittenResponse] so a save never grows an `id`/`url` key.
 */
@Serializable
data class CreatedResponse(
    val id: String,
    val url: String,
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
)

/**
 * 201 (`POST /api/v1/pages`, `WriteOutcome.WrittenButUnindexed`, R2): the create twin of
 * [WrittenButUnindexedResponse] — the bytes are durably on disk, the search/history sync deferred —
 * carrying the same [id] a clean create returns plus the non-null [warning]. Unlike the clean
 * [CreatedResponse], [url] is **present-`null`**: the page is unpublished, so there is no reliable
 * canonical url until reconciliation. Fabricating one from the raw on-disk path would diverge from the
 * post-reconciliation canonical (a `_folder.yaml` slug override, unicode, or collision-de-dup all shift
 * the url segment), so the honest answer is `null` — the client does NOT navigate on this branch (it
 * shows the warning) so no url is needed.
 */
@Serializable
data class CreatedButUnindexedResponse(
    val id: String,
    val url: String?,
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
    val warning: WriteWarning,
)

/** 409 drift envelope (kept distinct from the frozen [ErrorEnvelope] — it grows `reason` + `current_*`). */
@Serializable
data class WriteConflictEnvelope(val error: WriteConflictBody)

@Serializable
data class WriteConflictBody(
    val code: String,
    val reason: String,
    val message: String,
    @SerialName("current_content") val currentContent: String?,
    @SerialName("current_hash") val currentHash: String?,
    @SerialName("current_path") val currentPath: String?,
)

/** 422 unsupported-edit envelope (a rename, not a drift): `code` + `field`, NO `reason`, NO `current_*`. */
@Serializable
data class UnsupportedEditEnvelope(val error: UnsupportedEditBody)

@Serializable
data class UnsupportedEditBody(val code: String, val field: String, val message: String)

/** 413 body: the plain `{code, message}` envelope PLUS the authoritative `max_bytes` (never a mutation of [ErrorBody]). */
@Serializable
data class BodyTooLargeEnvelope(val error: BodyTooLargeBody)

@Serializable
data class BodyTooLargeBody(val code: String, val message: String, @SerialName("max_bytes") val maxBytes: Long)

/**
 * W2 request for `POST /api/v1/pages` (JSON, NOT raw — this is metadata, not a document): the server
 * mints the id, derives the on-disk path + slug, and composes the frontmatter+body bytes. `folder` is
 * the content-relative parent (`""`/omitted = root); `title` is required non-blank; `slug` is the
 * optional author slug intent; `body` is the optional Markdown body (the server adds the frontmatter).
 */
@Serializable
data class CreatePageRequest(
    val folder: String = "",
    val title: String,
    val slug: String? = null,
    val body: String? = null,
)

/**
 * W2 409 envelope (kept distinct from the frozen [ErrorEnvelope] — it carries an extra `path`, exactly
 * as [BodyTooLargeBody] carries `max_bytes` without mutating [ErrorBody]). `path` is the REAL attempted
 * on-disk path relayed end-to-end (createExclusive → AlreadyExists → here), the actionable datum.
 */
@Serializable
data class PageExistsEnvelope(val error: PageExistsBody)

@Serializable
data class PageExistsBody(val code: String, val message: String, val path: String)

/**
 * The frozen drift-only `reason` enum (PB-WRITE-1): the set is `{content_changed, page_moved,
 * page_deleted}` and only ever grows (additive). `page_moved` is PRODUCER-RESERVED — no §H mover
 * emits it yet, but the value is pinned so a future producer adds no new vocabulary. **`id_changed`
 * is deliberately NOT a member** (the debate's sharpest fix): id/slug/redirect_from rejections are
 * 422 + code + field, never a drift discriminator.
 */
object WriteConflictReason {
    const val CONTENT_CHANGED: String = "content_changed"
    const val PAGE_MOVED: String = "page_moved"
    const val PAGE_DELETED: String = "page_deleted"

    /** The frozen reason set (additive-only). Pinned by the golden suite's reason-enum assertion. */
    val ALL: Set<String> = setOf(CONTENT_CHANGED, PAGE_MOVED, PAGE_DELETED)
}

/**
 * The frozen PB-WRITE-1 warning vocabulary — distinct from [ErrorCodes] (a warning rides a 200, not
 * an error status). Append-only, never removed/retyped.
 */
object WriteWarningCode {
    /** R2: the bytes saved to disk but the search/history sync deferred to the next startup reconciliation. */
    const val REINDEX_DEFERRED: String = "reindex_deferred"
}

/** The W3a default warning message for a deferred reindex (R2). */
private const val REINDEX_DEFERRED_MESSAGE =
    "Saved to disk; search/history update deferred to next startup reconciliation."

/**
 * The single point where a [WriteOutcome] meets the PB-WRITE-1 wire: a [status] plus a self-rendering
 * payload. The route renders it through the scoped [RestJson]. Pre-pipeline request rejections
 * (415/413/404/400-id/400-base_hash/422-route-layer-id) are the route's own [respondError]-style
 * answers, never produced here.
 */
sealed interface WriteWire {
    val status: HttpStatusCode

    /** Encodes the body through [RestJson] (the present-null guarantee). */
    fun encode(): String

    private class Of<T>(
        override val status: HttpStatusCode,
        private val serializer: KSerializer<T>,
        private val value: T,
    ) : WriteWire {
        override fun encode(): String = RestJson.encodeToString(serializer, value)
    }

    companion object {
        internal fun <T> of(status: HttpStatusCode, serializer: KSerializer<T>, value: T): WriteWire =
            Of(status, serializer, value)
    }
}

/**
 * Maps a [WriteOutcome] to the frozen PB-WRITE-1 wire, applying the retry-idempotency shim
 * (Resolution / debate "Other frozen items"): a stale `base_hash` whose on-disk bytes ALREADY equal
 * the submitted bytes is a network-retry of a write that landed — a 200 no-op, not a false 409. It is
 * a pure wire-layer shim (no W1 signature change): a `content_changed` [WriteOutcome.Conflict] whose
 * `currentHash` equals `hash(submitted)` becomes a [WrittenResponse] with that hash.
 *
 * [submittedHash] is `CitationFactory.contentHash` over the exact submitted body bytes (the same
 * frozen hash W1 keyed the CAS on — no second hash definition).
 */
fun WriteOutcome.toWire(submittedHash: String): WriteWire = when (this) {
    is WriteOutcome.Written ->
        WriteWire.of(HttpStatusCode.OK, WrittenResponse.serializer(), WrittenResponse(contentHash = newHash, commit = commit))

    is WriteOutcome.WrittenButUnindexed ->
        WriteWire.of(
            HttpStatusCode.OK,
            WrittenButUnindexedResponse.serializer(),
            WrittenButUnindexedResponse(
                contentHash = newHash,
                commit = null,
                warning = WriteWarning(code = WriteWarningCode.REINDEX_DEFERRED, message = REINDEX_DEFERRED_MESSAGE),
            ),
        )

    is WriteOutcome.Conflict -> {
        // Retry-idempotency shim: stale base_hash but the on-disk bytes ARE the submitted bytes → 200 no-op.
        if (reason == WriteConflictReason.CONTENT_CHANGED && currentHash == submittedHash) {
            WriteWire.of(HttpStatusCode.OK, WrittenResponse.serializer(), WrittenResponse(contentHash = submittedHash, commit = null))
        } else {
            // FROZEN page_deleted shape: ALL current_* are null — the file is gone, so the path it WAS at
            // is not surfaced (W1 still carries the stale snapshot path on `currentPath`; the wire nulls it
            // to keep `page_deleted` a clean "nothing to rebase against" signal, per the PB-WRITE-1 freeze).
            val deleted = reason == WriteConflictReason.PAGE_DELETED
            WriteWire.of(
                HttpStatusCode.Conflict,
                WriteConflictEnvelope.serializer(),
                WriteConflictEnvelope(
                    WriteConflictBody(
                        code = ErrorCodes.CONFLICT,
                        reason = reason,
                        message = conflictMessage(reason),
                        currentContent = currentContent,
                        currentHash = currentHash,
                        currentPath = if (deleted) null else currentPath?.value,
                    ),
                ),
            )
        }
    }

    is WriteOutcome.UnsupportedEdit ->
        WriteWire.of(
            HttpStatusCode.UnprocessableEntity,
            UnsupportedEditEnvelope.serializer(),
            UnsupportedEditEnvelope(
                UnsupportedEditBody(code = unsupportedEditCode(field), field = field, message = unsupportedEditMessage(field)),
            ),
        )

    is WriteOutcome.Unreadable ->
        WriteWire.of(
            HttpStatusCode.ServiceUnavailable,
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody(ErrorCodes.CONTENT_UNREADABLE, "The page could not be read on disk; nothing was written. Retry.")),
        )

    // W2: AlreadyExists / InvalidLocation / SlugConflict are produced ONLY by WritePipeline.create; the
    // create route owns their status mapping (409 page_exists / 400 invalid_create_request / 409
    // slug_conflict). The frozen PUT toWire never produces them — these branches only keep the sealed
    // `when` exhaustive, adding NO new envelope/serializer dependency to the freeze.
    is WriteOutcome.AlreadyExists ->
        error("AlreadyExists is a create-only outcome; PUT (toWire) never produces it")

    is WriteOutcome.InvalidLocation ->
        error("InvalidLocation is a create-only outcome; PUT (toWire) never produces it")

    is WriteOutcome.SlugConflict ->
        error("SlugConflict is a create-only outcome; PUT (toWire) never produces it")
}

private fun conflictMessage(reason: String): String = when (reason) {
    WriteConflictReason.CONTENT_CHANGED -> "The page changed on disk since you loaded it."
    WriteConflictReason.PAGE_DELETED -> "The page no longer exists on disk."
    WriteConflictReason.PAGE_MOVED -> "The page moved on disk since you loaded it."
    else -> "The page changed on disk since you loaded it."
}

/** The 422 `<field>_change_unsupported` code for a rename-class rejected edit. */
fun unsupportedEditCode(field: String): String = when (field) {
    "id" -> ErrorCodes.ID_CHANGE_UNSUPPORTED
    "slug" -> ErrorCodes.SLUG_CHANGE_UNSUPPORTED
    "redirect_from" -> ErrorCodes.REDIRECT_FROM_CHANGE_UNSUPPORTED
    else -> error("unsupported-edit field is not an immutable identity field: '$field'")
}

private fun unsupportedEditMessage(field: String): String = when (field) {
    "id" -> "Changing id is not allowed — page identity is immutable."
    "slug" -> "Changing slug is a move, not a save (deferred)."
    "redirect_from" -> "Changing redirect_from is a move, not a save (deferred)."
    else -> "Changing $field is not allowed."
}

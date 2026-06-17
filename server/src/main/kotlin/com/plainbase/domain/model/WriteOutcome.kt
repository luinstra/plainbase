package com.plainbase.domain.model

import com.plainbase.domain.content.TreePath

/**
 * The outcome of a `WritePipeline.write` (PB-WRITE-1). Pure domain: the wire mapping — HTTP status,
 * JSON shape, structured `reason`/error codes — is W2/W3's job, never decided here. W1 produces the
 * five discriminated cases below; W3 freezes the full wire enum and maps each `cause`/`field` to a
 * structured code, surfacing diagnostics ONLY as a mapped code, never raw.
 *
 * The case set is the debate-hardened five: `Written` plus four non-success cases. `NotFound` stays
 * folded into [Conflict] (`reason="page_deleted"`); [UnsupportedEdit] / [Unreadable] are the debate's
 * MUST-FIX 1 / MEDIUM 6 additions.
 */
sealed interface WriteOutcome {

    /**
     * Disk write + every post-write step (reindex, and in W4 the commit) succeeded. [commit] is the
     * recorded Git commit — always null in W1 (no history layer; the [com.plainbase.domain.service]
     * WriteHistoryHook seam defaults to a no-op).
     */
    data class Written(val newHash: String, val commit: String?) : WriteOutcome

    /**
     * The atomic CAS found the on-disk file no longer matched the client's `base_hash` — NOTHING was
     * written. [reason] is `content_changed` (the bytes on disk drifted since the client read them) or
     * `page_deleted` (the indexed file is gone). [currentContent]/[currentHash] are null for
     * `page_deleted`; [currentPath] is the id's path if it is still indexed.
     */
    data class Conflict(
        val reason: String,
        val currentContent: String?,
        val currentHash: String?,
        val currentPath: TreePath?,
    ) : WriteOutcome

    /**
     * MUST-FIX 1: the submitted buffer changed an immutable URL/identity field ([field] ∈
     * `id`|`slug`|`redirect_from`) — that is a RENAME (the deferred §H move/rename workflow), not an
     * edit, so NOTHING is written and the pipeline never falls back to a full rebuild. This is what
     * makes the targeted reindex's skip-the-checkpoint sound by construction. W3 maps it to a
     * structured 409/422 reason (e.g. `slug_change_unsupported`).
     */
    data class UnsupportedEdit(val field: String) : WriteOutcome

    /**
     * MEDIUM 6: the on-disk file could not be read (permission/locked/partial/transient FS) — NOTHING
     * was written. W3 maps it to a retryable code; [cause] is diagnostic, mapped to a structured code,
     * never surfaced raw.
     */
    data class Unreadable(val cause: String) : WriteOutcome

    /**
     * Fix H: the bytes ARE durably on disk, but a post-write step (reindex, or in W4 the commit)
     * failed. The page is marked dirty write-ahead (app DB) and reconciled at the next startup — a
     * REAL surfaced outcome, never a 500 and never a fake success. [cause] is mapped to a structured
     * code at the W3 route, never surfaced raw.
     */
    data class WrittenButUnindexed(val newHash: String, val cause: String) : WriteOutcome

    /**
     * W2 (create): an exclusive create found a file ALREADY at [path] on disk — NOTHING written.
     * Distinct from [Conflict] (a drift on an EDIT): a create has no base_hash and no prior identity,
     * so this is its own case. Produced ONLY by `WritePipeline.create`; the W2 create route maps it to
     * 409 `page_exists`. The PUT route never produces it (its `toWire` branch is an `error(...)`).
     */
    data class AlreadyExists(val path: TreePath) : WriteOutcome

    /**
     * W2 (create, P1 containment): the requested location can never name content — an ignored/excluded
     * segment, or a symlinked / outside-root existing ancestor (links-are-not-content). NOTHING
     * written. Produced ONLY by `WritePipeline.create`; the W2 create route maps it to 400
     * `invalid_create_request`. The PUT route never produces it (its `toWire` branch is an `error(...)`).
     */
    data class InvalidLocation(val reason: String) : WriteOutcome

    /**
     * W2 (create, P1/P2 canonical-URL collision): the prospective canonical URL ([urlPath], a
     * `/`-joined slug path) is already owned by a DIFFERENT published page, folder, or live alias —
     * publishing would leave the newcomer URL-less or silently DISPLACE the existing entry's routing.
     * NOTHING written. Evaluated UNDER the create monitor against the fresh published snapshot (so a
     * second concurrent URL-colliding create, serialized after the first's rebuild, sees the claim).
     * Produced ONLY by `WritePipeline.create`; the W2 create route maps it to 409 `slug_conflict`. The
     * PUT route never produces it (its `toWire` branch is an `error(...)`).
     */
    data class SlugConflict(val urlPath: String) : WriteOutcome
}

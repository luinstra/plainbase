package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome

/**
 * The terminal disposition of an apply (Phase 5, chunk P1b), derived PURELY from the pipeline [WriteOutcome] +
 * the apply's proposed-content hash — no I/O, no clock. Keeping it a pure domain function makes the
 * `WriteOutcome`->status table exhaustively testable per-variant and keeps the apply coordinator thin (the FCIS
 * "pure decision core").
 */
sealed interface ApplyDisposition {
    data class Applied(val newHash: String, val commit: String?, val reindexDeferred: Boolean) : ApplyDisposition

    data class Conflicted(val reason: String, val currentHash: String?, val currentPath: TreePath?) : ApplyDisposition

    data class Failed(val reason: String) : ApplyDisposition
}

/**
 * The FROZEN `WriteOutcome` -> disposition table (one test per variant; PB-PROPOSE-1 P1b/C1). [proposedHash] is the
 * apply's content hash. An EDIT apply hits `Written`/`WrittenButUnindexed`/`Conflict`/`Unreadable`/`UnsupportedEdit`;
 * a CREATE apply (C1) hits `Written`/`WrittenButUnindexed`/`AlreadyExists`/`SlugConflict`/`InvalidLocation`/
 * `Unreadable` and NEVER `Conflict` (no base_hash) — so the three create-only variants are now REACHABLE, mapped to a
 * terminal `Failed` with a STABLE, no-interpolation `status_reason` (deterministic golden + no FS leak). `Conflict`/
 * `UnsupportedEdit` stay edit-only but the `when` stays exhaustive (a future ninth variant is a deliberate compile
 * signal, the house pattern).
 */
fun dispositionOf(outcome: WriteOutcome, proposedHash: String): ApplyDisposition = when (outcome) {
    is WriteOutcome.Written -> ApplyDisposition.Applied(outcome.newHash, outcome.commit, reindexDeferred = false)
    is WriteOutcome.WrittenButUnindexed -> ApplyDisposition.Applied(outcome.newHash, commit = null, reindexDeferred = true)
    is WriteOutcome.Conflict ->
        // Idempotent-replay: the disk ALREADY equals the proposed bytes (interrupted replay / concurrent identical
        // apply) -> APPLIED, not CONFLICTED. Mirrors the W1 idempotent re-commit philosophy.
        if (outcome.currentHash == proposedHash) {
            ApplyDisposition.Applied(proposedHash, commit = null, reindexDeferred = false)
        } else {
            ApplyDisposition.Conflicted(outcome.reason, outcome.currentHash, outcome.currentPath)
        }
    // STABLE reason "unreadable" — the raw outcome.cause is diagnostic and MUST NOT leak to the wire/status_reason
    // (WriteOutcome.kt "never surfaced raw"). The coordinator logs the raw cause server-side; dispositionOf emits
    // only the fixed string so the wire + goldens stay deterministic.
    is WriteOutcome.Unreadable -> ApplyDisposition.Failed("unreadable")
    is WriteOutcome.UnsupportedEdit -> ApplyDisposition.Failed("unsupported_edit: ${outcome.field}")
    // create-only (C1, REACHABLE) — STABLE no-interpolation reasons: the raw path/url/reason is logged server-side
    // (ProposalService.apply), never woven into the wire status_reason, so the golden stays deterministic + leak-free.
    is WriteOutcome.AlreadyExists -> ApplyDisposition.Failed("create_path_taken")
    is WriteOutcome.SlugConflict -> ApplyDisposition.Failed("create_slug_conflict")
    is WriteOutcome.InvalidLocation -> ApplyDisposition.Failed("create_invalid_location")
}

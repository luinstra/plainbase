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
 * The FROZEN `WriteOutcome` -> disposition table (one test per variant; PB-PROPOSE-1 P1b). [proposedHash] is the
 * apply's content hash. On the P1b edit-apply path only `Written`/`WrittenButUnindexed`/`Conflict`/`Unreadable`/
 * `UnsupportedEdit` arise; the three create-only variants (`AlreadyExists`/`SlugConflict`/`InvalidLocation`) are
 * UNREACHABLE in P1b (creates never reach the pipeline) — mapped to `Failed` DEFENSIVELY so the `when` stays
 * exhaustive (a future ninth variant is then a deliberate compile signal, the house pattern).
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
    // create-only, UNREACHABLE on the P1b edit-apply path — defensive terminal mapping for exhaustiveness:
    is WriteOutcome.AlreadyExists -> ApplyDisposition.Failed("already_exists: ${outcome.path.value}")
    is WriteOutcome.SlugConflict -> ApplyDisposition.Failed("slug_conflict: ${outcome.urlPath}")
    is WriteOutcome.InvalidLocation -> ApplyDisposition.Failed("invalid_location: ${outcome.reason}")
}

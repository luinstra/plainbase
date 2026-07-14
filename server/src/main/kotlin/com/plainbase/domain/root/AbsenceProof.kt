package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId

/**
 * The absence-authority machinery: the ONE licence to assert that a binding is gone.
 *
 * > **A scan proves the pages it READ. It does not prove the pages it did not read are DELETED.**
 *
 * An empty mount point, a deliberately emptied root, a partially-restored tree and a decoy tree produce
 * IDENTICAL filesystem observations. A corpus mass-deleted while the server was down is permanently
 * indistinguishable from an outage, by any oracle - that is a theorem, not a bug. So nothing deletes on an
 * INFERENCE from what a scan failed to see; it deletes on a PROOF, and a proof is a value, not a mood.
 *
 * There are exactly three ways a binding may leave `id_map`, and only ONE of them is an absence claim:
 *
 *  - **DISPLACEMENT** - we READ the file at (root, path) and it carries a DIFFERENT id (or none). Positive
 *    evidence: the page is right in front of us. No proof needed; the displaced id is TOMBSTONED
 *    ([RetiredBinding]) in the same transaction as the new bind, so `/p/{oldId}` stays a 410 rather than
 *    becoming a 404.
 *  - **CONTEST** - two roots hold the same id and BOTH pages are witnessed. Registry rank decides
 *    (`RootRegistry.rank`); the loser reassigns to a fresh id and records a `CrossRootDuplicateId`. Nothing is
 *    tombstoned, because nothing DIED: the contested id is still live, at its new home, and the permalink
 *    follows the id.
 *  - **ABSENCE** - we did NOT see it. *This is the only one that needs a proof, and it is the only one that
 *    has ever destroyed a corpus.*
 *
 * **In C0 there are ZERO proof sources.** Nothing in production constructs an [AbsenceProof] - every one of
 * the five [ProofSource]s arrives in a later chunk - so `proofs` is always empty and NOTHING IS EVER REAPED BY
 * INFERENCE. That is not an oversight; it is the safety floor, and it is a property of the TYPE rather than of
 * a policy anyone could forget to apply. The honest cost, stated out loud: a legitimate delete does not
 * converge in C0 - the row sits in limbo ([RootLimbo]) instead of being reaped. C2 (the observation epoch) and
 * C4 (git history) buy that back with evidence rather than with a guess.
 */
enum class ProofSource {
    /** A delete through Plainbase's own API. We did not INFER this absence, we CAUSED it. */
    API_DELETE,

    /** A CONFIRMATION scan under an unbroken observation epoch: it witnessed the page, and now it does not. */
    EPOCH,

    /** A commit range that DELETED the path, on a HEAD that descends from the recorded one. Recorded human intent. */
    GIT,

    /** A complete, generation-bound bucket LIST under a TRUSTED binding. */
    OBJECT_LIST,

    /** An operator accepting an exact observation digest (`plainbase root reconcile --accept`). */
    OPERATOR,
}

/**
 * A per-root freshness token, DURABLE (`root_observation`) because **a restart is itself a revocation** and an
 * in-memory token could not prove that after a crash. Any break, binding change or restart mints a new one,
 * which invalidates every outstanding proof by the freshness rule (see [AbsenceProof.observationId]).
 */
@JvmInline
value class ObservationId(val value: Long) {
    /** The next token. Monotonic per root; minting one IS the revocation of the last. */
    fun next(): ObservationId = ObservationId(value + 1)
}

/**
 * The thing an absence proof is ABOUT: a (path, id) PAIR, never a bare path.
 *
 * A path is not a page. A proof minted for `guides/deploy.md` would otherwise survive a delete-and-recreate at
 * that path and retire the BRAND-NEW page living there. The id is what the proof is actually about.
 */
data class BindingRef(val path: TreePath, val id: PageId)

/**
 * A positive licence to assert that a SPECIFIC BINDING is gone.
 *
 * [root] is load-bearing and is checked at apply time: [BindingRef] carries no root, so without it a proof
 * minted for root A could authorize retiring a binding in root B whose path and id happened to match -
 * cross-root proof replay, in a MULTI-ROOT feature. Authority is per-root, always.
 *
 * [observationId] is re-read from `root_observation` INSIDE the apply transaction and must still match. A
 * revocation that commits before the apply opens therefore serializes AGAINST it and the reap becomes a
 * no-op - there is no window, because the compare and the deletes are ONE transaction.
 */
data class AbsenceProof(
    val root: RootName,
    val source: ProofSource,
    val observationId: ObservationId,
    val covers: Set<BindingRef>,
)

/**
 * What a pass actually SAW at one rooted path: the page was READ, and [observedId] is the id its frontmatter
 * carried (null = it carries none, i.e. an unmaterialized page).
 *
 * **The witness must carry IDENTITY, not just presence.** A bare path-witness cannot decide anything: the
 * supersession gate needs to know that the incumbent NO LONGER CARRIES the id, and reading a *path* does not
 * tell you the *id the file now holds*.
 *
 * Absence from the witness map is NOT a licence. It means "we did not read this", which is exactly as
 * consistent with an unmounted disk as with a delete.
 */
data class Witness(val observedId: PageId?)

/**
 * A tombstoned binding: the id is RESERVED FOREVER, and `/p/{id}` answers **410 Gone** naming [path] rather
 * than the 404 that tells an agent to drop its citation.
 *
 * Tombstones live OUTSIDE the live `(root, path)` key space (their own table, keyed by [id] alone), so
 * ordinary path reuse - delete a page, later create a different page at the same path - cannot collide with
 * one. [path] is the LAST-KNOWN path, and it is deliberately NOT a key: reuse is legal.
 *
 * **Reclaim only at the page's OWN (root, path).** A retired id turning up at a different path is exactly as
 * likely to be a paste as a return, and a page that moved and a file that was COPIED are observationally
 * identical - so we must not guess. Returning to your own path is the one case that carries its own evidence.
 * Anyone else who claims the id mints a fresh one and raises a `CrossRootDuplicateId`.
 */
data class RetiredBinding(
    val id: PageId,
    val path: RootedPath,
    val materialized: Boolean,
    val retiredAt: Long,
)

package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * The LIVE read seam `ProposalService` needs (P1a, B4) — all derivable from the published [com.plainbase.domain
 * .page.PageIndex] snapshot + the per-root `ContentStore`s the read facade already holds. Framework-free
 * (`domain/`); the impl is `com.plainbase.frameworks.ktor.IndexProposalBaseReader`. Reads only — propose-time
 * base-hash validation + drift + the `create`-collision flag; it performs NO content-tree write.
 *
 * The flags it derives ([occupied], and the hash compare the service runs over [currentBytes]) read the PUBLISHED
 * snapshot, NOT on-disk truth under the apply monitor — so `base_drifted` is a NON-AUTHORITATIVE pre-apply triage
 * datum. P1b's apply over on-disk truth is the real gate; a benign TOCTOU disagreement is acceptable.
 *
 * Every target is a [RootedPath] — a rooted PAIR, never a loose `(root, path)` — so an untrusted root can never be
 * smuggled in beside a path that does not belong to it. Every call is reached from a SNAPSHOT-derived target with
 * exactly ONE exception (`recoverApplyingRow`'s CREATE arm, whose path comes from a durable ROW rather than the
 * snapshot), and that one consults the row's root status before it reads.
 */
interface ProposalBaseReader {

    /**
     * The published content-file path of [pageId] **within [root]** — an edit proposal's live target — or null when
     * that root publishes no page with that id.
     *
     * Scoped to the proposal's OWN root, deliberately (per-root identity, C5). A page id is a durable identity but NOT
     * a durable location, and post-flip it does not even name ONE root: the SAME frontmatter `id:` may live in several
     * roots at once (a legal cross-root duplicate), each root holding its OWN page under it. So an unscoped bare-id
     * resolve is AMBIGUOUS and could silently walk a proposal off the root it was filed, gated and reviewed against and
     * onto a stranger's file: the approve writes there, the rebase re-pins `base_hash` there, `base_drifted` reports on
     * it. With two checkouts of one repo (the D2 case that makes duplicate ids ROUTINE) the bytes are identical, so the
     * CAS does not even catch it.
     *
     * Root-scoping keeps the one resolution that IS wanted - an in-root move (the file was renamed on disk; the id
     * travels with its frontmatter) still applies - and turns the cross-root case into an honest null, which every
     * consumer already handles as "the target is gone".
     */
    fun pathOf(root: RootName, pageId: PageId): RootedPath?

    /**
     * The target's CURRENT source bytes, CLASSIFIED — the base-hash / drift source.
     *
     * A [ContentRead] and not a `ByteArray?`, because THREE of this port's four consumers turn a bare null into a
     * durable rewrite or a wire lie: `recoverApplyingRow` would stamp an APPLYING row back to PENDING, `rebase`
     * would stamp its row terminally FAILED (`rebase_target_gone` — foreclosing the recovery that restoring the
     * root would give), and `proposeEdit` would tell an agent its base moved when the truth is that the disk is
     * unmounted. A downed root and a deleted page are byte-identical as a null; they are not the same answer, and
     * here the difference is durable.
     */
    fun currentBytes(target: RootedPath): ContentRead

    /** True iff a content FILE (page OR asset) currently occupies [target] in the published snapshot — the create-collision flag. */
    fun occupied(target: RootedPath): Boolean
}

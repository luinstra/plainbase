package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.ClaimantState
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.RetiredBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import kotlin.time.Clock

/**
 * SQLDelight adapter for [IdMapRepository] over the `id_map` and `identity_issue` tables (IdMap.sq).
 *
 * Ids are 16-byte BLOBs at rest ([PageIdColumnAdapter] — the single conversion point); issues are
 * flattened one row per [IdentityIssue] variant ([IssueRow] documents the per-kind column mapping).
 * [record]'s idempotence is schema-enforced: the `identity_issue` UNIQUE natural key plus an upsert
 * keep exactly one row per issue, so re-running `adopt` over an unchanged tree never grows the
 * issues list — while a message that changed between runs is refreshed, never served stale.
 */
class SqlDelightIdMapRepository(
    private val db: PlainbaseDb,
    private val clock: Clock = Clock.System,
) : IdMapRepository {

    private val queries get() = db.idMapQueries
    private val observations get() = db.rootObservationQueries

    override fun find(path: RootedPath): IdBinding? =
        queries.selectBinding(root = path.root, path = path.path).executeAsOneOrNull()?.toBinding()

    override fun rootsHoldingId(id: PageId): List<RootName> = queries.selectRootsHoldingId(id).executeAsList()

    override fun retiredRootsHoldingId(id: PageId): List<RootName> = queries.selectRetiredRootsHoldingId(id).executeAsList()

    override fun retiredAt(root: RootName, id: PageId): RetiredBinding? =
        queries.selectRetiredAt(root = root, id = id).executeAsOneOrNull()?.toRetired()

    override fun bindingInRoot(root: RootName, id: PageId): IdBinding? =
        queries.selectBindingByRootId(id = id, root = root).executeAsOneOrNull()?.toBinding()

    override fun retiredBindings(): List<RetiredBinding> = queries.selectAllRetired().executeAsList().map { it.toRetired() }

    // ONE statement (selectClaimantsById, IdMap.sq) reads both claimant lists off a single consistent SQLite snapshot,
    // so the live and retired lists reflect the SAME durable moment WITHOUT opening a transaction. That matters on the
    // app DB's single non-thread-safe connection: a BEGIN here raced a concurrent bare resolve's BEGIN into "cannot
    // start a transaction within a transaction" (C5 regression). kind partitions the rows: 1 = a retired tombstone, 0 =
    // a live claimant (root only).
    override fun claimantState(id: PageId): ClaimantState {
        val (retired, live) = queries.selectClaimantsById(id).executeAsList().partition { it.kind == RETIRED_CLAIMANT }
        return ClaimantState(
            live = live.map { it.root },
            retired = retired.map {
                RetiredBinding(
                    id = requireNotNull(it.id),
                    path = RootedPath(it.root, requireNotNull(it.path)),
                    materialized = requireNotNull(it.materialized),
                    retiredAt = requireNotNull(it.retired_at),
                )
            },
        )
    }

    /**
     * ONE transaction, and every C0 identity rule that cannot be enforced by convention lives inside it.
     *
     * The order is the argument:
     *  1. **The tombstone reservation, PER ROOT.** The reservation is scoped to this bind's own root (the tombstone
     *     is read at `(path.root, id)`): a retired id is reclaimable ONLY by the same page returning to its OWN
     *     (root, path), and only that root's tombstone can refuse it. A tombstone under ANOTHER root reserves that
     *     id for that root's own page and never speaks to this bind. Post-flip `UNIQUE(id, root)` legalizes the
     *     same id living in two roots, so the resolver fails CLOSED on a foreign tombstone rather than serving the
     *     wrong page (PageRootResolver.resolution): a dead link announces itself, a live link to the wrong document
     *     does not.
     *  2. **The supersession gate, root-scoped.** The incumbent is read at `(id, path.root)`, so it can only ever
     *     be a SAME-root binding; removing another (root, path)'s row under this root asserts it no longer holds the
     *     id - a negative claim, and negative claims need authority. Outside [Supersession] we REFUSE, having
     *     written NOTHING, and the caller mints fresh.
     *  3. **The displacement tombstone.** Whatever id this key held before is leaving the live key space with no
     *     one else to hold it, so it is retired here, in the same transaction as the bind that displaces it -
     *     `/p/{root}/{oldId}` answers 410, never 404. (Safe against a same-pass claimant of that id purely by the
     *     caller's deterministic bind order (`IndexBuilder`: rank, then frontmatter-first, then path;
     *     `AdoptionPass`: rank then path): a winner always binds before the page it beat. If that order ever breaks,
     *     step 1 REFUSES the late claimant - a loud failure, never a silent steal.)
     *  4. **The binding-epoch advance (revoke-before-stamp, C5).** A successful bind is a binding change, so it
     *     increments `path.root`'s `binding_epoch` in this SAME transaction (NOT its observation - that would collapse
     *     the epoch every live proof rides; the two stamps are orthogonal). Any inferred `AbsenceProof` minted before
     *     this bind was stamped with the OLD epoch, so it loses `applyProofs`' two-token compare and cannot reap the
     *     binding a restore just re-created (its `dirty_page` recovery row is USER CONTENT). ONLY the Bound path
     *     advances - the two `Refused` early-returns land before the upsert and advance nothing; and even an idempotent
     *     same-`(root, path, id)` re-bind advances, because a restore's re-create IS that re-bind and must revoke the
     *     stale proof. The counter is monotonic and never reset, so there is no ABA hazard.
     */
    override fun bind(path: RootedPath, id: PageId, materialized: Boolean, supersession: Supersession): BindOutcome =
        db.transactionWithResult {
            val tombstone = queries.selectRetiredAt(root = path.root, id = id).executeAsOneOrNull()?.toRetired()
            if (tombstone != null && tombstone.path != path) {
                return@transactionWithResult BindOutcome.Refused(id, heldBy = tombstone.path, retired = true)
            }
            val incumbent = queries.selectBindingByRootId(id = id, root = path.root).executeAsOneOrNull()?.toBinding()
            if (incumbent != null && incumbent.path != path && !supersession.mayDisplace(incumbent)) {
                return@transactionWithResult BindOutcome.Refused(id, heldBy = incumbent.path, retired = false)
            }
            queries.selectBinding(root = path.root, path = path.path).executeAsOneOrNull()
                ?.toBinding()
                ?.takeIf { it.id != id }
                ?.let { displaced ->
                    queries.retire(
                        id = displaced.id,
                        root = path.root,
                        path = path.path,
                        materialized = displaced.materialized,
                        retiredAt = clock.now().toEpochMilliseconds(),
                    )
                }
            if (tombstone != null) queries.unretireInRoot(root = path.root, id = id) // this page reclaims its own (root, path)
            queries.unbindStaleInRoot(id = id, root = path.root, path = path.path)
            queries.upsertBinding(root = path.root, path = path.path, id = id, materialized = materialized)
            observations.incrementBindingEpoch(root = path.root) // revoke-before-stamp: this bind invalidates stale proofs
            BindOutcome.Bound
        }

    override fun markMaterialized(path: RootedPath) {
        queries.markMaterialized(materialized = true, root = path.root, path = path.path)
    }

    override fun bindings(): List<IdBinding> =
        queries.selectAllBindings().executeAsList().map { it.toBinding() }

    override fun roots(): Set<RootName> =
        queries.selectDistinctRoots().executeAsList().toSet()

    override fun record(issue: IdentityIssue) {
        insertIssue(issue)
    }

    /**
     * The one issue write: upsert on the schema's UNIQUE(kind, root, path, other_root, other_path,
     * page_id) - DB-enforced dedup with no read-then-insert window; a re-record with a changed
     * message refreshes the row.
     */
    private fun insertIssue(issue: IdentityIssue) {
        val row = issue.toRow()
        queries.insertIssue(
            kind = row.kind.name,
            root = row.root,
            path = row.path,
            otherRoot = NO_OTHER_ROOT,
            otherPath = row.otherPath ?: NO_OTHER_PATH,
            pageId = row.pageId?.let(PageIdColumnAdapter::encode) ?: NO_PAGE_ID,
            message = row.message,
        )
    }

    override fun issues(): List<IdentityIssue> =
        queries.selectAllIssues().executeAsList().map { it.toIssue() }

    private fun Id_map.toBinding(): IdBinding = IdBinding(path = RootedPath(root, path), id = id, materialized = materialized)

    private fun Retired_binding.toRetired(): RetiredBinding =
        RetiredBinding(id = id, path = RootedPath(root, path), materialized = materialized, retiredAt = retired_at)

    /**
     * One issue's flattened column values - THE per-kind mapping, in both directions ([toRow] /
     * [toIssue]); `(kind, root, path, other_root, other_path, page_id)` is the natural key behind the
     * schema's UNIQUE constraint, in that column order:
     *
     * | kind                       | root       | path     | other_root | other_path         | page_id | message      |
     * |----------------------------|------------|----------|------------|--------------------|---------|--------------|
     * | `DUPLICATE_ID`             | shared root| keptPath | -          | reassignedPath     | id      | -            |
     * | `PATCH_REFUSED`            | issue root | path     | -          | -                  | -       | refusal text |
     * | `REDIRECT_CONFLICT`        | issue root | path     | -          | -                  | -       | conflict text|
     * | `PATH_COLLISION`           | issue root | keptPath | -          | loserRawName (raw) | -       | -            |
     * | `PATH_SLUG_COLLISION`      | issue root | keptPath | -          | loserPath          | -       | -            |
     *
     * [otherPath] is a raw string (the schema's `other_path` is plain TEXT, not `AS TreePath`)
     * because `PATH_COLLISION` stores a raw on-disk filename that must NOT be normalized — for the
     * NFC/NFD siblings the issue exists to report, [TreePath.require] would collapse it into the
     * kept path. The other two-path kinds store a real [TreePath]'s canonical [TreePath.value].
     * `other_root` is likewise plain TEXT, and since C7 removed the one cross-root kind it is now
     * ALWAYS the [NO_OTHER_ROOT] sentinel: no surviving kind names a second root. The column stays
     * because it is part of the UNIQUE key, and the sentinel is not a valid [RootName].
     *
     * Absent key fields persist as the [NO_OTHER_ROOT]/[NO_OTHER_PATH]/[NO_PAGE_ID] sentinels,
     * never NULL - SQLite treats NULLs as distinct inside a UNIQUE index, which would defeat the
     * dedup. (A raw filename is never empty, so `PATH_COLLISION` cannot collide with the sentinel.)
     */
    private data class IssueRow(
        val kind: IdentityIssue.Kind,
        val root: RootName,
        val path: TreePath,
        val otherPath: String? = null,
        val pageId: PageId? = null,
        val message: String? = null,
    )

    private fun IdentityIssue.toRow(): IssueRow = when (this) {
        is IdentityIssue.DuplicateId -> IssueRow(kind, root, keptPath, otherPath = reassignedPath.value, pageId = id)
        is IdentityIssue.PatchRefused -> IssueRow(kind, root, path, message = message)
        is IdentityIssue.RedirectConflict -> IssueRow(kind, root, path, message = message)
        is IdentityIssue.PathCollision -> IssueRow(kind, root, keptPath, otherPath = loserRawName)
        is IdentityIssue.PathSlugCollision -> IssueRow(kind, root, keptPath, otherPath = loserPath.value)
    }

    private fun Identity_issue.toIssue(): IdentityIssue {
        val otherPath = other_path.takeIf { it != NO_OTHER_PATH }
        val pageId = page_id.takeIf { it.isNotEmpty() }?.let(PageIdColumnAdapter::decode)
        return when (IdentityIssue.Kind.valueOf(kind)) {
            IdentityIssue.Kind.DUPLICATE_ID ->
                IdentityIssue.DuplicateId(requireNotNull(pageId), root, path, TreePath.require(requireNotNull(otherPath)))
            IdentityIssue.Kind.PATCH_REFUSED ->
                IdentityIssue.PatchRefused(root, path, requireNotNull(message))
            IdentityIssue.Kind.REDIRECT_CONFLICT ->
                IdentityIssue.RedirectConflict(root, path, requireNotNull(message))
            IdentityIssue.Kind.PATH_COLLISION ->
                IdentityIssue.PathCollision(root, path, requireNotNull(otherPath))
            IdentityIssue.Kind.PATH_SLUG_COLLISION ->
                IdentityIssue.PathSlugCollision(root, path, TreePath.require(requireNotNull(otherPath)))
        }
    }

    private companion object {
        /** [selectClaimantsById]'s discriminator: 1 tags a retired-binding row, 0 a live claimant (see IdMap.sq). */
        const val RETIRED_CLAIMANT = 1L

        /** Sentinels for absent UNIQUE-key columns (see [IssueRow]); all are impossible real values. */
        const val NO_OTHER_ROOT = ""
        const val NO_OTHER_PATH = ""
        val NO_PAGE_ID = ByteArray(0)
    }
}

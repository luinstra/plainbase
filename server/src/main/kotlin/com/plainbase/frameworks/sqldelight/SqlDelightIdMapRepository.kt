package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/**
 * SQLDelight adapter for [IdMapRepository] over the `id_map` and `identity_issue` tables (IdMap.sq).
 *
 * Ids are 16-byte BLOBs at rest ([PageIdColumnAdapter] — the single conversion point); issues are
 * flattened one row per [IdentityIssue] variant ([IssueRow] documents the per-kind column mapping).
 * [record]'s idempotence is schema-enforced: the `identity_issue` UNIQUE natural key plus an upsert
 * keep exactly one row per issue, so re-running `adopt` over an unchanged tree never grows the
 * issues list — while a message that changed between runs is refreshed, never served stale.
 */
class SqlDelightIdMapRepository(private val db: PlainbaseDb) : IdMapRepository {

    private val queries get() = db.idMapQueries

    override fun find(path: RootedPath): IdBinding? =
        queries.selectBinding(root = path.root, path = path.path).executeAsOneOrNull()?.toBinding()

    override fun pathOf(id: PageId): RootedPath? =
        queries.selectPathById(id).executeAsOneOrNull()?.let { RootedPath(it.root, it.path) }

    override fun bind(path: RootedPath, id: PageId, materialized: Boolean) {
        db.transaction {
            // Key-complete supersede: any OTHER (root, path) holding the id goes - a moved file's
            // stale row, a detached root's row, or a scanned rank-contest loser's (port contract:
            // the caller's D2/D16/D17 duplicate policy is what keeps unscanned owners safe) -
            // keeping UNIQUE(id) honest.
            queries.unbindStale(id = id, root = path.root, path = path.path)
            queries.upsertBinding(root = path.root, path = path.path, id = id, materialized = materialized)
        }
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
            otherRoot = row.otherRoot?.value ?: NO_OTHER_ROOT,
            otherPath = row.otherPath ?: NO_OTHER_PATH,
            pageId = row.pageId?.let(PageIdColumnAdapter::encode) ?: NO_PAGE_ID,
            message = row.message,
        )
    }

    override fun issues(): List<IdentityIssue> =
        queries.selectAllIssues().executeAsList().map { it.toIssue() }

    private fun Id_map.toBinding(): IdBinding = IdBinding(path = RootedPath(root, path), id = id, materialized = materialized)

    /**
     * One issue's flattened column values - THE per-kind mapping, in both directions ([toRow] /
     * [toIssue]); `(kind, root, otherRoot, path, otherPath, pageId)` is the natural key behind the
     * schema's UNIQUE constraint:
     *
     * | kind                       | root       | otherRoot       | path     | otherPath          | pageId | message      |
     * |----------------------------|------------|-----------------|----------|--------------------|--------|--------------|
     * | `DUPLICATE_ID`             | shared root| -               | keptPath | reassignedPath     | id     | -            |
     * | `PATCH_REFUSED`            | issue root | -               | path     | -                  | -      | refusal text |
     * | `REDIRECT_CONFLICT`        | issue root | -               | path     | -                  | -      | conflict text|
     * | `PATH_COLLISION`           | issue root | -               | keptPath | loserRawName (raw) | -      | -            |
     * | `PATH_SLUG_COLLISION`      | issue root | -               | keptPath | loserPath          | -      | -            |
     * | `CROSS_ROOT_DUPLICATE_ID`  | kept.root  | reassigned.root | kept.path| reassigned.path    | id     | -            |
     *
     * [otherPath] is a raw string (the schema's `other_path` is plain TEXT, not `AS TreePath`)
     * because `PATH_COLLISION` stores a raw on-disk filename that must NOT be normalized — for the
     * NFC/NFD siblings the issue exists to report, [TreePath.require] would collapse it into the
     * kept path. The other two-path kinds store a real [TreePath]'s canonical [TreePath.value].
     * [otherRoot] is likewise plain TEXT in the schema: it is non-sentinel ONLY for the cross-root
     * kind, and the sentinel is not a valid [RootName].
     *
     * Absent key fields persist as the [NO_OTHER_ROOT]/[NO_OTHER_PATH]/[NO_PAGE_ID] sentinels,
     * never NULL - SQLite treats NULLs as distinct inside a UNIQUE index, which would defeat the
     * dedup. (A raw filename is never empty, so `PATH_COLLISION` cannot collide with the sentinel.)
     */
    private data class IssueRow(
        val kind: IdentityIssue.Kind,
        val root: RootName,
        val path: TreePath,
        val otherRoot: RootName? = null,
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
        is IdentityIssue.CrossRootDuplicateId ->
            IssueRow(kind, kept.root, kept.path, otherRoot = reassigned.root, otherPath = reassigned.path.value, pageId = id)
    }

    private fun Identity_issue.toIssue(): IdentityIssue {
        val otherRoot = other_root.takeIf { it != NO_OTHER_ROOT }?.let(RootName::require)
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
            IdentityIssue.Kind.CROSS_ROOT_DUPLICATE_ID ->
                IdentityIssue.CrossRootDuplicateId(
                    id = requireNotNull(pageId),
                    kept = RootedPath(root, path),
                    reassigned = RootedPath(requireNotNull(otherRoot), TreePath.require(requireNotNull(otherPath))),
                )
        }
    }

    private companion object {
        /** Sentinels for absent UNIQUE-key columns (see [IssueRow]); all are impossible real values. */
        const val NO_OTHER_ROOT = ""
        const val NO_OTHER_PATH = ""
        val NO_PAGE_ID = ByteArray(0)
    }
}

package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository

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

    override fun find(path: TreePath): IdBinding? =
        queries.selectBinding(path).executeAsOneOrNull()?.toBinding()

    override fun pathOf(id: PageId): TreePath? =
        queries.selectPathById(id).executeAsOneOrNull()

    override fun bind(path: TreePath, id: PageId, materialized: Boolean) {
        db.transaction {
            // Supersede a stale row when the id moved with its file (port contract: the caller's
            // duplicate policy guarantees a live owner never reaches here) — keeps UNIQUE(id) honest.
            queries.unbindStale(id = id, path = path)
            queries.upsertBinding(path = path, id = id, materialized = materialized)
        }
    }

    override fun markMaterialized(path: TreePath) {
        queries.markMaterialized(materialized = true, path = path)
    }

    override fun bindings(): List<IdBinding> =
        queries.selectAllBindings().executeAsList().map { it.toBinding() }

    override fun record(issue: IdentityIssue) {
        val row = issue.toRow()
        // Upsert on the schema's UNIQUE(kind, path, other_path, page_id): DB-enforced dedup with
        // no read-then-insert window; a re-record with a changed message refreshes the row.
        queries.insertIssue(
            kind = row.kind.name,
            path = row.path,
            otherPath = row.otherPath ?: NO_OTHER_PATH,
            pageId = row.pageId?.let(PageIdColumnAdapter::encode) ?: NO_PAGE_ID,
            message = row.message,
        )
    }

    override fun issues(): List<IdentityIssue> =
        queries.selectAllIssues().executeAsList().map { it.toIssue() }

    private fun Id_map.toBinding(): IdBinding = IdBinding(path = path, id = id, materialized = materialized)

    /**
     * One issue's flattened column values — THE per-kind mapping, in both directions ([toRow] /
     * [toIssue]); `(kind, path, otherPath, pageId)` is the natural key behind the schema's UNIQUE
     * constraint:
     *
     * | kind                  | path     | otherPath          | pageId | message      |
     * |-----------------------|----------|--------------------|--------|--------------|
     * | `DUPLICATE_ID`        | keptPath | reassignedPath     | id     | —            |
     * | `PATCH_REFUSED`       | path     | —                  | —      | refusal text |
     * | `REDIRECT_CONFLICT`   | path     | —                  | —      | conflict text|
     * | `PATH_COLLISION`      | keptPath | loserRawName (raw) | —      | —            |
     * | `PATH_SLUG_COLLISION` | keptPath | loserPath          | —      | —            |
     *
     * [otherPath] is a raw string (the schema's `other_path` is plain TEXT, not `AS TreePath`)
     * because `PATH_COLLISION` stores a raw on-disk filename that must NOT be normalized — for the
     * NFC/NFD siblings the issue exists to report, [TreePath.require] would collapse it into the
     * kept path. The other two-path kinds store a real [TreePath]'s canonical [TreePath.value].
     *
     * Absent key fields persist as the [NO_OTHER_PATH]/[NO_PAGE_ID] sentinels, never NULL —
     * SQLite treats NULLs as distinct inside a UNIQUE index, which would defeat the dedup.
     * (A raw filename is never empty, so `PATH_COLLISION` cannot collide with the sentinel.)
     */
    private data class IssueRow(
        val kind: IdentityIssue.Kind,
        val path: TreePath,
        val otherPath: String? = null,
        val pageId: PageId? = null,
        val message: String? = null,
    )

    private fun IdentityIssue.toRow(): IssueRow = when (this) {
        is IdentityIssue.DuplicateId -> IssueRow(kind, keptPath, otherPath = reassignedPath.value, pageId = id)
        is IdentityIssue.PatchRefused -> IssueRow(kind, path, message = message)
        is IdentityIssue.RedirectConflict -> IssueRow(kind, path, message = message)
        is IdentityIssue.PathCollision -> IssueRow(kind, keptPath, otherPath = loserRawName)
        is IdentityIssue.PathSlugCollision -> IssueRow(kind, keptPath, otherPath = loserPath.value)
    }

    private fun Identity_issue.toIssue(): IdentityIssue {
        val otherPath = other_path.takeIf { it != NO_OTHER_PATH }
        val pageId = page_id.takeIf { it.isNotEmpty() }?.let(PageIdColumnAdapter::decode)
        return when (IdentityIssue.Kind.valueOf(kind)) {
            IdentityIssue.Kind.DUPLICATE_ID ->
                IdentityIssue.DuplicateId(requireNotNull(pageId), path, TreePath.require(requireNotNull(otherPath)))
            IdentityIssue.Kind.PATCH_REFUSED ->
                IdentityIssue.PatchRefused(path, requireNotNull(message))
            IdentityIssue.Kind.REDIRECT_CONFLICT ->
                IdentityIssue.RedirectConflict(path, requireNotNull(message))
            IdentityIssue.Kind.PATH_COLLISION ->
                IdentityIssue.PathCollision(path, requireNotNull(otherPath))
            IdentityIssue.Kind.PATH_SLUG_COLLISION ->
                IdentityIssue.PathSlugCollision(path, TreePath.require(requireNotNull(otherPath)))
        }
    }

    private companion object {
        /** Sentinels for absent UNIQUE-key columns (see [IssueRow]); both are impossible real values. */
        const val NO_OTHER_PATH = ""
        val NO_PAGE_ID = ByteArray(0)
    }
}

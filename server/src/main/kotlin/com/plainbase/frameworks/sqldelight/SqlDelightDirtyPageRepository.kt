package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.Stage

/**
 * SQLDelight adapter for [DirtyPageRepository] over the `dirty_page` table (landed by `3.sqm`).
 *
 * Ids are 16-byte BLOBs and paths NFC text at rest (the shared column adapters). [Stage] is stored as
 * its enum name in TEXT and mapped here ([Stage.name] / [Stage.valueOf]) — a one-site mapping, no new
 * column adapter. [mark] is an INSERT OR REPLACE so a re-mark of the same page simply re-points.
 */
class SqlDelightDirtyPageRepository(private val db: PlainbaseDb) : DirtyPageRepository {

    private val queries get() = db.dirtyPageQueries

    override fun mark(pageId: PageId, path: TreePath, expectedHash: String, stage: Stage) {
        queries.upsert(id = pageId, path = path, expectedHash = expectedHash, stage = stage.name)
    }

    override fun all(): List<DirtyPage> =
        queries.selectAll().executeAsList().map {
            DirtyPage(pageId = it.id, path = it.path, expectedHash = it.expected_hash, stage = Stage.valueOf(it.stage))
        }

    override fun get(pageId: PageId): DirtyPage? =
        queries.selectById(pageId).executeAsOneOrNull()?.let {
            DirtyPage(pageId = it.id, path = it.path, expectedHash = it.expected_hash, stage = Stage.valueOf(it.stage))
        }

    override fun clear(pageId: PageId) {
        queries.deleteById(pageId)
    }
}

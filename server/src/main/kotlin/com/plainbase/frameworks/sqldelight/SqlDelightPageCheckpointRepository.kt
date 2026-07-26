package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.root.RootedPageId
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * SQLDelight adapter for [PageCheckpointRepository] over the `page_checkpoint` table (landed by
 * S2's `2.sqm`; root-stamped by C2's `10.sqm`). Ids are 16-byte BLOBs, paths NFC text, and roots
 * validated slugs at rest (the shared column adapters).
 *
 * The port's advisory contract is enforced HERE: a row the adapters cannot decode (hand-edited DB,
 * torn write - a corrupt root name included) makes [load] answer the empty checkpoint with a
 * warning instead of failing startup — §B3's degrade-to-pre-Phase-2 promise. [replace] is one
 * transaction, so a crash mid-replace leaves the previous complete checkpoint, never a
 * half-written one (risk R11).
 */
class SqlDelightPageCheckpointRepository(private val db: PlainbaseDb) : PageCheckpointRepository {

    private val queries get() = db.pageCheckpointQueries

    override fun load(): Map<RootedPageId, TreePath?> =
        runCatching {
            queries.selectAll().executeAsList().associate { RootedPageId(it.root, it.id) to it.url_path }
        }.getOrElse { failure ->
            if (failure is Error) throw failure
            logger.warn(failure) { "page_checkpoint unreadable; continuing without down-time move aliases (advisory, §B3)" }
            emptyMap()
        }

    override fun replace(urlPaths: Map<RootedPageId, TreePath?>) {
        db.transaction {
            queries.deleteAll()
            urlPaths.forEach { (rooted, urlPath) -> queries.insertRow(id = rooted.id, root = rooted.root, urlPath = urlPath) }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

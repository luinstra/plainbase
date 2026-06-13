package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.PageCheckpointRepository
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * SQLDelight adapter for [PageCheckpointRepository] over the `page_checkpoint` table (landed by
 * S2's `2.sqm`). Ids are 16-byte BLOBs and paths NFC text at rest (the shared column adapters).
 *
 * The port's advisory contract is enforced HERE: a row the adapters cannot decode (hand-edited DB,
 * torn write) makes [load] answer the empty checkpoint with a warning instead of failing startup —
 * §B3's degrade-to-pre-Phase-2 promise. [replace] is one transaction, so a crash mid-replace
 * leaves the previous complete checkpoint, never a half-written one (risk R11).
 */
class SqlDelightPageCheckpointRepository(private val db: PlainbaseDb) : PageCheckpointRepository {

    private val queries get() = db.pageCheckpointQueries

    override fun load(): Map<PageId, TreePath?> = try {
        queries.selectAll().executeAsList().associate { it.id to it.url_path }
    } catch (e: Exception) {
        logger.warn(e) { "page_checkpoint unreadable; continuing without down-time move aliases (advisory, §B3)" }
        emptyMap()
    }

    override fun replace(urlPaths: Map<PageId, TreePath?>) {
        db.transaction {
            queries.deleteAll()
            urlPaths.forEach { (id, urlPath) -> queries.insertRow(id = id, urlPath = urlPath) }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

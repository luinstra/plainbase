package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.UrlAlias
import com.plainbase.domain.repository.UrlAliasRepository

/**
 * SQLDelight adapter for [UrlAliasRepository] over the `url_alias` table (IdMap.sq).
 *
 * Ids are 16-byte BLOBs at rest ([PageIdColumnAdapter]). Chain-collapse is structural — the column
 * holds a page id, never another alias's path — and [register]'s upsert keeps one row per path, so
 * a re-claimed old path simply re-points (§A4 one-hop guarantee).
 */
class SqlDelightUrlAliasRepository(private val db: PlainbaseDb) : UrlAliasRepository {

    private val queries get() = db.idMapQueries

    override fun register(path: TreePath, id: PageId) {
        queries.upsertAlias(path = path, id = id)
    }

    override fun find(path: TreePath): PageId? =
        queries.selectAliasId(path).executeAsOneOrNull()

    override fun aliases(): List<UrlAlias> =
        queries.selectAllAliases().executeAsList().map { UrlAlias(path = it.path, id = it.id) }

    override fun dropShadowed(canonicalPath: TreePath): UrlAlias? =
        db.transactionWithResult {
            queries.selectAliasId(canonicalPath).executeAsOneOrNull()?.let { shadowed ->
                queries.deleteAlias(canonicalPath)
                UrlAlias(path = canonicalPath, id = shadowed)
            }
        }
}

package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.UrlAlias
import com.plainbase.domain.repository.UrlAliasRepository
import com.plainbase.domain.root.RootedPath

/**
 * SQLDelight adapter for [UrlAliasRepository] over the `url_alias` table (IdMap.sq).
 *
 * Ids are 16-byte BLOBs at rest ([PageIdColumnAdapter]). Chain-collapse is structural — the column
 * holds a page id, never another alias's path — and [register]'s upsert keeps one row per
 * (root, path), so a re-claimed old path simply re-points (§A4 one-hop guarantee).
 */
class SqlDelightUrlAliasRepository(private val db: PlainbaseDb) : UrlAliasRepository {

    private val queries get() = db.idMapQueries

    override fun register(path: RootedPath, id: PageId) {
        queries.upsertAlias(root = path.root, path = path.path, id = id)
    }

    override fun find(path: RootedPath): PageId? =
        queries.selectAliasId(root = path.root, path = path.path).executeAsOneOrNull()

    override fun aliases(): List<UrlAlias> =
        queries.selectAllAliases().executeAsList().map { UrlAlias(path = RootedPath(it.root, it.path), id = it.id) }

    override fun dropShadowed(canonicalPath: RootedPath): UrlAlias? =
        db.transactionWithResult {
            queries.selectAliasId(root = canonicalPath.root, path = canonicalPath.path).executeAsOneOrNull()?.let { shadowed ->
                queries.deleteAlias(root = canonicalPath.root, path = canonicalPath.path)
                UrlAlias(path = canonicalPath, id = shadowed)
            }
        }
}

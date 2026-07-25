package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.UrlAlias
import com.plainbase.domain.repository.UrlAliasRepository
import com.plainbase.domain.root.RootedPageId
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

    override fun register(path: RootedPath, target: RootedPageId) {
        queries.upsertAlias(root = path.root, path = path.path, id = target.id, targetRoot = target.root)
    }

    override fun find(path: RootedPath): RootedPageId? =
        queries.selectAliasTarget(root = path.root, path = path.path).executeAsOneOrNull()?.let { RootedPageId(it.target_root, it.id) }

    override fun aliases(): List<UrlAlias> =
        queries.selectAllAliases().executeAsList().map {
            UrlAlias(path = RootedPath(it.root, it.path), target = RootedPageId(it.target_root, it.id))
        }

    override fun dropShadowed(canonicalPath: RootedPath): UrlAlias? =
        db.transactionWithResult {
            queries.selectAliasTarget(root = canonicalPath.root, path = canonicalPath.path).executeAsOneOrNull()?.let { shadowed ->
                queries.deleteAlias(root = canonicalPath.root, path = canonicalPath.path)
                UrlAlias(path = canonicalPath, target = RootedPageId(shadowed.target_root, shadowed.id))
            }
        }
}

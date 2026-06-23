@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.AssetReadOutcome
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageHtmlPayload
import com.plainbase.domain.service.PagePayload
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ReadFacade
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.UrlAliasRegistry
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The frameworks-side [ReadFacade] impl (A3): it holds the raw read services + the [PolicyService] as PRIVATE
 * deps, calls `checkRead` FIRST on every method (throwing [com.plainbase.domain.service.AccessDenied] BEFORE any
 * snapshot/membership work, so a read never leaks page existence to anonymous), then delegates. The route never
 * sees the raw services. The memoized tree JSON ([TreeJsonCache]) lives here (per-snapshot framework state, not a
 * mutator). The `AccessDenied` → 401/403 mapping is the route's; this impl just lets the throw propagate.
 */
class GuardedReadFacade(
    private val policy: PolicyService,
    private val pageService: PageService,
    private val searchService: SearchService,
    private val contentStore: ContentStore,
    private val indexBuilder: IndexBuilder,
    private val history: HistoryProvider,
    private val aliasRegistry: UrlAliasRegistry,
) : ReadFacade {

    private val treeJson = TreeJsonCache(indexBuilder)

    override fun pageById(principal: Principal, id: PageId): PagePayload? {
        policy.checkRead(principal, id.value)
        return pageService.byId(id)
    }

    override fun pageByUrlPath(principal: Principal, path: TreePath): PagePayload? {
        policy.checkRead(principal, path.value)
        return pageService.byUrlPath(path)
    }

    override fun pageHtml(principal: Principal, id: PageId): PageHtmlPayload? {
        policy.checkRead(principal, id.value)
        return pageService.htmlById(id)
    }

    override fun search(principal: Principal, q: String?, limit: String?, offset: String?): SearchService.Outcome {
        policy.checkRead(principal, "search")
        return searchService.search(q = q, limit = limit, offset = offset)
    }

    override fun tree(principal: Principal): String {
        policy.checkRead(principal, "tree")
        return treeJson.current()
    }

    override fun preview(principal: Principal, sourcePath: TreePath, bytes: ByteArray): RenderedPage {
        policy.checkRead(principal, "preview")
        return indexBuilder.renderPreview(sourcePath, bytes)
    }

    override fun history(principal: Principal, path: TreePath, limit: Int): List<Commit> {
        policy.checkRead(principal, path.value)
        return history.log(path, limit)
    }

    override fun diff(principal: Principal, from: String, to: String, path: TreePath): FileDiff {
        policy.checkRead(principal, path.value)
        return history.diff(from, to, path)
    }

    // Ungated: `enabled` is a server CAPABILITY flag, not page existence — it leaks nothing about the content tree, so
    // it needs no read check. The history/diff routes call this AFTER their own pageById checkRead has passed; a second
    // checkRead on the same authorized request was redundant (W5/A3 minor).
    override fun gitEnabled(principal: Principal): Boolean = history.enabled

    override fun assetRead(principal: Principal, path: TreePath): AssetReadOutcome {
        policy.checkRead(principal, path.value)
        if (path !in indexBuilder.current.assets) return AssetReadOutcome.NotContentAsset
        // An indexed asset whose on-disk file vanished is IndexedButMissing (→ 404), NOT NotContentAsset: it must
        // never fall through to bundled static and unmask a shadowed name (disk is source of truth).
        return contentStore.read(path)?.let(AssetReadOutcome::Found) ?: AssetReadOutcome.IndexedButMissing
    }

    override fun pageBytes(principal: Principal, path: TreePath): ByteArray? {
        policy.checkRead(principal, path.value)
        return contentStore.read(path)
    }

    override fun currentSnapshot(principal: Principal, resource: String): PageIndex {
        policy.checkRead(principal, resource)
        return indexBuilder.current
    }

    override fun resolveDocsRedirect(principal: Principal, path: TreePath): String? {
        // Deny → null (NOT a throw): the docsRoutes shell-fallback arm is public, so an unauthorized caller must
        // fall through to the shell exactly like any unknown path (a 401 here would leak that an alias exists).
        try {
            policy.checkRead(principal, path.value)
        } catch (_: AccessDenied) {
            return null
        }
        val snapshot = indexBuilder.current
        val target = aliasRegistry.find(path)
            .takeIf { path !in snapshot.byUrlPath } // live canonical wins (§A4)
            ?.let { snapshot.byId[it] }
            ?: return null
        return target.url ?: target.permalink
    }
}

package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.render.RenderedPage

/**
 * The guarded READ surface (A3, the choke point). Every method takes a [Principal], calls
 * `PolicyService.checkRead` FIRST (throwing [AccessDenied] on deny — BEFORE any snapshot/membership work, so a
 * read never leaks page existence to an anonymous caller), then delegates to the underlying read services. This
 * interface is the ONLY domain surface a READ route touches for these calls — a read route physically cannot
 * reference a mutator (interface segregation). The `action`+`resource` pair is INTRINSIC to each method (the
 * route never passes an action), so REST and the future MCP server get the identical `check()` for free.
 *
 * The impl ([com.plainbase.frameworks.ktor.GuardedReadFacade]) lives frameworks-side because the
 * [AccessDenied] → HTTP-status mapping is a frameworks concern; it holds the raw read services + the
 * [PolicyService] as PRIVATE deps.
 */
interface ReadFacade {

    fun pageById(principal: Principal, id: PageId): PagePayload?

    fun pageByUrlPath(principal: Principal, path: TreePath): PagePayload?

    fun pageHtml(principal: Principal, id: PageId): PageHtmlPayload?

    /**
     * The broken links + anchors ON the page [id] (Phase 5 `validate_links`, master §2.6): a checkRead-FIRST gated,
     * per-page view of the EXISTING whole-index [LinkChecker] report (filtered to this page). Null when [id] is
     * unknown (the checkRead gate fired first, so a denied caller cannot tell unknown-from-known). NOT a re-checker —
     * it aggregates the one render-time resolution model, exactly like the whole-tree gate.
     */
    fun validateLinks(principal: Principal, id: PageId): LinkReport?

    /**
     * The page [id]'s metadata projection (Phase 5 `get_page_metadata`, master §2.6): id/path/url/content_hash/
     * commit/title/headings, all from the published snapshot (no disk read). checkRead-FIRST; null when [id] unknown
     * (the gate fired first). Headings are document order. Returns the domain [IndexedPage] (the route projects it).
     */
    fun pageMetadata(principal: Principal, id: PageId): IndexedPage?

    fun search(principal: Principal, q: String?, limit: String?, offset: String?): SearchService.Outcome

    /** The memoized `/api/v1/tree` JSON for the current published snapshot. */
    fun tree(principal: Principal): String

    fun preview(principal: Principal, sourcePath: TreePath, bytes: ByteArray): RenderedPage

    fun history(principal: Principal, path: TreePath, limit: Int): List<Commit>

    fun diff(principal: Principal, from: String, to: String, path: TreePath): FileDiff

    /**
     * Whether the history layer is Git-backed (the `git_enabled` flag). Intentionally UNGATED — it is a server
     * CAPABILITY flag, not page existence, so it leaks nothing about the content tree. The [principal] is kept only
     * for port-signature uniformity with the gated reads above (the impl ignores it); do NOT read it as enforced.
     */
    fun gitEnabled(principal: Principal): Boolean

    /**
     * A content-tree asset read (read-gated — the gate fires BEFORE membership, so existence never leaks). The
     * outcome SEPARATES "not a content asset" from "indexed but the on-disk file vanished": only [AssetReadOutcome
     * .NotContentAsset] may fall through to the PUBLIC bundled-static lookup. An indexed asset whose file vanished
     * is [AssetReadOutcome.IndexedButMissing] → 404 (disk is source of truth; a vanished upload must NEVER unmask
     * a bundled-static name it shadowed).
     */
    fun assetRead(principal: Principal, path: TreePath): AssetReadOutcome

    /** The page file's bytes for the asset-route stale recheck (a read), or null when gone / unreadable→throw. */
    fun pageBytes(principal: Principal, path: TreePath): ByteArray?

    /**
     * The current published snapshot, for the routes that resolve against it (permalink/browse redirects, the
     * asset content-tree membership). Read-GATED so the redirect's existence is not revealed to an anonymous
     * caller (the gate fires BEFORE the resolve). [resource] names the lookup for the audit-free read gate.
     */
    fun currentSnapshot(principal: Principal, resource: String): PageIndex

    /**
     * The `/docs/{path}` 301 alias-redirect target (the page's current canonical URL, or its permalink for a
     * collision loser), or null when there is no LIVE alias OR the principal may not read the target. Returning
     * null on a DENY (rather than throwing) is deliberate: the `docsRoutes` shell-fallback arm is PUBLIC, so an
     * unauthorized caller must fall through to the shell EXACTLY like any unknown path — a 401 here would itself
     * leak that an alias exists (§WI-5). A live canonical path shadows an alias (§A4).
     */
    fun resolveDocsRedirect(principal: Principal, path: TreePath): String?
}

/**
 * The outcome of [ReadFacade.assetRead] — it SEPARATES content-tree MEMBERSHIP from the disk READ so the asset
 * route can never conflate "this path isn't a content asset" with "an indexed asset's file vanished". Only
 * [NotContentAsset] is eligible for the public bundled-static fallback; an [IndexedButMissing] is a 404 (disk is
 * source of truth — a vanished upload must not unmask a bundled-static name it shadowed).
 */
sealed interface AssetReadOutcome {

    /** The path is not in the content tree's asset set — the route may fall through to bundled static. */
    data object NotContentAsset : AssetReadOutcome

    /** The path is an indexed content asset; [bytes] are its current on-disk content. */
    data class Found(val bytes: ByteArray) : AssetReadOutcome {
        override fun equals(other: Any?): Boolean = this === other || (other is Found && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** The path is an indexed content asset but its on-disk file vanished — a 404, NEVER the bundled fallback. */
    data object IndexedButMissing : AssetReadOutcome
}

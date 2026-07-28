package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

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
 *
 * **Availability (ADR-0011 D5).** Every method here throws [RootUnavailable] — AFTER its `checkRead` has passed,
 * never before — when the root it resolves is not serving, and the route funnel maps that to ONE 503
 * `root_unavailable` + `Retry-After`. The gate order is not an implementation detail: authn precedes topology, so
 * an anonymous prober's answer is byte-identical to today's on every surface and availability cannot leak. The one
 * deliberate exception is [currentSnapshot] (see its doc).
 */
interface ReadFacade {

    /**
     * The full page payload for [id], or null (§A4). [root] is the OPTIONAL `?root=` pin (C4): null resolves the
     * owning root id_map-first (bare read); a present pin is coherent-stale on a snapshot HIT and durable-validated
     * on a miss. An unregistered/non-owner pin resolves to null (404) AFTER `checkRead`; a bare id held by more than
     * one root throws [AmbiguousPageId] (the funnel's 409).
     */
    fun pageById(principal: Principal, id: PageId, root: RootName? = null): PagePayload?

    /** The page at [root]'s canonical-or-alias URL [path] (the route-parsed root segment, C3), or null (§A4 by-path rules). */
    fun pageByUrlPath(principal: Principal, root: RootName, path: TreePath): PagePayload?

    fun pageHtml(principal: Principal, id: PageId, root: RootName? = null): PageHtmlPayload?

    /**
     * The broken links + anchors ON the page [id] (Phase 5 `validate_links`, master §2.6): a checkRead-FIRST gated,
     * per-page view of the EXISTING whole-index [LinkChecker] report (filtered to this page). Null when [id] is
     * unknown (the checkRead gate fired first, so a denied caller cannot tell unknown-from-known). NOT a re-checker —
     * it aggregates the one render-time resolution model, exactly like the whole-tree gate. [root] is the C4 `?root=`
     * pin (see [pageById]).
     */
    fun validateLinks(principal: Principal, id: PageId, root: RootName? = null): LinkReport?

    /**
     * The page [id]'s metadata projection (Phase 5 `get_page_metadata`, master §2.6): id/path/url/content_hash/
     * commit/title/headings, all from the published snapshot (no disk read). checkRead-FIRST; null when [id] unknown
     * (the gate fired first). Headings are document order. Returns the domain [IndexedPage] (the route projects it).
     * [root] is the C4 `?root=` pin (see [pageById]).
     */
    fun pageMetadata(principal: Principal, id: PageId, root: RootName? = null): IndexedPage?

    fun search(principal: Principal, q: String?, limit: String?, offset: String?): SearchService.Outcome

    /** The memoized `/api/v1/tree` JSON for the current published snapshot. */
    fun tree(principal: Principal): String

    /** A read-only render of a submitted buffer against [root]'s view of the published snapshot (W3b). */
    fun preview(principal: Principal, root: RootName, sourcePath: TreePath, bytes: ByteArray): RenderedPage

    /** [root]'s history for [path] — history is per-root topology, so the page's root selects the provider. */
    fun history(principal: Principal, root: RootName, path: TreePath, limit: Int): List<Commit>

    fun diff(principal: Principal, root: RootName, from: String, to: String, path: TreePath): FileDiff

    /**
     * Whether [root]'s history layer is Git-backed (the `git_enabled` flag). Intentionally UNGATED — it is a server
     * CAPABILITY flag, not page existence, so it leaks nothing about the content tree. The [principal] is kept only
     * for port-signature uniformity with the gated reads above (the impl ignores it); do NOT read it as enforced.
     *
     * It is PER-ROOT, like the history reads it accompanies (C4): history is per-root topology, so a flag read off
     * MAIN would hide an extra root's real history whenever main is `off`, and advertise history an extra does not
     * have whenever only main is on. The `/history` + `/diff` responses pair the flag with commits from the PAGE's
     * provider, so both must come from the same root or the pair contradicts itself.
     */
    fun gitEnabled(principal: Principal, root: RootName): Boolean

    /**
     * A content-tree asset read under [root] (the route-parsed root segment, C3; read-gated - the gate fires
     * BEFORE membership, so existence never leaks). The asset route does an UPFRONT embedded-bundle lookup
     * (bundle-wins) BEFORE calling this, so a request that names a real `static/assets/` bundle file is served the
     * bundle and never reaches here - `assetRead` only decides the outcome for NON-bundle paths. The outcome still
     * SEPARATES "not a content asset" from "indexed but the on-disk file vanished"
     * ([AssetReadOutcome.NotContentAsset] vs [AssetReadOutcome.IndexedButMissing]) - both are genuine 404 misses
     * for a non-bundle path; the route maps each to a plain 404 (disk is source of truth).
     */
    fun assetRead(principal: Principal, root: RootName, path: TreePath): AssetReadOutcome

    /**
     * The current published snapshot. Read-GATED so a resolve's existence is not revealed to an anonymous caller
     * (the gate fires BEFORE the resolve). [resource] names the lookup for the audit-free read gate.
     *
     * **The ONE read that deliberately does NOT availability-gate**: it returns the WHOLE snapshot, carried sections
     * and all, so there is no single root to gate on - the CALLER owns the per-root gate. After C4 its only route
     * caller is the benign post-create outcome renderer, whose created page's root just passed the WRITE gate. Every
     * other route-side snapshot walk moved BEHIND a dedicated, gated facade method ([browseTarget], [permalink]), and
     * any future root-scoped resolution must follow that precedent rather than reach in here.
     */
    fun currentSnapshot(principal: Principal, resource: String): PageIndex

    /**
     * The `/{root}/{path}` 301 alias-redirect target under [root] (the route-parsed root segment, C3): the
     * page's current canonical URL, or its permalink for a collision loser - or null when there is no LIVE alias
     * OR the principal may not read the target. Returning null on a DENY (rather than throwing) is deliberate:
     * the `rootContentRoutes` shell-fallback arm is PUBLIC, so an unauthorized caller must fall through to the shell
     * EXACTLY like any unknown path - a 401 here would itself leak that an alias exists. A live canonical path
     * shadows an alias (§A4).
     *
     * An alias is NOT root-local: it resolves to a page ID, and that page may live in a DIFFERENT root than the one
     * the route named. When that root is unavailable the redirect STILL fires - to the target's permalink if it has
     * no canonical URL to offer - and the target surface answers the 503. A documented, accepted two-step: the point
     * is that a caller lands on an honest "this root is down", never on a soft 404 that says the page is gone.
     */
    fun resolveRootContentRedirect(principal: Principal, root: RootName, path: TreePath): String?

    /**
     * The `/browse/{root}/{file-path}` redirect target: the page's canonical URL, or its permalink for a collision
     * loser - null for a miss (the route's 404). A gated facade operation rather than a route-side [currentSnapshot]
     * walk, precisely so it CAN availability-gate: the pre-C4 route would have 302'd to a carried-forward page in a
     * root that answers 503 everywhere else.
     *
     * Genuinely root-parameterized: it resolves through `byPath` alone, with no alias and no id resolution, so it
     * gates on the route's root directly and needs no id_map fallback.
     */
    fun browseTarget(principal: Principal, root: RootName, path: TreePath): String?

    /**
     * The BARE `/p/{id}` — the permanent ID permalink's resolution (§A4's durability layer), as ONE facade operation so the
     * route keeps only its 302/shell/400 mapping.
     *
     * The truth table, after `checkRead`, and it is id_map-FIRST (Option B, C4): the DURABLE claimant decides the
     * owning root, never the snapshot. A root unavailable SINCE BOOT was never scanned this process, so its pages
     * are in no section - a snapshot-first resolution would answer 404 for every one of them.
     *
     * Exactly one live registered claimant: the root's availability is gated (an UNAVAILABLE one THROWS 503
     * `root_unavailable`), then a page still BOUND there but absent from the snapshot THROWS 503
     * `absence_unverified` - it is a page the last pass could not read, and C1 forbids reporting that as gone. A
     * page that IS in the snapshot is [PermalinkResolution.Found], or [PermalinkResolution.LoserNoUrl] for a
     * path-space collision loser whose permalink IS its only human URL. More than one claimant is
     * [PermalinkResolution.Ambiguous] (the route's 300).
     *
     * No live claimant - which includes a binding under a DETACHED root, since the resolver filters its candidate
     * list to REGISTERED roots (the boot WARN is a detached root's visibility) - falls through to TOMBSTONES:
     * exactly one retired claimant is [PermalinkResolution.Retired] (410, naming the last-known path), more than one
     * is Ambiguous, and none at all is [PermalinkResolution.Unknown] (404).
     *
     * There is deliberately NO `Unavailable` variant: the unavailable arms THROW like every other facade method, so
     * the 503 stays mapped in ONE place instead of being re-minted route-side.
     */
    fun permalink(principal: Principal, id: PageId): PermalinkResolution

    /**
     * `/p/{root}/{id}` - the ROOT-PINNED permalink resolution (C4): a COHERENT-STALE snapshot-first PRESENT read
     * (the pinned READ split, safe per the endpoint-status table), with a durable `id_map` consult only on a
     * snapshot miss. An unregistered/detached [root] resolves to [PermalinkResolution.Unknown] (404) AFTER
     * `checkRead`; a snapshot hit under an available root is [PermalinkResolution.Found]/[PermalinkResolution
     * .LoserNoUrl], a live-binding miss THROWS (503), and a tombstone under [root] is [PermalinkResolution.Retired]
     * (410) carrying `liveElsewhere` (other registered roots still holding the id) for the route's `rel="alternate"`
     * hints. It never emits [PermalinkResolution.Ambiguous] - the root is already named.
     */
    fun permalinkAt(principal: Principal, root: RootName, id: PageId): PermalinkResolution
}

/** The outcome of [ReadFacade.permalink] — the route maps these to 302 / SPA shell / 410 / 404. */
sealed interface PermalinkResolution {

    /** The page's current canonical URL — a 302 (never a 301: the target moves with the page). */
    data class Found(val url: String) : PermalinkResolution

    /** A path-space collision loser: no canonical URL exists, so the permalink itself IS its human URL (the shell). */
    data object LoserNoUrl : PermalinkResolution

    /**
     * The binding was RETIRED (C0 tombstone) - **410 Gone**, naming [lastKnownPath].
     *
     * The 410 is honest now: the id was deleted from this root. It is not permanent because the same `(root, path)`
     * may reclaim the id, and the id may already be live in [liveElsewhere]. The route therefore sends
     * `Cache-Control: no-store` and exposes live holders as `Link: rel="alternate"` hints.
     */
    data class Retired(
        val lastKnownPath: RootedPath,
        val liveElsewhere: List<RootName> = emptyList(),
    ) : PermalinkResolution

    /** No such page, here or in the persisted bindings — a 404. */
    data object Unknown : PermalinkResolution

    /**
     * The bare id refuses to resolve to one root (C5) — the permalink route answers **300 Multiple Choices** with one
     * `Link: rel="alternate"` per [candidates] entry (rank order). Either 2+ live roots hold it, or a live root holds
     * it alongside a registered tombstone (the fail-closed mixed case), or 2+ roots hold a tombstone for it. Only the
     * bare `permalink` produces it, never [permalinkAt] (which is already root-pinned). [hasRetiredCandidate] is a
     * DOMAIN flag only (no serialized key): true when a candidate holds a tombstone, so the 300 body can note it.
     */
    data class Ambiguous(
        val candidates: List<RootName>,
        val hasRetiredCandidate: Boolean = false,
    ) : PermalinkResolution
}

/**
 * The outcome of [ReadFacade.assetRead] for a NON-bundle path (a real bundle name is served upfront by the route's
 * bundle-wins check and never reaches here). It still SEPARATES content-tree MEMBERSHIP from the disk READ — so a
 * "this path isn't a content asset" never conflates with "an indexed asset's file vanished" — but with bundle-wins
 * both [NotContentAsset] and [IndexedButMissing] are genuine 404 misses (the route maps each to a plain 404; disk is
 * source of truth).
 */
sealed interface AssetReadOutcome {

    /** The path is not in the content tree's asset set (and is not a bundle name) — a 404 miss. */
    data object NotContentAsset : AssetReadOutcome

    /** The path is an indexed content asset; [bytes] are its current on-disk content. */
    data class Found(val bytes: ByteArray) : AssetReadOutcome {
        override fun equals(other: Any?): Boolean = this === other || (other is Found && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** The path is an indexed content asset but its on-disk file vanished — a 404 (disk is source of truth). */
    data object IndexedButMissing : AssetReadOutcome
}

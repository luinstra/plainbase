@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.root.Permalink
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AbsenceUnverified
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.AssetReadOutcome
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.LinkChecker
import com.plainbase.domain.service.LinkReport
import com.plainbase.domain.service.PageHtmlPayload
import com.plainbase.domain.service.PagePayload
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.PermalinkResolution
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ReadFacade
import com.plainbase.domain.service.RootLossClassifier
import com.plainbase.domain.service.RootStatus
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.RootedResource
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.UrlAliasRegistry
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The frameworks-side [ReadFacade] impl (A3): it holds the raw read services + the [PolicyService] as PRIVATE
 * deps, calls `checkRead` FIRST on every method (throwing [com.plainbase.domain.service.AccessDenied] BEFORE any
 * snapshot/membership work, so a read never leaks page existence to anonymous), then delegates. The route never
 * sees the raw services. The memoized tree JSON ([TreeJsonCache]) lives here (per-snapshot framework state, not a
 * mutator). The `AccessDenied` → 401/403 mapping is the route's; this impl just lets the throw propagate.
 *
 * **Availability is checked AFTER the gate, always (ADR-0011 D5).** Every read that resolves a root throws
 * [RootUnavailable] once `checkRead` has passed and the root turns out not to be serving; the route funnel maps it
 * to one 503. Authn precedes topology, so anonymous behavior is unchanged and availability cannot leak.
 *
 * **The gate is a status, so it cannot answer for a root that dies DURING the call** - and BOTH root-backed ports
 * can be the one that notices. Content reads are CLASSIFIED ([ContentRead.RootDown]); the two git reads
 * ([history]/[diff]) run through the shared [RootLossClassifier], which probes and MARKS. A root-gone condition
 * therefore never reaches the wire as an unclassified 500, and never leaves the root unmarked for the next read
 * to serve its carried-forward bytes with a 200.
 *
 * **One snapshot per request.** Each id-addressed read takes `indexBuilder.current` exactly ONCE into a local and
 * threads that immutable object through the root resolution, the availability check and the page resolve. A rebuild
 * landing between two reads can re-award a cross-root duplicate id to another root - possibly one whose section is
 * a carried-forward one from a root that is DOWN - so a facade that gated on read #1 and served from read #2 could
 * answer 200 with a downed root's stale bytes. Threading one object makes that unreachable rather than unlikely.
 */
class GuardedReadFacade(
    private val policy: PolicyService,
    private val pageService: PageService,
    private val searchService: SearchService,
    private val indexBuilder: IndexBuilder,
    private val aliasRegistry: UrlAliasRegistry,
    private val linkChecker: LinkChecker,
    private val registry: RootRegistry,
    private val availability: RootAvailability,
    /** The ONE owner of "who owns this id?" and "is that root serving?" - never a raw idMap/registry here. */
    private val resolver: PageRootResolver,
    /** The ONE owner of "is this absence a 404 or a 503?" (C1) - the same rule the write and index paths ask. */
    private val absence: AbsenceClassifier,
    private val stores: (RootName) -> ContentStore,
    private val histories: (RootName) -> HistoryProvider,
) : ReadFacade {

    private val treeJson = TreeJsonCache(indexBuilder, registry, availability)

    /** The shared exit boundary for a rooted BACKEND call - here, the two git reads (see [history]/[diff]). */
    private val rootLoss = RootLossClassifier(availability)

    override fun pageById(principal: Principal, id: PageId): PagePayload? {
        policy.checkRead(principal, id.value)
        val snapshot = gatedSnapshot(id) ?: return null
        return pageService.byId(snapshot, id)
    }

    override fun pageHtml(principal: Principal, id: PageId): PageHtmlPayload? {
        policy.checkRead(principal, id.value)
        val snapshot = gatedSnapshot(id) ?: return null
        return pageService.htmlById(snapshot, id)
    }

    override fun validateLinks(principal: Principal, id: PageId): LinkReport? {
        policy.checkRead(principal, id.value) // FIRST — existence never leaks (the A3 gate)
        val snapshot = gatedSnapshot(id) ?: return null
        val page = snapshot.byId[id] ?: return null // unknown page → 404 (the route maps null)
        // The EXISTING whole-index check, FILTERED to this page (D-A): LinkChecker.check is the one resolution model;
        // this aggregates it, it never re-resolves.
        //
        // DELIBERATE P2 deferral (addendum D-A): `check` sweeps the WHOLE tree then we filter to one page — O(tree) per
        // call, not O(page). Accepted for P2: `validate_links` is a low-frequency agent op on internal-docs-scale
        // corpora, and the cost is an impl detail BEHIND the frozen `ValidateLinksResponse`. A future per-page or
        // memoized-per-snapshot optimization is transparent — no contract change, no LinkChecker change (the addendum
        // forbids touching it here).
        // Rooted filter: identical relative paths in two roots must never merge their failures.
        return LinkReport(linkChecker.check(snapshot).broken.filter { it.page == RootedPath(page.root, page.path) })
    }

    override fun pageMetadata(principal: Principal, id: PageId): IndexedPage? {
        policy.checkRead(principal, id.value) // FIRST
        val snapshot = gatedSnapshot(id) ?: return null
        return snapshot.byId[id]
    }

    /**
     * The shared id-addressed read shape (ADR-0011 D5): resolve the id's OWNING root against ONE snapshot, throw if
     * that root is not serving, and otherwise hand the SAME snapshot back for the caller's own resolve. A null result
     * means "no registered root owns this id" - an unknown id, or a binding under a detached root - which is the
     * caller's existing 404.
     *
     * The `id_map` fallback inside `rootOf` is what this exists for: a root unavailable SINCE BOOT was never scanned,
     * so it has NO section, so a snapshot-only lookup misses and every one of these surfaces would answer **404** -
     * telling an agent the page is GONE when the truth is that the disk is unmounted. That is the exact lie D5
     * forbids, and only the persisted binding can tell the two apart. It costs nothing on the hot path: the DB read
     * fires only on a snapshot miss, i.e. on a read already bound for a 404.
     */
    private fun gatedSnapshot(id: PageId): PageIndex? {
        val snapshot = indexBuilder.current
        val root = resolver.rootOf(snapshot, id) ?: return null
        requireAvailable(root)
        // **The 404 lie, at its source (C1).** A page in the durable index and NOT in the published snapshot is a
        // page whose bytes the last pass could not produce - and every id-addressed read above then resolved it to
        // `null`, i.e. told the caller it does not exist. The root is up (the gate just said so) and the binding is
        // live, so the honest answer is "come back later" - and it is a DIFFERENT answer from `root_unavailable`,
        // because the disk is fine.
        absence.requireVerifiedAbsence(root, id, snapshot)
        return snapshot
    }

    override fun pageByUrlPath(principal: Principal, root: RootName, path: TreePath): PagePayload? {
        policy.checkRead(principal, RootedResource(root, path.value).audit)
        requireAvailable(root) // the ROUTE's root
        val snapshot = indexBuilder.current
        val payload = pageService.byUrlPath(snapshot, root, path)
        if (payload == null) {
            // A by-path MISS is not necessarily a miss: the path may be an ALIAS whose target page lives in a root
            // that was never scanned (so it is in no section, and `byUrlPath` cannot see it). Re-resolve the alias -
            // only on the miss, a read already bound for 404 - and if its target's root is registered but not
            // serving, answer 503 rather than "gone".
            aliasRegistry.find(RootedPath(root, path))?.let { requireResolvedTargetAvailable(snapshot, it) }
            return null
        }
        // An alias hit resolves through the GLOBAL byId, so the page it found may live in a DIFFERENT root than the
        // one the route named and the gate checked - and a vanished root's section is CARRIED FORWARD, so without
        // this the facade would serve its stale bytes with a 200. Gate the root we are actually about to serve.
        requireAvailable(payload.page.root)
        return payload
    }

    override fun search(principal: Principal, q: String?, limit: String?, offset: String?): SearchService.Outcome {
        policy.checkRead(principal, "search")
        return searchService.search(q = q, limit = limit, offset = offset)
    }

    override fun tree(principal: Principal): String {
        policy.checkRead(principal, "tree")
        return treeJson.current()
    }

    override fun preview(principal: Principal, root: RootName, sourcePath: TreePath, bytes: ByteArray): RenderedPage {
        policy.checkRead(principal, RootedResource(root, "preview").audit)
        requireAvailable(root)
        return indexBuilder.renderPreview(root, sourcePath, bytes)
    }

    // The gate below answers for a root that was ALREADY marked. These two then run a rooted GIT call, which is
    // the other backend a vanishing root can take down (`git -C <workTree>` on a gone work tree exits non-zero),
    // so the call itself goes through the same exit boundary every ContentStore read has: a root that disappears
    // DURING the call is probed, MARKED, and answered as one 503 - never a 500 off an unclassified
    // HistoryCommandException, and never left unmarked for the next read to serve carried bytes for. A genuine
    // git fault on a LIVE root still surfaces as itself (the two-sided rule; see [RootLossClassifier]).
    override fun history(principal: Principal, root: RootName, path: TreePath, limit: Int): List<Commit> {
        policy.checkRead(principal, RootedResource(root, path.value).audit)
        requireAvailable(root)
        return rootLoss.guarding(root, stores(root)) { histories(root).log(path, limit) }
    }

    override fun diff(principal: Principal, root: RootName, from: String, to: String, path: TreePath): FileDiff {
        policy.checkRead(principal, RootedResource(root, path.value).audit)
        requireAvailable(root)
        return rootLoss.guarding(root, stores(root)) { histories(root).diff(from, to, path) }
    }

    // Ungated: `enabled` is a server CAPABILITY flag, not page existence — it leaks nothing about the content tree, so
    // it needs no read check. The history/diff routes call this AFTER their own pageById checkRead has passed; a second
    // checkRead on the same authorized request was redundant. It answers for the PAGE's root (see the port doc): the
    // commits beside it on the wire come from that root's provider, so the flag has to come from the same one.
    override fun gitEnabled(principal: Principal, root: RootName): Boolean = histories(root).enabled

    override fun assetRead(principal: Principal, root: RootName, path: TreePath): AssetReadOutcome {
        policy.checkRead(principal, RootedResource(root, path.value).audit)
        requireAvailable(root)
        if (path !in indexBuilder.current.section(root).assets) return AssetReadOutcome.NotContentAsset
        // An indexed asset whose on-disk file vanished is IndexedButMissing (→ 404), NOT NotContentAsset: it must
        // never fall through to bundled static and unmask a shadowed name (disk is source of truth). But a 404 is
        // only honest for a file that is genuinely gone on a LIVE root - for a downed one it is the "drop your
        // citations" lie - so the read is CLASSIFIED, not a bare null.
        //
        // An ASSET has no `id_map` binding - the durable index tracks PAGES - so the classifier can only ever answer
        // ConfirmedAbsent for one, and the 404 stands. That is not a gap the classifier papers over: it is the honest
        // limit of what we durably know about an asset, said in one place instead of assumed in each.
        return when (val read = absence.read(stores(root), RootedPath(root, path))) {
            is ContentRead.Bytes -> AssetReadOutcome.Found(read.bytes)
            ContentRead.ConfirmedAbsent, ContentRead.AbsenceUnknown -> AssetReadOutcome.IndexedButMissing
            ContentRead.RootDown -> throw RootUnavailable(root, UnavailableCause.VANISHED)
        }
    }

    override fun pageBytes(principal: Principal, root: RootName, path: TreePath): ByteArray? {
        policy.checkRead(principal, RootedResource(root, path.value).audit)
        requireAvailable(root)
        val target = RootedPath(root, path)
        return when (val read = absence.read(stores(root), target)) {
            is ContentRead.Bytes -> read.bytes
            // The index still binds this page and its bytes are not there: 503, never the null the route turns into
            // a 404. This is the raw-markdown surface an AGENT reads (`read_file`), so it is the one place the lie
            // costs the most - a 404 tells it to drop the citation for a page that is coming back.
            ContentRead.AbsenceUnknown -> throw AbsenceUnverified(target)
            ContentRead.ConfirmedAbsent -> null
            ContentRead.RootDown -> throw RootUnavailable(root, UnavailableCause.VANISHED)
        }
    }

    override fun currentSnapshot(principal: Principal, resource: String): PageIndex {
        policy.checkRead(principal, resource)
        return indexBuilder.current
    }

    override fun browseTarget(principal: Principal, root: RootName, path: TreePath): String? {
        policy.checkRead(principal, RootedResource(root, path.value).audit)
        requireAvailable(root)
        val page = indexBuilder.current.byPath[RootedPath(root, path)] ?: return null
        return page.url ?: page.permalink
    }

    override fun permalink(principal: Principal, id: PageId): PermalinkResolution {
        policy.checkRead(principal, id.value)
        val snapshot = indexBuilder.current
        // No LIVE binding: the id may still be TOMBSTONED, and a retired id is reserved forever - so it answers
        // 410 Gone naming its last-known path, never the 404 that tells an agent the citation was never real.
        val root = resolver.rootOf(snapshot, id)
            ?: return resolver.retirementOf(id)?.let(PermalinkResolution::Retired) ?: PermalinkResolution.Unknown
        requireAvailable(root)
        // A live binding with no page in the snapshot is LIMBO, not Unknown (C1): the permalink is the ONE promise
        // §A4 makes to an agent, and answering 404 for a page we still bind - because a mount came up empty - is the
        // failure mode the whole absence-authority redesign exists to end.
        absence.requireVerifiedAbsence(root, id, snapshot)
        val page = snapshot.byId[id] ?: return PermalinkResolution.Unknown
        return page.url?.let(PermalinkResolution::Found) ?: PermalinkResolution.LoserNoUrl
    }

    override fun resolveDocsRedirect(principal: Principal, root: RootName, path: TreePath): String? {
        // Deny → null (NOT a throw): the docsRoutes shell-fallback arm is public, so an unauthorized caller must
        // fall through to the shell exactly like any unknown path (a 401 here would leak that an alias exists).
        try {
            policy.checkRead(principal, RootedResource(root, path.value).audit)
        } catch (_: AccessDenied) {
            return null
        }
        requireAvailable(root)
        val snapshot = indexBuilder.current
        val rooted = RootedPath(root, path)
        val id = aliasRegistry.find(rooted)
            .takeIf { rooted !in snapshot.byUrlPath } // live canonical wins (§A4)
            ?: return null
        val target = snapshot.byId[id]
            // The alias target is in no section. Usually that means a stale binding (the shadow sweep has not run) and
            // today's null → SPA shell is right. But it is ALSO what a root unavailable SINCE BOOT looks like: never
            // scanned, so no section, so no canonical URL to redirect TO. Emit the id-derived PERMALINK there, and the
            // permalink route answers the 503 - so the promised "302, then an honest 503" holds in BOTH arms, rather
            // than only in the one that happened to have a section.
            ?: return id.takeIf { unavailableRoot(snapshot, it) }?.let { Permalink.of(root, it) }
        // A cross-root alias's TARGET may live in an unavailable root even though the route's root is fine. The 302
        // still fires and the target surface answers 503 - an accepted, documented two-step.
        return target.url ?: target.permalink
    }

    /** True iff [id] resolves to a REGISTERED root that is not serving (a detached or unknown id answers false). */
    private fun unavailableRoot(snapshot: PageIndex, id: PageId): Boolean =
        resolver.rootOf(snapshot, id)?.let { resolver.statusOf(it, availability.current()) == RootStatus.UNAVAILABLE } == true

    /** Throw 503 when [id]'s resolved root is registered but not serving; a detached/unknown id is left to the 404 arm. */
    private fun requireResolvedTargetAvailable(snapshot: PageIndex, id: PageId) {
        val root = resolver.rootOf(snapshot, id) ?: return
        requireAvailable(root)
    }

    /**
     * The ONE availability gate. Called only AFTER `checkRead` has passed - never as a route precheck, never
     * pre-authz - so an anonymous caller's answer is byte-identical to today's on every surface.
     */
    private fun requireAvailable(root: RootName) {
        // ONE read of the holder: `statusOf` and the cause lookup must not be able to disagree.
        val snapshot = availability.current()
        when (resolver.statusOf(root, snapshot)) {
            RootStatus.AVAILABLE -> Unit
            // A detached root is never IN the holder (it was never probed), so its cause is registry-derived.
            RootStatus.DETACHED -> throw RootUnavailable(root, UnavailableCause.DETACHED)
            RootStatus.UNAVAILABLE -> throw RootUnavailable(root, snapshot.unavailable.getValue(root).cause)
        }
    }
}

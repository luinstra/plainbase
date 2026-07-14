@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.service

import com.plainbase.domain.content.ContentFile
import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.HistoryCommandException
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.principal.ManageGrant
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.NoRetirements
import com.plainbase.domain.repository.NoTopology
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.PreviousUrl
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.ObjectManifestProvider
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.ObservationId
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.root.Witness
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Chunk 5's index pass (caching decision §C4; N root sources since multi-root C2, ADR-0011):
 * scan → frontmatter → identity → URLs → render metadata → one immutable [PageIndex] of per-root
 * sections, published atomically. The full scan runs at startup and on rescan (the chunk-6 admin
 * route calls [rebuild]); watcher-driven incremental updates are Phase 2. Since C4 the runtime wires
 * EVERY registered root as a source, in registry (D7) order.
 *
 * **A root that is not there is SKIPPED, never treated as empty (ADR-0011 D5).** Each pass probes each
 * source's store: an already-Unavailable root is skipped outright (the status is sticky until restart), and
 * a root whose probe fails now is MARKED Unavailable and skipped. A skipped root's LAST-GOOD section is
 * carried into the new snapshot verbatim, because the publication listeners ARE the deletion pipelines - a
 * dropped section would purge that root's search rows AND its `page_checkpoint` rows (durable state) in one
 * publish, i.e. a mass delete caused by an unplugged disk. A never-scanned root simply contributes no
 * section, and since C0 the listeners' authority set ([PublicationListener.published]'s `retired`) is what keeps
 * its rows safe there - a set that is EMPTY unless a proof put something in it.
 *
 * **What is classified is the COMPLETED SCAN, never the precondition** ([scanIfAvailable]). The entry probe
 * says the root was there when the walk STARTED, and a root can vanish in between - a directory iteration whose
 * tree disappears mid-walk can return SHORT (or empty) without throwing anything. So the root is re-probed at
 * HANDOFF, and a scan whose root is gone by then is skipped and carried like any other loss. (Since C0 a short
 * scan can no longer authorize a deletion whatever it claims to be - nothing can, without a proof - but it can
 * still poison the WITNESS map with a tree that was falling apart as we read it, so the probe stays.) A scan that
 * THROWS is classified the same way; what a LIVE-root failure costs is THAT root's pass, never the whole
 * rebuild - one unreadable subdirectory in one extra root must not take the other roots (or, at boot, the
 * server) down with it. It is not marked unavailable either: a chmod is fixed in place, and sticky
 * unavailability would prescribe a restart nobody needs. It skips, carries, WARNs loudly, and the next pass
 * retries it.
 *
 * **A scan proves the pages it READ. It does not prove the pages it did not read are DELETED (C0).** That is a
 * theorem, not a bug: an empty mount point, a deliberately emptied root, a partially-restored tree and a decoy
 * tree produce IDENTICAL observations, and four rounds were spent computing an answer to a question that has
 * none. So this pass no longer INFERS a deletion from ANYTHING - not from a zero-page scan, not from a corpus
 * this process once saw (a snapshot from T cashed at T+n; with ext4 inode reuse it handed a REAPED corpus full
 * authority), not from a page "located" in another root (which cannot tell a MOVE from a COPY), and above all
 * not from `drafts.isNotEmpty()`, under which ONE decoy file bought authority over a thousand rows. The whole
 * admission apparatus is DELETED, and nothing replaces it, because there is nothing honest to replace it with.
 * ([corpusSeen] survives with its teeth pulled: it decides 503-vs-404 and it decides nothing else.)
 *
 * What a pass publishes instead is what it actually SAW: a [Witness] per rooted path it READ, carrying the id
 * that file turned out to hold. Absence from that map is NOT a licence - it means "we did not read this",
 * which is exactly as consistent with an unplugged disk as with a delete. The ONLY licence to delete is an
 * [AbsenceProof].
 *
 * **C2 mints the first one ([mintEpochProofs]), and it is what makes an ordinary delete converge again.** An
 * [ObservationEpoch] that has watched a tree WITHOUT A GAP since it read a page - fully covered, identity-stable,
 * scanned end to end - and now does not find it has evidence rather than an inference, and evidence is the only
 * thing that has ever been allowed to delete anything here. Every other absence still ends in LIMBO ([RootLimbo]):
 * carried, served as "come back later", never destroyed, self-healing the moment the page is witnessed again. The
 * residue is honest and bounded - a delete storm past the watcher's queue bound is observationally identical to an
 * unmount, so the epoch refuses to guess and its tail waits for `reconcile` (C5) or for git (C4).
 *
 * The sinks CONSUME that authority and never re-derive it - the checkpoint replace, the search sync, the search
 * generation swap, the id_map supersessions ([Supersession]) and the dirty-page reconcile. They are handed the
 * bindings a proof actually RETIRED, which in C0 is the empty set.
 *
 * **One pass:** each file's bytes are read exactly once ([ContentStore.read]), each page's
 * frontmatter values are parsed exactly once ([FrontmatterParser], over the already-read bytes —
 * render only re-detects the block boundary, never the values), and each page is rendered exactly
 * once ([MarkdownRenderer.render]). The same in-hand bytes also yield the page's verbatim
 * `markdown` and its content hash ([CitationFactory]), so the read path never touches disk for
 * pages. The parse runs up front because URL construction needs every page's `slug` BEFORE any
 * page renders — rendered links embed other pages' canonical URLs — so render happens against a
 * URL-complete skeleton snapshot built first, each root's pages against that root's [PageIndex.view].
 *
 * **Cross-root identity (C2):** identities resolve GLOBALLY across all sources in registry (D7)
 * rank order, under the [PageIdentityService] cross-root winner policy; a binding's liveness AND its
 * supersedability are classified by the shared [BindingVisibility] rule over the scanned/registered
 * root sets (D16). A pass therefore NEVER supersedes a binding under a root it did not scan - not
 * even on rank. That is the same no-delete rule the carried-forward section implements, applied to
 * identity: a skipped root's page is still IN the snapshot, so taking its id would both destroy the
 * durable binding an outage gave us no authority to touch (D-C4-10) and put a DUPLICATE id in the
 * snapshot (a rebuild crash, and a wedged rescan/scheduler with it). The scanned claimant reassigns
 * instead, and the rank contest waits for a pass that can see both roots. The two D17 execution
 * invariants live as comments at their enforcement points in [rebuild]/[resolveIdentities].
 *
 * **Safe publication, no `@Volatile`:** the new snapshot is built entirely off to the side and
 * published with a single [AtomicReference.store]; [current] readers always observe a complete,
 * internally consistent, deeply immutable [PageIndex] — old or new, never torn — and stay
 * lock-free. [rebuild] itself is `@Synchronized` (rescans are rare): two concurrent rebuilds
 * could otherwise publish out of order — the earlier-scanned one finishing later would regress
 * [current] to a stale world (a classic lost update).
 *
 * **Move aliases (§A4; down-time moves closed by the Phase-2 §B3 checkpoint):** a known id whose
 * canonical URL path changed since the previously published snapshot leaves its old (root, path)
 * behind as a `url_alias` row; the registry maps rooted paths straight to page ids, so chains
 * collapse on write (one hop after any number of moves). On the FIRST rebuild after startup the
 * previous paths come from the persisted [PageCheckpointRepository] instead of the (empty) holder,
 * so a materialized page moved while the server was down still records its alias. `redirect_from`
 * frontmatter registers through the same construction, in the declaring page's root namespace; a
 * live canonical path always shadows an alias (dropped, with a recorded `redirect_conflict` issue).
 *
 * Identity binding mirrors `AdoptionPass` RECORD semantics over the in-hand bytes (zero content
 * writes): id_map rows plus issues, sources in rank order and pages in path order so duplicate
 * resolution is deterministic.
 *
 * **Publication listeners (§B4, the Phase-2/3 seam):** after the snapshot publishes, [rebuild] —
 * still inside its serialized section — synchronously invokes every registered
 * [PublicationListener], so listeners (checkpoint replace, search sync) can never interleave or
 * run against a superseded snapshot. A throwing listener is caught and logged here: the publish
 * has already happened and stands, the remaining listeners still run, and nothing propagates to
 * any [rebuild] caller (a failed search sync is repaired for free by the next sync's engine-truth
 * diff). Phase 3: the save path calls [rebuild], so a saved page is searchable before the save
 * returns — this listener chain IS that hook; nothing else to build.
 */
class IndexBuilder(
    sources: List<Source>,
    private val frontmatterParser: FrontmatterParser,
    private val rendererFactory: (PageIndexView) -> MarkdownRenderer,
    private val identity: PageIdentityService,
    private val patcher: FrontmatterPatcher,
    private val idMap: IdMapRepository,
    private val aliasRegistry: UrlAliasRegistry,
    private val checkpoint: PageCheckpointRepository,
    private val citations: CitationFactory,
    rootRank: (RootName) -> Int,
    private val registeredRoots: Set<RootName>,
    private val listeners: List<PublicationListener> = emptyList(),
    private val searchIndexer: SearchIndexer? = null,
    /** The availability HOLDER, not a captured map: this builder both READS it (skip a sticky-Unavailable root)
     *  and WRITES it (mark a root whose probe just failed). Defaulted so single-root constructions stay terse. */
    private val availability: RootAvailability = RootAvailability(kotlin.time.Clock.System),
    /**
     * The proof-apply transaction (C0) - the ONE deleter, and the durable freshness token it checks against.
     * Defaulted to a repository that holds no proofs and grants no tokens so single-root constructions stay
     * terse; the runtime wires the real one.
     */
    private val retirements: RetirementRepository = NoRetirements,
    /** The DERIVED limbo set, republished every pass. Never stored: a stored flag is another snapshot used later. */
    private val limbo: RootLimbo = RootLimbo(),
    /**
     * The observation epochs (C2) - the ONE proof source this pass mints from, and the reason a legitimate delete
     * converges again. Defaulted to an epoch over [NoRetirements], which cannot mint a token anything will honor, so
     * the many single-root constructions stay terse AND stay at the C0 floor: they observe, and they reap nothing.
     */
    private val epochs: ObservationEpoch = ObservationEpoch(NoRetirements, RootConvergence()),
    /**
     * The C3 binding latch - the OTHER proof source, and the one that decides whether a bucket LIST is evidence about
     * OUR corpus or about somebody else's. Defaulted to a latch with no durable table behind it, which records
     * nothing, promotes nothing and therefore grants nothing: the C0 floor, again as a value rather than as a policy.
     */
    private val bindings: BindingLatch = BindingLatch(NoTopology),
) {

    /** One root's inputs: its topology entry, its content tree, and its history. */
    data class Source(
        val root: Root,
        val store: ContentStore,
        val history: HistoryProvider,
        /**
         * The C3 proof source for an OBJECT-backed root: the latest complete bucket LIST. Null for a local root,
         * which has no bucket to list and earns its authority the other way (an observation epoch) - and null for
         * every construction that wires no manifest at all, which therefore mints no `OBJECT_LIST` proof.
         */
        val manifests: ObjectManifestProvider? = null,
    )

    /**
     * Notified with each newly published snapshot — synchronously, inside the serialized rebuild (§B4).
     *
     * [retired] is the AUTHORITY SET, and it is now a set of PAGES rather than of roots: exactly the bindings an
     * [AbsenceProof] just RETIRED in the proof-apply transaction, and therefore the only rows a listener may
     * DELETE. It is the applied RESULT, never the raw proofs - a proof that failed its freshness check
     * authorizes nothing here either, so a listener cannot delete on the strength of a licence that was revoked
     * before it could be cashed.
     *
     * Everything else a listener holds SURVIVES, and that is the point. "Absent from the snapshot" is not
     * evidence of anything: a root skipped this pass, a root never scanned since boot, a root whose scan came
     * back as a partial view, a DETACHED root, a page on a failed submount and a page in a decoy tree all look
     * identical from here. Handing the listener the POSITIVE, proof-backed set means the compiler - not a
     * convention, and not the next listener's memory - is what keeps an unplugged disk from performing a mass
     * delete. Since C2 the set is non-empty for exactly one reason: an unbroken observation epoch watched a page
     * it had read stop existing.
     */
    fun interface PublicationListener {
        fun published(snapshot: PageIndex, retired: Set<PageId>)
    }

    init {
        val names = sources.map { it.root.name }
        require(names.size == names.toSet().size) {
            "duplicate source root(s): ${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.joinToString(", ")}"
        }
        // A root the rank source does not know comes back -1, which would otherwise silently sort
        // it FIRST - i.e. silently seat an unknown root as the top-rank winner.
        sources.forEach { source ->
            require(rootRank(source.root.name) >= 0) { "source root '${source.root.name}' is unknown to the registry rank" }
        }
    }

    // Sorted by the shared D7 rank (the SAME lambda PageIdentityService receives, wired once):
    // scan-and-resolve order is correctness-critical - the registry-order winner must always be
    // claimed and bound first - so it is enforced by construction, never trusted from the caller.
    private val sources: List<Source> = sources.sortedBy { rootRank(it.root.name) }

    private val sourcesByRoot: Map<RootName, Source> = this.sources.associateBy { it.root.name }

    /** The shared root-loss rule (probe → mark), over the SAME holder this builder reads and writes. */
    private val rootLoss = RootLossClassifier(availability)

    /** The ONE 404-vs-503 rule (C1), over the SAME durable index this pass binds into. Never re-derived here. */
    private val absence = AbsenceClassifier(idMap)

    /**
     * The roots whose corpus THIS PROCESS has actually seen on disk - and, since C0, a **SERVING HINT with ZERO
     * delete authority.** Read [publishLimbo] for what it is now allowed to decide, which is exactly one thing:
     * whether an empty scan of a rooted tree answers 503 or 404.
     *
     * It used to be the corpus-loss tripwire's exoneration, and as a DELETE oracle it was unrepairable: a
     * snapshot taken at T and cashed at T+n, which under ext4 inode reuse hands a REAPED corpus full authority
     * (ledger A2). That power is gone - deletion needs an [AbsenceProof] now, and this is not one.
     *
     * What it still does is tell "the operator emptied this root under a running server" from "the volume went
     * away", and NOTHING can do that (they are the same observation - the theorem again). The difference is the
     * COST of being wrong, and that is the whole reason it survives: wrong here means a page reads 404 instead
     * of 503, which is bad; wrong as a delete oracle meant the corpus. Without it, deleting the last page of a
     * root would mark the WHOLE root unavailable and 503 it, sticky until restart - a product-breaking answer to
     * an ordinary edit.
     *
     * **C1 was going to delete this, and did not - deliberately.** The per-ROW limbo 503 ([AbsenceClassifier]) is
     * strictly finer-grained and it does subsume the READ half of this hint: a limbo page answers 503
     * `absence_unverified` whether or not its root is marked. What it does NOT subsume is the WRITE half. An empty
     * mount point passes `available()` (it is a readable, searchable directory), so an unmarked root would accept a
     * CREATE and lay a partial skeleton of the operator's tree into the mount point - the exact resurrection the
     * whole D5 design exists to prevent, arriving through the one surface a per-page rule cannot see. So the
     * root-granular BROKEN-VIEW mark stays for the case it was always about (a root that scanned to zero pages
     * having never shown this process a corpus - a failed mount at boot), and the per-row rule handles everything
     * finer than that, including the partial restore this mark never could.
     *
     * Touched only from inside the `@Synchronized` [rebuild], so it needs no atomics.
     */
    private val corpusSeen = mutableSetOf<RootName>()

    /**
     * What a pass PUBLISHES - swapped as ONE value, because they are one fact. Reading the snapshot from one
     * field and its authority from another could pair a fresh snapshot with a stale authority, i.e. hand a
     * consumer permission to delete rows on the strength of a pass that never ran.
     *
     * [witnessed] is what the pass actually SAW: every rooted path it READ, and the id that file carried
     * (null = it carries none). Absence from this map is NOT a licence.
     *
     * [proofs] is the ONLY licence to delete - since C2, one per root whose observation epoch witnessed a page and
     * then witnessed it go. [retired] is what the proof-apply transaction actually acted on (a proof whose token was
     * revoked between the mint and the apply authorizes NOTHING), and it is what the sinks consume.
     *
     * [observedAt] stamps each root's durable freshness token, so a proof minted from this observation can be
     * checked against a token that a restart, a break or a rebind may since have revoked.
     */
    private data class Published(
        val snapshot: PageIndex,
        val witnessed: Map<RootedPath, Witness>,
        val proofs: List<AbsenceProof>,
        val retired: Set<PageId>,
        val observedAt: Map<RootName, ObservationId>,
    )

    private val holder = AtomicReference(
        Published(PageIndex.EMPTY, witnessed = emptyMap(), proofs = emptyList(), retired = emptySet(), observedAt = emptyMap()),
    )

    /** The published snapshot — always complete and consistent ([PageIndex.EMPTY] before the first build). */
    val current: PageIndex get() = holder.load().snapshot

    /** Runs the full pass and atomically publishes (and returns) the new snapshot (serialized — see class doc). */
    @Synchronized
    fun rebuild(): PageIndex {
        val previous = holder.load().snapshot
        // §B3 checkpoint-as-previous: the first rebuild after startup (holder still the EMPTY
        // sentinel) compares against the persisted checkpoint of the last published snapshot, so a
        // move performed while the server was down still records its alias. Every later rebuild
        // compares against the previous published snapshot, exactly as before.
        val previousUrlPaths =
            if (previous === PageIndex.EMPTY) {
                checkpoint.load()
            } else {
                previous.pages.associate { it.id to PreviousUrl(it.root, it.urlPath) }
            }

        // D17 execution invariant (b): scan ALL sources before the FIRST resolve. Only then is the WITNESS map
        // complete - under interleaved scan+resolve a binding in a not-yet-scanned later root would be
        // misclassified by the D16 visibility rule.
        //
        // D5: probe first, and skip what is not there. `scans` holds only the roots this pass actually walked.
        // Nothing here is an ADMISSION any more - there is no tripwire to pass and no authority to be granted,
        // because a scan is no longer evidence of a deletion under any circumstances. What comes out of it is a
        // WITNESS map: the pages we READ, and the ids they carried.
        val observed = sources.mapNotNull { scanIfAvailable(it) }
        // What the pass READ, and the id each file carried. This is the FULL witness - the latch is entitled to see
        // every page we looked at, because "is this the tree our rows describe?" is exactly what it is deciding.
        val seen: Map<RootedPath, Witness> = observed.flatMap { scan ->
            scan.drafts.map { draft ->
                RootedPath(scan.root, draft.file.path) to Witness(patcher.readIdValue(draft.bytes)?.let(PageId::of))
            }
        }.toMap()

        // **The only licence to delete** - and there are now TWO sources that mint it here: EPOCH (C2, local roots)
        // and OBJECT_LIST (C3, a complete bucket LIST under a TRUSTED binding). GIT (C4), OPERATOR (C5) and
        // API_DELETE arrive later; until they do, an absence outside those two is still never believed. Minted BEFORE
        // the binds below, against the id_map as it stands NOW: a proof is about the durable binding a page HAD when
        // the pass observed it gone, and this pass is about to rewrite that table.
        val proofs: List<AbsenceProof> = refuted(mintEpochProofs(observed) + mintObjectListProofs(seen), seen)
        val retired: Set<PageId> = retirements.applyProofs(proofs).map { it.id }.toSet()

        // **A suspect tree may not DISPLACE the incumbents it does not carry** (C3). The latch guards the ABSENCE
        // half; this is the door beside it. On a root whose binding is UNRESOLVED - a swapped bucket, a first sight -
        // a decoy file at an at-risk path carrying a DIFFERENT id needs no absence proof to destroy anything:
        // displacement is POSITIVE evidence ("we read the file, and it no longer holds that id"), so the bind would
        // tombstone the incumbent and turn a protected page into a 410 before the binding is trusted at all.
        //
        // So those drafts are dropped from this pass entirely: not bound, not published, not witnessed. Their
        // incumbents fall to LIMBO and read 503 - "we do not know what we are looking at" - which is the honest
        // answer and the self-healing one. (Minted proofs above are unaffected: they were computed from the FULL
        // witness, which is what the latch needs to decide whether the tree is ours in the first place.)
        val suspect = suspectDrafts(seen)
        val scans = if (suspect.isEmpty()) {
            observed
        } else {
            observed.map { scan -> scan.copy(drafts = scan.drafts.filterNot { RootedPath(scan.root, it.file.path) in suspect }) }
        }
        val witnessed: Map<RootedPath, Witness> = if (suspect.isEmpty()) seen else seen - suspect
        val scannedRoots: Set<RootName> = scans.filter { it.complete }.map { it.root }.toSet()
        // The SERVING hint (see [corpusSeen]) - a complete walk that came back holding pages. It decides 503-vs-404
        // for a later empty scan of this root, and it decides NOTHING ELSE. It is not delete authority, and there
        // is no longer any code path by which it could become some.
        scans.filter { it.complete && it.drafts.isNotEmpty() }.forEach { corpusSeen += it.root }

        // The scan's own issue rows are persisted only once the scan that produced them COMPLETED. Recording
        // them as they were found would leave rows behind from a pass that never happened: a scan that dies
        // half-way is SKIPPED and its last-good section carried, so its half-walked path/URL collisions describe
        // a tree nobody indexed. The identity issues below ride the resolve, which only ever sees full scans.
        scans.forEach { scan -> scan.issues.forEach(idMap::record) }

        val identities = resolveIdentities(scans, witnessed, scannedRoots, retired)

        // Build ALL provisional sections, then render each root's pages against ITS view of the
        // ONE URL-complete skeleton (identity and URLs final; render fields filled below).
        val provisionalSections = scans.map { scan ->
            RootSection(
                root = scan.root,
                pages = scan.drafts.map { draft ->
                    provisionalPage(scan, draft, identities.getValue(RootedPath(scan.root, draft.file.path)))
                },
                folders = scan.folders,
                assets = scan.assets,
            )
        }
        val provisional = PageIndex(provisionalSections)
        val scanned = scans.zip(provisionalSections) { scan, section ->
            val renderer = rendererFactory(provisional.view(scan.root))
            section.copy(
                pages = section.pages.zip(scan.drafts) { page, draft ->
                    val rendered = renderer.render(page.path, draft.bytes)
                    page.copy(
                        title = draft.frontmatter.scalar("title")
                            ?: rendered.headings.firstOrNull { it.level == 1 }?.text
                            ?: page.path.stem,
                        html = rendered.html,
                        headings = rendered.headings.toList(),
                        links = rendered.links.toList(),
                        // The §B4 search sections, captured from the SAME single render — no extra read,
                        // no second parse (see the IndexedPage.sections doc for the accepted memory cost).
                        sections = rendered.sections.toList(),
                    )
                },
            )
        }

        // Carry each SKIPPED root's last-good section forward, so no listener sees a deletion (a never-scanned
        // root has no previous section and simply contributes none - `section` is total). In registry rank
        // order, like the sources themselves, so the snapshot is deterministic either way.
        val scannedIds = scanned.flatMap { section -> section.pages.map { it.id } }.toSet()
        val sections = sources.mapNotNull { source ->
            val root = source.root.name
            scanned.firstOrNull { it.root == root }
                ?: previous.sections.firstOrNull { it.root == root }?.let { carryForward(it, scannedIds) }
        }

        val snapshot = PageIndex(sections)
        recordAliases(previousUrlPaths, snapshot)
        holder.store(
            Published(
                snapshot = snapshot,
                witnessed = witnessed,
                proofs = proofs,
                retired = retired,
                observedAt = retirements.observations(),
            ),
        )
        publishLimbo(witnessed, scannedRoots)
        logger.info {
            val breakdown = if (snapshot.sections.size > 1) {
                snapshot.sections.joinToString(prefix = " [", postfix = "]") { "${it.root}: ${it.pages.size} page(s)" }
            } else {
                ""
            }
            "indexed ${snapshot.pages.size} page(s), ${snapshot.sections.sumOf { it.assets.size }} asset(s), " +
                "${snapshot.sections.sumOf { it.folders.size }} folder(s); " +
                "${snapshot.pages.count { it.urlPath == null }} excluded from path space" + breakdown
        }
        notifyPublished(snapshot, retired)
        return snapshot
    }

    /**
     * **A MOVE is not an ABSENCE**, and this is the ONE place that says so - for every scan-derived source at once,
     * including the ones that have not been written yet.
     *
     * A proof covers a binding whose PATH this pass did not see, and a RENAMED page's old path is exactly that. So
     * every source here would mint a proof over a page that is sitting in front of us under a different name, and the
     * apply transaction would tombstone the id its frontmatter still plainly carries - handing the file back a FRESH
     * id and turning `/p/{oldId}` into a permanent 410. One `git mv` would kill every inbound link and every agent
     * citation to that page. (That is not hypothetical: it shipped in C2 and [OnlineMoveIdentityTest] is the proof.)
     *
     * The rule the design already states, and the reason it did not bite here: *a witness is positive evidence of
     * PRESENCE, and proving a page we are looking at to be absent is not a proof, it is a contradiction*
     * ([com.plainbase.domain.root.BindingLatch]). Both sources enforce it - by **PATH** ([ObservationEpoch.scanned],
     * `BindingLatch.proven`) - and a rename is precisely the event that changes the path. **IDENTITY is the only key
     * that survives one**, so the refutation has to be asked in the currency the evidence actually arrives in: not
     * *"did we read that path?"* but *"did we read that ID, anywhere?"*
     *
     * Two consequences worth being explicit about, because both are correct rather than tolerated:
     *  - **An UNMATERIALIZED page still splits on a move.** Its id lives nowhere but `id_map`, so its bytes cannot
     *    testify to which page they are, and a move is genuinely indistinguishable from a delete-and-recreate. The
     *    honest residue - and a SOFT retirement, so the old id answers 410 rather than 404.
     *  - **A COPY that carries a live id refutes the absence too**, and hands the id to the copy. A move and a copy
     *    produce IDENTICAL observations - the design says so twice - so the two cannot be told apart here, and this is
     *    the rank policy's problem, never the admission oracle's. It is also what an OFFLINE move has always done
     *    (no epoch at boot, so no proof, so the id travels): this makes ONLINE agree with OFFLINE rather than
     *    inventing a third behaviour.
     *
     * [witnessed] is the FULL witness - every page this pass READ, before the suspect-tree filter - because the
     * question is only ever *"are we looking at it?"*, and a proof we decline to mint costs a row in limbo (503,
     * self-healing) while one we mint wrongly costs a permalink forever.
     *
     * **`API_DELETE` is deliberately NOT filtered here** and never passes through this function: it is minted by the
     * write pipeline, not by a scan. Every proof this sees is an INFERENCE FROM NOT-SEEING, which reading the id
     * elsewhere refutes. `API_DELETE`'s authority is *"we CAUSED this"*, which no observation can refute.
     */
    private fun refuted(proofs: List<AbsenceProof>, witnessed: Map<RootedPath, Witness>): List<AbsenceProof> {
        val present = witnessed.values.mapNotNullTo(mutableSetOf()) { it.observedId }
        if (present.isEmpty()) return proofs
        return proofs.mapNotNull { proof ->
            val gone = proof.covers.filterNotTo(mutableSetOf()) { it.id in present }
            if (gone.size != proof.covers.size) {
                logger.info {
                    "refusing ${proof.covers.size - gone.size} of root '${proof.root}''s ${proof.source} proof(s): this pass READ " +
                        "those ids elsewhere in the tree, so the pages MOVED - a page we are looking at is not a page that is gone"
                }
            }
            proof.takeIf { gone.isNotEmpty() }?.copy(covers = gone)
        }
    }

    /**
     * **The EPOCH proof source (C2): the chunk that makes an online delete converge again.**
     *
     * A page is proven gone when an epoch that WITNESSED it - an unbroken observation of an identity-stable tree,
     * fully watched, scanned end to end - looks again and does not find it. Nothing here trusts a delete EVENT:
     * the events are what make us LOOK, and [ObservationEpoch] decides whether looking is worth anything.
     *
     * The four ways this can fail, and all of them fail CLOSED - into limbo, never into a delete:
     *  - **an object root gets no epoch at all.** Its watch is a POLLER over a mirror, so "the page is not in the
     *    mirror" says nothing about the bucket, and a rebound or wrong bucket would drain the mirror and read as a
     *    corpus-wide delete. Its authority is a complete `OBJECT_LIST` under the C3 binding latch, which is the
     *    thing that can actually see what the bucket holds.
     *  - **a root this pass could not scan** (unavailable, vanished, a live-root failure) BREAKS its epoch. That is
     *    the availability mark and the scan failure, arriving as the same fact: we stopped watching.
     *  - **an INCOMPLETE scan** breaks it too. A view with holes in it is not an observation of a tree, and a page
     *    "missing" from a walk that could not see the whole tree is not missing at all.
     *  - **partial watch coverage, a break, or a restart** are the epoch's own business ([ObservationEpoch]).
     *
     * [durable] is read HERE, before the binds: the proof is about the row the page HAD, and `resolveIdentities`
     * is about to rewrite that table. ([publishLimbo] re-reads it afterwards on purpose - it is answering the
     * opposite question, about the rows that are left.)
     */
    private fun mintEpochProofs(scans: List<SourceScan>): List<AbsenceProof> {
        val durable = idMap.bindings().groupBy({ it.path.root }, { BindingRef(it.path.path, it.id) })
        return sources
            .filter { it.root.backend is RootBackend.Local }
            .mapNotNull { source ->
                val root = source.root.name
                // SKIPPED and SHORT are the same fact here - we did not see this tree - and they break the epoch for
                // the same reason. The skip is where the availability mark and the scan failure both arrive.
                val scan = scans.firstOrNull { it.root == root }?.takeIf { it.complete }
                if (scan == null) {
                    epochs.broke(root, BreakCause.SCAN_FAILED)
                    return@mapNotNull null
                }
                epochs.scanned(
                    root = root,
                    witnessed = scan.drafts.mapTo(mutableSetOf()) { it.file.path },
                    // The walk saw these and the read could not produce them: neither witnessed nor absent, and the
                    // difference between a page that is GONE and a page we merely could not read this pass.
                    unread = scan.unread,
                    durable = durable[root].orEmpty().toSet(),
                )
            }
    }

    /**
     * **The OBJECT_LIST proof source (C3): the chunk that lets an object root converge a delete without ever letting
     * it believe the wrong bucket.**
     *
     * An object root gets no observation epoch - its watch is a POLLER over a mirror, and "the page is not in the
     * mirror" says nothing about the bucket. What it gets instead is the bucket itself: a COMPLETE LIST is positive
     * proof of absence, *of the bucket it listed*. Whether that bucket is OURS is the [BindingLatch]'s question, and
     * every guard lives there rather than here, so this is only the plumbing: hand the latch the manifest and the
     * witness, take back the bindings it says are provably gone, and stamp them with the root's current token.
     *
     * A root with no manifest (never listed, or its last LIST failed) mints nothing at all. That is the fail-closed
     * arm, and it is the common one: a store that has listed nothing knows nothing.
     */
    private fun mintObjectListProofs(witnessed: Map<RootedPath, Witness>): List<AbsenceProof> =
        sources.filter { it.root.backend is RootBackend.Object }.mapNotNull { source ->
            val root = source.root.name
            val manifest = source.manifests?.latestManifest() ?: return@mapNotNull null
            val gone = bindings.proven(root, manifest, witnessed)
            if (gone.isEmpty()) {
                null
            } else {
                AbsenceProof(root = root, source = ProofSource.OBJECT_LIST, observationId = retirements.observation(root), covers = gone)
            }
        }

    /**
     * The drafts this pass must NOT bind: a file at an at-risk path, under a root whose binding is still UNRESOLVED,
     * carrying an id that is NOT the incumbent's (see the call site for why binding it would destroy a permalink).
     *
     * The two arms it deliberately leaves alone:
     *  - a file carrying **NO** id displaces nothing. Its identity comes from the `id_map` row itself (pre-materialized
     *    identity is path-keyed), so the incumbent id is what it binds to - which is also why an UNMATERIALIZED page
     *    must not be quarantined here: it would take a legitimate install's own pages away from it while its binding
     *    waits for the operator reconcile it can never earn by witness.
     *  - a file carrying the incumbent's OWN id is the page witnessing itself. That is the promotion path, not a threat.
     */
    private fun suspectDrafts(witnessed: Map<RootedPath, Witness>): Set<RootedPath> =
        sources.filter { it.root.backend is RootBackend.Object }
            .flatMapTo(mutableSetOf()) { source ->
                bindings.protects(source.root.name)
                    .map { RootedPath(source.root.name, it.path) to it.id }
                    .filter { (path, id) -> witnessed[path]?.observedId?.let { it != id } == true }
                    .map { (path, _) -> path }
            }

    /**
     * `limbo = durableRows - witnessed - retired`, recomputed from scratch every pass and never stored.
     *
     * A row that lands here is not a deletion and not a page - it is an OPEN QUESTION, and it stays open until
     * the page is witnessed again (it drops out of the next derivation, with no code running - that is what
     * "self-healing" has to mean to be worth anything) or a proof settles it.
     *
     * It also drives the ONE thing the deleted corpus-loss tripwire did that was never about deleting: a root
     * whose scan came back EMPTY while its durable rows say it holds content is a BROKEN VIEW, and its pages must
     * not be SERVED as 404 - the answer that tells an agent to drop its citations. [UnavailableCause
     * .CORPUS_MISSING] is what turns them into an honest 503, and it is preserved here verbatim.
     *
     * **It is a SERVING HINT and carries ZERO delete authority** (design §2.1: `available()` is demoted to a
     * hint - a write fail-fast and a health signal, never an input to anything that deletes). That is the whole
     * difference from the tripwire it replaces: the old rule used this same observation to hand out and withhold
     * DELETE AUTHORITY, which is a question an empty directory can never answer. Deletion now needs a proof, so
     * being wrong here costs a 503 instead of a corpus.
     *
     * C1 makes the READ 503 per-ROW off the durable binding ([AbsenceClassifier]) - so every page here already
     * answers `absence_unverified` without this mark. The mark survives for the WRITE side, which no per-page rule
     * can reach: an empty mount point is a perfectly writable directory, and an unmarked root would let a create
     * lay a skeleton corpus into it. See [corpusSeen].
     */
    private fun publishLimbo(witnessed: Map<RootedPath, Witness>, scannedRoots: Set<RootName>) {
        val stranded = idMap.bindings()
            .filterNot { it.path in witnessed }
            .groupBy({ it.path.root }, { BindingRef(it.path.path, it.id) })
            .mapValues { (_, refs) -> refs.toSet() }
        limbo.publish(stranded)
        if (stranded.isEmpty()) return

        logger.warn {
            "LIMBO: " + stranded.entries.joinToString { (root, refs) -> "'$root': ${refs.size} row(s)" } +
                " - durable rows whose pages this pass did not witness and no proof covers. NOTHING is deleted for " +
                "them; they self-heal the moment the pages are read again."
        }
        val witnessedRoots = witnessed.keys.mapTo(mutableSetOf()) { it.root }
        for ((root, refs) in stranded) {
            // A root we WATCHED hold pages and then watched go empty reads as 404, not 503 - the [corpusSeen]
            // hint, and the ONLY thing it is still allowed to decide. Deleting the last page of a root is an
            // ordinary edit, and answering it with a sticky 503 over the whole root would be a worse lie than the
            // one this mark exists to prevent.
            if (root !in scannedRoots || root in witnessedRoots || root in corpusSeen) continue
            availability.markUnavailable(root, UnavailableCause.CORPUS_MISSING)
            val where = sourcesByRoot[root]?.root?.localPath ?: "its backing store"
            logger.error {
                "root '$root' scanned to ZERO pages while holding ${refs.size} durable binding(s): treating it as a BROKEN " +
                    "VIEW, not a delete. NOTHING is deleted for it (nothing could be - a scan is not a proof), its " +
                    "last-good pages are carried forward, and it serves 503 rather than the 404 that would tell an agent " +
                    "its citations were never real. Check the mount at $where."
            }
        }
    }

    /**
     * The MANAGE-gated rescan entry (A3): the admin `rescan` route reaches the full pass ONLY through this thin
     * wrapper, which requires a [ManageGrant] minted by `PolicyService.checkManage()`. The no-arg [rebuild] stays
     * for the MANY internal callers (the watcher loop, startup reconcile, the create route's post-write index, the
     * asset facade's post-write rebuild) — they are not a manage admin action, so they keep the ungated path.
     * "Gain a grant param, keep the logic": the gated overload is the new surface, the body is the shared no-arg.
     */
    fun rebuild(@Suppress("UNUSED_PARAMETER") grant: ManageGrant): PageIndex = rebuild()

    /**
     * Search-only full rebuild (the S8 reindex path): reads the CURRENT published snapshot AND
     * rebuilds the search engine from it, both inside the SAME monitor [rebuild]/[notifyPublished]
     * use. So a concurrent watcher rebuild either fully precedes this (the reindex sees its
     * snapshot) or fully follows it (its own [SearchIndexer.sync] runs afterward) — it can never
     * interleave to roll the engine back to a stale generation (the debate-caught regression a
     * naive read-`current`-then-`rebuild` would reopen). This is NOT a page rescan: no scan, no
     * checkpoint listener re-fire — just a clean generation swap of the engine over the snapshot
     * already published. Both the reindex endpoint and the `plainbase reindex` CLI route through
     * here. Returns the page count rebuilt into the engine (the §C4 reindex-response figure).
     *
     * It swaps the engine under the SAME delete authority the pass that published this snapshot ran under, which
     * is why the two travel together in [Published]. Without it the swap is a mass delete for any root the pass
     * skipped: an unavailable root has no section in the snapshot, so the engine would re-derive the corpus
     * WITHOUT it and drop its rows - the D5 lie, performed by a reindex nobody meant as a deletion.
     */
    @Synchronized
    fun rebuildSearchIndex(): Int {
        val indexer = requireNotNull(searchIndexer) { "rebuildSearchIndex() needs a SearchIndexer; none was wired into this IndexBuilder" }
        val published = holder.load()
        indexer.rebuild(published.snapshot, published.retired)
        return published.snapshot.pages.size
    }

    /**
     * The MANAGE-gated reindex entry (A3): the admin `reindex` route + the `plainbase reindex` CLI reach the
     * engine generation-swap ONLY through this thin wrapper, which requires a [ManageGrant]. The no-arg
     * [rebuildSearchIndex] stays the internal surface (same gain-a-param-keep-the-logic shape as [rebuild]).
     */
    fun rebuildSearchIndex(@Suppress("UNUSED_PARAMETER") grant: ManageGrant): Int = rebuildSearchIndex()

    /**
     * Targeted single-page reindex (PB-WRITE-1 §B1 fix C): re-reads + re-renders ONLY the page at [target],
     * publishes a snapshot identical to the current one except for that page (its own root's section
     * rebuilt, every other section riding through untouched), and upserts that ONE page into search via
     * [SearchIndexer.syncPage]. O(changed-page) END-TO-END — render O(1), search O(1) (single-page
     * upsert, NOT the corpus-wide [SearchIndexer.sync] diff), checkpoint O(0) (skipped). Full [rebuild]
     * stays the startup/admin/watcher path. Shares the rebuild monitor, so a watcher rebuild never
     * interleaves. Bytes and history come from the target root's source.
     *
     * **The target is a [RootedPath], NOT a page id, and that is the whole point (ADR-0011 D17).** A page id
     * does not durably name a location: a rebuild can re-award it to another root the moment that root claims
     * the same frontmatter id (the cross-root duplicate-id rank contest). This method runs DOWNSTREAM of a CAS
     * that has already put bytes on ONE root's disk, and it takes a FRESH snapshot — so resolving the id here
     * would let a rebuild landing in that window send the reindex at a DIFFERENT root's file, splitting disk
     * truth from index truth. Taking the location the bytes actually went to makes that unreachable rather than
     * unlikely: the caller pins the write target, and this can never re-derive another owner from it.
     *
     * The caller (`WritePipeline.write`) has ALREADY rejected any id/slug/redirect_from change (the
     * edit-classification guard), so this page's identity, urlPath, and aliases are unchanged — only
     * its bytes-derived fields (markdown, contentHash, html, headings, links, sections, title) are
     * recomputed. So this does NOT call [notifyPublished] (which would fire the O(corpus) checkpoint
     * replace) and does NOT call [recordAliases]: there is nothing checkpoint- or alias-relevant to
     * change. A genuine rename never reaches here — it is a deferred §H operation through full [rebuild].
     *
     * Rendered against the CURRENT published snapshot's per-root view (URL-complete: every OTHER page's
     * canonical URL is final), so this page's outbound links resolve exactly as in a full rebuild.
     *
     * **Cross-page render coherence — a documented invariant, not a tracked feature.** Re-rendering
     * one page is correct iff a page's HTML/headings/links/sections are a pure function of its OWN
     * content (plus the unchanged URL-complete view). That holds today: the renderer embeds other
     * pages' URLs but never their content (no backlinks, no transclusion, no server-rendered
     * child-lists), and folder landing pages are client-rendered (ADR-0003). TRIPWIRE for whoever
     * later adds backlinks / transclusion / "pages that mention this one": that feature breaks the
     * pure-function assumption and must either re-render dependents or route through full [rebuild].
     *
     * THROWS [IllegalStateException] if [target] is absent from the snapshot or its file is unreadable on the
     * SAVE path: the CAS just wrote those bytes, so a missing page is a real invariant violation, never a silent
     * success. `WritePipeline.reconcileDirtyPages` tolerates a vanished page at its
     * OWN call site, never here.
     */
    @Synchronized
    fun reindex(target: RootedPath): PageIndex {
        val published = holder.load()
        val previous = published.snapshot
        val page = previous.byPath[target]
            ?: error("reindex($target): page not in the published snapshot — a save-path invariant violation")
        val source = sourcesByRoot[target.root]
            ?: error("reindex($target): no source for root '${target.root}' - the snapshot outran this builder's wiring")
        // The cheap belt for the ALREADY-MARKED case - a save into a vanished root must not half-run. The
        // facade's gate normally fires first, so this is an internal-path guard, not the 503 surface: when it
        // does fire mid-save, WritePipeline's post-write catch absorbs it into the honest WrittenButUnindexed
        // (the bytes ARE on disk, the dirty mark IS retained). A STATUS cannot answer for the UNMARKED window,
        // though, which is what the two classified calls below are for.
        if (!availability.current().isAvailable(target.root)) throw RootUnavailable(target.root, UnavailableCause.VANISHED)
        val bytes = when (val read = absence.read(source.store, target)) {
            is ContentRead.Bytes -> read.bytes
            // The store has already MARKED on its way out; this throw is only the carrier. Without it the read's
            // null became an error() the pipeline's blanket catch absorbed - the right wire answer, but the root
            // was never marked, so every subsequent READ kept serving its carried content.
            ContentRead.RootDown -> throw RootUnavailable(target.root, UnavailableCause.VANISHED)
            // BOTH absences are an invariant violation HERE and nowhere else: the CAS wrote these bytes moments ago,
            // on this path, in this root. Whichever way the index reads it, the file is not supposed to be missing -
            // so this stays a loud error() that the pipeline's post-write catch turns into WrittenButUnindexed (the
            // bytes ARE on disk, the dirty mark IS retained). This is not the C1 read-classification surface; it is a
            // save-path invariant, and softening it would hide a lost write behind a retry.
            ContentRead.ConfirmedAbsent, ContentRead.AbsenceUnknown ->
                error("reindex($target): ${target.path.value} unreadable just after a CAS write")
        }
        val parsed = frontmatterParser.parse(bytes)
        val rendered = rendererFactory(previous.view(target.root)).render(target.path, bytes)
        // One genuinely O(1) last-commit lookup for just this page (D-3, reversed by re-review P2-1): a
        // BOUNDED `git log --max-count=1 -- path`, NEVER `lastCommits` — which has no cap and buffers the
        // page's FULL history before parsing, so for a heavily-edited page every save/reconcile would read
        // the whole history (unbounded; can time out / null the commit). `log(path, 1)` shares `rebuild`'s
        // first-parent attribution, so the citation SHA stays consistent between the two paths.
        //
        // The git read takes the SAME exit boundary every other rooted backend call does ([RootLossClassifier]):
        // `git -C <workTree>` on a gone work tree raises a HistoryCommandException, which - unclassified - would
        // be absorbed by the pipeline with the root left AVAILABLE. A live-root git fault still rethrows as the
        // honest 500.
        val commit = rootLoss.guarding(target.root, source.store) { source.history.log(target.path, limit = 1).firstOrNull()?.sha }
        val reindexed = page.copy(
            frontmatter = parsed,
            markdown = String(bytes, Charsets.UTF_8),
            contentHash = citations.contentHash(bytes),
            commit = commit,
            title = parsed.scalar("title") ?: rendered.headings.firstOrNull { it.level == 1 }?.text ?: target.path.stem,
            html = rendered.html,
            headings = rendered.headings.toList(),
            links = rendered.links.toList(),
            sections = rendered.sections.toList(),
        )
        // Matched by PATH, like the lookup: the page occupying the written location is the one to replace, and
        // an id match here would re-introduce the very re-derivation the RootedPath target exists to prevent.
        val snapshot = PageIndex(
            previous.sections.map { section ->
                if (section.root == target.root) {
                    section.copy(pages = section.pages.map { if (it.path == target.path) reindexed else it })
                } else {
                    section
                }
            },
        )
        // The authority set rides through unchanged: this republishes ONE page of an already-scanned root, so it
        // says nothing new about which roots a pass has walked - and a search reindex racing it must still be
        // told what the last full pass knew.
        holder.store(published.copy(snapshot = snapshot))
        logger.info {
            "reindexed page ${reindexed.id.value} (${target.path.value} in '${target.root}'); ${snapshot.pages.size} page(s) published"
        }
        searchIndexer?.syncPage(reindexed) // genuine O(1) single-page upsert — NOT sync(snapshot), NOT notifyPublished
        return snapshot
    }

    /**
     * Renders a SUBMITTED Markdown buffer for the (private, non-contractual W3b) preview pane: PB-SLUG-1
     * heading ids + PB-LINK-1 link rewriting via the SAME [rendererFactory] every index render uses (§3
     * single-renderer rule — preview NEVER constructs its own renderer). Link resolution is against
     * [root]'s view of the CURRENT published snapshot [current] (so `[[other page]]` / relative links
     * resolve as a reader would see them); [sourcePath] is the buffer's notional location for
     * relative-href resolution (the editor's page path, or a synthetic root path when previewing a
     * not-yet-saved buffer). READ-ONLY: nothing is read from disk, nothing is published, no snapshot
     * swap — a pure function of [bytes] + the live view.
     */
    fun renderPreview(root: RootName, sourcePath: TreePath, bytes: ByteArray): RenderedPage =
        rendererFactory(current.view(root)).render(sourcePath, bytes)

    /**
     * A skipped root's last-good section, minus any page whose id a SCANNED root now holds — the last thing
     * standing between a duplicate id and [PageIndex]'s `byId` uniqueness check, which would otherwise throw
     * and take every future rebuild (rescan, watcher, scheduler) down with it until a restart.
     *
     * The D16 non-supersedable rule upstream is what makes this near-unreachable: a scanned page can no longer
     * TAKE an id from an unscanned root, so it cannot end up sharing one. What is left is durable state that
     * says otherwise - an `id_map` missing the binding that the carried page's id needs (a row an older build
     * superseded before the rule existed). Identity state we cannot trust must not be able to wedge the index,
     * so the SCANNED side wins (it is live disk truth) and the carried page steps out of the SNAPSHOT only.
     * Nothing durable is touched: no proof covers its rows, so no listener may delete them, its `id_map` binding
     * stands, and the page returns with the root - by which point a full pass can settle the contest properly.
     * Every unaffected page of the downed root rides through untouched.
     */
    private fun carryForward(section: RootSection, scannedIds: Set<PageId>): RootSection {
        val kept = section.pages.filterNot { it.id in scannedIds }
        if (kept.size == section.pages.size) return section
        logger.warn {
            "carrying unavailable root '${section.root}' forward WITHOUT ${section.pages.size - kept.size} page(s) whose id a " +
                "scanned root now holds - its durable rows are untouched, and the pages return when the root does"
        }
        return section.copy(pages = kept)
    }

    /** §B4 listener exception policy: contain and log — the publish stands, the remaining listeners still run. */
    private fun notifyPublished(snapshot: PageIndex, retired: Set<PageId>) {
        listeners.forEach { listener ->
            try {
                listener.published(snapshot, retired)
            } catch (e: Exception) {
                // Exception, not Throwable — narrower than §B4's literal "nothing propagates" so a JVM Error (OOM/SOE) still fails loudly.
                logger.error(e) { "publication listener failed; the published snapshot stands" }
            }
        }
    }

    /** One page's in-flight state: read once, frontmatter parsed once, bytes kept for the single render. */
    private class Draft(
        val file: ContentFile,
        val bytes: ByteArray,
        val frontmatter: Frontmatter,
    )

    /**
     * One source's COMPLETED scan: drafts in path order, URLs assigned, last-commits batched — and the
     * path/URL-collision [issues] it raised, BUFFERED rather than persisted as they were found, so an
     * abandoned (root-loss) scan leaves no rows describing a tree it never finished walking. The caller
     * records them once the scan has come back whole.
     */
    private data class SourceScan(
        val root: RootName,
        val drafts: List<Draft>,
        val folders: List<ContentFolder>,
        val assets: Set<TreePath>,
        val urls: CanonicalUrlBuilder.Result,
        val commits: Map<TreePath, Commit>,
        val issues: List<IdentityIssue>,
        /** Did the backend see the WHOLE tree ([ScanResult.complete])? Only a complete walk gets delete authority. */
        val complete: Boolean,
        /**
         * **The paths the WALK enumerated and the READ could not produce bytes for** - and the reason [complete] is
         * not the whole story about what this pass knows.
         *
         * A scan is a walk followed by a read of each thing it walked, and [complete] describes only the WALK. So a
         * page that lost a race between the two (an `rm`, a `git checkout` of another branch, a sync tool's
         * delete-then-write) drops out of [drafts] while the scan still, correctly, calls itself complete - and it is
         * then MISSING FROM THE WITNESS MAP of a scan that saw the whole tree. The epoch mints its proof from exactly
         * that difference, so without this set the page we merely FAILED TO READ reads as a page that is GONE, and
         * `AbsenceUnknown` - the carrier for *"the bytes are not there and we cannot prove they are gone"* - becomes
         * the one thing this design forbids it to become.
         *
         * These paths are therefore neither WITNESSED nor ABSENT. They are the third answer, and they go to LIMBO.
         */
        val unread: Set<TreePath>,
    )

    private class Identity(
        val id: PageId,
        val materialized: Boolean,
    )

    /**
     * [scan]s ONE source unless its root is not there - in which case it is MARKED (if the probe is what
     * discovered it), SKIPPED, and its last-good section carried forward by the caller. Null means skipped.
     *
     * **The scan is classified where it is HANDED OVER, not where it is started.** The entry probe below is a
     * cheap fail-fast, and it is all it is: what the pass grants delete authority to is the SourceScan that
     * came back, so that is what gets re-probed. A tree that vanishes DURING the walk does not have to throw -
     * a directory stream can simply run out of entries - and a short scan of a live root should not be mistaken
     * for a WITNESS of the pages it failed to reach. Probing the artifact instead of the precondition makes that
     * structural rather than lucky.
     *
     * The classifier's carrier set is DERIVED from what `scan(source)` actually COLLABORATES with, not from
     * the NIO ladder - because this is a COMPOSITE rooted operation, not a store call:
     *  - `store.scan()` / `store.readClassified()` -> `IOException` (total over the store's NIO surface, once
     *    the directory-stream normalization has run);
     *  - `store.readClassified()`'s `RootDown` arm -> a `RootUnavailable` we throw ourselves;
     *  - `history.lastCommits()` -> every git call is `git -C <workTree>`, so a gone work tree exits non-zero
     *    and raises a [HistoryCommandException];
     *  - `idMap.record`/`bind` (a DB in DATA_DIR, a different tree) and the pure parser/URL builder -> nothing
     *    a vanished root can do. A throw from those is a GENUINE fault and takes the live-root arm.
     *
     * So it is those three and NOT `catch (Exception)`: a widened catch would swallow a programming error into
     * a skipped root, and the whole point of [skipOnLiveFailure]'s WARN is that a live root's failure stays
     * visible instead of being laundered into "the disk is gone".
     */
    private fun scanIfAvailable(source: Source): SourceScan? {
        val root = source.root.name
        if (!availability.current().isAvailable(root)) {
            logger.warn { "root '$root' is unavailable; skipping its scan and carrying its last-good section forward" }
            return null
        }
        if (rootLoss.markIfGone(root, source.store)) return skipAndCarry(root, "its backing tree is not traversable")
        val scan = try {
            scan(source)
        } catch (_: RootUnavailable) {
            // Only ever raised by the mark-then-throw rule, so the root is ALREADY marked and there is nothing
            // left to decide: skip and carry, unconditionally. Re-probing here would be worse than redundant -
            // a root that vanished and whose path has since REAPPEARED would probe PASS, and the pass would
            // then trust a scan the store already refused to answer for.
            logger.warn { "root '$root' vanished mid-scan; skipping it and carrying its last-good section forward" }
            return null
        } catch (e: IOException) {
            return classifyScanFailure(source, e)
        } catch (e: HistoryCommandException) {
            return classifyScanFailure(source, e)
        }
        // The handoff probe: the tree that handed this scan back must still be the tree we started on.
        if (rootLoss.markIfGone(root, source.store)) {
            return skipAndCarry(root, "it vanished while being scanned, so the tree it handed back is not a corpus")
        }
        return scan
    }

    /**
     * The rebuild's arm of the shared [RootLossClassifier] rule: a failure whose re-probe FAILS means the root
     * vanished mid-operation - the same hazard class, since a half-scanned section is a partial mass-delete - so
     * mark, skip and carry. A failure whose re-probe still PASSES is NOT a disappearance (a parser bug, a corrupt
     * repo, an unknown git flag, a `chmod 000` subdirectory) and takes [skipOnLiveFailure]. A request-serving
     * surface wants the classifier's `guarding` (mark and 503); a rebuild wants to keep going over the roots
     * that ARE there, which is this.
     */
    private fun classifyScanFailure(source: Source, failure: Exception): SourceScan? {
        val root = source.root.name
        if (rootLoss.markIfGone(root, source.store)) {
            return skipAndCarry(root, "it vanished while being scanned (${failure.message})")
        }
        return skipOnLiveFailure(source, failure)
    }

    /**
     * A LIVE root whose scan failed: fail THAT root's pass, not the whole rebuild. The old rethrow escaped the
     * per-root loop, so one unreadable subdirectory in one extra root failed every root's pass - and at boot,
     * where `serve()` calls [rebuild] uncaught, it killed the server outright with a stack trace instead of
     * serving the roots that were perfectly fine. The root is deliberately NOT marked unavailable: it is THERE,
     * a permission or a corrupt repo is fixed in place, and sticky-until-restart would prescribe a restart
     * nobody needs. So it keeps its last-good section (nothing is deleted for it - nothing proved anything gone),
     * it keeps serving, and the next pass retries it. The WARN carries the DIRECTORY, because that is the datum
     * an operator acts on.
     */
    private fun skipOnLiveFailure(source: Source, failure: Exception): SourceScan? {
        val where = source.root.localPath?.let { " at $it" }.orEmpty()
        logger.warn(failure) {
            "root '${source.root.name}'$where is still there but its scan FAILED (${failure.message}); skipping it and " +
                "carrying its last-good section forward - NOTHING is deleted for it, the other roots still index, and the " +
                "next pass retries it"
        }
        return null
    }

    /** The loss is already published; this is the skip. Marking is what stops the carried section from being SERVED as live. */
    private fun skipAndCarry(root: RootName, detail: String): SourceScan? {
        logger.warn {
            "root '$root' is no longer available ($detail); skipping its scan and carrying its last-good section " +
                "forward - NOTHING is deleted for it, and it will serve 503 until it is restored and the server restarted"
        }
        return null
    }

    /** Scans ONE source end-to-end (files, frontmatter, per-root URLs, batched last-commits). */
    private fun scan(source: Source): SourceScan {
        val root = source.root.name
        val scan = source.store.scan()

        // The walk saw these; the read could not produce their bytes. NOT witnessed, and NOT absent - see
        // [SourceScan.unread]. Collected here because this is the only place that knows the difference.
        val unread = mutableSetOf<TreePath>()
        val drafts = scan.files
            .filter { it.path.name.endsWith(".md") }
            .sortedBy { it.path.value }
            .mapNotNull { file ->
                // CLASSIFIED, not `checkNotNull`: a plain null read cannot tell a page that vanished mid-scan from
                // the whole ROOT going away (which must mark + skip + carry). The old checkNotNull raised an
                // IllegalStateException that walked straight past the classifier above, leaving the root AVAILABLE
                // and its carried section being served - the D5 lie.
                //
                // A page that vanished between the walk and the read is simply NOT WITNESSED (C1): it drops out of
                // this pass's drafts, so it is in no snapshot, and - if the durable index still binds it - it lands
                // in LIMBO, which reads 503 rather than 404 until the page is seen again or a proof settles it. It
                // used to `error()`, which killed the whole rebuild (and, at boot, the server) over one file losing
                // a race with an ordinary `rm` - taking every OTHER root's pass down with it.
                val bytes = when (val read = absence.read(source.store, RootedPath(root, file.path))) {
                    is ContentRead.Bytes -> read.bytes
                    ContentRead.RootDown -> throw RootUnavailable(root, UnavailableCause.VANISHED)
                    ContentRead.AbsenceUnknown -> {
                        // ...and it is recorded as UNREAD, which is what actually keeps that last promise. Dropping the
                        // draft is only half of it: a page missing from the witness map of a COMPLETE scan is precisely
                        // what the epoch mints an absence proof from, so without this line the log below is a lie and
                        // the row is reaped (tombstone, checkpoint, and the dirty_page row that is the interrupted
                        // save's only recovery record). An unknown is not a fact.
                        unread += file.path
                        logger.warn {
                            "page ${file.path.value} in '$root' vanished between the walk and the read; it is NOT witnessed " +
                                "this pass and its durable row goes to LIMBO - nothing is deleted for it"
                        }
                        return@mapNotNull null
                    }
                    ContentRead.ConfirmedAbsent -> {
                        logger.warn { "page ${file.path.value} in '$root' vanished between the walk and the read; it was never indexed" }
                        return@mapNotNull null
                    }
                }
                Draft(file, bytes, frontmatterParser.parse(bytes))
            }
        val assets = scan.files.filterNot { it.path.name.endsWith(".md") }.map { it.path }.toSet()

        // Per-root URL construction: the builder is pure and per-tree, so per-root URL uniqueness
        // falls out of calling it once per source (§A4 holds per root, not across roots).
        val urls = CanonicalUrlBuilder.build(
            root = root,
            pages = drafts.map { CanonicalUrlBuilder.PageInput(it.file.path, it.file.rawName, it.frontmatter.scalar("slug")) },
            folders = scan.folders,
        )

        // ONE batched last-commit read per source (fix-C corollary): never one query per page.
        // NoOp → empty map → every commit null off Git (the frozen-golden invariant). The map is
        // keyed by the same TreePath the draft carries; an uncommitted page is simply absent (→ null).
        // It is also the LAST thing that can raise root loss here, which is why the issues below are
        // handed BACK rather than recorded: nothing this scan found is persisted until all of it is in hand.
        val commits = source.history.lastCommits(drafts.map { it.file.path })
        return SourceScan(
            root = root,
            drafts = drafts,
            folders = scan.folders,
            assets = assets,
            urls = urls,
            commits = commits,
            issues = scan.issues.map { it.toIdentityIssue(root) } + urls.issues,
            complete = scan.complete,
            unread = unread,
        )
    }

    /** The URL-complete, render-empty skeleton page for one draft. */
    private fun provisionalPage(scan: SourceScan, draft: Draft, identityOf: Identity): IndexedPage {
        val assignment = scan.urls.byPage.getValue(draft.file.path)
        return IndexedPage(
            id = identityOf.id,
            root = scan.root,
            path = draft.file.path,
            slug = assignment.slug,
            urlPath = assignment.urlPath,
            title = draft.file.path.stem,
            frontmatter = draft.frontmatter,
            materialized = identityOf.materialized,
            // Captured from the one read, alongside everything else the page serves: the
            // payload a request answers with is coherent BY CONSTRUCTION (see IndexedPage doc).
            markdown = String(draft.bytes, Charsets.UTF_8),
            contentHash = citations.contentHash(draft.bytes),
            commit = scan.commits[draft.file.path]?.sha,
            html = "",
            headings = emptyList(),
            links = emptyList(),
            sections = emptyList(),
        )
    }

    /**
     * §5.2 identity over the in-hand bytes — the same precedence/duplicate seam as `AdoptionPass`
     * RECORD, run ONCE globally across all sources in rank-then-path order so the registry-order
     * winner is always claimed first.
     *
     * **RESOLVE THE WHOLE CORPUS, THEN BIND IT** - the `AdoptionPass` two-phase split (D19), for the same
     * reason and now literally the same seam. Binding INLINE, as this used to, made the D16/D17 loser issue
     * UNRECORDABLE for one specific loser: a page whose identity lives in `id_map` ONLY (no frontmatter id of
     * its own). The winner's key-complete bind DELETES that row on its way through, so when the loser's own
     * draft came up for resolution its `mappedId` read back null - and a page with no frontmatter id and no
     * mapping is not a duplicate, it is a VIRGIN PAGE. It minted a fresh id, silently, and its `/p/{id}`
     * permalink moved to another page in another root with no `CrossRootDuplicateId` issue recorded anywhere.
     * A durable permalink reassignment with no record is precisely the outcome D16/D17's loser-behalf issue
     * recording exists to make impossible.
     *
     * Resolving first fixes it at the root: every draft's `mappedId` is read against the id_map as it stood
     * BEFORE this pass touched it, so the beaten owner still sees the contested id, `PageIdentityService`
     * reaches its owner check on the id_map arm (the arm its doc says an inline-binding pass can never reach),
     * and the loser reassigns WITH its issue. The binds then replay the resolved plan in the same rank-then-path
     * order, so the winner's key-complete bind still lands before the loser's row is rewritten.
     */
    private fun resolveIdentities(
        scans: List<SourceScan>,
        witnessed: Map<RootedPath, Witness>,
        scannedRoots: Set<RootName>,
        retired: Set<PageId>,
    ): Map<RootedPath, Identity> {
        // The ONE supersession rule, built once and handed to BOTH the resolver below and every bind it
        // produces - so the plan the pass makes and the writes the repository will accept cannot disagree.
        val supersession = Supersession(witnessed = witnessed.keys, scannedRoots = scannedRoots, registeredRoots = registeredRoots)
        val claimed = HashMap<PageId, RootedPath>()
        val resolved = LinkedHashMap<RootedPath, PageIdentityService.Assignment>() // rank-then-path = the bind order
        for (scan in scans) {
            for (draft in scan.drafts) {
                val path = RootedPath(scan.root, draft.file.path)
                val assignment = identity.resolve(
                    path = path,
                    rawFrontmatterId = patcher.readIdValue(draft.bytes),
                    // Read against the PRE-PASS id_map (nothing has been bound yet), which is what lets a beaten
                    // id_map-only owner still see the contested id and lose it with an issue rather than silently.
                    mappedId = idMap.find(path)?.id,
                    // Within-run claims first, then id_map bindings classified by the shared D16 rule - and then
                    // the TOMBSTONES, because a retired id is RESERVED FOREVER: it belongs to the page that
                    // earned it and to nothing else. Without that last arm a copied/restored file carrying a
                    // retired `id:` would simply take it, and since the resolver checks LIVE first, `/p/{id}`
                    // would then silently redirect to the WRONG PAGE - strictly worse than the 404 the tombstone
                    // exists to prevent, because a dead link announces itself and a live wrong one does not.
                    ownerOf = { id ->
                        claimed[id]
                            ?: idMap.binding(id)
                                ?.takeIf { BindingVisibility.isLive(it, witnessed.keys, scannedRoots, registeredRoots, supersession) }
                                ?.path
                            ?: idMap.retired(id)?.path
                    },
                    // ...and the OTHER half: may this pass TAKE the id from that owner, knowing the winner's
                    // key-complete bind DELETES the owner's row? Only on positive evidence - we READ the file, a
                    // proof covers it, or its id lives in its own frontmatter and is therefore free to travel
                    // with it (see [Supersession]). A retired owner has no durable row and is never supersedable:
                    // the claimant reassigns and records the contest.
                    supersedable = { owner -> idMap.find(owner)?.let(supersession::mayDisplace) ?: (owner in witnessed) },
                )
                claimed[assignment.id] = path
                resolved[path] = assignment
            }
        }

        // The plan is checked BEFORE it is made durable, because a durable duplicate cannot be walked back: the
        // winner's key-complete bind sweeps the loser's row, the loser walks off with the permalink, and
        // `PageIndex`'s own `byId` uniqueness check throws only AFTER all of that has landed - so every
        // subsequent boot dies in the same place, on rows nothing will now rewrite. A failed pass changes
        // nothing and carries the last-good snapshot; that is strictly the better failure.
        requireDistinctIds(resolved.mapValues { (_, assignment) -> assignment.id })

        val identities = HashMap<RootedPath, Identity>()
        for ((path, assignment) in resolved) {
            val materialized = assignment.source == PageIdentityService.Source.FRONTMATTER
            // Rank-then-path order (the map's insertion order): the winner's key-complete bind sweeps the loser's
            // stale row BEFORE the loser rebinds itself, so no page ever reads back an identity this pass has
            // already re-awarded. The ISSUE lands with the bind that supersedes it, never after it or not at all.
            //
            // The bind is handed the SAME [Supersession] the resolve above ran under, so a REFUSAL means the two
            // disagreed - a rule-drift bug, not a data condition. It is checked rather than ignored for the same
            // reason [requireDistinctIds] is: `duplicate()` reused a `mappedId` blind for a whole release and
            // nothing between there and the disk noticed. bind() refuses BEFORE it writes anything, so the abort
            // costs a pass and destroys nothing - which is strictly better than a silently stolen permalink.
            val outcome = idMap.bind(path, assignment.id, materialized = materialized, supersession = supersession)
            check(outcome is BindOutcome.Bound) {
                "identity resolution awarded ${assignment.id.value} to ${path.path.value} in '${path.root}', and the bind " +
                    "REFUSED it: ${(outcome as BindOutcome.Refused).let {
                        "held by ${it.heldBy}${if (it.retired) " (retired)" else ""}"
                    }}. " +
                    "The resolver and the bind gate disagree about who owns that id - no supersession is safe under that."
            }
            assignment.issue?.let(idMap::record)
            identities[path] = Identity(assignment.id, materialized)
        }
        return identities
    }

    /** §A4 alias semantics for one rebuild: move detection, `redirect_from`, then the shadow sweep. */
    private fun recordAliases(previousUrlPaths: Map<PageId, PreviousUrl>, snapshot: PageIndex) {
        val liveCanonicals = snapshot.byUrlPath.keys

        // Move/rename/slug-change detection: a known id whose canonical (root, URL path) changed
        // since the previous snapshot leaves the old rooted path behind as an alias — unless a live
        // canonical now claims it (live always wins; nothing to register, the conflict is recorded
        // instead). The alias lands in the OLD root's namespace.
        //
        // The previous paths come from the previous published snapshot — or, on the first rebuild
        // after startup, from the persisted §B3 checkpoint, which closes the Phase-1 down-time-move
        // gap for MATERIALIZED pages (the id travels in the file). An unmaterialized page moved
        // while down still gets a fresh id and no alias: the accepted §5.2 path-keyed-identity
        // trade-off, restated, not fixed here.
        for (page in snapshot.pages) {
            val previous = previousUrlPaths[page.id] ?: continue
            val oldUrlPath = previous.urlPath ?: continue
            val old = RootedPath(previous.root, oldUrlPath)
            if (old == page.urlPath?.let { RootedPath(page.root, it) }) continue
            if (old in liveCanonicals) {
                idMap.record(
                    IdentityIssue.RedirectConflict(
                        root = old.root,
                        path = old.path,
                        message = "move alias for page ${page.id} dropped: shadowed by a live canonical path",
                    ),
                )
            } else {
                aliasRegistry.register(old, page.id)
            }
        }

        // redirect_from registration: file-path values converted through the same URL construction,
        // in the declaring page's root namespace.
        for (page in snapshot.pages) {
            for (raw in page.frontmatter.strings("redirect_from")) {
                val target = CanonicalUrlBuilder.redirectUrlPath(raw)
                if (target == null) {
                    logger.warn { "ignoring unusable redirect_from '$raw' on ${page.path.value}" }
                    continue
                }
                registerRedirect(RootedPath(page.root, target), page, liveCanonicals)
            }
        }

        // Shadow sweep: an alias persisted earlier that a live canonical path claims now is dropped.
        for (canonical in liveCanonicals) {
            aliasRegistry.dropShadowed(canonical)?.let { dropped ->
                idMap.record(
                    IdentityIssue.RedirectConflict(
                        root = canonical.root,
                        path = canonical.path,
                        message = "alias to page ${dropped.id} dropped: shadowed by a live canonical path",
                    ),
                )
            }
        }
    }

    /** Registers one `redirect_from` alias unless a live canonical or another page's alias claims it. */
    private fun registerRedirect(target: RootedPath, page: IndexedPage, liveCanonicals: Set<RootedPath>) {
        val existing = aliasRegistry.find(target)
        when {
            target in liveCanonicals -> idMap.record(
                IdentityIssue.RedirectConflict(
                    root = target.root,
                    path = target.path,
                    message = "redirect_from of ${page.path.value} ignored: a live canonical path claims it",
                ),
            )
            existing != null && existing != page.id -> idMap.record(
                IdentityIssue.RedirectConflict(
                    root = target.root,
                    path = target.path,
                    message = "redirect_from of ${page.path.value} ignored: already an alias of page $existing",
                ),
            )
            existing == null -> aliasRegistry.register(target, page.id)
            // existing == page.id: already registered — nothing to do.
        }
    }

    private fun ScanIssue.toIdentityIssue(root: RootName): IdentityIssue = when (this) {
        // The loser's raw name passes through verbatim — building a TreePath from it would
        // NFC-normalize it back into keptPath, erasing the one value that distinguishes the loser.
        is ScanIssue.PathCollision -> IdentityIssue.PathCollision(root = root, keptPath = path, loserRawName = loserRawName)
    }

    private val TreePath.stem: String get() = name.removeSuffix(".md")

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

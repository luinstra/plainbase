@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.principal.ManageGrant
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.NoRetirements
import com.plainbase.domain.repository.NoTopology
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.GitCheckpointAdvance
import com.plainbase.domain.root.InferredProofMint
import com.plainbase.domain.root.ObjectManifest
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
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.root.Witness
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Coordinates source materialization, absence proofs, identity assignment, and snapshot assembly across registered
 * roots (ADR-0011), then atomically publishes one immutable [PageIndex].
 *
 * [IndexSourceReader] probes for root loss. An unavailable or failed root is skipped and its last-good section carried;
 * an incomplete scan still participates, while a never-scanned root contributes no section. Missing pages grant no
 * deletion authority: only [AbsenceProof] (EPOCH, OBJECT_LIST, or GIT) and accepted OPERATOR decisions can retire a
 * binding. Other absences remain in [RootLimbo], and search reads current durable retired-unbound authority itself.
 *
 * Witnesses are pass-local rooted read evidence. All sources resolve before binding, with roots ordered by rank and
 * duplicates resolved within a root by frontmatter and path order. Identity is root-scoped (ADR-0012), and
 * [Supersession] prevents changes under roots this pass did not scan.
 *
 * Bytes and frontmatter are read once, and each page is rendered once from a URL-complete skeleton. [current] is
 * published atomically; synchronized rebuilds prevent stale publication. The persisted checkpoint seeds the first
 * rebuild for aliases after downtime. Listeners run after publication: [Exception] is contained and logged, [Error]
 * propagates, and no rollback is promised for earlier durable effects.
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
    /** Shared availability state used for both skip checks and root-loss marking. */
    private val availability: RootAvailability = RootAvailability(kotlin.time.Clock.System),
    /** Proof-apply transaction and durable freshness checks; the runtime supplies the real repository. */
    private val retirements: RetirementRepository = NoRetirements,
    /** Derived limbo state, republished each pass rather than stored as snapshot data. */
    private val limbo: RootLimbo = RootLimbo(),
    /** Observation-epoch proof source; the default cannot mint an honored token. */
    private val epochs: ObservationEpoch = ObservationEpoch(NoRetirements, RootConvergence()),
    /** Binding-latch proof source for deciding whether an object LIST describes this corpus. */
    private val bindings: BindingLatch = BindingLatch(NoTopology),
) {

    /** One root's topology entry, content store, history, and optional object manifest source. */
    data class Source(
        val root: Root,
        val store: ContentStore,
        val history: HistoryProvider,
        /** Latest complete bucket LIST source for an object root; null means no OBJECT_LIST proof source. */
        val manifests: ObjectManifestProvider? = null,
    )

    /** Called synchronously after publication with the pass-local bindings retired by proof application. */
    fun interface PublicationListener {
        fun published(snapshot: PageIndex, retired: Set<RootedPageId>)
    }

    init {
        val names = sources.map { it.root.name }
        require(names.size == names.toSet().size) {
            "duplicate source root(s): ${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.joinToString(", ")}"
        }
        // Unknown roots would sort before ranked roots when the rank function returns -1.
        sources.forEach { source ->
            require(rootRank(source.root.name) >= 0) { "source root '${source.root.name}' is unknown to the registry rank" }
        }
    }

    // Enforce one deterministic source and section order. Rank does not choose an id across roots (ADR-0012).
    private val sources: List<Source> = sources.sortedBy { rootRank(it.root.name) }

    private val sourcesByRoot: Map<RootName, Source> = this.sources.associateBy { it.root.name }

    /** Shared root-loss rule over the availability holder. */
    private val rootLoss = RootLossClassifier(availability)

    /** Shared 404-vs-503 rule over the durable index. */
    private val absence = AbsenceClassifier(idMap)

    /** Eager source materializer using the shared classifiers. */
    private val sourceReader = IndexSourceReader(frontmatterParser, absence, availability, rootLoss)

    /** Identity resolver and binder using the builder's repositories and patcher. */
    private val identityAssignments = IndexIdentityAssignments(idMap, identity, patcher)

    /** Snapshot assembly; source reads, authority, and publication remain in this coordinator. */
    private val snapshotAssembler = IndexSnapshotAssembler(rendererFactory, citations)

    /** Serving-only hint: a previously seen corpus may treat an empty scan as 404, never as delete authority. */
    private val corpusSeen = mutableSetOf<RootName>()

    /** The atomically published snapshot; authority evidence remains pass-local. */
    private val holder = AtomicReference<PageIndex>(PageIndex.EMPTY)

    /** The published snapshot, initially [PageIndex.EMPTY]. */
    val current: PageIndex get() = holder.load()

    /** Runs the serialized full pass and atomically publishes the new snapshot. */
    @Synchronized
    fun rebuild(): PageIndex = rebuildInternal(createTarget = null)

    /** Runs the same serialized full pass, allowing confirmation only for this eligible CREATE target. */
    @Synchronized
    fun rebuildAfterCreate(target: RootedPath): PageIndex = rebuildInternal(createTarget = target)

    private fun rebuildInternal(createTarget: RootedPath?): PageIndex {
        val previous = holder.load()
        // The EMPTY holder is the startup sentinel: use the persisted checkpoint for the first alias comparison,
        // then use the previous published snapshot.
        val previousUrlPaths: Map<RootedPageId, TreePath?> =
            if (previous === PageIndex.EMPTY) {
                checkpoint.load()
            } else {
                previous.pages.associate { it.rooted to it.urlPath }
            }

        // Capture freshness once, before scanning or other evidence reads.
        val pass = AbsencePass.capture(epochs, retirements, idMap, bindings, sources, localSources, gitOracleRoots)
        val observed = sources.mapNotNull { sourceReader.read(it.root, it.store, it.history) }
        // Witness every rooted path read, including pages later excluded as suspect; the latch uses this full view.
        val seen: Map<RootedPath, Witness> = observed.flatMap { scan ->
            scan.drafts.map { draft ->
                RootedPath(scan.root, draft.file.path) to Witness(patcher.readIdValue(draft.bytes)?.let(PageId::of))
            }
        }.toMap()

        val confirmations = confirmEpochs(observed)
        // Manifest selection needs a complete walk; minting also requires selected-page reads for the matching root.
        val manifests = sources.filter { it.root.backend is RootBackend.Object }
            .filter { source -> observed.any { it.root == source.root.name && it.complete } }
            .mapNotNull { source -> source.manifests?.latestManifest()?.let { source.root.name to it } }
            .toMap()
        val absence = pass.mintEpoch(confirmations) + pass.mintObjectList(manifests, observed, witnessed = seen)
        val gitMint = pass.mintGit(observed)
        val proofs: List<AbsenceProof> = absence + gitMint.proofs
        // Pass-local witnessed rooted ids refute retirement. Keep availability live inside applyProofs: stamps use
        // the captured early value, while root-loss checks must observe the latest mark inside the transaction.
        // Apply before identity binding, which advances freshness and must see this pass's tombstones.
        val retired: Set<RootedPageId> = retirements.applyProofs(
            proofs = proofs,
            witnessed = seen.entries.mapNotNullTo(mutableSetOf()) { (rootedPath, w) ->
                w.observedId?.let { RootedPageId(rootedPath.root, it) }
            },
            unavailableNow = { availability.current().unavailable.keys },
            advances = gitMint.advances,
        )

        // A suspect object draft must not displace an incumbent before the binding is trusted. Drop it from binding,
        // publication, and witnessing; its incumbent remains in limbo. Proofs use the full witness computed above.
        val suspect = suspectDrafts(seen)
        val scans = if (suspect.isEmpty()) {
            observed
        } else {
            observed.map { scan -> scan.copy(drafts = scan.drafts.filterNot { RootedPath(scan.root, it.file.path) in suspect }) }
        }
        val witnessed: Map<RootedPath, Witness> = if (suspect.isEmpty()) seen else seen - suspect
        val scannedRoots: Set<RootName> = scans.filter { it.complete }.map { it.root }.toSet()
        // A complete non-empty scan updates the serving-only 503-vs-404 hint; it grants no proof authority.
        scans.filter { it.complete && it.drafts.isNotEmpty() }.forEach { corpusSeen += it.root }

        // Incomplete materialized scans participate. Record buffered issues only after every source returns.
        val raisedIssues = mutableListOf<IdentityIssue>()
        scans.forEach { scan -> scan.issues.forEach { record(raisedIssues, it) } }

        val allowUnchangedConfirmation = createTarget?.let { isEligibleCreateConfirmation(it, scans) } == true
        val identities = identityAssignments.resolveIdentities(
            scans = scans,
            witnessed = witnessed,
            scannedRoots = scannedRoots,
            registeredRoots = registeredRoots,
            raised = raisedIssues,
            allowUnchangedConfirmation = allowUnchangedConfirmation,
        )

        val snapshot = snapshotAssembler.assemble(
            scans = scans,
            identities = identities,
            roots = sources.map { it.root.name },
            previous = previous,
        )
        recordAliases(previousUrlPaths, snapshot, raisedIssues)
        holder.store(snapshot)
        finishRebuild(snapshot, retired, witnessed, scannedRoots, raisedIssues)
        return snapshot
    }

    private fun isEligibleCreateConfirmation(target: RootedPath, scans: List<SourceScan>): Boolean {
        if (sources.size != 1 || registeredRoots != setOf(target.root)) return false
        val source = sources.single()
        if (source.root.name != target.root || source.root.backend !is RootBackend.Local) return false
        val scan = scans.singleOrNull { it.root == target.root } ?: return false
        return availability.current().isAvailable(target.root) &&
            scan.complete &&
            scan.pageReadsComplete &&
            scan.issues.isEmpty() &&
            scan.urls.issues.isEmpty() &&
            scan.drafts.any { it.file.path == target.path }
    }

    private fun finishRebuild(
        snapshot: PageIndex,
        retired: Set<RootedPageId>,
        witnessed: Map<RootedPath, Witness>,
        scannedRoots: Set<RootName>,
        raisedIssues: List<IdentityIssue>,
    ) {
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
        // Surface this pass's issues once at WARN so operators can find the affected paths.
        if (raisedIssues.isNotEmpty()) {
            logger.warn {
                val shown = raisedIssues.take(MAX_LOGGED_ISSUES).joinToString("; ") { it.summary() }
                val more = (raisedIssues.size - MAX_LOGGED_ISSUES).takeIf { it > 0 }?.let { " (+$it more)" } ?: ""
                "${raisedIssues.size} identity issue(s) this pass, unresolved until the tree changes: $shown$more"
            }
        }
        notifyPublished(snapshot, retired)
    }

    /** Confirms complete local scans for the epoch source; skipped or incomplete scans break the epoch. */
    private fun confirmEpochs(scans: List<SourceScan>): Map<RootName, ObservationEpoch.EpochConfirmation> {
        val durable = idMap.bindings().groupBy({ it.path.root }, { BindingRef(it.path.path, it.id) })
        return localSources
            .mapNotNull { source ->
                val root = source.root.name
                // Skipped and incomplete scans both mean the tree was not fully observed.
                val scan = scans.firstOrNull { it.root == root }?.takeIf { it.complete }
                if (scan == null) {
                    epochs.broke(root, BreakCause.SCAN_FAILED)
                    return@mapNotNull null
                }
                epochs.scanned(
                    root = root,
                    witnessed = scan.drafts.mapTo(mutableSetOf()) { it.file.path },
                    // An uncertain read is neither witnessed nor absent.
                    unread = scan.unread,
                    durable = durable[root].orEmpty().toSet(),
                )?.let { root to it }
            }.toMap()
    }

    /** Captures freshness before evidence, then mints EPOCH, OBJECT_LIST, and GIT proofs from that capture. */
    @OptIn(InferredProofMint::class)
    private class AbsencePass private constructor(
        private val proven: (RootName, ObjectManifest, Map<RootedPath, Witness>) -> Set<BindingRef>,
        private val durable: () -> List<IdBinding>,
        private val gitCheckpoint: (RootName) -> String?,
        private val histories: Map<RootName, GitReads>,
        private val observationStamps: Map<RootName, ObservationId>,
        private val bindingEpochs: Map<RootName, BindingEpoch>,
        private val headsBefore: Map<RootName, String>,
    ) {
        /** GIT proofs and checkpoint advances applied together. */
        data class GitMint(val proofs: List<AbsenceProof>, val advances: List<GitCheckpointAdvance>)

        /** Read-only history operations for one eligible root. */
        class GitReads(
            val currentHead: () -> String?,
            val isAncestor: (String, String) -> Boolean,
            val deletedIn: (String, String) -> Set<TreePath>?,
        )

        /**
         * An epoch proves an online absence only after continuous coverage of an identity-stable tree. Object roots
         * use OBJECT_LIST instead; skipped or incomplete scans break the epoch and leave rows in limbo.
         */
        fun mintEpoch(confirmations: Map<RootName, ObservationEpoch.EpochConfirmation>): List<AbsenceProof> =
            confirmations.entries.map { (root, confirmation) ->
                AbsenceProof.inferred(
                    root = root,
                    source = ProofSource.EPOCH,
                    observationId = confirmation.observationId,
                    bindingEpoch = bindingEpochs.getValue(root),
                    covers = confirmation.gone,
                )
            }

        /**
         * Mints OBJECT_LIST only for a complete matching-root scan whose selected Markdown candidates were all read.
         * The [BindingLatch] decides whether the listed bucket is this corpus; no manifest means no proof. Unread
         * candidates withhold the proof while readable pages still publish and affected rows remain in limbo.
         */
        fun mintObjectList(
            manifests: Map<RootName, ObjectManifest>,
            scans: List<SourceScan>,
            witnessed: Map<RootedPath, Witness>,
        ): List<AbsenceProof> = manifests.entries.mapNotNull { (root, manifest) ->
            if (scans.none { it.root == root && it.complete && it.pageReadsComplete }) return@mapNotNull null
            val gone = proven(root, manifest, witnessed)
            if (gone.isEmpty()) {
                null
            } else {
                // Use the manifest's evidence-bound binding epoch, not a later live read. The latch also closes the
                // poll-to-mint rebind window (see ObjectListRebindBetweenPollAndMintTest).
                AbsenceProof.inferred(
                    root = root,
                    source = ProofSource.OBJECT_LIST,
                    observationId = observationStamps.getValue(root),
                    bindingEpoch = manifest.bindingEpoch,
                    covers = gone,
                )
            }
        }

        /**
         * Mints offline-delete proofs from a complete, head-stable scan and advances the checkpoint in the same
         * transaction. A null checkpoint establishes a baseline only; equal heads do nothing, rewrites fail closed,
         * and unread deleted paths withhold the advance. The effective cover is resolution-based, so an empty reap
         * still advances after the range is safely resolved.
         */
        fun mintGit(scans: List<SourceScan>): GitMint {
            val durableByRoot = durable().groupBy({ it.path.root }, { BindingRef(it.path.path, it.id) })
            val minted = histories.entries.mapNotNull { (root, git) ->
                mintGitForSource(root, git, scans, durableByRoot)
            }
            return GitMint(
                proofs = minted.flatMap(GitMint::proofs),
                advances = minted.flatMap(GitMint::advances),
            )
        }

        private fun mintGitForSource(
            root: RootName,
            git: GitReads,
            scans: List<SourceScan>,
            durable: Map<RootName, List<BindingRef>>,
        ): GitMint? {
            val preHead = headsBefore[root]
            val postHead = git.currentHead()
            val scan = scans.firstOrNull { it.root == root }?.takeIf { it.complete }
            return when {
                preHead == null -> null
                postHead == null || postHead != preHead -> null
                scan == null -> null
                else -> {
                    val token = observationStamps.getValue(root)
                    val epoch = bindingEpochs.getValue(root)
                    mintGitRange(git, scan, root, postHead, token, epoch, durable[root].orEmpty())
                }
            }
        }

        private fun mintGitRange(
            git: GitReads,
            scan: SourceScan,
            root: RootName,
            postHead: String,
            token: ObservationId,
            epoch: BindingEpoch,
            durable: List<BindingRef>,
        ): GitMint? {
            val oldHead = gitCheckpoint(root)
            return when {
                oldHead == null ->
                    GitMint(
                        proofs = emptyList(),
                        advances = listOf(GitCheckpointAdvance(root, token, epoch, postHead)),
                    )

                oldHead == postHead -> null
                !git.isAncestor(oldHead, postHead) -> null
                else -> git.deletedIn(oldHead, postHead)?.let { deleted ->
                    val enumerated = scan.drafts.mapTo(mutableSetOf()) { it.file.path }
                    val covers = durable.filterTo(mutableSetOf()) {
                        it.path in deleted && it.path !in enumerated && it.path !in scan.unread
                    }
                    val proof = covers.takeIf { it.isNotEmpty() }?.let {
                        AbsenceProof.inferred(
                            root = root,
                            source = ProofSource.GIT,
                            observationId = token,
                            bindingEpoch = epoch,
                            covers = it,
                        )
                    }
                    val advance = GitCheckpointAdvance(root, token, epoch, postHead)
                        .takeIf { (deleted intersect scan.unread).isEmpty() }
                    GitMint(listOfNotNull(proof), listOfNotNull(advance))
                }
            }
        }

        companion object {
            /**
             * Before evidence: establish epochs, reuse their returned observation tokens (singular read otherwise),
             * capture local binding epochs, then capture Git HEADs. Re-reading an established token could absorb a
             * concurrent break; late binding stamps could absorb a rebind that should invalidate the proof.
             * EPOCH uses its confirmation's observation token; OBJECT_LIST uses its manifest's binding epoch.
             * Git HEAD must still match after the scan, or neither proof nor checkpoint advance is allowed.
             */
            fun capture(
                epochs: ObservationEpoch,
                retirements: RetirementRepository,
                idMap: IdMapRepository,
                latch: BindingLatch,
                sources: List<Source>,
                localSources: List<Source>,
                gitOracleRoots: List<Source>,
            ): AbsencePass {
                val established = localSources.associate { it.root.name to epochs.establish(it.root.name) }
                val observationStamps = sources.associate { source ->
                    source.root.name to (established[source.root.name] ?: retirements.observation(source.root.name))
                }
                val bindingEpochs = localSources.associate { it.root.name to retirements.bindingEpoch(it.root.name) }
                val headsBefore = gitOracleRoots.mapNotNull { source ->
                    source.history.currentHead()?.let { source.root.name to it }
                }.toMap()
                val histories = gitOracleRoots.associate { source ->
                    source.root.name to GitReads(
                        source.history::currentHead,
                        source.history::isAncestor,
                        source.history::deletedIn,
                    )
                }
                return AbsencePass(
                    proven = latch::proven,
                    durable = idMap::bindings,
                    gitCheckpoint = retirements::gitHead,
                    histories = histories,
                    observationStamps = observationStamps,
                    bindingEpochs = bindingEpochs,
                    headsBefore = headsBefore,
                )
            }
        }
    }

    /** Roots eligible for the Git HEAD bracket and oracle. */
    private val gitOracleRoots: List<Source>
        get() = sources.filter { it.root.backend is RootBackend.Local && it.history.enabled }

    /** Local roots eligible for epoch and binding stamps. */
    private val localSources: List<Source>
        get() = sources.filter { it.root.backend is RootBackend.Local }

    /** Drafts that would displace an untrusted object binding with a different id. */
    private fun suspectDrafts(witnessed: Map<RootedPath, Witness>): Set<RootedPath> =
        sources.filter { it.root.backend is RootBackend.Object }
            .flatMapTo(mutableSetOf()) { source ->
                bindings.protects(source.root.name)
                    .map { RootedPath(source.root.name, it.path) to it.id }
                    .filter { (path, id) -> witnessed[path]?.observedId?.let { it != id } == true }
                    .map { (path, _) -> path }
            }

    /** Publishes `durableRows - witnessed - retired` as limbo and derives the root-level serving hint. */
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
            // A previously witnessed root may serve an empty scan as 404; this hint never authorizes deletion.
            if (root !in scannedRoots || root in witnessedRoots || root in corpusSeen) continue
            availability.markUnavailable(root, UnavailableCause.CORPUS_MISSING)
            val where = sourcesByRoot[root]?.root?.localPath ?: "its backing store"
            logger.error {
                "root '$root' scanned to ZERO pages while holding ${refs.size} durable binding(s): treating it as a BROKEN " +
                "VIEW, not deletion evidence: these live bindings are retained, while previously retired-unbound " +
                    "search rows may still be cleaned. Its last-good pages are carried forward, and it serves 503 " +
                    "rather than the 404 that would tell an agent " +
                    "its citations were never real. Check the mount at $where."
            }
        }
    }

    /** Manage-gated rescan entry; internal callers use the no-arg [rebuild]. */
    fun rebuild(@Suppress("UNUSED_PARAMETER") grant: ManageGrant): PageIndex = rebuild()

    /**
     * Rebuilds search from the current published snapshot under the rebuild monitor. This is a generation swap, not a
     * page rescan or publication-listener replay. Durable retirement authority is read during the operation so stale
     * snapshot pages are filtered; missing authority is a read failure, not mass-deletion permission.
     */
    @Synchronized
    fun rebuildSearchIndex(): Int {
        val indexer = requireNotNull(searchIndexer) { "rebuildSearchIndex() needs a SearchIndexer; none was wired into this IndexBuilder" }
        val snapshot = holder.load()
        return indexer.rebuild(snapshot)
    }

    /** Manage-gated entry for the search generation swap. */
    fun rebuildSearchIndex(@Suppress("UNUSED_PARAMETER") grant: ManageGrant): Int = rebuildSearchIndex()

    /**
     * Re-reads and re-renders only [target], a fixed [RootedPath] chosen by the write (ADR-0012), then stores the
     * updated snapshot and calls [SearchIndexer.syncPage]. It does not run a whole-listener/search diff, replace
     * checkpoints, or update aliases; renames use [rebuild]. The current root view supplies complete URLs.
     *
     * Rendering must depend only on the page's own content and that URL view; folder landing pages remain
     * client-rendered (ADR-0003). The bounded history lookup preserves the save path's cost; the page is stored
     * before targeted search, whose failure is recovered as dirty state.
     */
    @Synchronized
    fun reindex(target: RootedPath): PageIndex {
        val previous = holder.load()
        val page = previous.byPath[target]
            ?: error("reindex($target): page not in the published snapshot — a save-path invariant violation")
        val source = sourcesByRoot[target.root]
            ?: error("reindex($target): no source for root '${target.root}' - the snapshot outran this builder's wiring")
        // Check the live mark before reading; the classified read handles an unmarked root that vanishes mid-save.
        if (!availability.current().isAvailable(target.root)) throw RootUnavailable(target.root, UnavailableCause.VANISHED)
        val bytes = when (val read = absence.read(source.store, target)) {
            is ContentRead.Bytes -> read.bytes
            // Carry the store's root-loss mark through the write pipeline.
            ContentRead.RootDown -> throw RootUnavailable(target.root, UnavailableCause.VANISHED)
            // A CAS just wrote this path, so either absence is a save-path invariant failure, not a normal read result.
            ContentRead.ConfirmedAbsent, ContentRead.AbsenceUnknown ->
                error("reindex($target): ${target.path.value} unreadable just after a CAS write")
        }
        val parsed = frontmatterParser.parse(bytes)
        val rendered = rendererFactory(previous.view(target.root)).render(target.path, bytes)
        // Use the bounded one-page history lookup and the same root-loss boundary as full rebuild.
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
        // Replace by path, preserving the write target's root and location.
        val snapshot = PageIndex(
            previous.sections.map { section ->
                if (section.root == target.root) {
                    section.copy(pages = section.pages.map { if (it.path == target.path) reindexed else it })
                } else {
                    section
                }
            },
        )
        // Store before targeted search; search checks current durable retirement authority independently.
        holder.store(snapshot)
        logger.info {
            "reindexed page ${reindexed.id.value} (${target.path.value} in '${target.root}'); ${snapshot.pages.size} page(s) published"
        }
        searchIndexer?.syncPage(reindexed) // One-page upsert; no full search diff or publication listeners.
        return snapshot
    }

    /** Renders a submitted Markdown buffer against the current root view without source I/O or publication. */
    fun renderPreview(root: RootName, sourcePath: TreePath, bytes: ByteArray): RenderedPage =
        rendererFactory(current.view(root)).render(sourcePath, bytes)

    /** Listener exception policy: contain and log - the publish stands, the remaining listeners still run. */
    private fun notifyPublished(snapshot: PageIndex, retired: Set<RootedPageId>) {
        listeners.forEach { listener ->
            runCatching {
                listener.published(snapshot, retired)
            }.onFailure { failure ->
                if (failure is Error) throw failure
                // Contain Exception but let JVM Error terminate the rebuild.
                logger.error(failure) { "publication listener failed; the published snapshot stands" }
            }
        }
    }

    /** Records a scan or alias issue for durable storage and the rebuild warning. */
    private fun record(into: MutableList<IdentityIssue>, issue: IdentityIssue) {
        idMap.record(issue)
        into += issue
    }

    /** Records move aliases, `redirect_from`, and shadow conflicts for one rebuild. */
    private fun recordAliases(
        previousUrlPaths: Map<RootedPageId, TreePath?>,
        snapshot: PageIndex,
        // Keep alias conflicts in the same raised-issue accumulator as other identity issues.
        raised: MutableList<IdentityIssue>,
    ) {
        val liveCanonicals = snapshot.byUrlPath.keys

        // Register a changed rooted canonical path as an alias unless a live canonical shadows it. The first rebuild
        // uses the persisted checkpoint, so materialized moves during downtime are included.
        snapshot.pages.forEach { page ->
            recordMoveAlias(page, previousUrlPaths, liveCanonicals, raised)
        }

        // Register redirect_from values in the declaring page's root namespace.
        for (page in snapshot.pages) {
            for (raw in page.frontmatter.strings("redirect_from")) {
                val target = CanonicalUrlBuilder.redirectUrlPath(raw)
                if (target == null) {
                    logger.warn { "ignoring unusable redirect_from '$raw' on ${page.path.value}" }
                    continue
                }
                registerRedirect(RootedPath(page.root, target), page, liveCanonicals, raised)
            }
        }

        // Drop aliases shadowed by live canonical paths.
        for (canonical in liveCanonicals) {
            aliasRegistry.dropShadowed(canonical)?.let { dropped ->
                record(
                    raised,
                    IdentityIssue.RedirectConflict(
                        root = canonical.root,
                        path = canonical.path,
                        message = "alias to page ${dropped.target.id} dropped: shadowed by a live canonical path",
                    ),
                )
            }
        }
    }

    private fun recordMoveAlias(
        page: IndexedPage,
        previousUrlPaths: Map<RootedPageId, TreePath?>,
        liveCanonicals: Set<RootedPath>,
        raised: MutableList<IdentityIssue>,
    ) {
        // Match the exact rooted identity; cross-root movement is not inferred.
        val priorKey = page.rooted.takeIf { it in previousUrlPaths } ?: return
        val oldUrlPath = previousUrlPaths.getValue(priorKey) ?: return
        val old = RootedPath(priorKey.root, oldUrlPath)
        when {
            old == page.urlPath?.let { RootedPath(page.root, it) } -> Unit
            old in liveCanonicals ->
                record(
                    raised,
                    IdentityIssue.RedirectConflict(
                        root = old.root,
                        path = old.path,
                        message = "move alias for page ${page.id} dropped: shadowed by a live canonical path",
                    ),
                )

            else -> aliasRegistry.register(old, page.rooted)
        }
    }

    /** Registers one `redirect_from` alias unless a live canonical or another page's alias claims it. */
    private fun registerRedirect(
        target: RootedPath,
        page: IndexedPage,
        liveCanonicals: Set<RootedPath>,
        raised: MutableList<IdentityIssue>,
    ) {
        val existing = aliasRegistry.find(target)
        when {
            target in liveCanonicals -> record(
                raised,
                IdentityIssue.RedirectConflict(
                    root = target.root,
                    path = target.path,
                    message = "redirect_from of ${page.path.value} ignored: a live canonical path claims it",
                ),
            )
            existing != null && existing != page.rooted -> record(
                raised,
                IdentityIssue.RedirectConflict(
                    root = target.root,
                    path = target.path,
                    message = "redirect_from of ${page.path.value} ignored: already an alias of page ${existing.id}",
                ),
            )
            existing == null -> aliasRegistry.register(target, page.rooted)
            // existing == page.rooted: already registered — nothing to do.
        }
    }

    private val TreePath.stem: String get() = name.removeSuffix(".md")

    /** Formats one issue for the rebuild warning without coupling it to CLI output. */
    private fun IdentityIssue.summary(): String = when (this) {
        // Say "resolved first" because the winner may later re-identify and no longer hold the id.
        is IdentityIssue.DuplicateId ->
            "duplicate_id $id: $root:${keptPath.value} resolved first, ${reassignedPath.value} reassigned"
        // The message contains the actionable reason.
        is IdentityIssue.PatchRefused -> "patch_refused $root:${path.value}: $message"
        is IdentityIssue.RedirectConflict -> "redirect_conflict $root:${path.value}: $message"
        is IdentityIssue.PathCollision -> "path_collision $root:${keptPath.value} (excluded sibling '$loserRawName')"
        is IdentityIssue.PathSlugCollision ->
            "path_slug_collision $root:${keptPath.value} owns the URL, ${loserPath.value} is id-only"
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Bound warning detail while retaining the exact total count. */
        private const val MAX_LOGGED_ISSUES = 5
    }
}

@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AgentWriteDecision
import com.plainbase.domain.service.AssetWriteOutcome
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.CreateOutcome
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.MutatingFacade
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalFacade
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.ReindexResult
import com.plainbase.domain.service.RootStatus
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.domain.service.RootedResource
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.domain.service.WriteClass
import com.plainbase.domain.service.WriteIntent
import com.plainbase.domain.service.WriteOrigin
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.agentWriteDecision
import com.plainbase.domain.service.syntheticEmail
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The frameworks-side [MutatingFacade] impl (A3): it holds the raw [WritePipeline]/[ContentStore]/[IndexBuilder]
 * + the [PolicyService] as PRIVATE deps. Every method calls the matching `PolicyService.check*` FIRST (which
 * mints the unforgeable grant + records the pre-effect audit row, throwing [com.plainbase.domain.service
 * .AccessDenied] on deny), then delegates WITH the grant. No route can reach the raw mutators.
 *
 * The §A5 reindex single-flight lives here (the manage op owns it, never exposed to a route); the asset write's
 * INTERNAL post-write `IndexBuilder.rebuild()` uses the UNGATED no-arg overload (it is part of the EDIT write,
 * not a manage admin action).
 *
 * **The rooted write ordering (ADR-0011 D5/D6), for every id-addressed mutating entry:**
 *  1. read `indexBuilder.current` ONCE into a local, and resolve the target's owning root from it (falling back to
 *     the persisted `id_map` binding on a miss - which is the ONLY thing that can answer for a root that was never
 *     scanned this process);
 *  2. GATE on that root - rooted when a registered root owns the target, UNROOTED when none does;
 *  3. no owner -> the entry's existing not-found outcome. No write;
 *  4. root not serving -> 503;
 *  5. otherwise resolve the page from the SAME snapshot; still absent -> not-found (a stale binding under a LIVE
 *     root: the root is up and serving, so 404 is honest).
 *
 * Two consequences are deliberate. The gate PRECEDES availability, so a non-editable unavailable root answers 403
 * rather than 503 - a write that could never be authorized should not be reported as a transient outage. And the
 * root resolution PRECEDES the gate, which relaxes the "audit row lands before any read" wording: the gate is still
 * the FIRST authorization and the first audit, and no read RESULT reaches the caller before it - the pre-gate
 * resolution merely DERIVES the resource being authorized. Resolving-then-404ing before the gate would be the naive
 * alternative, and it would turn today's anonymous 401 into an existence oracle.
 */
class GuardedMutatingFacade(
    private val policy: PolicyService,
    private val writePipeline: WritePipeline,
    private val stores: (RootName) -> ContentStore,
    private val indexBuilder: IndexBuilder,
    private val availability: RootAvailability,
    /** The ONE owner of the id -> root resolution. Never a raw idMap/registry here: two copies of this rule drift. */
    private val resolver: PageRootResolver,
    // The degrade path files a proposal through the SAME guarded ProposalFacade routes use. The mutate↔proposals
    // construction cycle is broken by a provider-lambda (RouteContextFactory's 2-phase lateinit) - invoked only at
    // request time, never during assembly. Defaulted so the many older test constructors compile unchanged.
    private val proposals: () -> ProposalFacade = { error("ProposalFacade not wired for this GuardedMutatingFacade") },
    // The validated `agentDirectCommit` globs, each carrying the root its config key declared it under (config-parsed).
    // Empty (the default) ⇒ every agent write degrades.
    private val agentDirectCommitGlobs: List<CommitGlob> = emptyList(),
    // The author labeler - resolves an agent's snapshot (issuer, externalId, label) so a DIRECT agent
    // commit is git-attributed to the AGENT (author == committer), matching its agent-attributed audit row instead of
    // the server "Plainbase" identity. Defaulted null so the many older test constructors compile unchanged; the agent
    // DirectCommit path requires it (it is only ever reached with globs configured, where the production wiring + the
    // testRouteContext harness both thread it in via buildRouteContext).
    private val proposalLabeler: ProposalAuthorLabeler? = null,
) : MutatingFacade {

    /**
     * §A5 reindex single-flight: the first request flips this with `compareAndSet(false, true)` and proceeds; a
     * concurrent request sees the flip fail and gets [ReindexResult.InFlight] (the route's 409). Never `@Volatile`,
     * never `java.util.concurrent.atomic` (kotlin.concurrent.atomics house style; commit 9c78ca0).
     */
    private val reindexInFlight = AtomicBoolean(false)

    override fun save(principal: Principal, request: SaveRequest): SaveResult {
        // The agent direct-commit-vs-degrade decision is consulted ONLY for a genuine agent PUT
        // (Principal.Agent AND origin == DIRECT_PUT). Human/Anonymous ALWAYS, and the proposal-APPLY path REGARDLESS
        // of its principal (an off-mode agent CAN drive approve), take the strict audit-first direct
        // path UNCHANGED. The bypass is the WriteOrigin discriminator the apply caller sets, never an assumption about
        // the approver's principal type.
        if (principal !is Principal.Agent || request.origin == WriteOrigin.PROPOSAL_APPLY) {
            return directSave(principal, request)
        }

        // Agent DIRECT_PUT - DECIDE-FIRST (a deliberate, AGENT-ONLY relaxation of the strict audit-first ordering the
        // non-agent path keeps): the non-auditing agentModeFor lookup + the in-memory snapshot resolution run BEFORE
        // the audited checkEdit fires on the chosen branch. It leaks nothing client-visible - a deny still throws from
        // checkEdit with NO content returned, and EVERY agent path still audits EXACTLY once (EDIT@pageId on a direct
        // commit, EDIT@"proposal" on a degrade).
        val mode = policy.agentModeFor(principal)

        // ONE snapshot read, threaded through everything below (see the class doc).
        val snapshot = indexBuilder.current
        val root = resolver.rootOf(snapshot, request.pageId)

        // Missing-page COMMIT → 404 DIRECT (never a StaleBase degrade): there is no content to smuggle and no
        // applyable proposal to mint. Audit EDIT@pageId then return PageNotFound (a READ_ONLY agent denies here → 403).
        // A NON-degrading branch, so its single gate is not in tension with the gate-the-chosen-branch rule below.
        val current = snapshot.byId[request.pageId] ?: run {
            policy.checkEdit(principal, WriteClass.PageEdit, RootedResource(root, request.pageId.value))
            // The boot arm reaches HERE precisely because the page is absent from the snapshot - its root was never
            // scanned - so the 503 must be answered after the gate and before the 404, or an agent is told "gone".
            root?.let(::requireAvailable)
            return SaveResult.PageNotFound
        }

        // A null mode (revoked/expired token at clock.now()) is fail-safe DEGRADE; match against the SAME server-
        // resolved current.path the pipeline writes (smuggling closed by construction), in the page's OWN root - a
        // glob declared for main authorizes nothing in an extra root.
        val decision = if (mode == null) {
            AgentWriteDecision.DegradeToProposal(current.path)
        } else {
            agentWriteDecision(mode, agentDirectCommitGlobs, current.root, current.path)
        }
        return when (decision) {
            // DirectCommit: audit EDIT@pageId, then the EXISTING direct write over the SAME `current` object the
            // decision matched - so decision.targetPath === the WriteIntent's path by construction. The
            // agent's resolved identity threads in as BOTH git author AND committer (its commit is agent-attributed,
            // matching the audit row - never the server "Plainbase" default).
            //
            // The gate fires on the CHOSEN BRANCH, not up front, and that is load-bearing: a degrade then calls
            // `propose`, whose own checkEdit writes a SECOND audit row - so a pre-gate here would audit a degrading
            // agent TWICE and break the audits-exactly-once invariant. The degrade branch's own gate and its own
            // availability check fire inside `propose`, which 503s before it persists anything - so "an agent never
            // gets a proposal filed against a base its store cannot read" still holds, it just arrives via the
            // propose gate rather than a duplicate pre-gate.
            is AgentWriteDecision.DirectCommit -> {
                val identity = agentCommitIdentity(principal)
                val grant = policy.checkEdit(principal, WriteClass.PageEdit, RootedResource(current.root, request.pageId.value))
                requireAvailable(current.root)
                directWriteResolved(grant, request, current, identity, identity)
            }
            is AgentWriteDecision.DegradeToProposal -> degradeToProposal(principal, request)
        }
    }

    /** The strict audit-first direct path (Human/Anonymous + the proposal-apply caller): resolve → checkEdit → 503 → write. */
    private fun directSave(principal: Principal, request: SaveRequest): SaveResult {
        // ONE snapshot read (the class doc's rule). The root resolution that precedes the gate is not a read the
        // CALLER receives - it DERIVES the resource being authorized. The audit-first invariant that actually matters
        // is intact: the gate is still the FIRST authorization and the first audit, and no read RESULT reaches the
        // caller before it. (Resolving the root and 404ing BEFORE the gate would be the naive fix and a bad one: it
        // would turn today's anonymous 401 into an existence oracle - 401 for a real id, 404 for a bogus one - and
        // drop the denied-EDIT audit row this arm guarantees.)
        val snapshot = indexBuilder.current
        // A PINNED root (the proposal-apply path) IS the answer — it was decided at propose time, shown to the approving
        // admin and gated on; re-deriving it from the id would be exactly the re-derivation that lets a D17 cross-root
        // id reassignment walk an approved edit into an unreviewed repository. A bare PUT has no pin: the id is the
        // address, so it resolves (see [SaveRequest.expectedRoot]).
        val root = request.expectedRoot ?: resolver.rootOf(snapshot, request.pageId)
        val grant = policy.checkEdit(principal, WriteClass.PageEdit, RootedResource(root, request.pageId.value))

        // No registered root owns this id (unknown, or bound only under a detached root) -> today's 404, gated and
        // audited first. Otherwise: 503 before 404, so a boot-unavailable root never reports its pages as gone.
        if (root == null) return SaveResult.PageNotFound
        requireAvailable(root)

        // (5) Path-param id is the identity authority (R1): an id absent from the index is 404 - the route never
        // invents a path. A stale binding under a LIVE root lands here, and 404 is the honest answer: the root is up
        // and serving. An id present but under a DIFFERENT root than the pinned one is the same 404: from the root
        // this save was authorized against, that page is gone. The git attribution is whatever the request carried
        // (the apply path's proposer/approver).
        val current = snapshot.byId[request.pageId]?.takeIf { it.root == root } ?: return SaveResult.PageNotFound
        return directWriteResolved(grant, request, current, request.author, request.committer)
    }

    /**
     * The agent's git commit identity for a DIRECT commit (b1): the C4 labeler's snapshot label + the PINNED synthetic
     * email `<externalId>@<issuer>.plainbase.local` - the SAME attribution the apply path stamps for a proposer. Only
     * reached on the agent DirectCommit branch (globs configured), where [proposalLabeler] is always wired.
     */
    private fun agentCommitIdentity(principal: Principal.Agent): CommitIdentity {
        val author = requireNotNull(proposalLabeler) {
            "an agent direct commit needs the ProposalAuthorLabeler for git attribution (wire it in buildRouteContext)"
        }.resolve(principal)
        return CommitIdentity(author.label, syntheticEmail(author.issuer, author.externalId))
    }

    /**
     * The shared id-tamper-check + pipeline write over an ALREADY-resolved [current]. The agent DirectCommit branch
     * reuses the SAME `current` the decision matched, so `decision.targetPath === WriteIntent.path` holds by
     * construction; the non-agent path resolves its own `current` first.
     */
    private fun directWriteResolved(
        grant: EditGrant,
        request: SaveRequest,
        current: IndexedPage,
        author: CommitIdentity?,
        committer: CommitIdentity?,
    ): SaveResult {
        // (6) id-tamper check (R1, PB-WRITE-1): the submitted buffer's `id:` line must denote the SAME identity as
        // the page's CURRENT on-disk `id:` line. BOTH sides read through the IDENTICAL `PATCHER.readIdValue` - over
        // the submitted bytes and over `current.markdown` (the verbatim lenient decode the index captured; the `id:`
        // line is pure ASCII by the patcher grammar, so the round-trip is faithful) - and the two raw values compare
        // via [sameIdentity] (canonical-UUID when both parse, else byte-identical raw). Comparing the file's CURRENT
        // id - never `current.id`, the assigned pageId - lets a duplicate/adopted page whose on-disk id legitimately
        // differs from its pageId take a pure-body edit, matching `WritePipeline.classifyEdit` exactly. Adding/
        // changing/removing the honored id is a rename → 422 before the pipeline runs.
        val submittedRaw = PATCHER.readIdValue(request.bytes)
        val honoredRaw = PATCHER.readIdValue(current.markdown.toByteArray())
        if (!sameIdentity(submittedRaw, honoredRaw)) return SaveResult.IdMismatch

        return SaveResult.Written(
            writePipeline.write(
                grant,
                // The write's root is the GATED page's root, off the same snapshot object the gate read.
                WriteIntent(request.pageId, current.root, current.path, request.baseHash, request.bytes, author, committer),
            ),
        )
    }

    /**
     * The ONE availability gate on the write side. It runs AFTER the write gate, always: a write that could never be
     * authorized should not be reported as a transient outage, and the editable/matrix deny must not be preceded by
     * anything that leaks topology to an anonymous caller.
     */
    private fun requireAvailable(root: RootName) {
        val snapshot = availability.current()
        when (resolver.statusOf(root, snapshot)) {
            RootStatus.AVAILABLE -> Unit
            RootStatus.DETACHED -> throw RootUnavailable(root, UnavailableCause.DETACHED)
            RootStatus.UNAVAILABLE -> throw RootUnavailable(root, snapshot.unavailable.getValue(root).cause)
        }
    }

    /**
     * The out-of-glob / non-COMMIT degrade: file a proposal through the SAME guarded [ProposalFacade.propose] routes
     * use, so the audit is identical to every shipped propose (EDIT@"proposal") and the author resolves via the
     * labeler - no re-implementing the grant/labeler dance, no `Action.PROPOSE`, NEVER the pageId EditGrant.
     *
     * The id-tamper check is INTENTIONALLY bypassed here (it lives on the DirectCommit/non-agent path only): a COMMIT
     * agent submitting a mismatched `id:` on an OUT-of-glob page files a PROPOSAL, not a 422 - the rename surfaces to
     * a human reviewer who rejects the rename-proposal, rather than being rejected inline. Out-of-glob writes are
     * ALWAYS human-gated, so this is the desired behavior, not a silent divergence from the direct path.
     */
    private fun degradeToProposal(principal: Principal, request: SaveRequest): SaveResult {
        val outcome = proposals().propose(
            principal,
            ProposeCommand.Edit(
                pageId = request.pageId,
                baseHash = request.baseHash,
                clientTargetPath = null, // server-resolved; never a client-divergence path
                proposedContent = request.bytes,
                rationale = DEGRADE_RATIONALE,
            ),
        )
        return when (outcome) {
            is ProposeOutcome.Created -> SaveResult.DegradedToProposal(outcome.id, outcome.unifiedDiff)
            ProposeOutcome.StaleBase -> SaveResult.DegradeStaleBase
            ProposeOutcome.InvalidRequest -> error("degrade passes no client target_path; InvalidRequest is impossible")
            is ProposeOutcome.InvalidCreateContent -> error("an edit degrade files ProposeCommand.Edit; InvalidCreateContent is impossible")
        }
    }

    override fun create(principal: Principal, intent: CreateIntent, origin: WriteOrigin): CreateOutcome {
        // C1: the create twin of save()'s agent direct-commit-vs-degrade gate. Human/Anonymous ALWAYS, and the
        // PROPOSAL_APPLY caller REGARDLESS of principal (an off-mode agent can drive approve - finding #11), take the
        // strict direct path: the bypass is the WriteOrigin discriminator the apply caller sets, never an assumption
        // about the approver's principal type (the save() invariant). An approved out-of-glob create MUST land here.
        // A create's root comes from the REQUEST and the route has already validated it against the registry (400
        // `invalid_root`), so `checkCreate` never sees a null root and needs no unrooted arm.
        val resource = RootedResource(intent.root, intent.path.value)
        if (principal !is Principal.Agent || origin == WriteOrigin.PROPOSAL_APPLY) {
            val grant = policy.checkCreate(principal, WriteClass.PageCreate, resource)
            requireAvailable(intent.root)
            return CreateOutcome.DirectCreated(writePipeline.create(grant, intent))
        }

        // Agent DIRECT_PUT - decide-first (the save() AGENT-ONLY relaxation): a null mode (revoked/expired token at
        // clock.now()) is fail-safe DEGRADE; otherwise the glob decision over the SERVER-COMPOSED intent.path, in the
        // intent's OWN root.
        val mode = policy.agentModeFor(principal)
        val decision = if (mode == null) {
            AgentWriteDecision.DegradeToProposal(intent.path)
        } else {
            agentWriteDecision(mode, agentDirectCommitGlobs, intent.root, intent.path)
        }
        return when (decision) {
            // In-glob COMMIT agent: the agent's resolved identity as BOTH git author AND committer (the save() b1 idiom).
            is AgentWriteDecision.DirectCommit -> {
                val identity = agentCommitIdentity(principal)
                val grant = policy.checkCreate(principal, WriteClass.PageCreate, resource)
                requireAvailable(intent.root)
                CreateOutcome.DirectCreated(
                    writePipeline.create(grant, intent.copy(author = identity, committer = identity)),
                )
            }
            is AgentWriteDecision.DegradeToProposal -> degradeCreateToProposal(principal, intent)
        }
    }

    /**
     * The out-of-glob / non-COMMIT create degrade (the create twin of [degradeToProposal]): file a create-proposal
     * through the SAME guarded [ProposalFacade.propose] so the audit is identical to a shipped propose and `checkCreate`
     * runs (a READ_ONLY/revoked principal is denied there → AccessDenied → 403). The id is the one the
     * create route already minted + baked into the bytes - PIN it so the stored row + blob agree, no re-mint.
     */
    private fun degradeCreateToProposal(principal: Principal, intent: CreateIntent): CreateOutcome {
        val outcome = proposals().propose(
            principal,
            ProposeCommand.Create(
                // The THIRD ProposeCommand.Create construction site, and the only one that sees no wire string: it
                // inherits a legal root by construction from the CreateIntent the route already validated.
                root = intent.root,
                targetPath = intent.path,
                proposedContent = intent.bytes, // already composed + id-baked by the create route (degrade arm)
                rationale = DEGRADE_RATIONALE,
                pageId = intent.pageId, // PIN the already-minted id - no re-mint
            ),
        )
        return when (outcome) {
            is ProposeOutcome.Created -> CreateOutcome.DegradedToProposal(outcome.id, outcome.unifiedDiff)
            is ProposeOutcome.InvalidCreateContent -> CreateOutcome.InvalidContent(outcome.message)
            ProposeOutcome.StaleBase -> error("a create degrade has no base; StaleBase is impossible")
            ProposeOutcome.InvalidRequest -> error("a create degrade passes a server-derived path; InvalidRequest is impossible")
        }
    }

    override fun writeAsset(
        principal: Principal,
        pageId: PageId,
        filename: String,
        bytes: ByteArray,
        hasher: (ByteArray) -> String,
    ): AssetWriteOutcome {
        // ONE snapshot read, threaded through the gate and the page resolve (the class doc's rule).
        val snapshot = indexBuilder.current
        val root = resolver.rootOf(snapshot, pageId)

        // EDIT-gate on the page id (the asset belongs to the page); the grant authorizes the asset write AND the
        // internal post-write rebuild (the rebuild is part of the write, reached via the ungated no-arg overload).
        val grant = policy.checkEdit(principal, WriteClass.AssetWrite, RootedResource(root, pageId.value))

        // Resolve the page's folder from the published snapshot; an unknown id is a missing page - but 503 before
        // 404, so a boot-unavailable root's page is never reported as gone.
        if (root == null) return AssetWriteOutcome.PageMissing
        requireAvailable(root)
        val page = snapshot.byId[pageId] ?: return AssetWriteOutcome.PageMissing
        val store = stores(page.root)

        // Snapshot membership ≠ disk reality: re-check the page file on disk so we don't write an asset (and
        // return Created) for a page whose .md was externally deleted since the last rebuild. A throwing read is a
        // transient FS fault (Unreadable), not a missing page.
        //
        // This read is UPSTREAM of the store's own write-entry guard - it returns before `writeAssetExclusive` is
        // ever called - so the guard cannot reach it, and a bare null here would answer 404 `PageMissing` for a root
        // whose disk is unmounted: an outright D5 violation, on the surface that most needs to not lie (an agent
        // told "page gone" drops its citations). Hence a CLASSIFIED read. It also has to be a RESULT and not a throw:
        // the blanket catch just below would have swallowed a throw and re-emitted it as 503 `content_unreadable`,
        // one of the very codes the invariant forbids for a root-gone condition.
        val pageOnDisk = try {
            store.readClassified(page.path)
        } catch (e: Exception) {
            logger.warn(e) { "stale-page re-check failed reading '${page.path.value}'; treating as unreadable" }
            return AssetWriteOutcome.Unreadable
        }
        when (pageOnDisk) {
            is ContentRead.Bytes -> Unit
            ContentRead.Absent -> return AssetWriteOutcome.PageMissing
            ContentRead.RootDown -> throw RootUnavailable(page.root, UnavailableCause.VANISHED)
        }

        // The asset path = the page's folder + the validated segment (childOf throws only on a bad segment, which
        // the route's filename validator already excluded).
        val assetPath = TreePath.childOf(page.path.parent, filename)

        return when (val result = store.writeAssetExclusive(grant, assetPath, bytes, hasher)) {
            is CreateResult.Created -> {
                // Make the asset reachable: a full rebuild puts it in current.assets. A throw leaves the bytes
                // durably on disk but unindexed (the route's 503). Uses the UNGATED rebuild (part of the write).
                try {
                    indexBuilder.rebuild()
                } catch (e: Exception) {
                    logger.error(e) { "asset written but rebuild failed for '${assetPath.value}'; bytes are durable" }
                    return AssetWriteOutcome.WrittenButUnindexed(assetPath)
                }
                // A rebuild no longer THROWS on a lost root - it probes, MARKS, skips, carries and returns normally -
                // so the `catch` above is not a guard any more, and without this re-read the facade would answer 201
                // `Created` with a url for a write into a root that is gone, while every READ of that same root
                // answers 503. Whatever CALLS an operation that can degrade must re-read the mark before it answers
                // success. It is a holder read, not an FS call: the rebuild already probed.
                //
                // WrittenButUnindexed, not RootUnavailable: the bytes DID land. A 503 `root_unavailable` promises
                // "nothing written, retryable", and answering that here would be the mirror-image lie - the one the
                // retry-honesty rule forbids. It is also exactly what the create twin already answers for the
                // identical window, so one situation keeps one answer.
                if (!availability.current().isAvailable(page.root)) return AssetWriteOutcome.WrittenButUnindexed(assetPath)
                AssetWriteOutcome.Created(
                    path = assetPath,
                    url = indexBuilder.current.view(page.root).assetUrl(assetPath),
                    contentHash = result.newHash,
                )
            }
            is CreateResult.Exists -> {
                // Self-heal a prior written-but-unindexed orphan: if the bytes are on disk but the asset is NOT in
                // current.assets, best-effort rebuild FIRST so it becomes reachable on this retry. A failing
                // rebuild here must NOT turn the 409 into a 500 (runCatching). A genuine duplicate skips it.
                if (result.path !in indexBuilder.current.section(page.root).assets) {
                    runCatching { indexBuilder.rebuild() }
                }
                // The same degraded-rebuild window as the Created arm - but NOTHING landed on THIS request, so the
                // honest answer is the entry guard's own nothing-written 503, never a 409 minted off a dead root.
                requireAvailable(page.root)
                AssetWriteOutcome.Exists(result.path)
            }
            is CreateResult.ParentMissing -> AssetWriteOutcome.PageMissing
            is CreateResult.Rejected -> AssetWriteOutcome.Rejected(result.reason)
            is CreateResult.Unreadable ->
                if (result.targetMutated) {
                    // Q8b durable_but_unmirrored (object mode): the asset PUT is DURABLE at the bucket but the
                    // mirror apply failed - the authoritative store HOLDS the asset. Report it HONESTLY as
                    // durable-but-unpublished (the same 503 shape as a Created-but-rebuild-failed asset), NEVER
                    // "nothing written" - a client must not retry under a false no-write assumption (the asset
                    // twin of the page-write Q8b retry-honesty). Best-effort rebuild in case the immediate
                    // reconcile already healed the mirror, so a subsequent read/retry converges.
                    runCatching { indexBuilder.rebuild() }
                    AssetWriteOutcome.WrittenButUnindexed(assetPath)
                } else {
                    AssetWriteOutcome.Unreadable // nothing landed (a pre-send / atomic failure) - honest "nothing written"
                }
        }
    }

    override fun rescan(principal: Principal): PageIndex {
        val grant = policy.checkManage(principal)
        return indexBuilder.rebuild(grant)
    }

    override fun reindex(principal: Principal): ReindexResult {
        // The manage check (mint + audit) fires BEFORE the single-flight flag, so a denied caller never touches
        // the flag (and a deny is audited). The finally release means a thrown rebuild never wedges the flag.
        val grant = policy.checkManage(principal)
        if (!reindexInFlight.compareAndSet(expectedValue = false, newValue = true)) return ReindexResult.InFlight
        return try {
            ReindexResult.Done(indexBuilder.rebuildSearchIndex(grant))
        } finally {
            reindexInFlight.store(false)
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** P5: the deterministic rationale stamped on an auto-degraded proposal (a single-line literal - no `\n`). */
        const val DEGRADE_RATIONALE =
            "Auto-degraded: an agent direct commit fell outside agentDirectCommit.globs and was filed as a proposal for review."

        /** The single frontmatter id-detection grammar (lenient decode - the id-inspection trap is closed in W3a). */
        val PATCHER = FrontmatterPatcher()

        /**
         * Two raw `id:` line values (each from [FrontmatterPatcher.readIdValue], surrounding quotes NOT stripped)
         * denote the SAME identity iff they parse to the same canonical [PageId], OR - when one or both are not a
         * bare UUID (`id: "<uuid>"`, garbage, or absent) - they are the byte-identical raw string. The UUID arm makes
         * the check quote-TOLERANT across forms; the raw arm keeps a both-null (both-quoted/both-malformed/both-
         * absent) comparison honest instead of collapsing every unparseable id to "equal".
         */
        fun sameIdentity(a: String?, b: String?): Boolean {
            val pa = a?.let(PageId::of)
            val pb = b?.let(PageId::of)
            return if (pa != null && pb != null) pa == pb else a == b
        }
    }
}

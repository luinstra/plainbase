@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AbsenceUnverified
import com.plainbase.domain.service.AgentWriteDecision
import com.plainbase.domain.service.AmbiguousPageId
import com.plainbase.domain.service.AssetWriteOutcome
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.CreateOutcome
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IdResolution
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.MutatingFacade
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalFacade
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.ReindexResult
import com.plainbase.domain.service.ResolvedClaimants
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
import com.plainbase.frameworks.ktor.dto.WriteConflictReason
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
 * **The rooted write ordering (ADR-0011 D5/D6 + the C4 pinned split), for every id-addressed mutating entry:**
 *  1. read `indexBuilder.current` ONCE into a local; resolve the owning root id_map-FIRST (Option B) on a bare
 *     write, or durable-VALIDATE the caller's pin (registered-and-live `bindsLive`, never trusted blind);
 *  2. GATE on that root - rooted when a registered root owns the target, UNROOTED when none does;
 *  3. no owner -> the entry's existing not-found outcome, and no write. On the two page-CONTENT entries ([save] /
 *     [directSave]) a BARE write against a TOMBSTONED id is the frozen 409 `page_deleted` instead - but ONLY there:
 *     that 409 is a [WriteOutcome.Conflict] carrying current_content/current_hash/current_path, and an asset write
 *     has no analogue for any of the three, so [writeAsset] keeps its [AssetWriteOutcome.PageMissing] 404;
 *  4. root not serving -> 503;
 *  5. otherwise resolve the page from the SAME snapshot; a live binding whose page is absent from it is 503
 *     `absence_unverified` via the classifier (C1), and only a verified absence is the honest not-found - which then
 *     goes through the SAME [goneOrNotFound] step (3) uses, so the unbind-race arm and the no-owner arm answer one
 *     vocabulary for one domain fact instead of 404-vs-409 depending on which road reached it.
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
    /** The ONE owner of "is this absence a 404 or a 503?" (C1) - the same rule the read and index paths ask. */
    private val absence: AbsenceClassifier,
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

        // ONE snapshot read, threaded through everything below (see the class doc). The owning root is resolved
        // id_map-FIRST (Option B): a pinned agent PUT durable-validates the pin (registered-and-live), a bare one
        // resolves the durable claimant. Ambiguous/None audit ONCE with the bare (null-root) resource then answer,
        // so a degrading agent is never audited twice (the gate-the-chosen-branch rule below).
        //
        // The null root on a REJECTED PINNED write is a known forensic gap, NOT an oversight: the ledger row loses the
        // root the caller asserted. It cannot be closed by passing the named root here, because RootedResource.root is
        // an AUTHORIZATION input, not a label - PolicyService.kt:154 denies ROOT_NOT_EDITABLE on it, so a ghost or
        // read-only pin would start answering 403 instead of its ratified 404/stale_base. Closing it needs an audit-only
        // root channel through the gate. See the round-6 ledger entry.
        val snapshot = indexBuilder.current
        // A BARE write reads ONE durable `claims` snapshot and threads it into `goneOrNotFound` (§6.2): the fail-closed
        // `resolution()` and the frozen 409-vs-404 tombstone decision come from the SAME atomic read, so a reclaim
        // landing between the two can no longer flip the frozen 409 to a 404. A pinned write keeps `resolvePinned`.
        val expectedRoot = request.expectedRoot
        val claims: ResolvedClaimants? = if (expectedRoot == null) resolver.claimants(request.pageId) else null
        val res: IdResolution = if (expectedRoot != null) {
            resolver.resolvePinned(expectedRoot, request.pageId)
        } else {
            // `claims` is populated in exactly this branch (the `expectedRoot == null` predicate above); make that
            // coupling explicit rather than a bare `!!` that a later predicate change would turn into a latent NPE.
            checkNotNull(claims) { "a bare write (no expectedRoot) always reads a claimants snapshot" }.resolution()
        }
        return when (res) {
            is IdResolution.Ambiguous -> {
                policy.checkEdit(principal, WriteClass.PageEdit, RootedResource(null, request.pageId.value))
                throw AmbiguousPageId(request.pageId, res.candidates, res.hasRetiredCandidate)
            }
            // Missing-owner: audit EDIT@pageId (a READ_ONLY agent denies → 403), then the SAME frozen C1 vocabulary
            // as [directSave]'s bare None arm - deliberately, ONE vocabulary per endpoint regardless of principal. A
            // BARE write whose id is TOMBSTONED is 409 page_deleted (proven gone, never never-existed); only a
            // genuinely-unknown id (or a pin that no longer binds it) is the direct 404 - never a StaleBase degrade,
            // there is no content to smuggle and no applyable proposal to mint.
            IdResolution.None -> {
                policy.checkEdit(principal, WriteClass.PageEdit, RootedResource(null, request.pageId.value))
                goneOrNotFound(request.pageId, request.expectedRoot, claims)
            }
            is IdResolution.One -> {
                val current = snapshot.pageAt(RootedPageId(res.root, request.pageId)) ?: run {
                    // The page durably binds under res.root but is absent from the snapshot: a concurrent unbind race
                    // fallback (resolve read a claimant at T1; requireVerifiedAbsence re-reads at T2). Audit EDIT@page,
                    // then 503-before-404 so a boot-unavailable or limbo page is never reported as gone (C1).
                    // This arm and the None arm now AGREE: both end in [goneOrNotFound], so a bare write that loses the
                    // race is told the same frozen 409 page_deleted the common path tells it, for the same domain fact.
                    policy.checkEdit(principal, WriteClass.PageEdit, RootedResource(res.root, request.pageId.value))
                    requireAvailable(res.root)
                    absence.requireVerifiedAbsence(res.root, request.pageId, snapshot)
                    // The bare One-race fallback reads a FRESH claims snapshot at the RECHECK (post-unbind state) so the
                    // frozen 409 page_deleted survives a live->tombstone race; the pinned fallback passes null (404).
                    val recheck = if (request.expectedRoot == null) resolver.claimants(request.pageId) else null
                    return goneOrNotFound(request.pageId, request.expectedRoot, recheck)
                }
                // A null mode (revoked/expired token at clock.now()) is fail-safe DEGRADE; match against the SAME
                // server-resolved current.path the pipeline writes (smuggling closed by construction), in the page's
                // OWN root - a glob declared for main authorizes nothing in an extra root. DECIDE FIRST, then gate the
                // chosen branch: a degrade calls `propose`, whose own checkEdit writes the ONE audit row, so a pre-gate
                // here would audit a degrading agent TWICE.
                val decision = if (mode == null) {
                    AgentWriteDecision.DegradeToProposal(current.path)
                } else {
                    agentWriteDecision(mode, agentDirectCommitGlobs, current.root, current.path)
                }
                when (decision) {
                    // DirectCommit: audit EDIT@pageId once, then the direct write over the SAME `current` the decision
                    // matched (decision.targetPath === WriteIntent.path). The agent's resolved identity is BOTH git
                    // author AND committer (agent-attributed, matching the audit row).
                    is AgentWriteDecision.DirectCommit -> {
                        val identity = agentCommitIdentity(principal)
                        val grant = policy.checkEdit(principal, WriteClass.PageEdit, RootedResource(current.root, request.pageId.value))
                        requireAvailable(current.root)
                        directWriteResolved(grant, request, current, identity, identity)
                    }
                    // The degrade keeps the pin (CLASS-D): its proposal is filed against current.root, and `propose`'s
                    // own checkEdit + availability check fire inside it (one audit, 503 before persisting).
                    is AgentWriteDecision.DegradeToProposal -> degradeToProposal(principal, request, current.root)
                }
            }
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
        // RESOLVE-then-checkEdit-then-branch (audit EXACTLY once). A PINNED root (the proposal-apply path) IS the
        // answer, but it is durable-VALIDATED (registered-and-live) rather than trusted blind: re-deriving it from the
        // id would be picking among the roots that may each hold it (ADR-0012) and could walk an approved edit into
        // an unreviewed repository, while a pin
        // that no longer binds the id reads as gone from the approved root (see [SaveRequest.expectedRoot]). A bare PUT
        // has no pin: the id is the address, so it resolves id_map-first.
        val expectedRoot = request.expectedRoot
        val claims: ResolvedClaimants? = if (expectedRoot == null) resolver.claimants(request.pageId) else null
        val res: IdResolution = if (expectedRoot != null) {
            resolver.resolvePinned(expectedRoot, request.pageId)
        } else {
            // `claims` is populated in exactly this branch (the `expectedRoot == null` predicate above); make that
            // coupling explicit rather than a bare `!!` that a later predicate change would turn into a latent NPE.
            checkNotNull(claims) { "a bare write (no expectedRoot) always reads a claimants snapshot" }.resolution()
        }
        val grant = policy.checkEdit(principal, WriteClass.PageEdit, RootedResource((res as? IdResolution.One)?.root, request.pageId.value))
        return when (res) {
            is IdResolution.Ambiguous -> throw AmbiguousPageId(request.pageId, res.candidates, res.hasRetiredCandidate)
            // No LIVE registered root owns this id. On a BARE write, a TOMBSTONED id (any registered retired claimant)
            // is the frozen C1 answer: 409 page_deleted, current_* null - a page PROVEN gone is reported as deleted,
            // never as never-existed. Only a genuinely-unknown id (or a pin that no longer binds it - the pinned
            // fresh-fail-closed arm, which the apply path's toWriteOutcome maps to its own page_deleted) is 404.
            IdResolution.None -> goneOrNotFound(request.pageId, request.expectedRoot, claims)
            is IdResolution.One -> {
                requireAvailable(res.root)
                // (5) Path-param id is the identity authority (R1): an id absent from the index is 404 - the route
                // never invents a path. But 503 before 404: a live binding whose page is missing from the snapshot
                // means the pass could not read it, not that it is gone (C1). pageAt keys on (res.root, id), so a page
                // held under a DIFFERENT root (its own page, legal since ADR-0012) is already a miss here.
                // This arm and the None arm AGREE (they ask the same [goneOrNotFound]): a bare write that loses the
                // unbind race answers the frozen 409 page_deleted, not a 404 claiming the page never existed.
                val current = snapshot.pageAt(RootedPageId(res.root, request.pageId))
                    ?: run {
                        absence.requireVerifiedAbsence(res.root, request.pageId, snapshot)
                        val recheck = if (request.expectedRoot == null) resolver.claimants(request.pageId) else null
                        return goneOrNotFound(request.pageId, request.expectedRoot, recheck)
                    }
                directWriteResolved(grant, request, current, request.author, request.committer)
            }
        }
    }

    /**
     * **"No live page here" resolved into the ONE honest vocabulary, for every page-CONTENT arm that reaches it.** A
     * BARE write ([expectedRoot] null) against an id some registered root has TOMBSTONED is the frozen 409
     * `page_deleted` with current_* null: the page is PROVEN gone, and saying 404 would tell the caller it never
     * existed - the precise lie C1 exists to prevent, and the one that makes an agent discard live citations.
     *
     * Both callers per entry ask this: the common [IdResolution.None] arm AND the [IdResolution.One] fallback where the
     * page resolved live but was absent from the snapshot (the unbind race between `resolve` at T1 and the re-check at
     * T2). Those two are the SAME domain fact reached by different roads, so they must not answer differently - they
     * used to, and three review seats called it.
     *
     * The consult sits AFTER the caller's availability gate and AFTER `requireVerifiedAbsence`, deliberately: a page in
     * LIMBO must still 503 before anything here decides it is gone. Only an absence someone has actually PROVEN gets to
     * be reported at all, and only then does the tombstone pick which of the two "gone" answers is true.
     *
     * A PINNED request keeps its 404 (the ratified endpoint table): the caller asserted a root, and the honest answer to
     * "is it in THAT root" is no - a 409 would describe a different question than the one asked.
     *
     * [claims] is the single durable snapshot the bare arm already read (§6.2), NULL on the pinned path (which returns
     * 404 without consulting a tombstone). Its `retired` list is registered-filtered, so `retired.isNotEmpty()` is
     * byte-identical to the old `resolveRetired(id) != None` - only the read is now the same atomic snapshot as
     * `resolution()`, closing the reclaim-race that flipped the frozen 409 to a 404.
     */
    private fun goneOrNotFound(pageId: PageId, expectedRoot: RootName?, claims: ResolvedClaimants?): SaveResult =
        if (expectedRoot == null && claims?.retired?.isNotEmpty() == true) {
            SaveResult.Written(
                WriteOutcome.Conflict(
                    reason = WriteConflictReason.PAGE_DELETED,
                    currentContent = null,
                    currentHash = null,
                    currentPath = null,
                ),
            )
        } else {
            SaveResult.PageNotFound
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
    private fun degradeToProposal(principal: Principal, request: SaveRequest, root: RootName): SaveResult {
        val outcome = proposals().propose(
            principal,
            ProposeCommand.Edit(
                pageId = request.pageId,
                // CLASS-D: the degrade PINS the root the decision matched, so the propose gate durable-validates the
                // SAME root the write would have landed in, never a re-resolve that could pick a different holder.
                root = root,
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
        expectedRoot: RootName?,
    ): AssetWriteOutcome {
        // ONE snapshot read, threaded through the gate and the page resolve (the class doc's rule). RESOLVE (pin
        // durable-validated, else id_map-first) -> single EDIT-gate on the resolved (root, id) -> branch. The grant
        // authorizes the asset write AND the internal post-write rebuild (reached via the ungated no-arg overload).
        val snapshot = indexBuilder.current
        val res: IdResolution = expectedRoot?.let { resolver.resolvePinned(it, pageId) }
            ?: resolver.resolve(pageId)
        val grant = policy.checkEdit(principal, WriteClass.AssetWrite, RootedResource((res as? IdResolution.One)?.root, pageId.value))
        val page = when (res) {
            is IdResolution.Ambiguous -> throw AmbiguousPageId(pageId, res.candidates, res.hasRetiredCandidate)
            IdResolution.None -> return AssetWriteOutcome.PageMissing
            is IdResolution.One -> {
                requireAvailable(res.root)
                // 503 before 404 for a page we still BIND but did not witness, for the same reason (C1).
                absence.requireVerifiedAbsence(res.root, pageId, snapshot)
                snapshot.pageAt(RootedPageId(res.root, pageId)) ?: return AssetWriteOutcome.PageMissing
            }
        }
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
        val target = RootedPath(page.root, page.path)
        val pageOnDisk = try {
            absence.read(store, target)
        } catch (e: Exception) {
            logger.warn(e) { "stale-page re-check failed reading '${page.path.value}'; treating as unreadable" }
            return AssetWriteOutcome.Unreadable
        }
        when (pageOnDisk) {
            is ContentRead.Bytes -> Unit
            ContentRead.ConfirmedAbsent -> return AssetWriteOutcome.PageMissing
            // The page is still BOUND and its bytes are missing (C1): `PageMissing` is a 404, and a 404 here would
            // tell an author uploading an image that their page is gone while it sits on an unmounted disk. 503.
            ContentRead.AbsenceUnknown -> throw AbsenceUnverified(target)
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

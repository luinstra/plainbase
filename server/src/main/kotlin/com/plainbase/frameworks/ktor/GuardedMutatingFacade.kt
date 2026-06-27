@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.AgentWriteDecision
import com.plainbase.domain.service.AssetWriteOutcome
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.MutatingFacade
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalFacade
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.ReindexResult
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.domain.service.WriteIntent
import com.plainbase.domain.service.WriteOrigin
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.agentWriteDecision
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
 * not a manage admin action — §WI-3).
 */
class GuardedMutatingFacade(
    private val policy: PolicyService,
    private val writePipeline: WritePipeline,
    private val contentStore: ContentStore,
    private val indexBuilder: IndexBuilder,
    // P5: the degrade path files a proposal through the SAME guarded ProposalFacade routes use. The mutate↔proposals
    // construction cycle is broken by a provider-lambda (RouteContextFactory's 2-phase lateinit) — invoked only at
    // request time, never during assembly. Defaulted so the many non-P5 test constructors compile unchanged.
    private val proposals: () -> ProposalFacade = { error("ProposalFacade not wired for this GuardedMutatingFacade") },
    // P5: the validated `agentDirectCommit.globs` (config-parsed). Empty (the default) ⇒ every agent write degrades.
    private val agentDirectCommitGlobs: List<CommitGlob> = emptyList(),
) : MutatingFacade {

    /**
     * §A5 reindex single-flight: the first request flips this with `compareAndSet(false, true)` and proceeds; a
     * concurrent request sees the flip fail and gets [ReindexResult.InFlight] (the route's 409). Never `@Volatile`,
     * never `java.util.concurrent.atomic` (kotlin.concurrent.atomics house style; commit 9c78ca0).
     */
    private val reindexInFlight = AtomicBoolean(false)

    override fun save(principal: Principal, request: SaveRequest): SaveResult {
        // P5 gate (WI-4): the agent direct-commit-vs-degrade decision is consulted ONLY for a genuine agent PUT
        // (Principal.Agent AND origin == DIRECT_PUT). Human/Anonymous ALWAYS, and the proposal-APPLY path REGARDLESS
        // of its principal (an off-mode agent CAN drive approve — finding #11), take the strict audit-first direct
        // path UNCHANGED. The bypass is the WriteOrigin discriminator the apply caller sets, never an assumption about
        // the approver's principal type.
        if (principal !is Principal.Agent || request.origin == WriteOrigin.PROPOSAL_APPLY) {
            return directSave(principal, request)
        }

        // Agent DIRECT_PUT — DECIDE-FIRST (a deliberate, AGENT-ONLY relaxation of the strict audit-first ordering the
        // non-agent path keeps): the non-auditing agentModeFor lookup + the in-memory snapshot resolution run BEFORE
        // the audited checkEdit fires on the chosen branch. It leaks nothing client-visible — a deny still throws from
        // checkEdit with NO content returned, and EVERY agent path still audits EXACTLY once (EDIT@pageId on a direct
        // commit, EDIT@"proposal" on a degrade).
        val mode = policy.agentModeFor(principal)

        // Missing-page COMMIT → 404 DIRECT (never a StaleBase degrade): there is no content to smuggle and no
        // applyable proposal to mint. Audit EDIT@pageId then return PageNotFound (a READ_ONLY agent denies here → 403).
        val current = indexBuilder.current.byId[request.pageId] ?: run {
            policy.checkEdit(principal, request.pageId.value)
            return SaveResult.PageNotFound
        }

        // A null mode (revoked/expired token at clock.now()) is fail-safe DEGRADE; match against the SAME server-
        // resolved current.path the pipeline writes (WI-3 — smuggling closed by construction).
        val decision = if (mode == null) {
            AgentWriteDecision.DegradeToProposal(current.path)
        } else {
            agentWriteDecision(mode, agentDirectCommitGlobs, current.path)
        }
        return when (decision) {
            // DirectCommit: audit EDIT@pageId, then the EXISTING direct write over the SAME `current` object the
            // decision matched — so decision.targetPath === the WriteIntent's path by construction (WI-3).
            is AgentWriteDecision.DirectCommit ->
                directWriteResolved(policy.checkEdit(principal, request.pageId.value), request, current)
            is AgentWriteDecision.DegradeToProposal -> degradeToProposal(principal, request)
        }
    }

    /** The strict audit-first direct path (Human/Anonymous + the proposal-apply caller): checkEdit → resolve → write. */
    private fun directSave(principal: Principal, request: SaveRequest): SaveResult {
        // The AUDITED edit-check is the FIRST authorization on the write path — a denied save writes the denied-EDIT
        // audit row (PolicyService.checkEdit) BEFORE any read, so an unauthorized PUT can never escape the audit log
        // via an unaudited read-check. Only after the grant is minted do we resolve the snapshot + id-tamper-check.
        val grant = policy.checkEdit(principal, request.pageId.value)

        // (5) Path-param id is the identity authority (R1): an id absent from the index is 404 — the route never
        // invents a path. The snapshot resolution lives HERE (post-grant), not in a route read-check.
        val current = indexBuilder.current.byId[request.pageId] ?: return SaveResult.PageNotFound
        return directWriteResolved(grant, request, current)
    }

    /**
     * The shared id-tamper-check + pipeline write over an ALREADY-resolved [current]. The agent DirectCommit branch
     * reuses the SAME `current` the decision matched, so `decision.targetPath === WriteIntent.path` holds by
     * construction; the non-agent path resolves its own `current` first.
     */
    private fun directWriteResolved(grant: EditGrant, request: SaveRequest, current: IndexedPage): SaveResult {
        // (6) id-tamper check (R1, PB-WRITE-1): the submitted buffer's `id:` line must denote the SAME identity as
        // the page's CURRENT on-disk `id:` line. BOTH sides read through the IDENTICAL `PATCHER.readIdValue` — over
        // the submitted bytes and over `current.markdown` (the verbatim lenient decode the index captured; the `id:`
        // line is pure ASCII by the patcher grammar, so the round-trip is faithful) — and the two raw values compare
        // via [sameIdentity] (canonical-UUID when both parse, else byte-identical raw). Comparing the file's CURRENT
        // id — never `current.id`, the assigned pageId — lets a duplicate/adopted page whose on-disk id legitimately
        // differs from its pageId take a pure-body edit, matching `WritePipeline.classifyEdit` exactly. Adding/
        // changing/removing the honored id is a rename → 422 before the pipeline runs.
        val submittedRaw = PATCHER.readIdValue(request.bytes)
        val honoredRaw = PATCHER.readIdValue(current.markdown.toByteArray())
        if (!sameIdentity(submittedRaw, honoredRaw)) return SaveResult.IdMismatch

        return SaveResult.Written(
            writePipeline.write(
                grant,
                WriteIntent(request.pageId, current.path, request.baseHash, request.bytes, request.author, request.committer),
            ),
        )
    }

    /**
     * The out-of-glob / non-COMMIT degrade: file a proposal through the SAME guarded [ProposalFacade.propose] routes
     * use, so the audit is identical to every shipped propose (EDIT@"proposal") and the author resolves via the
     * labeler — no re-implementing the grant/labeler dance, no `Action.PROPOSE`, NEVER the pageId EditGrant.
     *
     * The id-tamper check is INTENTIONALLY bypassed here (it lives on the DirectCommit/non-agent path only): a COMMIT
     * agent submitting a mismatched `id:` on an OUT-of-glob page files a PROPOSAL, not a 422 — the rename surfaces to
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
        }
    }

    override fun create(principal: Principal, intent: CreateIntent): WriteOutcome {
        val grant = policy.checkCreate(principal, intent.path.value)
        return writePipeline.create(grant, intent)
    }

    override fun writeAsset(
        principal: Principal,
        pageId: PageId,
        filename: String,
        bytes: ByteArray,
        hasher: (ByteArray) -> String,
    ): AssetWriteOutcome {
        // EDIT-gate on the page id (the asset belongs to the page); the grant authorizes the asset write AND the
        // internal post-write rebuild (the rebuild is part of the write, reached via the ungated no-arg overload).
        val grant = policy.checkEdit(principal, pageId.value)

        // Resolve the page's folder from the published snapshot; an unknown id is a missing page.
        val page = indexBuilder.current.byId[pageId] ?: return AssetWriteOutcome.PageMissing

        // Snapshot membership ≠ disk reality: re-check the page file on disk so we don't write an asset (and
        // return Created) for a page whose .md was externally deleted since the last rebuild. A throwing read is a
        // transient FS fault (Unreadable), not a missing page.
        val pageStillOnDisk = try {
            contentStore.read(page.path) != null
        } catch (e: Exception) {
            logger.warn(e) { "stale-page re-check failed reading '${page.path.value}'; treating as unreadable" }
            return AssetWriteOutcome.Unreadable
        }
        if (!pageStillOnDisk) return AssetWriteOutcome.PageMissing

        // The asset path = the page's folder + the validated segment (childOf throws only on a bad segment, which
        // the route's filename validator already excluded).
        val assetPath = TreePath.childOf(page.path.parent, filename)

        return when (val result = contentStore.writeAssetExclusive(grant, assetPath, bytes, hasher)) {
            is CreateResult.Created -> {
                // Make the asset reachable: a full rebuild puts it in current.assets. A throw leaves the bytes
                // durably on disk but unindexed (the route's 503). Uses the UNGATED rebuild (part of the write).
                try {
                    indexBuilder.rebuild()
                } catch (e: Exception) {
                    logger.error(e) { "asset written but rebuild failed for '${assetPath.value}'; bytes are durable" }
                    return AssetWriteOutcome.WrittenButUnindexed(assetPath)
                }
                AssetWriteOutcome.Created(
                    path = assetPath,
                    url = indexBuilder.current.assetUrl(assetPath),
                    contentHash = result.newHash,
                )
            }
            is CreateResult.Exists -> {
                // Self-heal a prior written-but-unindexed orphan: if the bytes are on disk but the asset is NOT in
                // current.assets, best-effort rebuild FIRST so it becomes reachable on this retry. A failing
                // rebuild here must NOT turn the 409 into a 500 (runCatching). A genuine duplicate skips it.
                if (result.path !in indexBuilder.current.assets) {
                    runCatching { indexBuilder.rebuild() }
                }
                AssetWriteOutcome.Exists(result.path)
            }
            is CreateResult.ParentMissing -> AssetWriteOutcome.PageMissing
            is CreateResult.Rejected -> AssetWriteOutcome.Rejected(result.reason)
            is CreateResult.Unreadable -> AssetWriteOutcome.Unreadable
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

        /** P5: the deterministic rationale stamped on an auto-degraded proposal (a single-line literal — no `\n`). */
        const val DEGRADE_RATIONALE =
            "Auto-degraded: an agent direct commit fell outside agentDirectCommit.globs and was filed as a proposal for review."

        /** The single frontmatter id-detection grammar (lenient decode — the id-inspection trap is closed in W3a). */
        val PATCHER = FrontmatterPatcher()

        /**
         * Two raw `id:` line values (each from [FrontmatterPatcher.readIdValue], surrounding quotes NOT stripped)
         * denote the SAME identity iff they parse to the same canonical [PageId], OR — when one or both are not a
         * bare UUID (`id: "<uuid>"`, garbage, or absent) — they are the byte-identical raw string. The UUID arm makes
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

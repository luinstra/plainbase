package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal

/**
 * The guarded MUTATING surface (A3, the choke point). Every method takes a [Principal], calls the matching
 * `PolicyService.check*` FIRST (which mints an unforgeable typed grant on success, records the pre-effect audit
 * row, and throws [AccessDenied] on deny), then delegates to the raw mutator WITH the grant. The mutator REQUIRES
 * the grant — so a bypassed check is a COMPILE error. The `action`+`resource` pair is INTRINSIC to each method
 * (`save` is always EDIT/the page id, `create` always CREATE/"page", `writeAsset` EDIT/the asset path,
 * `rescan`/`reindex` MANAGE/"admin") — the route never passes an action.
 *
 * The impl ([com.plainbase.frameworks.ktor.GuardedMutatingFacade]) holds the raw [WritePipeline]/`ContentStore`/
 * [IndexBuilder] + [PolicyService] as PRIVATE deps and owns the [AccessDenied] → 401/403 mapping; no route can
 * reach the raw mutators.
 */
interface MutatingFacade {

    /**
     * EDIT: `PolicyService.checkEdit` FIRST (audited — a denied save writes the denied-EDIT row BEFORE any read),
     * THEN the snapshot resolution + the PB-WRITE-1 id-tamper validation + `WritePipeline.write(grant, …)` — all
     * inside the facade so the route NEVER does a read-check ahead of the audited edit-check. The route hands the
     * RAW [SaveRequest] (id + base_hash + the exact submitted bytes); the facade resolves the page's current path
     * from the snapshot internally and returns a [SaveResult] the route maps to status (a denied edit → the facade
     * throws `AccessDenied` BEFORE this returns; an allowed edit on a missing page → [SaveResult.PageNotFound]).
     */
    fun save(principal: Principal, request: SaveRequest): SaveResult

    /** CREATE: `PolicyService.checkCreate` then `WritePipeline.create(grant, intent)`. */
    fun create(principal: Principal, intent: CreateIntent): WriteOutcome

    /**
     * EDIT: the WHOLE asset write under one check — resolve the page's folder from the snapshot, the stale-page
     * recheck, the no-clobber `ContentStore.writeAssetExclusive(grant, …)`, and (on a fresh/orphan write) the
     * post-write `IndexBuilder.rebuild()` that makes the asset reachable. Returns an [AssetWriteOutcome] the route
     * maps to status — all raw-mutator/snapshot access stays INSIDE the facade impl. [filename] is the
     * route-validated single segment (the facade composes the path); [hasher] is the frozen content hash.
     */
    fun writeAsset(
        principal: Principal,
        pageId: PageId,
        filename: String,
        bytes: ByteArray,
        hasher: (ByteArray) -> String,
    ): AssetWriteOutcome

    /** MANAGE: `PolicyService.checkManage` then `IndexBuilder.rebuild(grant)` (the §A5 rescan). */
    fun rescan(principal: Principal): PageIndex

    /**
     * MANAGE: `PolicyService.checkManage` (throws `AccessDenied` on deny) then the §A5 single-flighted
     * `IndexBuilder.rebuildSearchIndex(grant)`. The single-flight flag lives INSIDE the impl (never exposed to a
     * route): a concurrent call returns [ReindexResult.InFlight]; otherwise [ReindexResult.Done] with the page
     * count. Blocking JDBC — the route runs this on `Dispatchers.IO`.
     */
    fun reindex(principal: Principal): ReindexResult
}

/**
 * The RAW input to [MutatingFacade.save] (PB-WRITE-1 PUT): the path-param [pageId] (the identity authority, R1),
 * the `If-Match` [baseHash], and the EXACT submitted document [bytes]. The facade resolves the page's current
 * path from the snapshot INTERNALLY — the route never reads it ahead of the audited edit-check (so a denied save
 * is audited as a denied EDIT, not swallowed by an unaudited read).
 *
 * [author]/[committer] (P1b) carry the optional git attribution the apply coordinator supplies (the proposer +
 * approver); they DEFAULT to null so the existing PUT route constructs a [SaveRequest] unchanged (server identity).
 */
class SaveRequest(
    val pageId: PageId,
    val baseHash: String,
    val bytes: ByteArray,
    val author: CommitIdentity? = null,
    val committer: CommitIdentity? = null,
    val origin: WriteOrigin = WriteOrigin.DIRECT_PUT,
)

/**
 * P5: which write entrypoint a [SaveRequest] came from. The agent direct-commit decision (WI-4) is consulted ONLY for
 * [DIRECT_PUT]; a [PROPOSAL_APPLY] write carries already-approved, already-reviewed content and ALWAYS direct-writes
 * through the pipeline, bypassing the glob decision REGARDLESS of the caller's principal type/mode (the apply path can
 * pass a [Principal.Agent] in `auth.mode=off`, where everyone is permitted — finding #11).
 *
 * SECURITY INVARIANT: `origin` is SERVER-SET ONLY — [SaveRequest] is never deserialized from a request body (it is not
 * `@Serializable`; the PUT route constructs it server-side), so an agent cannot smuggle [PROPOSAL_APPLY] over the wire
 * to bypass the glob gate. A future refactor that made [SaveRequest] `@Serializable` would silently open exactly that
 * bypass — do NOT.
 */
enum class WriteOrigin { DIRECT_PUT, PROPOSAL_APPLY }

/**
 * The outcome of [MutatingFacade.save]. A DENIED edit never reaches here — the facade's `checkEdit` throws
 * `AccessDenied` (audited) first. The two facade-resolved pre-pipeline outcomes ([PageNotFound], [IdMismatch])
 * are split from [Written] so the route maps each to its frozen status WITHOUT doing its own read.
 */
sealed interface SaveResult {

    /** The id is absent from the published snapshot (the audited edit was allowed, but there is no such page) → 404. */
    data object PageNotFound : SaveResult

    /** The submitted body's frontmatter `id:` denotes a DIFFERENT identity than the page's on-disk id → 422 (a rename). */
    data object IdMismatch : SaveResult

    /** The edit reached the pipeline; [outcome] is mapped through the frozen `toWire`. */
    data class Written(val outcome: WriteOutcome) : SaveResult

    /** P5: an agent COMMIT write OUTSIDE `agentDirectCommit.globs` was degraded to a proposal (202 Accepted). */
    data class DegradedToProposal(val proposalId: ProposalId, val unifiedDiff: String) : SaveResult

    /** P5: the degrade's `proposeEdit` hit a stale base_hash / missing target → 400 stale_base (no proposal stored). */
    data object DegradeStaleBase : SaveResult
}

/** The outcome of [MutatingFacade.reindex] — `Done` with the rebuilt page count, or `InFlight` (the §A5 409). */
sealed interface ReindexResult {
    data class Done(val pages: Int) : ReindexResult

    data object InFlight : ReindexResult
}

/**
 * The outcome of [MutatingFacade.writeAsset] — the facade's own type so the asset route maps it to status without
 * touching a raw mutator. It collapses the `CreateResult` variants plus the post-write rebuild result:
 *  - [Created] — the bytes landed AND the post-write rebuild published them; [url] is the reachable asset URL.
 *  - [WrittenButUnindexed] — the bytes are durably on disk but the post-write rebuild threw (→ the route's 503).
 *  - [Exists] — a file already occupies the path ([path] is the real attempted target); the orphan self-heal
 *    rebuild (if any) already ran inside the facade.
 *  - [PageMissing] — the page id is unknown, or its folder vanished on disk (→ 404).
 *  - [Rejected] — the location can never hold content (→ 400).
 *  - [Unreadable] — the write/recheck threw on a transient FS fault (→ 503).
 */
sealed interface AssetWriteOutcome {
    data class Created(val path: TreePath, val url: String, val contentHash: String) : AssetWriteOutcome

    data class WrittenButUnindexed(val path: TreePath) : AssetWriteOutcome

    data class Exists(val path: TreePath) : AssetWriteOutcome

    data object PageMissing : AssetWriteOutcome

    data class Rejected(val reason: String) : AssetWriteOutcome

    data object Unreadable : AssetWriteOutcome
}

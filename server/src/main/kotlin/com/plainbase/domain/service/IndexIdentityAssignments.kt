package com.plainbase.domain.service

import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.Witness

/** Resolves the identity assignments for one observed index pass and makes those assignments durable. */
internal class IndexIdentityAssignments(
    private val idMap: IdMapRepository,
    private val identity: PageIdentityService,
    private val patcher: FrontmatterPatcher,
) {
    /**
     * Path-keyed identity over the in-hand bytes uses the same precedence/duplicate seam as `AdoptionPass`
     * RECORD, run ONCE globally across all sources in a deterministic order: roots by rank, then within
     * each root frontmatter-carrying drafts first (`precedenceOrdered`), then by path. Rank orders the
     * roots and nothing else - it picks no id winner (ADR-0012).
     *
     * **RESOLVE THE WHOLE CORPUS, THEN BIND IT** - the `AdoptionPass` two-phase split, for the same
     * reason and now literally the same seam. Binding INLINE, as this used to, made the loser issue
     * UNRECORDABLE for one specific WITHIN-root loser: a page that ends up with NO frontmatter id of its own and
     * whose `id_map` row is swept out from under it mid-pass. The winner's key-complete bind DELETES that row on
     * its way through, so when the loser's own draft came up for resolution its `mappedId` read back null - and
     * a page with no frontmatter id and no mapping is not a duplicate, it is a VIRGIN PAGE. It minted a fresh id,
     * silently, so the `/p/{root}/{id}` permalink its readers held stopped naming it, with no `DuplicateId` issue
     * recorded anywhere. A durable permalink reassignment with no record is precisely the outcome the
     * loser-behalf issue recording exists to make impossible.
     *
     * **The reachable shape of that loser is narrower than it used to be, and naming it precisely matters** -
     * this paragraph is the most-cited justification for the split, and a justification that has quietly become
     * unreachable is how a defence gets refactored away. Since [BindingVisibility.isOwner] gained the
     * materialized-aware path-reuse gate, an UNMATERIALIZED owner is never displaced at all (its file was never
     * expected to carry the id, so witnessing it carry none confirms nothing), which used to be the headline
     * example here and no longer occurs. What remains reachable is the MATERIALIZED page whose `id:` line was
     * STRIPPED externally: the gate correctly stops treating it as the owner, a claimant takes the id, its row is
     * swept, and it then has neither frontmatter id nor mapping - a virgin page, silently minted. (A file whose
     * `id:` was REWRITTEN to some other valid id is not this case: it resolves on the frontmatter arm under its
     * new id and is never a virgin page.)
     *
     * Resolving first fixes it at the root: every draft's `mappedId` is read against the id_map as it stood
     * BEFORE this pass touched it, so the beaten owner still sees the contested id, `PageIdentityService`
     * reaches its owner check on the id_map arm (the arm its doc says an inline-binding pass can never reach),
     * and the loser reassigns WITH its issue. The binds then replay the resolved plan in the same rank-then-frontmatter-then-path
     * order, so the winner's key-complete bind still lands before the loser's row is rewritten.
     */
    fun resolveIdentities(
        scans: List<SourceScan>,
        witnessed: Map<RootedPath, Witness>,
        scannedRoots: Set<RootName>,
        registeredRoots: Set<RootName>,
        // Collects what this pass RAISED, so [IndexBuilder.rebuild] can warn about it. Deliberately not re-read
        // `idMap.issues()`: that decode path deliberately has no production caller, and giving it one would turn a
        // stale mid-branch row into a crash on every rebuild.
        raised: MutableList<IdentityIssue>,
    ): Map<RootedPath, Identity> {
        // The ONE supersession rule, built once and handed to BOTH the resolver below and every bind it
        // produces - so the plan the pass makes and the writes the repository will accept cannot disagree.
        //
        // [Supersession.proven] is deliberately NOT passed, and the reason is an ORDERING rather than a rule: the
        // proof-apply transaction has ALREADY run by the time we get here, so every binding a proof covered is gone
        // from `id_map` and there is no incumbent left for that arm to displace. It would be vacuous, and a vacuous
        // authority argument is worse than none - it reads like a working safety net.
        //
        // **So do not move `applyProofs` after this call and expect `proven` to carry the weight: nothing passes it.**
        // (The order is load-bearing in the other direction too - it is what lets the tombstone arm of `ownerOf` below
        // see THIS pass's own retirements, so a copied or restored file carrying a just-retired id is refused rather
        // than handed a dead page's permalink.)
        val supersession = Supersession(witnessed = witnessed.keys, scannedRoots = scannedRoots, registeredRoots = registeredRoots)
        val claimed = HashMap<RootedPageId, RootedPath>()
        val resolved = LinkedHashMap<RootedPath, PageIdentityService.Assignment>() // rank-then-frontmatter-then-path = the bind order
        for (scan in scans) {
            // Within one root, a valid frontmatter id travels with its page before an unmaterialized
            // newcomer at the vacated path can reuse the stale id_map row. The sort is stable, so
            // duplicate frontmatter claims retain path order, and the outer loop preserves root rank.
            val precedenceOrdered = scan.drafts.sortedBy { draft ->
                patcher.readIdValue(draft.bytes)?.let(PageId::of) == null
            }
            for (draft in precedenceOrdered) {
                val path = RootedPath(scan.root, draft.file.path)
                val assignment = identity.resolve(
                    path = path,
                    rawFrontmatterId = patcher.readIdValue(draft.bytes),
                    // Read against the PRE-PASS id_map (nothing has been bound yet), which is what lets a beaten
                    // id_map-only owner still see the contested id and lose it with an issue rather than silently.
                    mappedId = idMap.find(path)?.id,
                    // Within-run claims first, then id_map bindings classified by the shared D16 rule - and then
                    // the TOMBSTONES, because a retired id is RESERVED FOREVER within its root: it belongs to the
                    // page that earned it and to nothing else. All three arms are ROOT-SCOPED to this draft's own
                    // root: a cross-root duplicate is legal, so ownerOf never returns an
                    // owner in another root and the same id living in two roots is not a contest.
                    ownerOf = { id ->
                        claimed[RootedPageId(path.root, id)]
                            ?: idMap.bindingInRoot(path.root, id)
                                ?.takeIf { binding ->
                                    // isLive + the path-reuse gate, as ONE shared question - see [BindingVisibility.isOwner]
                                    // for why an unmaterialized owner carrying no `id:` is witnessing itself, not reused.
                                    BindingVisibility.isOwner(binding, witnessed, scannedRoots, registeredRoots, supersession)
                                }
                                ?.path
                            ?: idMap.retiredAt(path.root, id)?.path
                    },
                )
                claimed[RootedPageId(path.root, assignment.id)] = path
                resolved[path] = assignment
            }
        }

        // The plan is checked BEFORE this helper makes any bind durable. Effects the coordinator performed before
        // this call may already exist; if uniqueness fails, this helper performs no binds and the last-good snapshot
        // remains current.
        requireDistinctIds(resolved.mapValues { (_, assignment) -> assignment.id })

        val identities = HashMap<RootedPath, Identity>()
        for ((path, assignment) in resolved) {
            val materialized = assignment.source == PageIdentityService.Source.FRONTMATTER
            // Rank-then-frontmatter-then-path order (the map's insertion order): the winner's key-complete bind sweeps the loser's
            // stale row BEFORE the loser rebinds itself, so no page ever reads back an identity this pass has
            // already reassigned. The ISSUE lands with the bind that supersedes it, never after it or not at all.
            //
            // The bind is handed the SAME [Supersession] the resolve above ran under, so a REFUSAL means the two
            // disagreed - a rule-drift bug, not a data condition. It is checked rather than ignored for the same
            // reason [requireDistinctIds] is: `duplicate()` reused a `mappedId` blind for a whole release and
            // nothing between there and the disk noticed. bind() refuses BEFORE it writes anything. Earlier
            // successful binds and coordinator effects may already exist, while the old holder remains current.
            val outcome = idMap.bind(path, assignment.id, materialized = materialized, supersession = supersession)
            check(outcome is BindOutcome.Bound) {
                "identity resolution awarded ${assignment.id.value} to ${path.path.value} in '${path.root}', and the bind " +
                    "REFUSED it: ${(outcome as BindOutcome.Refused).let {
                        "held by ${it.heldBy}${if (it.retired) " (retired)" else ""}"
                    }}. " +
                    "The resolver and the bind gate disagree about who owns that id - no supersession is safe under that."
            }
            assignment.issue?.let { record(raised, it) }
            identities[path] = Identity(assignment.id, materialized)
        }
        return identities
    }

    /** Persists one identity issue, then appends it to the caller's accumulator immediately. */
    private fun record(into: MutableList<IdentityIssue>, issue: IdentityIssue) {
        idMap.record(issue)
        into += issue
    }
}

package com.plainbase.domain.service

import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.Witness

/** Resolves one pass's identities before making the assignments durable. */
internal class IndexIdentityAssignments(
    private val idMap: IdMapRepository,
    private val identity: PageIdentityService,
    private val patcher: FrontmatterPatcher,
) {
    /**
     * Resolves all drafts before binding, using the `AdoptionPass` RECORD order: roots by rank, then within each root
     * frontmatter-carrying drafts first, then by path. Reading `mappedId` before any binds preserves the beaten owner's
     * identity evidence, so resolution computes its duplicate issue before the winner's bind removes the mapping.
     * Identity is root-scoped (ADR-0012); rank orders roots but never transfers an id between them.
     */
    fun resolveIdentities(
        scans: List<SourceScan>,
        witnessed: Map<RootedPath, Witness>,
        scannedRoots: Set<RootName>,
        registeredRoots: Set<RootName>,
        // Accumulate this pass's issues for [IndexBuilder.rebuild]; do not decode the legacy `idMap.issues()` rows here.
        raised: MutableList<IdentityIssue>,
        allowUnchangedConfirmation: Boolean,
    ): Map<RootedPath, Identity> {
        // Share one [Supersession] between resolution and binding. Proofs are applied before this call, so their
        // bindings are gone; that ordering also lets the tombstone arm see this pass's retirements.
        val supersession = Supersession(witnessed = witnessed.keys, scannedRoots = scannedRoots, registeredRoots = registeredRoots)
        val claimed = HashMap<RootedPageId, RootedPath>()
        val resolved = LinkedHashMap<RootedPath, PageIdentityService.Assignment>() // rank-then-frontmatter-then-path = the bind order
        for (scan in scans) {
            // Stable order keeps a valid frontmatter id with its page before an unmaterialized path reuse; the outer
            // loop preserves root rank.
            val precedenceOrdered = scan.drafts.sortedBy { draft ->
                patcher.readIdValue(draft.bytes)?.let(PageId::of) == null
            }
            for (draft in precedenceOrdered) {
                val path = RootedPath(scan.root, draft.file.path)
                val assignment = identity.resolve(
                    path = path,
                    rawFrontmatterId = patcher.readIdValue(draft.bytes),
                    mappedId = idMap.find(path)?.id,
                    // Claims, live bindings, and tombstones are all root-scoped: cross-root duplicates are legal and
                    // retired ids remain reserved within their own root.
                    ownerOf = { id ->
                        claimed[RootedPageId(path.root, id)]
                            ?: idMap.bindingInRoot(path.root, id)
                                ?.takeIf { binding ->
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

        // Check uniqueness before binding. Earlier coordinator effects may remain; no rollback is promised.
        requireDistinctIds(resolved.mapValues { (_, assignment) -> assignment.id })

        val confirmed = allowUnchangedConfirmation &&
            resolved.values.all { assignment ->
                assignment.source == PageIdentityService.Source.FRONTMATTER && assignment.issue == null
            } &&
            idMap.confirmUnchangedBindings(
                resolved.map { (path, assignment) -> IdBinding(path, assignment.id, materialized = true) },
            )
        if (confirmed) {
            return resolved.mapValues { (_, assignment) -> Identity(assignment.id, materialized = true) }
        }

        val identities = HashMap<RootedPath, Identity>()
        for ((path, assignment) in resolved) {
            val materialized = assignment.source == PageIdentityService.Source.FRONTMATTER
            // Replay the resolved order so a winner's bind removes a stale row before the loser is rebound. A refusal
            // means resolution and binding disagree. Earlier binds may remain durable while the old snapshot stays current.
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

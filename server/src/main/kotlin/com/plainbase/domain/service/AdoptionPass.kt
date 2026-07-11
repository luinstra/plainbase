package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The adoption pass (§5.2, chunk 4b): scan the content tree, resolve every page's identity through
 * the 4a precedence/duplicate logic ([PageIdentityService]), persist the resulting id_map bindings
 * and issues, and — in [Mode.MATERIALIZE] only — write `id:` lines into accepted pages through the
 * surgical patcher and the ContentStore's atomic write.
 *
 * **One tree, rooted keys (C2):** the pass adopts ONE root ([root] - its [ContentStore] is one tree
 * by construction), but identity is global: a binding under another root is classified by the
 * shared [BindingVisibility] rule over [registeredRoots] (ADR-0011 D16), so a single-root adopt
 * never silently steals a configured-but-unscanned root's id - the D17 rank contest decides, and a
 * win over an unscanned owner records the loser-behalf [IdentityIssue.CrossRootDuplicateId]
 * in-pass. [Report]/[PageReport] keep bare [TreePath]s: the report is per-tree by construction and
 * the `adopt` output contract is pinned.
 *
 * **Read-only first index (frozen policy):** [Mode.RECORD] performs ZERO ContentStore writes —
 * unidentified pages get id_map rows only. [Mode.PREVIEW] (`adopt --write-ids --dry-run`) writes
 * nothing at all, file or row: it reports exactly what a MATERIALIZE run would do, including every
 * would-refuse page with its rule-naming reason — the §A3 asymmetric-freeze measurement input.
 *
 * **Materialization (frozen order):** patcher output is written via the ContentStore atomic write,
 * THEN the binding is marked materialized — an interruption between the two re-resolves cleanly on
 * the next run (the file's own frontmatter id wins by precedence). `Refused` records an
 * [IdentityIssue.PatchRefused] with the patcher's rule-naming message; the page keeps its map
 * identity. A file already carrying a column-0 `id` key that is NOT this page's assigned id (a
 * copied duplicate, or a shape-invalid value) comes back `AlreadyPresent` and is never overwritten —
 * reconciling frontmatter-vs-map is exactly this pass's policy, and the policy is "keep the map
 * identity, surface the issue".
 *
 * **Write durability (debate item 9):** every ContentStore write is announced through the
 * `logIntent` callback (path + id) BEFORE it is performed, so an interrupted run leaves a log from
 * which the completed/pending split is reconstructable; idempotence (a second MATERIALIZE run
 * performs zero writes) makes re-running safe. On NFS/SMB the underlying atomic rename falls back
 * to copy+delete (not crash-atomic) — the caveat the `adopt` output documents.
 *
 * Git-mode single batched commit for `adopt --write-ids` is Phase 3 (no Git layer exists yet) — a
 * deferred hook, not a dropped requirement.
 *
 * Pure domain orchestration over ports; pages are scanned in path order so duplicate resolution and
 * the intent log are deterministic.
 */
class AdoptionPass(
    private val contentStore: ContentStore,
    private val idMap: IdMapRepository,
    private val identity: PageIdentityService,
    private val patcher: FrontmatterPatcher,
    private val root: RootName,
    private val registeredRoots: Set<RootName>,
) {

    /** The one root this pass can see (D16: everything else is unscanned or detached). */
    private val scannedRoots = setOf(root)

    /** The three `adopt` modes — see the class header for the frozen write policy of each. */
    enum class Mode {
        /** Default `adopt`: id_map rows (and issues) only; zero ContentStore writes. */
        RECORD,

        /** `adopt --write-ids`: RECORD plus materialization of every accepted page. */
        MATERIALIZE,

        /** `adopt --write-ids --dry-run`: report only; no file writes, no db writes. */
        PREVIEW,
    }

    /** What happened (or would happen) to one page. */
    enum class Disposition {
        /** Identity lives in id_map only — the file was not (and would not be) touched. */
        MAPPED,

        /** The file already carries its assigned id; nothing to write. */
        ALREADY_MATERIALIZED,

        /** MATERIALIZE: the id line was written into the file. */
        MATERIALIZED,

        /** PREVIEW: a MATERIALIZE run would patch this page. */
        WOULD_MATERIALIZE,

        /** The patcher refused (rule-naming message in [PageReport.issues]); map identity kept. */
        REFUSED,
    }

    /** One page's resolved identity and outcome, plus any issues raised on the way. */
    data class PageReport(
        val path: TreePath,
        val id: PageId,
        val source: PageIdentityService.Source,
        val disposition: Disposition,
        val issues: List<IdentityIssue>,
    )

    /** The whole pass: per-page reports in scan (path) order. */
    data class Report(val mode: Mode, val pages: List<PageReport>) {

        val issues: List<IdentityIssue> get() = pages.flatMap { it.issues }

        fun pages(disposition: Disposition): List<PageReport> = pages.filter { it.disposition == disposition }
    }

    /**
     * Runs the pass in [mode]. [logIntent] is invoked (path + id) immediately BEFORE each
     * ContentStore write — the pre-write intent log the durability policy requires.
     */
    fun run(mode: Mode, logIntent: (TreePath, PageId) -> Unit = { _, _ -> }): Report {
        val pages = contentStore.scan().files
            .map { it.path }
            .filter { it.name.endsWith(".md") }
            .sortedBy { it.value }
        val livePaths = pages.mapTo(mutableSetOf()) { RootedPath(root, it) }
        val claimed = HashMap<PageId, RootedPath>()

        val reports = pages.map { path ->
            val bytes = checkNotNull(contentStore.read(path)) { "scanned page vanished before read: ${path.value}" }
            val rooted = RootedPath(root, path)
            val assignment = identity.resolve(
                path = rooted,
                rawFrontmatterId = patcher.readIdValue(bytes),
                mappedId = idMap.find(rooted)?.id,
                // Duplicate-detection seam: within-run claims first, then id_map bindings classified
                // by the shared D16 rule (scanned root live-iff-on-disk, configured-but-unscanned
                // untouchable, detached supersedable).
                ownerOf = { id ->
                    claimed[id] ?: idMap.pathOf(id)?.takeIf { BindingVisibility.isLive(it, livePaths, scannedRoots, registeredRoots) }
                },
            )
            claimed[assignment.id] = rooted
            adopt(mode, path, bytes, assignment, logIntent)
        }

        logger.info {
            "adoption pass ($mode): ${reports.size} page(s), " +
                "${reports.count { it.disposition == Disposition.MATERIALIZED }} materialized, " +
                "${reports.count { it.issues.isNotEmpty() }} with issues"
        }
        return Report(mode, reports)
    }

    private fun adopt(
        mode: Mode,
        path: TreePath,
        bytes: ByteArray,
        assignment: PageIdentityService.Assignment,
        logIntent: (TreePath, PageId) -> Unit,
    ): PageReport {
        val issues = mutableListOf<IdentityIssue>()
        assignment.issue?.let(issues::add)
        val idInFile = assignment.source == PageIdentityService.Source.FRONTMATTER

        if (mode != Mode.PREVIEW) {
            // D16 outcome two, adopt side: winning the rank contest against a registered-but-
            // unscanned owner deletes its row via the key-complete bind, so the loser-behalf issue
            // rides the bind's OWN transaction (the port's D16 atomicity contract; same natural key
            // the loser itself would record at its next rebuild - the UNIQUE upsert dedups) and
            // surfaces in this page's report. PREVIEW binds nothing, so nothing is superseded or
            // recorded.
            val superseded = assignment.supersededOwner
                ?.takeIf { it.root in registeredRoots && it.root !in scannedRoots }
                ?.let { IdentityIssue.CrossRootDuplicateId(id = assignment.id, kept = RootedPath(root, path), reassigned = it) }
            idMap.bind(RootedPath(root, path), assignment.id, materialized = idInFile, supersededOwnerIssue = superseded)
            superseded?.let(issues::add)
        }

        val disposition = when {
            idInFile -> Disposition.ALREADY_MATERIALIZED
            mode == Mode.RECORD -> Disposition.MAPPED
            else -> when (val result = patcher.patch(bytes, assignment.id)) {
                is FrontmatterPatcher.PatchResult.Patched ->
                    if (mode == Mode.PREVIEW) {
                        Disposition.WOULD_MATERIALIZE
                    } else {
                        // Intent BEFORE write (durability policy), write, THEN mark materialized.
                        logIntent(path, assignment.id)
                        contentStore.write(path, result.bytes)
                        idMap.markMaterialized(RootedPath(root, path))
                        Disposition.MATERIALIZED
                    }
                // A column-0 `id` key whose value is not this page's assigned id — never overwritten.
                FrontmatterPatcher.PatchResult.AlreadyPresent -> Disposition.MAPPED
                is FrontmatterPatcher.PatchResult.Refused -> {
                    issues += IdentityIssue.PatchRefused(root, path, result.message)
                    Disposition.REFUSED
                }
            }
        }

        if (mode != Mode.PREVIEW) {
            issues.forEach(idMap::record)
        }
        return PageReport(path, assignment.id, assignment.source, disposition, issues.toList())
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

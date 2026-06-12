package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The adoption pass (§5.2, chunk 4b): scan the content tree, resolve every page's identity through
 * the 4a precedence/duplicate logic ([PageIdentityService]), persist the resulting id_map bindings
 * and issues, and — in [Mode.MATERIALIZE] only — write `id:` lines into accepted pages through the
 * surgical patcher and the ContentStore's atomic write.
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
) {

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
        val livePaths = pages.toSet()
        val claimed = HashMap<PageId, TreePath>()

        val reports = pages.map { path ->
            val bytes = checkNotNull(contentStore.read(path)) { "scanned page vanished before read: ${path.value}" }
            val assignment = identity.resolve(
                path = path,
                rawFrontmatterId = patcher.readIdValue(bytes),
                mappedId = idMap.find(path)?.id,
                // Duplicate-detection seam: within-run claims first, then live id_map bindings. A
                // binding whose path is gone from the tree is a moved file, not a duplicate owner.
                ownerOf = { id -> claimed[id] ?: idMap.pathOf(id)?.takeIf(livePaths::contains) },
            )
            claimed[assignment.id] = path
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
            idMap.bind(path, assignment.id, materialized = idInFile)
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
                        idMap.markMaterialized(path)
                        Disposition.MATERIALIZED
                    }
                // A column-0 `id` key whose value is not this page's assigned id — never overwritten.
                FrontmatterPatcher.PatchResult.AlreadyPresent -> Disposition.MAPPED
                is FrontmatterPatcher.PatchResult.Refused -> {
                    issues += IdentityIssue.PatchRefused(path, result.message)
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

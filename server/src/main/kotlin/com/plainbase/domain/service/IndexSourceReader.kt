package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryCommandException
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException

/**
 * Reads one registered content source into an eager, passive [SourceScan]. The builder owns capture, proof handling,
 * identity, persistence, rendering, and publication; this class only materializes source data and buffers scan issues.
 * The reader leaves buffered issue recording and identity binding to the later coordinator after all materialized
 * source results are available; it does not own the coordinator's earlier capture or classifier repository reads.
 */
internal class IndexSourceReader(
    private val frontmatterParser: FrontmatterParser,
    private val absence: AbsenceClassifier,
    private val availability: RootAvailability,
    private val rootLoss: RootLossClassifier,
) {

    /**
     * Reads [root] unless its availability is sticky or the entry probe finds it gone. The entry probe is outside the
     * captured operation; only [scan] is classified as a scan failure, and a successful materialization is re-probed
     * before it is handed back to [IndexBuilder.rebuild]. A non-null result may have [SourceScan.complete] false and
     * still participates in the coordinator; null means that the whole source was skipped.
     *
     * The classified exceptions follow this composite operation's collaborators: store scan/read I/O can raise
     * IOException, a classified RootDown becomes RootUnavailable, and the batched history read can raise
     * HistoryCommandException. RootUnavailable preserves its supplied root/reason; the other two re-probe before
     * deciding whether the root is gone or merely failed this pass. Do not widen failure classification to every
     * Exception: unexpected parser/URL or other programming faults must propagate, rather than become skip-and-carry.
     * Buffered issue recording and identity binding happen later in the coordinator.
     */
    fun read(root: Root, store: ContentStore, history: HistoryProvider): SourceScan? {
        val rootName = root.name
        return when {
            !availability.current().isAvailable(rootName) -> {
                logger.warn { "root '$rootName' is unavailable; skipping its scan and carrying its last-good section forward" }
                null
            }

            rootLoss.markIfGone(rootName, store) ->
                skipAndCarry(rootName, "its backing tree is not traversable")

            else -> runCatching { scan(root, store, history) }.fold(
                onSuccess = { scan ->
                    when {
                        rootLoss.markIfGone(rootName, store) ->
                            skipAndCarry(rootName, "it vanished while being scanned, so the tree it handed back is not a corpus")
                        else -> scan
                    }
                },
                onFailure = { failure ->
                    when (failure) {
                        is RootUnavailable -> {
                            availability.markUnavailable(failure.root, failure.reason)
                            logger.warn {
                                "root '$rootName' vanished mid-scan; skipping it and carrying its last-good section forward"
                            }
                            null
                        }
                        is IOException -> classifyScanFailure(root, store, failure)
                        is HistoryCommandException -> classifyScanFailure(root, store, failure)
                        else -> throw failure
                    }
                },
            )
        }
    }

    /**
     * The rebuild arm of the shared root-loss rule: a failed re-probe marks, skips, and carries; a live root keeps the
     * original failure visible and retries on the next pass. Unexpected parser/URL faults propagate. This helper only
     * classifies caught scan/history failures; buffered issue recording and identity binding remain later coordinator
     * effects, after all materialized source results are in hand.
     */
    private fun classifyScanFailure(root: Root, store: ContentStore, failure: Exception): SourceScan? {
        val rootName = root.name
        if (rootLoss.markIfGone(rootName, store)) {
            return skipAndCarry(rootName, "it vanished while being scanned (${failure.message})")
        }
        return skipOnLiveFailure(root, failure)
    }

    /**
     * A live root whose scan failed: fail that root's pass, not [IndexBuilder.rebuild]. The root is deliberately not
     * marked unavailable: it is still present, the operator can fix the permission or repository in place, and the
     * next pass retries it. No issue is persisted from this incomplete materialization.
     */
    private fun skipOnLiveFailure(root: Root, failure: Exception): SourceScan? {
        val where = root.localPath?.let { " at $it" }.orEmpty()
        logger.warn(failure) {
            "root '${root.name}'$where is still there but its scan FAILED (${failure.message}); skipping it and " +
                "carrying its last-good section forward - the failed scan grants no new deletion authority; current " +
                "durable retired-unbound search identities may still be reconciled, the other roots still index, and " +
                "the next pass retries it"
        }
        return null
    }

    /** The loss is already published; this is the skip. */
    private fun skipAndCarry(root: RootName, detail: String): SourceScan? {
        logger.warn {
            "root '$root' is no longer available ($detail); skipping its scan and carrying its last-good section " +
                "forward - the loss grants no new deletion authority; previously committed retired-and-unbound search " +
                "identities may still be reconciled, and it will serve 503 until it is restored and the server restarted"
        }
        return null
    }

    /**
     * Scans one source end-to-end: files, frontmatter, per-root URLs, and one batched last-commit read. This is eager
     * source materialization: it performs source I/O but does not record issues or bind identities, and parser/URL
     * faults remain visible to the caller.
     */
    private fun scan(root: Root, store: ContentStore, history: HistoryProvider): SourceScan {
        val rootName = root.name
        val scan = store.scan()

        var pageReadsComplete = true
        val unread = mutableSetOf<TreePath>()
        val drafts = scan.files
            .filter { it.path.name.endsWith(".md") }
            .sortedBy { it.path.value }
            .mapNotNull { file ->
                // The classifier is bound-only: an uncertain read race is not a deletion fact. It stays in unread and
                // limbo, while a confirmed absence only withholds read evidence for the matching object proof.
                val bytes = when (val read = absence.read(store, RootedPath(rootName, file.path))) {
                    is ContentRead.Bytes -> read.bytes
                    ContentRead.RootDown -> throw RootUnavailable(rootName, UnavailableCause.VANISHED)
                    ContentRead.AbsenceUnknown -> {
                        pageReadsComplete = false
                        unread += file.path
                        logger.warn {
                            "page ${file.path.value} in '$rootName' vanished between the walk and the read; it is NOT witnessed " +
                                "this pass and its durable row goes to LIMBO - nothing is deleted for it"
                        }
                        return@mapNotNull null
                    }
                    ContentRead.ConfirmedAbsent -> {
                        pageReadsComplete = false
                        logger.warn {
                            "page ${file.path.value} in '$rootName' vanished between the walk and the read; it was never indexed"
                        }
                        return@mapNotNull null
                    }
                }
                Draft(file, bytes, frontmatterParser.parse(bytes))
            }
        val assets = scan.files.filterNot { it.path.name.endsWith(".md") }.map { it.path }.toSet()
        // Preserve ContentFile.rawName verbatim. Converting it through TreePath would normalize NFC and erase the
        // raw-name distinction that CanonicalUrlBuilder uses to select a collision winner and report its loser.
        val urls = CanonicalUrlBuilder.build(
            root = rootName,
            pages = drafts.map { CanonicalUrlBuilder.PageInput(it.file.path, it.file.rawName, it.frontmatter.scalar("slug")) },
            folders = scan.folders,
        )
        // Read last commits once per source, never once per page. NoOp returns an empty map, so non-Git and
        // uncommitted pages retain null commit metadata. This is the last potentially failing source-I/O operation
        // inside scan; return its buffered issues only after it succeeds. The reader still re-probes successful
        // materialization, and the coordinator records issues only after all source materializations finish.
        val commits = history.lastCommits(drafts.map { it.file.path })
        return SourceScan(
            root = rootName,
            drafts = drafts,
            folders = scan.folders,
            assets = assets,
            urls = urls,
            commits = commits,
            issues = scan.issues.map { it.toIdentityIssue(rootName) } + urls.issues,
            complete = scan.complete,
            pageReadsComplete = pageReadsComplete,
            unread = unread,
        )
    }

    // Preserve the raw loser name for the collision diagnostic. Converting it through TreePath would normalize NFC
    // and erase the raw-name distinction that the collision report is required to retain.
    private fun ScanIssue.toIdentityIssue(root: RootName): IdentityIssue = when (this) {
        is ScanIssue.PathCollision -> IdentityIssue.PathCollision(root = root, keptPath = path, loserRawName = loserRawName)
    }

    private companion object {
        // Keep the old category so established operator filters and diagnostic grouping survive relocation.
        private val logger = KotlinLogging.logger("com.plainbase.domain.service.IndexBuilder")
    }
}

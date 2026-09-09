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

/** Reads one source into an eager [SourceScan]; the coordinator owns proof, identity, persistence, and publication. */
internal class IndexSourceReader(
    private val frontmatterParser: FrontmatterParser,
    private val absence: AbsenceClassifier,
    private val availability: RootAvailability,
    private val rootLoss: RootLossClassifier,
) {

    /**
     * Reads [root] with entry and exit probes. A non-null incomplete [SourceScan] still participates; null means the
     * source was skipped and carried. Scan/history I/O failures are re-probed to distinguish root loss from a live
     * scan failure; parser, URL, and other unexpected failures propagate.
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

    /** A failed re-probe marks, skips, and carries; a live root keeps the failure visible for the next pass. */
    private fun classifyScanFailure(root: Root, store: ContentStore, failure: Exception): SourceScan? {
        val rootName = root.name
        if (rootLoss.markIfGone(rootName, store)) {
            return skipAndCarry(rootName, "it vanished while being scanned (${failure.message})")
        }
        return skipOnLiveFailure(root, failure)
    }

    /** Skips a live root's failed scan without marking it unavailable, so the next pass can retry it. */
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

    /** Skips a root already classified as unavailable; its last-good section is carried. */
    private fun skipAndCarry(root: RootName, detail: String): SourceScan? {
        logger.warn {
            "root '$root' is no longer available ($detail); skipping its scan and carrying its last-good section " +
                "forward - the loss grants no new deletion authority; previously committed retired-and-unbound search " +
                "identities may still be reconciled, and it will serve 503 until it is restored and the server restarted"
        }
        return null
    }

    /** Materializes files, frontmatter, URLs, and one batched history read without recording or binding anything. */
    private fun scan(root: Root, store: ContentStore, history: HistoryProvider): SourceScan {
        val rootName = root.name
        val scan = store.scan()

        var pageReadsComplete = true
        val unread = mutableSetOf<TreePath>()
        val drafts = scan.files
            .filter { it.path.name.endsWith(".md") }
            .sortedBy { it.path.value }
            .mapNotNull { file ->
                // An uncertain read is buffered as unread and stays in limbo; a confirmed absence only withholds
                // read evidence for the matching object proof.
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
        // Keep rawName verbatim: TreePath would normalize Unicode and erase the collision diagnostic's loser name.
        val urls = CanonicalUrlBuilder.build(
            root = rootName,
            pages = drafts.map { CanonicalUrlBuilder.PageInput(it.file.path, it.file.rawName, it.frontmatter.scalar("slug")) },
            folders = scan.folders,
        )
        // Read commits once per source. Buffered issues are returned only after this last source-I/O operation
        // succeeds; the coordinator records them after all materializations finish.
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

    // Preserve the raw loser name; TreePath normalization would change the collision diagnostic.
    private fun ScanIssue.toIdentityIssue(root: RootName): IdentityIssue = when (this) {
        is ScanIssue.PathCollision -> IdentityIssue.PathCollision(root = root, keptPath = path, loserRawName = loserRawName)
    }

    private companion object {
        // Keep the existing logger category for operator filtering and diagnostic grouping.
        private val logger = KotlinLogging.logger("com.plainbase.domain.service.IndexBuilder")
    }
}

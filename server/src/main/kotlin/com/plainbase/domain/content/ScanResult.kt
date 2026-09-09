package com.plainbase.domain.content

/**
 * The outcome of a [ContentStore] tree scan: the indexed entries plus any [ScanIssue]s
 * that the scan surfaced (e.g. an NFC path collision).
 *
 * [files] holds exactly the entries that own their [TreePath] — collision *losers* are
 * excluded here and recorded only as issues (policy B3). Each [ContentFile] retains its
 * raw on-disk name (P4) so reads reach the winner's bytes, never a name re-derived from
 * the NFC path.
 */
data class ScanResult(
    val files: List<ContentFile>,
    val folders: List<ContentFolder>,
    val issues: List<ScanIssue>,
    /**
     * Did this walk see the whole tree or only part of it? Walk completeness is required evidence for inferred
     * absence; it is not by itself a deletion proof. An incomplete scan can still supply readable pages, but cannot
     * grant new absence authority for entries it did not reach. The OBJECT_LIST consumer additionally requires
     * successful reads of every selected Markdown candidate and its binding/proof checks.
     *
     * A filesystem walk is complete by construction (a tree it could not read RAISES; a tree that went away
     * mid-walk is caught by the root-loss probe), which is why the default is true. The object backend is the
     * caller that answers false: its reads are served from a local MIRROR, and a boot hydration that DEFERRED
     * an object (a transient GET/write failure) leaves that mirror missing pages the bucket still holds -
     * indistinguishable, to a walk, from pages the operator deleted.
     */
    val complete: Boolean = true,
)

/**
 * A problem detected during a scan that does not abort the scan but must be surfaced
 * (chunk 5 persists these as `IdentityIssue`s for the admin issues list).
 */
sealed interface ScanIssue {
    /** The single [TreePath] both colliding files normalize to. */
    val path: TreePath

    /**
     * Two distinct on-disk files normalize to one [TreePath] (B3). Possible only on a
     * normalization-preserving filesystem (Linux ext4 et al.). The deterministic winner is
     * the file whose raw filename bytes sort first (lexicographic unsigned-byte order); the
     * loser is excluded from the index and its content is unreachable through Plainbase —
     * which is exactly the condition this issue exists to surface.
     *
     * [winnerRawName] and [loserRawName] record both raw byte-forms (as filename strings)
     * so the issue is actionable.
     */
    data class PathCollision(
        override val path: TreePath,
        val winnerRawName: String,
        val loserRawName: String,
    ) : ScanIssue
}

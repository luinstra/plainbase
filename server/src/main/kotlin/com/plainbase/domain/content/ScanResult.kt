package com.plainbase.domain.content

/**
 * The outcome of a [ContentStore] tree scan: the indexed entries plus any [ScanIssue]s
 * that the scan surfaced (e.g. an NFC path collision).
 *
 * [files] holds exactly the entries that own their [TreePath] — collision *losers* are
 * excluded here and recorded only as issues (policy B3). [rawNames] is the retained
 * `TreePath -> raw on-disk name` mapping (P4) for every indexed file; reads of a file go
 * through this map, never through a name re-derived from the NFC path.
 */
data class ScanResult(
    val files: List<ContentFile>,
    val folders: List<ContentFolder>,
    val issues: List<ScanIssue>,
) {
    /** The retained `TreePath -> raw on-disk name` map (P4) for every indexed file. Computed once. */
    val rawNames: Map<TreePath, String> = files.associate { it.path to it.rawName }
}

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

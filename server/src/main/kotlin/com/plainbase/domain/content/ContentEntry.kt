package com.plainbase.domain.content

/**
 * A non-ignored entry discovered by a [ContentStore] scan.
 *
 * Every entry is addressed by an NFC-normalized [TreePath] (chunk 1.5). The scan also
 * retains, per file, the **raw on-disk name** of the entry's final segment — the byte-form
 * actually present on the filesystem — so a later read goes through that retained name
 * rather than re-deriving it from the NFC path (implementation note P4). On a
 * normalization-preserving filesystem, re-deriving the on-disk name from the NFC
 * [TreePath] would open the collision *loser* (or nothing) when the winner's raw name is
 * the non-NFC byte-form.
 */
sealed interface ContentEntry {
    /** The NFC-normalized, content-root-relative path of this entry. */
    val path: TreePath
}

/**
 * A regular file in the content tree (a Markdown page, an asset, a `.meta.yaml` sidecar).
 *
 * [rawName] is the final on-disk path segment's exact byte-form (decoded as the platform's
 * filename charset, which is UTF-8 on the supported platforms). It is usually equal to
 * `path.name`, but differs when the on-disk name is the non-NFC form of a precomposed
 * character — that is precisely the case P4 exists to handle: reads use [rawName], never
 * a name re-derived from [path].
 */
data class ContentFile(
    override val path: TreePath,
    val rawName: String,
) : ContentEntry

/**
 * A directory in the content tree. [meta] carries the parsed `_folder.yaml`, if present.
 */
data class ContentFolder(
    override val path: TreePath,
    val meta: FolderMeta? = null,
) : ContentEntry

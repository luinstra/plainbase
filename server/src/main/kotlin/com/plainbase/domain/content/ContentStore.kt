package com.plainbase.domain.content

/**
 * The single internal interface to the content tree (master plan §2.2): a small port over a
 * directory of Markdown files, assets, and metadata. The only implemented adapter is
 * `LocalContentStore` (java.nio); Git is detected *on top of* the same directory, never as a
 * second backend.
 *
 * The store speaks [TreePath] exclusively — no caller ever hands it a raw `String` path or a
 * filesystem `Path`. NFC normalization happens at the adapter boundary in both directions
 * (the chunk 1.5 `Nfc` helper), so a macOS NFD-named file scans to an NFC [TreePath] and a
 * read of an NFC [TreePath] reaches the correct on-disk bytes via the retained raw name (P4).
 */
interface ContentStore {

    /**
     * Recursively scans the content tree, honoring the configured ignore rules, and returns
     * the indexed entries plus any [ScanIssue]s (e.g. NFC path collisions, policy B3).
     *
     * Each indexed [ContentFile] retains its raw on-disk name (P4) so subsequent [read]s of a
     * collision winner reach the winner's bytes, not the loser's.
     */
    fun scan(): ScanResult

    /**
     * Reads the full bytes of the file at [path], or null if no such file is indexed.
     *
     * The read goes through the scan-retained raw on-disk name (P4) so a collision winner's
     * content is served even when its raw name is the non-NFC byte-form.
     */
    fun read(path: TreePath): ByteArray?

    /**
     * Lists the immediate children (files and folders) of the directory at [dir], or of the
     * content root when [dir] is null. Ignored entries are excluded.
     */
    fun list(dir: TreePath?): List<ContentEntry>

    /** Returns lightweight stat for the entry at [path], or null if it is not indexed. */
    fun stat(path: TreePath): ContentStat?

    /**
     * Atomically writes [bytes] to the file at [path], creating parent directories as needed.
     *
     * The implementation writes to a temporary sibling and renames into place
     * (`ATOMIC_MOVE`), falling back to copy+delete on filesystems that do not support atomic
     * rename (NFS/SMB). Each intended write is logged (path) before it is performed, so an
     * interrupted run is detectable (chunk 4b's adopt durability requirement).
     */
    fun write(path: TreePath, bytes: ByteArray)

    /**
     * Watches the content tree for changes, invoking [onChange] per change.
     *
     * **Stubbed for Phase 2** (the file watcher lands with incremental indexing). The default
     * implementation does nothing; the signature is present so the port shape is frozen now.
     */
    fun watch(onChange: (TreePath) -> Unit) {
        // Phase 2: file-watch driven incremental updates. Intentionally a no-op for Phase 1.
    }
}

/** Lightweight metadata for a content entry — what a scan-free `stat` can cheaply provide. */
data class ContentStat(
    val path: TreePath,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

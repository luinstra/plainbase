package com.plainbase.domain.content

import com.plainbase.domain.principal.EditGrant

/**
 * The single internal interface to the content tree (master plan §2.2): a small port over a
 * tree of Markdown files, assets, and metadata, whatever backend holds the authoritative bytes.
 *
 * The store speaks [TreePath] exclusively: no caller ever hands it a raw `String` path or a
 * filesystem `Path`. NFC normalization happens at the adapter boundary in both directions
 * (the chunk 1.5 `Nfc` helper), so a backend-side NFD-named entry scans to an NFC [TreePath] and
 * a read of an NFC [TreePath] reaches the correct stored bytes via the retained raw name (P4).
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
     * Atomically replaces the single file at [path] with [bytes], creating missing parents as
     * the backend requires. Unconditional replace, last-writer-wins: no precondition is checked,
     * and any stage failing THROWS rather than returning a typed result (Q8c; the sole production
     * caller is the single-writer adoption pass). Each intended write is logged (path) before it
     * is performed, so an interrupted run is detectable (the adopt durability requirement).
     */
    fun write(path: TreePath, bytes: ByteArray)

    /**
     * The PB-WRITE-1 indexed-only, hash-guarded, identity-rechecked atomic write.
     *
     * A read-then-[write] split has a window (an external writer landing between the two) that could
     * lose an update or write a ghost file. This resolves [path] ONCE to a single stored-content
     * identity, reads its bytes, hashes them through [hasher] (the domain-owned frozen
     * `CitationFactory.contentHash`, passed in so no adapter ever imports it), and, immediately
     * before the atomic replace, rechecks that identity through the backend's own native mechanism
     * (a file-key + mtime re-stat, a conditional-request precondition) so a concurrent external
     * write since the read is DETECTED, not clobbered.
     *
     * Boundary honesty: this is best-effort detection of NON-cooperating external writers on the
     * authority. It is NOT a global lock: two Plainbase processes are excluded by the DATA_DIR lock,
     * and cooperating writers serialize on the `WritePipeline` monitor (which is why the recheck only
     * ever guards against external writers).
     *
     * Returns the bytes verbatim on a [CasResult.Written] (no reserialization, no patcher); a
     * [CasResult.Mismatch] when the stored hash differs from [baseHash] or an external write landed
     * between the read and the replace; [CasResult.Deleted] when the indexed file is gone; and
     * [CasResult.Unreadable] when the read/stat threw (permission/locked/partial/transient), with
     * [CasResult.Unreadable.targetMutated] = true when a non-atomic replacement failed midway and
     * the target may have been partially replaced.
     */
    fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String): CasResult

    /**
     * Exclusively creates the file at [path] with [bytes], write-if-absent (PB-WRITE-1).
     * Returns [CreateResult.Created] (with the written bytes' [hasher] hash) when the file did not
     * exist and the create landed; [CreateResult.Exists] (carrying the REAL attempted stored
     * [TreePath]) when a file is ALREADY at [path] (nothing written); [CreateResult.Unreadable]
     * on a permission/transient failure.
     *
     * The existence check is the backend's own atomic create-if-absent (an O_EXCL-style exclusive
     * create, a conditional-request precondition), NOT an index lookup, so a path the scan has not
     * yet seen (a fresh create) is still protected against a racing second create, and a stale
     * not-yet-indexed file is still detected. The same boundary-honesty framing as
     * [compareAndSwapWrite]: cooperating writers serialize on the `WritePipeline` monitor; the
     * atomic create is the belt-and-suspenders against an external writer and the not-yet-scanned
     * case. Parents are created as needed (mirroring [write]).
     */
    fun createExclusive(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult

    /**
     * The binary twin of [createExclusive] for an uploaded asset: write-if-absent into a
     * page's OWN, already-existing folder. It reuses the SAME containment guards as [createExclusive]
     * (the scan-skipped-name / excluded-subtree / symlinked-ancestor / outside-root refusals + the
     * NFC-leaf collision guard) as ONE source of truth — never a re-derived weaker check — but differs in
     * exactly two ways an asset demands and a page does not:
     *
     *  1. **It NEVER creates parent directories.** A page create legitimately mints a fresh nested folder;
     *     an asset writes into THIS page's folder, which must already exist. Snapshot membership proves the
     *     page existed at index time, not that its folder still exists at upload time, so if the resolved
     *     parent is absent (or is not a directory) this returns [CreateResult.ParentMissing] rather than
     *     recreating the folder and stranding the asset under a page-less directory.
     *  2. **It fails closed.** It uses ONLY the no-0-byte-window `createLink` O_EXCL write; on a filesystem
     *     where hardlinks are unavailable it returns [CreateResult.Unreadable] instead of falling back to
     *     the reserve-then-move path. Pages self-heal a reserve-then-move crash window via the `dirty_page`
     *     journal; an asset has no such recovery, so a 0-byte reservation could permanently wedge future
     *     uploads behind a 409 — fail closed instead.
     *
     * [hasher] is the frozen `CitationFactory.contentHash` (passed in so this adapter never imports it).
     *
     * [grant] is an unused compile-time witness that `PolicyService.checkEdit()` ran (A3): the asset write is an
     * EDIT, so the gated mutator cannot be reached without a minted [EditGrant].
     */
    fun writeAssetExclusive(grant: EditGrant, path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult

    /**
     * Watches the content tree for changes, invoking [onChange] with each changed path until the
     * returned handle is closed. The signal may be coarse or polled (a backend without native
     * change events reconciles on an interval). Ignored entries (the same rules as [scan]) never
     * produce a call.
     *
     * Deliberate, documented internal-port change (Phase 2 §B2): the Phase-1 no-op stub gained a
     * lifecycle handle; domain ports are not wire contracts. The path argument exists for logging;
     * consumers are path-blind by design: an event's ONLY effect is scheduling the serialized full
     * rebuild, never a direct state mutation. An event-queue overflow is delivered as the synthetic
     * [OVERFLOW] path: the convergence operation is already a full pass, so overflow needs nothing
     * beyond scheduling one.
     */
    fun watch(onChange: (TreePath) -> Unit): AutoCloseable

    companion object {
        /** The synthetic path [watch] delivers on an event-queue overflow (consumers just schedule — §B2). */
        val OVERFLOW: TreePath = TreePath.require("(overflow)")
    }
}

/** The outcome of [ContentStore.compareAndSwapWrite] (PB-WRITE-1). */
sealed interface CasResult {

    /** The on-disk hash matched [baseHash] and the rename completed; [newHash] is the written bytes' hash. */
    data class Written(val newHash: String) : CasResult

    /**
     * The on-disk hash differed from `baseHash`, OR an external write landed between the read and the
     * rename (the file-identity recheck fired). [currentBytes]/[currentHash] are the on-disk state at
     * detection — both null when the file vanished concurrently.
     */
    data class Mismatch(val currentBytes: ByteArray?, val currentHash: String?) : CasResult

    /** The indexed file is gone (deleted, or never indexed) — nothing to compare-and-swap against. */
    data object Deleted : CasResult

    /**
     * The read/stat threw (permission/locked/partial/transient FS); [cause] is diagnostic.
     *
     * [targetMutated] is true ONLY when a non-atomic copy-fallback failure may have TRUNCATED/partially
     * replaced the target on disk (a no-atomic-move FS). The pipeline then RETAINS the write-ahead dirty
     * mark so reconcile commits a fully-landed copy or drift-skips a partial — never a silent corruption.
     * It stays false for every pre-move / atomic-move failure (atomicity → nothing landed = nothing
     * written), which is the default the existing positional `Unreadable("…")` sites keep.
     */
    data class Unreadable(val cause: String, val targetMutated: Boolean = false) : CasResult
}

/** The outcome of [ContentStore.createExclusive] (PB-WRITE-1, write-if-absent). */
sealed interface CreateResult {

    /** The file did not exist and the create + atomic rename landed; [newHash] is the written bytes' hash. */
    data class Created(val newHash: String) : CreateResult

    /** A file already occupies [path]; nothing written. [path] is the REAL attempted target the route surfaces. */
    data class Exists(val path: TreePath) : CreateResult

    /**
     * The target can never name content (containment): a path segment is ignored (dotfile/glob)
     * or excluded (DATA_DIR), or an existing ancestor is a symlink / resolves outside the content root
     * (links-are-not-content). NOTHING written. [reason] is diagnostic; the route maps it to a 4xx.
     */
    data class Rejected(val reason: String) : CreateResult

    /**
     * The resolved parent directory is absent or is not a directory (W3b [ContentStore.writeAssetExclusive]
     * only): the page's folder vanished on disk between index time and the upload. NOTHING written, and —
     * unlike [createExclusive] — the missing dir is deliberately NOT recreated. The route maps this to 404.
     */
    data object ParentMissing : CreateResult

    /**
     * The create threw (permission/locked/partial/transient); [cause] is diagnostic.
     *
     * [targetMutated] is true ONLY when the AUTHORITY may already hold the created bytes even though
     * the create as a whole failed (Q8b's create twin: a durable backend create whose follow-up apply
     * failed). The pipeline then RETAINS the write-ahead dirty mark so reconcile commits a
     * fully-landed create or drift-skips, never a silent loss of the recovery record. It stays false
     * for every nothing-landed failure (atomicity means nothing landed = nothing written), which is
     * the default the existing positional `Unreadable("…")` sites keep.
     */
    data class Unreadable(val cause: String, val targetMutated: Boolean = false) : CreateResult
}

/** Lightweight metadata for a content entry — what a scan-free `stat` can cheaply provide. */
data class ContentStat(
    val path: TreePath,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

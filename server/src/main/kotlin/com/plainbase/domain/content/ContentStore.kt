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
     * Whether the backing tree exists and is traversable RIGHT NOW - the ONE liveness probe (ADR-0011 D5).
     *
     * A local store answers the same three-predicate check the config's one-probe rule uses (a readable,
     * searchable directory); an object store answers `true` unconditionally - the bucket is the authority
     * and transport failures have their own error paths, so availability is a LOCAL-path concept in v1 (D10).
     *
     * Deliberately NOT a write-capability check: a READ-ONLY remounted root exists, is readable and serves
     * every byte correctly, so calling it "unavailable" would be false on its face - and availability is
     * sticky-until-restart, which is the wrong remediation for a condition `mount -o remount,rw` fixes. A
     * read-only root is a WRITE fault, and the mutation surfaces' own `Unreadable` arms already say so.
     */
    fun available(): Boolean

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
     *
     * A read that FEEDS A DECISION - one whose failure would drive a durable rewrite or a not-found wire
     * answer - must use [readClassified] instead: a bare null here cannot tell a deleted FILE from a downed
     * ROOT, and they are not the same answer (ADR-0011 D5).
     */
    fun read(path: TreePath): ByteArray?

    /**
     * [read], CLASSIFIED - and classified as far as a STORE can honestly go, which is not all the way (C1).
     *
     * EVERY rooted read whose FAILURE would drive a durable rewrite or a not-found WIRE answer calls this and
     * never [read] - neither a bare null NOR a raw `IOException` can distinguish "no bytes here" from a downed
     * root, and the two must never produce the same answer: telling an agent "page gone" for a root whose disk
     * is unmounted is the exact lie ADR-0011 D5 forbids.
     *
     * It returns [StoreRead], NOT [ContentRead]: a store knows about BYTES, not about PAGES. "There is nothing
     * at that path" ([StoreRead.NoBytes]) is the whole of what it can say, and turning that into "this page was
     * deleted" needs the durable index, which a store has never heard of. The domain's [com.plainbase.domain
     * .service.AbsenceClassifier] makes that call, in one place, for every consumer.
     *
     * The liveness probe fires ONLY on a failure, so the hot read path is untouched. [read] itself is left
     * exactly as it is - reading for RENDERING is unchanged; only reading to DECIDE is classified.
     */
    fun readClassified(path: TreePath): StoreRead

    /**
     * Lists the immediate children (files and folders) of the directory at [dir], or of the
     * content root when [dir] is null. Ignored entries are excluded.
     */
    fun list(dir: TreePath?): List<ContentEntry>

    /** Returns lightweight stat for the entry at [path], or null if it is not indexed. */
    fun stat(path: TreePath): ContentStat?

    /**
     * Atomically CREATES-OR-REPLACES the single file at [path] with [bytes], **making missing parent
     * directories**. Unconditional, last-writer-wins: no precondition is checked, and any stage failing
     * THROWS rather than returning a typed result (Q8c).
     *
     * **Not a content-mutation surface, and the parent creation is why.** A tree whose root has been deleted
     * or unmounted is RECREATED by this call - as a partial skeleton holding only the pages the caller happened
     * to write, at wherever that path now resolves. Anything patching a page that is supposed to ALREADY EXIST
     * wants [compareAndSwapWrite] instead, which replaces a file it resolved and creates nothing; anything
     * making a genuinely new page wants [createExclusive], which enforces containment. The remaining caller is
     * the object backend's local MIRROR apply, whose target is derived DATA_DIR state that is CONTRACTUALLY
     * allowed to be absent and re-materialized from the bucket - the one place "make the parents" is the
     * correct answer rather than a resurrection.
     *
     * Each intended write is logged (path) before it is performed, so an interrupted run is detectable.
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
     * NFC-leaf collision guard) as ONE source of truth - never a re-derived weaker check - but differs in
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
     *     uploads behind a 409 - fail closed instead.
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
     *
     * [onFailure] is invoked at most ONCE when the watch worker dies UNEXPECTEDLY (a `WatchService` fault) -
     * not on a graceful close. It is one of the two things a watcher exists to notice, and it is the narrower
     * one: an unexpected worker death would otherwise leave a healthy-looking server whose changes silently
     * stop converging.
     *
     * [onCoverage] reports how much of the tree the watcher can actually SEE ([WatchCoverage]), and it is
     * deliberately NOT a failure: a subtree the backend cannot register (the inotify watch limit, a
     * permission-denied directory) degrades CONVERGENCE, never AVAILABILITY - the root is there, every byte of
     * it serves, and the backend keeps converging it the slow way (a periodic full pass) while it retries the
     * registration in place. Reporting it as a root fault would 503 a healthy root over a host-wide kernel
     * limit, sticky until a restart that re-registers, re-fails and re-marks: an outage the server inflicts on
     * itself and cannot leave. Both transitions are reported, so a raised limit or a fixed permission clears
     * the flag with no restart.
     *
     * The other is ROOT LOSS, and a backend that watches a root MUST detect it (ADR-0011 D5): a deleted or
     * unmounted root does not necessarily fail its watcher and may raise no event at all, so a root with no
     * write traffic has NO other detector - every one of them (the write probe, the rebuild probe) is driven by
     * an operation somebody asked for. An undetected root loss is not a slow 503, it is a permanent 200 over
     * carried-forward bytes. The local backend therefore probes the root on an interval and, on loss, marks it
     * unavailable and schedules the converging pass (see `LocalContentStore.watch`); no callback is added here
     * for it, because both mechanisms are ones the store already holds.
     */
    fun watch(
        onChange: (TreePath) -> Unit,
        onFailure: (Throwable) -> Unit = {},
        onCoverage: (WatchCoverage) -> Unit = {},
    ): AutoCloseable

    companion object {
        /** The synthetic path [watch] delivers on an event-queue overflow (consumers just schedule - §B2). */
        val OVERFLOW: TreePath = TreePath.require("(overflow)")

        /**
         * The bound every [watch] handle's `close()` honors: both backends join a worker thread, and neither may
         * wait longer than this for it. It is what the graceful-shutdown budget counts per watcher, so a backend
         * that waited longer would be cut off mid-close.
         */
        const val WATCH_CLOSE_BOUND_MILLIS: Long = 10_000
    }
}

/**
 * How much of a root's tree its watcher is actually watching - a CONVERGENCE fact, never an availability one
 * (see [ContentStore.watch]'s `onCoverage`).
 */
enum class WatchCoverage {
    /** Every directory in the tree is registered: an edit anywhere converges within the change-to-visible bound. */
    WHOLE,

    /**
     * Part of the tree could not be registered (the inotify watch limit, an unreadable directory): edits under it
     * raise no event, so they converge only on the backend's periodic full pass - slower, never never. The backend
     * keeps retrying the registration, so this can go back to [WHOLE] without a restart.
     */
    PARTIAL,
}

/**
 * **Everything a STORE can honestly say about a read** (C1). It knows about BYTES; it does not know about pages,
 * and it has never heard of the durable index - so it cannot, and no longer may, decide that a page is DELETED.
 *
 * A sealed RESULT rather than a throw, deliberately: several consumers must SWALLOW a root-down condition (leave
 * an APPLYING proposal APPLYING, leave a dirty journal row dirty, answer `base_drifted = true`) rather than
 * propagate it, and two blanket `catch (Exception)` arms already sitting on these paths would otherwise launder
 * an honest 503 back into one of the very codes ADR-0011 D5 forbids.
 */
sealed interface StoreRead {

    data class Bytes(val bytes: ByteArray) : StoreRead {
        override fun equals(other: Any?): Boolean = this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * There are no bytes at that path, on a root that IS live. **That is ALL it means.**
     *
     * It is NOT "the page was deleted". An empty mount point, a half-finished restore, a decoy tree and a real
     * deletion produce the identical observation here, and no probe a store can run separates them - so the
     * store hands the fact up and the domain decides what it MEANS ([com.plainbase.domain.service.AbsenceClassifier]).
     */
    data object NoBytes : StoreRead

    /** The backing tree is not traversable right now - NOTHING may be concluded about the file. */
    data object RootDown : StoreRead
}

/**
 * **What the DOMAIN concludes** by combining a [StoreRead] with the durable index (C1). The compiler makes each
 * consumer name its behavior for all four arms; a `catch` nobody remembered to narrow cannot swallow one.
 *
 * The rule, and it closes ledger A4 by REMOVING a check rather than adding one:
 *
 * > A read for a page **the durable index HAS**, whose bytes the store cannot produce, is **503**.
 * > **404** only for a page the index does not have.
 *
 * The adapter used to decide this from `available()`, and after an ext4 inode-reused replacement `available()`
 * says LIVE - so a read for a page sitting safe on an unmounted disk answered 404 ("drop your citations") and a
 * CAS write answered `page_deleted`. The index knew better the whole time; nobody asked it.
 */
sealed interface ContentRead {

    data class Bytes(val bytes: ByteArray) : ContentRead {
        override fun equals(other: Any?): Boolean = this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** No bytes, AND the durable index does not have this page either. Nothing is in doubt: the honest **404**. */
    data object ConfirmedAbsent : ContentRead

    /**
     * No bytes, BUT the durable index HAS a live binding for it. The page is in LIMBO - neither present nor
     * proven gone - and the only honest answer is **503 `absence_unverified`: come back later**.
     *
     * **An `AbsenceUnknown` is never allowed to become a fact.** It ends every path in "we do not know yet",
     * never in "it's gone": no 404, no `page_deleted`, no `rebase_target_gone`, no cleared recovery row, no
     * adoption against a view we cannot verify. It self-heals the moment the page is witnessed again.
     */
    data object AbsenceUnknown : ContentRead

    /** The backing tree is not traversable right now - NOTHING may be concluded about the file. */
    data object RootDown : ContentRead
}

/** The outcome of [ContentStore.compareAndSwapWrite] (PB-WRITE-1). */
sealed interface CasResult {

    /** The on-disk hash matched [baseHash] and the rename completed; [newHash] is the written bytes' hash. */
    data class Written(val newHash: String) : CasResult

    /**
     * The on-disk hash differed from `baseHash`, OR an external write landed between the read and the
     * rename (the file-identity recheck fired). [currentBytes]/[currentHash] are the on-disk state at
     * detection - both null when the file vanished concurrently.
     */
    data class Mismatch(val currentBytes: ByteArray?, val currentHash: String?) : CasResult

    /** The indexed file is gone (deleted, or never indexed) - nothing to compare-and-swap against. */
    data object Deleted : CasResult

    /**
     * The read/stat threw (permission/locked/partial/transient FS); [cause] is diagnostic.
     *
     * [targetMutated] is true when the write may already be DURABLE at the authority even though the
     * operation as a whole failed, so the pipeline RETAINS the write-ahead dirty mark (reconcile then
     * commits the fully-landed write or drift-skips, never a silent corruption). Two backend-neutral
     * cases raise it: (1) a local no-atomic-move copy-fallback that may have TRUNCATED/partially
     * replaced the target on disk; (2) an object backend whose conditional PUT landed DURABLY at the
     * bucket but whose local mirror apply then failed (`durable_but_unmirrored`). It stays false for
     * every nothing-landed failure (a pre-send / atomic-move failure - nothing written), the default
     * the existing positional `Unreadable("…")` sites keep.
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
     * only): the page's folder vanished on disk between index time and the upload. NOTHING written, and -
     * unlike [createExclusive] - the missing dir is deliberately NOT recreated. The route maps this to 404.
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

/** Lightweight metadata for a content entry - what a scan-free `stat` can cheaply provide. */
data class ContentStat(
    val path: TreePath,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

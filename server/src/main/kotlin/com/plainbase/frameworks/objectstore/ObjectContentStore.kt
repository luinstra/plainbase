package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentEntry
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStat
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.content.WatchCoverage
import com.plainbase.domain.principal.EditGrant
import com.plainbase.frameworks.filesystem.FOLDER_META_NAME
import com.plainbase.frameworks.filesystem.FileAtomics
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.filesystem.isBlank
import com.plainbase.frameworks.filesystem.rootLivenessProbe
import com.plainbase.frameworks.filesystem.withDirectoryStream
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The hybrid object-backend [ContentStore] (plan C4): the bucket is the AUTHORITY, the local
 * [mirror] (a [LocalContentStore] over `DATA_DIR/mirror`) is derived, deletable read state.
 *
 * - **Reads are local**: `scan/read/list/stat` delegate to the mirror verbatim, inheriting the
 *   snapshot gates, NFC collision policy (B3), and P4 raw-name behavior - lock-free (M1).
 * - **Mutations write the bucket first** (conditional PUT per the Q8 mapping), then apply the same
 *   bytes to the mirror and record the returned etag in [MirrorState] - the {mirror write ->
 *   recordConfirmed -> persist} apply runs under the hybrid monitor with per-key re-checks; NO
 *   network call ever runs under it (M1's no-lock-across-network rule).
 * - **The etag map's invariant** (Q8b): an entry asserts "mirror bytes == this bucket generation";
 *   not known-true => ABSENT. A map miss is a cache miss the CAS resolves by reading the bucket
 *   back (seam h) - never a `!!`.
 * - **[watch] is a poll-reconcile** (Q5): LIST + diff + GETs outside the lock, per-key apply under
 *   it, skipping keys whose entry moved mid-flight and keys with a live dirty-journal row (R3/R11).
 * - **[hydrate] is the M1 boot-diff**, called by boots inside the DataDirLock region (never
 *   constructor work, so boot laziness R9 holds). Its first LIST doubles as the R16 fail-closed
 *   TLS/signature self-check.
 *
 * The port is synchronous; each network call bridges via [runBlocking] (the `S3SmokeCommand`
 * idiom). Writers are already serialized on the pipeline monitor, so this blocks one save at a time.
 */
class ObjectContentStore(
    private val client: ObjectStoreClient,
    /** The inner mirror store (exposed per the plan's Koin shape; C5 binds git's repoPath to it). */
    val mirror: LocalContentStore,
    private val state: MirrorState,
    /** `""`, or the configured `storage.prefix` + `"/"`. */
    private val keyPrefix: String,
    private val pollSeconds: Long,
    /** The live dirty-journal paths - the hydrate delete phase snapshots this ONCE per boot (R3). */
    private val dirtyPaths: () -> Set<TreePath>,
    /**
     * The per-path live dirty check for the poll hot path (MINOR-1): the guard runs once per poll candidate
     * under [applyLock], so it queries ONE path (an indexed EXISTS) instead of rebuilding the whole [dirtyPaths]
     * set per candidate. Defaults to membership in [dirtyPaths] so tests need not wire a separate predicate.
     */
    private val isDirty: (TreePath) -> Boolean = { it in dirtyPaths() },
    mirrorRoot: Path,
    private val ignoreRules: IgnoreRules = IgnoreRules(),
    private val atomics: FileAtomics = FileAtomics.Real,
) : ContentStore, AutoCloseable {

    private val mirrorRoot: Path = mirrorRoot.toAbsolutePath().normalize()

    // The hybrid apply monitor (M1): guards only the short local apply ({mirror write ->
    // recordConfirmed/invalidate -> persist} plus the per-key re-checks). Never held across a
    // network call; the fake's tripwire asserts that.
    private val applyLock = Any()

    /** The monitor-tripwire seam: the fake asserts no network op runs while this lock is held. */
    internal val applyLockForTests: Any get() = applyLock

    init {
        constructions.incrementAndGet() // R9: LOCAL boot must construct ZERO of these (counter-proven)
    }

    // ---- Reads: pure delegation to the mirror ------------------------------------------------

    /**
     * The BUCKET is the authority and its transport failures have their own error paths - but the tree this store
     * SERVES is the local mirror, and every read answers from it. So availability is the MIRROR's liveness, and a
     * mirror that is missing (or is no longer the directory we hydrated - DATA_DIR can sit on a mounted volume too)
     * is UNAVAILABLE, never "available and empty". The distinction is the whole of ADR-0011 D5: an empty scan on an
     * available root is a full-corpus DELETE instruction, and a rebuild acting on it purges the checkpoints and the
     * search rows of a corpus the bucket still holds in full.
     *
     * [mirrorProbe] is null until [hydrate] materializes the mirror, and that arm answers `true`: a store that has
     * never hydrated has claimed no tree and holds nothing anyone could lose (PREVIEW adopt, which never hydrates,
     * never mkdirs, and reads whatever mirror is there, point-in-time). Once hydrated, liveness is the probe
     * `LocalContentStore` uses on its own root - the tree's IDENTITY, not the path's - so this store and the local
     * store answer the same question the same way.
     *
     * **And then it asks the one question the identity CANNOT answer: is the mirror BLANK?** `fileKey` is
     * `(st_dev, st_ino)`, and an inode is a REUSABLE number - on ext4, deleting a directory and recreating it at
     * the same path hands back the SAME inode, so the tree is replaced and the identity says it never moved. The
     * probe therefore cannot see this class of loss at all, and no probe over the path can.
     *
     * What makes the mirror answerable anyway is that it is not a content root: it is APP-OWNED derived state,
     * this store materialized every byte in it, and nobody empties it on purpose. So a mirror that HELD pages and
     * now holds nothing is lost, whatever the inode says - unavailable (503, "the page still exists"), never
     * available-and-empty (404 + a full-corpus delete, of a corpus the bucket still holds in full). The
     * corresponding blank LOCAL root is the opposite case and must stay AVAILABLE: an operator may legitimately
     * empty a content tree, and adjudicating THAT is the corpus-loss tripwire's job, not a `stat`'s.
     */
    override fun available(): Boolean {
        val probe = mirrorProbe.get() ?: return true
        return probe(mirrorRoot) && !(hydratedPages.get() && isBlank(mirrorRoot))
    }

    /**
     * Did the last [hydrate] materialize any page AT ALL? The exoneration [available]'s blank-mirror check needs: a
     * bucket that is legitimately empty hydrates to an empty mirror, and an empty mirror is the honest answer for
     * it - only a mirror that HAD pages and lost them is a loss.
     */
    private val hydratedPages = AtomicBoolean(false)

    /**
     * The mirror's liveness probe, bound to the tree [hydrate] materialized. Null until then - the only state in
     * which an absent mirror is an honest empty corpus rather than a lost one (seam c).
     */
    private val mirrorProbe = AtomicReference<((Path) -> Boolean)?>(null)

    /**
     * Did the last [hydrate] leave an object BEHIND? A non-strict boot DEFERS a key whose GET or mirror write
     * failed (sites (a) and (b) there) and returns successfully anyway - the bucket is still the authority and a
     * transient fault must not stop the server - so the mirror it hands the rebuild is missing pages the bucket
     * still holds. Published as ONE value at the end of the hydrate that computed it, never mutated per key.
     */
    private val hydrationDeferred = AtomicBoolean(false)

    /**
     * A never-hydrated store with no mirror on disk scans to an empty tree (seam c): a fresh install previews
     * cleanly instead of throwing NoSuchFileException. Once hydrated, the mirror IS the corpus - so a mirror that
     * has gone away is NOT an empty scan here, it is the store's NIO failure, which the rebuild's root-loss
     * classifier turns into skip-and-carry (never the mass delete an empty ScanResult would authorize).
     *
     * **A hydrate that DEFERRED an object answers `complete = false`, and this is the one backend that ever does.**
     * A walk cannot tell a page whose GET failed at boot from a page the operator deleted - both are simply not in
     * the mirror - so the pass is refused DELETE AUTHORITY for this root ([ScanResult.complete]) until a hydrate
     * comes back clean. Nothing is withheld from the READ path by that: every page the mirror DID hydrate still
     * publishes and still serves, which is the whole point of the split - a transient GET failure must never blank
     * a site, and it must never delete its rows either.
     */
    override fun scan(): ScanResult =
        if (mirrorProbe.get() == null && !Files.isDirectory(mirrorRoot)) {
            ScanResult(files = emptyList(), folders = emptyList(), issues = emptyList())
        } else {
            mirror.scan().copy(complete = !hydrationDeferred.get())
        }

    override fun read(path: TreePath): ByteArray? = mirror.read(path)

    /**
     * Read-or-[ContentRead.Absent], with the same D5 classification every rooted read owes: a read that comes back
     * empty-handed on a mirror that is GONE is `RootDown` (503, "the page still exists"), never `Absent` (404, "drop
     * your citations"). The probe fires only on that empty-handed path, so the hot read is untouched. Deliberately
     * NOT delegated to `mirror.readClassified`: the mirror is a `LocalContentStore` bound to a root it did not
     * choose, and its classifier would answer `RootDown` for the never-hydrated mirror this store answers for.
     */
    override fun readClassified(path: TreePath): ContentRead =
        read(path)?.let(ContentRead::Bytes) ?: if (available()) ContentRead.Absent else ContentRead.RootDown

    override fun list(dir: TreePath?): List<ContentEntry> = mirror.list(dir)

    override fun stat(path: TreePath): ContentStat? = mirror.stat(path)

    /** Releases the underlying object-store transport (the ktor HttpClient). Owned here; closed by the
     *  CLI at command end and by `serve()` on shutdown - the mirror is plain files, nothing to close. */
    override fun close() = client.close()

    // ---- Mutators: bucket-first, Q8 mapping --------------------------------------------------

    override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String): CasResult {
        val key = keyOf(path)
        // (1) Resolve the comparison bytes + the If-Match etag, absence-safely (seam h).
        val compareBytes: ByteArray
        val ifMatch: String
        val mapEtag = state.etagOf(path)
        // A state entry is a cache hit ONLY when the mirror file it describes is present AND readable. A
        // surviving entry over a DELETED mirror file (DATA_DIR/mirror is deletable derived state) is NOT
        // "known-true" (Q8b), so it must NOT short-circuit to Deleted: drop the stale entry and read the
        // authority back, exactly like any other cache miss. A LOCAL mirror-read fault (disk error) is
        // likewise NOT a bucket answer: catch it (never let it escape UNTYPED after WritePipeline's
        // write-ahead dirty mark) and fall into the same authoritative read-back below.
        val mirrorBytes = if (mapEtag != null) {
            try {
                mirror.read(path)
            } catch (e: Exception) {
                logger.warn { "mirror read of '${path.value}' failed (${causeOf(e)}); reading the bucket authority instead" }
                null
            }
        } else {
            null
        }
        if (mapEtag != null && mirrorBytes != null) {
            compareBytes = mirrorBytes
            ifMatch = mapEtag
        } else {
            // Cache miss - never-seen / invalidated / post-failed-heal / mirror-file-gone / mirror-read-fault:
            // read the bucket back for both. A stale entry over an unusable mirror file is invalidated first (Q8b).
            if (mapEtag != null) state.invalidate(path)
            val fetched = try {
                runBlocking { client.get(key) }
            } catch (e: Exception) {
                // Fail closed: never PUT blind when the authority cannot be read (the frozen retryable 503).
                return CasResult.Unreadable(causeOf(e), targetMutated = false)
            } ?: return CasResult.Deleted
            healMirror(path, fetched) // opportunistic; a failed heal proceeds on the bucket bytes (seam g)
            compareBytes = fetched.bytes
            ifMatch = fetched.etag
        }
        // (2) Base compare.
        val currentHash = hasher(compareBytes)
        if (currentHash != baseHash) return CasResult.Mismatch(currentBytes = compareBytes, currentHash = currentHash)

        // (3) Conditional PUT at the authority - outside the lock.
        logger.info { "CAS-writing content object: ${path.value} (${bytes.size} bytes)" }
        val outcome = try {
            runBlocking { client.put(key, bytes, PutCondition.IfMatch(ifMatch), contentType = MARKDOWN) }
        } catch (e: Exception) {
            return if (isDefinitivePreSend(e)) {
                CasResult.Unreadable(causeOf(e), targetMutated = false) // the request never went out (Q13)
            } else {
                disambiguateCas(path, key, bytes, hasher, priorEtag = ifMatch, failure = e) // Q8a
            }
        }
        return when (outcome) {
            // (4) Durable at the bucket => apply to the mirror (Q8b guards the apply).
            is PutOutcome.Stored -> when (val failure = applyConfirmedWrite(path, bytes, outcome.etag)) {
                null -> CasResult.Written(newHash = hasher(bytes))
                else -> CasResult.Unreadable(failure, targetMutated = true)
            }
            // (5) Someone else wrote between our read and our PUT (R2 412 / S3 409): authoritative GET.
            is PutOutcome.PreconditionFailed -> readBackAfterPrecondition(path, key, hasher)
        }
    }

    override fun createExclusive(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult {
        // Heal a KNOWN pending-unmirrored generation of this path FIRST so the mirror gates below are
        // authoritative (the create-family NFC-collision close, see [healPendingUnmirrored]).
        healPendingUnmirrored(path)
        // Pre-PUT read-only gates against the mirror (the C3 seam): a refused create provably leaves
        // ZERO side effects anywhere - the bucket has no directories, and nothing was sent.
        val target = mirror.onDiskTarget(path)
        mirror.gates.rejectionReason(path, target)?.let { reason ->
            logger.warn { "Refusing create of '${path.value}': $reason" }
            return CreateResult.Rejected(reason)
        }
        if (mirror.gates.resolveParent(path).occupiedByFile) return CreateResult.Exists(path)
        if (mirror.gates.nfcEquivalentSiblingExists(target)) return CreateResult.Exists(path)
        logger.info { "Creating content object: ${path.value} (${bytes.size} bytes)" }
        return exclusivePut(path, bytes, hasher, contentType = MARKDOWN)
    }

    override fun writeAssetExclusive(
        @Suppress("UNUSED_PARAMETER") grant: EditGrant,
        path: TreePath,
        bytes: ByteArray,
        hasher: (ByteArray) -> String,
    ): CreateResult {
        healPendingUnmirrored(path) // authoritative gates (create-family NFC-collision close, as [createExclusive])
        val target = mirror.onDiskTarget(path)
        // Asset law (W3b): the resolved parent must ALREADY exist as a directory - never pre-created
        // pre-PUT (wire 404). Post-durability the plain mirror apply may recreate a vanished parent:
        // the bucket already holds the object and the mirror must converge.
        val onDiskParent = target.parent
        if (onDiskParent == null || !Files.isDirectory(onDiskParent)) {
            logger.warn { "Refusing asset write of '${path.value}': parent directory is absent or not a directory" }
            return CreateResult.ParentMissing
        }
        mirror.gates.rejectionReason(path, target)?.let { reason ->
            logger.warn { "Refusing asset write of '${path.value}': $reason" }
            return CreateResult.Rejected(reason)
        }
        if (mirror.gates.nfcEquivalentSiblingExists(target)) return CreateResult.Exists(path)
        logger.info { "Writing asset object: ${path.value} (${bytes.size} bytes)" }
        return exclusivePut(path, bytes, hasher, contentType = null) // assets are arbitrary binaries
    }

    override fun write(path: TreePath, bytes: ByteArray) {
        val key = keyOf(path)
        auditHead(path, key) // observational only - the audit never gates (Q8c; LWW is the point)
        // Log the intended write BEFORE performing it (the adopt durability contract), path only.
        logger.info { "Writing content object: ${path.value} (${bytes.size} bytes)" }
        val stored = putUnconditionalWithRetry(key, bytes)
        // Q8b: a post-PUT mirror failure invalidates the entry + persists BEFORE the throw (inside
        // applyConfirmedWrite) and schedules the immediate single-key reconcile.
        applyConfirmedWrite(path, bytes, stored.etag)?.let { failure ->
            throw ObjectStoreException("write of '${path.value}' was durable at the bucket but the mirror write failed: $failure")
        }
    }

    // ---- Watch: the Q5 poll-reconcile ----------------------------------------------------------

    // [onFailure] is accepted and IGNORED beyond the existing retry/logging below: a poll fault is transient by
    // design (the next tick re-reconciles), and availability is not an object-mode concept (D10) - there is no
    // root to mark unavailable.
    //
    // [onCoverage] is accepted and NEVER INVOKED, which is the honest answer rather than a stub: there is no
    // registration to lose here. Every poll LISTs the whole bucket, so coverage is whole by construction - and the
    // one incompleteness this backend DOES have (an object a boot hydrate deferred) is not a watch fact at all, so
    // it is reported where it belongs, on the scan ([scan]'s `complete`).
    override fun watch(
        onChange: (TreePath) -> Unit,
        onFailure: (Throwable) -> Unit,
        onCoverage: (WatchCoverage) -> Unit,
    ): AutoCloseable {
        val stop = CountDownLatch(1)
        val thread = Thread {
            while (true) {
                val stopped = try {
                    stop.await(pollSeconds, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    break // close() interrupted the sleep - exit cleanly
                }
                if (stopped) break // close() signalled - exit cleanly
                try {
                    pollOnce(onChange)
                } catch (_: InterruptedException) {
                    break // close() interrupted an in-flight cycle - exit cleanly
                } catch (e: Exception) {
                    // Any transient poll-cycle fault (a scan IO error, a future throw the inner guards miss)
                    // must WARN and continue - NEVER permanently kill the poll thread (the Q13 "retry next
                    // cycle" promise every fail-closed site depends on). Network GET/LIST faults are already
                    // handled inside pollOnce; this is the last-resort backstop.
                    logger.warn { "object poll cycle failed (${causeOf(e)}); retrying next cycle" }
                }
            }
        }
        thread.name = "plainbase-object-poll"
        thread.isDaemon = true
        thread.start()
        // close() must fully STOP the poll before serve()/the CLI closes the shared transport, or a
        // GET/LIST in flight would use-after-close the ktor client. Signal the loop, interrupt a
        // blocking network op, and JOIN (bounded) so no poll is running when the caller then close()s.
        return AutoCloseable {
            stop.countDown()
            thread.interrupt()
            thread.join(ContentStore.WATCH_CLOSE_BOUND_MILLIS)
        }
    }

    /**
     * One poll cycle (the [watch] thread's loop body, callable directly for deterministic tests).
     * LIST + diff + GETs run OUTSIDE the lock; the per-key apply re-checks under it: a key whose map
     * entry moved since this cycle's snapshot is skipped (a writer/healer advanced it - R11), and a
     * dirty-journaled key is never overwritten or deleted (R3). A LIST/GET failure mutates NOTHING
     * (Q13): WARN and retry next cycle.
     *
     * The change diff is the SAME one [hydrate] runs - a bucket etag the state does not have, OR a listed key
     * whose mirror FILE is missing. The etag-only version left a mirror file deleted at RUNTIME (DATA_DIR/mirror
     * is deletable derived state) absent until the next boot, and absent from the mirror means absent from the
     * next rebuild's scan: the page leaves the snapshot, and with the root scanned and available its checkpoint
     * and search rows are DELETED for a page the bucket still holds. The mirror is derived state; a poll that
     * cannot re-derive it is not a poll. The extra cost is one local `exists` per listed key, against a LIST that
     * already crossed the network.
     */
    internal fun pollOnce(onChange: (TreePath) -> Unit = {}) {
        val before = state.snapshot()
        val listed = try {
            listBucket()
        } catch (e: Exception) {
            logger.warn { "poll LIST failed (${causeOf(e)}); nothing mutated, retrying next cycle" }
            return
        }
        val changed = listed.filter { (path, entry) -> before[path] != entry.etag || !mirrorHasRaw(entry.rawRelative) }
        val fetched = changed.mapNotNull { (path, entry) ->
            try {
                // A key whose GET returns null (a 404 in the LIST->GET window) is silently dropped here, NOT
                // state-invalidated - unlike hydrate, which invalidates. Intentional asymmetry: poll is
                // best-effort and the NEXT cycle re-LISTs, so the delete phase (or a later cycle) reconciles it.
                runBlocking { client.get(keyPrefix + entry.rawRelative) }?.let { Triple(path, entry, it) }
            } catch (e: Exception) {
                logger.warn { "poll GET of '${path.value}' failed (${causeOf(e)}); retrying next cycle" }
                null
            }
        }

        val events = mutableListOf<TreePath>()
        for ((path, entry, body) in fetched) {
            synchronized(applyLock) {
                if (state.etagOf(path) != before[path]) return@synchronized // entry moved mid-flight - skip (R11)
                // LIVE dirty check (O1/MINOR-1): re-query THIS path under applyLock, NOT a top-of-poll snapshot -
                // a write-ahead dirty mark added DURING the poll (after the LIST/GET) must still protect the path
                // from being overwritten by this stale GET (R3). An indexed single-path EXISTS, not a rebuild of
                // the whole dirty set per candidate (which was O(candidates * dirty rows) under the monitor).
                if (isDirty(path)) return@synchronized // never overwrite a dirty-ahead write (R3)
                val failure = mirrorWriteFailure { writeMirrorRaw(entry.rawRelative, body.bytes, fullNfcSweep = false) }
                if (failure == null) {
                    state.recordConfirmed(path, body.etag)
                    events.add(path)
                } else {
                    // Poll-apply failure (seam g): never recordConfirmed over a failed write; the entry
                    // goes ABSENT so the next poll re-GETs. The batch continues for other keys.
                    state.invalidate(path)
                    logger.warn { "mirror_apply_failed: poll apply of '${path.value}' failed ($failure); retrying next cycle" }
                }
            }
        }
        // Delete phase: mirror files / map entries absent from LIST (eligible paths only - the mirror
        // scan is already ignore-filtered, so `.git`/dotfiles never appear here).
        for (path in (before.keys + mirrorFilePaths()) - listed.keys) {
            synchronized(applyLock) {
                if (isDirty(path)) return@synchronized // LIVE R3 check (O1/MINOR-1): a dirty-ahead write may be mid-flight
                if (state.etagOf(path) != before[path]) return@synchronized // entry moved - skip
                // Drop state + emit the change ONLY after the file is actually gone (finding 3): a swallowed
                // delete failure must not invalidate state while the mirror still serves a bucket-absent file.
                if (deleteMirrorFile(path)) {
                    state.invalidate(path)
                    events.add(path)
                }
            }
        }
        synchronized(applyLock) { state.persist() } // one flush per batch (M1 cadence)
        if (events.size >= OVERFLOW_THRESHOLD) onChange(ContentStore.OVERFLOW) else events.forEach(onChange)
    }

    // ---- Boot hydration (M1 boot-diff + the R16 fail-closed self-check) -----------------------

    /**
     * The M1 boot-diff hydration, called by boots (serve/adopt/reindex) INSIDE the DataDirLock
     * region - never constructor work. Boot is single-threaded through that lock, so this holds NO
     * monitor at all, and still never holds one across a GET (fetch-then-apply per chunk).
     *
     * The first LIST doubles as the R16 fail-closed TLS/signature self-check: a TLS-, signature-, or
     * connect-rejected LIST throws the operator-actionable refusal the caller surfaces via the
     * System.err + exit(1) idiom. There is no degraded boot and no trust-all retry.
     *
     * The delete-absent step EXCLUDES [dirtyPaths] - and that is safe for a load-bearing REASON, not
     * luck: `WritePipeline.write` marks the page dirty BEFORE calling `compareAndSwapWrite`
     * (mark-precedes-CAS), so a page whose bytes may be mid-flight to the bucket is ALWAYS present in
     * the journal; boot can never reap an unpushed dirty edit before `reconcileDirtyPages` runs.
     *
     * C5 FORK 1 - [strict]: on the RESTORE path (a bundle-restore/reconcile is owed, `Application.kt`
     * passes `strict = restored.isRestored`), any of the THREE best-effort deferral sites below - (a) a
     * GET failure/404-while-listed, (b) a mirror-WRITE failure after a good GET, (c) a delete-phase
     * failure - THROWS instead of deferring, aborting the boot (which retries via the :128-135 idiom,
     * with the FORK-2 sentinel keeping the reconcile owed until a strict hydrate fully succeeds).
     * Reason: any of the three leaves the mirror INCOMPLETE, and `GitBundleDr`'s reconcile enumerates
     * [authoritativeMirrorPaths] AFTER hydrate - a successful-GET-but-failed-WRITE key silently absent
     * from the mirror would make the reconcile's remove-set FALSE-DELETE it from history (HOLE A
     * reopening). WARM object boots pass `strict = false` (unchanged C4 best-effort behavior).
     */
    fun hydrate(strict: Boolean = false) {
        Files.createDirectories(mirrorRoot) // deferred out of the factory so PREVIEW (no hydrate) never mkdirs
        // The mirror we are about to fill is the tree this store now answers for: bind liveness to THAT directory
        // (see [available]). Re-bound on every hydrate, because every hydrate re-materializes the mirror.
        mirrorProbe.set(rootLivenessProbe(mirrorRoot))
        val before = state.snapshot()
        val listed = try {
            listBucket()
        } catch (e: Exception) {
            throw bootRefusal(e)
        }
        // Re-fetch a key when its bucket etag differs from state OR its mirror file is MISSING: DATA_DIR/
        // mirror is deletable derived state, so a deleted mirror file whose state entry survived must
        // self-heal on boot (never vanish from the rebuilt index until mirror-state is also wiped).
        val changed = listed.filter { (path, entry) -> before[path] != entry.etag || !mirrorHasRaw(entry.rawRelative) }
        var healed = 0
        for (chunk in changed.entries.chunked(FETCH_CHUNK)) {
            // Bounded-parallel GETs, then the chunk's applies strictly AFTER the fetches complete. Each
            // result is (path, fetched-or-null): a null is a GET failure OR a 404-while-LIST-reported-it.
            val gate = Semaphore(FETCH_PARALLELISM)
            val results = runBlocking {
                chunk.map { (path, entry) ->
                    async {
                        gate.withPermit {
                            try {
                                path to client.get(keyPrefix + entry.rawRelative)?.let { entry to it }
                            } catch (e: Exception) {
                                logger.warn {
                                    "hydrate GET of '${path.value}' failed (${causeOf(e)}); invalidating so a later poll re-detects"
                                }
                                path to null
                            }
                        }
                    }
                }.awaitAll()
            }
            // The fetch-apply phase writes the AUTHORITATIVE bucket bytes over the mirror WITHOUT the
            // dirtyPaths() guard the delete phase below carries - and that asymmetry is correct, not an
            // omission: mutators PUT to the bucket BEFORE writing the mirror (bucket-first), so the mirror
            // never holds an edit the bucket lacks. Overwriting a dirty page's mirror bytes with the
            // bucket's current bytes therefore cannot lose a durable edit (if the edit reached the bucket,
            // that IS what we write back; if it did not, reconcileDirtyPages drift-skips the stale mark).
            // The delete phase needs the guard because reaping a just-written page whose LIST entry is
            // eventually-consistent-lagged WOULD lose it; fetching cannot, so it does not guard.
            for ((path, fetched) in results) {
                if (fetched == null) {
                    // (a) GET failed or 404'd while the mirror file is missing/stale. Best-effort: DROP the
                    // state entry so the mirror-file-missing / etag diff stays detectable and a later poll
                    // (whose diff is etag-only) or the next hydrate re-fetches - retaining the old etag here
                    // would wedge the key absent forever (poll would see before[path] == listed.etag and never
                    // retry). C5 FORK 1: under `strict`, this leaves the mirror incomplete for a key the
                    // reconcile enumeration will need - abort the boot instead.
                    if (strict) {
                        throw ObjectStoreException(
                            "strict hydrate (restore path): GET of '${path.value}' failed or 404'd while listed; " +
                                "the mirror would be incomplete for the boot reconcile",
                        )
                    }
                    state.invalidate(path)
                    continue
                }
                val (entry, body) = fetched
                // Boot hydrate does a FULL parent scan (fullNfcSweep): it runs once at boot and must catch even a
                // non-canonically-ordered stale sibling the targeted poll sweep cannot name (MINOR-2).
                val failure = mirrorWriteFailure { writeMirrorRaw(entry.rawRelative, body.bytes, fullNfcSweep = true) }
                if (failure == null) {
                    state.recordConfirmed(path, body.etag)
                    healed++
                } else {
                    // (b) Hydrate-apply failure (seam g). Best-effort: boot does NOT fail on a single-key
                    // mirror write error - the bucket stays authority; the key stays absent and re-heals
                    // later. C5 FORK 1: under `strict`, a durable-but-unmirrored key here is exactly the
                    // HOLE A false-delete class - abort the boot instead.
                    if (strict) {
                        throw ObjectStoreException(
                            "strict hydrate (restore path): mirror write of '${path.value}' failed after a successful " +
                                "GET ($failure); the mirror would be incomplete for the boot reconcile",
                        )
                    }
                    logger.warn { "mirror_hydrate_failed: '${path.value}' could not be applied ($failure); the poll will retry" }
                    state.invalidate(path)
                }
            }
            state.persist()
        }
        val dirty = dirtyPaths()
        for (path in (before.keys + mirrorFilePaths()) - listed.keys) {
            if (path in dirty) continue // never reap an unpushed dirty edit (mark-precedes-CAS, above)
            if (deleteMirrorFile(path)) {
                state.invalidate(path) // drop state ONLY after the file is gone (finding 3)
            } else if (strict) {
                // (c) delete-phase failure (a swallowed IOException). C5 FORK 1: under `strict` a file the
                // bucket no longer has must actually be gone before the reconcile enumerates the mirror -
                // a surviving stale file would masquerade as authoritative and be re-committed. Abort.
                throw ObjectStoreException(
                    "strict hydrate (restore path): deleting bucket-absent mirror file '${path.value}' failed; " +
                        "the mirror would be incomplete for the boot reconcile",
                )
            }
        }
        state.persist()
        // Published ONCE, from the accounting the log line already keeps: a hydrate that left ANY object behind
        // hands the rebuild a mirror with holes in it, and a view with holes is not a corpus (see [scan]). A later
        // hydrate that comes back clean clears it; a root that stays deferred simply stays unauthoritative, which
        // is the honest answer until the bucket (or the disk) is fixed.
        val deferred = changed.size - healed
        hydrationDeferred.set(deferred > 0)
        // What the bucket HAS, not what this pass fetched: a hydrate that healed nothing because the mirror was
        // already whole still stands behind every page in it (see [available]).
        hydratedPages.set(listed.isNotEmpty())
        logger.info { "hydrated mirror from the bucket: ${listed.size} object(s), $healed fetched, $deferred deferred" }
        if (deferred > 0) {
            logger.warn {
                "$deferred object(s) could not be hydrated into the mirror: this root serves the pages it DID hydrate " +
                    "but is refused delete authority until a hydrate completes cleanly - nothing of its is deleted, and " +
                    "the poll keeps retrying"
            }
        }
    }

    // ---- Internals -----------------------------------------------------------------------------

    /**
     * The bucket key for [path]: identity on raw bytes, prefix-joined (seam a).
     * `resolveRepoRelativePath` is total, raw-name-preserving and `/`-joined; a fresh create falls
     * back to the NFC `TreePath.value`, the correct fresh form.
     */
    private fun keyOf(path: TreePath): String = keyPrefix + mirror.resolveRepoRelativePath(path)

    /**
     * The Q8b-guarded success apply: mirror write (retried once) + `recordConfirmed` + `persist`,
     * under the apply monitor. Returns null on success; otherwise runs the full Q8b treatment
     * (invalidate + persist + ERROR tag + immediate single-key reconcile) and returns the
     * `durable_but_unmirrored:` cause for the caller's typed result.
     */
    private fun applyConfirmedWrite(path: TreePath, bytes: ByteArray, etag: String): String? {
        val failure = synchronized(applyLock) {
            val failed = mirrorWriteFailure { mirror.write(path, bytes) }
            if (failed == null) {
                state.recordConfirmed(path, etag)
            } else {
                // Q8b: the bucket is durable, the mirror is not - the entry must be ABSENT, never
                // advanced (an advanced entry would let the NEXT save silently overwrite the bucket).
                state.invalidate(path)
            }
            state.persist()
            failed
        } ?: return null
        val cause = "durable_but_unmirrored: $failure"
        logger.error { "$cause - '${path.value}' landed at the bucket but the mirror write failed; reconciling now" }
        reconcileKey(path) // immediate single-key reconcile - do not wait for the poll
        return cause
    }

    /** The immediate single-key reconcile after Q8b: GET -> mirror write retry -> map restore. */
    private fun reconcileKey(path: TreePath) {
        val fetched = try {
            runBlocking { client.get(keyOf(path)) }
        } catch (e: Exception) {
            logger.warn { "single-key reconcile GET of '${path.value}' failed (${causeOf(e)}); the poll will retry" }
            return
        } ?: return
        healMirror(path, fetched)
    }

    /**
     * Eagerly heals a create target whose mirror file was DELETED while its `MirrorState` entry
     * survived (the finding-6 recovery class: DATA_DIR/mirror is deletable derived state), so the
     * mirror gates decide `Created`-vs-`Exists` against a fresh mirror. Only that stale-entry case
     * triggers a read-back; a CLEAN create - and a fresh path with no state entry - issues NO GET, so
     * the happy path stays PUT-only (network-light).
     *
     * NOT gated on `dirtyPaths()` (opus R3): `WritePipeline.create` write-ahead-marks the page dirty
     * BEFORE calling `createExclusive`, so a `dirtyPaths()` disjunct would fire on EVERY pipeline
     * create - a wasteful 404 GET on the happy path. And it buys no correctness: a genuine
     * `durable_but_unmirrored` generation resolves WITHOUT the pre-heal, because the exact-key
     * `IfAbsent` PUT hits the bucket key we wrote and 412s -> [existsAfterRefusedCreate] GET-heals and
     * returns `Exists` (every key Plainbase writes uses [keyOf], so the durable key IS `keyOf(path)`).
     * A DIFFERENT-raw NFC-equivalent key can only come from a FOREIGN writer (pre-existing
     * foreign-normalized bucket, or a concurrent external uploader) - the deferred multi-writer /
     * bucket-lease scope, reconciled by the periodic poll's mirror heal (<= pollSeconds); it is never
     * in `dirtyPaths()` (we never wrote it), so the disjunct could not have caught it anyway.
     */
    private fun healPendingUnmirrored(path: TreePath) {
        if (state.etagOf(path) == null) return // fresh / never-recorded path - no read-back, stay network-light
        // A missing OR unreadable mirror file over a surviving state entry means the mirror is not
        // trustworthy for [path]: heal from the bucket. Catch the read fault so it never escapes the
        // create UNTYPED after WritePipeline's write-ahead dirty mark (BLOCKING 3 class).
        val mirrorMissingOrUnreadable = try {
            mirror.read(path) == null
        } catch (e: Exception) {
            logger.warn { "mirror read of '${path.value}' failed during create pre-check (${causeOf(e)}); healing from the bucket" }
            true
        }
        if (mirrorMissingOrUnreadable) reconcileKey(path)
    }

    /**
     * The universal bucket->mirror heal (seam g): apply [fetched] under the monitor. On a mirror
     * write that still throws after the retry, the entry goes ABSENT (never `recordConfirmed` over a
     * failed write), the failure is WARN-tagged, and the caller proceeds on the authoritative bucket
     * bytes it already holds; the next poll/hydrate or a mutating read-back re-heals.
     */
    private fun healMirror(path: TreePath, fetched: FetchedObject) {
        synchronized(applyLock) {
            val failed = mirrorWriteFailure { mirror.write(path, fetched.bytes) }
            if (failed == null) {
                state.recordConfirmed(path, fetched.etag)
            } else {
                state.invalidate(path)
                logger.warn { "mirror_heal_failed: '${path.value}' could not be healed from the bucket ($failed)" }
            }
            state.persist()
        }
    }

    /** CAS step 5: the precondition refused the PUT - the authoritative GET decides Mismatch/Deleted. */
    private fun readBackAfterPrecondition(path: TreePath, key: String, hasher: (ByteArray) -> String): CasResult {
        val fetched = try {
            runBlocking { client.get(key) }
        } catch (e: Exception) {
            // Nothing landed (the precondition refused the write) - the frozen retryable 503.
            return CasResult.Unreadable(causeOf(e), targetMutated = false)
        } ?: return CasResult.Deleted
        healMirror(path, fetched) // a failed heal still serves the GET'd bytes (seam g)
        return CasResult.Mismatch(currentBytes = fetched.bytes, currentHash = hasher(fetched.bytes))
    }

    /** Q8a: the PUT threw ambiguously - read the authority back and disambiguate exactly. */
    private fun disambiguateCas(
        path: TreePath,
        key: String,
        bytes: ByteArray,
        hasher: (ByteArray) -> String,
        priorEtag: String,
        failure: Exception,
    ): CasResult {
        val stat = try {
            runBlocking { client.head(key) }
        } catch (e: Exception) {
            return outcomeUnknown(path, failure, e) { cause, mutated -> CasResult.Unreadable(cause, mutated) }
        } ?: return CasResult.Deleted // the object is gone - the authority's current state is "absent"
        if (stat.etag == priorEtag) {
            return CasResult.Unreadable(causeOf(failure), targetMutated = false) // the PUT did not land
        }
        val fetched = try {
            runBlocking { client.get(key) }
        } catch (e: Exception) {
            return outcomeUnknown(path, failure, e) { cause, mutated -> CasResult.Unreadable(cause, mutated) }
        } ?: return CasResult.Deleted
        return if (hasher(fetched.bytes) == hasher(bytes)) {
            // OUR put landed after all - complete the normal success apply.
            when (val applyFailure = applyConfirmedWrite(path, bytes, fetched.etag)) {
                null -> CasResult.Written(newHash = hasher(bytes))
                else -> CasResult.Unreadable(applyFailure, targetMutated = true)
            }
        } else {
            healMirror(path, fetched) // an external writer won the race - the standard mismatch path
            CasResult.Mismatch(currentBytes = fetched.bytes, currentHash = hasher(fetched.bytes))
        }
    }

    /** The shared exclusive-create PUT ([createExclusive] and its asset twin, post-gates). */
    private fun exclusivePut(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String, contentType: String?): CreateResult {
        val key = keyOf(path)
        val outcome = try {
            runBlocking { client.put(key, bytes, PutCondition.IfAbsent, contentType = contentType) }
        } catch (e: Exception) {
            return if (isDefinitivePreSend(e)) {
                CreateResult.Unreadable(causeOf(e), targetMutated = false)
            } else {
                disambiguateCreate(path, key, bytes, hasher, failure = e) // Q8a create twin
            }
        }
        return when (outcome) {
            is PutOutcome.Stored -> when (val failure = applyConfirmedWrite(path, bytes, outcome.etag)) {
                null -> CreateResult.Created(newHash = hasher(bytes))
                else -> CreateResult.Unreadable(failure, targetMutated = true) // Q8b create twin (C2 field USED)
            }
            is PutOutcome.PreconditionFailed -> existsAfterRefusedCreate(path, key) // Q8d
        }
    }

    /**
     * Q8d/seam e: the `If-None-Match:*` precondition refused - a file already occupies the path.
     * Parity with the local store, which reports the REQUESTED path. When the mirror has no
     * NFC-equivalent yet (an external upload not yet reconciled), GET-heal the key first so the
     * mirror converges; the reported path is the requested one either way.
     */
    private fun existsAfterRefusedCreate(path: TreePath, key: String): CreateResult {
        if (!mirror.gates.nfcEquivalentSiblingExists(mirror.onDiskTarget(path))) {
            val fetched = try {
                runBlocking { client.get(key) }
            } catch (e: Exception) {
                logger.warn { "post-conflict GET-heal of '${path.value}' failed (${causeOf(e)}); the poll will retry" }
                null
            }
            fetched?.let { healMirror(path, it) }
        }
        return CreateResult.Exists(path)
    }

    /** Q8a create twin: an ambiguous `If-None-Match:*` PUT disambiguates by GET. */
    private fun disambiguateCreate(
        path: TreePath,
        key: String,
        bytes: ByteArray,
        hasher: (ByteArray) -> String,
        failure: Exception,
    ): CreateResult {
        val fetched = try {
            runBlocking { client.get(key) }
        } catch (e: Exception) {
            return outcomeUnknown(path, failure, e) { cause, mutated -> CreateResult.Unreadable(cause, mutated) }
        } ?: return CreateResult.Unreadable(causeOf(failure), targetMutated = false) // absent: nothing landed
        return if (hasher(fetched.bytes) == hasher(bytes)) {
            when (val applyFailure = applyConfirmedWrite(path, bytes, fetched.etag)) {
                null -> CreateResult.Created(newHash = hasher(bytes))
                else -> CreateResult.Unreadable(applyFailure, targetMutated = true)
            }
        } else {
            healMirror(path, fetched)
            CreateResult.Exists(path)
        }
    }

    /** Q8a step 2: the disambiguating HEAD/GET itself failed - the outcome is honestly unknown. */
    private inline fun <T> outcomeUnknown(path: TreePath, failure: Exception, disambiguation: Exception, wrap: (String, Boolean) -> T): T {
        val cause = "outcome_unknown: ${causeOf(disambiguation)}"
        logger.error {
            "$cause - '${path.value}' PUT failed (${causeOf(failure)}) and the read-back also failed; " +
                "mark retained, recovery is hydrate-then-reconcile"
        }
        return wrap(cause, true)
    }

    /** Q8c audit trail: HEAD-and-warn, strictly observational - a null or a throw never gates. */
    private fun auditHead(path: TreePath, key: String) {
        val prior = state.etagOf(path)
        val stat = try {
            runBlocking { client.head(key) }
        } catch (e: Exception) {
            logger.debug { "write audit HEAD for '${path.value}' failed (${causeOf(e)}); proceeding (the audit never gates)" }
            return
        }
        when {
            stat == null -> logger.debug { "write audit: '${path.value}' is absent at the bucket; proceeding (last-writer-wins)" }
            prior != null && stat.etag != prior ->
                logger.warn {
                    "write audit: '${path.value}' drifted at the bucket (etag ${stat.etag} != recorded $prior); proceeding (last-writer-wins)"
                }
        }
    }

    /** Q8c: the unconditional PUT is idempotent, so retry once on ANY failure, then throw. */
    // Stamps `text/markdown` unconditionally: [write] is markdown-only BY CONTRACT today (its sole caller is
    // the page-write path; assets go through the null-content-type asset path). If a non-markdown caller is
    // ever added, derive the content-type from the path/caller here rather than widening this constant.
    private fun putUnconditionalWithRetry(key: String, bytes: ByteArray): PutOutcome.Stored {
        lateinit var last: Exception
        repeat(2) { attempt ->
            try {
                return when (val outcome = runBlocking { client.put(key, bytes, PutCondition.None, contentType = MARKDOWN) }) {
                    is PutOutcome.Stored -> outcome
                    is PutOutcome.PreconditionFailed ->
                        throw ObjectStoreException("unconditional PUT of '$key' reported precondition status ${outcome.status}")
                }
            } catch (e: Exception) {
                last = e
                if (attempt == 0) logger.warn { "unconditional PUT of '$key' failed (${causeOf(e)}); retrying once (idempotent)" }
            }
        }
        throw last
    }

    /** Retries the mirror write once (seam g); returns null on success, else the last failure detail. */
    private fun mirrorWriteFailure(write: () -> Unit): String? {
        var lastError: String? = null
        repeat(2) {
            try {
                write()
                return null
            } catch (e: Exception) {
                lastError = e.message ?: e::class.simpleName ?: "io error"
            }
        }
        return lastError
    }

    /**
     * LISTs the whole [keyPrefix] space through the eligibility funnel (seam a): wire decode
     * ([S3WireKey]) -> prefix strip -> per-segment ignore check + the NFC [TreePath] parse, with B3
     * winner resolution for NFC-colliding keys. Network - never called under the monitor. The
     * parser's fail-closed truncation guard is inherited per page.
     */
    private fun listBucket(): Map<TreePath, ListedEntry> {
        val entries = mutableMapOf<TreePath, ListedEntry>()
        runBlocking {
            client.forEachListedObject(keyPrefix) { wire ->
                val raw = S3WireKey.decode(wire.key)
                if (!raw.startsWith(keyPrefix)) {
                    logger.warn { "skipping bucket key outside the configured prefix: '$raw'" }
                    return@forEachListedObject
                }
                val relative = raw.removePrefix(keyPrefix)
                val path = eligibleTreePath(relative) ?: return@forEachListedObject
                val existing = entries[path]
                when {
                    existing == null -> entries[path] = ListedEntry(relative, wire.etag)
                    RawByteOrder.compare(relative, existing.rawRelative) < 0 -> {
                        logger.warn {
                            "NFC key collision at '${path.value}': winner raw='$relative', loser raw='${existing.rawRelative}'"
                        }
                        entries[path] = ListedEntry(relative, wire.etag)
                    }
                    else -> logger.warn {
                        "NFC key collision at '${path.value}': winner raw='${existing.rawRelative}', loser raw='$relative'"
                    }
                }
            }
        }
        return entries
    }

    /**
     * The ONE eligibility predicate (seam a), shared by the hydrate GET-set, the poll diff, and the
     * delete phase: every decoded segment passes the ignore rules (dotfiles - so `.git` and the
     * reserved `.plainbase/` prefix are invisible by existing law) AND the NFC-normalized form
     * parses as a [TreePath] (the R8 funnel). Ineligible keys are skipped with a warn; the expected
     * app-owned `.plainbase/` prefix logs debug only.
     */
    private fun eligibleTreePath(rawRelative: String): TreePath? {
        // The DECISION is single-sourced in [MirrorKeyFunnel] (so the native funnel test exercises the
        // real logic, not a copy); this wrapper only adds the operational skip logging. The SECURITY
        // escape guard runs first: a foreign/hostile key (`..\x`, `C:/x`) that would land outside the
        // mirror on a Windows host is rejected before it can become a ListedEntry reaching writeMirrorRaw
        // or the delete phase.
        val path = MirrorKeyFunnel.eligible(rawRelative, mirrorRoot, ignoreRules)
        if (path == null) {
            when {
                MirrorKeyFunnel.escapesRoot(rawRelative, mirrorRoot) ->
                    logger.warn { "skipping bucket key that could escape the mirror root: '$rawRelative'" }
                rawRelative.startsWith(APP_OWNED_PREFIX) -> logger.debug { "skipping app-owned bucket key: '$rawRelative'" }
                else -> logger.warn { "skipping ineligible bucket key (ignored or unparseable): '$rawRelative'" }
            }
        }
        return path
    }

    /**
     * Writes [bytes] at the LITERAL raw relative location under the mirror root (temp-sibling +
     * ATOMIC_MOVE): hydration/poll preserve the bucket's raw byte-form verbatim so the inner store's
     * next scan applies NFC normalization, B3 collision resolution, and P4 raw-name retention to
     * those files exactly as it does locally (the point of the hybrid composition).
     */
    private fun writeMirrorRaw(rawRelative: String, bytes: ByteArray, fullNfcSweep: Boolean) {
        // SECURITY sandbox guard (write-sink side, belt-and-suspenders behind the funnel): NEVER create a
        // dir or write bytes for a key that resolves outside the mirror root. Throwing here routes through
        // `mirrorWriteFailure` so the apply is treated as NOT applied (the caller invalidates, never
        // recordConfirmed for a skipped-unsafe key).
        if (MirrorKeyFunnel.escapesRoot(rawRelative, mirrorRoot)) {
            logger.warn { "refusing mirror write of a key that escapes the mirror root: '$rawRelative'" }
            throw ObjectStoreException("mirror key '$rawRelative' resolves outside the mirror root")
        }
        val target = mirrorRoot.resolve(rawRelative)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".pbtmp", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                atomics.atomicMove(tmp, target)
            } catch (_: AtomicMoveNotSupportedException) {
                logger.warn { "ATOMIC_MOVE unsupported for '$rawRelative'; falling back to copy+delete (non-atomic)" }
                atomics.copyReplace(tmp, target)
            }
            // The bucket keys by RAW bytes; LocalContentStore keys reads by NFC TreePath. If the bucket's raw
            // key for this path changed (an NFC-equivalent re-upload / a raw-byte swap), the OLD raw file would
            // survive and could win B3 collision resolution, serving a stale generation. Sweep any NFC-equivalent
            // sibling that is NOT this exact raw name so one raw file backs each TreePath.
            //
            // O2: the sweep runs on EVERY apply-write, NOT only on a fresh ADD. A `replacingInPlace` skip (target
            // already exists) would let a RETRY after a failed sweep - OR after a failed rollback-delete
            // (double-fault) - see the target and SKIP the sweep, then `recordConfirmed` over a surviving stale
            // sibling. Always-sweep makes the double-fault non-stranding: any retry / next poll re-sweeps until it
            // succeeds, so a confirmed apply NEVER leaves a stale NFC sibling.
            //
            // MINOR-2: the warm poll passes fullNfcSweep=false so it names only THIS key's NFC/NFD sibling(s)
            // instead of scanning the whole parent - a full scan per write is O(N^2) over a flat mega-dir on a
            // bulk poll. Boot hydrate passes true (one full scan per write, boot-only) to also catch a rare
            // non-canonically-ordered sibling the targeted names cannot enumerate.
            try {
                if (fullNfcSweep) removeStaleNfcSiblings(target) else removeStaleNfcSiblingsTargeted(target)
            } catch (e: IOException) {
                // A sweep FAILURE fails the whole apply (a surviving NFC-sibling can serve stale bytes). Roll back
                // the just-added target; the caller invalidates and the next poll/hydrate re-applies AND re-sweeps.
                // For a REPLACE this delete leaves the path ABSENT (the atomic move already displaced the old file),
                // not the pre-apply bytes - a transient read-miss the invalidate + re-fetch self-heals, never stale.
                runCatching { Files.deleteIfExists(target) }.onFailure { rollbackErr ->
                    logger.warn {
                        "rollback delete of '$rawRelative' after a failed NFC sweep ALSO failed " +
                            "(${causeOf(rollbackErr)}); the next apply always re-sweeps, so this cannot strand the stale sibling"
                    }
                }
                throw ObjectStoreException("mirror NFC-sibling sweep failed for '$rawRelative': ${causeOf(e)}", e)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** True iff the mirror already holds the exact raw file [rawRelative] names (the finding-1 self-heal probe). */
    private fun mirrorHasRaw(rawRelative: String): Boolean = Files.exists(mirrorRoot.resolve(rawRelative))

    /**
     * The TARGETED sibling sweep for the warm poll path (MINOR-2): removes only the NFC- and NFD-encoded
     * leaf names of [target] that resolve to a DIFFERENT file, without a whole-directory scan (which is
     * O(N^2) over a flat mega-dir on a bulk poll). A canonically-equivalent re-upload stores at most those
     * two canonical byte-forms; a rare non-canonically-ordered sibling is left for the boot hydrate's full
     * [removeStaleNfcSiblings] scan. THROWS on an I/O failure exactly like the full sweep (same fail-the-apply
     * contract). [Files.isSameFile] skips [target] itself by inode - on a folding filesystem (macOS/Windows)
     * the NFC and NFD names resolve to the just-written file, so a name compare would delete what we healed.
     */
    private fun removeStaleNfcSiblingsTargeted(target: Path) {
        val parent = target.parent ?: return
        val name = target.fileName.toString()
        val candidates = setOf(Nfc.normalize(name), Nfc.decompose(name)) - name
        for (candidateName in candidates) {
            val entry = parent.resolve(candidateName)
            if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isSameFile(entry, target)) continue
            logger.warn { "removing stale NFC-equivalent mirror sibling '$candidateName' superseded by '$name'" }
            Files.deleteIfExists(entry)
        }
    }

    /**
     * Removes any NFC-equivalent leaf sibling of [target] that is a DIFFERENT file, so one raw file per
     * TreePath. THROWS on failure (B-C2): the sweep is load-bearing - a surviving NFC-sibling can win a
     * `LocalContentStore` scan/read and serve a stale generation, so a failed sweep must fail the whole
     * apply (the caller rolls back and never `recordConfirmed`s), never be swallowed as a WARN. Boot/hydrate
     * only (fullNfcSweep); the warm poll uses [removeStaleNfcSiblingsTargeted].
     */
    private fun removeStaleNfcSiblings(target: Path) {
        val parent = target.parent ?: return
        val wantNfc = Nfc.normalize(target.fileName.toString())
        withDirectoryStream(parent) { stream ->
            for (entry in stream) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue
                if (Nfc.normalize(entry.fileName.toString()) != wantNfc) continue
                // Skip [target] itself by IDENTITY, never by name string: on a normalization-INSENSITIVE
                // filesystem (macOS/Windows) the just-written target and an NFD-named entry are the SAME
                // inode whose directory-entry name differs in code units - a name compare would delete the
                // file we just healed. On a preserving filesystem (Linux) they are distinct inodes to sweep.
                if (Files.isSameFile(entry, target)) continue
                logger.warn { "removing stale NFC-equivalent mirror sibling '${entry.fileName}' superseded by '${target.fileName}'" }
                Files.deleteIfExists(entry)
            }
        }
    }

    /**
     * EVERY bucket-managed file the mirror currently holds, as eligible [TreePath]s. [scan] refreshes
     * the snapshot (so [deleteMirrorFile]'s [LocalContentStore.onDiskTarget] resolves NFD raw names
     * correctly), but `scan().files` EXCLUDES folder-metadata sidecars (`_folder.yaml`) - and those ARE
     * bucket-managed (an operator/adopted bucket can hold them; hydrate/poll sync them). A folder whose
     * `_folder.yaml` is present carries non-null [ContentFolder.meta], so add those sidecar paths too:
     * without them the delete-absent phase could not sweep an orphaned sidecar when mirror-state is
     * empty/corrupt (its `before.keys` would be empty too), leaving stale folder title/slug metadata
     * driving URLs and the tree.
     */
    private fun mirrorFilePaths(): Set<TreePath> {
        val scanned = scan()
        val sidecars = scanned.folders.filter { it.meta != null }.map { it.path.resolveChild(FOLDER_META_NAME) }
        return (scanned.files.map { it.path } + sidecars).toSet()
    }

    /**
     * C5 BLOCKING 1: the intention-revealing internal accessor over [mirrorFilePaths] the boot
     * reconcile (`GitBundleDr`) enumerates against - AFTER a strict hydrate, so this equals the bucket
     * listing by construction (HOLE A: the reconcile remove-set keys off THIS authority, never raw
     * disk-absence). `internal` is visible to `GitBundleDr` across packages within the `:server` module.
     */
    internal fun authoritativeMirrorPaths(): Set<TreePath> = mirrorFilePaths()

    /**
     * C5: STREAMS the bucket-shipped `.git` bundle to [target], returning true when found or false on a true
     * 404 (no bundle has ever shipped - a fresh install / an abandoned restore). Any OTHER failure PROPAGATES
     * (HOLE C): [client] throws on a non-404 status, so a TLS/connect blip is never misread as "no bundle" -
     * that would let [restore] init a fresh empty repo the next ship would then CLOBBER over real history.
     *
     * B-C3: streamed straight to disk (never a whole-body in-heap array), so a bundle exceeding available heap
     * on a smaller recovery host cannot throw `OutOfMemoryError` and escape the DR boot refusal; and given a
     * bundle-appropriate request timeout, not the short page-op one, so a large bundle transfer does not time
     * out (an unbootable restore loop). The restore caller catches `Throwable` and routes it through [bootRefusal].
     */
    fun fetchHistoryBundleTo(target: Path): Boolean =
        runBlocking { client.getToFile(bundleKey, target, requestTimeoutMillis = BUNDLE_TRANSFER_TIMEOUT_MILLIS) }

    /**
     * C5: ships the DR bundle unconditionally (`PutCondition.None` - see the invariant comment at
     * [GitBundleDr.ship]'s call site), with the bundle-appropriate request timeout (B-C3): a large bundle must
     * not time out on the short page-op bound and leave the DR artifact permanently stale.
     */
    fun putHistoryBundle(bytes: ByteArray) {
        runBlocking {
            client.put(bundleKey, bytes, PutCondition.None, contentType = null, requestTimeoutMillis = BUNDLE_TRANSFER_TIMEOUT_MILLIS)
        }
    }

    /**
     * C5: ships the DR bundle by STREAMING [source] straight to the PUT body (B-C3), never materializing the
     * whole bundle in heap - so a later cadence ship on a memory-constrained host cannot OOM and strand the
     * DR artifact. Unconditional, bundle-timeout, symmetric with [fetchHistoryBundleTo].
     */
    fun putHistoryBundleFrom(source: Path) {
        runBlocking {
            client.putFromFile(bundleKey, source, contentType = null, requestTimeoutMillis = BUNDLE_TRANSFER_TIMEOUT_MILLIS)
        }
    }

    /**
     * `<prefix/>.plainbase/history.bundle` - under the reserved [APP_OWNED_PREFIX] dot-skip law
     * ([MirrorKeyFunnel]'s eligibility check, wrapped by [eligibleTreePath]) so it is invisible to
     * scan/hydrate/poll like every other app-owned key.
     */
    private val bundleKey get() = keyPrefix + APP_OWNED_PREFIX + "history.bundle"

    /**
     * Deletes [path]'s mirror file (P4 raw-name-aware) and drops now-empty parent directories. Returns
     * true iff the file is now absent (deleted, or already gone); false iff the delete FAILED, so the
     * caller keeps the state entry rather than invalidating/emitting over a file that still serves.
     */
    private fun deleteMirrorFile(path: TreePath): Boolean {
        val target = mirror.onDiskTarget(path)
        logger.info { "deleting mirror file absent from the bucket: ${path.value}" }
        return try {
            Files.deleteIfExists(target)
            dropEmptyParents(target.parent)
            true
        } catch (e: IOException) {
            logger.warn { "mirror delete of '${path.value}' failed (${causeOf(e)}); keeping state, retrying next cycle" }
            false
        }
    }

    private fun dropEmptyParents(start: Path?) {
        var dir = start?.toAbsolutePath()?.normalize()
        while (dir != null && dir != mirrorRoot && dir.startsWith(mirrorRoot)) {
            try {
                val empty = Files.isDirectory(dir) && withDirectoryStream(dir) { !it.iterator().hasNext() }
                if (!empty) return
                Files.delete(dir)
            } catch (_: IOException) {
                return
            }
            dir = dir.parent
        }
    }

    /**
     * Ambiguity classification (seam b): a failure where the request provably never went out
     * (connect refusal/timeout, DNS) is DEFINITIVE; everything else is AMBIGUOUS and read-back
     * disambiguates. Over-classifying as ambiguous is safe by construction (Q8a resolves a
     * never-sent PUT to the same outcome), so when in doubt, read back.
     */
    private fun isDefinitivePreSend(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any {
            it is ConnectException || it is UnresolvedAddressException || it is ConnectTimeoutException
        }

    /**
     * The R16 fail-closed boot refusal: TLS/signature/connect rejections name their remedy. `internal`
     * (not private) so [com.plainbase.frameworks.git.GitBundleDr.restore] can reuse the SAME
     * classification for its own pre-hydrate bucket GET (`fetchHistoryBundleTo`) - a non-404 transport/
     * credential failure there must surface this operator-actionable message too, never a raw exception.
     */
    internal fun bootRefusal(failure: Throwable): Exception {
        val tls = generateSequence<Throwable>(failure) { it.cause }.any { it is javax.net.ssl.SSLException }
        val message = when {
            tls ->
                "object storage TLS self-check failed against the configured endpoint: ${causeOf(failure)}. " +
                    "Fix the endpoint or the CA trust of this host; never disable certificate validation to fix this."
            isDefinitivePreSend(failure) ->
                "object storage endpoint is unreachable: ${causeOf(failure)}. " +
                    "Check storage.object.endpoint and network reachability; object-mode boot requires the bucket."
            else ->
                // "self-check" covers BOTH callers sharing this classification: hydrate's own first LIST,
                // and GitBundleDr.restore's pre-hydrate bundle GET (a non-404 failure there is HOLE C).
                "object storage self-check failed: ${causeOf(failure)}. " +
                    "Check the endpoint, bucket, credentials, and this host's clock (SigV4); " +
                    "never disable certificate validation to fix this."
        }
        return ObjectStoreException(message, failure) // chain the original (keep its stack), not just its message
    }

    private fun causeOf(failure: Throwable): String = failure.message ?: failure::class.simpleName ?: "failure"

    private class ListedEntry(val rawRelative: String, val etag: String)

    companion object {
        /** R9 test hook: LOCAL boot must construct ZERO hybrids - proven by counter, never reasoned. */
        internal val constructions = AtomicInteger()

        /**
         * B-C3: the request-timeout for the DR-bundle transfer (GET-to-file on restore, ship PUT), well above
         * the short page-op timeout so a large bundle over a slow link neither aborts a restore nor leaves the
         * DR artifact permanently stale. Bounded (not infinite) so a genuinely hung endpoint still fails a boot
         * rather than blocking it forever; the connect timeout guards the initial reach either way.
         */
        internal const val BUNDLE_TRANSFER_TIMEOUT_MILLIS: Long = 10 * 60 * 1000

        /** The reserved app-owned bucket prefix (dot-ignored by law; C5's history bundle lives here). */
        private const val APP_OWNED_PREFIX = ".plainbase/"

        private const val MARKDOWN = "text/markdown"

        /** Bulk drift folds into one synthetic overflow event - consumers only schedule (§B2). */
        private const val OVERFLOW_THRESHOLD = 16

        private const val FETCH_CHUNK = 64
        private const val FETCH_PARALLELISM = 16

        private val logger = KotlinLogging.logger {}
    }
}

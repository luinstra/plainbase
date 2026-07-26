package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.TreePath
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The strictly READ-ONLY create gate: every pre-write check a create must pass, factored out of
 * [LocalContentStore] so a second backend can run the SAME gates against its local mirror BEFORE the
 * authoritative write — one source of truth, per [com.plainbase.domain.content.ContentStore
 * .writeAssetExclusive]'s rule that containment guards are never re-derived weaker.
 *
 * Read-only is the load-bearing property: nothing here mutates the filesystem (no create/move/delete
 * NIO calls, verifiable by inspection), so a create these gates reject provably left ZERO side effects
 * — no freshly-minted parent dir, nothing to roll back. [LocalContentStore] recomposes
 * [resolveParent]'s report with its own `Files.createDirectory` per missing segment for byte-identical
 * local behavior; a remote-authority adapter runs the gates and only then mutates its authority.
 *
 * [root] is the content root the owning store serves; [excludedDirs] is the EFFECTIVE exclusion set
 * (strictly inside root) that store computed — the single policy shared with scan/watch, so the gate
 * can't over-reject the `PlainbaseConfig`-legal layout where DATA_DIR is a strict ANCESTOR of root.
 */
internal class CreateGates(
    private val root: Path,
    private val ignoreRules: IgnoreRules,
    private val excludedDirs: List<Path>,
) {

    /**
     * The read-only report of [resolveParent]: the deepest EXISTING on-disk ancestor
     * ([existingPrefix]; the content root when no parent segment exists yet), the parent segments
     * still [missing] beneath it (NFC names, outermost first — nothing beneath a missing dir can
     * exist, so the tail is contiguous), and whether a segment is [occupiedByFile] (an NFC-equivalent
     * non-directory holds it; the create then surfaces as `CreateResult.Exists` — a page cannot be
     * created under a file).
     */
    data class ParentResolution(
        val existingPrefix: Path,
        val missing: List<String>,
        val occupiedByFile: Boolean,
    )

    /**
     * Resolves [path]'s on-disk PARENT directory without creating anything: descends
     * segment-by-segment, REUSING an existing on-disk child that NFC-normalizes to the segment (the
     * P2 NFC-parent guard — an external process may have added a raw non-NFC parent, e.g. NFD `café/`,
     * after the last scan; minting a duplicate NFC sibling would split the subtree and get the new
     * page excluded on the next rebuild). The first segment with no equivalent starts the [missing]
     * tail; the owning store creates exactly those.
     */
    fun resolveParent(path: TreePath): ParentResolution {
        val segments = path.parent?.segments ?: return ParentResolution(root, emptyList(), occupiedByFile = false)
        var dir = root
        for ((index, segment) in segments.withIndex()) {
            val existing = nfcEquivalentChild(dir, segment)
                ?: return ParentResolution(dir, segments.drop(index), occupiedByFile = false)
            if (!Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) {
                return ParentResolution(dir, emptyList(), occupiedByFile = true) // a file holds this segment
            }
            dir = existing // reuse the existing (possibly non-NFC raw-named) dir — no duplicate
        }
        return ParentResolution(dir, emptyList(), occupiedByFile = false)
    }

    /**
     * The create-containment gate: returns a rejection reason iff the requested [path] can never
     * legitimately name content, or null when a create may proceed. Fails closed (an [IOException]
     * resolving the real path is "not contained"). Three guards, mirroring the scan/read invariants:
     *  1. **Scan-skipped-name segment** — any ancestor (or the leaf) whose NAME the scan would skip
     *     ([isScanSkippedName]: `_folder.yaml`, dotfile, `content.ignore` glob), or a segment under an
     *     excluded subtree (DATA_DIR) → a ghost the next rebuild discards, so refuse it up front. The
     *     name predicate is the SAME one the scan's candidate filter uses, so the create-reject set
     *     cannot drift from scan's skip set (this is what closes the "scan skips X but create allows
     *     it" class — dotfiles, `_folder.yaml`).
     *  2. **Symlinked existing ancestor** — links are not content; an existing ancestor directory that
     *     is a symlink would let a create write THROUGH it (the scan never enters it), so refuse.
     *  3. **Real-path escape** — the nearest EXISTING ancestor's resolved real path must stay inside
     *     root's real path, so a symlink pointing outside the root (or any escape) is caught even when
     *     the lexical [TreePath] looks contained.
     */
    fun rejectionReason(path: TreePath, target: Path): String? {
        val onDiskParent = target.parent
        return scanSkippedSegmentReason(path)
            ?: excludedSubtreeReason(onDiskParent)
            ?: existingAncestorReason(onDiskParent)
    }

    private fun scanSkippedSegmentReason(path: TreePath): String? {
        // (1) Scan-skipped name: check each content-relative segment along the path against the SAME
        // name-skip predicate scan uses, so no scan-skipped name (incl. `_folder.yaml`) can be created.
        var relative: TreePath? = null
        for (segment in path.segments) {
            relative = relative?.resolveChild(segment) ?: TreePath.require(segment)
            if (isScanSkippedName(segment, relative.value)) {
                return "segment '$segment' is one the scan skips (_folder.yaml / dotfile / ignore glob — not content)"
            }
        }
        return null
    }

    private fun excludedSubtreeReason(onDiskParent: Path?): String? =
        // (2)+(3) Walk the existing ancestor directories, root-exclusive: none may be a symlink, and the
        // nearest existing one must resolve inside root. The store resolves [target] with the same
        // P4-aware resolution the create itself uses, so the check sees exactly the dirs the create
        // would create under / write through.
        // excludedDirs is the EFFECTIVE set (strictly inside root), shared with scan — so an ancestor
        // DATA_DIR (a legal layout) is absent here and can't make every create reject; only a DATA_DIR
        // genuinely nested under root matches and rejects a target beneath it.
        when {
            onDiskParent != null && excludedDirs.any { onDiskParent.toAbsolutePath().normalize().startsWith(it) } ->
                "target lies under an excluded subtree (DATA_DIR)"

            else -> null
        }

    private fun existingAncestorReason(onDiskParent: Path?): String? {
        // Walk the on-disk ancestor dirs from the target's parent up to (and including) root. Stop once
        // we reach root; never inspect a dir outside it. The FIRST existing one is the nearest existing
        // ancestor whose real path must stay inside root.
        var nearestExisting: Path? = null
        var ancestor: Path? = onDiskParent
        while (ancestor != null && ancestor.startsWith(root)) {
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                if (ancestor != root &&
                    Files.isSymbolicLink(ancestor)
                ) {
                    return "an existing ancestor directory is a symlink (links are not content)"
                }
                // A folder segment that names an existing NON-directory (a regular file) can never be a
                // parent — Files.createDirectories would throw. That is a PERMANENT client error (400),
                // not the retryable Unreadable (503) the IOException catch would otherwise surface.
                if (ancestor != root && !Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                    return "an existing ancestor path is a file, not a directory"
                }
                if (nearestExisting == null) nearestExisting = ancestor
            }
            if (ancestor == root) break
            ancestor = ancestor.parent
        }
        return when {
            nearestExisting != null && !isWithinRoot(root, nearestExisting) ->
                "the target resolves outside the content root"

            else -> null
        }
    }

    /**
     * True iff an existing entry in [target]'s parent directory NFC-normalizes to the SAME leaf name as
     * [target] — i.e. a non-NFC sibling the scan has not yet seen would collide with this create under
     * the scan's [Nfc] normalization. The parent is freshly listed (creates are rare; one dir read is
     * fine); a missing/unreadable parent simply yields false (the exclusive create then decides).
     */
    fun nfcEquivalentSiblingExists(target: Path): Boolean {
        val parent = target.parent ?: return false
        val wantNfc = Nfc.normalize(target.fileName.toString())
        return try {
            withDirectoryStream(parent) { stream ->
                stream.any { Nfc.normalize(it.fileName.toString()) == wantNfc }
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * The existing child of [dir] that the scan would INDEX for the NFC [segment], or null. When two or
     * more raw on-disk names NFC-collide to [segment] (a real B3 collision on a byte-preserving FS),
     * `scan()` keeps a single WINNER by [RawByteOrder] (unsigned-byte-first) and excludes the losers —
     * so the create MUST resolve to that same winner, else it could write under a loser dir whose whole
     * subtree the next rebuild excludes (a 201 with an unindexed file). This mirrors the scan's
     * collision resolution (`sortedWith(compareBy(RawByteOrder) { rawName }).first()`) byte-for-byte,
     * over the same scan-eligible candidates (symlinks / `_folder.yaml` / ignored / excluded entries
     * are NOT content, exactly as the scan filters them, so they never win the segment).
     */
    private fun nfcEquivalentChild(dir: Path, segment: String): Path? {
        // The `/`-joined on-disk-relative prefix of [dir], for the glob-ignore check (root → null).
        val dirPrefix = root.relativize(dir).joinToString("/").takeIf { it.isNotEmpty() }
        return try {
            withDirectoryStream(dir) { stream ->
                stream
                    .filter { child -> Nfc.normalize(child.fileName.toString()) == segment && isScanEligible(child, dirPrefix) }
                    .minWithOrNull(compareBy(RawByteOrder) { it.fileName.toString() })
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * The SINGLE source of truth for "scan would skip a segment by NAME alone" (independent of whether
     * it exists on disk yet): the `_folder.yaml` metadata sidecar ([FOLDER_META_NAME]) OR an
     * [IgnoreRules]-ignored name (dotfile / `content.ignore` glob). The scan's candidate filter applies
     * exactly these name skips, so both the create-reject gate ([rejectionReason]) and the
     * scan-eligibility filter ([isScanEligible]) defer to this — a created page can never land at a
     * name scan won't index. (The on-disk-entry skips — excluded DATA_DIR subtree, symlink — are
     * existence-dependent and stay in [isScanEligible] / the [rejectionReason] ancestor walk.)
     */
    private fun isScanSkippedName(name: String, relativePath: String): Boolean =
        name == FOLDER_META_NAME || ignoreRules.isIgnored(name, relativePath)

    /** Whether [child] (under the `/`-joined [dirPrefix], null at root) is a content candidate — the scan's filter. */
    private fun isScanEligible(child: Path, dirPrefix: String?): Boolean {
        val rawName = child.fileName.toString()
        val relativePath = if (dirPrefix == null) rawName else "$dirPrefix/$rawName"
        if (isScanSkippedName(rawName, relativePath)) return false
        if (child.toAbsolutePath().normalize() in excludedDirs) return false
        return !Files.isSymbolicLink(child)
    }
}

/**
 * Defense-in-depth root containment shared by the read paths and the create gate: resolves symlinks
 * on both [target] and [root] to their real paths and asserts the target stays inside root. Catches a
 * TOCTOU symlink swap between scan and read. Returns false (and the caller refuses) if the target is
 * missing or escapes; an [IOException] resolving the real path is treated as "not contained" — fail
 * closed.
 */
internal fun isWithinRoot(root: Path, target: Path): Boolean =
    try {
        target.toRealPath().startsWith(root.toRealPath())
    } catch (_: IOException) {
        false
    }

/**
 * Iterates [dir]'s children, translating the [DirectoryIteratorException] a mid-walk IO fault raises (an
 * unmounted or permission-revoked root: `UnixDirectoryStream` boxes it at :169/:189) back into the
 * [IOException] it wraps.
 *
 * `DirectoryStream`'s iterator cannot throw a CHECKED exception, so an IO fault DURING iteration comes out
 * as an unchecked wrapper - which every `catch (IOException)` in this codebase silently misses. Unwrapping
 * it HERE, at the one place a directory stream is opened, is what lets each caller keep catching
 * `IOException` and actually SEE the fault it was written to absorb: the scan classifier, the create gates'
 * two best-effort arms, the store's root-loss exit wrapper, and the object/DR sweeps whose own contracts
 * promise an `IOException` (GitBundleDr's reap promises "a failed reap never fails a restore" - a
 * `DirectoryIteratorException` escapes that catch and aborts the boot it was written not to).
 *
 * After this, `IOException` is the TOTAL carrier of every filesystem fault, and a direct
 * `Files.newDirectoryStream` anywhere in the tree is a reintroduced hole.
 *
 * `throw e.cause` does not compile: the JDK's covariant `getCause()` override carries no nullability
 * annotation, so Kotlin narrows it to `IOException?`. Both real throw sites construct the wrapper from a
 * NON-NULL `IOException` by contract, so [checkNotNull] asserts a true invariant - the house idiom.
 */
internal fun <T> withDirectoryStream(dir: Path, body: (DirectoryStream<Path>) -> T): T =
    runCatching {
        Files.newDirectoryStream(dir).use(body)
    }.getOrElse { failure ->
        when (failure) {
            is DirectoryIteratorException ->
                throw checkNotNull(failure.cause) {
                    "DirectoryIteratorException always wraps an IOException (UnixDirectoryStream:169,189)"
                }
            else -> throw failure
        }
    }

/** The glob-filtered twin of [withDirectoryStream], for the DR husk reap. */
internal fun <T> withDirectoryStream(dir: Path, glob: String, body: (DirectoryStream<Path>) -> T): T =
    runCatching {
        Files.newDirectoryStream(dir, glob).use(body)
    }.getOrElse { failure ->
        when (failure) {
            is DirectoryIteratorException ->
                throw checkNotNull(failure.cause) {
                    "DirectoryIteratorException always wraps an IOException (UnixDirectoryStream:169,189)"
                }
            else -> throw failure
        }
    }

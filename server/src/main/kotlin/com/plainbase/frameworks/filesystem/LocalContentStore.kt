@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentEntry
import com.plainbase.domain.content.ContentFile
import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStat
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.FolderMeta
import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.RootUnavailable
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The on-disk name of a folder's metadata sidecar (§A4). Shared with the [CreateGates] name-skip predicate. */
internal const val FOLDER_META_NAME = "_folder.yaml"

/**
 * The java.nio [ContentStore] adapter over a local directory ([root]).
 *
 * Boundary responsibilities (chunk 1):
 *  - **NFC at the boundary, both directions.** On-disk names are NFC-normalized into
 *    [TreePath]s on scan (so a macOS NFD-named file yields an NFC path); reads of a path go
 *    back to disk through the scan-retained **raw** name, never a name re-derived from the NFC
 *    path (P4).
 *  - **NFC path-collision policy (B3).** When two distinct on-disk files in one directory
 *    normalize to a single [TreePath] (possible on normalization-preserving filesystems), the
 *    file whose raw filename bytes sort first (unsigned-byte order) wins; the loser is excluded
 *    from the index; a [ScanIssue.PathCollision] is recorded.
 *  - **Atomic writes.** Temp-sibling + `ATOMIC_MOVE`, falling back to copy+delete on NFS/SMB.
 *    Each intended write is logged before it is performed (interrupted-run detectability).
 *
 * The collision-winner raw-name maps (files and directories) are rebuilt as one immutable
 * snapshot on every [scan] and consulted by [read]/[stat]/[list]/[write], so reads reach the
 * winner's bytes and writes replace the winner's on-disk file rather than shadowing it.
 *
 * **Security policy - indexed-only visibility.** [read]/[stat]/[list] answer ONLY from the
 * retained immutable scan snapshot; they never re-touch disk to decide what is visible. A path
 * the scan skipped - a dotfile/`.git` entry (ignore rules), a symlink (links-are-not-content),
 * or a collision loser - is absent from the snapshot and therefore invisible to every read.
 * This closes the content-root / ignored-file escape at every filesystem access, not just the
 * scan's enumeration loop. [write] still resolves new paths through the total [resolveOnDisk]
 * because creating a not-yet-indexed file is a legitimate operation. Defense-in-depth on the
 * actual read additionally re-checks no-follow root containment against a TOCTOU symlink swap.
 *
 * **Root loss is classified at the operation's EXIT, never at its outcomes** (ADR-0011 D5). Whatever a
 * rooted operation lets a caller OBSERVE is a union of exactly two things - a returned value, or a thrown
 * exception - and BOTH can be produced or REPLACED by anything that runs before the frame pops, a `finally`
 * cleanup included. So the three mutation surfaces and [readClassified] each run their whole body inside
 * [classifyingRootLoss], which re-probes [available] on the way out: root gone -> MARK, then the carrier
 * (a `RootUnavailable` throw for a write; a `ContentRead.RootDown` value for a read); root live -> the
 * outcome passes through byte-unchanged and a genuine fault stays a genuine fault. Closure is then
 * structural - nothing can leave without being seen, and there is no list to keep complete.
 */
class LocalContentStore(
    private val root: Path,
    private val ignoreRules: IgnoreRules = IgnoreRules(),
    exclusions: List<Path> = emptyList(),
    private val atomics: FileAtomics = FileAtomics.Real,
    /** WHICH root this store serves - carried on every [RootUnavailable] it throws. */
    private val rootName: RootName = RootName.MAIN,
    /**
     * Called the instant this store's own probe finds the root gone, immediately BEFORE it answers root-loss
     * (the [FileWatcher] `onFailure` ctor-callback idiom). Marking is load-bearing, not bookkeeping: detection
     * WITHOUT publication would 503 the write while every read, the tree and health kept serving the carried
     * section as AVAILABLE until some later rebuild happened to probe - a contradictory 503-then-stale-success.
     * The frameworks store never depends on the domain holder; the production wiring closes over it.
     */
    private val onRootUnavailable: () -> Unit = {},
    /**
     * The liveness probe [available] runs, seamed for the ONE instant a test cannot otherwise observe: "the
     * root was still there when we ENTERED". No FS state change can happen between two calls inside a single
     * operation, so a test scripts THIS to pass once and every other thing in the row - the failing FS call,
     * the failure arm, the exit classifier, the mark, the throw - stays production code. Production always
     * runs the real three-predicate check.
     */
    private val probeRoot: (Path) -> Boolean = ::rootIsTraversable,
) : ContentStore {

    // App-owned subtrees (DATA_DIR) excluded from BOTH the scan and the watch: a nested data dir
    // must never be indexed - plainbase.db/search.db would otherwise be served as /assets/... -
    // and never re-trigger rebuilds from the app's own writes. One exclusion policy, two consumers.
    //
    // EFFECTIVE exclusions are only those STRICTLY INSIDE root: an exclusion AT or ABOVE root is a no-op,
    // because root is the scan boundary (the walk never ascends past it, and `collectCandidates`'s
    // `child in excludedDirs` membership test can never match a child against an ancestor). This is the
    // SINGLE source of truth shared by scan/watch AND the create-containment gate, so the gate can't
    // over-reject in the `PlainbaseConfig`-legal layout where DATA_DIR is a strict ANCESTOR of root.
    private val excludedDirs: List<Path> =
        exclusions.map { it.toAbsolutePath().normalize() }
            .filter {
                val rootNorm = root.toAbsolutePath().normalize()
                it.startsWith(rootNorm) && it != rootNorm
            }

    // The READ-ONLY pre-write checks (containment, NFC-equivalent occupancy, the resolve-only parent
    // walk), factored out as CreateGates so a second backend can run the SAME gates against its mirror
    // before its authoritative write. This store recomposes the walk with its own directory creation
    // ([resolveOrCreateParent]) - the seam itself never mutates. `internal` (C4 seam f) so the
    // ObjectContentStore hybrid runs the SAME gate instances against its mirror, never a re-derived set.
    internal val gates = CreateGates(root, ignoreRules, excludedDirs)

    /**
     * Immutable snapshot of the most recent [scan]: the indexed files/folders (the membership
     * authority for [read]/[stat]/[list]) plus the `TreePath -> raw on-disk name` maps (P4) used to
     * resolve a [TreePath] back to its exact on-disk byte-form. A collision winner whose raw name is
     * the non-NFC byte-form (and an NFD-named ancestor directory) is reached correctly; an entry the
     * scan skipped is simply absent, so it cannot be read, stat-ed, or listed.
     *
     * Safe publication, no `@Volatile` (S5.0): the Phase-2 watcher rescans on another thread, so
     * each [scan] builds the snapshot entirely off to the side and swaps it in with one
     * [AtomicReference.store] - the house pattern (`IndexBuilder`). Every read captures ONE snapshot
     * and answers entirely from it: complete and consistent, old or new, never torn, no locks.
     */
    private val snapshot = AtomicReference(IndexSnapshot.EMPTY)

    /**
     * An immutable snapshot of one [scan]: the indexed entries (membership authority) and the
     * retained raw on-disk names (P4) for files and directories.
     *
     * [files] and [dirs] are `TreePath -> ContentEntry` so a read can both confirm membership and
     * reach the entry's [ContentFile.rawName]. [children] groups indexed entries by their direct
     * parent ([rootChildren] for top-level) so [list] is derived purely from the snapshot, never
     * from a fresh directory stream that could surface an ignored or symlinked sibling.
     */
    private data class IndexSnapshot(
        val files: Map<TreePath, ContentFile>,
        val dirs: Map<TreePath, ContentFolder>,
        val children: Map<TreePath, List<ContentEntry>>,
        val rootChildren: List<ContentEntry>,
        val dirRawNames: Map<TreePath, String>,
    ) {
        /** True iff [path] names an indexed file (the [read] membership gate). */
        fun isIndexedFile(path: TreePath): Boolean = files.containsKey(path)

        /** True iff [path] names an indexed directory (the [list] membership gate; root is implicit). */
        fun isIndexedDir(path: TreePath): Boolean = dirs.containsKey(path)

        /** True iff [path] names an indexed file or directory (the [stat] membership gate). */
        fun isIndexedEntry(path: TreePath): Boolean = files.containsKey(path) || dirs.containsKey(path)

        /** The indexed direct children of [dir] (root when null), in folders-then-files order. */
        fun childrenOf(dir: TreePath?): List<ContentEntry> =
            if (dir == null) rootChildren else children[dir] ?: emptyList()

        companion object {
            val EMPTY = IndexSnapshot(emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyMap())

            /**
             * Builds an [IndexSnapshot] from a [ScanResult], preserving its [list] ordering
             * (folders before files, in scan-discovery order) per direct parent.
             */
            fun of(result: ScanResult, dirRawNames: Map<TreePath, String>): IndexSnapshot {
                val entries: List<ContentEntry> = result.folders + result.files // folders before files (list() order)
                val (atRoot, nested) = entries.partition { it.path.parent == null }
                return IndexSnapshot(
                    files = result.files.associateBy { it.path },
                    dirs = result.folders.associateBy { it.path },
                    children = nested.groupBy { checkNotNull(it.path.parent) },
                    rootChildren = atRoot,
                    dirRawNames = dirRawNames,
                )
            }
        }
    }

    /**
     * A mutable accumulator threaded through the recursive [scanDir] so the whole tree is gathered
     * before the immutable [IndexSnapshot] is assigned ONCE at the end of [scan] - no
     * element-by-element field mutation, no partially-populated map ever observable.
     */
    private class ScanAccumulator {
        val files = mutableListOf<ContentFile>()
        val folders = mutableListOf<ContentFolder>()
        val issues = mutableListOf<ScanIssue>()
        val dirNames = mutableMapOf<TreePath, String>()
    }

    /** A discovered child before collision resolution. */
    private data class Candidate(
        val rawName: String,
        val osPath: Path,
        val treePath: TreePath,
        val isDirectory: Boolean,
    )

    override fun available(): Boolean = probeRoot(root)

    /**
     * The ONE exit through which a rooted operation's observable outcome passes. Whatever the body minted -
     * an AMBIGUOUS typed result, or ANY [IOException] escaping its arms or its `finally` cleanup (a temp
     * delete on a root that just lost write permission) - is re-probed HERE, at the frame's exit, because a
     * cleanup block runs after the result is computed and can REPLACE it. Root gone -> mark, then [onRootLoss].
     * Root live -> the outcome passes through byte-unchanged, and a genuine fault is rethrown as the genuine
     * fault it is.
     *
     * [onRootLoss] is the CARRIER, and it is the only thing that differs between the read and the write paths:
     * the mutation surfaces THROW `RootUnavailable`, [readClassified] RETURNS `ContentRead.RootDown` (three of
     * its consumers must SWALLOW the condition, so a throw would be laundered by catches already sitting on
     * those paths). One rule, one probe, one marker, two carriers.
     *
     * It catches [IOException] and NOTHING ELSE, and that is PROVEN total rather than assumed: every errno the
     * default provider can raise arrives as an `IOException` subtype (`UnixException.translateToIOException`
     * maps every unmapped errno to `FileSystemException`), and the one genuine non-`IOException` carrier - the
     * `DirectoryIteratorException` a mid-walk fault boxes - is normalized back at its source by
     * [withDirectoryStream]. WIDENING the catch would be a BUG, not a belt: `UnsupportedOperationException` is
     * this store's own live "this filesystem has no hardlinks" signal, and `OutOfMemoryError` is reachable from
     * `readAllBytes` - laundering either as a downed root would hide the fault and lie to the operator.
     *
     * Note which faults the probe does NOT call root loss: a READ-ONLY remount passes all three predicates, so
     * its `EROFS` is caught, re-probed, and passed through as the surface's own `Unreadable` - 503
     * `content_unreadable` ("retryable, nothing written"), which is TRUE and prescribes the right remedy
     * (remount rw, no restart), where `root_unavailable` would promise the operator a restart they do not need.
     */
    private inline fun <T> classifyingRootLoss(ambiguous: (T) -> Boolean, onRootLoss: () -> T, op: () -> T): T {
        val outcome = try {
            op()
        } catch (e: IOException) {
            if (available()) throw e
            return rootGone(onRootLoss)
        }
        return if (ambiguous(outcome) && !available()) rootGone(onRootLoss) else outcome
    }

    /** Publish the loss, THEN answer it - never the other way round (see [onRootUnavailable]). */
    private inline fun <T> rootGone(onRootLoss: () -> T): T {
        onRootUnavailable()
        return onRootLoss()
    }

    /**
     * The mutation-surface ENTRY guard: fail fast before ANY partial work, and mark the common case (the root
     * is already gone) without waiting for an FS call to fail. It is not sufficient on its own - the root can
     * vanish AFTER it passes - which is what [classifyingRootLoss] closes at the other end.
     */
    private fun refuseIfRootGone() {
        if (available()) return
        onRootUnavailable()
        throw RootUnavailable(rootName, UnavailableCause.VANISHED)
    }

    override fun scan(): ScanResult {
        val acc = ScanAccumulator()
        scanDir(root, null, acc)
        val result = ScanResult(files = acc.files.toList(), folders = acc.folders.toList(), issues = acc.issues.toList())
        // Retain the raw directory names too so resolveOnDisk reaches an NFD-named ancestor (P4);
        // ContentFolder carries no rawName, so the dir map is sourced from the scan accumulator.
        snapshot.store(IndexSnapshot.of(result, acc.dirNames.toMap()))
        logger.info {
            "Scanned $root: ${acc.files.size} file(s), ${acc.folders.size} folder(s), ${acc.issues.size} issue(s)"
        }
        return result
    }

    /**
     * Recursively scans [dir] (content-relative path [dirPath], null at the root), appending
     * discovered entries and issues. Children are grouped by the NFC [TreePath] they normalize
     * to so a same-directory NFC/NFD collision is detected and resolved by the raw-byte-order
     * winner rule (B3) before either form is indexed.
     */
    private fun scanDir(
        dir: Path,
        dirPath: TreePath?,
        acc: ScanAccumulator,
    ) {
        val candidates = collectCandidates(dir, dirPath)

        for ((treePath, group) in candidates.groupBy { it.treePath }) {
            val winner = if (group.size == 1) {
                group.single()
            } else {
                resolveCollision(treePath, group, acc.issues)
            }
            if (winner.isDirectory) {
                acc.folders.add(ContentFolder(path = treePath, rawName = winner.rawName, meta = readFolderMeta(winner.osPath, treePath)))
                acc.dirNames[treePath] = winner.rawName
                scanDir(winner.osPath, treePath, acc)
            } else {
                acc.files.add(ContentFile(path = treePath, rawName = winner.rawName))
            }
        }
    }

    /** Lists [dir]'s non-ignored children as [Candidate]s (raw name + NFC [TreePath]). */
    private fun collectCandidates(dir: Path, dirPath: TreePath?): List<Candidate> {
        val children = withDirectoryStream(dir) { it.toList() }
        return children.mapNotNull { child ->
            val rawName = child.fileName.toString()
            if (rawName == FOLDER_META_NAME) return@mapNotNull null // metadata sidecar, not a content entry
            val relativePath = childRelativePath(dirPath, rawName)
            if (ignoreRules.isIgnored(rawName, relativePath)) return@mapNotNull null
            if (child.toAbsolutePath().normalize() in excludedDirs) {
                logger.debug { "Skipping excluded app-owned subtree: $relativePath" }
                return@mapNotNull null
            }
            // Skip symlinks: a symlink cycle is a startup stack overflow and an out-of-root target is
            // a content-root escape. NOFOLLOW_LINKS keeps the type checks honest about the link itself.
            if (Files.isSymbolicLink(child)) {
                logger.warn { "Skipping symlink (policy: links are not content): $relativePath" }
                return@mapNotNull null
            }
            val treePath = TreePath.childOf(dirPath, Nfc.normalize(rawName))
            Candidate(rawName, child, treePath, Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
        }
    }

    /**
     * Resolves an NFC path collision (B3): the raw-byte-order winner is indexed, every loser is
     * excluded and recorded as a [ScanIssue.PathCollision]. Returns the winner candidate.
     */
    private fun resolveCollision(
        treePath: TreePath,
        group: List<Candidate>,
        issues: MutableList<ScanIssue>,
    ): Candidate {
        val sorted = group.sortedWith(compareBy(RawByteOrder) { it.rawName })
        val winner = sorted.first()
        for (loser in sorted.drop(1)) {
            logger.warn {
                "NFC path collision at '${treePath.value}': winner raw='${winner.rawName}', " +
                    "loser raw='${loser.rawName}' excluded from index"
            }
            issues.add(
                ScanIssue.PathCollision(
                    path = treePath,
                    winnerRawName = winner.rawName,
                    loserRawName = loser.rawName,
                ),
            )
        }
        return winner
    }

    /**
     * Parses `<dir>/_folder.yaml` if present, else null. Non-UTF-8 bytes ([MalformedInputException]
     * from [Files.readString]) are tolerated like the parser tolerates malformed lines: the folder
     * meta is treated as absent (null) and a warning logged, rather than aborting the whole scan.
     */
    private fun readFolderMeta(dir: Path, dirPath: TreePath): FolderMeta? {
        val metaFile = dir.resolve(FOLDER_META_NAME)
        // No-follow on the sidecar: a symlinked _folder.yaml could point out of root, so honor the
        // same "links are not content" policy as the scan's symlink skip - never read or log it.
        if (Files.isSymbolicLink(metaFile)) {
            logger.warn { "Skipping symlink $FOLDER_META_NAME (policy: links are not content) at '${dirPath.value}'" }
            return null
        }
        if (!Files.isRegularFile(metaFile, LinkOption.NOFOLLOW_LINKS)) return null
        val body = try {
            Files.readString(metaFile, Charsets.UTF_8)
        } catch (_: MalformedInputException) {
            logger.warn { "Ignoring non-UTF-8 $FOLDER_META_NAME at '${dirPath.value}': treating folder meta as absent" }
            return null
        }
        return FolderMeta.parse(body, source = "${dirPath.value}/$FOLDER_META_NAME")
    }

    override fun read(path: TreePath): ByteArray? {
        val snap = snapshot.load()
        // Indexed-only gate (see class header): a path the scan skipped is unreadable.
        if (!snap.isIndexedFile(path)) return null
        val osPath = resolveOnDisk(path, snap)
        if (!Files.isRegularFile(osPath, LinkOption.NOFOLLOW_LINKS)) return null
        // Defense-in-depth (belt-and-suspenders behind the membership gate): re-verify the resolved
        // file stays inside the content root even against a TOCTOU symlink swapped in between scan
        // and read. Cheap, read-path only.
        if (!isWithinRoot(root, osPath)) {
            logger.warn { "Refusing read of '${path.value}': resolved path escapes content root (links are not content)" }
            return null
        }
        return Files.readAllBytes(osPath)
    }

    override fun readClassified(path: TreePath): ContentRead =
        // ONE exit. The read's ambiguous value is Absent; its root-loss carrier is RootDown - a VALUE, never a
        // throw, which is the whole point of a classified read. read() itself is untouched.
        classifyingRootLoss(ambiguous = { it == ContentRead.Absent }, onRootLoss = { ContentRead.RootDown }) {
            try {
                read(path)?.let(ContentRead::Bytes) ?: ContentRead.Absent
            } catch (_: NoSuchFileException) {
                // The FILE vanished between read()'s isRegularFile probe and its readAllBytes (which sits outside
                // any catch). That means exactly what read()'s own null means, so say so and let the wrapper decide
                // WHOSE absence it was: root gone -> RootDown; root live -> a plain deletion race, honestly Absent.
                // The two races COMPOSE rather than needing separate arms. Any OTHER IOException (a chmod-ed page
                // file, an EIO, a read-only remount) escapes to the wrapper's catch and is rethrown on a live root -
                // a genuine fault stays a genuine fault, never a false 503.
                ContentRead.Absent
            }
        }

    override fun stat(path: TreePath): ContentStat? {
        val snap = snapshot.load()
        // Indexed-only gate (see class header), file OR directory; unindexed -> null per the contract.
        if (!snap.isIndexedEntry(path)) return null
        val osPath = resolveOnDisk(path, snap)
        // Mirror read()'s structure: existence probe (no-follow, exists not isRegularFile - stat serves
        // files AND directories) first, then containment proven BEFORE reading the returned attributes.
        // A merely-missing entry was a silent null before this ordering too (readAttributes threw ahead
        // of the containment check); what the reorder buys is the read() parity and never acting on
        // attributes of an unproven path. The one still-warning case - a dangling/escaping symlink
        // swapped in post-scan - warns and returns null, unchanged (safe). Two filesystem hits where
        // one sufficed - stat is not a hot path, and the ordering discipline is worth it.
        if (!Files.exists(osPath, LinkOption.NOFOLLOW_LINKS)) return null
        if (!isWithinRoot(root, osPath)) {
            logger.warn { "Refusing stat of '${path.value}': resolved path escapes content root (links are not content)" }
            return null
        }
        val attrs = try {
            Files.readAttributes(osPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return null
        }
        return ContentStat(
            path = path,
            isDirectory = attrs.isDirectory,
            sizeBytes = if (attrs.isRegularFile) attrs.size() else 0L, // non-regular -> 0L (preserved)
        )
    }

    override fun list(dir: TreePath?): List<ContentEntry> {
        val snap = snapshot.load()
        // Indexed-only gate (see class header): children come purely from the snapshot. The root
        // (null) is always listable; any other directory must itself be indexed, else empty list.
        if (dir != null && !snap.isIndexedDir(dir)) return emptyList()
        return snap.childrenOf(dir)
    }

    /**
     * Resolves a [TreePath] to its on-disk [Path] via the scan-retained raw names (P4), parent
     * first so an NFD-named ancestor directory on a normalization-preserving filesystem is still
     * reached. The leaf prefers the retained file raw name, then the retained directory raw name,
     * then falls back to the NFC [name][TreePath.name] - so a collision winner whose raw name is
     * the non-NFC byte-form is reached, and a genuinely-new (unscanned) segment resolves to its
     * NFC name.
     *
     * This is **total**: the `?: path.name` fallback makes every segment resolvable, so the
     * function never returns null. Callers check existence on the result themselves.
     */
    private fun resolveOnDisk(path: TreePath, snap: IndexSnapshot): Path {
        val rawLeaf = snap.files[path]?.rawName ?: snap.dirRawNames[path] ?: path.name
        val parent = path.parent ?: return root.resolve(rawLeaf)
        return resolveOnDisk(parent, snap).resolve(rawLeaf)
    }

    /**
     * The on-disk target [resolveOnDisk] would resolve for [path] against the CURRENT snapshot - the
     * read-only accessor (C4 seam f) that lets the ObjectContentStore hybrid hand [gates] the same
     * mirror target the inner mutators would use. Resolution only; never touches disk.
     */
    internal fun onDiskTarget(path: TreePath): Path = resolveOnDisk(path, snapshot.load())

    /**
     * The repo-relative path to STAGE in git for [path]: slash-separated, raw-on-disk-name-preserving (so
     * it may differ from [TreePath.value] on a normalization-preserving filesystem, where an NFD on-disk
     * name is kept verbatim while the [TreePath] is NFC). The Git history layer must stage THIS string,
     * not [TreePath.value], or the committed git path is a phantom that does not match the real file
     * (history diverges from the content tree; the history layer's path-keyed citations miss it).
     *
     * Total, never throws. A not-yet-indexed / brand-new page falls back to [TreePath.value]; new pages
     * are NFC by construction, so that is the correct on-disk form. The separator is always `/` (git
     * paths are `/`-joined), never an OS-specific separator.
     *
     * Deliberately NOT on the [ContentStore] port: staging git paths over the served directory is a
     * local-filesystem concern, so the history wiring (`historyModule`) binds to this concrete adapter.
     */
    fun resolveRepoRelativePath(path: TreePath): String {
        // The raw on-disk path relative to the content root, re-joined with '/' (git paths are '/'-joined,
        // never OS backslashes). resolveOnDisk is total and raw-name-preserving, so a non-NFC on-disk name is
        // staged verbatim; a brand-new/unscanned page falls back to its NFC name (the correct fresh form).
        // Resolves via the current scan snapshot: a page created under a parent dir added externally since the
        // last scan (with a non-NFC raw name) falls back to the NFC path.value for that parent until the next
        // rescan updates the snapshot - an accepted narrow limitation (not a regression; r6b strictly improved
        // path fidelity), as a live-FS-resolution fix is disproportionate.
        return root.relativize(resolveOnDisk(path, snapshot.load())).joinToString("/") { it.toString() }
    }

    override fun write(path: TreePath, bytes: ByteArray) {
        // Resolve through the scan-retained raw names exactly like read (P4): on a
        // normalization-preserving filesystem an existing NFD-named file is REPLACED rather than
        // shadowed by a new NFC-named sibling. resolveOnDisk is total - a genuinely-new segment
        // falls back to its NFC name, which is the correct on-disk form for a fresh file.
        val target = resolveOnDisk(path, snapshot.load())
        // The object MIRROR requires parent creation: it is derived DATA_DIR state whose own fresh-install
        // contract says it may legitimately be ABSENT and re-materialized from the bucket, and this is the
        // mechanism that honors it. NOTHING ELSE may use this method to touch a page (see the port doc): the
        // reasoning that once let the offline `adopt` CLI in - that it serves no 503 and so has no D5 wire
        // contract to lie to - was measuring the wrong thing. The lie a resurrection tells is not on the wire,
        // it is ON DISK: a vanished root came back as a partial skeleton of the operator's tree, holding only
        // the pages that run patched, at whatever the old path now resolves to. Adopt CASes instead.
        Files.createDirectories(target.parent)
        // Log the intended write BEFORE performing it so an interrupted run is detectable
        // (chunk 4b adopt durability). Intentionally logs the path only, never content.
        logger.info { "Writing content file: ${path.value} (${bytes.size} bytes)" }
        val tmp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                atomics.atomicMove(tmp, target)
            } catch (_: AtomicMoveNotSupportedException) {
                // NFS/SMB: atomic rename unsupported - fall back to copy+delete (NOT crash-atomic;
                // the pre-write intent log above is what makes an interrupted run reconcilable).
                logger.warn { "ATOMIC_MOVE unsupported for ${path.value}; falling back to copy+delete (non-atomic)" }
                atomics.copyReplace(tmp, target)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String): CasResult {
        refuseIfRootGone()
        return classifyingRootLoss(
            CasResult::rootLossCandidate,
            onRootLoss = { throw RootUnavailable(rootName, UnavailableCause.VANISHED) },
        ) {
            casWrite(path, baseHash, bytes, hasher)
        }
    }

    /** The CAS body, verbatim. It is a separate frame so the exit wrapper actually WRAPS it - a `return` from an
     *  inlined lambda would return from `compareAndSwapWrite` itself and walk straight past the classifier. */
    private fun casWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String): CasResult {
        val snap = snapshot.load()
        // Indexed-only gate (see read/the class header): a path the scan skipped is not a CAS target.
        if (!snap.isIndexedFile(path)) return CasResult.Deleted
        val target = resolveOnDisk(path, snap)
        // One identity capture: read the current bytes AND the file key + mtime in the same breath, so
        // the recheck before the rename compares against exactly what the hash was computed over.
        val before = try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !isWithinRoot(root, target)) return CasResult.Deleted
            val attrs = Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            FileIdentity(bytes = Files.readAllBytes(target), fileKey = attrs.fileKey(), modified = attrs.lastModifiedTime())
        } catch (e: IOException) {
            return CasResult.Unreadable(e.message ?: e::class.simpleName ?: "io error")
        }
        val beforeHash = hasher(before.bytes)
        if (beforeHash != baseHash) return CasResult.Mismatch(currentBytes = before.bytes, currentHash = beforeHash)

        logger.info { "CAS-writing content file: ${path.value} (${bytes.size} bytes)" }
        // A pre-rename I/O failure (temp create/write, the re-stat, or the move) must NOT escape as an
        // exception: WritePipeline has already marked the page dirty, so an uncaught throw would orphan
        // a dirty row whose expectedHash names bytes that never landed. Convert it to Unreadable - the
        // same typed outcome as the read/stat section - so the pipeline restores-or-clears the mark.
        var tmp: Path? = null
        try {
            tmp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
            Files.write(tmp, bytes)
            // Re-stat the target immediately before the rename: a non-cooperating external write since
            // the read changes the file key or mtime - detect it rather than clobber it.
            val now = Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (now.fileKey() != before.fileKey || now.lastModifiedTime() != before.modified) {
                val current = try {
                    Files.readAllBytes(target)
                } catch (_: IOException) {
                    null
                }
                return CasResult.Mismatch(currentBytes = current, currentHash = current?.let(hasher))
            }
            try {
                atomics.atomicMove(tmp, target)
            } catch (_: AtomicMoveNotSupportedException) {
                logger.warn { "ATOMIC_MOVE unsupported for ${path.value}; falling back to copy+delete (non-atomic)" }
                try {
                    atomics.copyReplace(tmp, target)
                } catch (e: IOException) {
                    // The non-atomic copy may have TRUNCATED/partially replaced the target: report mutated
                    // so the pipeline keeps the write-ahead mark (reconcile then commits a fully-landed copy
                    // or drift-skips a partial - operator-visible, never silent corruption). An atomicMove
                    // or pre-move failure stays targetMutated=false (atomicity → nothing landed).
                    return CasResult.Unreadable(e.message ?: e::class.simpleName ?: "io error", targetMutated = true)
                }
            }
            return CasResult.Written(newHash = hasher(bytes))
        } catch (e: IOException) {
            return CasResult.Unreadable(e.message ?: e::class.simpleName ?: "io error")
        } finally {
            tmp?.let { Files.deleteIfExists(it) }
        }
    }

    override fun createExclusive(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult {
        refuseIfRootGone()
        return classifyingRootLoss(
            CreateResult::rootLossCandidate,
            onRootLoss = { throw RootUnavailable(rootName, UnavailableCause.VANISHED) },
        ) {
            createIfAbsent(path, bytes, hasher)
        }
    }

    /** The create body, verbatim (a separate frame - see [casWrite]). */
    private fun createIfAbsent(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult {
        // resolveOnDisk is total - a genuinely-new segment falls back to its NFC name, the correct
        // on-disk form for a fresh file (the same resolution `write` uses, P4-aware).
        val target = resolveOnDisk(path, snapshot.load())
        // P1 containment: a create is the one path that turns an ARBITRARY client-supplied location
        // into a new on-disk file, so it must enforce the same links-are-not-content / inside-root law
        // the read path re-checks - BEFORE creating any parent dirs or reserving the target. An ignored
        // or excluded segment, a symlinked existing ancestor, or an ancestor that resolves outside the
        // content root can never name content; refuse rather than write through it.
        gates.rejectionReason(path, target)?.let { reason ->
            logger.warn { "Refusing create of '${path.value}': $reason" }
            return CreateResult.Rejected(reason)
        }
        // Log the intended create BEFORE performing it, path only (the write/CAS idiom).
        logger.info { "Creating content file: ${path.value} (${bytes.size} bytes)" }
        val resolvedTarget = try {
            // Resolve+create each PARENT segment reusing an existing NFC-equivalent on-disk dir rather
            // than minting a duplicate (P2 NFC-parent guard): an external process may have added a raw
            // non-NFC parent (e.g. NFD `café/`) after the last scan, so the snapshot has no raw-name
            // entry and resolveOnDisk fell back to the NFC byte-form - createDirectories would then make
            // a SECOND `café/` dir, splitting the subtree and getting the new page excluded on the next
            // rebuild. So we descend segment-by-segment, reusing the existing raw-named dir on an NFC
            // match. The leaf is then taken under the resolved parent (same NFC-aware logic).
            val parent = resolveOrCreateParent(path) ?: return CreateResult.Exists(path)
            parent.resolve(target.fileName.toString())
        } catch (e: IOException) {
            return CreateResult.Unreadable(e.message ?: e::class.simpleName ?: "io error")
        }
        // NFC-equivalent LEAF guard: same reasoning as the parent, for the file itself - a non-NFC
        // sibling the scan has not yet seen occupies the path; treat it as already-present.
        if (gates.nfcEquivalentSiblingExists(resolvedTarget)) return CreateResult.Exists(path)
        return writeIfAbsent(path, resolvedTarget, bytes, hasher)
    }

    override fun writeAssetExclusive(
        @Suppress("UNUSED_PARAMETER") grant: EditGrant,
        path: TreePath,
        bytes: ByteArray,
        hasher: (ByteArray) -> String,
    ): CreateResult {
        // [grant] is an unused compile-time witness that PolicyService.checkEdit() ran (A3). Body unchanged.
        refuseIfRootGone()
        return classifyingRootLoss(
            CreateResult::rootLossCandidate,
            onRootLoss = { throw RootUnavailable(rootName, UnavailableCause.VANISHED) },
        ) {
            writeAsset(path, bytes, hasher)
        }
    }

    /** The asset-write body, verbatim (a separate frame - see [casWrite]). */
    private fun writeAsset(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult {
        val target = resolveOnDisk(path, snapshot.load())
        // Asset difference (1): require the parent to ALREADY exist and be a directory - never create it
        // (an external rm of the page's folder must not be papered over by recreating it under the asset).
        // This parent-is-a-directory check runs BEFORE rejectionReason so an ABSENT or NOT-A-DIRECTORY
        // parent (e.g. the page folder was replaced by a regular file) maps to ParentMissing (→ 404, the
        // documented contract) - NOT to rejectionReason's "file-not-dir ancestor" → Rejected (→ 400).
        // The existence check FOLLOWS links: the store legitimately allows a symlinked content ROOT, so a
        // NOFOLLOW check here would falsely return ParentMissing (→ 404) for a top-level page whose parent
        // IS that symlinked root. rejectionReason (run just after, on the confirmed-directory parent) still
        // vets symlinked ancestors below root and outside-root escapes - security is preserved, and a
        // non-directory parent can hold no content regardless.
        val onDiskParent = target.parent
        if (onDiskParent == null || !Files.isDirectory(onDiskParent)) {
            logger.warn { "Refusing asset write of '${path.value}': parent directory is absent or not a directory" }
            return CreateResult.ParentMissing
        }
        // SAME P1 containment as createExclusive (one source of truth), now on the confirmed-directory
        // parent: scan-skipped-name segments, an excluded subtree, a symlinked existing ancestor, an
        // ancestor resolving outside root.
        gates.rejectionReason(path, target)?.let { reason ->
            logger.warn { "Refusing asset write of '${path.value}': $reason" }
            return CreateResult.Rejected(reason)
        }
        // NFC-equivalent LEAF guard (same as createExclusive): a non-NFC sibling the scan hasn't seen
        // occupies the path; treat it as already-present. O_EXCL is the true serialization point below.
        if (gates.nfcEquivalentSiblingExists(target)) return CreateResult.Exists(path)
        logger.info { "Writing asset file: ${path.value} (${bytes.size} bytes)" }
        // Asset difference (2): fail closed - the createLink O_EXCL write ONLY, no reserve-then-move.
        return writeAssetIfAbsent(path, target, bytes, hasher)
    }

    /**
     * The fail-closed asset write (W3b): like [writeIfAbsent] but with NO reserve-then-move fallback -
     * an asset has no `dirty_page` self-heal for the 0-byte reservation window, so when [Files.createLink]
     * is unavailable this returns [CreateResult.Unreadable] (→ 503) rather than reserving an empty target.
     */
    private fun writeAssetIfAbsent(path: TreePath, target: Path, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult {
        var tmp: Path? = null
        return try {
            // Short fixed prefix (not the full target name): a 255-byte target would push a
            // `.${fileName}.` + random + `.tmp` temp past NAME_MAX. The temp only needs to be a hidden sibling.
            tmp = Files.createTempFile(target.parent, ".pbtmp", ".tmp")
            Files.write(tmp, bytes)
            try {
                // Atomic O_EXCL create-with-full-content: the target never exists as a 0-byte file.
                atomics.createLink(target, tmp)
            } catch (_: FileAlreadyExistsException) {
                return CreateResult.Exists(path) // a file already occupies the target - nothing written
            } catch (e: Exception) {
                if (e !is UnsupportedOperationException && e !is FileSystemException) throw e
                logger.warn { "createLink unavailable for asset ${path.value}; failing closed (no reserve-then-move)" }
                return CreateResult.Unreadable("hardlink unavailable on this filesystem; asset write fails closed")
            }
            CreateResult.Created(newHash = hasher(bytes))
        } catch (e: IOException) {
            CreateResult.Unreadable(e.message ?: e::class.simpleName ?: "io error")
        } finally {
            tmp?.let { Files.deleteIfExists(it) } // the target is its own hardlink now; drop the temp name
        }
    }

    /**
     * Creates the target write-if-absent WITHOUT ever exposing a 0-byte file (P2 race fix): the full
     * [bytes] are written to a temp sibling FIRST, then [Files.createLink] atomically links the target
     * to it (O_EXCL - throws [FileAlreadyExistsException] iff the target exists, and the target appears
     * with the COMPLETE content, never an empty window a concurrent watcher `rebuild()` could scan as a
     * ghost page). The temp is then unlinked, leaving one fully-populated target.
     *
     * Fallback (hardlinks unsupported - [UnsupportedOperationException]/[FileSystemException] on exotic
     * filesystems): the original `createFile`-reserve-then-move. There the reserved-but-unwritten crash
     * window still applies and self-heals - a later create returns [CreateResult.Exists], the next
     * `rebuild()` indexes the 0-byte file as an empty page, and the write-ahead journal's reconcile
     * drift-skips the stale intent (`hash(0 bytes) != expectedHash`).
     */
    private fun writeIfAbsent(path: TreePath, target: Path, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult {
        var tmp: Path? = null
        return try {
            // Short fixed prefix (not the full target name): a 255-byte target would push a
            // `.${fileName}.` + random + `.tmp` temp past NAME_MAX. The temp only needs to be a hidden sibling.
            tmp = Files.createTempFile(target.parent, ".pbtmp", ".tmp")
            Files.write(tmp, bytes)
            try {
                // Atomic O_EXCL create-with-full-content: the target never exists as a 0-byte file.
                atomics.createLink(target, tmp)
            } catch (_: FileAlreadyExistsException) {
                return CreateResult.Exists(path) // a file already occupies the target - nothing written
            } catch (e: Exception) {
                if (e !is UnsupportedOperationException && e !is FileSystemException) throw e
                // Hardlinks unsupported on this FS - fall back to reserve-then-move (the documented,
                // self-healing crash window; still O_EXCL via createFile, still no clobber).
                logger.warn { "createLink unsupported for ${path.value}; falling back to reserve-then-move" }
                return reserveThenMove(path, target, bytes, hasher)
            }
            CreateResult.Created(newHash = hasher(bytes))
        } catch (e: IOException) {
            CreateResult.Unreadable(e.message ?: e::class.simpleName ?: "io error")
        } finally {
            tmp?.let { Files.deleteIfExists(it) } // the target is its own hardlink now; drop the temp name
        }
    }

    /** The hardlink-unsupported fallback: O_EXCL [Files.createFile] reservation then atomic content move. */
    private fun reserveThenMove(path: TreePath, target: Path, bytes: ByteArray, hasher: (ByteArray) -> String): CreateResult {
        try {
            Files.createFile(target)
        } catch (_: FileAlreadyExistsException) {
            return CreateResult.Exists(path)
        } catch (e: IOException) {
            return CreateResult.Unreadable(e.message ?: e::class.simpleName ?: "io error")
        }
        var tmp: Path? = null
        return try {
            tmp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
            Files.write(tmp, bytes)
            try {
                atomics.atomicMove(tmp, target)
            } catch (_: AtomicMoveNotSupportedException) {
                logger.warn { "ATOMIC_MOVE unsupported for ${path.value}; falling back to copy+delete (non-atomic)" }
                atomics.copyReplace(tmp, target)
            }
            CreateResult.Created(newHash = hasher(bytes))
        } catch (e: IOException) {
            Files.deleteIfExists(target) // nothing meaningful landed - drop the empty reservation
            CreateResult.Unreadable(e.message ?: e::class.simpleName ?: "io error")
        } finally {
            tmp?.let { Files.deleteIfExists(it) }
        }
    }

    /**
     * Resolves [path]'s on-disk PARENT directory, creating any missing segment: the READ-ONLY gate walk
     * ([CreateGates.resolveParent]) reuses an existing on-disk sibling that NFC-normalizes to each
     * segment (P2 NFC-parent guard) instead of minting a duplicate, and the creation of the
     * genuinely-missing tail stays HERE - the seam never mutates. Returns the on-disk parent [Path], or
     * null if a parent segment is occupied by a non-directory NFC-equivalent (the create then surfaces
     * as [CreateResult.Exists] - a page cannot be created under a file). Root itself is never created
     * (it always exists).
     */
    private fun resolveOrCreateParent(path: TreePath): Path? {
        val resolution = gates.resolveParent(path)
        if (resolution.occupiedByFile) return null // a file holds a parent segment
        var dir = resolution.existingPrefix
        for (segment in resolution.missing) {
            dir = Files.createDirectory(dir.resolve(segment)) // genuinely new: mint it NFC-named
        }
        return dir
    }

    override fun watch(onChange: (TreePath) -> Unit, onFailure: (Throwable) -> Unit): AutoCloseable =
        // The watcher shares the scan's IgnoreRules (one ignore policy, §B1) and skips the
        // configured exclusions - DATA_DIR when it is nested inside the content root, so the app's
        // own search-index/database writes can never re-trigger the watcher.
        FileWatcher(
            root = root,
            ignoreRules = ignoreRules,
            excluded = excludedDirs,
            onChange = onChange,
            onFailure = onFailure,
            // THIS store's probe, not a second one: the watcher's "is the root there" and the write path's must
            // never fork, and a seamed probe (tests) has to reach the watcher too.
            rootIsAlive = ::available,
            // Root loss is PUBLISHED then CONVERGED, through the two mechanisms that already exist: the D5 marker
            // this store's mutation surfaces call (VANISHED - the honest cause; the watcher merely NOTICED it), and
            // the same full pass the OVERFLOW branch schedules, so the rebuild carries the root's section forward
            // and the tree/health memo (keyed on the availability snapshot) drops its `available: true`.
            onRootLost = {
                onRootUnavailable()
                onChange(ContentStore.OVERFLOW)
            },
        )

    /** The `/`-joined content-relative path of a child named [rawName] under [dirPath]. */
    private fun childRelativePath(dirPath: TreePath?, rawName: String): String =
        if (dirPath == null) rawName else "${dirPath.value}/$rawName"

    /** A CAS read's captured identity: the bytes hashed, plus the file key + mtime the rename rechecks. */
    private class FileIdentity(val bytes: ByteArray, val fileKey: Any?, val modified: FileTime)

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * The ONE liveness predicate: the backing tree exists and is traversable right now. Kept textually PAIRED with
 * `PlainbaseConfig.canonicalRootPathOrNull`'s one-probe rule, which defines a usable root as a readable,
 * SEARCHABLE directory - the runtime probe and the config probe must never fork, which is a second reason not
 * to add a write bit here (see [ContentStore.available]).
 */
internal fun rootIsTraversable(root: Path): Boolean =
    Files.isDirectory(root) && Files.isReadable(root) && Files.isExecutable(root)

/**
 * Whether a [CasResult] is AMBIGUOUS - i.e. a live-root failure and a root-gone condition mint the SAME value,
 * so the exit classifier must re-probe before letting it out. The `when` is EXHAUSTIVE over the sealed set on
 * purpose: a future variant does not compile until it declares its ambiguity, which is what makes this list the
 * compiler's rather than a plan's.
 *
 * Declared TOP-LEVEL, not as a member extension: Kotlin forbids a callable reference to a member-extension
 * function, so the unbound `CasResult::rootLossCandidate` the exit wrapper takes would not compile.
 */
private fun CasResult.rootLossCandidate(): Boolean = when (this) {
    is CasResult.Written -> false // bytes landed - the root was there
    // Null bytes mean the read failed. On a live root that is a genuine concurrent-write race (409
    // `content_changed`); on a gone root it would tell a client "someone else edited your page" while the
    // truth is the disk is unmounted.
    is CasResult.Mismatch -> currentBytes == null
    CasResult.Deleted, is CasResult.Unreadable -> true
}

/** The [CreateResult] twin of [rootLossCandidate]. A gone root yields no `Rejected` (the gates' ancestor walk
 *  finds nothing existing) and no `Exists` (that arm fires only on an occupied-by-file parent), so those - and
 *  `Created`, whose bytes demonstrably landed - are unambiguous. */
private fun CreateResult.rootLossCandidate(): Boolean = when (this) {
    is CreateResult.Created, is CreateResult.Exists, is CreateResult.Rejected -> false
    CreateResult.ParentMissing, is CreateResult.Unreadable -> true
}

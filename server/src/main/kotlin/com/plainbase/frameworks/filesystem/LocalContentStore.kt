package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.ContentEntry
import com.plainbase.domain.content.ContentFile
import com.plainbase.domain.content.ContentFolder
import com.plainbase.domain.content.ContentStat
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.FolderMeta
import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference

/** The on-disk name of a folder's metadata sidecar (§A4). */
private const val FOLDER_META_NAME = "_folder.yaml"

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
 * **Security policy — indexed-only visibility.** [read]/[stat]/[list] answer ONLY from the
 * retained immutable scan snapshot; they never re-touch disk to decide what is visible. A path
 * the scan skipped — a dotfile/`.git` entry (ignore rules), a symlink (links-are-not-content),
 * or a collision loser — is absent from the snapshot and therefore invisible to every read.
 * This closes the content-root / ignored-file escape at every filesystem access, not just the
 * scan's enumeration loop. [write] still resolves new paths through the total [resolveOnDisk]
 * because creating a not-yet-indexed file is a legitimate operation. Defense-in-depth on the
 * actual read additionally re-checks no-follow root containment against a TOCTOU symlink swap.
 */
class LocalContentStore(
    private val root: Path,
    private val ignoreRules: IgnoreRules = IgnoreRules(),
    exclusions: List<Path> = emptyList(),
) : ContentStore {

    // App-owned subtrees (DATA_DIR) excluded from BOTH the scan and the watch: a nested data dir
    // must never be indexed — plainbase.db/search.db would otherwise be served as /assets/... —
    // and never re-trigger rebuilds from the app's own writes. One exclusion policy, two consumers.
    private val excludedDirs: List<Path> = exclusions.map { it.toAbsolutePath().normalize() }

    /**
     * Immutable snapshot of the most recent [scan]: the indexed files/folders (the membership
     * authority for [read]/[stat]/[list]) plus the `TreePath -> raw on-disk name` maps (P4) used to
     * resolve a [TreePath] back to its exact on-disk byte-form. A collision winner whose raw name is
     * the non-NFC byte-form (and an NFD-named ancestor directory) is reached correctly; an entry the
     * scan skipped is simply absent, so it cannot be read, stat-ed, or listed.
     *
     * Safe publication, no `@Volatile` (S5.0): the Phase-2 watcher rescans on another thread, so
     * each [scan] builds the snapshot entirely off to the side and swaps it in with one
     * [AtomicReference.set] — the house pattern (`IndexBuilder`). Every read captures ONE snapshot
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
     * before the immutable [IndexSnapshot] is assigned ONCE at the end of [scan] — no
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

    override fun scan(): ScanResult {
        val acc = ScanAccumulator()
        scanDir(root, null, acc)
        val result = ScanResult(files = acc.files.toList(), folders = acc.folders.toList(), issues = acc.issues.toList())
        // Retain the raw directory names too so resolveOnDisk reaches an NFD-named ancestor (P4);
        // ContentFolder carries no rawName, so the dir map is sourced from the scan accumulator.
        snapshot.set(IndexSnapshot.of(result, acc.dirNames.toMap()))
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
        val children = Files.newDirectoryStream(dir).use { it.toList() }
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
        // same "links are not content" policy as the scan's symlink skip — never read or log it.
        if (Files.isSymbolicLink(metaFile)) {
            logger.warn { "Skipping symlink $FOLDER_META_NAME (policy: links are not content) at '${dirPath.value}'" }
            return null
        }
        if (!Files.isRegularFile(metaFile, LinkOption.NOFOLLOW_LINKS)) return null
        val body = try {
            Files.readString(metaFile, StandardCharsets.UTF_8)
        } catch (_: MalformedInputException) {
            logger.warn { "Ignoring non-UTF-8 $FOLDER_META_NAME at '${dirPath.value}': treating folder meta as absent" }
            return null
        }
        return FolderMeta.parse(body, source = "${dirPath.value}/$FOLDER_META_NAME")
    }

    override fun read(path: TreePath): ByteArray? {
        val snap = snapshot.get()
        // Indexed-only gate (see class header): a path the scan skipped is unreadable.
        if (!snap.isIndexedFile(path)) return null
        val osPath = resolveOnDisk(path, snap)
        if (!Files.isRegularFile(osPath, LinkOption.NOFOLLOW_LINKS)) return null
        // Defense-in-depth (belt-and-suspenders behind the membership gate): re-verify the resolved
        // file stays inside the content root even against a TOCTOU symlink swapped in between scan
        // and read. Cheap, read-path only.
        if (!isWithinRoot(osPath)) {
            logger.warn { "Refusing read of '${path.value}': resolved path escapes content root (links are not content)" }
            return null
        }
        return Files.readAllBytes(osPath)
    }

    override fun stat(path: TreePath): ContentStat? {
        val snap = snapshot.get()
        // Indexed-only gate (see class header), file OR directory; unindexed -> null per the contract.
        if (!snap.isIndexedEntry(path)) return null
        val osPath = resolveOnDisk(path, snap)
        // One filesystem hit, no-follow: a missing target throws and is reported as null (port contract).
        val attrs = try {
            Files.readAttributes(osPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return null
        }
        if (!isWithinRoot(osPath)) {
            logger.warn { "Refusing stat of '${path.value}': resolved path escapes content root (links are not content)" }
            return null
        }
        return ContentStat(
            path = path,
            isDirectory = attrs.isDirectory,
            sizeBytes = if (attrs.isRegularFile) attrs.size() else 0L, // non-regular -> 0L (preserved)
        )
    }

    override fun list(dir: TreePath?): List<ContentEntry> {
        val snap = snapshot.get()
        // Indexed-only gate (see class header): children come purely from the snapshot. The root
        // (null) is always listable; any other directory must itself be indexed, else empty list.
        if (dir != null && !snap.isIndexedDir(dir)) return emptyList()
        return snap.childrenOf(dir)
    }

    /**
     * Resolves a [TreePath] to its on-disk [Path] via the scan-retained raw names (P4), parent
     * first so an NFD-named ancestor directory on a normalization-preserving filesystem is still
     * reached. The leaf prefers the retained file raw name, then the retained directory raw name,
     * then falls back to the NFC [name][TreePath.name] — so a collision winner whose raw name is
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

    override fun write(path: TreePath, bytes: ByteArray) {
        // Resolve through the scan-retained raw names exactly like read (P4): on a
        // normalization-preserving filesystem an existing NFD-named file is REPLACED rather than
        // shadowed by a new NFC-named sibling. resolveOnDisk is total — a genuinely-new segment
        // falls back to its NFC name, which is the correct on-disk form for a fresh file.
        val target = resolveOnDisk(path, snapshot.get())
        Files.createDirectories(target.parent)
        // Log the intended write BEFORE performing it so an interrupted run is detectable
        // (chunk 4b adopt durability). Intentionally logs the path only, never content.
        logger.info { "Writing content file: ${path.value} (${bytes.size} bytes)" }
        val tmp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // NFS/SMB: atomic rename unsupported — fall back to copy+delete (NOT crash-atomic;
                // the pre-write intent log above is what makes an interrupted run reconcilable).
                logger.warn { "ATOMIC_MOVE unsupported for ${path.value}; falling back to copy+delete (non-atomic)" }
                Files.copy(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun watch(onChange: (TreePath) -> Unit): AutoCloseable =
        // The watcher shares the scan's IgnoreRules (one ignore policy, §B1) and skips the
        // configured exclusions — DATA_DIR when it is nested inside the content root, so the app's
        // own search-index/database writes can never re-trigger the watcher.
        FileWatcher(root = root, ignoreRules = ignoreRules, excluded = excludedDirs, onChange = onChange)

    /** The `/`-joined content-relative path of a child named [rawName] under [dirPath]. */
    private fun childRelativePath(dirPath: TreePath?, rawName: String): String =
        if (dirPath == null) rawName else "${dirPath.value}/$rawName"

    /**
     * Defense-in-depth root containment: resolves symlinks on both [target] and [root] to their
     * real paths and asserts the target stays inside root. Catches a TOCTOU symlink swap between
     * scan and read. Returns false (and the caller refuses) if the target is missing or escapes;
     * an [IOException] resolving the real path is treated as "not contained" — fail closed.
     */
    private fun isWithinRoot(target: Path): Boolean =
        try {
            target.toRealPath().startsWith(root.toRealPath())
        } catch (_: IOException) {
            false
        }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

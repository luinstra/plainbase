package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.RootBinding
import com.plainbase.frameworks.filesystem.FileAtomics
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fresh [ObjectContentStore] over a temp mirror directory + a fresh [FakeObjectStore] - the shared
 * test rig for the C4 acceptance suites (oracle, Q8, mirror-apply-failure, concurrency, boot order,
 * outage). One fixture per test keeps every suite hermetic; [close] deletes the temp mirror.
 */
class HybridFixture(
    conflictStatus: Int = 412,
    val dirtyPaths: MutableSet<TreePath> = mutableSetOf(),
    /** The per-path poll guard (MINOR-1); defaults to [dirtyPaths] membership, overridable to assert the
     *  poll consults THIS predicate per candidate rather than rebuilding the whole dirty set. */
    isDirty: ((TreePath) -> Boolean)? = null,
    val mirrorAtomics: FailableFileAtomics = FailableFileAtomics(),
    /** A SEPARATE injection point for MirrorState.persist() flushes, so a persist fault can be tested
     *  independently of a mirror-write fault (the BLOCKING poll-survives / Q8b-with-failing-persist tests). */
    val stateAtomics: FailableFileAtomics = FailableFileAtomics(),
    keyPrefix: String = "",
    pollSeconds: Long = 3600,
    /** WHICH BUCKET this store thinks it is looking at (C3) - the string the durable latch compares. */
    val binding: RootBinding = RootBinding("https://fake|bucket|"),
    /** The root's durable bindings, read fresh before every LIST (the C3 pagination boundary). None by default. */
    val rowsAtStart: () -> Set<BindingRef> = { emptySet() },
) : AutoCloseable {
    val mirrorRoot: Path = Files.createTempDirectory("pb-hybrid-mirror")
    private val stateFile: Path = Files.createTempFile("pb-hybrid-mirror-state", ".json").also { Files.deleteIfExists(it) }
    val ignoreRules = IgnoreRules()
    val fake = FakeObjectStore(conflictStatus)
    val mirror = LocalContentStore(root = mirrorRoot, ignoreRules = ignoreRules, atomics = mirrorAtomics)
    val state = MirrorState(stateFile, stateAtomics)
    val store = ObjectContentStore(
        client = fake,
        mirror = mirror,
        state = state,
        binding = binding,
        rowsAtStart = rowsAtStart,
        keyPrefix = keyPrefix,
        pollSeconds = pollSeconds,
        dirtyPaths = { dirtyPaths.toSet() },
        isDirty = isDirty ?: { it in dirtyPaths },
        mirrorRoot = mirrorRoot,
        ignoreRules = ignoreRules,
        atomics = mirrorAtomics, // the SAME injection point as the mirror's own - poll/hydrate route through this one
    )

    /** Writes [bytes] directly to both the fake bucket and the mirror, healed (a pre-existing page). */
    fun seedExisting(path: TreePath, bytes: ByteArray) {
        val etag = fake.seed(mirror.resolveRepoRelativePath(path), bytes)
        mirror.write(path, bytes)
        state.recordConfirmed(path, etag)
        state.persist()
        mirror.scan()
    }

    override fun close() {
        mirrorRoot.toFile().deleteRecursively()
        Files.deleteIfExists(stateFile)
    }
}

/**
 * A [FileAtomics] whose [atomicMove]/[copyReplace] throw when [shouldFail] says so - the injection
 * seam every mirror-apply-failure test uses (seam g). Defaults to never failing.
 */
class FailableFileAtomics(private val delegate: FileAtomics = FileAtomics.Real) : FileAtomics {

    /** Called once per attempted mirror write; true throws instead of delegating. */
    var shouldFail: () -> Boolean = { false }

    /** Called with the write TARGET - lets a batch (e.g. a poll cycle) fail exactly one key. */
    var shouldFailForTarget: (Path) -> Boolean = { false }

    override fun createLink(link: Path, existing: Path) = delegate.createLink(link, existing)

    override fun atomicMove(source: Path, target: Path) {
        if (shouldFail() || shouldFailForTarget(target)) throw IOException("simulated mirror write failure (atomicMove)")
        delegate.atomicMove(source, target)
    }

    override fun copyReplace(source: Path, target: Path) {
        if (shouldFail() || shouldFailForTarget(target)) throw IOException("simulated mirror write failure (copyReplace)")
        delegate.copyReplace(source, target)
    }

    override fun fsync(path: Path) = delegate.fsync(path)

    /** Fails the first [times] attempts, then delegates normally - the "heals on reconcile" shape. */
    fun failFirst(times: Int) {
        val remaining = AtomicInteger(times)
        shouldFail = { remaining.getAndDecrement() > 0 }
    }

    /** Fails every attempt - the "heal also fails" shape (never `recordConfirmed`, no NPE). */
    fun failAlways() {
        shouldFail = { true }
    }
}

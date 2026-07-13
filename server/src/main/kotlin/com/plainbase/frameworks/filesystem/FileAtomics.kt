package com.plainbase.frameworks.filesystem

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The java.nio primitives whose availability varies by filesystem (hardlinks, atomic rename).
 * Injectable so [LocalContentStore]'s exotic-FS fallback branches are deterministically testable;
 * [Real] is the production default — plain delegation, no behavior. Adapter-scoped by convention
 * (framework layer), NOT a domain port: [LocalContentStore] is already the adapter, and this only
 * splits its own FS calls behind a seam. Public (not `internal`) only because [LocalContentStore]'s
 * public constructor takes it as a defaulted param, and the native-test source set — which is not
 * associated with `main` for internal visibility — constructs [LocalContentStore] directly.
 */
interface FileAtomics {
    fun createLink(link: Path, existing: Path)
    fun atomicMove(source: Path, target: Path)
    fun copyReplace(source: Path, target: Path)

    /**
     * Forces [path] to the physical medium - a file's BYTES, or a directory's ENTRIES.
     *
     * An `ATOMIC_MOVE` is atomic against a killed PROCESS the moment it returns, and that is all it is. Against a
     * killed HOST the rename and the bytes it publishes are two separate things the filesystem may still be holding
     * in cache, so a promote that skips this can survive exactly the kill it was designed for and come back as a
     * ZERO-LENGTH file - which for `roots.conf` is an install that has silently lost every root it had.
     *
     * Both halves are needed and they are not interchangeable: fsync the FILE and the bytes are durable but the
     * name may not point at them; fsync the DIRECTORY and the name is durable but may point at nothing. The caller
     * decides what a failure means - for the temp file it is fatal (the bytes are not there), for the parent it is
     * not (the file is present and visible; only the rename's durability is in doubt, and Windows cannot open a
     * directory channel at all).
     */
    fun fsync(path: Path)

    object Real : FileAtomics {
        override fun createLink(link: Path, existing: Path) {
            Files.createLink(link, existing)
        }

        override fun atomicMove(source: Path, target: Path) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        override fun copyReplace(source: Path, target: Path) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }

        override fun fsync(path: Path) {
            // A directory channel is READ-only (a WRITE open of one is EISDIR); a file's must be writable to force.
            val mode = if (Files.isDirectory(path)) StandardOpenOption.READ else StandardOpenOption.WRITE
            FileChannel.open(path, mode).use { it.force(true) }
        }
    }
}

package com.plainbase.frameworks.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
    }
}

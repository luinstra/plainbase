package com.plainbase.frameworks.filesystem

import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C1b native-divergence pin (item 1's native acceptance bullet): [FileAtomics.Real] — the java.nio
 * primitives [LocalContentStore] routes its FS writes through — exercised UNDER THE NATIVE IMAGE, so
 * the [LocalContentStoreTest] policy comment's "plain java.nio behaves identically on JVM and native"
 * is a TESTED fact, not an assumption. Plus the guard-2 mechanism: a symlink's [Path.toRealPath]
 * escapes the content root (what `rejectionReason`/`isWithinRoot` rely on) resolves correctly natively.
 *
 * @Tag("native") + kotlin.test only — this source set compiles INTO the native test image, so
 * Kotest/MockK must never appear here. Primitive-level (no [LocalContentStore] construction) so it
 * stays tiny in the native test binary — the seam-driven CONTROL-FLOW suites stay JVM Kotest.
 */
@Tag("native")
class FileAtomicsRealNativeTest {

    @Test
    fun `createLink hardlinks the target with the full content`() {
        withTempDir { dir ->
            val existing = dir.resolve("existing.bin")
            Files.write(existing, byteArrayOf(1, 2, 3, 4))
            val link = dir.resolve("link.bin")

            FileAtomics.Real.createLink(link, existing)

            assertTrue(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS), "hardlink not created")
            assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(link))
        }
    }

    @Test
    fun `atomicMove renames the source onto the target, replacing it`() {
        withTempDir { dir ->
            val source = dir.resolve("source.bin")
            Files.write(source, byteArrayOf(9, 8, 7))
            val target = dir.resolve("target.bin")
            Files.write(target, byteArrayOf(0)) // a stale target to REPLACE

            FileAtomics.Real.atomicMove(source, target)

            assertContentEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(target))
            assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS), "source not consumed by the move")
        }
    }

    @Test
    fun `copyReplace copies the source over an existing target`() {
        withTempDir { dir ->
            val source = dir.resolve("source.bin")
            Files.write(source, byteArrayOf(5, 5, 5, 5))
            val target = dir.resolve("target.bin")
            Files.write(target, byteArrayOf(1))

            FileAtomics.Real.copyReplace(source, target)

            assertContentEquals(byteArrayOf(5, 5, 5, 5), Files.readAllBytes(target))
            assertTrue(Files.exists(source, LinkOption.NOFOLLOW_LINKS), "copy must leave the source in place")
        }
    }

    @Test
    fun `a symlink's real path escapes the content root under native-image`() {
        val root = Files.createTempDirectory("pb-native-atomics-root")
        val outside = Files.createTempDirectory("pb-native-atomics-outside")
        try {
            val escape = root.resolve("escape")
            try {
                Files.createSymbolicLink(escape, outside)
            } catch (_: IOException) {
                return // platform/permissions disallow symlinks — nothing to assert
            }
            // The guard-2 primitive: the symlink's resolved real path is NOT inside the root's real path.
            assertFalse(
                escape.toRealPath().startsWith(root.toRealPath()),
                "toRealPath failed to detect the out-of-root escape",
            )
        } finally {
            deleteRecursively(root)
            deleteRecursively(outside)
        }
    }
}

private fun withTempDir(block: (Path) -> Unit) {
    val dir = Files.createTempDirectory("pb-native-atomics")
    try {
        block(dir)
    } finally {
        deleteRecursively(dir)
    }
}

private fun deleteRecursively(dir: Path) {
    Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

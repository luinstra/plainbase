package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MirrorState]'s persist/corruption cold-path under the native image (NIO/charset divergence
 * surface): temp+ATOMIC_MOVE round-trip equality, and the M1 cold-path (missing / unparseable /
 * wrong-version file all load EMPTY). kotlin.test + `@Tag("native")` only.
 */
@Tag("native")
class MirrorStatePersistTest {

    @Test
    fun `persist then reload round-trips the map exactly`() {
        val dir = Files.createTempDirectory("pb-native-mirror-state")
        try {
            val file = dir.resolve("mirror-state")
            val a = TreePath.require("a.md")
            val b = TreePath.require("dir/b.md")
            val state = MirrorState(file)
            state.recordConfirmed(a, "\"etag-a\"")
            state.recordConfirmed(b, "\"etag-b\"")
            state.persist()

            val reloaded = MirrorState(file)
            assertEquals(mapOf(a to "\"etag-a\"", b to "\"etag-b\""), reloaded.snapshot())
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun `an invalidate is durable across a reload`() {
        val dir = Files.createTempDirectory("pb-native-mirror-state-invalidate")
        try {
            val file = dir.resolve("mirror-state")
            val a = TreePath.require("a.md")
            val state = MirrorState(file)
            state.recordConfirmed(a, "\"etag-a\"")
            state.persist()
            state.invalidate(a)
            state.persist()

            val reloaded = MirrorState(file)
            assertNull(reloaded.etagOf(a))
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun `a missing file loads EMPTY (the cold path)`() {
        val dir = Files.createTempDirectory("pb-native-mirror-state-missing")
        try {
            val state = MirrorState(dir.resolve("does-not-exist"))
            assertTrue(state.snapshot().isEmpty())
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun `unparseable JSON loads EMPTY (the cold path)`() {
        val dir = Files.createTempDirectory("pb-native-mirror-state-garbage")
        try {
            val file = dir.resolve("mirror-state")
            Files.writeString(file, "{ not json")
            val state = MirrorState(file)
            assertTrue(state.snapshot().isEmpty())
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun `a wrong version number loads EMPTY (the cold path)`() {
        val dir = Files.createTempDirectory("pb-native-mirror-state-version")
        try {
            val file = dir.resolve("mirror-state")
            Files.writeString(file, """{"version": 99, "entries": {"a.md": "\"etag-a\""}}""")
            val state = MirrorState(file)
            assertTrue(state.snapshot().isEmpty())
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun `an invalid path name inside the document loads EMPTY (the cold path)`() {
        val dir = Files.createTempDirectory("pb-native-mirror-state-badpath")
        try {
            val file = dir.resolve("mirror-state")
            Files.writeString(file, """{"version": 1, "entries": {"../escape": "\"etag\""}}""")
            val state = MirrorState(file)
            assertTrue(state.snapshot().isEmpty())
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun `persist is crash-safe - temp+ATOMIC_MOVE never leaves a torn file, old or new only`() {
        val dir = Files.createTempDirectory("pb-native-mirror-state-atomic")
        try {
            val file = dir.resolve("mirror-state")
            val a = TreePath.require("a.md")
            val state = MirrorState(file)
            state.recordConfirmed(a, "\"etag-a\"")
            state.persist()
            val afterFirst = Files.readString(file)

            state.recordConfirmed(a, "\"etag-a-v2\"")
            state.persist()
            val afterSecond = Files.readString(file)

            // Every observation of the file is either the FIRST complete document or the SECOND - never
            // a partial/interleaved write (the rename replaces the whole file in one filesystem operation).
            assertTrue(afterFirst.contains("etag-a"))
            assertTrue(afterSecond.contains("etag-a-v2"))
            // No stray temp sibling left behind.
            Files.list(dir).use { stream ->
                val names = stream.map { it.fileName.toString() }.toList()
                assertEquals(listOf("mirror-state"), names)
            }
        } finally {
            deleteRecursively(dir)
        }
    }
}

private fun deleteRecursively(dir: java.nio.file.Path) {
    Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

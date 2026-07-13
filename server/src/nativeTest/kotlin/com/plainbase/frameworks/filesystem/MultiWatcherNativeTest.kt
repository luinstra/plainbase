package com.plainbase.frameworks.filesystem

import com.plainbase.domain.root.RootName
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * N watchers over N trees, all feeding the ONE debounced scheduler — the shape `serve()` wires since multi-root.
 *
 * Native-tagged because `WatchService` is a genuine JVM-vs-native-image divergence surface: it is inotify on Linux
 * and a POLLING implementation on macOS, and the native image resolves the provider closed-world. A watcher that
 * silently never fires under the native binary would leave a server that looks perfectly healthy and quietly stops
 * converging — which is precisely the failure the per-root `onFailure` callback exists to make loud.
 */
@Tag("native")
class MultiWatcherNativeTest {

    private fun tree(name: String): Path = Files.createTempDirectory("pb-multiwatch-$name")

    @Test
    fun `events from BOTH roots reach the one scheduler-shaped sink, each tagged with its own root`() {
        val a = tree("a")
        val b = tree("b")
        try {
            // The root on each closure is carried for LOGGING only - the scheduler itself stays root-BLIND, because a
            // rebuild is a whole-corpus pass and needs no idea which tree woke it.
            val seen = ConcurrentHashMap.newKeySet<RootName>()
            val both = CountDownLatch(2)
            val watchers = listOf(RootName.MAIN to a, RootName.require("extra") to b).map { (root, dir) ->
                LocalContentStore(dir).watch(
                    onChange = {
                        if (seen.add(root)) both.countDown()
                    },
                )
            }

            try {
                Files.writeString(a.resolve("in-main.md"), "# A\n")
                Files.writeString(b.resolve("in-extra.md"), "# B\n")

                assertTrue(both.await(90, TimeUnit.SECONDS), "both roots must deliver: saw $seen")
            } finally {
                watchers.forEach { it.close() }
            }
        } finally {
            listOf(a, b).forEach { it.toFile().deleteRecursively() }
        }
    }

    /**
     * A subtree the registration walk cannot open - permissions here, the inotify watch limit in production, the
     * same lost coverage either way. The watcher used to LOG it and report success: no events under it, no rebuild,
     * and no availability transition either (the idle tick only asks whether the ROOT still exists, and it does), so
     * the root serves carried-forward 200s after every edit, forever, behind a server that reports itself healthy.
     * It must fail THAT root - and only that root.
     *
     * Vacuous as uid 0 (which bypasses permission bits), so it skips there rather than passing falsely.
     */
    @Test
    fun `a subtree that cannot be registered FAILS that root's watcher - and leaves its siblings running`() {
        if (System.getProperty("user.name") == "root") return
        val a = tree("a")
        val b = tree("b")
        val locked = Files.createDirectory(a.resolve("locked"))
        try {
            Files.writeString(locked.resolve("page.md"), "# Locked\n")
            Files.setPosixFilePermissions(locked, emptySet())

            val failures = ConcurrentLinkedQueue<Throwable>()
            val fromB = CountDownLatch(1)
            val watchA = LocalContentStore(a).watch(onChange = {}, onFailure = { failures += it })
            val watchB = LocalContentStore(b).watch(onChange = { fromB.countDown() }, onFailure = { failures += it })

            try {
                // `is`, never assertIs: the native image resolves no Kotlin reflection metadata for the class.
                assertTrue(
                    failures.singleOrNull() is WatchCoverageLost,
                    "an un-watchable subtree was accepted as a healthy watcher (failures: $failures)",
                )

                Files.writeString(b.resolve("still-here.md"), "# B\n")
                assertTrue(fromB.await(90, TimeUnit.SECONDS), "per-root failure stays per-root: the sibling must keep converging")
            } finally {
                watchA.close()
                watchB.close()
            }
        } finally {
            Files.setPosixFilePermissions(locked, ALL_PERMS) // else the temp tree cannot be deleted
            listOf(a, b).forEach { it.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `closing ONE root's watcher leaves the other live - a lost root must not take its siblings down`() {
        val a = tree("a")
        val b = tree("b")
        try {
            val fromB = CountDownLatch(1)
            val watchA = LocalContentStore(a).watch(onChange = {})
            val watchB = LocalContentStore(b).watch(onChange = { fromB.countDown() })

            try {
                watchA.close() // the vanished-root case: its watcher goes away, and B must not care

                Files.writeString(b.resolve("still-here.md"), "# B\n")
                assertTrue(fromB.await(90, TimeUnit.SECONDS), "the surviving root's watcher must keep converging")
            } finally {
                watchB.close()
            }
        } finally {
            listOf(a, b).forEach { it.toFile().deleteRecursively() }
        }
    }

    private companion object {
        val ALL_PERMS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    }
}

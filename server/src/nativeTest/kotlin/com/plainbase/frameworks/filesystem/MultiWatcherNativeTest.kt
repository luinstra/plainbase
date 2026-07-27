package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.WatchCoverage
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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
            val watchers = listOf(RootName.PRIMARY to a, RootName.require("extra") to b).map { (root, dir) ->
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
     * same lost coverage either way. It is a CONVERGENCE fact and NOT an availability one, and the difference is
     * the whole test: the root exists, reads correctly and serves every byte it holds - what it has lost is EVENTS
     * under one subtree. Reported as a watcher FAILURE it would 503 a healthy root over a host-wide kernel limit,
     * stickily, until a restart that only re-registers, re-fails and re-marks: an outage the server inflicts on
     * itself and cannot leave.
     *
     * So: PARTIAL, no failure, no mark - and it keeps converging on the periodic pass until the registration
     * succeeds, at which point it says WHOLE again with nobody restarting anything.
     *
     * Vacuous as uid 0 (which bypasses permission bits), so it skips there rather than passing falsely.
     */
    @Test
    fun `a subtree that cannot be registered reports PARTIAL coverage - it never FAILS the root`() {
        if (System.getProperty("user.name") == "root") return
        val a = tree("a")
        val b = tree("b")
        val locked = Files.createDirectory(a.resolve("locked"))
        try {
            Files.writeString(locked.resolve("page.md"), "# Locked\n")
            Files.setPosixFilePermissions(locked, emptySet())

            val failures = ConcurrentLinkedQueue<Throwable>()
            val coverage = ConcurrentLinkedQueue<WatchCoverage>()
            val fromB = CountDownLatch(1)
            val watchA = LocalContentStore(a).watch(onChange = {}, onFailure = { failures += it }, onCoverage = { coverage += it })
            val watchB = LocalContentStore(b).watch(onChange = { fromB.countDown() }, onFailure = { failures += it })

            try {
                assertEquals(listOf(WatchCoverage.PARTIAL), coverage.toList(), "an un-watchable subtree must REPORT, not fail")
                assertTrue(
                    failures.isEmpty(),
                    "coverage loss reported as a failure is a restart-proof 503 for a root that serves fine (failures: $failures)",
                )
                assertTrue(LocalContentStore(a).available(), "the root is THERE - a subtree's permissions say nothing about that")

                Files.writeString(b.resolve("still-here.md"), "# B\n")
                assertTrue(fromB.await(90, TimeUnit.SECONDS), "per-root stays per-root: the sibling must keep converging")
            } finally {
                watchA.close()
                watchB.close()
            }
        } finally {
            Files.setPosixFilePermissions(locked, ALL_PERMS) // else the temp tree cannot be deleted
            listOf(a, b).forEach { it.toFile().deleteRecursively() }
        }
    }

    /**
     * The recovery, which is the half a sticky failure could never have: the permission is fixed IN PLACE, the
     * retry re-registers the subtree, and the watcher reports WHOLE with no restart. The converging pass it drives
     * meanwhile is what makes the degraded state honest rather than a slow lie - an edit made under the unwatched
     * subtree raises no event of its own, so the synthetic OVERFLOW is the only thing that will ever go find it.
     *
     * The retry interval is seamed down to sub-second (production is coarse - a whole-corpus pass is not free).
     */
    @Test
    fun `a fixed permission returns the tree to WHOLE with NO restart - and the converging pass keeps running meanwhile`() {
        if (System.getProperty("user.name") == "root") return
        val root = tree("recover")
        val locked = Files.createDirectory(root.resolve("locked"))
        try {
            Files.writeString(locked.resolve("page.md"), "# Locked\n")
            Files.setPosixFilePermissions(locked, emptySet())

            val coverage = ConcurrentLinkedQueue<WatchCoverage>()
            val converged = CountDownLatch(1)
            val whole = CountDownLatch(1)
            FileWatcher(
                root = root,
                ignoreRules = IgnoreRules(),
                excluded = emptyList(),
                // The unwatched subtree's edits land through THIS path and no other, so it is what the whole
                // degraded state rests on.
                onChange = { if (it == ContentStore.OVERFLOW) converged.countDown() },
                onCoverage = {
                    coverage += it
                    if (it == WatchCoverage.WHOLE) whole.countDown()
                },
                livenessInterval = 200.milliseconds,
                coverageRetryInterval = 300.milliseconds,
            ).use {
                assertTrue(
                    converged.await(60, TimeUnit.SECONDS),
                    "a PARTIAL tree that schedules no pass converges NEVER, not late: an edit under the unwatched subtree is lost",
                )

                Files.setPosixFilePermissions(locked, ALL_PERMS) // the operator fixes it, server still running

                assertTrue(whole.await(60, TimeUnit.SECONDS), "the retry never re-registered: coverage would be sticky until a restart")
            }
            assertEquals(
                listOf(WatchCoverage.PARTIAL, WatchCoverage.WHOLE),
                coverage.toList(),
                "both transitions, each reported ONCE - a report per retry tick would be a consumer told what it already knows",
            )
        } finally {
            Files.setPosixFilePermissions(locked, ALL_PERMS)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a fully-registerable tree reports NOTHING - silence is WHOLE, and a healthy watcher must not chatter`() {
        val root = tree("whole")
        try {
            Files.writeString(root.resolve("page.md"), "# Page\n")
            val coverage = ConcurrentLinkedQueue<WatchCoverage>()
            LocalContentStore(root).watch(onChange = {}, onCoverage = { coverage += it }).use {
                Thread.sleep(500)
            }
            assertTrue(coverage.isEmpty(), "an all-whole watcher reported coverage transitions it never made: $coverage")
        } finally {
            root.toFile().deleteRecursively()
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

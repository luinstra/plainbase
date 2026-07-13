package com.plainbase.frameworks.cli

import com.plainbase.bootGateFor
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.filesystem.DataDirLock
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **INVARIANT W: every mutating verb makes its ENTIRE decision under `roots.lock`, over state RE-READ under it.**
 *
 * `FileChannel.tryLock` is OS-level behavior that has bitten this project before, so mutual exclusion stays
 * tested. **But exclusion alone is NOT the property that matters, and testing only exclusion is what let the
 * design ship a check-then-act.** Here is the window a "validate, then lock, then re-check the NAME" protocol
 * leaves wide open:
 *
 *  - A validates against `roots.conf = {}` and holds a candidate `{x}` at `/srv/docs/x`.
 *  - B validates against `roots.conf = {}` and holds a candidate `{y}` at `/srv/docs/x/nested`.
 *  - A takes the lock, re-reads (still `{}`), the NAME `x` is free, writes `{x}`, releases.
 *  - B takes the lock, re-reads (`{x}` now), the NAME `y` is STILL FREE - **so a name-only re-check passes** -
 *    and B writes `{x, y}`. **The pair is NESTED. Neither add was ever invalid on its own; the CONFIGURATION
 *    is, and nothing ever validated the configuration.** The next boot refuses, from a command whose entire job
 *    is to never produce an unbootable config.
 *
 * Note what does NOT save you: the lock windows never overlap, so mutual exclusion is intact and USELESS; and
 * re-reading is necessary (without it B's write would silently DROP `x`) but not sufficient. **Only running the
 * GATE over the merged candidate, under the lock, catches it.**
 *
 * So these cases assert on the FINAL CONFIG, not on the lock. **They FAIL if the gate is ever moved back outside
 * the critical section** - which is the entire point. Do not "optimize" the gate out of it.
 *
 * `@Tag("native")`: `FileChannel.tryLock` is an OS surface, and the gate shells out to real `git`.
 */
@Tag("native")
class RootsLockNativeTest {

    private class World(val base: Path, val data: Path, val content: Path) {
        val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
        val rootsConf: Path get() = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
        fun config(): PlainbaseConfig = PlainbaseConfig.fromEnvAndFile(env)
    }

    private fun <T> world(block: (World) -> T): T {
        val base = Files.createTempDirectory("pb-roots-lock")
        return try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            Files.writeString(content.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")
            block(World(base, data, content))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    /** Runs [tasks] genuinely concurrently and returns their exit codes in submission order. */
    private fun concurrently(vararg tasks: () -> Int): List<Int> {
        val pool = Executors.newFixedThreadPool(tasks.size)
        return try {
            pool.invokeAll(tasks.map { Callable(it) }).map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** The final config must BOOT. That is the assertion; everything else is bookkeeping. */
    private fun assertFinalConfigBoots(world: World) {
        val config = world.config()
        assertTrue(
            bootGateFor(config).refusals.isEmpty(),
            "the surviving config does NOT boot: two individually-valid commands composed into a topology that " +
                "nothing ever validated. This is what happens when the gate runs outside the lock.",
        )
    }

    @Test
    fun `ADD-ADD of two roots whose paths NEST - one wins, one is refused, and the SURVIVING config boots`() = world { w ->
        // Each add is perfectly valid against the topology it started from. The PAIR is not. Nothing but a gate
        // over the MERGED candidate, under the lock, can catch that.
        val outer = Files.createDirectory(w.base.resolve("outer"))
        val inner = Files.createDirectories(outer.resolve("inner"))

        val exits = concurrently(
            { RootCommand.run(listOf("add", "outer", outer.toString()), w.env) },
            { RootCommand.run(listOf("add", "inner", inner.toString()), w.env) },
        )

        assertEquals(listOf(0, 1), exits.sorted(), "exactly one add must win and the other must be refused: $exits")
        assertFinalConfigBoots(w)
        assertEquals(1, w.config().roots.extras.size, "exactly one of the nesting pair may survive")
    }

    @Test
    fun `ADD-ADD of NON-conflicting roots - BOTH succeed, and neither update is lost`() = world { w ->
        // The lost-update case. The bounded lock wait exists precisely so this is not a first-one-wins race: two
        // `root add`s in a provisioning script are a normal thing to write.
        val alpha = Files.createDirectory(w.base.resolve("alpha"))
        val beta = Files.createDirectory(w.base.resolve("beta"))

        val exits = concurrently(
            { RootCommand.run(listOf("add", "alpha", alpha.toString()), w.env) },
            { RootCommand.run(listOf("add", "beta", beta.toString()), w.env) },
        )

        assertEquals(listOf(0, 0), exits, "both non-conflicting adds must succeed: $exits")
        assertEquals(
            setOf("alpha", "beta"),
            w.config().roots.extras.map { it.name.value }.toSet(),
            "an update was LOST: read-modify-write without re-reading under the lock drops one of them",
        )
        assertFinalConfigBoots(w)
    }

    @Test
    fun `ADD-REMOVE on the same root leaves a bootable config, and roots-conf is parseable or absent - never a truncated husk`() =
        world { w ->
            val notes = Files.createDirectory(w.base.resolve("notes"))
            Files.writeString(
                w.rootsConf,
                ManagedRootsFileSeed.forRoot("notes", notes),
            )
            val other = Files.createDirectory(w.base.resolve("other"))

            concurrently(
                { RootCommand.run(listOf("add", "other", other.toString()), w.env) },
                { RootCommand.run(listOf("remove", "notes"), w.env) },
            )

            // Either ordering is legal. What must hold in BOTH is that the file is whole and the config boots.
            assertFinalConfigBoots(w)
            if (Files.exists(w.rootsConf)) {
                assertTrue(Files.size(w.rootsConf) > 0, "roots.conf survived as a truncated husk")
            }
        }

    @Test
    fun `REMOVE-REMOVE of the same root - one succeeds, one reports no such root, and the file is gone`() = world { w ->
        val notes = Files.createDirectory(w.base.resolve("notes"))
        Files.writeString(w.rootsConf, ManagedRootsFileSeed.forRoot("notes", notes))

        val exits = concurrently(
            { RootCommand.run(listOf("remove", "notes"), w.env) },
            { RootCommand.run(listOf("remove", "notes"), w.env) },
        )

        assertEquals(listOf(0, 1), exits.sorted(), "one remove wins; the loser reports 'no such root': $exits")
        assertFalse(Files.exists(w.rootsConf), "the last managed root was removed, so the file must be unlinked")
        assertEquals(RootsOrigin.SYNTHESIZED, w.config().roots.origin)
        assertFinalConfigBoots(w)
    }

    @Test
    fun `the roots lock is a DIFFERENT lock from the DATA_DIR lock - staging a topology change while the server runs is the point`() {
        world { w ->
            // A running server holds `plainbase.lock` for its whole lifetime, and restart-to-apply means an operator
            // MUST be able to stage a change while it runs. Requiring them to stop the server to edit config they
            // can only apply by restarting would be a worse ritual for no safety.
            val serverLock = DataDirLock.tryAcquire(w.data)
            assertTrue(serverLock != null, "precondition: the DATA_DIR lock must be acquirable")
            serverLock.use {
                val notes = Files.createDirectory(w.base.resolve("notes"))
                assertEquals(
                    0,
                    RootCommand.run(listOf("add", "notes", notes.toString()), w.env),
                    "`plainbase root` must work WHILE a server holds the DATA_DIR lock",
                )
            }
            assertTrue(Files.exists(w.data.resolve(DataDirLock.ROOTS_LOCK_FILE_NAME)))
        }
    }

    @Test
    fun `a held roots lock is bounded-waited, then refused - not a bare immediate failure`() = world { w ->
        val held = DataDirLock.tryAcquire(w.data, DataDirLock.ROOTS_LOCK_FILE_NAME)
        assertTrue(held != null)
        held.use {
            val notes = Files.createDirectory(w.base.resolve("notes"))
            val started = System.nanoTime()
            val exit = RootCommand.run(listOf("add", "notes", notes.toString()), w.env)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000

            assertEquals(1, exit, "a contended lock must refuse, not corrupt")
            assertTrue(elapsedMillis >= 1_000, "it must WAIT (bounded), not fail instantly: waited ${elapsedMillis}ms")
            assertFalse(Files.exists(w.rootsConf), "a contended run must write nothing")
        }
    }
}

/** Seeds a `roots.conf` without going through the CLI - the arrange half of a concurrency case. */
private object ManagedRootsFileSeed {
    fun forRoot(name: String, path: Path): String =
        """
        roots {
          $name {
            backend = local
            path = "$path"
            editable = false
            history = off
          }
        }
        """.trimIndent()
}

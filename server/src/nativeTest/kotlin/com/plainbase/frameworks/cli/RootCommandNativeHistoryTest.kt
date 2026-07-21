package com.plainbase.frameworks.cli

import com.plainbase.bootGateFor
import com.plainbase.frameworks.config.PlainbaseConfig
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **`root add --history native` refuses EVERYTHING boot would refuse - by never listing what boot refuses.**
 *
 * This is the CLI-side twin of `GitNativeRootGuardTest`, and it is the regression test for the defect this whole
 * chunk was re-designed around. An earlier revision hand-wrote the guard as a LIST:
 *
 * ```
 * if (request.history == NATIVE) {              // <- only the REQUESTED root
 *     gitVersionFloorFailure(exec)?.let { … }   // <- and it FORGOT the git binary probe
 *     nativeRootGuardFailure(exec, abs)?.let { … }   // <- and the repo ACCESS probe
 * }
 * ```
 *
 * Four things were wrong with that, and a three-seat review panel caught only two of them:
 *  - it looked at the REQUESTED root only, so `root add x --history off` could rewrite a topology that still
 *    contained an EXISTING invalid `native` root and report SUCCESS;
 *  - it omitted the git VERSION FLOOR, so a git below 2.31 that `serve` REJECTS passed the CLI;
 *  - it omitted the missing-BINARY check and the repo ACCESS probe (dubious ownership, a container UID
 *    mismatch, a corrupt `.git`) - **a four-item list, reviewed three times, was missing half of what it was a
 *    list OF**;
 *  - and it refused in the WRONG DIRECTION too: `serve` SKIPS the gate for an extra whose path is absent, so a
 *    `native` root on an unmounted disk degrades to 503 by design - the hand-rolled guard would have refused an
 *    add the server accepts.
 *
 * The CLI now calls the server's own gate and NAMES none of them, so it gets all four for free.
 * `@Tag("native")`: real `git` process execution.
 */
@Tag("native")
class RootCommandNativeHistoryTest {

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed in $dir: $output" }
    }

    private fun repo(dir: Path): Path {
        Files.createDirectories(dir)
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "t@example.com")
        git(dir, "config", "user.name", "T")
        Files.writeString(dir.resolve("doc.md"), "---\ntitle: D\n---\n\n# D\n")
        git(dir, "add", ".")
        git(dir, "commit", "-qm", "seed")
        return dir
    }

    private fun <T> world(block: (World) -> T): T {
        val base = Files.createTempDirectory("pb-root-native-history")
        return try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            Files.writeString(content.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")
            block(World(base, data, content))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private class World(val base: Path, val data: Path, val content: Path) {
        val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
        val rootsConf: Path get() = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
        fun add(name: String, path: Path, vararg extra: String): Int =
            RootCommand.run(listOf("add", name, path.toString()) + extra.toList(), env, NativeCommandOutputCapture.current)
    }

    /** In EVERY refusal case: exit 1, roots.conf untouched, and no temp sibling left behind. */
    private fun assertRefused(world: World, exit: Int) {
        assertEquals(1, exit, "the CLI accepted a `native` root that `serve` would refuse to boot on")
        assertFalse(Files.exists(world.rootsConf), "a refusal must write NOTHING")
        val litter = Files.list(world.data).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }
        assertTrue(litter.isEmpty(), "a temp sibling survived a refusal: $litter")
    }

    @Test
    fun `(a) a plain readable directory that is not a repo at all is refused`() = world { w ->
        val plain = Files.createDirectory(w.base.resolve("plain"))
        assertRefused(w, w.add("plain", plain, "--history", "native"))
    }

    @Test
    fun `(b) a LINKED WORKTREE is refused - Plainbase must never commit into somebody else's checkout`() = world { w ->
        val origin = repo(w.base.resolve("origin"))
        val linked = w.base.resolve("linked")
        git(origin, "worktree", "add", "-q", linked.toString())
        assertRefused(w, w.add("linked", linked, "--history", "native"))
    }

    @Test
    fun `(c) a SUBMODULE is refused`() = world { w ->
        val inner = repo(w.base.resolve("inner"))
        val outer = repo(w.base.resolve("outer"))
        // -c protocol.file.allow=always: git >= 2.38 refuses file:// submodules by default (CVE-2022-39253).
        git(outer, "-c", "protocol.file.allow=always", "submodule", "add", "-q", inner.toString(), "sub")
        assertRefused(w, w.add("sub", outer.resolve("sub"), "--history", "native"))
    }

    @Test
    fun `(d) a directory NESTED inside a surrounding checkout is refused - the case that would commit into the operator's real repo`() =
        world { w ->
            val surrounding = repo(w.base.resolve("surrounding"))
            val nested = Files.createDirectories(surrounding.resolve("docs"))
            Files.writeString(nested.resolve("p.md"), "---\ntitle: P\n---\n\n# P\n")
            assertRefused(w, w.add("nested", nested, "--history", "native"))
        }

    @Test
    fun `the CONTROL - a real standalone repo is ACCEPTED`() = world { w ->
        val standalone = repo(w.base.resolve("standalone"))
        assertEquals(0, w.add("standalone", standalone, "--history", "native"))
        assertTrue(Files.exists(w.rootsConf))
        val extra = PlainbaseConfig.fromEnvAndFile(w.env).roots.extras.single()
        assertEquals("native", extra.history.name.lowercase())
    }

    @Test
    fun `the SECOND control - history off over that same plain directory is ACCEPTED`() = world { w ->
        // So the gate is HISTORY-GATED, not a blanket git requirement: the same directory that a `native` root is
        // refused over is a perfectly good `off` root.
        val plain = Files.createDirectory(w.base.resolve("plain"))
        assertEquals(0, w.add("plain", plain))
        assertTrue(Files.exists(w.rootsConf))
    }

    @Test
    fun `THE PROBE-FIRST DIRECTION - a native root on a path that is NOT THERE is ACCEPTED, because serve degrades it to 503`() =
        world { w ->
            // The bug an enumerated guard would have ADDED. `serve` marks an extra whose store is missing
            // MISSING_AT_BOOT and SKIPS its gate check entirely: a `native` root on an unmounted disk must degrade
            // to 503 like any other unavailable root, not take the whole server down, and the guard re-arms on the
            // next restart when the disk is back and it can actually judge the repo. **A CLI that is STRICTER than
            // `serve` is a bug, not a safety margin.**
            assertEquals(0, w.add("notyet", Path.of("/mnt/definitely-not-mounted"), "--history", "native"))
            assertTrue(Files.exists(w.rootsConf))
        }

    @Test
    fun `INSTANCE SEVEN - an unrelated OFF add over an EXISTING native root that went bad WARNS, names it, and proceeds`() =
        world { w ->
            // The regression test for the defect that forced the whole redesign. The old guard keyed on
            // `request.history`, so an `--history off` add never looked at the other roots AT ALL - it would rewrite
            // the topology and report SUCCESS while handing back a config that will not boot.
            //
            // The fix EVALUATES every root, always. What it then DOES about a fault on a root the operator did not
            // touch is a SEPARATE question, and the answer is WARN, not refuse: a fault that was already there is
            // not one this command introduced, and refusing would trap the operator inside their own broken config.
            // **Evaluate everything; refuse only what you broke.**
            val broken = repo(w.base.resolve("broken"))
            Files.writeString(
                w.data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE),
                """roots { broken { backend = local, path = "$broken", editable = false, history = native } }""",
            )
            // Turn the real repo into a LINKED WORKTREE after the fact, which `serve` refuses to boot on.
            val host = repo(w.base.resolve("host"))
            broken.toFile().deleteRecursively()
            git(host, "worktree", "add", "-q", broken.toString())

            val before = Files.readAllBytes(w.rootsConf)
            val unrelated = Files.createDirectory(w.base.resolve("unrelated"))

            val err = captureStderr {
                assertEquals(0, w.add("unrelated", unrelated), "a PRE-EXISTING fault must not block an unrelated add")
            }
            assertTrue(err.contains("WARNING"), "the pre-existing failure must be printed as a warning: $err")
            assertTrue(err.contains("broken"), "the warning must NAME the other root - that is the instance-7 regression: $err")
            assertFalse(
                Files.readAllBytes(w.rootsConf).contentEquals(before),
                "the add should have been WRITTEN: the CLI never made this config less bootable",
            )
            assertContentEquals(
                listOf("main", "broken", "unrelated"),
                PlainbaseConfig.fromEnvAndFile(w.env).roots.list.map { it.name.value },
            )
        }

    @Test
    fun `THE SIBLING - on that SAME broken install, an add that introduces a NEW fault is REFUSED, and the message names the NEW one`() =
        world { w ->
            // Inherit a fault, get warned. INTRODUCE one, get refused. The two cases together ARE the policy;
            // either alone is a half-test.
            val broken = repo(w.base.resolve("broken"))
            Files.writeString(
                w.data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE),
                """roots { broken { backend = local, path = "$broken", editable = false, history = native } }""",
            )
            val host = repo(w.base.resolve("host"))
            broken.toFile().deleteRecursively()
            git(host, "worktree", "add", "-q", broken.toString())

            val before = Files.readAllBytes(w.rootsConf)
            val nestedInMain = Files.createDirectories(w.content.resolve("deeper"))

            val err = captureStderr {
                assertEquals(1, w.add("deeper", nestedInMain), "an add that INTRODUCES a nesting violation must be refused")
            }
            assertTrue(err.contains("roots.deeper"), "the message must name the NEW fault, not the pre-existing git one: $err")
            assertContentEquals(before.toList(), Files.readAllBytes(w.rootsConf).toList(), "a refusal writes NOTHING")
        }

    @Test
    fun `an INVALID root can always be REMOVED - the operator is never trapped inside a config only this command can repair`() =
        world { w ->
            // Without the baseline diff this is exit 1 and the operator is stuck: the only command that can fix
            // their config declines to run BECAUSE their config is broken. That is the single worst thing this CLI
            // could do, and it is why the policy is "refuse only what you broke".
            val broken = repo(w.base.resolve("broken"))
            Files.writeString(
                w.data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE),
                """roots { broken { backend = local, path = "$broken", editable = false, history = native } }""",
            )
            val host = repo(w.base.resolve("host"))
            broken.toFile().deleteRecursively()
            git(host, "worktree", "add", "-q", broken.toString())

            // `serve` refuses this config today.
            assertTrue(
                bootGateFor(PlainbaseConfig.fromEnvAndFile(w.env)).refusals.isNotEmpty(),
                "precondition: this install must currently REFUSE to boot, or the test proves nothing",
            )

            captureStderr {
                assertEquals(0, RootCommand.run(listOf("remove", "broken"), w.env, NativeCommandOutputCapture.current))
            }

            // And the resulting config BOOTS. Removing the offender cleared the refusal.
            assertFalse(Files.exists(w.rootsConf))
            assertTrue(
                bootGateFor(PlainbaseConfig.fromEnvAndFile(w.env)).refusals.isEmpty(),
                "removing the offending root must have made the config bootable again",
            )
        }
}

/** Captures System.err for the duration of [block] - the CLI's operator-facing channel, and where refusals go. */
private fun captureStderr(block: () -> Unit): String = NativeCommandOutputCapture.captureStderr(block)

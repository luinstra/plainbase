package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The C4 absence oracle on the PROCESS-EXEC divergence surface: real `git` subprocesses, no [GitExecutor] mocking.
 * Every fail-closed mode from plan #19 gets a case (a shallow clone, a rewritten history, an unresolvable ref, a
 * hostile repo-local diff driver, a replace-ref graft) plus the end-to-end range reads a boot actually makes. The
 * malformed-STREAM parser arms are pure and live in JVM `NameStatusParserTest`; the DECISION logic (covers, advance,
 * refutation) is pinned by JVM `GitAbsenceConvergenceTest` against a fake - this file is only real-git behavior.
 *
 * @Tag("native") + kotlin.test only - this source set compiles INTO the native test image.
 */
@Tag("native")
class GitAbsenceOracleNativeTest {

    @Test
    fun `currentHead - a commit yields a full sha, no-repo, no-commits, a SHALLOW clone, and a failed probe are null`() {
        // A plain directory that is not a git repo: the shallow probe fails, and a failed probe is never a licence.
        Repo(tempDir("plain")).use { assertNull(it.provider.currentHead()) }

        // A repo with NO commits: the probe says "false" but rev-parse HEAD is unborn -> null.
        Repo(tempDir("fresh")).use {
            it.run("init")
            assertNull(it.provider.currentHead())
        }

        // A real repo with a commit: a full object id.
        Repo(tempDir("real")).use {
            it.run("init")
            it.write("a.md", "a\n")
            val sha = it.commitAll("c1")
            assertEquals(sha, it.provider.currentHead())
        }

        // A SHALLOW clone is DISABLED OUTRIGHT (#19): a truncated history could miss the deleting commit.
        Repo(tempDir("shallow-src")).use { src ->
            src.run("init")
            src.write("a.md", "a\n")
            src.commitAll("c1")
            src.write("b.md", "b\n")
            src.commitAll("c2")
            val shallow = tempDir("shallow-dest").also { Files.delete(it) } // clone creates it
            src.run("clone", "--depth", "1", "file://${src.workTree}", shallow.toString())
            Repo(shallow).use { assertNull(it.provider.currentHead()) }
        }

        // A FAILED shallow probe: a bogus binary makes every invocation fail -> null (an inconclusive probe is null).
        val bogusHome = tempDir("bogus-home")
        val bogusWork = tempDir("bogus-work")
        val bogusExec = GitExecutor(workTree = bogusWork, home = bogusHome, gitBinary = "definitely-not-git-$RUN")
        assertNull(providerOver(bogusExec, bogusWork).currentHead())
    }

    @Test
    fun `isAncestor - linear history is true, reversed, unrelated, and unknown-sha are false`() {
        Repo(tempDir("ancestor")).use {
            it.run("init")
            it.write("a.md", "a\n")
            val c1 = it.commitAll("c1")
            it.write("b.md", "b\n")
            val c2 = it.commitAll("c2")
            assertTrue(it.provider.isAncestor(c1, c2))
            assertFalse(it.provider.isAncestor(c2, c1)) // not an ancestor (exit 1)
            assertFalse(it.provider.isAncestor("0".repeat(40), c2)) // unknown sha (exit 128) -> false

            it.run("checkout", "--orphan", "other")
            it.write("c.md", "c\n")
            val orphan = it.commitAll("orphan")
            assertFalse(it.provider.isAncestor(orphan, c2)) // unrelated lineage (a force-push / fresh clone)
        }
    }

    @Test
    fun `deletedIn - git rm shows D, a plain git mv shows ONLY the old md path, non-md deletions are dropped`() {
        Repo(tempDir("deleted")).use {
            it.run("init")
            it.write("guides/a.md", "a\n")
            it.write("guides/keep.md", "k\n")
            it.write("note.txt", "n\n")
            val base = it.commitAll("c1")

            it.run("rm", "guides/a.md", "note.txt")
            val afterRm = it.commitAll("rm")
            assertEquals(setOf(TreePath.require("guides/a.md")), it.provider.deletedIn(base, afterRm)) // note.txt dropped

            // A plain `git mv` (the --no-renames flag is OUR diff's, not mv's): the range is D keep.md + A moved.md,
            // and deletedIn returns ONLY the old path - the new path's file is what the pass's witness refutes with.
            it.run("mv", "guides/keep.md", "guides/moved.md")
            val afterMv = it.commitAll("mv")
            assertEquals(setOf(TreePath.require("guides/keep.md")), it.provider.deletedIn(afterRm, afterMv))
        }
    }

    @Test
    fun `deletedIn fails CLOSED to null on a ref that does not resolve`() {
        Repo(tempDir("badref")).use {
            it.run("init")
            it.write("a.md", "a\n")
            val h = it.commitAll("c1")
            assertNull(it.provider.deletedIn("0".repeat(40), h)) // an unknown from-ref -> non-zero exit -> null
        }
    }

    @Test
    fun `deletedIn maps an NFD-named deletion to its NFC TreePath - the form the walk would have produced`() {
        Repo(tempDir("nfd")).use {
            it.run("init")
            val nfd = Normalizer.normalize("café.md", Normalizer.Form.NFD)
            it.write(nfd, "x\n")
            val base = it.commitAll("c1")
            it.run("rm", nfd)
            val after = it.commitAll("rm")
            val nfc = Normalizer.normalize("café.md", Normalizer.Form.NFC)
            assertEquals(setOf(TreePath.require(nfc)), it.provider.deletedIn(base, after))
        }
    }

    @Test
    fun `a hostile repo-local diff-external cannot execute during deletedIn`() {
        Repo(tempDir("hostile")).use {
            it.run("init")
            it.write("a.md", "a\n")
            val base = it.commitAll("c1")
            it.run("rm", "a.md")
            val after = it.commitAll("rm")

            val sentinel = it.workTree.resolveSibling("pwned-${UUID.randomUUID()}")
            it.run("config", "diff.external", "sh -c 'touch $sentinel'")
            Files.writeString(it.workTree.resolve(".gitattributes"), "*.md diff=hostile\n")

            val deleted = it.provider.deletedIn(base, after) // --no-ext-diff --no-textconv must disarm the driver
            assertFalse(Files.exists(sentinel), "the hostile diff.external ran despite --no-ext-diff")
            assertEquals(setOf(TreePath.require("a.md")), deleted) // ...and the D-list is still correct
        }
    }

    @Test
    fun `a replace-ref graft cannot steer the oracle - the useReplaceRefs=false seal holds`() {
        Repo(tempDir("replace")).use {
            it.run("init")
            it.write("a.md", "a\n")
            val c1 = it.commitAll("c1")
            it.write("b.md", "b\n")
            it.commitAll("c2")
            it.write("c.md", "c\n")
            val c3 = it.commitAll("c3")

            // Graft a LIE: c1's parent is now c3 (a descendant). If replace refs were honored, c3 would read as an
            // ancestor of c1; with the seal, ancestry ignores the graft and answers by the real DAG.
            it.run("replace", "--graft", c1, c3)
            assertFalse(it.provider.isAncestor(c3, c1))
        }
    }

    @Test
    fun `a no-op boot (gc, no new commit) leaves head unchanged so the range is empty`() {
        Repo(tempDir("noop")).use {
            it.run("init")
            it.write("a.md", "a\n")
            val h = it.commitAll("c1")
            assertEquals(h, it.provider.currentHead())
            it.run("gc")
            assertEquals(h, it.provider.currentHead())
            assertEquals(emptySet<TreePath>(), it.provider.deletedIn(h, h)) // an empty range deletes nothing
        }
    }

    @Test
    fun `end to end - a real rm+commit range proves the deletion, a sibling mv+commit proves ONLY the old path`() {
        Repo(tempDir("e2e")).use {
            it.run("init")
            it.write("notes/rollback.md", "# Rollback\n")
            it.write("notes/keep.md", "# Keep\n")
            val checkpoint = it.commitAll("seed")

            // The offline delete: `git rm && commit`. From the recorded checkpoint the range descends and proves it.
            it.run("rm", "notes/rollback.md")
            val afterRm = it.commitAll("rm")
            assertTrue(it.provider.isAncestor(checkpoint, afterRm))
            assertEquals(setOf(TreePath.require("notes/rollback.md")), it.provider.deletedIn(checkpoint, afterRm))

            // The sibling `git mv`: the range shows ONLY the old path deleted - which this pass's witness refutes, so
            // the id travels and the permalink never splits (the decision that consumes this is pinned in the JVM suite).
            it.run("mv", "notes/keep.md", "notes/moved.md")
            val afterMv = it.commitAll("mv")
            assertEquals(setOf(TreePath.require("notes/keep.md")), it.provider.deletedIn(afterRm, afterMv))
        }
    }

    companion object {
        private val RUN = UUID.randomUUID().toString().take(8)
        private val IDENTITY = CommitIdentity("Plainbase Test", "test@plainbase.local")
        private val CLOCK = object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1_780_272_000L)
        }

        private fun tempDir(tag: String): Path = Files.createTempDirectory("pb-oracle-$tag-")

        private fun providerOver(exec: GitExecutor, workTree: Path): GitCliHistoryProvider =
            GitCliHistoryProvider(
                exec = exec,
                workTree = workTree,
                gitHome = tempDir("gh"),
                defaultAuthor = IDENTITY,
                defaultCommitter = IDENTITY,
                clock = CLOCK,
            )
    }

    /** A throwaway git repo rooted at [workTree], with the oracle provider over it. */
    private class Repo(val workTree: Path) : AutoCloseable {
        private val gitHome: Path = tempDir("home")
        val exec = GitExecutor(workTree = workTree, home = gitHome)
        val provider = providerOver(exec, workTree)

        fun run(vararg args: String): GitResult =
            exec.run(listOf(*args)).also { check(it.ok) { "git ${args.joinToString(" ")} failed: ${it.stderr}" } }

        fun write(path: String, content: String) {
            val target = workTree.resolve(path)
            Files.createDirectories(target.parent ?: workTree)
            Files.writeString(target, content)
        }

        /** Stages everything and commits with a pinned identity (git's global config is nulled), returning the new HEAD. */
        fun commitAll(message: String): String {
            run("add", "-A")
            run("-c", "user.name=${IDENTITY.name}", "-c", "user.email=${IDENTITY.email}", "commit", "-m", message)
            return requireNotNull(GitExecutor.parseSha(run("rev-parse", "HEAD").stdout))
        }

        override fun close() {
            workTree.toFile().deleteRecursively()
            gitHome.toFile().deleteRecursively()
        }
    }
}

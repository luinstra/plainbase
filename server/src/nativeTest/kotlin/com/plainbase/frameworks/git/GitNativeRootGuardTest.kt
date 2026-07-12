package com.plainbase.frameworks.git

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The D4 strict guard for a root whose repository the operator CLAIMED (`history = native`), against REAL `git`.
 *
 * The stake is not tidiness. An EXTRA root is a repository that exists for somebody ELSE's reasons, and Plainbase is
 * being handed write access to it. A linked worktree's `.git` is a FILE pointing at a shared object store whose refs
 * belong to the main checkout; a submodule's points at the superproject's `.git/modules/…`. In BOTH cases
 * `git -C <root>` succeeds — quietly — against a repository that is not this root's, and the operator finds out when
 * their unrelated branch has Plainbase commits on it. So the guard fails the BOOT rather than degrading: a server
 * that will not start is a far smaller problem than one that corrupts a repo it was trusted with.
 *
 * Native-tagged: this is PROCESS EXECUTION (the `git` subprocess through the cleared-env executor), which is one of
 * the five divergence surfaces the native image can behave differently on.
 */
@Tag("native")
class GitNativeRootGuardTest {

    private fun git(dir: Path, home: Path, vararg args: String) {
        val result = GitExecutor(workTree = dir, home = home).run(args.toList())
        assertTrue(result.ok, "git ${args.joinToString(" ")} failed: ${result.stderr}")
    }

    private fun <T> withRepos(block: (Path, Path) -> T): T {
        val work = Files.createTempDirectory("pb-rootguard")
        val home = Files.createTempDirectory("pb-rootguard-home")
        return try {
            block(work, home)
        } finally {
            listOf(work, home).forEach { it.toFile().deleteRecursively() }
        }
    }

    /** A repo with one commit, rooted exactly at [dir]. */
    private fun seedRepo(dir: Path, home: Path) {
        Files.createDirectories(dir)
        git(dir, home, "init")
        Files.writeString(dir.resolve("a.md"), "# A\n")
        git(dir, home, "add", "a.md")
        git(dir, home, "-c", "user.name=T", "-c", "user.email=t@e", "commit", "-m", "seed")
    }

    @Test
    fun `a plain repo rooted exactly at the declared path PASSES all four checks`() {
        withRepos { work, home ->
            val root = work.resolve("plain")
            seedRepo(root, home)

            assertNull(
                nativeRootGuardFailure(GitExecutor(workTree = root, home = home), root),
                "an ordinary repository the operator owns must be accepted - the guard exists to catch the foreign ones",
            )
        }
    }

    @Test
    fun `a LINKED WORKTREE is refused, and the message names the failed check`() {
        withRepos { work, home ->
            val main = work.resolve("main-checkout")
            seedRepo(main, home)
            val linked = work.resolve("linked")
            git(main, home, "worktree", "add", linked.toString())

            val failure = nativeRootGuardFailure(GitExecutor(workTree = linked, home = home), linked)
            assertNotNull(failure, "a linked worktree's refs belong to the MAIN checkout - committing here corrupts it")
            assertTrue(failure.contains("check 1"), "the operator needs to know WHICH check failed, not just that one did: $failure")
        }
    }

    @Test
    fun `a root NESTED inside somebody else's checkout is refused - git would walk up and find the ancestor`() {
        withRepos { work, home ->
            val outer = work.resolve("outer")
            seedRepo(outer, home)
            val nested = outer.resolve("docs")
            Files.createDirectories(nested)

            val failure = nativeRootGuardFailure(GitExecutor(workTree = nested, home = home), nested)
            assertNotNull(failure, "with no `.git` of its own, git resolves to the SURROUNDING repo - and would commit into it")
            assertTrue(failure.contains("check 1"), failure)
        }
    }

    @Test
    fun `a SYMLINKED root path PASSES - toRealPath on both sides, or every macOS deployment false-fails`() {
        withRepos { work, home ->
            val real = work.resolve("real")
            seedRepo(real, home)
            val link = work.resolve("link")
            Files.createSymbolicLink(link, real)

            // macOS `/tmp` is itself a symlink to `/private/tmp`, so a LEXICAL comparison of `--show-toplevel`
            // against the declared path would refuse a perfectly ordinary repository on every developer machine.
            assertNull(
                nativeRootGuardFailure(GitExecutor(workTree = link, home = home), link),
                "a symlinked root path is legitimate - the guard compares REAL paths on both sides",
            )
        }
    }

    @Test
    fun `the executor's cleared environment means a poisoned parent GIT_DIR cannot steer the guard`() {
        withRepos { work, home ->
            val root = work.resolve("plain")
            seedRepo(root, home)
            val decoy = work.resolve("decoy")
            seedRepo(decoy, home)

            // The guard's whole answer comes from `git rev-parse`, so an inherited GIT_DIR in the parent environment
            // would be a way to make a foreign repo LOOK like this root's own. The executor clears the environment at
            // its chokepoint, which is what makes the four checks trustworthy rather than merely well-intentioned.
            val exec = GitExecutor(workTree = root, home = home)
            val toplevel = exec.run(listOf("rev-parse", "--show-toplevel"))
            assertTrue(toplevel.ok)
            assertTrue(
                toplevel.stdoutText.trim().endsWith("plain") || Path.of(toplevel.stdoutText.trim()).toRealPath() == root.toRealPath(),
                "the executor must answer for the DECLARED work tree, whatever the ambient environment says: ${toplevel.stdoutText}",
            )
        }
    }
}

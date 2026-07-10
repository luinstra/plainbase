package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.objectstore.HybridFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Revision fold (systemic review, BLOCKING): [GitBundleDr.restore] used to treat EVERY non-ok
 * `rev-parse --verify HEAD^{commit}` as "incomplete, safe to delete" — including a `.git` that git simply
 * CANNOT READ in this environment (dubious ownership, permissions, ...), which is NOT the same as a
 * genuinely incomplete/absent repo. This suite proves [GitBundleDr.classify]'s three-way split, then
 * proves [GitBundleDr.restore] wires it correctly: a [GitBundleDr.GitState.UNREADABLE] mirror aborts the
 * boot (never deletes `.git`, never even GETs the bucket bundle), while the R1 self-heal (a genuinely
 * partial crash-mid-fetch `.git`) still classifies DEFINITIVELY_INCOMPLETE exactly as before.
 */
class GitBundleDrGitStateTest : FunSpec({

    // ---- the pure classifier: exactly the synthetic-GitResult unit test the revision asked for --------

    test("classify: an ok result is COMPLETE regardless of .git presence") {
        GitBundleDr.classify(okResult(), gitDirExists = true) shouldBe GitBundleDr.GitState.COMPLETE
    }

    test("classify: .git absent entirely is DEFINITIVELY_INCOMPLETE even if stderr looks unreadable") {
        val result = failResult(128, "fatal: detected dubious ownership in repository")
        GitBundleDr.classify(result, gitDirExists = false) shouldBe GitBundleDr.GitState.DEFINITIVELY_INCOMPLETE
    }

    test("classify: exit 128 + dubious-ownership stderr is UNREADABLE, never DEFINITIVELY_INCOMPLETE") {
        val result = failResult(128, "fatal: detected dubious ownership in repository at '/data/mirror'")
        GitBundleDr.classify(result, gitDirExists = true) shouldBe GitBundleDr.GitState.UNREADABLE
    }

    test("classify: exit 128 + \"not a git repository\" (a non-repo dir) is DEFINITIVELY_INCOMPLETE") {
        val result = failResult(128, "fatal: not a git repository (or any of the parent directories): .git")
        GitBundleDr.classify(result, gitDirExists = true) shouldBe GitBundleDr.GitState.DEFINITIVELY_INCOMPLETE
    }

    test("classify: exit 128 + \"Needed a single revision\" (unborn HEAD) is DEFINITIVELY_INCOMPLETE") {
        val result = failResult(128, "fatal: Needed a single revision")
        GitBundleDr.classify(result, gitDirExists = true) shouldBe GitBundleDr.GitState.DEFINITIVELY_INCOMPLETE
    }

    test("classify: permission-denied (a non-definitive failure) is UNREADABLE - fail closed") {
        val result = failResult(128, "fatal: cannot open '.git/HEAD' for reading: Permission denied")
        GitBundleDr.classify(result, gitDirExists = true) shouldBe GitBundleDr.GitState.UNREADABLE
    }

    test("classify: a timeout (exit -1, no definitive stderr signature) is UNREADABLE - fail closed") {
        val result = failResult(-1, "git timed out after 30s and was force-killed")
        GitBundleDr.classify(result, gitDirExists = true) shouldBe GitBundleDr.GitState.UNREADABLE
    }

    // ---- gitState() wired to a real GitExecutor: the R1 self-heal is preserved -------------------------

    test("gitState(): a real genuinely-absent .git classifies DEFINITIVELY_INCOMPLETE") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                Files.exists(hybrid.mirrorRoot.resolve(".git")) shouldBe false
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir).gitState() shouldBe
                    GitBundleDr.GitState.DEFINITIVELY_INCOMPLETE
            }
        }
    }

    test("gitState(): a real unborn-HEAD freshly-init'd repo classifies DEFINITIVELY_INCOMPLETE") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                exec.run(listOf("init")).ok shouldBe true
                bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir).gitState() shouldBe
                    GitBundleDr.GitState.DEFINITIVELY_INCOMPLETE
            }
        }
    }

    test("gitState(): a real crash-mid-fetch partial .git (HEAD only, no objects) classifies DEFINITIVELY_INCOMPLETE") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                Files.createDirectories(hybrid.mirrorRoot.resolve(".git"))
                Files.writeString(hybrid.mirrorRoot.resolve(".git/HEAD"), "ref: refs/heads/main\n")
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir).gitState() shouldBe
                    GitBundleDr.GitState.DEFINITIVELY_INCOMPLETE
            }
        }
    }

    test("gitState(): a real complete repo (an actual commit) classifies COMPLETE") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                providerOver(exec, hybrid.mirrorRoot, gitHomeDir, repoPath = { hybrid.mirror.resolveRepoRelativePath(it) })
                    .commit(TreePath.require("a.md"), "v1".toByteArray())
                bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir).gitState() shouldBe GitBundleDr.GitState.COMPLETE
            }
        }
    }

    // ---- restore() wired end-to-end: UNREADABLE aborts the boot without deleting .git or touching the bucket ----

    test(
        "restore(): a complete-but-UNREADABLE mirror (dubious ownership) ABORTS the boot, never deletes " +
            ".git, and never GETs the bucket bundle",
    ) {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                Files.createDirectories(hybrid.mirrorRoot.resolve(".git")) // a .git IS present
                val fakeGit = fakeGit(
                    "#!/bin/sh\n" +
                        "case \"$*\" in\n" +
                        "  *rev-parse*--verify*) echo 'fatal: detected dubious ownership in repository' >&2; exit 128 ;;\n" +
                        "  *) exit 0 ;;\n" +
                        "esac\n",
                )
                try {
                    val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir, gitBinary = fakeGit.toString())
                    val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                    val error = shouldThrow<GitMirrorUnreadableException> { bundleDr.restore() }

                    error.message!! shouldContain "ownership"
                    Files.exists(hybrid.mirrorRoot.resolve(".git")) shouldBe true // never deleted
                    hybrid.fake.getCount shouldBe 0 // never even reached the bucket GET
                } finally {
                    fakeGit.toFile().delete()
                }
            }
        }
    }
})

private fun okResult(): GitResult = GitResult(exitCode = 0, stdout = "deadbeef".toByteArray(), stderr = "")

private fun failResult(exitCode: Int, stderr: String): GitResult = GitResult(exitCode = exitCode, stdout = ByteArray(0), stderr = stderr)

/** A fresh git-home + tmp dir + sentinel-path trio for one test, always cleaned up (matches GitBundleDrShipTest). */
private fun <T> withHarness(block: (gitHomeDir: Path, tmpDir: Path, sentinelPath: Path) -> T): T {
    val dataDir = Files.createTempDirectory("plainbase-bundledr-gitstate-data")
    return try {
        block(dataDir.resolve("git-home"), dataDir.resolve("tmp"), dataDir.resolve("restore-pending"))
    } finally {
        dataDir.toFile().deleteRecursively()
    }
}

private fun bundleDrFor(
    exec: GitExecutor,
    hybrid: HybridFixture,
    tmpDir: Path,
    sentinelPath: Path,
    gitHomeDir: Path,
    locks: GitRepoLocks = GitRepoLocks(),
): GitBundleDr = GitBundleDr(
    exec = exec,
    objectStore = hybrid.store,
    mirrorRoot = hybrid.mirrorRoot,
    tmpDir = tmpDir,
    sentinelPath = sentinelPath,
    identity = testIdentity(),
    clock = fixedClock(),
    repoPath = { path -> hybrid.mirror.resolveRepoRelativePath(path) },
    gitHome = gitHomeDir,
    locks = locks,
)

/** Writes an executable fake `git` shell script with [script] as its body; the caller deletes it. */
private fun fakeGit(script: String): Path {
    val bin = Files.createTempFile("fake-git-bundledr", ".sh")
    Files.writeString(bin, script)
    Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"))
    return bin
}

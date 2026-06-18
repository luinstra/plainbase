package com.plainbase.frameworks.git

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Startup gate-check (W4 §6 #6, M2): with Git on but the binary missing, [GitCliHistoryProvider.gateCheck]
 * fails fast with an ACTIONABLE message — and it runs in `serve()` BEFORE any commit can fire (proven by
 * gateCheck never touching the repo / making a commit). NoOp is always ready.
 */
class HistoryGateCheckTest : FunSpec({

    test("git mode on with git absent fails fast with an actionable message and no commit") {
        withGitRepo { root, _ ->
            val home = Files.createTempDirectory("gate-home")
            try {
                val absentGit = GitExecutor(workTree = root, home = home, gitBinary = "/nonexistent/git")
                val provider = providerOver(absentGit, root, home, maintenance = {})
                val error = shouldThrow<GitUnavailableException> { provider.gateCheck() }
                error.message!! shouldContain "git"
                error.message!! shouldContain "PLAINBASE_GIT_ENABLED=false"
                // The gate never created a repo or a commit — it ran before any write path.
                Files.exists(root.resolve(".git")) shouldBe false
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }

    test("git mode on with git present passes the gate cleanly") {
        withGitRepo { root, exec ->
            val home = Files.createTempDirectory("gate-ok-home")
            try {
                shouldNotThrowAny { providerOver(exec, root, home).gateCheck() }
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }

    // P2 (round-4): `git --version` succeeds without opening the worktree, so a `.git`-present-but-
    // INACCESSIBLE repo (Docker uid-mismatch dubious ownership, permissions, corrupt .git) would slip past
    // the gate and only error on the FIRST save — defeating fail-fast. gateCheck now validates repo ACCESS
    // (`rev-parse --is-inside-work-tree`) when a repo is present, aborting at startup with an actionable msg.
    test("git mode on with a present but inaccessible repo fails the gate with an actionable message") {
        val root = Files.createTempDirectory("gate-inaccessible")
        val home = Files.createTempDirectory("gate-inaccessible-home")
        try {
            Files.createDirectory(root.resolve(".git")) // a repo IS present, so access is validated
            // A fake git: ok on `--version`, but `fatal: detected dubious ownership` (exit 128) on rev-parse.
            val fakeGit = fakeGit(
                "#!/bin/sh\n" +
                    "case \"$*\" in\n" +
                    "  *--version*) echo 'git version 2.54.0'; exit 0 ;;\n" +
                    "  *rev-parse*) echo 'fatal: detected dubious ownership in repository' >&2; exit 128 ;;\n" +
                    "  *) exit 0 ;;\n" +
                    "esac\n",
            )
            try {
                val exec = GitExecutor(workTree = root, home = home, gitBinary = fakeGit.toString())
                val provider = providerOver(exec, root, home, maintenance = {})
                val error = shouldThrow<GitUnavailableException> { provider.gateCheck() }
                error.message!! shouldContain "not accessible"
                // The remediation must be one that ACTUALLY works under Plainbase's hermetic git config —
                // ownership, not `safe.directory` (which git ignores from local/-c config).
                error.message!! shouldContain "ownership"
                error.message!! shouldContain "PLAINBASE_GIT_ENABLED=false"
            } finally {
                fakeGit.toFile().delete()
            }
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    // Force-on-without-`.git`: the repo is created lazily at the first commit, so there is nothing to
    // validate at the gate — only `--version` applies. The gate must NOT throw.
    test("git mode forced on without a .git passes the gate (repo init deferred to first commit)") {
        withGitRepo { root, exec ->
            val home = Files.createTempDirectory("gate-force-home")
            try {
                Files.exists(root.resolve(".git")) shouldBe false // no repo yet
                shouldNotThrowAny { providerOver(exec, root, home).gateCheck() }
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }

    test("NoOp gate-check is always a clean no-op") {
        shouldNotThrowAny { NoOpHistoryProvider.gateCheck() }
    }
})

/** Writes an executable fake `git` shell script with [script] as its body; the caller deletes it. */
private fun fakeGit(script: String): Path {
    val bin = Files.createTempFile("fake-git", ".sh")
    Files.writeString(bin, script)
    Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"))
    return bin
}

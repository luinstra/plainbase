package com.plainbase.frameworks.git

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    // R7 (P2): the read path passes `--diff-merges=first-parent`, valid only since git 2.31.0. An
    // older-but-present git (Ubuntu 20.04 → 2.25) would pass the binary/access checks, then abort serve at the
    // first read (`rebuild` → `lastCommits`). The gate now rejects a sub-floor version LOUD and actionable.
    test("git mode on with a too-old git fails the gate with an actionable upgrade message") {
        val root = Files.createTempDirectory("gate-old-git")
        val home = Files.createTempDirectory("gate-old-git-home")
        try {
            // No `.git`, so only the version check applies; a fake git reporting the Ubuntu 20.04 version.
            val fakeGit = fakeGit(
                "#!/bin/sh\n" +
                    "case \"$*\" in\n" +
                    "  *--version*) echo 'git version 2.25.1'; exit 0 ;;\n" +
                    "  *) exit 0 ;;\n" +
                    "esac\n",
            )
            try {
                val exec = GitExecutor(workTree = root, home = home, gitBinary = fakeGit.toString())
                val provider = providerOver(exec, root, home, maintenance = {})
                val error = shouldThrow<GitUnavailableException> { provider.gateCheck() }
                error.message!! shouldContain "2.31"
                error.message!! shouldContain "2.25"
                error.message!! shouldContain "PLAINBASE_GIT_ENABLED=false"
            } finally {
                fakeGit.toFile().delete()
            }
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    // A modern vendor banner (`(Apple Git-154)` suffix) must parse to 2.39 and PASS — the version regex
    // tolerates the trailing vendor string.
    test("git mode on with a modern vendor-suffixed git passes the version gate") {
        val root = Files.createTempDirectory("gate-vendor-git")
        val home = Files.createTempDirectory("gate-vendor-git-home")
        try {
            val fakeGit = fakeGit(
                "#!/bin/sh\n" +
                    "case \"$*\" in\n" +
                    "  *--version*) echo 'git version 2.39.5 (Apple Git-154)'; exit 0 ;;\n" +
                    "  *) exit 0 ;;\n" +
                    "esac\n",
            )
            try {
                val exec = GitExecutor(workTree = root, home = home, gitBinary = fakeGit.toString())
                shouldNotThrowAny { providerOver(exec, root, home, maintenance = {}).gateCheck() }
            } finally {
                fakeGit.toFile().delete()
            }
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    // An unparseable banner is NOT a false-fail: a present git whose version line we cannot read PASSES with
    // a logged warning (better than rejecting a perfectly modern git over an unanticipated banner shape).
    test("git mode on with an unparseable version banner passes the gate") {
        val root = Files.createTempDirectory("gate-weird-git")
        val home = Files.createTempDirectory("gate-weird-git-home")
        try {
            val fakeGit = fakeGit(
                "#!/bin/sh\n" +
                    "case \"$*\" in\n" +
                    "  *--version*) echo 'totally unexpected banner'; exit 0 ;;\n" +
                    "  *) exit 0 ;;\n" +
                    "esac\n",
            )
            try {
                val exec = GitExecutor(workTree = root, home = home, gitBinary = fakeGit.toString())
                shouldNotThrowAny { providerOver(exec, root, home, maintenance = {}).gateCheck() }
            } finally {
                fakeGit.toFile().delete()
            }
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    test("NoOp gate-check is always a clean no-op") {
        shouldNotThrowAny { NoOpHistoryProvider.gateCheck() }
    }

    // W5 revision MINOR: `prepare()` runs in `serve()` INSIDE the lock's try/finally and INSIDE the
    // actionable-error catch (mirroring gateCheck). A forced-on Git that cannot init its content-root repo
    // (read-only dir, disk-full, a `git init` fault) must THROW from prepare() so `serve()` presents the
    // operator-friendly `serve:` message and releases the lock — never a raw stack trace. This proves the
    // catch has a real throwable to surface.
    test("prepare() throws an actionable git failure when the content-root repo cannot be initialized") {
        val root = Files.createTempDirectory("prepare-init-fail")
        val home = Files.createTempDirectory("prepare-init-fail-home")
        try {
            // No `.git` present → prepare()/ensureRepo() runs `git init`; the fake git fails it (exit 128).
            val fakeGit = fakeGit(
                "#!/bin/sh\n" +
                    "case \"$*\" in\n" +
                    "  *init*) echo 'fatal: could not create work tree dir: Permission denied' >&2; exit 128 ;;\n" +
                    "  *) exit 0 ;;\n" +
                    "esac\n",
            )
            try {
                val exec = GitExecutor(workTree = root, home = home, gitBinary = fakeGit.toString())
                val provider = providerOver(exec, root, home, maintenance = {})
                val error = shouldThrow<GitCommandException> { provider.prepare() }
                error.message!! shouldContain "git init"
            } finally {
                fakeGit.toFile().delete()
            }
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    test("DataDirLock releases on close so a forced-on prepare() failure never leaks it") {
        // The lock-leak half of the MINOR: serve() closes the lock in the prepare()-failure catch before
        // exitProcess (which skips finally). Proven structurally — a released lock is re-acquirable; a leaked
        // one is not. tryAcquire after close must succeed.
        val dataDir = Files.createTempDirectory("prepare-lock-release")
        try {
            val first = com.plainbase.frameworks.filesystem.DataDirLock.tryAcquire(dataDir)
            first shouldNotBe null
            first!!.close()
            val second = com.plainbase.frameworks.filesystem.DataDirLock.tryAcquire(dataDir)
            second shouldNotBe null
            second!!.close()
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }
})

/** Writes an executable fake `git` shell script with [script] as its body; the caller deletes it. */
private fun fakeGit(script: String): Path {
    val bin = Files.createTempFile("fake-git", ".sh")
    Files.writeString(bin, script)
    Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"))
    return bin
}

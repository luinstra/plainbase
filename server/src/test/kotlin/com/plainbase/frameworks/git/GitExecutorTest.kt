package com.plainbase.frameworks.git

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.measureTime

/**
 * [GitExecutor] hardening (W4 §6): the argv/env golden (ADR-0006 "frozen by a golden test"), the F1
 * stderr-doesn't-corrupt-a-SHA proof, pipe-flood and timeout safety (P1-1), and the clean missing-binary
 * error. The output-shaping tests point the executor at a fake `git` shell script (injected via the
 * test-only `gitBinary` ctor param) so they control stdout/stderr deterministically.
 */
class GitExecutorTest : FunSpec({

    test("the assembled argv pins the -c config + the args") {
        withFakeGit("#!/bin/sh\nfor a in \"${'$'}@\"; do echo \"${'$'}a\"; done\n") { root, home, git ->
            val argv = GitExecutor(workTree = root, home = home, gitBinary = git).run(listOf("write-tree")).stdoutText.lines()
            argv shouldContainAll listOf(
                "-C", root.toString(),
                "-c", "core.autocrlf=false",
                "-c", "core.eol=lf",
                "-c", "commit.gpgsign=false",
                "-c", "core.hooksPath=/dev/null",
                "-c", "init.defaultBranch=main",
                "-c", "core.quotePath=false",
                "-c", "core.precomposeUnicode=false",
                "write-tree",
            )
        }
    }

    test("the environment is hermetic: pinned HOME + nulled config + no-prompt (ambient HOME cannot leak)") {
        withFakeGit("#!/bin/sh\nenv\n") { root, home, git ->
            val lines = GitExecutor(workTree = root, home = home, gitBinary = git).run(listOf("write-tree"))
                .stdoutText.lines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
            lines["HOME"] shouldBe home.toString()
            lines["GIT_CONFIG_GLOBAL"] shouldBe "/dev/null"
            lines["GIT_CONFIG_SYSTEM"] shouldBe "/dev/null"
            lines["LC_ALL"] shouldBe "C"
            lines["GIT_TERMINAL_PROMPT"] shouldBe "0"
            lines["GIT_ASKPASS"] shouldBe "true"
            lines["GIT_OPTIONAL_LOCKS"] shouldBe "0"
        }
    }

    test("parseSha accepts a 64-hex SHA-256 object id and a 40-hex SHA-1, rejects partial/garbage (r6e)") {
        val sha1 = "a".repeat(40)
        val sha256 = "b".repeat(64)
        GitExecutor.parseSha("$sha1\n".toByteArray()) shouldBe sha1
        GitExecutor.parseSha("$sha256\n".toByteArray()) shouldBe sha256
        // Not full-width / not hex → never a SHA (no 39, no 41, no 63, no 65, no non-hex).
        GitExecutor.parseSha("a".repeat(39).toByteArray()) shouldBe null
        GitExecutor.parseSha("a".repeat(41).toByteArray()) shouldBe null
        GitExecutor.parseSha("a".repeat(63).toByteArray()) shouldBe null
        GitExecutor.parseSha("a".repeat(65).toByteArray()) shouldBe null
        GitExecutor.parseSha("not-a-sha\n".toByteArray()) shouldBe null
    }

    test("stderr output does not corrupt a parsed SHA (F1)") {
        // Mimic `fatal: detected dubious ownership` on stderr while emitting a valid SHA on stdout — the
        // real uid-mismatch deploy scenario that a redirectErrorStream(true) would silently corrupt.
        val sha = "a".repeat(40)
        withFakeGit("#!/bin/sh\necho 'fatal: detected dubious ownership in repository' >&2\necho $sha\n") { root, home, git ->
            val result = GitExecutor(workTree = root, home = home, gitBinary = git).run(listOf("rev-parse", "HEAD"))
            GitExecutor.parseSha(result.stdout) shouldBe sha
            result.stderr shouldContain "dubious ownership"
            result.stdoutText.trim() shouldBe sha // the warning never merged into the parsed data
            result.stdoutText shouldNotBe result.stderr
        }
    }

    test("a git that floods stdout AND stderr does not deadlock") {
        // Both pipes flooded well past any OS pipe buffer (64 KiB) — separate concurrent drains prevent the
        // pipe deadlock that a single-threaded read would hit.
        withFakeGit("#!/bin/sh\nyes stdoutline | head -n 50000\nyes stderrline | head -n 50000 >&2\n") { root, home, git ->
            val result = GitExecutor(workTree = root, home = home, gitBinary = git).run(listOf("log"))
            result.ok shouldBe true
            result.stdoutText.lines().count { it == "stdoutline" } shouldBeGreaterThan 40000
            result.stderr.lines().count { it == "stderrline" } shouldBeGreaterThan 40000
        }
    }

    test("a missing git binary yields a clean GitResult error, not a thrown stack trace") {
        withGitRepo { root, _ ->
            val home = Files.createTempDirectory("git-missing-home")
            try {
                val result = GitExecutor(workTree = root, home = home, gitBinary = "/nonexistent/git").run(listOf("--version"))
                result.ok shouldBe false
                result.exitCode shouldBe -1
            } finally {
                home.toFile().deleteRecursively()
            }
        }
    }

    test("a hung git is force-killed at the timeout and returns a GitResult failure (P1-1)") {
        // A fake git that sleeps far longer than the (short, injected) timeout — the force-kill path must
        // return a failure quickly, never block the W1 monitor forever.
        withFakeGit("#!/bin/sh\nsleep 60\n") { root, home, git ->
            val exec = GitExecutor(workTree = root, home = home, timeoutSeconds = 2, gitBinary = git)
            val elapsed = measureTime {
                exec.run(listOf("gc")).ok shouldBe false
            }
            elapsed.inWholeSeconds shouldBeLessThan 15L
        }
    }

    test("a large stdin payload to a git that never reads it still force-kills at the timeout (P2)") {
        // A fake git that sleeps and NEVER reads stdin. With a >pipe-buffer (>64 KiB) payload, a
        // calling-thread stdin write would block forever BEFORE waitFor — holding the W1 monitor. The
        // off-thread stdin writer lets the bounded waitFor fire and force-kill regardless of payload size.
        // `exec sleep` makes the shell process ITSELF the sleeper holding stdin, so destroyForcibly closes
        // the pipe (no orphaned child keeps the read-end open) → the writer's join returns promptly.
        withFakeGit("#!/bin/sh\nexec sleep 60\n") { root, home, git ->
            val exec = GitExecutor(workTree = root, home = home, timeoutSeconds = 2, gitBinary = git)
            val bigStdin = ByteArray(256 * 1024) { 'x'.code.toByte() } // 256 KiB ≫ the OS pipe buffer
            val elapsed = measureTime {
                exec.run(listOf("hash-object", "--stdin"), stdin = bigStdin).ok shouldBe false
            }
            elapsed.inWholeSeconds shouldBeLessThan 15L
        }
    }

    test("a git that spawns a pipe-holding child and hangs still returns within the bounded timeout (P2-1)") {
        // The fake git backgrounds a child that INHERITS stdout (`sleep 60 &` — fd 1 stays open) and then the
        // parent itself blocks. destroyForcibly() kills only the direct process; the surviving grandchild
        // would hold stdout open and make an UNBOUNDED drain join hang past the timeout. The descendant-kill
        // + bounded join must cap run() at timeoutSeconds + grace regardless.
        withFakeGit("#!/bin/sh\nsleep 60 &\nwait\n") { root, home, git ->
            val exec = GitExecutor(workTree = root, home = home, timeoutSeconds = 2, gitBinary = git)
            val elapsed = measureTime {
                exec.run(listOf("gc")).ok shouldBe false
            }
            // timeout (2s) + drain grace (2s) + slack — far under any unbounded-join hang (the child sleeps 60s).
            elapsed.inWholeSeconds shouldBeLessThan 10L
        }
    }
})

/** Writes an executable fake `git` script with [script] as its body and runs [block] over a temp repo + home. */
private fun <T> withFakeGit(script: String, block: (root: Path, home: Path, gitBinary: String) -> T): T {
    val root = Files.createTempDirectory("plainbase-git-fake")
    val home = Files.createTempDirectory("plainbase-git-fake-home")
    val bin = Files.createTempFile("fake-git", ".sh")
    Files.writeString(bin, script)
    Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"))
    return try {
        block(root, home, bin.toString())
    } finally {
        Files.deleteIfExists(bin)
        root.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }
}

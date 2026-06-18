package com.plainbase.frameworks.koin

import com.plainbase.domain.history.HistoryProvider
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.git.GitCliHistoryProvider
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path

/**
 * Impl selection (W4 §6 #5, D-7): `git.enabled` overrides either direction; `null` auto-detects a repo in
 * CONTENT_DIR — and detection catches `.git`-as-a-FILE (linked worktree / submodule, P1-2), which a
 * `Files.isDirectory` check would miss and silently fall to NoOp.
 */
class HistoryModuleDetectionTest : FunSpec({

    fun resolve(config: PlainbaseConfig): HistoryProvider = selectHistoryProvider(config)

    fun config(content: Path, data: Path, enabled: Boolean?) =
        PlainbaseConfig(contentDir = content, dataDir = data, host = "0.0.0.0", port = 8080, git = GitConfig(enabled = enabled))

    test("no .git and enabled=null auto-selects NoOp") {
        withDirs { content, data ->
            resolve(config(content, data, enabled = null)) shouldBeSameInstanceAs NoOpHistoryProvider
        }
    }

    test("a real repo dir and enabled=null auto-selects GitCliHistoryProvider") {
        withDirs { content, data ->
            initRepo(content)
            resolve(config(content, data, enabled = null)).shouldBeInstanceOf<GitCliHistoryProvider>()
        }
    }

    test("a linked-worktree repo (.git is a file) auto-selects GitCliHistoryProvider (P1-2)") {
        withDirs { content, data ->
            // Build a real repo elsewhere, register a linked worktree at `content`; in a worktree, `.git`
            // is a FILE with a `gitdir:` pointer, not a directory.
            val mainRepo = Files.createTempDirectory("plainbase-main-repo")
            try {
                initRepo(mainRepo)
                // Seed a commit so a worktree can be added.
                Files.writeString(mainRepo.resolve("seed.txt"), "x")
                runGit(mainRepo, "add", "seed.txt")
                runGit(mainRepo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "seed")
                content.toFile().deleteRecursively() // worktree add needs the target absent
                runGit(mainRepo, "worktree", "add", content.toString())

                Files.isRegularFile(content.resolve(".git")) // sanity: it IS a file, not a dir
                resolve(config(content, data, enabled = null)).shouldBeInstanceOf<GitCliHistoryProvider>()
            } finally {
                mainRepo.toFile().deleteRecursively()
            }
        }
    }

    test("enabled=false overrides a present repo to NoOp; enabled=true forces Git without a repo") {
        withDirs { content, data ->
            initRepo(content)
            resolve(config(content, data, enabled = false)) shouldBeSameInstanceAs NoOpHistoryProvider
        }
        withDirs { content, data ->
            resolve(config(content, data, enabled = true)).shouldBeInstanceOf<GitCliHistoryProvider>()
        }
    }

    // P2-2: `.git` exists + auto-detect, but the git binary is UNAVAILABLE (GitExecutor returns exitCode -1
    // when the process can't start). This must NOT silently drop to NoOp (which would record no history in a
    // real repo and skip the actionable startup gate) — gitEnabled returns true so the gate fails loudly.
    test("a real repo with an unavailable git binary selects Git mode (gate fails loud), never silent NoOp") {
        withDirs { content, data ->
            initRepo(content) // a real .git exists in CONTENT_DIR
            val absentGit = GitExecutor(workTree = content, home = data.resolve("git-home"), gitBinary = "/nonexistent/git")
            gitEnabled(config(content, data, enabled = null), absentGit) shouldBe true
        }
    }

    // P1: `.git` exists but `rev-parse` exits NON-ZERO with a real-repo error (here `fatal: detected dubious
    // ownership`, exit 128 — common under Docker/uid-mismatch). git RAN and failed; this is NOT "not a work
    // tree", so Git mode stays ON (the gate then fails loudly) — never a silent NoOp dropping history.
    test("a real repo where rev-parse fails with dubious ownership (exit 128) stays Git mode, never NoOp") {
        withDirs { content, data ->
            initRepo(content)
            val fakeGit = fakeGitDir("#!/bin/sh\necho 'fatal: detected dubious ownership in repository' >&2\nexit 128\n")
            try {
                val exec = GitExecutor(workTree = content, home = data.resolve("git-home"), gitBinary = fakeGit.toString())
                gitEnabled(config(content, data, enabled = null), exec) shouldBe true
            } finally {
                fakeGit.toFile().delete()
            }
        }
    }

    // P1 (other direction): git ran SUCCESSFULLY and explicitly reported "false" — a definitive not-a-work-tree
    // (bare repo / inside .git). THIS is the only case that drops to NoOp.
    test("a definitive not-a-work-tree (rev-parse ok, stdout 'false') drops to NoOp") {
        withDirs { content, data ->
            initRepo(content) // .git exists so detection proceeds to rev-parse
            val fakeGit = fakeGitDir("#!/bin/sh\necho false\nexit 0\n")
            try {
                val exec = GitExecutor(workTree = content, home = data.resolve("git-home"), gitBinary = fakeGit.toString())
                gitEnabled(config(content, data, enabled = null), exec) shouldBe false
            } finally {
                fakeGit.toFile().delete()
            }
        }
    }
})

/** Writes an executable fake `git` shell script with [script] as its body; the caller deletes it. */
private fun fakeGitDir(script: String): Path {
    val bin = Files.createTempFile("fake-git", ".sh")
    Files.writeString(bin, script)
    Files.setPosixFilePermissions(bin, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    return bin
}

private fun <T> withDirs(block: (content: Path, data: Path) -> T): T {
    val content = Files.createTempDirectory("plainbase-detect-content")
    val data = Files.createTempDirectory("plainbase-detect-data")
    return try {
        block(content, data)
    } finally {
        content.toFile().deleteRecursively()
        data.toFile().deleteRecursively()
    }
}

private fun initRepo(dir: Path) = runGit(dir, "init", "-b", "main", ".")

private fun runGit(dir: Path, vararg args: String) {
    val p = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed in $dir: $out" }
}

// The file's primary export is the `nativeRootGuardFailure` function (the D4 strict guard); it holds no class.
@file:Suppress("ktlint:standard:filename")

package com.plainbase.frameworks.git

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The ADR-0011 D4 strict guard for a root whose repository the operator explicitly CLAIMED (`history = native`):
 * the refusal text naming the FAILED CHECK, or null when [declaredRoot] holds a repository rooted exactly there and
 * owned by nobody else.
 *
 * **Why a guard at all, when main gets by on auto-detection.** Main's AUTO arm is grandfathered and deliberately
 * lax: it accepts a `.git`-as-a-FILE, it may `git init`, and if it guesses wrong the blast radius is the one
 * directory the operator pointed the server at. An EXTRA root is different in kind - the operator is handing
 * Plainbase write access to a repository that exists for other reasons, and every failure mode here ends with
 * Plainbase committing into somebody else's repo. A linked worktree's `.git` FILE points at a shared object store
 * whose refs belong to the main checkout; a submodule's points at the superproject's `.git/modules/...`. In both
 * cases `git -C <root>` succeeds, quietly, against a repository that is not this root's - and the operator finds out
 * when their unrelated branch has Plainbase commits on it. So this fails the BOOT rather than degrading: a server
 * that will not start is a much smaller problem than one that corrupts a repository it was trusted with.
 *
 * The four checks, each independently sufficient, run through [GitExecutor]'s cleared-environment chokepoint (so an
 * inherited `GIT_DIR`/`GIT_WORK_TREE` in the parent env cannot steer the answer):
 *  1. `<root>/.git` is a real DIRECTORY (no-follow) - which rejects a linked worktree AND a submodule in one check,
 *     since both mark themselves with a `.git` FILE;
 *  2. `rev-parse --show-toplevel` equals the root, both sides real-path'd (macOS `/tmp` is a symlink to
 *     `/private/tmp`, so a lexical compare would false-fail every macOS test and some real deployments) - which
 *     rejects a root NESTED inside somebody else's checkout, where git walks up and finds the ancestor;
 *  3. `rev-parse --show-superproject-working-tree` prints EMPTY - a belt against a submodule whose `.git` someone
 *     converted back into a directory;
 *  4. `--git-dir` and `--git-common-dir` resolve to the SAME place - the definitive linked-worktree signature (a
 *     worktree's git-dir is private, its common-dir is the shared one), and a belt behind check 1.
 *
 * A PURE refusal-text function (the `detachedRootsRefusal` idiom): it decides, it does not exit. Public (not
 * `internal`) for the same reason [com.plainbase.frameworks.filesystem.FileAtomics] is - the native-test source set,
 * which is where a guard about REAL `git` subprocesses belongs, is not associated with `main` for internal
 * visibility.
 */
fun nativeRootGuardFailure(exec: GitExecutor, declaredRoot: Path): String? {
    val prefix = "the root at $declaredRoot declares `history = native`, but"

    // (1) A real .git DIRECTORY. NOFOLLOW so a symlinked .git cannot present itself as one.
    if (!Files.isDirectory(declaredRoot.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
        return "$prefix it has no `.git` DIRECTORY of its own (check 1). A linked worktree and a submodule both mark " +
            "themselves with a `.git` FILE pointing at somebody else's repository, and Plainbase will not commit into " +
            "one. Point this root at a real repository, or set `history = off` for it."
    }

    // (2) The repo is rooted EXACTLY here - not at an ancestor this root happens to sit inside.
    val toplevel = exec.run(listOf("rev-parse", "--show-toplevel"))
    if (!toplevel.ok) {
        return "$prefix `git rev-parse --show-toplevel` failed there (check 2): ${toplevel.stderr.ifBlank { "exit ${toplevel.exitCode}" }}"
    }
    val actual = realPathOrNull(Path.of(toplevel.stdoutText.trim()))
    val declared = realPathOrNull(declaredRoot)
    if (actual == null || declared == null || actual != declared) {
        return "$prefix the repository git finds there is rooted at ${toplevel.stdoutText.trim()}, not at the root " +
            "itself (check 2). Plainbase would be committing into a SURROUNDING checkout. Give this root its own " +
            "repository, or set `history = off` for it."
    }

    // (3) Not a submodule (belt behind check 1, for a submodule whose .git was converted back to a directory).
    val superproject = exec.run(listOf("rev-parse", "--show-superproject-working-tree"))
    if (!superproject.ok) {
        return "$prefix `git rev-parse --show-superproject-working-tree` failed there (check 3): " +
            superproject.stderr.ifBlank { "exit ${superproject.exitCode}" }
    }
    if (superproject.stdoutText.isNotBlank()) {
        return "$prefix it is a SUBMODULE of the checkout at ${superproject.stdoutText.trim()} (check 3). Its history " +
            "belongs to the superproject, so Plainbase will not write to it. Set `history = off` for this root."
    }

    // (4) The git-dir IS the common-dir - the definitive linked-worktree signature.
    val dirs = exec.run(listOf("rev-parse", "--git-dir", "--git-common-dir"))
    if (!dirs.ok) {
        return "$prefix `git rev-parse --git-dir --git-common-dir` failed there (check 4): ${dirs.stderr.ifBlank {
            "exit ${dirs.exitCode}"
        }}"
    }
    // Both are printed relative to the WORK TREE when they are relative, so resolve against it before comparing.
    val (gitDir, commonDir) = dirs.stdoutText.lines().filter { it.isNotBlank() }.let { lines ->
        if (lines.size < 2) return "$prefix `git rev-parse --git-dir --git-common-dir` printed ${lines.size} line(s), not 2 (check 4)"
        realPathOrNull(declaredRoot.resolve(lines[0].trim())) to realPathOrNull(declaredRoot.resolve(lines[1].trim()))
    }
    if (gitDir == null || commonDir == null || gitDir != commonDir) {
        return "$prefix its git directory ($gitDir) is not its own - it shares a common directory ($commonDir) with " +
            "another checkout, which is what a LINKED WORKTREE looks like (check 4). Plainbase will not commit into a " +
            "repository whose refs another worktree owns. Set `history = off` for this root."
    }
    return null
}

/** [path]'s real path, or null when it cannot be resolved — a resolution failure is a guard FAILURE (fail closed). */
private fun realPathOrNull(path: Path): Path? =
    try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }

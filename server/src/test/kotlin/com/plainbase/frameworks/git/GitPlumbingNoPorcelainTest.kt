package com.plainbase.frameworks.git

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * C5's single-chokepoint guarantee, a source-scan regression guard (the [com.plainbase.objectModeGitDisabledWarning]
 * "no snapshot/manifest writer" test's shape): [GitPlumbing], [GitBundleDr], and [GitCliHistoryProvider]
 * must NEVER invoke porcelain `git add` or `git commit` - only the ADR-0006 plumbing recipe
 * (`hash-object`/`update-index`/`write-tree`/`commit-tree`/`update-ref`). This is what makes the
 * ADR-0006 Amendment-2 byte-fidelity golden (native, `GitBundleDrNativeTest`) a guarantee about the
 * CLASS, not one exercised call path - a future edit that slips in a porcelain call anywhere in any of
 * the three files fails this test immediately. [GitCliHistoryProvider] is in scope (cursor-auto review
 * fold): it invokes git directly too (`syncLiveIndex`, `ensureRepo`, the read paths), so a porcelain slip
 * there would otherwise go uncaught by this guard.
 */
class GitPlumbingNoPorcelainTest : FunSpec({

    test("GitPlumbing.kt, GitBundleDr.kt, and GitCliHistoryProvider.kt never invoke porcelain `git add` or `git commit`") {
        val gitPackageRoot = mainSourceRoot().resolve("com/plainbase/frameworks/git")
        val files = listOf("GitPlumbing.kt", "GitBundleDr.kt", "GitCliHistoryProvider.kt").map { gitPackageRoot.resolve(it) }
        require(files.all { it.isRegularFile() }) { "expected files not found under $gitPackageRoot: $files" }

        val violations = files.flatMap { file ->
            file.readText().lineSequence()
                .filter { PORCELAIN_INVOCATION.containsMatchIn(it) }
                .map { "${file.fileName}: ${it.trim()}" }
        }
        violations.shouldBeEmpty()
    }
})

/** The start of an argv list naming the porcelain subcommand `add` or `commit` (never `--add`/`commit-tree`/`commit-`). */
private val PORCELAIN_INVOCATION = Regex("""listOf\(\s*"(add|commit)"""")

/** Locates `server/src/main/kotlin` (the [com.plainbase.DomainPurityTest] pattern). */
private fun mainSourceRoot(): java.nio.file.Path {
    var dir: java.nio.file.Path? = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        for (candidate in listOf("src/main/kotlin", "server/src/main/kotlin")) {
            val resolved = dir.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        dir = dir.parent
    }
    error("Could not locate the main source tree from ${System.getProperty("user.dir")}")
}

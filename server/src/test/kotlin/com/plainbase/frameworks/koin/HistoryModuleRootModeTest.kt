package com.plainbase.frameworks.koin

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.domain.service.commit
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.git.GitCliHistoryProvider
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path

/**
 * MAIN's `history` knob is ENFORCED, exactly like an extra's (ADR-0011 D4).
 *
 * The bug this pins: main used to get the legacy detect-or-override provider UNCONDITIONALLY, whatever its `history`
 * said. So `history = off` still COMMITTED (the legacy path commits whenever a repo is detected), and - the sharp end
 * - `history = native` SKIPPED the strict four-check guard and could `git init` a repository Plainbase does not own.
 * Main is where an operator is MOST likely to be pointing Plainbase at somebody else's checkout, so it is the last
 * root that should have been exempt from the refusal.
 *
 * The guard's four checks are exercised against real `git` in `GitNativeRootGuardTest` (native-tagged: process
 * execution). What is proven HERE is the WIRING - that main's provider is the guarded one at all.
 */
class HistoryModuleRootModeTest : FunSpec({

    test("main with `history = off` records NOTHING - even sitting in a real repository the legacy path would commit into") {
        withMainRepo { content, dataDir ->
            withKoin(content, dataDir, HistoryMode.OFF) { koin ->
                val provider = koin.get<HistoryProvider>()
                provider shouldBe NoOpHistoryProvider
                withClue("the per-root map must agree with the single - main resolves THROUGH it") {
                    koin.get<HistoryProviders>().primary shouldBe NoOpHistoryProvider
                }

                val sha = koin.get<WriteHistoryHook>().commit(RootName.PRIMARY, TreePath.require("a.md"), "# A edited\n".toByteArray())

                withClue("`off` means off: a null SHA, and the operator's repository is left exactly as they left it") {
                    sha shouldBe null
                    commitCount(content, dataDir) shouldBe 1
                }
            }
        }
    }

    test("main with `history = native` over a FOREIGN repo REFUSES BOOT - the strict guard is not skippable for main") {
        withMainRepo { content, dataDir ->
            // Main is a plain subdirectory INSIDE somebody else's checkout: `git -C` walks up and finds the ancestor,
            // so an unguarded provider would happily commit our pages onto their branch (guard check 1).
            val nested = content.resolve("docs")
            Files.createDirectories(nested)

            withKoin(nested, dataDir, HistoryMode.NATIVE) { koin ->
                val provider = koin.get<HistoryProvider>()
                provider.shouldBeInstanceOf<GitCliHistoryProvider>()

                // serve()'s gate loop calls exactly this, and turns a throw into an actionable `serve:` refusal.
                shouldThrowAny { provider.gateCheck() }

                withClue("a CLAIMED root is never initialized: Plainbase does not create a repository it does not own") {
                    Files.exists(nested.resolve(".git")).shouldBeFalse()
                }
            }
        }
    }

    test("main with `history = auto` keeps the legacy detect-or-override behavior (the synthesized-config back-compat pin)") {
        withMainRepo { content, dataDir ->
            withKoin(content, dataDir, HistoryMode.AUTO) { koin ->
                val provider = koin.get<HistoryProvider>()
                provider.shouldBeInstanceOf<GitCliHistoryProvider>()
                provider.enabled shouldBe true
                provider.gateCheck() // an owned repo at main's root - the AUTO arm passes, as it always has
            }
        }
    }
})

/** A temp content root holding a REAL one-commit git repository, plus a temp DATA_DIR. Always cleaned up. */
private fun withMainRepo(block: (content: Path, dataDir: Path) -> Unit) {
    val content = Files.createTempDirectory("pb-history-mode")
    val dataDir = Files.createTempDirectory("pb-history-mode-data")
    try {
        val exec = GitExecutor(workTree = content, home = dataDir.resolve("git-home"))
        Files.createDirectories(dataDir.resolve("git-home"))
        exec.run(listOf("init"))
        Files.writeString(content.resolve("a.md"), "---\ntitle: A\n---\n\n# A\n")
        exec.run(listOf("add", "a.md"))
        exec.run(listOf("-c", "user.name=T", "-c", "user.email=t@e", "commit", "-m", "seed"))
        block(content, dataDir)
    } finally {
        listOf(content, dataDir).forEach { it.toFile().deleteRecursively() }
    }
}

/** The commits on HEAD of the repo at [content] — the "did anything get written into it?" oracle. */
private fun commitCount(content: Path, dataDir: Path): Int {
    val result = GitExecutor(workTree = content, home = dataDir.resolve("git-home")).run(listOf("rev-list", "--count", "HEAD"))
    return result.stdoutText.trim().toInt()
}

/**
 * Resolves the PRODUCTION module set over a config whose `roots {}` declares main at [content] with [history] — the
 * whole point being that the selection under test is the WIRING's, not a hand-built provider's.
 */
private fun withKoin(content: Path, dataDir: Path, history: HistoryMode, block: (Koin) -> Unit) {
    val config = module {
        single {
            PlainbaseConfig.fromEnv(emptyMap()).copy(
                contentDir = content,
                dataDir = dataDir,
                roots = RootsConfig.of(
                    list = listOf(Root(RootName.PRIMARY, RootBackend.Local(content), editable = true, history = history)),
                    origin = RootsOrigin.EXPLICIT,
                ),
            )
        }
    }
    val app = koinApplication { modules(config, contentModule, repositoryModule, securityModule, historyModule) }
    try {
        block(app.koin)
    } finally {
        app.close()
    }
}

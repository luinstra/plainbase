package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.frameworks.objectstore.HybridFixture
import com.plainbase.frameworks.objectstore.ObjectStoreException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * C5's `GitBundleDr` acceptance suite (JVM, real `git` subprocess via [GitExecutor] + the C4 fake
 * [com.plainbase.frameworks.objectstore.FakeObjectStore] bucket - no network, no mocking of
 * [GitExecutor] itself, which is a concrete hermetic chokepoint, not an interface):
 *
 *  - the FORK-2 2b gate/sentinel truth table (warm restart, crash-before-reconcile, stale-sentinel-clear,
 *    a real bundle restore),
 *  - HOLE C (a non-404 bundle GET aborts the restore, no repo init'd) - confirm-review fold: the abort now
 *    surfaces via the SAME [com.plainbase.frameworks.objectstore.ObjectContentStore.bootRefusal] R16
 *    classification the object hydrate/LIST self-check uses, never a raw connect/TLS exception,
 *  - HOLE B (concurrent ships, and a close()-flush racing them, never corrupt the shipped bundle),
 *  - the ship-vs-commit exclusion (concurrent `commit()`/`ship()` sharing one [GitRepoLocks] never
 *    corrupts the repo - proven by a real `git fsck` at the end, not an instrumented mock).
 */
class GitBundleDrShipTest : FunSpec({

    test("2b truth table: complete .git + no sentinel => NOT_RESTORED (warm restart), no GET issued") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                providerFor(exec, hybrid, gitHomeDir).commit(TreePath.require("a.md"), "v1".toByteArray())
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                val restored = bundleDr.restore()

                restored shouldBe GitBundleDr.Restored.NOT_RESTORED
                hybrid.fake.getCount shouldBe 0
            }
        }
    }

    test("2b truth table: complete .git + sentinel present => RESTORED(tip=HEAD), no GET (reconcile merely owed)") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                providerFor(exec, hybrid, gitHomeDir).commit(TreePath.require("a.md"), "v1".toByteArray())
                val head = requireNotNull(GitExecutor.parseSha(exec.run(listOf("rev-parse", "HEAD")).stdout))
                Files.createDirectories(sentinelPath.parent)
                Files.createFile(sentinelPath)
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                val restored = bundleDr.restore()

                restored.isRestored shouldBe true
                restored.tip shouldBe head
                hybrid.fake.getCount shouldBe 0
            }
        }
    }

    test("2b truth table: incomplete/absent .git + a 404 bucket clears a STALE sentinel and returns NOT_RESTORED") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                Files.createDirectories(sentinelPath.parent)
                Files.createFile(sentinelPath) // a stale sentinel from an earlier abandoned restore attempt
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                val restored = bundleDr.restore()

                restored shouldBe GitBundleDr.Restored.NOT_RESTORED
                Files.exists(sentinelPath) shouldBe false // MUST-BIND 5: never loops restoreOwed forever
            }
        }
    }

    test("2b truth table: incomplete/absent .git + a present bundle performs init -> fetch -> retarget -> reset; tmp bundle cleaned up") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val sourceRoot = Files.createTempDirectory("plainbase-bundledr-source")
                val sourceHome = Files.createTempDirectory("plainbase-bundledr-source-home")
                try {
                    val sourceExec = GitExecutor(workTree = sourceRoot, home = sourceHome)
                    val sourceProvider = providerFor(sourceExec, hybrid, sourceHome, repoPath = { it.value })
                    sourceProvider.commit(TreePath.require("history.md"), "from the bundle".toByteArray())
                    val sourceBundle = sourceRoot.resolve("src.bundle")
                    sourceExec.run(listOf("bundle", "create", sourceBundle.toString(), "--all")).ok shouldBe true
                    hybrid.store.putHistoryBundle(Files.readAllBytes(sourceBundle))

                    val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                    val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                    val restored = bundleDr.restore()

                    restored.isRestored shouldBe true
                    restored.tip.shouldNotBeNull()
                    Files.exists(tmpDir.resolve("history.bundle")) shouldBe false // MUST-BIND 6
                    Files.exists(sentinelPath) shouldBe true // cleared only by reconcileBootCommit
                    exec.run(listOf("rev-parse", "--verify", "HEAD^{commit}")).ok shouldBe true
                } finally {
                    sourceRoot.toFile().deleteRecursively()
                    sourceHome.toFile().deleteRecursively()
                }
            }
        }
    }

    test(
        "HOLE C / R16 (confirm fix): a non-404 bundle GET connect-refusal aborts the restore with the SAME " +
            "operator-actionable refusal object hydrate/LIST uses, not a raw exception; no repo is init'd",
    ) {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                hybrid.fake.connectRefusal = true
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                val failure = shouldThrow<ObjectStoreException> { bundleDr.restore() }

                failure.message shouldContain "unreachable" // the SAME classification ObjectOutageTest asserts for hydrate()
                Files.exists(hybrid.mirrorRoot.resolve(".git")) shouldBe false // HOLE C intact: still fails the boot
            }
        }
    }

    test(
        "HOLE C / R16 (confirm fix): a non-404 bundle GET TLS/signature rejection aborts the restore naming the " +
            "TLS cause specifically, never disable certificate validation; no repo is init'd",
    ) {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                hybrid.fake.tlsRejection = true
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                val failure = shouldThrow<ObjectStoreException> { bundleDr.restore() }

                failure.message shouldContain "TLS"
                failure.message shouldContain "never disable certificate validation"
                Files.exists(hybrid.mirrorRoot.resolve(".git")) shouldBe false // HOLE C intact: still fails the boot
            }
        }
    }

    test("HOLE B: concurrent ships, and a close()-flush racing them, never corrupt the shipped bundle") {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                val provider = providerFor(exec, hybrid, gitHomeDir)
                repeat(5) { i -> provider.commit(TreePath.require("f$i.md"), "v$i".toByteArray()) }
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                val shipThreads = (1..8).map { Thread { runCatching { bundleDr.ship() } } }
                val closeThread = Thread { runCatching { bundleDr.close() } }
                shipThreads.forEach(Thread::start)
                closeThread.start()
                shipThreads.forEach { it.join(10_000) }
                closeThread.join(10_000)

                val shipped = requireNotNull(hybrid.store.getHistoryBundle())
                val verifyTarget = tmpDir.resolve("verify.bundle")
                Files.write(verifyTarget, shipped)
                exec.run(listOf("bundle", "verify", verifyTarget.toString())).ok shouldBe true
            }
        }
    }

    test(
        "ship-vs-commit exclusion: concurrent commits and ships sharing one GitRepoLocks never corrupt the repo (real git fsck, not a mock)",
    ) {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                val locks = GitRepoLocks()
                val provider = providerFor(exec, hybrid, gitHomeDir, repoWriteMonitor = locks.repoWrite)
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir, locks)
                provider.commit(TreePath.require("seed.md"), "seed".toByteArray()) // an unborn repo can't bundle create

                val commitThread = Thread {
                    repeat(20) { i -> runCatching { provider.commit(TreePath.require("race$i.md"), "v$i".toByteArray()) } }
                }
                val shipThreads = (1..4).map { Thread { repeat(5) { runCatching { bundleDr.ship() } } } }
                commitThread.start()
                shipThreads.forEach(Thread::start)
                commitThread.join(20_000)
                shipThreads.forEach { it.join(20_000) }

                exec.run(listOf("fsck", "--no-progress")).ok shouldBe true
            }
        }
    }

    test(
        "HOLE A / BLOCKING #4: reconcile force-removes a tip file absent from authority (incl. a leading-dash " +
            "hostile name), and BLOCKING #3: the live index matches the new tip afterward",
    ) {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val sourceRoot = Files.createTempDirectory("plainbase-bundledr-source-holea")
                val sourceHome = Files.createTempDirectory("plainbase-bundledr-source-holea-home")
                try {
                    // The bundle's tip has TWO files, one with a HOSTILE leading-dash name - the
                    // argument-injection surface `update-index --force-remove` must not misread as a flag.
                    val sourceExec = GitExecutor(workTree = sourceRoot, home = sourceHome)
                    val sourceProvider = providerFor(sourceExec, hybrid, sourceHome, repoPath = { it.value })
                    sourceProvider.commit(TreePath.require("keep.md"), "keep me".toByteArray())
                    sourceProvider.commit(TreePath.require("--stdin.md"), "remove me (hostile leading-dash name)".toByteArray())
                    val sourceBundle = sourceRoot.resolve("src.bundle")
                    sourceExec.run(listOf("bundle", "create", sourceBundle.toString(), "--all")).ok shouldBe true
                    hybrid.store.putHistoryBundle(Files.readAllBytes(sourceBundle))

                    // The authority (bucket + mirror) has ONLY "keep.md" - "--stdin.md" is absent, so the
                    // reconcile must force-remove it from the recovered tip.
                    hybrid.seedExisting(TreePath.require("keep.md"), "keep me".toByteArray())

                    val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                    val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                    val restored = bundleDr.restore()
                    restored.isRestored shouldBe true

                    hybrid.store.hydrate(strict = true)
                    bundleDr.reconcileBootCommit(restored)

                    // The hostile-named file is gone from the recovered tree (proves the `--` fix: without
                    // it, `update-index --force-remove --stdin.md` silently misreads as the `--stdin` flag
                    // and never actually removes the entry).
                    val tipFiles = exec.run(listOf("ls-tree", "-r", "--name-only", "-z", "HEAD")).stdoutText
                        .split(Char(0)).filter { it.isNotEmpty() }
                    tipFiles shouldBe listOf("keep.md")

                    // BLOCKING #3: the live index was resynced after the reconcile's ref mutation, so
                    // `git status` shows no phantom differences.
                    exec.run(listOf("status", "--porcelain")).stdoutText.trim() shouldBe ""
                } finally {
                    sourceRoot.toFile().deleteRecursively()
                    sourceHome.toFile().deleteRecursively()
                }
            }
        }
    }

    test(
        "husk fix: an UNBORN/EMPTY .git (freshly init'd by prepare(), nothing ever committed, bundle 404) " +
            "is DELETED outright, not renamed aside",
    ) {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                // Exactly what GitCliHistoryProvider.prepare()'s ensureRepo() does on every boot lacking a
                // `.git` - a crash-looping, never-committed container hits this every restart.
                exec.run(listOf("init")).ok shouldBe true
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                val restored = bundleDr.restore()

                restored shouldBe GitBundleDr.Restored.NOT_RESTORED
                Files.exists(hybrid.mirrorRoot.resolve(".git")) shouldBe false
                huskDirs(hybrid.mirrorRoot) shouldBe emptyList() // deleted outright - no unbounded husk left behind
            }
        }
    }

    test(
        "husk fix: a .git carrying a real ref/commit (crash-mid-fetch after ONE branch landed, HEAD still " +
            "unborn) still takes the preserve/rename-aside path",
    ) {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                exec.run(listOf("init")).ok shouldBe true
                val emptyTree = requireNotNull(GitExecutor.parseSha(exec.run(listOf("write-tree")).stdout))
                val identityEnv = GitPlumbing.identityEnv(testIdentity(), testIdentity(), fixedClock().now())
                val commitResult = exec.run(listOf("commit-tree", emptyTree, "-m", "partial fetch landed this branch"), identityEnv)
                val commitSha = requireNotNull(GitExecutor.parseSha(commitResult.stdout))
                // A ref lands on a branch OTHER than the unborn default (`main`) - exactly what a fetch of
                // `refs/heads/*:refs/heads/*` crashing after one branch update, but before HEAD's
                // symbolic-ref retarget, would leave behind: HEAD still points at the still-unborn `main`.
                exec.run(listOf("update-ref", "refs/heads/other", commitSha)).ok shouldBe true
                val bundleDr = bundleDrFor(exec, hybrid, tmpDir, sentinelPath, gitHomeDir)

                val restored = bundleDr.restore()

                restored shouldBe GitBundleDr.Restored.NOT_RESTORED
                Files.exists(hybrid.mirrorRoot.resolve(".git")) shouldBe false // moved, not left in place
                huskDirs(hybrid.mirrorRoot).size shouldBe 1 // preserved: a real commit existed on refs/heads/other
            }
        }
    }

    test(
        "cadence: recordCommit ships on the FIRST commit, defers 2..19, ships at the 20-commit threshold " +
            "(resetting the pending alarm), and the 300s debounce alarm path never throws",
    ) {
        HybridFixture().use { hybrid ->
            withHarness { gitHomeDir, tmpDir, sentinelPath ->
                val exec = GitExecutor(workTree = hybrid.mirrorRoot, home = gitHomeDir)
                var armedDelay: Long? = null
                var armedAction: (() -> Unit)? = null
                val fakeAlarm = RebuildScheduler.Alarm { delayMillis, action ->
                    armedDelay = delayMillis
                    armedAction = action
                }
                val bundleDr = GitBundleDr(
                    exec = exec,
                    objectStore = hybrid.store,
                    mirrorRoot = hybrid.mirrorRoot,
                    tmpDir = tmpDir,
                    sentinelPath = sentinelPath,
                    identity = testIdentity(),
                    clock = fixedClock(),
                    repoPath = { path -> hybrid.mirror.resolveRepoRelativePath(path) },
                    gitHome = gitHomeDir,
                    locks = GitRepoLocks(),
                    alarm = fakeAlarm,
                )
                val provider = providerFor(exec, hybrid, gitHomeDir)
                provider.commit(TreePath.require("seed.md"), "seed".toByteArray()) // an unborn repo can't bundle create

                // The FIRST-EVER commit of this process ships immediately - no alarm armed.
                bundleDr.recordCommit() shouldBe true
                armedAction.shouldBeNull()
                bundleDr.shipBestEffort() // marks everShipped = true, as the real per-save wiring would

                // Commits 2..19 defer, arming exactly ONE debounce alarm (never re-armed while pending).
                repeat(18) { bundleDr.recordCommit() shouldBe false }
                armedAction.shouldNotBeNull()
                armedDelay shouldBe GitBundleDr.SHIP_MAX_LATENCY_MILLIS
                val firstArmedAction = armedAction
                bundleDr.recordCommit() shouldBe false // the 19th deferred commit: still armed, no re-arm
                armedAction shouldBe firstArmedAction

                // The 20th commit crosses SHIP_COMMIT_THRESHOLD - ships NOW regardless of the pending alarm.
                bundleDr.recordCommit() shouldBe true
                bundleDr.shipBestEffort()

                // The 300s debounce alarm firing later (a redundant, harmless extra ship - MINOR: no
                // cancel primitive exists) must not throw.
                firstArmedAction?.invoke()
            }
        }
    }
})

private fun providerFor(
    exec: GitExecutor,
    hybrid: HybridFixture,
    gitHomeDir: Path,
    repoPath: (TreePath) -> String = { path -> hybrid.mirror.resolveRepoRelativePath(path) },
    repoWriteMonitor: Any? = null,
): GitCliHistoryProvider = GitCliHistoryProvider(
    exec = exec,
    workTree = hybrid.mirrorRoot,
    gitHome = gitHomeDir,
    defaultAuthor = testIdentity(),
    defaultCommitter = testIdentity(),
    clock = fixedClock(),
    repoPath = repoPath,
    maintenance = {},
    repoWriteMonitor = repoWriteMonitor,
)

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

/** The `.git.pre-restore-<epoch-millis>-<uuid>` husk dirs (if any) directly under [mirrorRoot]. */
private fun huskDirs(mirrorRoot: Path): List<Path> =
    Files.list(mirrorRoot).use { it.toList() }.filter { it.fileName.toString().startsWith(".git.pre-restore-") }

/** A fresh git-home + tmp dir + sentinel-path trio for one test, always cleaned up. */
private fun <T> withHarness(block: (gitHomeDir: Path, tmpDir: Path, sentinelPath: Path) -> T): T {
    val dataDir = Files.createTempDirectory("plainbase-bundledr-data")
    return try {
        block(dataDir.resolve("git-home"), dataDir.resolve("tmp"), dataDir.resolve("restore-pending"))
    } finally {
        dataDir.toFile().deleteRecursively()
    }
}

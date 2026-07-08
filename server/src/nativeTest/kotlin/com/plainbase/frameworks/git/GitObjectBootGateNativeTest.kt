package com.plainbase.frameworks.git

import com.plainbase.domain.history.CommitIdentity
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * C5 Cluster-1 (native, process-exec divergence surface): the object-mode pre-lock `gateCheck()` must
 * PASS even when the mirror directory (`DATA_DIR/mirror`) does not exist yet - `Application.kt`'s
 * `serve()` calls it BEFORE the lock and before hydrate's mkdir. Before the fix, `GitExecutor.run`
 * prepended `-C <missing-mirror>` to EVERY call (including the `--version` probe), so git errored
 * `cannot change to '<dir>'` and misreported the binary itself as missing; [GitExecutor.versionProbe]
 * drops `-C` for exactly this one call.
 */
@Tag("native")
class GitObjectBootGateNativeTest {

    @Test
    fun `gateCheck() passes over a missing object-mode mirror directory`() {
        val missingMirror = Files.createTempDirectory("plainbase-gate-native").resolve("mirror")
        val home = Files.createTempDirectory("plainbase-gate-native-home")
        try {
            assertTrue(Files.notExists(missingMirror), "the mirror directory must NOT exist for this test to prove anything")
            val exec = GitExecutor(workTree = missingMirror, home = home)
            val fixedClock = object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(1_780_272_000L)
            }
            val provider = GitCliHistoryProvider(
                exec = exec,
                workTree = missingMirror,
                gitHome = home,
                defaultAuthor = CommitIdentity("Plainbase", "plainbase@localhost"),
                defaultCommitter = CommitIdentity("Plainbase", "plainbase@localhost"),
                clock = fixedClock,
                maintenance = {},
                objectMode = true, // the real object-mode wiring this test models (`historyModule`)
            )

            provider.gateCheck() // must not throw - this is the whole assertion
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    /**
     * BLOCKING #1 (review fold): a PRESENT-but-INCOMPLETE mirror `.git` (a killed mid-init/mid-restore,
     * or corruption) must ALSO pass the pre-lock gate in object mode - `rev-parse --is-inside-work-tree`
     * fails loud on an incomplete `.git` (empirically verified: exit 128, "fatal: not a git repository"),
     * so running that probe pre-lock would hard-fail the gate BEFORE `GitBundleDr.restore()` (which runs
     * POST-lock) ever gets the chance to delete/rebuild it - defeating the self-heal path entirely. LOCAL
     * mode (`objectMode = false`) is asserted to keep the OLD fail-loud behavior, unchanged.
     */
    @Test
    fun `gateCheck() passes over a present-but-incomplete object-mode mirror dot-git (self-heal is restore()'s job, not the gate's)`() {
        val mirror = Files.createTempDirectory("plainbase-gate-native-incomplete")
        val home = Files.createTempDirectory("plainbase-gate-native-incomplete-home")
        try {
            // An empty `.git` directory - not a valid repo (no HEAD/objects/refs) - models a killed
            // mid-`git init` or mid-restore crash.
            Files.createDirectories(mirror.resolve(".git"))
            val exec = GitExecutor(workTree = mirror, home = home)
            val fixedClock = object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(1_780_272_000L)
            }
            assertTrue(
                exec.run(listOf("rev-parse", "--is-inside-work-tree")).exitCode != 0,
                "the empty .git must actually be invalid for this test to prove anything",
            )

            val objectModeProvider = GitCliHistoryProvider(
                exec = exec,
                workTree = mirror,
                gitHome = home,
                defaultAuthor = CommitIdentity("Plainbase", "plainbase@localhost"),
                defaultCommitter = CommitIdentity("Plainbase", "plainbase@localhost"),
                clock = fixedClock,
                maintenance = {},
                objectMode = true,
            )
            objectModeProvider.gateCheck() // must not throw - the access probe is skipped entirely

            val localModeProvider = GitCliHistoryProvider(
                exec = exec,
                workTree = mirror,
                gitHome = home,
                defaultAuthor = CommitIdentity("Plainbase", "plainbase@localhost"),
                defaultCommitter = CommitIdentity("Plainbase", "plainbase@localhost"),
                clock = fixedClock,
                maintenance = {},
            )
            var threw = false
            try {
                localModeProvider.gateCheck()
            } catch (e: GitUnavailableException) {
                threw = true
            }
            assertTrue(threw, "LOCAL mode must keep failing loud on an inaccessible/incomplete .git - unchanged behavior")
        } finally {
            mirror.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }
}

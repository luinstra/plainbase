package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The W5 native gate (D-8): the NEW read plumbing (`log`/`diff` — the only new server code that shells out
 * to `git`) round-trips inside the native image, NOT a hollow check. Two versions of a page committed
 * through the production [GitCliHistoryProvider], then `log(path)` must return 2 commits newest-first and
 * `diff(older, newer, path)` a non-empty unified diff. kotlin.test + @Tag("native"), mirroring
 * [GitNativeSmokeTest]; no JGit (test-only oracle), the reads go through the same hermetic executor.
 */
@Tag("native")
class GitHistoryReadNativeSmokeTest {

    @Test
    fun `log and diff round-trip through GitCliHistoryProvider in the native image`() {
        val root = Files.createTempDirectory("plainbase-git-read-native")
        val home = Files.createTempDirectory("plainbase-git-read-native-home")
        try {
            val exec = GitExecutor(workTree = root, home = home)
            val fixedClock = object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(1_780_272_000L)
            }
            val provider = GitCliHistoryProvider(
                exec = exec,
                workTree = root,
                gitHome = home,
                defaultAuthor = CommitIdentity("Plainbase", "plainbase@localhost"),
                defaultCommitter = CommitIdentity("Plainbase", "plainbase@localhost"),
                clock = fixedClock,
                maintenance = {},
            )

            val path = TreePath.require("docs/page.md")
            provider.commit(path, "first line\n".toByteArray())
            provider.commit(path, "second line\n".toByteArray())

            val log = provider.log(path)
            assertEquals(2, log.size, "expected two commits in the log")
            val newer = log[0].sha
            val older = log[1].sha
            assertEquals(exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim(), newer, "newest-first ordering")

            val diff = provider.diff(older, newer, path)
            assertTrue(diff.unifiedDiff.contains("-first line"), "diff must show the removed line")
            assertTrue(diff.unifiedDiff.contains("+second line"), "diff must show the added line")
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }
}

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
 * The W4 native gate (F8): a REAL stdin + temp-index + root-commit round-trip through the production
 * [GitCliHistoryProvider] against a temp repo — proving the new plumbing (the only new server code that
 * shells out to `git`) works inside the native image, NOT a hollow `git --version`. kotlin.test +
 * @Tag("native"), so it runs identically on the JVM and in the native image. No JGit (test-only oracle);
 * the commit is read back through the same hermetic executor.
 */
@Tag("native")
class GitNativeSmokeTest {

    @Test
    fun `a real commit round-trips through GitCliHistoryProvider in the native image`() {
        val root = Files.createTempDirectory("plainbase-git-native")
        val home = Files.createTempDirectory("plainbase-git-native-home")
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
                maintenance = {}, // no background GC in the smoke
            )

            val path = TreePath.require("docs/page.md")
            val bytes = "native round-trip\n".toByteArray()
            val commit = provider.commit(path, bytes) // unborn HEAD → root commit via read-tree --empty, no -p

            // One commit reachable from HEAD, and it is the one we created.
            val count = exec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim()
            assertEquals("1", count, "expected exactly one commit")
            assertEquals(commit.sha, exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim())
            assertEquals("Plainbase", commit.author.name)

            // Byte-fidelity: the committed blob equals the hook bytes (filter-free stdin staging).
            val committed = exec.run(listOf("show", "HEAD:docs/page.md")).stdout
            assertTrue(committed.contentEquals(bytes), "committed blob must equal the hook bytes")
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }
}

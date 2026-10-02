package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.CommitOutcome
import com.plainbase.domain.history.HistoryChange
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@Tag("native")
class DiscussionGitCommitNativeTest {
    @Test
    fun discussionCommitWithPutAndDeleteTouchesOnlyItsPaths() {
        withGitNative { root, home ->
            val exec = GitExecutor(root, home)
            val provider = nativeProvider(exec, root, home)
            val keep = TreePath.require("docs/page.md")
            val deleted = TreePath.require(".plainbase/discussions/01900000-0000-7000-8000-000000000001/old.md")
            val added = TreePath.require(
                ".plainbase/discussions/01900000-0000-7000-8000-000000000001/01900000-0000-7000-8000-000000000002.md",
            )
            provider.commit(keep, "keep\n".encodeToByteArray())
            provider.commit(deleted, "old\n".encodeToByteArray())

            val result = provider.commitChanges(
                listOf(
                    HistoryChange.Put(added, "new discussion file\n".encodeToByteArray()),
                    HistoryChange.Delete(deleted),
                ),
                "discussion: purge 01900000-0000-7000-8000-000000000001",
                IDENTITY,
                IDENTITY,
            )
            assertTrue(result is CommitOutcome.Committed, "expected committed, got $result")
            val changes = exec.run(listOf("diff-tree", "--no-commit-id", "--name-status", "-r", "HEAD"))
                .stdoutText.trim().lines().sorted()
            assertEquals(
                listOf("A\t${added.value}", "D\t${deleted.value}"),
                changes,
            )
            assertEquals("keep\n", exec.run(listOf("show", "HEAD:${keep.value}")).stdoutText)
            assertEquals("new discussion file\n", exec.run(listOf("show", "HEAD:${added.value}")).stdoutText)
        }
    }

    @Test
    fun hydrationFailureAfterUpdateRefIsCommitted() {
        withGitNative { root, home ->
            val shim =
                installNativeShim(
                    home,
                    """
                if [ "${'$'}sub" = "show" ]; then
                    echo "hydration unavailable" 1>&2
                    exit 1
                fi
                exec git "${'$'}@"
            """.trimIndent(),
                )
            try {
                val baseExec = GitExecutor(root, home)
                val exec = GitExecutor(root, home, gitBinary = shim.toString())
                val provider = nativeProvider(exec, root, home)
                val path = TreePath.require(".plainbase/discussions/01900000-0000-7000-8000-000000000001/discussion.md")
                val result = provider.commitChanges(
                    listOf(HistoryChange.Put(path, "marker\n".encodeToByteArray())),
                    "discussion: start 01900000-0000-7000-8000-000000000001",
                    IDENTITY,
                    IDENTITY,
                )
                assertTrue(result is CommitOutcome.Committed, "hydration failure must keep the committed outcome")
                assertEquals("marker\n", baseExec.run(listOf("show", "HEAD:${path.value}")).stdoutText)
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    @Test
    fun refusedUpdateRefIsNotCommitted() {
        withGitNative { root, home ->
            val baseExec = GitExecutor(root, home)
            val baseProvider = nativeProvider(baseExec, root, home)
            val keep = TreePath.require("docs/page.md")
            baseProvider.commit(keep, "base\n".encodeToByteArray())
            val oldHead = baseExec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val shim =
                installNativeShim(
                    home,
                    """
                if [ "${'$'}sub" = "update-ref" ]; then
                    echo "refused" 1>&2
                    exit 1
                fi
                exec git "${'$'}@"
            """.trimIndent(),
                )
            try {
                val exec = GitExecutor(root, home, gitBinary = shim.toString())
                val provider = nativeProvider(exec, root, home)
                val added = TreePath.require(".plainbase/discussions/01900000-0000-7000-8000-000000000001/discussion.md")
                val result = provider.commitChanges(
                    listOf(HistoryChange.Put(added, "marker\n".encodeToByteArray())),
                    "discussion: start 01900000-0000-7000-8000-000000000001",
                    IDENTITY,
                    IDENTITY,
                )
                assertTrue(result is CommitOutcome.NotCommitted, "expected a refused commit, got $result")
                assertEquals(oldHead, baseExec.run(listOf("rev-parse", "HEAD")).stdoutText.trim())
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    @Test
    fun headBlobsListsCommittedFilesAndVerifiesAnUnbornHead() {
        withGitNative { root, home ->
            val exec = GitExecutor(root, home)
            val provider = nativeProvider(exec, root, home)
            provider.prepare()
            val directory = TreePath.require(".plainbase/discussions/01900000-0000-7000-8000-000000000021")
            assertEquals(emptyMap(), provider.headBlobs(listOf(directory)))

            val path = directory.resolveChild("discussion.md")
            provider.commit(path, "native boot marker\n".encodeToByteArray())

            val blobs = provider.headBlobs(listOf(directory))
            assertEquals(1, blobs?.size)
            assertTrue(blobs?.containsKey(path) == true)
        }
    }

    @Test
    fun blobIdEqualsHashObjectForSha1AndSha256Repositories() {
        val bytes = "native blob identity\n".encodeToByteArray()
        withGitNative { root, home ->
            val exec = GitExecutor(root, home)
            val provider = nativeProvider(exec, root, home)
            provider.prepare()
            val expected = exec.run(listOf("hash-object", "--stdin"), stdin = bytes).stdoutText.trim()
            assertEquals(expected, provider.blobId(bytes))
        }
        withGitNative { root, home ->
            val exec = GitExecutor(root, home)
            assertTrue(exec.run(listOf("init", "--object-format=sha256")).ok)
            val provider = nativeProvider(exec, root, home)
            val expected = exec.run(listOf("hash-object", "--stdin"), stdin = bytes).stdoutText.trim()
            assertEquals(expected, provider.blobId(bytes))
        }
    }
}

private val IDENTITY = CommitIdentity("Plainbase", "plainbase@localhost")

private fun nativeProvider(exec: GitExecutor, root: Path, home: Path): GitCliHistoryProvider = GitCliHistoryProvider(
    exec = exec,
    workTree = root,
    gitHome = home,
    defaultAuthor = IDENTITY,
    defaultCommitter = IDENTITY,
    clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_780_272_000L)
    },
    maintenance = {},
)

private fun installNativeShim(home: Path, behavior: String): Path {
    val script = """
        #!/bin/sh
        sub=""
        skip=0
        for arg in "${'$'}@"; do
            if [ "${'$'}skip" -eq 1 ]; then skip=0; continue; fi
            case "${'$'}arg" in -C|-c) skip=1;; -*) ;; *) sub="${'$'}arg"; break;; esac
        done
        $behavior
    """.trimIndent()
    val shim = Files.createTempFile(home, "discussion-native-git", ".sh")
    Files.writeString(shim, script)
    Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwxr-xr-x"))
    return shim
}

private inline fun <T> withGitNative(block: (Path, Path) -> T): T {
    val root = Files.createTempDirectory("plainbase-discussion-git-native")
    val home = Files.createTempDirectory("plainbase-discussion-git-home-native")
    try {
        return block(root, home)
    } finally {
        root.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }
}

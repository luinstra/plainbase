package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Tag("native")
class LocalContentStorePolicyNativeTest {
    @Test
    fun `physical DATA_DIR is excluded through a symlinked content root`() {
        val base = Files.createTempDirectory("pb-native-policy-alias")
        try {
            val real = Files.createDirectories(base.resolve("real"))
            val alias = base.resolve("alias")
            try {
                Files.createSymbolicLink(alias, real)
            } catch (_: IOException) {
                return
            }
            val data = Files.createDirectories(real.resolve("state"))
            val databaseBytes = "database sentinel".encodeToByteArray()
            val pageBytes = "# Allowed\n".encodeToByteArray()
            Files.write(data.resolve("plainbase.db"), databaseBytes)
            Files.write(real.resolve("page.md"), pageBytes)
            val root = Root(
                name = RootName.PRIMARY,
                backend = RootBackend.Local(alias),
                editable = true,
                history = HistoryMode.OFF,
            )
            val policy = localContentPathPolicy(root, alias, IgnoreRules(), listOf(data))
            val store = LocalContentStore(alias, exclusions = listOf(data), policy = policy)
            val hidden = TreePath.require("state/plainbase.db")

            assertEquals(listOf("page.md"), store.scan().files.map { it.path.value })
            assertNull(store.read(hidden))
            assertNull(store.stat(hidden))
            val create = store.createExclusive(TreePath.require("state/new.md"), "blocked".encodeToByteArray()) { it.size.toString() }
            assertEquals(true, create is CreateResult.Rejected)
            assertEquals(
                CasResult.Deleted,
                store.compareAndSwapWrite(hidden, databaseBytes.size.toString(), "changed".encodeToByteArray()) { it.size.toString() },
            )
            assertContentEquals(databaseBytes, Files.readAllBytes(data.resolve("plainbase.db")))
            assertContentEquals(pageBytes, store.read(TreePath.require("page.md")))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `in-root ancestor symlink cannot redirect indexed reads stats or CAS`() {
        val rootPath = Files.createTempDirectory("pb-native-policy-symlink")
        try {
            val publicBytes = "# Public\n".encodeToByteArray()
            val privateBytes = "# Private\n".encodeToByteArray()
            Files.createDirectories(rootPath.resolve("docs"))
            Files.createDirectories(rootPath.resolve("private"))
            Files.write(rootPath.resolve("docs/page.md"), publicBytes)
            Files.write(rootPath.resolve("private/page.md"), privateBytes)
            val root = Root(
                name = RootName.PRIMARY,
                backend = RootBackend.Local(rootPath),
                editable = true,
                history = HistoryMode.OFF,
                includes = listOf("docs/**"),
            )
            val policy = localContentPathPolicy(root, rootPath, IgnoreRules(), emptyList())
            val store = LocalContentStore(rootPath, policy = policy).also { it.scan() }
            val path = TreePath.require("docs/page.md")

            Files.delete(rootPath.resolve("docs/page.md"))
            Files.delete(rootPath.resolve("docs"))
            try {
                Files.createSymbolicLink(rootPath.resolve("docs"), rootPath.resolve("private"))
            } catch (_: IOException) {
                return
            }

            assertNull(store.read(path))
            assertEquals(StoreRead.NoBytes, store.readClassified(path))
            assertNull(store.stat(path))
            assertEquals(
                CasResult.Deleted,
                store.compareAndSwapWrite(path, publicBytes.size.toString(), "changed".encodeToByteArray()) { it.size.toString() },
            )
            assertContentEquals(privateBytes, Files.readAllBytes(rootPath.resolve("private/page.md")))
        } finally {
            Files.walk(rootPath).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

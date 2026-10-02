package com.plainbase.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedBuildSourceTest {
    @TempDir
    lateinit var root: Path

    private fun write(path: String, value: String = "input") {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
    }

    @Test
    fun `should detect changed deleted and added inputs including ignored source files`() {
        write("server/src/main/App.kt")
        val index = "100644 ${"a".repeat(40)} 0\tserver/src/main/App.kt\u0000"
        val original = SharedBuildSource.fingerprint(root, index)
        write("server/src/main/App.kt", "changed")
        assertNotEquals(original, SharedBuildSource.fingerprint(root, index))
        Files.delete(root.resolve("server/src/main/App.kt"))
        assertNotEquals(original, SharedBuildSource.fingerprint(root, index))
        write("server/src/main/App.kt")
        val additions = listOf("frontend/src/new.ts", "frontend/public/new.svg", "frontend/scripts/ignored.mjs", "buildSrc/new.gradle.kts")
        additions.forEach { path ->
            write(path)
            assertNotEquals(original, SharedBuildSource.fingerprint(root, index))
            Files.delete(root.resolve(path))
        }
        write("unrelated-owner-directory/unread-draft.md")
        write("server/build/generated/ignored.kt")
        assertEquals(original, SharedBuildSource.fingerprint(root, index))
    }

    @Test
    fun `should reject unmerged index source symlinks and frontend environment overrides`() {
        assertFailsWith<IllegalArgumentException> { SharedBuildSource.fingerprint(root, "100644 a 1\tconflict\u0000") }
        write("frontend/src/source.ts")
        Files.createSymbolicLink(root.resolve("frontend/src/link.ts"), root.resolve("frontend/src/source.ts"))
        assertFailsWith<IllegalArgumentException> { SharedBuildSource.fingerprint(root, "") }
        assertFailsWith<IllegalArgumentException> { SharedBuildSource.checkFrontendEnvironment(root, mapOf("VITE_OVERRIDE" to "1")) }
        write("frontend/.env.local")
        assertFailsWith<IllegalArgumentException> { SharedBuildSource.checkFrontendEnvironment(root, emptyMap()) }
    }

    @Test
    fun `should detect additions edits and deletions beneath output-like source directory names`() {
        val roots = listOf(
            "frontend/public", "frontend/src/packages", "server/src/main/kotlin/example", "frontend/scripts", "frontend/e2e",
            "buildSrc/src/main/kotlin/example", "config", "gradle",
        )
        val names = listOf("build", "dist", "node_modules", ".node", ".gradle", ".kotlin", "test-results", "playwright-report")
        val original = SharedBuildSource.fingerprint(root, "")
        roots.forEach { sourceRoot ->
            names.forEach { name ->
                // These inputs may be ignored by Git; discovery under explicit roots must still read them.
                val path = "$sourceRoot/$name/input.txt"
                write(path)
                val added = SharedBuildSource.fingerprint(root, "")
                assertNotEquals(original, added, path)
                write(path, "edited")
                val edited = SharedBuildSource.fingerprint(root, "")
                assertNotEquals(added, edited, path)
                val index = "100644 ${"a".repeat(40)} 0\t$path\u0000"
                val tracked = SharedBuildSource.fingerprint(root, index)
                Files.delete(root.resolve(path))
                assertNotEquals(tracked, SharedBuildSource.fingerprint(root, index), path)
                assertNotEquals(edited, SharedBuildSource.fingerprint(root, ""), path)
                assertEquals(original, SharedBuildSource.fingerprint(root, ""), path)
            }
        }
    }

    @Test
    fun `should reject file and directory symlinks beneath output-like source directory names`() {
        write("source-target.txt")
        Files.createDirectories(root.resolve("source-target-directory"))
        listOf("frontend/public/dist", "frontend/src/packages/build", "server/src/main/kotlin/example/dist").forEach { directory ->
            Files.createDirectories(root.resolve(directory))
            listOf("link.txt", "linked-directory").forEach { name ->
                val link = root.resolve("$directory/$name")
                val target = if (name == "link.txt") "source-target.txt" else "source-target-directory"
                Files.createSymbolicLink(link, root.resolve(target))
                val error = assertFailsWith<IllegalArgumentException> { SharedBuildSource.fingerprint(root, "") }
                assertTrue(error.message.orEmpty().contains("symlink"))
                Files.delete(link)
            }
        }
    }

    @Test
    fun `should detect local npm configuration and exclude only actual generated roots`() {
        val original = SharedBuildSource.fingerprint(root, "")
        listOf(".npmrc", "frontend/.npmrc").forEach { path ->
            write(path, "ignore-scripts=true")
            val added = SharedBuildSource.fingerprint(root, "")
            assertNotEquals(original, added, path)
            write(path, "ignore-scripts=false")
            assertNotEquals(added, SharedBuildSource.fingerprint(root, ""), path)
            Files.delete(root.resolve(path))
            assertEquals(original, SharedBuildSource.fingerprint(root, ""), path)
        }
        val outputs = listOf(
            "build/output", "server/build/output", "buildSrc/build/output", "frontend/dist/output", "frontend/.node/output",
            "frontend/node_modules/output", "frontend/test-results/output", "frontend/playwright-report/output", ".gradle/output",
        )
        outputs.forEach { write(it) }
        val index = outputs.joinToString("") { "100644 ${"a".repeat(40)} 0\t$it\u0000" }
        Files.createSymbolicLink(root.resolve("unrelated-owner-directory"), root.resolve("unread-target"))
        Files.createSymbolicLink(root.resolve("server/.npmrc"), root.resolve("unread-target"))
        assertEquals(original, SharedBuildSource.fingerprint(root, index))
    }
}

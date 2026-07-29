package com.plainbase.frameworks.cli

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsOrigin
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `plainbase root` end-to-end round trip under the NATIVE image (the `AdoptCommandNativeTest` pattern).
 *
 * Three divergence surfaces meet in this one command and all three are exercised here: **process execution** (the
 * boot gate shells out to `git`), **file I/O** (an `ATOMIC_MOVE` promote plus an unlink), and **HOCON config
 * parsing** (the candidate is parsed from a STRING and then, at the next load, from a FILE - the closed-world
 * image is where a reflective parser path would break).
 */
@Tag("native")
class RootCommandNativeTest {

    @Test
    fun `add then list then remove, end to end, and the loader agrees at every step`() {
        val base = Files.createTempDirectory("pb-root-native")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            val notes = Files.createDirectory(base.resolve("notes"))
            Files.writeString(content.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")
            Files.writeString(notes.resolve("n.md"), "---\ntitle: N\n---\n\n# N\n")

            val env = mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString())
            val rootsConf = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)

            // ADD
            val added = captureStdout {
                assertEquals(
                    0,
                    RootCommand.run(listOf("add", "notes", notes.toString(), "--editable"), env, NativeCommandOutputCapture.current),
                )
            }
            assertTrue(Files.exists(rootsConf))
            assertTrue(added.contains(notes.toString()), "the ABSOLUTE path must be printed: $added")
            assertTrue(added.contains("restart the server to apply"))

            val afterAdd = PlainbaseConfig.fromEnvAndFile(env).roots
            assertContentEquals(listOf("docs", "notes"), afterAdd.list.map { it.name.value })
            assertTrue(afterAdd.extras.single().editable)
            assertEquals(RootsOrigin.EXPLICIT, afterAdd.origin)

            // LIST
            val listed = captureStdout { assertEquals(0, RootCommand.run(listOf("list"), env, NativeCommandOutputCapture.current)) }
            assertTrue(listed.contains("notes"), listed)
            assertTrue(listed.contains(PlainbaseConfig.MANAGED_ROOTS_FILE), "provenance must be shown: $listed")
            assertTrue(listed.contains("CONTENT_DIR"), "a synthesized main comes from CONTENT_DIR: $listed")
            assertTrue(listed.contains("/healthz"), "live state is the SERVER's to know: $listed")

            // REMOVE - and the file goes with it, returning the install to byte-identical legacy behavior.
            captureStdout { assertEquals(0, RootCommand.run(listOf("remove", "notes"), env, NativeCommandOutputCapture.current)) }
            assertFalse(Files.exists(rootsConf))
            assertEquals(RootsOrigin.SYNTHESIZED, PlainbaseConfig.fromEnvAndFile(env).roots.origin)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the operator's plainbase-conf is never opened for writing - asserted on the BYTES, under the native image`() {
        val base = Files.createTempDirectory("pb-root-native-untouched")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            val notes = Files.createDirectory(base.resolve("notes"))
            Files.writeString(content.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")

            val conf: Path = data.resolve("plainbase.conf")
            Files.writeString(
                conf,
                """
                # comments the operator cares about
                host = "127.0.0.1"

                roots {
                  docs { path = "$content" }
                }
                """.trimIndent(),
            )
            val before = Files.readAllBytes(conf)
            val env = mapOf("DATA_DIR" to data.toString())

            captureStdout {
                assertEquals(0, RootCommand.run(listOf("add", "notes", notes.toString()), env, NativeCommandOutputCapture.current))
            }
            captureStdout { assertEquals(0, RootCommand.run(listOf("remove", "notes"), env, NativeCommandOutputCapture.current)) }

            assertContentEquals(before, Files.readAllBytes(conf), "`plainbase root` opened plainbase.conf for writing")
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}

private fun captureStdout(block: () -> Unit): String = NativeCommandOutputCapture.captureStdout(block)

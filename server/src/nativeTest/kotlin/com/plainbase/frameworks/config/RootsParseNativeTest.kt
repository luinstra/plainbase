package com.plainbase.frameworks.config

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Native proof for the two multi-root C1 divergence surfaces (the [HoconParseNativeTest] pattern):
 * the `getObject`/`ConfigObject.entrySet()`/`origin().lineNumber()` iteration is a typesafe-config
 * API surface previously unexercised under the closed-world image (only `hasPath` + typed getters
 * ran there), and `toRealPath()` symlink resolution in the duplicate-root check is NIO edge
 * behavior. kotlin.test + @Tag("native") only (the native gate's source set).
 */
@Tag("native")
class RootsParseNativeTest {

    @Test
    fun `a roots block parses in origin-line order with the name tiebreak inside the native image`() {
        val data = Files.createTempDirectory("pb-native-roots-order")
        try {
            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                roots {
                  docs { path = "/roots/m" }
                  zeta { path = "/roots/z" }, alpha { path = "/roots/a" }
                }
                """.trimIndent(),
            )
            val config = PlainbaseConfig.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            assertEquals(RootsOrigin.EXPLICIT, config.roots.origin)
            assertEquals(listOf("docs", "alpha", "zeta"), config.roots.list.map { it.name.value })
        } finally {
            Files.walk(data).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `a symlinked duplicate root is refused inside the native image`() {
        val base = Files.createTempDirectory("pb-native-roots-symlink")
        try {
            val data = Files.createDirectories(base.resolve("data"))
            val docs = Files.createDirectories(base.resolve("docs"))
            val link = Files.createSymbolicLink(base.resolve("docs-link"), docs)
            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                roots {
                  docs { path = "$docs" }
                  twin { path = "$link" }
                }
                """.trimIndent(),
            )
            val config = PlainbaseConfig.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            val failure = assertFailsWith<IllegalArgumentException> { config.requireContentDir() }
            assertTrue(failure.message.orEmpty().contains("resolve to the same directory"), "unexpected message: ${failure.message}")
        } finally {
            Files.walk(base).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

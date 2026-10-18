package com.plainbase.frameworks.filesystem

import com.plainbase.domain.theming.ThemeBuiltins
import com.plainbase.domain.theming.ThemeLeaf
import com.plainbase.domain.theming.ThemeMode
import com.plainbase.domain.theming.ThemeSelection
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("native")
class LocalThemeStylesheetsNativeTest {
    private val builtins = ThemeBuiltins("light builtin".encodeToByteArray(), "dark builtin".encodeToByteArray())
    private val paper = requireNotNull(ThemeLeaf.of("paper.css"))
    private val css = "body { color: purple; }".encodeToByteArray()
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val scheme = buildString {
        append("system: \"base24\"\r\nname: \"Paper\"\r\nauthor: \"Jos\u00e9 \u65e5\u672c\"\r\npalette:\r\n")
        "0123456789ABCDEF".forEach { slot -> append("  base0$slot: \"#AABBCC\"\r\n") }
        ('0'..'7').forEach { slot -> append("  base1$slot: \"#DDEEFF\"\r\n") }
    }
    private val expectedSchemeCss = """
        :where(:root:not([data-theme="dark"])) {
          --pb-theme-background: #aabbcc;
          --pb-theme-foreground: #aabbcc;
          --pb-theme-primary: #aabbcc;
          --pb-theme-primary-foreground: #aabbcc;
          --pb-theme-muted: #aabbcc;
          --pb-theme-muted-foreground: #aabbcc;
          --pb-theme-ring: #ddeeff;
          --pb-theme-selection: #aabbcc;
          --pb-theme-selection-foreground: #aabbcc;
          --pb-theme-destructive: #ddeeff;
          --pb-theme-warning: #ddeeff;
          --pb-theme-success: #ddeeff;
          --pb-theme-syntax-keyword: #ddeeff;
          --pb-theme-syntax-string: #ddeeff;
          --pb-theme-syntax-comment: #aabbcc;
          --pb-theme-syntax-literal: #aabbcc;
          --pb-theme-syntax-title: #ddeeff;
          --pb-theme-syntax-attribute: #ddeeff;
        }
    """.trimIndent() + "\n"

    @Test
    fun themesDirectorySchemeWithNonAsciiAuthorConverts() {
        withDataDir { data ->
            Files.createDirectory(data.resolve("themes"))
            val leaf = requireNotNull(ThemeLeaf.of("themes/paper.yaml"))
            Files.write(data.resolve(leaf.value), bom + scheme.encodeToByteArray())
            val adapter = LocalThemeStylesheets(data, ThemeSelection(leaf, null), builtins)

            val read = adapter.readSelected(leaf)
            assertTrue(read is ThemeRead.Bytes)
            assertEquals(scheme, read.bytes.decodeToString())
            assertEquals(expectedSchemeCss, adapter.definition(ThemeMode.LIGHT).decodeToString())
        }
    }

    @Test
    fun lineSeparatorInSchemeFallsBack() {
        withDataDir { data ->
            val leaf = requireNotNull(ThemeLeaf.of("paper.yaml"))
            val path = Files.writeString(data.resolve(leaf.value), scheme)
            val adapter = LocalThemeStylesheets(data, ThemeSelection(leaf, null), builtins)

            assertEquals(expectedSchemeCss, adapter.definition(ThemeMode.LIGHT).decodeToString())
            Files.writeString(path, "# before\u2028after\r\n$scheme")
            assertContentEquals(builtins.light, adapter.definition(ThemeMode.LIGHT))
        }
    }

    @Test
    fun symlinkedThemesDirectoryIsNotRegular() {
        withDataDir { outside ->
            Files.write(outside.resolve("paper.css"), css)
            withDataDir { data ->
                Files.createSymbolicLink(data.resolve("themes"), outside)
                val leaf = requireNotNull(ThemeLeaf.of("themes/paper.css"))
                val adapter = LocalThemeStylesheets(data, ThemeSelection(leaf, null), builtins)

                assertTrue(Files.isRegularFile(data.resolve(leaf.value)))
                assertEquals(ThemeRead.Failure(ThemeReadFailure.NOT_REGULAR), adapter.readSelected(leaf))
                assertContentEquals(builtins.light, adapter.definition(ThemeMode.LIGHT))
            }
        }
    }

    @Test
    fun selectedSymlinkToFileIsNotRegular() {
        withDataDir { data ->
            val target = Files.write(data.resolve("target.css"), css)
            val path = Files.createSymbolicLink(data.resolve("paper.css"), target)

            assertFailure(selected(data), path, ThemeReadFailure.NOT_REGULAR)
        }
    }

    @Test
    fun danglingSelectedSymlinkIsNotRegular() {
        withDataDir { data ->
            val path = Files.createSymbolicLink(data.resolve("paper.css"), data.resolve("missing.css"))

            assertFailure(selected(data), path, ThemeReadFailure.NOT_REGULAR)
        }
    }

    @Test
    fun selectedDirectoryIsNotRegular() {
        withDataDir { data ->
            val path = Files.createDirectory(data.resolve("paper.css"))

            assertFailure(selected(data), path, ThemeReadFailure.NOT_REGULAR)
        }
    }

    @Test
    fun symlinkedDataDirectoryServesRegularSelectedLeaf() {
        withDataDir { temporary ->
            val real = Files.createDirectory(temporary.resolve("real"))
            Files.write(real.resolve("paper.css"), css)
            val data = Files.createSymbolicLink(temporary.resolve("linked-data"), real)

            assertTrue(Files.isSymbolicLink(data))
            assertContentEquals(css, selected(data).definition(ThemeMode.LIGHT))
        }
    }

    @Test
    fun symlinkSwappedAfterPrecheckIsUnreadable() {
        withDataDir { data ->
            val path = Files.write(data.resolve("paper.css"), css)
            val sentinel = "swapped sentinel stylesheet".encodeToByteArray()
            val target = Files.write(data.resolve("sentinel.css"), sentinel)
            var openFailure: IOException? = null
            val opener = ThemeChannelOpener { leaf ->
                Files.delete(leaf)
                Files.createSymbolicLink(leaf, target)
                try {
                    ThemeChannelOpener.NOFOLLOW.open(leaf)
                } catch (failure: IOException) {
                    openFailure = failure
                    logger.info { "Swapped stylesheet no-follow open failed with ${failure.javaClass.name}: ${failure.message}" }
                    throw failure
                }
            }
            val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)

            assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val result = adapter.read(path)
            assertEquals(ThemeRead.Failure(ThemeReadFailure.UNREADABLE), result)
            assertNotNull(openFailure, "the no-follow channel open must reject the swapped symlink")

            Files.delete(path)
            Files.write(path, css)
            openFailure = null
            assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val served = adapter.definition(ThemeMode.LIGHT)
            assertContentEquals(builtins.light, served)
            assertFalse(served.contentEquals(sentinel))
            assertNotNull(openFailure, "the selected definition must use the no-follow channel open")
        }
    }

    @Test
    fun oneLeadingUtf8BomIsStripped() {
        withDataDir { data ->
            val path = Files.write(data.resolve("paper.css"), bom + css)
            val adapter = selected(data)
            val result = adapter.read(path)

            assertTrue(result is ThemeRead.Bytes)
            assertContentEquals(css, result.bytes)
            assertContentEquals(css, adapter.definition(ThemeMode.LIGHT))
        }
    }

    @Test
    fun malformedUtf8IsRejectedWithoutReplacement() {
        val malformed = listOf(
            "lone continuation" to byteArrayOf(0x80.toByte()),
            "overlong form" to byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            "surrogate" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        )
        malformed.forEach { (label, bytes) ->
            withDataDir { data ->
                val path = Files.write(data.resolve("paper.css"), bytes)

                assertFailure(selected(data), path, ThemeReadFailure.INVALID_UTF8, label)
            }
        }
    }

    @Test
    fun exactCapIncludingBomServesTheCompletePayload() {
        listOf(ByteArray(0), bom).forEach { prefix ->
            withDataDir { data ->
                val payload = ByteArray(262_144 - prefix.size) { 'a'.code.toByte() }
                val path = Files.write(data.resolve("paper.css"), prefix + payload)
                val adapter = selected(data)
                val result = adapter.read(path)

                assertTrue(result is ThemeRead.Bytes)
                assertContentEquals(payload, result.bytes)
                assertContentEquals(payload, adapter.definition(ThemeMode.LIGHT))
            }
        }
    }

    @Test
    fun oneByteBeyondCapIncludingBomIsOversize() {
        listOf(ByteArray(0), bom).forEach { prefix ->
            withDataDir { data ->
                val payload = ByteArray(262_145 - prefix.size) { 'a'.code.toByte() }
                val path = Files.write(data.resolve("paper.css"), prefix + payload)

                assertFailure(selected(data), path, ThemeReadFailure.OVERSIZE)
            }
        }
    }

    private fun selected(data: Path): LocalThemeStylesheets = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins)

    private fun assertFailure(adapter: LocalThemeStylesheets, path: Path, reason: ThemeReadFailure, message: String? = null) {
        assertEquals(ThemeRead.Failure(reason), adapter.read(path), message)
        assertContentEquals(builtins.light, adapter.definition(ThemeMode.LIGHT), message)
    }

    private fun withDataDir(block: (Path) -> Unit) {
        val data = Files.createTempDirectory("pb-native-theme-read").toRealPath()
        try {
            block(data)
        } finally {
            Files.walk(data).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

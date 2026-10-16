package com.plainbase.frameworks.filesystem

import com.plainbase.domain.theming.BundledThemeStylesheets
import com.plainbase.domain.theming.ThemeBuiltins
import com.plainbase.domain.theming.ThemeLeaf
import com.plainbase.domain.theming.ThemeMode
import com.plainbase.domain.theming.ThemeSelection
import com.plainbase.frameworks.ktor.routes.bundleResourceBytes
import com.plainbase.frameworks.ktor.routes.embeddedThemeBuiltins
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

class LocalThemeStylesheetsTest : FunSpec({
    val builtins = ThemeBuiltins("light builtin".encodeToByteArray(), "dark builtin".encodeToByteArray())
    val paper = requireNotNull(ThemeLeaf.of("paper.css"))
    val css = "body { color: purple; }".encodeToByteArray()
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    test("embedded theme builtins contain both complete nonempty bundled resources") {
        val themes = embeddedThemeBuiltins()
        assertSoftly {
            themes.light shouldBe requireNotNull(bundleResourceBytes("_plainbase/plainbase-theme-light.css"))
            themes.dark shouldBe requireNotNull(bundleResourceBytes("_plainbase/plainbase-theme-dark.css"))
            themes.light.isNotEmpty() shouldBe true
            themes.dark.isNotEmpty() shouldBe true
        }
    }

    listOf("_plainbase/plainbase-theme-light.css", "_plainbase/plainbase-theme-dark.css").forEach { missing ->
        test("a missing embedded $missing refuses with its resource name") {
            val failure = shouldThrow<IllegalStateException> {
                embeddedThemeBuiltins { resource -> if (resource == missing) null else "bundled stylesheet".encodeToByteArray() }
            }
            failure.message shouldContain missing
        }
    }

    fun withDataDir(block: (Path) -> Unit) {
        val data = Files.createTempDirectory("pb-theme-read")
        try {
            block(data)
        } finally {
            Files.walk(data).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun selected(data: Path, mode: ThemeMode = ThemeMode.LIGHT): LocalThemeStylesheets {
        val selection = when (mode) {
            ThemeMode.LIGHT -> ThemeSelection(paper, null)
            ThemeMode.DARK -> ThemeSelection(null, paper)
        }
        return LocalThemeStylesheets(data, selection, builtins)
    }

    fun assertFailure(adapter: LocalThemeStylesheets, path: Path, reason: ThemeReadFailure) {
        assertSoftly {
            adapter.definition(ThemeMode.LIGHT) shouldBe builtins.light
            adapter.read(path) shouldBe ThemeRead.Failure(reason)
        }
    }

    ThemeMode.entries.forEach { mode ->
        test("a selected $mode definition reads the DATA_DIR root leaf exactly") {
            withDataDir { data ->
                Files.write(data.resolve("paper.css"), css)
                val themes = Files.createDirectory(data.resolve("themes"))
                Files.writeString(themes.resolve("paper.css"), "wrong directory")
                selected(data, mode).definition(mode) shouldBe css
            }
        }
    }

    test("a leaf present only under themes is missing at the DATA_DIR root") {
        withDataDir { data ->
            val themes = Files.createDirectory(data.resolve("themes"))
            Files.write(themes.resolve("paper.css"), css)
            assertFailure(selected(data), data.resolve("paper.css"), ThemeReadFailure.MISSING)
        }
    }

    test("the fixed override reads custom.css directly from DATA_DIR") {
        withDataDir { data ->
            Files.write(data.resolve("custom.css"), css)
            LocalThemeStylesheets(data, ThemeSelection.BUNDLED, builtins).override() shouldBe css
        }
    }

    ThemeMode.entries.forEach { mode ->
        test("an unselected $mode definition serves its distinct builtin exactly") {
            withDataDir { data ->
                Files.write(data.resolve("paper.css"), css)
                val selection = when (mode) {
                    ThemeMode.LIGHT -> ThemeSelection(null, paper)
                    ThemeMode.DARK -> ThemeSelection(paper, null)
                }
                LocalThemeStylesheets(data, selection, builtins).definition(mode) shouldBe builtins.definition(mode)
            }
        }
    }

    test("an absent override is an empty sheet with a missing read category") {
        withDataDir { data ->
            val adapter = LocalThemeStylesheets(data, ThemeSelection.BUNDLED, builtins)
            assertSoftly {
                adapter.override() shouldBe ByteArray(0)
                adapter.read(data.resolve("custom.css")) shouldBe ThemeRead.Failure(ThemeReadFailure.MISSING)
            }
        }
    }

    test("exactly 262144 bytes are served without padding or truncation") {
        withDataDir { data ->
            val bytes = ByteArray(262144) { 'a'.code.toByte() }
            Files.write(data.resolve("paper.css"), bytes)
            selected(data).definition(ThemeMode.LIGHT) shouldBe bytes
        }
    }

    test("262145 bytes fall back with the oversize category") {
        withDataDir { data ->
            val path = data.resolve("paper.css")
            Files.write(path, ByteArray(262145) { 'a'.code.toByte() })
            assertFailure(selected(data), path, ThemeReadFailure.OVERSIZE)
        }
    }

    test("a BOM plus 262141 bytes fits the cap and serves only the payload") {
        withDataDir { data ->
            val payload = ByteArray(262141) { 'a'.code.toByte() }
            Files.write(data.resolve("paper.css"), bom + payload)
            selected(data).definition(ThemeMode.LIGHT) shouldBe payload
        }
    }

    test("a BOM plus 262142 bytes exceeds the cap including its BOM") {
        withDataDir { data ->
            val path = data.resolve("paper.css")
            Files.write(path, bom + ByteArray(262142) { 'a'.code.toByte() })
            assertFailure(selected(data), path, ThemeReadFailure.OVERSIZE)
        }
    }

    test("a cap-sized file ending in a complete three-byte character is served exactly") {
        withDataDir { data ->
            val bytes = ByteArray(262141) { 'a'.code.toByte() } + byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte())
            Files.write(data.resolve("paper.css"), bytes)
            selected(data).definition(ThemeMode.LIGHT) shouldBe bytes
        }
    }

    test("one leading BOM is stripped from the served definition") {
        withDataDir { data ->
            Files.write(data.resolve("paper.css"), bom + css)
            selected(data).definition(ThemeMode.LIGHT) shouldBe css
        }
    }

    test("a BOM-only file falls back with the empty category") {
        withDataDir { data ->
            val path = data.resolve("paper.css")
            Files.write(path, bom)
            assertFailure(selected(data), path, ThemeReadFailure.EMPTY)
        }
    }

    test("a BOM followed by CSS whitespace falls back with the empty category") {
        withDataDir { data ->
            val path = data.resolve("paper.css")
            Files.write(path, bom + "\n \t".encodeToByteArray())
            assertFailure(selected(data), path, ThemeReadFailure.EMPTY)
        }
    }

    test("a double BOM serves exactly its second BOM") {
        withDataDir { data ->
            Files.write(data.resolve("paper.css"), bom + bom)
            selected(data).definition(ThemeMode.LIGHT) shouldBe bom
        }
    }

    test("a non-leading U+FEFF is served unchanged") {
        withDataDir { data ->
            val bytes = "before".encodeToByteArray() + bom + "after".encodeToByteArray()
            Files.write(data.resolve("paper.css"), bytes)
            selected(data).definition(ThemeMode.LIGHT) shouldBe bytes
        }
    }

    test("a zero-byte file falls back with the empty category") {
        withDataDir { data ->
            val path = data.resolve("paper.css")
            Files.write(path, ByteArray(0))
            assertFailure(selected(data), path, ThemeReadFailure.EMPTY)
        }
    }

    test("all five CSS whitespace bytes alone fall back with the empty category") {
        withDataDir { data ->
            val path = data.resolve("paper.css")
            Files.write(path, byteArrayOf(0x09, 0x0A, 0x0C, 0x0D, 0x20))
            assertFailure(selected(data), path, ThemeReadFailure.EMPTY)
        }
    }

    test("NBSP alone is served as nonempty UTF-8") {
        withDataDir { data ->
            val bytes = byteArrayOf(0xC2.toByte(), 0xA0.toByte())
            Files.write(data.resolve("paper.css"), bytes)
            selected(data).definition(ThemeMode.LIGHT) shouldBe bytes
        }
    }

    test("comment-only CSS is served exactly") {
        withDataDir { data ->
            val bytes = "/* */".encodeToByteArray()
            Files.write(data.resolve("paper.css"), bytes)
            selected(data).definition(ThemeMode.LIGHT) shouldBe bytes
        }
    }

    listOf(
        "a lone continuation byte" to byteArrayOf(0x80.toByte()),
        "an overlong sequence" to byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
        "a surrogate encoding" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        "a truncated sequence at EOF" to byteArrayOf(0xE2.toByte(), 0x82.toByte()),
        "a UTF-16LE BOM" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
    ).forEach { (label, bytes) ->
        test("$label falls back with the invalid UTF-8 category") {
            withDataDir { data ->
                val path = data.resolve("paper.css")
                Files.write(path, bytes)
                assertFailure(selected(data), path, ThemeReadFailure.INVALID_UTF8)
            }
        }
    }

    test("the bundled-only port serves both complete definitions and an empty override") {
        val adapter = BundledThemeStylesheets(builtins)
        assertSoftly {
            adapter.definition(ThemeMode.LIGHT) shouldBe builtins.light
            adapter.definition(ThemeMode.DARK) shouldBe builtins.dark
            adapter.override() shouldBe ByteArray(0)
        }
    }

    test("a selected symlink to a file inside DATA_DIR falls back as not regular") {
        withDataDir { data ->
            val target = Files.write(data.resolve("target.css"), css)
            val path = Files.createSymbolicLink(data.resolve("paper.css"), target)
            assertFailure(selected(data), path, ThemeReadFailure.NOT_REGULAR)
        }
    }

    test("a selected symlink to a file outside DATA_DIR falls back as not regular") {
        withDataDir { outside ->
            val target = Files.write(outside.resolve("target.css"), css)
            withDataDir { data ->
                val path = Files.createSymbolicLink(data.resolve("paper.css"), target)
                assertFailure(selected(data), path, ThemeReadFailure.NOT_REGULAR)
            }
        }
    }

    test("a dangling selected symlink is not regular rather than missing") {
        withDataDir { data ->
            val path = Files.createSymbolicLink(data.resolve("paper.css"), data.resolve("missing.css"))
            assertFailure(selected(data), path, ThemeReadFailure.NOT_REGULAR)
        }
    }

    test("a directory named as the selected stylesheet falls back as not regular") {
        withDataDir { data ->
            val path = Files.createDirectory(data.resolve("paper.css"))
            assertFailure(selected(data), path, ThemeReadFailure.NOT_REGULAR)
        }
    }

    test("a symlinked DATA_DIR is the trusted base for a regular selected leaf") {
        withDataDir { temporary ->
            val base = temporary.toRealPath()
            val real = Files.createDirectory(base.resolve("real"))
            Files.write(real.resolve("paper.css"), css)
            val data = Files.createSymbolicLink(base.resolve("linked-data"), real)
            Files.isSymbolicLink(data) shouldBe true
            selected(data).definition(ThemeMode.LIGHT) shouldBe css
        }
    }

    listOf(
        ThemeReadFailure.EMPTY to ByteArray(0),
        ThemeReadFailure.OVERSIZE to ByteArray(262145) { 'a'.code.toByte() },
        ThemeReadFailure.INVALID_UTF8 to byteArrayOf(0x80.toByte()),
    ).forEach { (reason, bytes) ->
        test("an override with $reason produces an empty sheet and its exact read category") {
            withDataDir { data ->
                val path = Files.write(data.resolve("custom.css"), bytes)
                val adapter = LocalThemeStylesheets(data, ThemeSelection.BUNDLED, builtins)
                assertSoftly {
                    adapter.override() shouldBe ByteArray(0)
                    adapter.read(path) shouldBe ThemeRead.Failure(reason)
                }
            }
        }
    }

    test("a directory override produces an empty sheet and the not regular category") {
        withDataDir { data ->
            val path = Files.createDirectory(data.resolve("custom.css"))
            val adapter = LocalThemeStylesheets(data, ThemeSelection.BUNDLED, builtins)
            assertSoftly {
                adapter.override() shouldBe ByteArray(0)
                adapter.read(path) shouldBe ThemeRead.Failure(ThemeReadFailure.NOT_REGULAR)
            }
        }
    }

    test("a symlinked override warns and produces an empty sheet with the not regular category") {
        withDataDir { data ->
            val target = Files.write(data.resolve("target.css"), css)
            val path = Files.createSymbolicLink(data.resolve("custom.css"), target)
            val adapter = LocalThemeStylesheets(data, ThemeSelection.BUNDLED, builtins)
            var served = css
            val events = captureThemeWarnings { served = adapter.override() }
            assertSoftly {
                served shouldBe ByteArray(0)
                adapter.read(path) shouldBe ThemeRead.Failure(ThemeReadFailure.NOT_REGULAR)
                events.map { it.formattedMessage } shouldBe listOf("Theme stylesheet custom.css NOT_REGULAR; serving no override")
            }
        }
    }

    test("an override open failure produces an empty sheet and the unreadable category") {
        withDataDir { data ->
            val path = Files.write(data.resolve("custom.css"), css)
            val opener = mockk<ThemeChannelOpener>()
            every { opener.open(path) } throws IOException("injected open failure")
            val adapter = LocalThemeStylesheets(data, ThemeSelection.BUNDLED, builtins, opener)
            Files.isRegularFile(path) shouldBe true
            assertSoftly {
                adapter.override() shouldBe ByteArray(0)
                adapter.read(path) shouldBe ThemeRead.Failure(ThemeReadFailure.UNREADABLE)
            }
            verify { opener.open(path) }
        }
    }

    test("a missing override logs nothing") {
        withDataDir { data ->
            val adapter = LocalThemeStylesheets(data, ThemeSelection.BUNDLED, builtins)
            val events = captureThemeWarnings { adapter.override() shouldBe ByteArray(0) }
            events shouldBe emptyList()
        }
    }

    test("an empty override logs nothing") {
        withDataDir { data ->
            Files.write(data.resolve("custom.css"), ByteArray(0))
            val adapter = LocalThemeStylesheets(data, ThemeSelection.BUNDLED, builtins)
            val events = captureThemeWarnings { adapter.override() shouldBe ByteArray(0) }
            events shouldBe emptyList()
        }
    }

    test("a replacement before channel open serves the complete new bytes") {
        withDataDir { data ->
            val path = Files.writeString(data.resolve("paper.css"), "old complete stylesheet")
            val replacement = "new complete stylesheet".encodeToByteArray()
            val temporary = Files.write(data.resolve("replacement.css"), replacement)
            val opener = ThemeChannelOpener { leaf ->
                Files.move(temporary, leaf, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                ThemeChannelOpener.NOFOLLOW.open(leaf)
            }
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) shouldBe true
            val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)
            adapter.definition(ThemeMode.LIGHT) shouldBe replacement
        }
    }

    test("a replacement after channel open serves the old bytes then the fresh bytes") {
        withDataDir { data ->
            val old = "old complete stylesheet".encodeToByteArray()
            val replacement = "new complete stylesheet".encodeToByteArray()
            Files.write(data.resolve("paper.css"), old)
            val temporary = Files.write(data.resolve("replacement.css"), replacement)
            val delegates = mutableListOf<SeekableByteChannel>()
            var replaced = false
            val opener = ThemeChannelOpener { leaf ->
                val channel = ThemeChannelOpener.NOFOLLOW.open(leaf)
                delegates += channel
                if (!replaced) {
                    Files.move(temporary, leaf, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    replaced = true
                }
                channel
            }
            try {
                val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)
                val first = adapter.definition(ThemeMode.LIGHT)
                val next = adapter.definition(ThemeMode.LIGHT)
                assertSoftly {
                    first shouldBe old
                    next shouldBe replacement
                }
            } finally {
                delegates.forEach { it.close() }
            }
        }
    }

    test("growth after the precheck is measured by the channel and rejected as oversize") {
        withDataDir { data ->
            val path = Files.write(data.resolve("paper.css"), css)
            val opener = ThemeChannelOpener { leaf ->
                Files.write(leaf, ByteArray(262145) { 'a'.code.toByte() })
                ThemeChannelOpener.NOFOLLOW.open(leaf)
            }
            val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)
            val result = adapter.read(path)
            val served = adapter.definition(ThemeMode.LIGHT)
            assertSoftly {
                result shouldBe ThemeRead.Failure(ThemeReadFailure.OVERSIZE)
                served shouldBe builtins.light
            }
        }
    }

    test("a symlink swapped in after the precheck is unreadable and never serves its sentinel") {
        withDataDir { data ->
            val path = Files.write(data.resolve("paper.css"), css)
            val sentinel = "swapped sentinel stylesheet".encodeToByteArray()
            val target = Files.write(data.resolve("sentinel.css"), sentinel)
            val opener = ThemeChannelOpener { leaf ->
                Files.delete(leaf)
                Files.createSymbolicLink(leaf, target)
                ThemeChannelOpener.NOFOLLOW.open(leaf)
            }
            val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) shouldBe true
            val result = adapter.read(path)
            Files.delete(path)
            Files.write(path, css)
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) shouldBe true
            val served = adapter.definition(ThemeMode.LIGHT)
            assertSoftly {
                result shouldBe ThemeRead.Failure(ThemeReadFailure.UNREADABLE)
                served shouldBe builtins.light
                served.contentEquals(sentinel) shouldBe false
            }
        }
    }

    test("zero-length channel reads continue until the complete stylesheet is served") {
        withDataDir { data ->
            Files.write(data.resolve("paper.css"), css)
            val delegates = mutableListOf<SeekableByteChannel>()
            var zeroReads = 0
            var delegatedReads = 0
            val opener = ThemeChannelOpener { leaf ->
                val channel = ThemeChannelOpener.NOFOLLOW.open(leaf)
                delegates += channel
                object : SeekableByteChannel by channel {
                    private var returnZero = true

                    override fun read(buffer: ByteBuffer): Int {
                        val zero = returnZero
                        returnZero = !returnZero
                        return if (zero) {
                            zeroReads++
                            0
                        } else {
                            delegatedReads++
                            val originalLimit = buffer.limit()
                            buffer.limit(buffer.position() + minOf(buffer.remaining(), 3))
                            try {
                                channel.read(buffer)
                            } finally {
                                buffer.limit(originalLimit)
                            }
                        }
                    }
                }
            }
            try {
                val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)
                val served = adapter.definition(ThemeMode.LIGHT)
                assertSoftly {
                    served shouldBe css
                    (zeroReads > 0) shouldBe true
                    (delegatedReads > 0) shouldBe true
                }
            } finally {
                delegates.forEach { it.close() }
            }
        }
    }

    test("injected open unreadability falls back for both public sheets without echoing its message") {
        withDataDir { data ->
            val path = Files.write(data.resolve("paper.css"), css)
            val custom = Files.write(data.resolve("custom.css"), css)
            val opener = mockk<ThemeChannelOpener>()
            every { opener.open(path) } throws IOException("injected")
            every { opener.open(custom) } throws IOException("injected")
            val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) shouldBe true
            Files.isRegularFile(custom, LinkOption.NOFOLLOW_LINKS) shouldBe true
            val selectedResult = adapter.read(path)
            val overrideResult = adapter.read(custom)
            var served = ByteArray(0)
            var override = css
            val events = captureThemeWarnings {
                served = adapter.definition(ThemeMode.LIGHT)
                override = adapter.override()
            }
            assertSoftly {
                selectedResult shouldBe ThemeRead.Failure(ThemeReadFailure.UNREADABLE)
                overrideResult shouldBe ThemeRead.Failure(ThemeReadFailure.UNREADABLE)
                served shouldBe builtins.light
                override shouldBe ByteArray(0)
                events.map { it.formattedMessage } shouldBe listOf(
                    "Theme stylesheet theme.light paper.css UNREADABLE; serving the bundled definition",
                )
                events.forEach { it.formattedMessage shouldNotContain "injected" }
            }
            verify(exactly = 2) { opener.open(path) }
            verify(exactly = 2) { opener.open(custom) }
        }
    }

    listOf(
        "a successful read" to (css to null),
        "an oversize read" to (ByteArray(262145) { 'a'.code.toByte() } to ThemeReadFailure.OVERSIZE),
        "an invalid UTF-8 read" to (byteArrayOf(0xFF.toByte()) to ThemeReadFailure.INVALID_UTF8),
        "an empty read" to (ByteArray(0) to ThemeReadFailure.EMPTY),
    ).forEach { (label, fixture) ->
        test("$label closes its channel exactly once") {
            withDataDir { data ->
                val (bytes, reason) = fixture
                val path = Files.write(data.resolve("paper.css"), bytes)
                val delegates = mutableListOf<SeekableByteChannel>()
                var observed: CloseCountingThemeChannel? = null
                val opener = ThemeChannelOpener { leaf ->
                    val channel = ThemeChannelOpener.NOFOLLOW.open(leaf)
                    delegates += channel
                    CloseCountingThemeChannel(channel).also { observed = it }
                }
                try {
                    val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)
                    val result = adapter.read(path)
                    assertSoftly {
                        if (reason == null) {
                            (result as? ThemeRead.Bytes)?.bytes shouldBe bytes
                        } else {
                            result shouldBe ThemeRead.Failure(reason)
                        }
                        delegates.size shouldBe 1
                        observed?.closeCalls shouldBe 1
                    }
                } finally {
                    delegates.forEach { it.close() }
                }
            }
        }
    }

    test("a mid-read IOException closes its channel exactly once and is unreadable") {
        withDataDir { data ->
            val path = Files.write(data.resolve("paper.css"), css)
            val delegates = mutableListOf<SeekableByteChannel>()
            var observed: CloseCountingThemeChannel? = null
            val opener = ThemeChannelOpener { leaf ->
                val channel = ThemeChannelOpener.NOFOLLOW.open(leaf)
                delegates += channel
                CloseCountingThemeChannel(channel, throwAfterFirstRead = true).also { observed = it }
            }
            try {
                val adapter = LocalThemeStylesheets(data, ThemeSelection(paper, null), builtins, opener)
                val result = adapter.read(path)
                assertSoftly {
                    result shouldBe ThemeRead.Failure(ThemeReadFailure.UNREADABLE)
                    observed?.delegatedBytes shouldBe 2
                    observed?.readCalls shouldBe 2
                    delegates.size shouldBe 1
                    observed?.closeCalls shouldBe 1
                }
            } finally {
                delegates.forEach { it.close() }
            }
        }
    }

    test("same-size same-mtime replacement deletion and recreation are read freshly") {
        withDataDir { data ->
            val path = data.resolve("paper.css")
            val timestamp = FileTime.fromMillis(1_700_000_000_000)
            val adapter = selected(data)
            Files.writeString(path, "aaaa")
            Files.setLastModifiedTime(path, timestamp)
            val firstSize = Files.size(path)
            val firstTime = Files.getLastModifiedTime(path)
            val first = adapter.definition(ThemeMode.LIGHT)
            Files.writeString(path, "bbbb")
            Files.setLastModifiedTime(path, timestamp)
            val nextSize = Files.size(path)
            val nextTime = Files.getLastModifiedTime(path)
            val next = adapter.definition(ThemeMode.LIGHT)
            Files.delete(path)
            val deleted = adapter.definition(ThemeMode.LIGHT)
            Files.writeString(path, "cccc")
            Files.setLastModifiedTime(path, timestamp)
            val recreated = adapter.definition(ThemeMode.LIGHT)
            assertSoftly {
                firstSize shouldBe 4L
                nextSize shouldBe firstSize
                firstTime shouldBe timestamp
                nextTime shouldBe timestamp
                first shouldBe "aaaa".encodeToByteArray()
                next shouldBe "bbbb".encodeToByteArray()
                deleted shouldBe builtins.light
                recreated shouldBe "cccc".encodeToByteArray()
            }
        }
    }
})

private class CloseCountingThemeChannel(
    private val delegate: SeekableByteChannel,
    private val throwAfterFirstRead: Boolean = false,
) : SeekableByteChannel by delegate {
    var closeCalls = 0
        private set
    var readCalls = 0
        private set
    var delegatedBytes = 0
        private set

    override fun read(buffer: ByteBuffer): Int {
        readCalls++
        if (throwAfterFirstRead && readCalls > 1) throw IOException("injected mid-read failure")
        val originalLimit = buffer.limit()
        if (throwAfterFirstRead) buffer.limit(buffer.position() + minOf(buffer.remaining(), 2))
        return try {
            delegate.read(buffer).also { if (it > 0) delegatedBytes += it }
        } finally {
            buffer.limit(originalLimit)
        }
    }

    override fun close() {
        closeCalls++
        delegate.close()
    }
}

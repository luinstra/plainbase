package com.plainbase.frameworks.filesystem

import com.plainbase.domain.theming.ThemeBuiltins
import com.plainbase.domain.theming.ThemeMode
import com.plainbase.domain.theming.ThemeSelection
import com.plainbase.domain.theming.ThemeStylesheets
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong

internal sealed interface ThemeRead {
    data class Bytes(val bytes: ByteArray) : ThemeRead
    data class Failure(val reason: ThemeReadFailure) : ThemeRead
}

internal enum class ThemeReadFailure { MISSING, EMPTY, OVERSIZE, INVALID_UTF8, NOT_REGULAR, UNREADABLE }

internal fun interface ThemeChannelOpener {
    fun open(path: Path): SeekableByteChannel

    companion object {
        val NOFOLLOW: ThemeChannelOpener = ThemeChannelOpener { path ->
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        }
    }
}

internal class LocalThemeStylesheets(
    private val dataDir: Path,
    private val selection: ThemeSelection,
    private val builtins: ThemeBuiltins,
    private val opener: ThemeChannelOpener = ThemeChannelOpener.NOFOLLOW,
    private val nanoTime: () -> Long = System::nanoTime,
) : ThemeStylesheets {
    private val nextWarningAt = AtomicLong(nanoTime())
    private val suppressed = AtomicLong()

    override fun definition(mode: ThemeMode): ByteArray {
        val leaf = selection.leaf(mode) ?: return builtins.definition(mode)
        return when (val result = read(dataDir.resolve(leaf.value))) {
            is ThemeRead.Bytes -> result.bytes
            is ThemeRead.Failure -> {
                warn("theme.${mode.name.lowercase()} ${leaf.value}", result, "the bundled definition")
                builtins.definition(mode)
            }
        }
    }

    override fun override(): ByteArray = when (val result = read(dataDir.resolve("custom.css"))) {
        is ThemeRead.Bytes -> result.bytes
        is ThemeRead.Failure -> {
            if (result.reason != ThemeReadFailure.MISSING && result.reason != ThemeReadFailure.EMPTY) {
                warn("custom.css", result, "no override")
            }
            ByteArray(0)
        }
    }

    private fun tryWarn(): Boolean {
        val now = nanoTime()
        val next = nextWarningAt.get()
        if (now - next < 0) return false
        return nextWarningAt.compareAndSet(next, now + WARNING_WINDOW_NANOS)
    }

    private fun warn(subject: String, failure: ThemeRead.Failure, serving: String) {
        if (tryWarn()) {
            val hidden = suppressed.getAndSet(0)
            logger.warn {
                val message = "Theme stylesheet $subject ${failure.reason}; serving $serving"
                if (hidden > 0) "$message ($hidden more theme stylesheet failures suppressed since the last warning)" else message
            }
        } else {
            suppressed.incrementAndGet()
        }
    }

    internal fun read(leafPath: Path): ThemeRead {
        val attributes = try {
            Files.readAttributes(leafPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return ThemeRead.Failure(ThemeReadFailure.MISSING)
        } catch (_: IOException) {
            return ThemeRead.Failure(ThemeReadFailure.UNREADABLE)
        }
        if (!attributes.isRegularFile) return ThemeRead.Failure(ThemeReadFailure.NOT_REGULAR)
        val bytes = ByteArray(CAP + 1)
        val buffer = ByteBuffer.wrap(bytes)
        val channel = try {
            opener.open(leafPath)
        } catch (_: NoSuchFileException) {
            return ThemeRead.Failure(ThemeReadFailure.MISSING)
        } catch (_: IOException) {
            return ThemeRead.Failure(ThemeReadFailure.UNREADABLE)
        }
        try {
            channel.use { opened ->
                while (buffer.hasRemaining()) {
                    if (opened.read(buffer) < 0) break
                }
            }
        } catch (_: IOException) {
            return ThemeRead.Failure(ThemeReadFailure.UNREADABLE)
        }
        val count = buffer.position()
        if (count > CAP) return ThemeRead.Failure(ThemeReadFailure.OVERSIZE)
        val bomLength = if (count >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        if (!isStrictUtf8(bytes, bomLength, count)) return ThemeRead.Failure(ThemeReadFailure.INVALID_UTF8)
        val empty = (bomLength until count).all { index ->
            when (bytes[index].toInt()) {
                0x09, 0x0A, 0x0C, 0x0D, 0x20 -> true
                else -> false
            }
        }
        if (empty) return ThemeRead.Failure(ThemeReadFailure.EMPTY)
        return ThemeRead.Bytes(bytes.copyOfRange(bomLength, count))
    }

    private fun isStrictUtf8(bytes: ByteArray, offset: Int, count: Int): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, offset, count - offset)
        val output = CharBuffer.allocate(UTF8_VALIDATION_BUFFER_SIZE)
        while (true) {
            val result = decoder.decode(input, output, true)
            if (result.isError) return false
            if (result.isUnderflow) break
            output.clear()
        }
        while (true) {
            val result = decoder.flush(output)
            if (result.isError) return false
            if (result.isUnderflow) break
            output.clear()
        }
        return true
    }

    companion object {
        private const val CAP = 262_144
        private const val UTF8_VALIDATION_BUFFER_SIZE = 4096
        private const val WARNING_WINDOW_NANOS = 60_000_000_000L
        private val logger = KotlinLogging.logger {}
    }
}

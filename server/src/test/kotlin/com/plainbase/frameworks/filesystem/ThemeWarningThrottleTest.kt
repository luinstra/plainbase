package com.plainbase.frameworks.filesystem

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.theming.ThemeBuiltins
import com.plainbase.domain.theming.ThemeLeaf
import com.plainbase.domain.theming.ThemeMode
import com.plainbase.domain.theming.ThemeSelection
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ThemeWarningThrottleTest : FunSpec({
    val builtins = ThemeBuiltins("light builtin".encodeToByteArray(), "dark builtin".encodeToByteArray())
    val paper = requireNotNull(ThemeLeaf.of("paper.css"))
    val night = requireNotNull(ThemeLeaf.of("night.css"))
    val selection = ThemeSelection(paper, night)
    val css = "body { color: purple; }".encodeToByteArray()
    val second = 1_000_000_000L
    val lightWarning = "Theme stylesheet theme.light paper.css MISSING; serving the bundled definition"
    val darkWarning = "Theme stylesheet theme.dark night.css INVALID_UTF8; serving the bundled definition"

    fun withDataDir(block: (Path) -> Unit) {
        val data = Files.createTempDirectory("pb-theme-warning")
        try {
            block(data)
        } finally {
            Files.walk(data).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun adapter(data: Path, clock: AtomicLong): LocalThemeStylesheets =
        LocalThemeStylesheets(data, selection, builtins, nanoTime = clock::get)

    fun plantOtherFailures(data: Path) {
        Files.write(data.resolve("night.css"), byteArrayOf(0xFF.toByte()))
        Files.write(data.resolve("custom.css"), ByteArray(262_145) { 'x'.code.toByte() })
    }

    test("the first eligible failure logs one warning immediately") {
        withDataDir { data ->
            val themes = adapter(data, AtomicLong())
            val events = captureThemeWarnings { themes.definition(ThemeMode.LIGHT) shouldBe builtins.light }
            assertSoftly {
                events.map { it.level } shouldBe listOf(Level.WARN)
                events.map { it.formattedMessage } shouldBe listOf(lightWarning)
            }
        }
    }

    test("an override failure names custom.css once") {
        withDataDir { data ->
            plantOtherFailures(data)
            val events = captureThemeWarnings { adapter(data, AtomicLong()).override() shouldBe ByteArray(0) }
            events.map { it.formattedMessage } shouldBe listOf("Theme stylesheet custom.css OVERSIZE; serving no override")
        }
    }

    test("the shared warning boundary is inclusive at sixty seconds") {
        withDataDir { data ->
            val clock = AtomicLong()
            val themes = adapter(data, clock)
            val counts = mutableListOf<Int>()
            val events = captureThemeWarnings { captured ->
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
                clock.set(60 * second - 1)
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
                clock.set(60 * second)
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
            }
            assertSoftly {
                counts shouldBe listOf(1, 1, 2)
                events.map { it.formattedMessage } shouldBe listOf(
                    lightWarning,
                    "$lightWarning (1 more theme stylesheet failures suppressed since the last warning)",
                )
            }
        }
    }

    test("dark light and override failures share one warning window") {
        withDataDir { data ->
            plantOtherFailures(data)
            val clock = AtomicLong()
            val themes = adapter(data, clock)
            val events = captureThemeWarnings {
                themes.definition(ThemeMode.DARK)
                clock.set(second)
                themes.definition(ThemeMode.LIGHT)
                clock.set(2 * second)
                themes.override()
            }
            events.map { it.formattedMessage } shouldBe listOf(darkWarning)
        }
    }

    test("a successful read leaves the first warning deadline intact") {
        withDataDir { data ->
            val clock = AtomicLong()
            val themes = adapter(data, clock)
            val counts = mutableListOf<Int>()
            val events = captureThemeWarnings { captured ->
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
                clock.set(5 * second)
                themes.definition(ThemeMode.LIGHT)
                clock.set(10 * second)
                Files.write(data.resolve("paper.css"), css)
                themes.definition(ThemeMode.LIGHT) shouldBe css
                Files.delete(data.resolve("paper.css"))
                clock.set(30 * second)
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
                clock.set(60 * second)
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
            }
            assertSoftly {
                counts shouldBe listOf(1, 1, 2)
                events.map { it.formattedMessage } shouldBe listOf(
                    lightWarning,
                    "$lightWarning (2 more theme stylesheet failures suppressed since the last warning)",
                )
            }
        }
    }

    test("a missing override consumes no warning opportunity") {
        withDataDir { data ->
            val themes = adapter(data, AtomicLong())
            val events = captureThemeWarnings {
                themes.override() shouldBe ByteArray(0)
                themes.definition(ThemeMode.LIGHT) shouldBe builtins.light
            }
            events.map { it.formattedMessage } shouldBe listOf(lightWarning)
        }
    }

    test("suppressed failures are reported once and restart in the next window") {
        withDataDir { data ->
            plantOtherFailures(data)
            val clock = AtomicLong()
            val themes = adapter(data, clock)
            val counts = mutableListOf<Int>()
            val events = captureThemeWarnings { captured ->
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
                clock.set(second)
                themes.definition(ThemeMode.DARK)
                clock.set(2 * second)
                themes.override()
                clock.set(3 * second)
                Files.delete(data.resolve("custom.css"))
                themes.override()
                counts += captured.size
                clock.set(60 * second)
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
                clock.set(61 * second)
                themes.definition(ThemeMode.DARK)
                clock.set(120 * second)
                themes.definition(ThemeMode.LIGHT)
                counts += captured.size
            }
            assertSoftly {
                counts shouldBe listOf(1, 1, 2, 3)
                events.getOrNull(0)?.formattedMessage shouldBe lightWarning
                events.getOrNull(1)?.formattedMessage shouldBe
                    "$lightWarning (2 more theme stylesheet failures suppressed since the last warning)"
                events.getOrNull(2)?.formattedMessage shouldBe
                    "$lightWarning (1 more theme stylesheet failures suppressed since the last warning)"
            }
        }
    }

    test("concurrent eligible failures elect one warning across fifty fresh adapters") {
        withDataDir { data ->
            val workers = Executors.newFixedThreadPool(16)
            try {
                repeat(50) {
                    val themes = adapter(data, AtomicLong())
                    val barrier = CyclicBarrier(16)
                    val events = captureThemeWarnings {
                        val futures = (1..16).map {
                            workers.submit {
                                barrier.await(10, TimeUnit.SECONDS)
                                themes.definition(ThemeMode.LIGHT)
                            }
                        }
                        futures.forEach { it.get(10, TimeUnit.SECONDS) }
                    }
                    events.size shouldBe 1
                    events.map { it.level } shouldBe listOf(Level.WARN)
                }
            } finally {
                workers.shutdownNow()
                workers.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    test("warnings exclude filesystem paths stylesheet bytes and attached throwables") {
        withDataDir { data ->
            val clock = AtomicLong()
            val path = data.resolve("paper.css")
            val invalid = "zz-sentinel-f6".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 'x'.code.toByte())
            invalid.size shouldBe 16
            Files.write(path, invalid)
            val opener = mockk<ThemeChannelOpener>()
            every { opener.open(path) } answers { ThemeChannelOpener.NOFOLLOW.open(path) }
            val themes = LocalThemeStylesheets(data, selection, builtins, opener, clock::get)
            val declaredPath = data.toString()
            val realPath = data.toRealPath().toString()
            val events = captureThemeWarnings {
                themes.definition(ThemeMode.LIGHT)
                clock.set(60 * second)
                Files.delete(path)
                themes.definition(ThemeMode.LIGHT)
                clock.set(120 * second)
                Files.write(path, css)
                every { opener.open(path) } throws IOException(path.toString())
                themes.definition(ThemeMode.LIGHT)
            }
            verify(exactly = 2) { opener.open(path) }
            val expected = listOf(
                "Theme stylesheet theme.light paper.css INVALID_UTF8; serving the bundled definition",
                lightWarning,
                "Theme stylesheet theme.light paper.css UNREADABLE; serving the bundled definition",
            )
            assertSoftly {
                events.size shouldBe 3
                expected.forEachIndexed { index, message ->
                    val event = events.getOrNull(index)
                    event?.formattedMessage shouldBe message
                    event?.throwableProxy shouldBe null
                    listOf(event?.formattedMessage.orEmpty(), event?.throwableProxy?.message.orEmpty()).forEach { text ->
                        text shouldNotContain declaredPath
                        text shouldNotContain realPath
                        text shouldNotContain "zz-sentinel"
                    }
                }
            }
        }
    }
})

internal fun captureThemeWarnings(block: (List<ILoggingEvent>) -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(LocalThemeStylesheets::class.java) as Logger
    val previousLevel = logger.level
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.level = Level.WARN
    logger.addAppender(appender)
    try {
        block(appender.list)
    } finally {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
    return appender.list.toList()
}

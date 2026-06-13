package com.plainbase.frameworks.filesystem

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Native-image smoke for the §B1 watcher: `WatchService` is plain JDK I/O and is EXPECTED to work
 * under native-image, but the gate proves it rather than assumes it (§B1) — without this test the
 * closed-world image would not even compile the `FileWatcher`/`ContentStore.watch` path. One watch
 * over a temp tree, one file touched, one callback: the schedule trigger fires natively.
 *
 * @Tag("native") + kotlin.test only — this source set compiles INTO the native test image. The
 * generous timeout covers macOS's polling WatchService when the JVM test task runs this suite.
 */
@Tag("native")
class FileWatcherNativeTest {

    @Test
    fun `watching a temp tree sees a touched file and would schedule a rebuild`() {
        val root = Files.createTempDirectory("pb-native-watch")
        try {
            val fired = CountDownLatch(1)
            LocalContentStore(root).watch { fired.countDown() }.use {
                Files.writeString(root.resolve("touched.md"), "# Touched\n")
                assertTrue(fired.await(90, TimeUnit.SECONDS), "watch event never arrived")
            }
        } finally {
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

package com.plainbase

import com.plainbase.frameworks.cli.CommandOutput
import com.plainbase.frameworks.cli.WriteIntent
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.lifecycle.ServerRunControl
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.S3ObjectClient
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.search.SearchDb
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val NATIVE_FIXTURE_RUN_DEADLINE_MILLIS = 30_000L
private const val NATIVE_FIXTURE_CLEANUP_DEADLINE_MILLIS = 10_000L

/** Native production-seam twin for the four lock/preparation effect cases. */
@Tag("native")
class ServerPreLockEffectsNativeTest {

    @Test
    fun `OBJECT absent database stays untouched under a held lock`() {
        withNativeFixture("object-absent") { content, data ->
            val mirror = Files.createDirectories(data.resolve("mirror"))
            Files.writeString(mirror.resolve("sentinel.md"), "must remain untouched")
            val mirrorBefore = nativeTreeSnapshot(mirror)
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = NativeOpenStats()
            val lock = assertNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = runServer(
                    nativeObjectConfig(content, data),
                    NativePreLockOutput,
                    nativeOpeners(stats),
                    nativeControl(data, stats),
                )

                assertEquals(1, status)
                assertEquals(0, stats.driverOpens.get())
                assertEquals(0, stats.objectOpens.get())
                assertEquals(0, stats.searchOpens.get())
                assertEquals(1, stats.contextCloses.get())
                assertEquals(beforeObject, ObjectContentStore.constructions.get())
                assertEquals(beforeS3, S3ObjectClient.constructions.get())
                assertFalse(Files.exists(data.resolve("plainbase.db")))
                nativeAssertByteMapsEqual(mirrorBefore, nativeTreeSnapshot(mirror))
            } finally {
                lock.close()
            }
        }
    }

    @Test
    fun `OBJECT prior schema stays untouched under a held lock`() {
        withNativeFixture("object-prior") { content, data ->
            nativeSeedV17(data.resolve("plainbase.db"))
            nativeSeedSearch(data.resolve("search.db"))
            val appBefore = nativeFileFamily(data, "plainbase.db")
            val searchBefore = nativeFileFamily(data, "search.db")
            val schemaBefore = nativeV17Signature(data.resolve("plainbase.db"))
            val mirror = Files.createDirectories(data.resolve("mirror"))
            Files.writeString(mirror.resolve("sentinel.md"), "must remain untouched")
            val mirrorBefore = nativeTreeSnapshot(mirror)
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = NativeOpenStats()
            val lock = assertNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = runServer(
                    nativeObjectConfig(content, data),
                    NativePreLockOutput,
                    nativeOpeners(stats),
                    nativeControl(data, stats),
                )

                assertEquals(1, status)
                assertEquals(0, stats.driverOpens.get())
                assertEquals(0, stats.objectOpens.get())
                assertEquals(0, stats.searchOpens.get())
                assertEquals(1, stats.contextCloses.get())
                assertEquals(beforeObject, ObjectContentStore.constructions.get())
                assertEquals(beforeS3, S3ObjectClient.constructions.get())
                nativeAssertByteMapsEqual(appBefore, nativeFileFamily(data, "plainbase.db"))
                nativeAssertByteMapsEqual(searchBefore, nativeFileFamily(data, "search.db"))
                assertEquals(schemaBefore, nativeV17Signature(data.resolve("plainbase.db")))
                nativeAssertByteMapsEqual(mirrorBefore, nativeTreeSnapshot(mirror))
            } finally {
                lock.close()
            }
        }
    }

    @Test
    fun `LOCAL retained key swap refuses without opening the app`() {
        withNativeSwapFixture("local-swap-refused") { swap, content, data ->
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = NativeOpenStats()
            val lock = assertNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = runServer(
                    nativeLocalConfig(content, data),
                    NativePreLockOutput,
                    swap.openers(stats),
                    nativeControl(data, stats),
                )

                assertEquals(1, status)
                nativeAssertDistinctKeys(swap)
                assertNotNull(swap.store)
                assertEquals(0, stats.driverOpens.get())
                assertEquals(0, stats.objectOpens.get())
                assertEquals(0, stats.searchOpens.get())
                assertEquals(1, stats.contextCloses.get())
                assertEquals(beforeObject, ObjectContentStore.constructions.get())
                assertEquals(beforeS3, S3ObjectClient.constructions.get())
            } finally {
                lock.close()
            }
        }
    }

    @Test
    fun `LOCAL retained key swap reaches the lock-owned handoff`() {
        withNativeSwapFixture("local-swap-acquired") { swap, content, data ->
            val stats = NativeOpenStats()
            val status = runServer(
                nativeLocalConfig(content, data),
                NativePreLockOutput,
                swap.openers(stats),
                nativeControl(data, stats),
            )

            assertEquals(0, status)
            nativeAssertDistinctKeys(swap)
            assertNotNull(swap.store)
            assertEquals(1, stats.driverOpens.get())
            assertEquals(1, stats.driverCloses.get())
            assertEquals(1, stats.searchOpens.get())
            assertEquals(1, stats.searchCloses.get())
            assertEquals(1, stats.contextCloses.get())
        }
    }
}

private object NativePreLockOutput : CommandOutput {
    override fun result(text: String, newline: Boolean) = Unit

    override fun error(text: String) = Unit

    override fun intent(event: WriteIntent) = Unit
}

private class NativeOpenStats {
    val driverOpens = AtomicInteger()
    val driverCloses = AtomicInteger()
    val objectOpens = AtomicInteger()
    val searchOpens = AtomicInteger()
    val searchCloses = AtomicInteger()
    val contextCloses = AtomicInteger()
}

private class NativeSwapFixture(
    private val base: Path,
    private val replacement: Path,
) {
    var store: LocalContentStore? = null
    var originalKey: Any? = null
    var replacementKey: Any? = null

    fun openers(stats: NativeOpenStats): ServerOpeners {
        val defaults = ServerOpeners()
        return ServerOpeners(
            openDriver = { path ->
                stats.driverOpens.incrementAndGet()
                defaults.openDriver(path)
            },
            openLocal = { inputs ->
                val opened = defaults.openLocal(inputs)
                store = opened
                originalKey = nativeFileKey(inputs.root)
                Files.move(inputs.root, base.resolve("original-retained"))
                Files.move(replacement, inputs.root)
                replacementKey = nativeFileKey(inputs.root)
                opened
            },
            openObject = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
                stats.objectOpens.incrementAndGet()
                defaults.openObject(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
            },
            openSearch = { path ->
                stats.searchOpens.incrementAndGet()
                defaults.openSearch(path)
            },
        )
    }
}

private fun nativeOpeners(stats: NativeOpenStats): ServerOpeners {
    val defaults = ServerOpeners()
    return ServerOpeners(
        openDriver = { path ->
            stats.driverOpens.incrementAndGet()
            defaults.openDriver(path)
        },
        openObject = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
            stats.objectOpens.incrementAndGet()
            defaults.openObject(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
        },
        openSearch = { path ->
            stats.searchOpens.incrementAndGet()
            defaults.openSearch(path)
        },
    )
}

private fun nativeControl(data: Path, stats: NativeOpenStats): ServerRunControl = ServerRunControl(
    startServer = {},
    closeDriver = { driver ->
        nativeRequireLockHeld(data)
        stats.driverCloses.incrementAndGet()
        driver.close()
    },
    closeObject = { store ->
        nativeRequireLockHeld(data)
        store.close()
    },
    closeSearch = { search ->
        nativeRequireLockHeld(data)
        stats.searchCloses.incrementAndGet()
        search.close()
    },
    closeContext = { app ->
        nativeRequireLockHeld(data)
        stats.contextCloses.incrementAndGet()
        app.close()
    },
)

private fun nativeLocalConfig(content: Path, data: Path): PlainbaseConfig = PlainbaseConfig(
    contentDir = content,
    dataDir = data,
    host = "127.0.0.1",
    port = nativeFreePort(),
    auth = AuthConfig(),
    git = GitConfig(enabled = false),
)

private fun nativeObjectConfig(content: Path, data: Path): PlainbaseConfig = ConfigLoader.fromEnv(
    mapOf(
        "CONTENT_DIR" to content.toString(),
        "DATA_DIR" to data.toString(),
        "PLAINBASE_STORAGE_BACKEND" to "object",
        "PLAINBASE_S3_ENDPOINT" to "https://127.0.0.1:${nativeFreePort()}",
        "PLAINBASE_S3_BUCKET" to "docs",
        "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
        "PLAINBASE_GIT_ENABLED" to "false",
        "PLAINBASE_HOST" to "127.0.0.1",
        "PLAINBASE_PORT" to nativeFreePort().toString(),
    ),
)

private fun withNativeFixture(prefix: String, block: (content: Path, data: Path) -> Unit) {
    withNativeBoundedBase("plainbase-prelock-native-$prefix") { base ->
        val content = Files.createDirectory(base.resolve("content"))
        val data = Files.createDirectory(base.resolve("data"))
        Files.writeString(content.resolve("index.md"), "# Page\n\nA native pre-lock fixture.\n")
        block(content, data)
    }
}

private fun withNativeSwapFixture(
    prefix: String,
    block: (swap: NativeSwapFixture, content: Path, data: Path) -> Unit,
) {
    withNativeBoundedBase("plainbase-prelock-native-$prefix") { base ->
        val content = Files.createDirectory(base.resolve("content"))
        val replacement = Files.createDirectory(base.resolve("replacement"))
        val data = Files.createDirectory(base.resolve("data"))
        Files.writeString(content.resolve("index.md"), "# Old\n")
        Files.writeString(replacement.resolve("index.md"), "# Replacement\n")
        block(NativeSwapFixture(base, replacement), content, data)
    }
}

private fun withNativeBoundedBase(prefix: String, block: (Path) -> Unit) {
    val base = Files.createTempDirectory(prefix)
    val result = AtomicReference<Result<Unit>?>(null)
    val worker = thread(start = false, isDaemon = true, name = "plainbase-prelock-native-fixture") {
        result.set(runCatching { block(base) })
    }
    try {
        worker.start()
        val runWait = nativeJoinSafely(worker, NATIVE_FIXTURE_RUN_DEADLINE_MILLIS)
        if (runWait.interrupted) {
            worker.interrupt()
            val cleanupWait = nativeJoinSafely(worker, NATIVE_FIXTURE_CLEANUP_DEADLINE_MILLIS)
            val failure = InterruptedException("native pre-lock fixture wait was interrupted")
            if (!cleanupWait.stopped) {
                failure.addSuppressed(TimeoutException("native pre-lock fixture worker survived interrupted cleanup"))
            }
            throw failure
        }
        if (!runWait.stopped) {
            worker.interrupt()
            val cleanupWait = nativeJoinSafely(worker, NATIVE_FIXTURE_CLEANUP_DEADLINE_MILLIS)
            val failure = TimeoutException("native pre-lock fixture exceeded its 30s run bound")
            if (!cleanupWait.stopped) {
                failure.addSuppressed(TimeoutException("native pre-lock fixture worker survived its 40s bound"))
            }
            throw failure
        }
        assertNotNull(result.get()).getOrThrow()
    } finally {
        if (!worker.isAlive) base.toFile().deleteRecursively()
    }
}

private data class NativeJoinResult(val stopped: Boolean, val interrupted: Boolean)

private fun nativeJoinSafely(worker: Thread, timeoutMillis: Long): NativeJoinResult {
    val initiallyInterrupted = Thread.interrupted()
    var interrupted = initiallyInterrupted
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (worker.isAlive && System.nanoTime() < deadline) {
        val remaining = deadline - System.nanoTime()
        try {
            worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
    return NativeJoinResult(!worker.isAlive, interrupted)
}

private data class NativeV17Signature(val userVersion: Long, val columns: List<String>, val observation: Long)

private fun nativeSeedV17(path: Path) {
    DriverManager.getConnection("jdbc:sqlite:$path").use { raw ->
        raw.createStatement().use {
            it.execute("CREATE TABLE root_observation (root TEXT NOT NULL PRIMARY KEY, observation_id INTEGER NOT NULL)")
            it.execute("INSERT INTO root_observation(root, observation_id) VALUES ('docs', 100)")
            it.execute("PRAGMA user_version = 17")
        }
    }
}

private fun nativeSeedSearch(path: Path) {
    SearchDb(path).close()
}

private fun nativeV17Signature(path: Path): NativeV17Signature = nativeReadOnlySqliteConnection(path).use { raw ->
    val version = raw.createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }
    val columns = raw.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info(root_observation)").use { result ->
            buildList {
                while (result.next()) add(result.getString("name"))
            }
        }
    }
    val observation = raw.createStatement().use { statement ->
        statement.executeQuery("SELECT observation_id FROM root_observation WHERE root = 'docs'").use { result ->
            result.next()
            result.getLong(1)
        }
    }
    NativeV17Signature(version, columns, observation)
}

private fun nativeReadOnlySqliteConnection(path: Path) =
    DriverManager.getConnection("jdbc:sqlite:file:${path.toAbsolutePath().normalize()}?mode=ro")

private fun nativeFileFamily(dir: Path, stem: String): Map<String, ByteArray> =
    listOf(stem, "$stem-wal", "$stem-shm", "$stem-journal")
        .filter { Files.exists(dir.resolve(it)) }
        .associateWith { Files.readAllBytes(dir.resolve(it)) }

private fun nativeTreeSnapshot(root: Path): Map<String, ByteArray> =
    Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .map { root.relativize(it).toString() to Files.readAllBytes(it) }
            .toList()
            .toMap()
    }

private fun nativeAssertByteMapsEqual(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
    assertEquals(expected.keys, actual.keys)
    expected.forEach { (path, bytes) ->
        assertTrue(bytes.contentEquals(requireNotNull(actual[path]) { "missing snapshot entry $path" }))
    }
}

private fun nativeFileKey(path: Path): Any = requireNotNull(
    Files.readAttributes(path, BasicFileAttributes::class.java).fileKey(),
) { "the native fixture filesystem does not expose directory file keys" }

private fun nativeAssertDistinctKeys(swap: NativeSwapFixture) {
    assertNotNull(swap.originalKey)
    assertNotNull(swap.replacementKey)
    assertTrue(swap.originalKey != swap.replacementKey)
}

private fun nativeRequireLockHeld(data: Path) {
    val attempt = DataDirLock.tryAcquire(data)
    if (attempt != null) {
        attempt.close()
        error("DATA_DIR lock was released before native resource cleanup")
    }
}

private fun nativeFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }

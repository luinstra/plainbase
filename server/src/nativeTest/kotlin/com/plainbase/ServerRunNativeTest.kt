package com.plainbase

import com.plainbase.frameworks.cli.CommandOutput
import com.plainbase.frameworks.cli.WriteIntent
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.lifecycle.ServerRunControl
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.runtime.ServerOpeners
import org.junit.jupiter.api.Tag
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Native runtime twin: the same run-owned seam executes without a process exit or global Koin context. */
@Tag("native")
class ServerRunNativeTest {

    @Test
    fun `a local server run can return naturally from the native image`() {
        withFixture { content, data ->
            var hook: Thread? = null
            val driverCloses = AtomicInteger()
            val searchCloses = AtomicInteger()
            val contextCloses = AtomicInteger()
            val status = runServer(
                config(content, data),
                NativeOutput,
                control = ServerRunControl(
                    startServer = {},
                    onHookInstalled = { hook = it },
                    closeDriver = { driver ->
                        requireLockHeld(data)
                        driverCloses.incrementAndGet()
                        driver.close()
                    },
                    closeSearch = { search ->
                        requireLockHeld(data)
                        searchCloses.incrementAndGet()
                        search.close()
                    },
                    closeContext = { app ->
                        requireLockHeld(data)
                        contextCloses.incrementAndGet()
                        app.close()
                    },
                ),
            )

            assertEquals(0, status)
            assertNotNull(hook)
            assertEquals(1, driverCloses.get())
            assertEquals(1, searchCloses.get())
            assertEquals(1, contextCloses.get())
            requireLockAvailable(data)
        }
    }

    @Test
    fun `an object run defers construction while the native data lock remains held`() {
        withBoundedFixtureBase("plainbase-server-run-native-object") { base ->
            val data = Files.createDirectory(base.resolve("data"))
            val content = base.resolve("unused-content")
            val mirror = Files.createDirectory(data.resolve("mirror"))
            val sentinel = mirror.resolve("sentinel.md")
            Files.writeString(sentinel, "must remain untouched")
            val mirrorBefore = Files.readAllBytes(sentinel)
            val endpointPort = freePort()
            val objectConfig = objectConfigFromEnv(content, data, endpointPort)
            val before = ObjectContentStore.constructions.get()
            val defaults = ServerOpeners()
            val driverOpens = AtomicInteger()
            val driverCloses = AtomicInteger()
            val objectOpens = AtomicInteger()
            val objectCloses = AtomicInteger()
            val searchOpens = AtomicInteger()
            val searchCloses = AtomicInteger()
            val contextCloses = AtomicInteger()
            val held = assertNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = runServer(
                    objectConfig,
                    NativeOutput,
                    openers = ServerOpeners(
                        openDriver = { path ->
                            driverOpens.incrementAndGet()
                            defaults.openDriver(path)
                        },
                        openObject = { cfg, ignore, dirty, isDirty, rows ->
                            objectOpens.incrementAndGet()
                            defaults.openObject(cfg, ignore, dirty, isDirty, rows)
                        },
                        openSearch = { path ->
                            searchOpens.incrementAndGet()
                            defaults.openSearch(path)
                        },
                    ),
                    control = ServerRunControl(
                        closeDriver = { driver ->
                            requireLockHeld(data)
                            driverCloses.incrementAndGet()
                            driver.close()
                        },
                        closeObject = { store ->
                            requireLockHeld(data)
                            objectCloses.incrementAndGet()
                            store.close()
                        },
                        closeSearch = { search ->
                            requireLockHeld(data)
                            searchCloses.incrementAndGet()
                            search.close()
                        },
                        closeContext = { app ->
                            requireLockHeld(data)
                            contextCloses.incrementAndGet()
                            app.close()
                        },
                    ),
                )

                assertEquals(1, status)
                assertEquals(0, driverOpens.get())
                assertEquals(0, driverCloses.get())
                assertEquals(0, objectOpens.get())
                assertEquals(0, objectCloses.get())
                assertEquals(0, searchOpens.get())
                assertEquals(0, searchCloses.get())
                assertEquals(1, contextCloses.get())
                assertEquals(before, ObjectContentStore.constructions.get())
                assertEquals(false, Files.exists(data.resolve("plainbase.db")))
                assertTrue(Files.readAllBytes(sentinel).contentEquals(mirrorBefore))
            } finally {
                held.close()
            }
            requireLockAvailable(data)
        }
    }
}

private val NativeOutput = object : CommandOutput {
    override fun result(text: String, newline: Boolean) = Unit
    override fun error(text: String) = Unit
    override fun intent(event: WriteIntent) = Unit
}

private fun config(content: Path, data: Path) = PlainbaseConfig(
    contentDir = content,
    dataDir = data,
    host = "127.0.0.1",
    port = freePort(),
    auth = AuthConfig(),
    git = GitConfig(enabled = false),
)

private fun objectConfigFromEnv(content: Path, data: Path, endpointPort: Int): PlainbaseConfig =
    PlainbaseConfig.fromEnv(
        mapOf(
            "CONTENT_DIR" to content.toString(),
            "DATA_DIR" to data.toString(),
            "PLAINBASE_STORAGE_BACKEND" to "object",
            "PLAINBASE_S3_ENDPOINT" to "https://127.0.0.1:$endpointPort",
            "PLAINBASE_S3_BUCKET" to "docs",
            "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
            "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
            "PLAINBASE_GIT_ENABLED" to "false",
            "PLAINBASE_HOST" to "127.0.0.1",
            "PLAINBASE_PORT" to freePort().toString(),
        ),
    )

private fun withFixture(block: (content: Path, data: Path) -> Unit) {
    withBoundedFixtureBase("plainbase-server-run-native") { base ->
        val content = Files.createDirectory(base.resolve("content"))
        val data = Files.createDirectory(base.resolve("data"))
        Files.writeString(content.resolve("readme.md"), "---\ntitle: Readme\n---\n\n# Readme\n")
        block(content, data)
    }
}

private fun withBoundedFixtureBase(prefix: String, block: (Path) -> Unit) {
    val base = Files.createTempDirectory(prefix)
    val result = AtomicReference<Result<Unit>?>(null)
    val worker = thread(start = false, isDaemon = true, name = "plainbase-native-fixture") {
        result.set(
            runCatching {
                block(base)
            },
        )
    }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NATIVE_FIXTURE_RUN_DEADLINE_MILLIS)
    try {
        worker.start()
        joinUntil(worker, deadline)
        val timedOut = worker.isAlive
        if (timedOut) {
            worker.interrupt()
            joinUntil(
                worker,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NATIVE_FIXTURE_CLEANUP_DEADLINE_MILLIS),
            )
        }
        if (timedOut) {
            val timeout = TimeoutException("native fixture run exceeded ${NATIVE_FIXTURE_RUN_DEADLINE_MILLIS}ms")
            result.get()?.exceptionOrNull()?.let(timeout::addSuppressed)
            if (worker.isAlive) {
                timeout.addSuppressed(
                    IllegalStateException("native fixture run left a surviving worker: ${worker.name}(${worker.state})"),
                )
            }
            throw timeout
        }
        requireNotNull(result.get()) { "native fixture run completed without a result" }.getOrThrow()
    } finally {
        if (!worker.isAlive) {
            base.toFile().deleteRecursively()
        }
    }
}

private const val NATIVE_FIXTURE_RUN_DEADLINE_MILLIS = 30_000L
private const val NATIVE_FIXTURE_CLEANUP_DEADLINE_MILLIS = 10_000L

private fun joinUntil(worker: Thread, deadline: Long) {
    while (worker.isAlive && System.nanoTime() < deadline) {
        val remaining = deadline - System.nanoTime()
        worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
    }
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun requireLockHeld(data: Path) {
    val attempt = DataDirLock.tryAcquire(data)
    if (attempt != null) {
        attempt.close()
        error("DATA_DIR lock was released before resource cleanup")
    }
}

private fun requireLockAvailable(data: Path) {
    val attempt = DataDirLock.tryAcquire(data) ?: error("DATA_DIR lock was not released")
    attempt.close()
}

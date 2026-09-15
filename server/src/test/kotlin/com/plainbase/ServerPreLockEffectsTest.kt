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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Production-seam evidence for effects that must remain on the lock-owned side of startup. */
class ServerPreLockEffectsTest : FunSpec({

    test("fixture cleanup keeps a timeout sticky and deletes only after a released worker exits") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerResult = AtomicReference<Result<Int>?>()
        val workerFiles = AtomicReference<Pair<Boolean, Boolean>?>()
        var base: Path? = null

        try {
            shouldThrow<TimeoutException> {
                withFixture(
                    prefix = "cleanup-release",
                    runTimeoutMillis = 25,
                    cleanupTimeoutMillis = 25,
                    entryTimeoutMillis = 1_000,
                    onWorkerSurvives = { release.countDown() },
                    onWorkerResult = workerResult::set,
                ) { fixture, content, data ->
                    base = fixture.base
                    fixture.runBounded {
                        entered.countDown()
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            release.await()
                        }
                        workerFiles.set(Files.exists(content) to Files.exists(data))
                        0
                    }
                }
            }
        } finally {
            release.countDown()
        }

        check(entered.await(1, TimeUnit.SECONDS))
        workerFiles.get() shouldBe (true to true)
        requireNotNull(workerResult.get()).isSuccess shouldBe true
        Files.exists(requireNotNull(base)) shouldBe false
    }

    test("fixture entry interruption cleans the worker and restores the parent interrupt") {
        val entryReady = CountDownLatch(1)
        val allowEntry = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val bodyEntered = CountDownLatch(1)
        val workerResult = AtomicReference<Result<Int>?>()
        val parent = Thread.currentThread()
        val interrupter = thread(start = false, isDaemon = true, name = "plainbase-prelock-entry-interrupter") {
            check(entryReady.await(1, TimeUnit.SECONDS))
            parent.interrupt()
        }
        var interruptRestored = false
        var base: Path? = null
        try {
            interrupter.start()
            shouldThrow<InterruptedException> {
                withFixture(
                    prefix = "entry-interruption",
                    entryTimeoutMillis = 1_000,
                    onWorkerSurvives = {
                        allowEntry.countDown()
                        releaseBody.countDown()
                    },
                    onWorkerResult = workerResult::set,
                    beforeWorkerEntry = {
                        entryReady.countDown()
                        try {
                            allowEntry.await()
                        } catch (_: InterruptedException) {
                            allowEntry.await()
                        }
                    },
                ) { fixture, content, data ->
                    base = fixture.base
                    fixture.runBounded {
                        bodyEntered.countDown()
                        releaseBody.await()
                        check(Files.exists(content))
                        check(Files.exists(data))
                        0
                    }
                }
            }
            interruptRestored = Thread.interrupted()
        } finally {
            allowEntry.countDown()
            releaseBody.countDown()
            interrupter.join(1_000)
            Thread.interrupted()
        }

        interruptRestored shouldBe true
        check(bodyEntered.await(1, TimeUnit.SECONDS))
        requireNotNull(workerResult.get()).isSuccess shouldBe true
        Files.exists(requireNotNull(base)) shouldBe false
    }

    test("OBJECT held-lock refusal leaves an absent app database and all resource constructors untouched") {
        withFixture("object-absent") { fixture, content, data ->
            val mirror = Files.createDirectories(data.resolve("mirror"))
            Files.writeString(mirror.resolve("sentinel.md"), "must remain untouched")
            val mirrorBefore = treeSnapshot(mirror)
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = OpenStats()
            val output = CaptureOutput()
            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = fixture.runBounded {
                    runServer(objectConfig(content, data), output, stats.openers(), stats.control(data))
                }

                status shouldBe 1
                output.errors.single() shouldContain "another Plainbase process is holding"
                val observation = capturePreLockObservation(
                    data = data,
                    mirror = mirror,
                    stats = stats,
                    beforeObject = beforeObject,
                    beforeS3 = beforeS3,
                    appBefore = emptyMap(),
                    searchBefore = emptyMap(),
                    mirrorBefore = mirrorBefore,
                    storeReturned = false,
                )
                requireNoPreLockResources(observation)
                observation.appFamily shouldBe emptyMap()
                observation.searchFamily shouldBe emptyMap()
            } finally {
                lock.close()
            }
        }
    }

    test("OBJECT held-lock refusal preserves a real v17 app database and search database byte-for-byte") {
        withFixture("object-prior") { fixture, content, data ->
            seedV17Database(data.resolve("plainbase.db"))
            seedSearchDatabase(data.resolve("search.db"))
            val mirror = Files.createDirectories(data.resolve("mirror"))
            Files.writeString(mirror.resolve("sentinel.md"), "must remain untouched")
            val appBefore = fileFamily(data, "plainbase.db")
            val searchBefore = fileFamily(data, "search.db")
            val appSchemaBefore = v17Signature(data.resolve("plainbase.db"))
            val mirrorBefore = treeSnapshot(mirror)
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = OpenStats()
            val output = CaptureOutput()
            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = fixture.runBounded {
                    runServer(objectConfig(content, data), output, stats.openers(), stats.control(data))
                }

                status shouldBe 1
                output.errors.single() shouldContain "another Plainbase process is holding"
                val observation = capturePreLockObservation(
                    data = data,
                    mirror = mirror,
                    stats = stats,
                    beforeObject = beforeObject,
                    beforeS3 = beforeS3,
                    appBefore = appBefore,
                    searchBefore = searchBefore,
                    appSchemaBefore = appSchemaBefore,
                    mirrorBefore = mirrorBefore,
                    storeReturned = false,
                )
                requireNoPreLockResources(observation)
                observation.appFamily shouldBe fingerprintFamily(appBefore)
                observation.searchFamily shouldBe fingerprintFamily(searchBefore)
            } finally {
                lock.close()
            }
        }
    }

    test("objectConstructorFailureOccursOnlyAfterLock refuses before the object opener") {
        withFixture("object-constructor-held") { fixture, content, data ->
            val sentinel = IllegalStateException("object constructor sentinel")
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = OpenStats()
            val output = CaptureOutput()
            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = fixture.runBounded {
                    runServer(
                        objectConfig(content, data),
                        output,
                        stats.openers(
                            ServerOpeners(openObject = { _, _, _, _, _ -> throw sentinel }),
                        ),
                        stats.control(data),
                    )
                }

                status shouldBe 1
                output.errors.single() shouldContain "another Plainbase process is holding"
                stats.driverOpens.get() shouldBe 0
                stats.objectOpens.get() shouldBe 0
                stats.searchOpens.get() shouldBe 0
                stats.contextCloses.get() shouldBe 1
                ObjectContentStore.constructions.get() - beforeObject shouldBe 0
                S3ObjectClient.constructions.get() - beforeS3 shouldBe 0
            } finally {
                lock.close()
            }
            requireNotNull(DataDirLock.tryAcquire(data)).close()
        }
    }

    test("objectConstructorFailureOccursOnlyAfterLock invokes the object opener after acquisition") {
        withFixture("object-constructor-acquired") { fixture, content, data ->
            val sentinel = IllegalStateException("object constructor sentinel")
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = OpenStats()
            val failure = shouldThrow<IllegalStateException> {
                fixture.runBounded {
                    runServer(
                        objectConfig(content, data),
                        CaptureOutput(),
                        stats.openers(
                            ServerOpeners(openObject = { _, _, _, _, _ -> throw sentinel }),
                            lockData = data,
                        ),
                        stats.control(data),
                    )
                }
            }

            failure shouldBeSameInstanceAs sentinel
            stats.driverOpens.get() shouldBe 1
            stats.driverCloses.get() shouldBe 1
            stats.objectOpens.get() shouldBe 1
            stats.searchOpens.get() shouldBe 0
            stats.contextCloses.get() shouldBe 1
            stats.driverOpenLockHeld.get() shouldBe true
            stats.objectOpenLockHeld.get() shouldBe true
            ObjectContentStore.constructions.get() - beforeObject shouldBe 0
            S3ObjectClient.constructions.get() - beforeS3 shouldBe 0
            requireNotNull(DataDirLock.tryAcquire(data)).close()
        }
    }

    test("ordinary LOCAL held-lock refusal preserves an absent app database") {
        withFixture("local-absent") { fixture, content, data ->
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = OpenStats()
            val output = CaptureOutput()
            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = fixture.runBounded {
                    runServer(localConfig(content, data), output, stats.openers(), stats.control(data))
                }

                status shouldBe 1
                output.errors.single() shouldContain "another Plainbase process is holding"
                val observation = capturePreLockObservation(
                    data = data,
                    stats = stats,
                    beforeObject = beforeObject,
                    beforeS3 = beforeS3,
                    appBefore = emptyMap(),
                    searchBefore = emptyMap(),
                    storeReturned = false,
                )
                requireNoPreLockResources(observation)
                observation.appFamily shouldBe emptyMap()
                observation.searchFamily shouldBe emptyMap()
            } finally {
                lock.close()
            }
        }
    }

    test("ordinary LOCAL held-lock refusal preserves a real v17 app database") {
        withFixture("local-prior") { fixture, content, data ->
            seedV17Database(data.resolve("plainbase.db"))
            val before = fileFamily(data, "plainbase.db")
            val schemaBefore = v17Signature(data.resolve("plainbase.db"))
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = OpenStats()
            val output = CaptureOutput()
            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = fixture.runBounded {
                    runServer(localConfig(content, data), output, stats.openers(), stats.control(data))
                }

                status shouldBe 1
                output.errors.single() shouldContain "another Plainbase process is holding"
                val observation = capturePreLockObservation(
                    data = data,
                    stats = stats,
                    beforeObject = beforeObject,
                    beforeS3 = beforeS3,
                    appBefore = before,
                    searchBefore = emptyMap(),
                    appSchemaBefore = schemaBefore,
                    storeReturned = false,
                )
                requireNoPreLockResources(observation)
                observation.appFamily shouldBe fingerprintFamily(before)
            } finally {
                lock.close()
            }
        }
    }

    test("a retained populated LOCAL directory swap reaches the real gate and refuses without app effects") {
        withSwapFixture("local-swap-refused") { fixture, swap, content, data ->
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = OpenStats()
            val output = CaptureOutput()
            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = fixture.runBounded {
                    runServer(
                        localConfig(content, data),
                        output,
                        swap.openers(stats),
                        stats.control(data),
                    )
                }

                status shouldBe 1
                output.errors.single() shouldContain "another Plainbase process is holding"
                val observation = capturePreLockObservation(
                    data = data,
                    stats = stats,
                    beforeObject = beforeObject,
                    beforeS3 = beforeS3,
                    storeReturned = swap.store != null,
                    retainedKey = swap.originalKey,
                    replacementKey = swap.replacementKey,
                )
                requireNoPreLockResources(observation)
                observation.storeReturned shouldBe true
                observation.keysDistinct shouldBe true
            } finally {
                lock.close()
            }
        }
    }

    test("a retained populated LOCAL directory swap preserves prior app and search files under refusal") {
        withSwapFixture("local-swap-refused-prior") { fixture, swap, content, data ->
            seedV17Database(data.resolve("plainbase.db"))
            seedSearchDatabase(data.resolve("search.db"))
            val appBefore = fileFamily(data, "plainbase.db")
            val searchBefore = fileFamily(data, "search.db")
            val appSchemaBefore = v17Signature(data.resolve("plainbase.db"))
            val beforeObject = ObjectContentStore.constructions.get()
            val beforeS3 = S3ObjectClient.constructions.get()
            val stats = OpenStats()
            val output = CaptureOutput()
            val lock = requireNotNull(DataDirLock.tryAcquire(data))
            try {
                val status = fixture.runBounded {
                    runServer(
                        localConfig(content, data),
                        output,
                        swap.openers(stats),
                        stats.control(data),
                    )
                }

                status shouldBe 1
                output.errors.single() shouldContain "another Plainbase process is holding"
                val observation = capturePreLockObservation(
                    data = data,
                    stats = stats,
                    beforeObject = beforeObject,
                    beforeS3 = beforeS3,
                    appBefore = appBefore,
                    searchBefore = searchBefore,
                    appSchemaBefore = appSchemaBefore,
                    storeReturned = swap.store != null,
                    retainedKey = swap.originalKey,
                    replacementKey = swap.replacementKey,
                )
                requireNoPreLockResources(observation)
                observation.appFamily shouldBe fingerprintFamily(appBefore)
                observation.searchFamily shouldBe fingerprintFamily(searchBefore)
                observation.appSchema shouldBe appSchemaBefore
                observation.storeReturned shouldBe true
                observation.keysDistinct shouldBe true
            } finally {
                lock.close()
            }
        }
    }

    test("the same retained populated LOCAL directory swap survives the lock-owned handoff") {
        withSwapFixture("local-swap-acquired") { fixture, swap, content, data ->
            val stats = OpenStats()
            val appBefore = fileFamily(data, "plainbase.db")
            val searchBefore = fileFamily(data, "search.db")
            val status = fixture.runBounded {
                runServer(
                    localConfig(content, data),
                    CaptureOutput(),
                    swap.openers(stats),
                    stats.control(data, startServer = {}),
                )
            }

            status shouldBe 0
            assertDistinctFileKeys(swap)
            check(swap.store != null) { "the real LOCAL opener did not return a store" }
            stats.driverOpens.get() shouldBe 1
            stats.driverCloses.get() shouldBe 1
            stats.searchOpens.get() shouldBe 1
            stats.searchCloses.get() shouldBe 1
            stats.contextCloses.get() shouldBe 1
            check(appBefore.isEmpty()) { "the acquired swap must start without an app database" }
            check(searchBefore.isEmpty()) { "the acquired swap must start without a search database" }
            check(fileFamily(data, "plainbase.db").isNotEmpty()) { "the acquired swap must create the app database" }
            check(fileFamily(data, "search.db").isNotEmpty()) { "the acquired swap must create the search database" }
        }
    }
})

private class OpenStats {
    val driverOpens = AtomicInteger()
    val driverCloses = AtomicInteger()
    val driverOpenLockHeld = AtomicReference<Boolean?>()
    val objectOpens = AtomicInteger()
    val objectOpenLockHeld = AtomicReference<Boolean?>()
    val searchOpens = AtomicInteger()
    val searchCloses = AtomicInteger()
    val contextCloses = AtomicInteger()

    fun openers(defaults: ServerOpeners = ServerOpeners(), lockData: Path? = null): ServerOpeners = ServerOpeners(
        openDriver = { path ->
            driverOpens.incrementAndGet()
            lockData?.let { driverOpenLockHeld.set(lockHeld(it)) }
            defaults.openDriver(path)
        },
        openObject = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
            objectOpens.incrementAndGet()
            lockData?.let { objectOpenLockHeld.set(lockHeld(it)) }
            defaults.openObject(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
        },
        openSearch = { path ->
            searchOpens.incrementAndGet()
            defaults.openSearch(path)
        },
        openLocal = defaults.openLocal,
    )

    fun control(data: Path, startServer: (com.plainbase.frameworks.ktor.KtorServer) -> Unit = {}): ServerRunControl =
        ServerRunControl(
            startServer = startServer,
            closeDriver = { driver ->
                requireLockHeld(data)
                driverCloses.incrementAndGet()
                driver.close()
            },
            closeObject = { store ->
                requireLockHeld(data)
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
        )
}

private class SwapFixture(
    private val base: Path,
    private val replacement: Path,
) {
    var store: LocalContentStore? = null
    var originalKey: Any? = null
    var replacementKey: Any? = null

    fun openers(stats: OpenStats): ServerOpeners {
        val defaults = ServerOpeners()
        return stats.openers(
            ServerOpeners(
                openLocal = { inputs ->
                    val opened = defaults.openLocal(inputs)
                    store = opened
                    originalKey = fileKey(inputs.root)
                    Files.move(inputs.root, base.resolve("original-retained"))
                    Files.move(replacement, inputs.root)
                    replacementKey = fileKey(inputs.root)
                    opened
                },
            ),
        )
    }
}

private class CaptureOutput : CommandOutput {
    val errors = mutableListOf<String>()

    override fun result(text: String, newline: Boolean) = Unit

    override fun error(text: String) {
        errors += text
    }

    override fun intent(event: WriteIntent) = Unit
}

private fun withFixture(
    prefix: String,
    runTimeoutMillis: Long = 30_000L,
    cleanupTimeoutMillis: Long = 10_000L,
    entryTimeoutMillis: Long = 1_000L,
    onWorkerSurvives: () -> Unit = {},
    onWorkerResult: (Result<Int>) -> Unit = {},
    beforeWorkerEntry: () -> Unit = {},
    block: (fixture: FixtureOwner, content: Path, data: Path) -> Unit,
) {
    val base = Files.createTempDirectory("plainbase-prelock-$prefix")
    val fixture = FixtureOwner(
        base = base,
        runTimeoutMillis = runTimeoutMillis,
        cleanupTimeoutMillis = cleanupTimeoutMillis,
        entryTimeoutMillis = entryTimeoutMillis,
        onWorkerSurvives = onWorkerSurvives,
        onWorkerResult = onWorkerResult,
        beforeWorkerEntry = beforeWorkerEntry,
    )
    try {
        val content = Files.createDirectory(base.resolve("content"))
        val data = Files.createDirectory(base.resolve("data"))
        Files.writeString(content.resolve("index.md"), "# Page\n\nA pre-lock fixture.\n")
        block(fixture, content, data)
    } finally {
        fixture.cleanup()
    }
}

private fun withSwapFixture(
    prefix: String,
    block: (fixture: FixtureOwner, swap: SwapFixture, content: Path, data: Path) -> Unit,
) {
    val base = Files.createTempDirectory("plainbase-prelock-$prefix")
    val fixture = FixtureOwner(
        base = base,
        runTimeoutMillis = 30_000L,
        cleanupTimeoutMillis = 10_000L,
        entryTimeoutMillis = 1_000L,
        onWorkerSurvives = {},
        onWorkerResult = {},
        beforeWorkerEntry = {},
    )
    try {
        val original = Files.createDirectory(base.resolve("content"))
        val replacement = Files.createDirectory(base.resolve("replacement"))
        val data = Files.createDirectory(base.resolve("data"))
        Files.writeString(original.resolve("index.md"), "# Old\n")
        Files.writeString(replacement.resolve("index.md"), "# Replacement\n")
        block(fixture, SwapFixture(base, replacement), original, data)
    } finally {
        fixture.cleanup()
    }
}

private fun localConfig(content: Path, data: Path): PlainbaseConfig = PlainbaseConfig(
    contentDir = content,
    dataDir = data,
    host = "127.0.0.1",
    port = freePort(),
    auth = AuthConfig(),
    git = GitConfig(enabled = false),
)

private fun objectConfig(content: Path, data: Path): PlainbaseConfig = ConfigLoader.fromEnv(
    mapOf(
        "CONTENT_DIR" to content.toString(),
        "DATA_DIR" to data.toString(),
        "PLAINBASE_STORAGE_BACKEND" to "object",
        "PLAINBASE_S3_ENDPOINT" to "https://127.0.0.1:${freePort()}",
        "PLAINBASE_S3_BUCKET" to "docs",
        "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
        "PLAINBASE_GIT_ENABLED" to "false",
        "PLAINBASE_HOST" to "127.0.0.1",
        "PLAINBASE_PORT" to freePort().toString(),
    ),
)

private fun seedV17Database(path: Path) {
    DriverManager.getConnection("jdbc:sqlite:$path").use { raw ->
        raw.createStatement().use {
            it.execute("CREATE TABLE root_observation (root TEXT NOT NULL PRIMARY KEY, observation_id INTEGER NOT NULL)")
            it.execute("INSERT INTO root_observation(root, observation_id) VALUES ('docs', 100)")
            it.execute("PRAGMA user_version = 17")
        }
    }
}

private fun seedSearchDatabase(path: Path) {
    SearchDb(path).close()
}

private data class V17Signature(val userVersion: Long, val columns: List<String>, val observation: Long)

private fun v17Signature(path: Path): V17Signature = readOnlySqliteConnection(path).use { raw ->
    val userVersion = raw.createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { result ->
            check(result.next())
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
            check(result.next())
            result.getLong(1)
        }
    }
    V17Signature(userVersion, columns, observation)
}

private fun readOnlySqliteConnection(path: Path) =
    DriverManager.getConnection("jdbc:sqlite:file:${path.toAbsolutePath().normalize()}?mode=ro")

private data class PreLockObservation(
    val driverOpens: Int,
    val objectOpens: Int,
    val searchOpens: Int,
    val contextCloses: Int,
    val objectConstructionDelta: Int,
    val s3ConstructionDelta: Int,
    val appFamily: Map<String, String>,
    val searchFamily: Map<String, String>,
    val appFamilyUnchanged: Boolean,
    val searchFamilyUnchanged: Boolean,
    val appSchema: V17Signature?,
    val appSchemaUnchanged: Boolean,
    val mirrorUnchanged: Boolean?,
    val storeReturned: Boolean,
    val retainedKey: Any?,
    val replacementKey: Any?,
    val keysDistinct: Boolean,
)

private fun capturePreLockObservation(
    data: Path,
    stats: OpenStats,
    beforeObject: Int,
    beforeS3: Int,
    appBefore: Map<String, ByteArray> = emptyMap(),
    searchBefore: Map<String, ByteArray> = emptyMap(),
    appSchemaBefore: V17Signature? = null,
    mirror: Path? = null,
    mirrorBefore: Map<String, ByteArray>? = null,
    storeReturned: Boolean = false,
    retainedKey: Any? = null,
    replacementKey: Any? = null,
): PreLockObservation {
    val appFamily = fileFamily(data, "plainbase.db")
    val searchFamily = fileFamily(data, "search.db")
    val appSchema = appSchemaBefore?.let {
        if (Files.exists(data.resolve("plainbase.db"))) v17Signature(data.resolve("plainbase.db")) else null
    }
    val mirrorUnchanged = mirror?.let { path ->
        check(mirrorBefore != null) { "a mirror path requires a mirror snapshot" }
        fingerprintTree(path) == fingerprintFamily(mirrorBefore)
    }
    return PreLockObservation(
        driverOpens = stats.driverOpens.get(),
        objectOpens = stats.objectOpens.get(),
        searchOpens = stats.searchOpens.get(),
        contextCloses = stats.contextCloses.get(),
        objectConstructionDelta = ObjectContentStore.constructions.get() - beforeObject,
        s3ConstructionDelta = S3ObjectClient.constructions.get() - beforeS3,
        appFamily = fingerprintFamily(appFamily),
        searchFamily = fingerprintFamily(searchFamily),
        appFamilyUnchanged = fingerprintFamily(appFamily) == fingerprintFamily(appBefore),
        searchFamilyUnchanged = fingerprintFamily(searchFamily) == fingerprintFamily(searchBefore),
        appSchema = appSchema,
        appSchemaUnchanged = appSchema == appSchemaBefore,
        mirrorUnchanged = mirrorUnchanged,
        storeReturned = storeReturned,
        retainedKey = retainedKey,
        replacementKey = replacementKey,
        keysDistinct = retainedKey != null && replacementKey != null && retainedKey != replacementKey,
    )
}

private fun requireNoPreLockResources(observation: PreLockObservation) {
    check(observation.driverOpens == 0) { "complete pre-lock observation: $observation" }
    check(observation.objectOpens == 0) { "complete pre-lock observation: $observation" }
    check(observation.searchOpens == 0) { "complete pre-lock observation: $observation" }
    check(observation.contextCloses == 1) { "complete pre-lock observation: $observation" }
    check(observation.objectConstructionDelta == 0) { "complete pre-lock observation: $observation" }
    check(observation.s3ConstructionDelta == 0) { "complete pre-lock observation: $observation" }
    check(observation.appFamilyUnchanged) { "complete pre-lock observation: $observation" }
    check(observation.searchFamilyUnchanged) { "complete pre-lock observation: $observation" }
    check(observation.appSchemaUnchanged) { "complete pre-lock observation: $observation" }
    check(observation.mirrorUnchanged != false) { "complete pre-lock observation: $observation" }
}

private fun fingerprintFamily(family: Map<String, ByteArray>): Map<String, String> = family.mapValues { (_, bytes) ->
    sha256(bytes)
}

private fun fingerprintTree(root: Path): Map<String, String> = treeSnapshot(root).let(::fingerprintFamily)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
    "%02x".format(it)
}

private class FixtureOwner(
    val base: Path,
    private val runTimeoutMillis: Long,
    private val cleanupTimeoutMillis: Long,
    private val entryTimeoutMillis: Long,
    private val onWorkerSurvives: () -> Unit,
    private val onWorkerResult: (Result<Int>) -> Unit,
    private val beforeWorkerEntry: () -> Unit,
) {
    private var worker: Thread? = null

    fun runBounded(block: () -> Int): Int {
        val result = AtomicReference<Result<Int>?>(null)
        val started = CountDownLatch(1)
        val worker = thread(start = false, isDaemon = true, name = "plainbase-prelock-fixture") {
            val outcome = runCatching {
                beforeWorkerEntry()
                started.countDown()
                block()
            }
            result.set(outcome)
            onWorkerResult(outcome)
        }
        this.worker = worker
        worker.start()
        var parentInterrupted = Thread.interrupted()
        try {
            val entryWait = if (parentInterrupted) {
                WaitResult(completed = false, interrupted = true)
            } else {
                awaitLatch(started, entryTimeoutMillis)
            }
            parentInterrupted = parentInterrupted || entryWait.interrupted
            if (!entryWait.completed) {
                val failure = if (entryWait.interrupted) {
                    InterruptedException("pre-lock fixture wait for worker entry was interrupted")
                } else {
                    TimeoutException(
                        "pre-lock fixture worker did not enter within its ${entryTimeoutMillis}ms entry bound",
                    )
                }
                abortWorker(worker, failure)
            }
            val runWait = joinSafely(worker, runTimeoutMillis)
            parentInterrupted = parentInterrupted || runWait.interrupted
            if (runWait.interrupted) {
                abortWorker(worker, InterruptedException("pre-lock fixture wait was interrupted"))
            }
            if (!runWait.stopped) {
                abortWorker(
                    worker,
                    TimeoutException("pre-lock fixture exceeded its ${runTimeoutMillis}ms run bound"),
                )
            }
            return requireNotNull(result.get()) { "pre-lock fixture completed without a result" }.getOrThrow()
        } finally {
            if (parentInterrupted) Thread.currentThread().interrupt()
        }
    }

    fun cleanup() {
        val worker = worker
        var parentInterrupted = Thread.interrupted()
        try {
            if (worker?.isAlive == true) {
                onWorkerSurvives()
                val cleanupWait = joinSafely(worker, cleanupTimeoutMillis)
                parentInterrupted = parentInterrupted || cleanupWait.interrupted
            }
            if (worker == null || !worker.isAlive) base.toFile().deleteRecursively()
        } finally {
            if (parentInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun abortWorker(worker: Thread, failure: Throwable): Nothing {
        worker.interrupt()
        runCatching { onWorkerSurvives() }.exceptionOrNull()?.let(failure::addSuppressed)
        val cleanupWait = joinSafely(worker, cleanupTimeoutMillis)
        if (!cleanupWait.stopped) {
            failure.addSuppressed(
                TimeoutException("pre-lock fixture worker survived its ${cleanupTimeoutMillis}ms cleanup bound"),
            )
        }
        throw failure
    }
}

private data class JoinResult(val stopped: Boolean, val interrupted: Boolean)

private data class WaitResult(val completed: Boolean, val interrupted: Boolean)

private fun awaitLatch(latch: CountDownLatch, timeoutMillis: Long): WaitResult = try {
    WaitResult(latch.await(timeoutMillis, TimeUnit.MILLISECONDS), interrupted = false)
} catch (_: InterruptedException) {
    WaitResult(completed = false, interrupted = true)
}

private fun joinSafely(worker: Thread, timeoutMillis: Long): JoinResult {
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
    return JoinResult(!worker.isAlive, interrupted)
}

private fun fileFamily(dir: Path, stem: String): Map<String, ByteArray> {
    val names = listOf(stem, "$stem-wal", "$stem-shm", "$stem-journal")
    return names.filter { Files.exists(dir.resolve(it)) }.associateWith { Files.readAllBytes(dir.resolve(it)) }
}

private fun treeSnapshot(root: Path): Map<String, ByteArray> {
    if (!Files.exists(root)) return emptyMap()
    return Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .map { root.relativize(it).toString() to Files.readAllBytes(it) }
            .toList()
            .toMap()
    }
}

private fun fileKey(path: Path): Any = requireNotNull(
    Files.readAttributes(path, BasicFileAttributes::class.java).fileKey(),
) { "the fixture filesystem does not expose directory file keys" }

private fun assertDistinctFileKeys(swap: SwapFixture) {
    check(swap.originalKey != null) { "expected a retained file key" }
    check(swap.replacementKey != null) { "expected a replacement file key" }
    check(swap.originalKey != swap.replacementKey) { "the retained and replacement directories share a file key" }
}

private fun requireLockHeld(data: Path) {
    val attempt = DataDirLock.tryAcquire(data)
    if (attempt != null) {
        attempt.close()
        error("DATA_DIR lock was released before resource cleanup")
    }
}

private fun lockHeld(data: Path): Boolean {
    val attempt = DataDirLock.tryAcquire(data)
    if (attempt == null) return true
    attempt.close()
    return false
}

private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

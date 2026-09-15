package com.plainbase.frameworks.objectstore

import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.filesystem.IgnoreRules
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files

/** Slice1 controls for the real client-to-store acquisition boundary. */
class ObjectContentStoreFactoryTest : FunSpec({

    test("the successful real mirror/store recipe transfers and closes its client once") {
        val dataDir = Files.createTempDirectory("plainbase-object-factory-success")
        val config = objectConfig(dataDir)
        val acquired = arrayOfNulls<TrackingRealClient>(1)
        try {
            val store = ObjectContentStoreFactory.buildWithClient(
                config = config,
                ignoreRules = IgnoreRules(),
                dirtyPaths = { emptySet() },
                isDirty = { false },
                rowsAtStart = { RowsAtStart(emptySet(), BindingEpoch(0)) },
                clientFactory = { clientConfig ->
                    TrackingRealClient(S3ObjectClient(clientConfig)).also { acquired[0] = it }
                },
            )
            val client = requireNotNull(acquired[0])
            client.transportActive shouldBe true

            store.close()

            client.closeCount shouldBe 1
            client.transportActive shouldBe false
        } finally {
            val closed = acquired[0]?.closeIfOpen(null) ?: true
            if (closed) dataDir.toFile().deleteRecursively()
        }
    }

    test("a named failure after the real mirror/store recipe closes the acquired client") {
        val dataDir = Files.createTempDirectory("plainbase-object-factory-failure")
        val config = objectConfig(dataDir)
        val failure = IllegalStateException("store assembly failed")
        val cleanup = IllegalStateException("client close failed")
        val acquired = arrayOfNulls<TrackingRealClient>(1)
        var testFailure: Throwable? = null
        try {
            val actual = shouldThrow<IllegalStateException> {
                ObjectContentStoreFactory.buildWithClient(
                    config = config,
                    ignoreRules = IgnoreRules(),
                    dirtyPaths = { emptySet() },
                    isDirty = { false },
                    rowsAtStart = { RowsAtStart(emptySet(), BindingEpoch(0)) },
                    clientFactory = { clientConfig ->
                        TrackingRealClient(S3ObjectClient(clientConfig), cleanup).also { acquired[0] = it }
                    },
                    storeFactory = { client ->
                        ObjectContentStoreFactory.buildStore(
                            client = client,
                            config = config,
                            ignoreRules = IgnoreRules(),
                            dirtyPaths = { emptySet() },
                            isDirty = { false },
                            rowsAtStart = { RowsAtStart(emptySet(), BindingEpoch(0)) },
                        ).also { throw failure }
                    },
                )
            }

            actual shouldBeSameInstanceAs failure
            val client = requireNotNull(acquired[0])
            client.closeCount shouldBe 1
            client.transportActive shouldBe false
            actual.suppressed.single() shouldBeSameInstanceAs cleanup
        } catch (caught: Throwable) {
            testFailure = caught
            throw caught
        } finally {
            val closed = acquired[0]?.closeIfOpen(testFailure) ?: true
            if (closed) dataDir.toFile().deleteRecursively()
        }
    }
})

private fun objectConfig(dataDir: java.nio.file.Path): PlainbaseConfig = ConfigLoader.fromEnv(emptyMap()).copy(
    dataDir = dataDir,
    storage = StorageConfig(
        backend = StorageBackend.OBJECT,
        endpoint = "http://127.0.0.1:1",
        bucket = "docs",
        accessKeyId = "k",
        secretAccessKey = "s",
    ),
)

private class TrackingRealClient(
    private val delegate: S3ObjectClient,
    private val closeFailure: Throwable? = null,
) : ObjectStoreClient by delegate {
    var closeCount: Int = 0
        private set

    val transportActive: Boolean
        get() = delegate.transportActiveForTest()

    override fun close() {
        closeCount++
        delegate.close()
        closeFailure?.let { throw it }
    }

    fun closeIfOpen(primary: Throwable?): Boolean {
        if (!transportActive) return true
        try {
            close()
            check(!transportActive) { "real object transport remained active after fallback close" }
            return true
        } catch (cleanup: Throwable) {
            if (primary != null) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            } else {
                throw cleanup
            }
            return !transportActive
        }
    }
}

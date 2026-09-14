package com.plainbase.frameworks.cli

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.IdentitySafeFailureAccumulator
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import com.plainbase.frameworks.objectstore.ObjectStoreClient
import com.plainbase.frameworks.objectstore.S3ObjectClient
import com.plainbase.frameworks.runtime.LocalStoreInputs
import com.plainbase.frameworks.runtime.OfflineStoreOperations
import com.plainbase.frameworks.runtime.RootStoreFactory
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.BeginImmediateSqliteDriver
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

internal typealias OfflineObjectOpener = (
    PlainbaseConfig,
    IgnoreRules,
    () -> Set<TreePath>,
    (TreePath) -> Boolean,
    () -> RowsAtStart,
) -> ObjectContentStore

/** Real JDBC and object-client handles for offline command cleanup assertions. */
internal class OfflineStoreFixture : AutoCloseable {
    val drivers = mutableListOf<TrackingSqlDriver>()
    val connections = mutableListOf<TrackingConnection>()
    val staticConnections = mutableListOf<Connection>()
    val clients = mutableListOf<TrackingRealClient>()
    val stores = mutableListOf<ObjectContentStore>()
    var onClientClose: (() -> Unit)? = null
    var onConnectionClose: (() -> Unit)? = null
    var onDriverClose: (() -> Unit)? = null
    var writableOpens: Int = 0
        private set
    var readOnlyOpens: Int = 0
        private set

    private val defaultStores = mutableListOf<ObjectContentStore>()
    private val failures = IdentitySafeFailureAccumulator()
    private var interrupted = false
    private var closed = false

    fun operations(
        openObject: OfflineObjectOpener = ::openRealObject,
        hydrateObject: (ObjectContentStore) -> Unit = { it.hydrate() },
        closeObject: (ObjectContentStore) -> Unit = { it.close() },
    ): OfflineStoreOperations = OfflineStoreOperations(
        openDriver = { path ->
            writableOpens++
            TrackingSqlDriver(
                delegate = DatabaseFactory.createDriver(path),
                onClose = { observe { onDriverClose?.invoke() } },
            ).also(drivers::add)
        },
        openReadOnlyDriver = { path ->
            readOnlyOpens++
            val fresh = Files.notExists(path)
            TrackingSqlDriver(
                delegate = DatabaseFactory.createReadOnlyDriver(path),
                onClose = { observe { onDriverClose?.invoke() } },
            ).also { driver ->
                if (fresh) (driver.delegate as BeginImmediateSqliteDriver).getConnection().let(staticConnections::add)
                drivers += driver
            }
        },
        openSearch = { path ->
            SearchDb(path) { url ->
                TrackingConnection(
                    delegate = DriverManager.getConnection(url),
                    onClose = { onConnectionClose?.invoke() },
                    recordFailure = ::recordFailure,
                )
                    .also(connections::add)
            }
        },
        openLocal = { inputs -> RootStoreFactory.local(inputs) },
        openObject = openObject,
        hydrateObject = hydrateObject,
        closeObject = closeObject,
    )

    fun productionOperations(
        openLocal: (LocalStoreInputs) -> LocalContentStore =
            { inputs -> RootStoreFactory.local(inputs) },
        hydrateObject: (ObjectContentStore) -> Unit = { it.hydrate() },
        closeObject: (ObjectContentStore) -> Unit = { it.close() },
    ): OfflineStoreOperations {
        val tracked = operations()
        val defaults = OfflineStoreOperations()
        return OfflineStoreOperations(
            openDriver = tracked.openDriver,
            openReadOnlyDriver = tracked.openReadOnlyDriver,
            openSearch = tracked.openSearch,
            openLocal = openLocal,
            openObject = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
                defaults.openObject(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart).also {
                    stores += it
                    defaultStores += it
                }
            },
            hydrateObject = hydrateObject,
            closeObject = closeObject,
        )
    }

    fun openRealObject(
        config: PlainbaseConfig,
        ignoreRules: IgnoreRules,
        dirtyPaths: () -> Set<TreePath>,
        isDirty: (TreePath) -> Boolean,
        rowsAtStart: () -> RowsAtStart,
    ): ObjectContentStore {
        val store = ObjectContentStoreFactory.buildWithClient(
            config = config,
            ignoreRules = ignoreRules,
            dirtyPaths = dirtyPaths,
            isDirty = isDirty,
            rowsAtStart = rowsAtStart,
            clientFactory = { clientConfig ->
                TrackingRealClient(
                    delegate = S3ObjectClient(clientConfig),
                    onClose = { observe { onClientClose?.invoke() } },
                ).also(clients::add)
            },
        )
        stores += store
        return store
    }

    fun openObjectThenFail(
        config: PlainbaseConfig,
        ignoreRules: IgnoreRules,
        dirtyPaths: () -> Set<TreePath>,
        isDirty: (TreePath) -> Boolean,
        rowsAtStart: () -> RowsAtStart,
        failure: Throwable,
    ): ObjectContentStore = ObjectContentStoreFactory.buildWithClient(
        config = config,
        ignoreRules = ignoreRules,
        dirtyPaths = dirtyPaths,
        isDirty = isDirty,
        rowsAtStart = rowsAtStart,
        clientFactory = { clientConfig ->
            TrackingRealClient(
                delegate = S3ObjectClient(clientConfig),
                onClose = { observe { onClientClose?.invoke() } },
            ).also(clients::add)
        },
        storeFactory = { client ->
            ObjectContentStoreFactory.buildStore(client, config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
                .also { store ->
                    stores += store
                    throw failure
                }
        },
    )

    override fun close() {
        if (closed) return
        closed = true

        defaultStores.forEach { store -> attempt { if (!store.isClosedForTest()) store.close() } }
        clients.forEach { client -> attempt { if (client.transportActive) client.closeDelegateForRescue() } }
        connections.forEach { connection -> attempt { if (!connection.isClosed) connection.closeDelegateForRescue() } }
        drivers.forEach { driver -> attempt { if (driver.closeCount == 0) driver.closeDelegateForRescue() } }
        staticConnections.forEach { connection -> attempt { if (!connection.isClosed) connection.close() } }

        if (interrupted) Thread.currentThread().interrupt()
        failures.failure?.let { throw it }
    }

    private fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            recordFailure(failure)
        }
    }

    private fun recordFailure(failure: Throwable) {
        if (failure is InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
        failures.add(failure)
    }

    fun observe(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            recordFailure(failure)
        }
    }
}

internal class TrackingSqlDriver(
    internal val delegate: SqlDriver,
    private val onClose: () -> Unit = {},
) : SqlDriver by delegate {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
        onClose()
        delegate.close()
    }

    fun closeDelegateForRescue() {
        delegate.close()
    }
}

internal class TrackingConnection(
    private val delegate: Connection,
    private val onClose: () -> Unit = {},
    private val recordFailure: (Throwable) -> Unit = {},
) : Connection by delegate {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
        try {
            onClose()
        } catch (failure: Throwable) {
            recordFailure(failure)
        }
        delegate.close()
    }

    fun closeDelegateForRescue() {
        delegate.close()
    }
}

internal class TrackingRealClient(
    private val delegate: S3ObjectClient,
    private val onClose: () -> Unit = {},
) : ObjectStoreClient by delegate {
    var closeCount: Int = 0
        private set

    val transportActive: Boolean
        get() = delegate.transportActiveForTest()

    override fun close() {
        closeCount++
        onClose()
        delegate.close()
    }

    fun closeDelegateForRescue() {
        delegate.close()
    }
}

internal fun withRetainedDirectories(vararg directories: java.nio.file.Path, block: () -> Unit) {
    var completed = false
    try {
        block()
        completed = true
    } finally {
        if (completed) directories.forEach(::deleteFixtureTree)
    }
}

private fun deleteFixtureTree(directory: java.nio.file.Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

internal fun withEmptyListEndpoint(block: (String) -> Unit) {
    val listXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult>
            <IsTruncated>false</IsTruncated>
        </ListBucketResult>
    """.trimIndent()
    withEndpoint(listXml, ContentType.Application.Xml, HttpStatusCode.OK, block = block)
}

internal fun withListRefusalEndpoint(block: (String, AtomicInteger) -> Unit) {
    val requests = AtomicInteger()
    withEndpoint(
        body = "LIST refused",
        contentType = ContentType.Text.Plain,
        status = HttpStatusCode.ServiceUnavailable,
        beforeRespond = { requests.incrementAndGet() },
    ) { endpoint -> block(endpoint, requests) }
}

private fun withEndpoint(
    body: String,
    contentType: ContentType,
    status: HttpStatusCode,
    beforeRespond: () -> Unit = {},
    block: (String) -> Unit,
) {
    val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            route("{path...}") {
                handle {
                    beforeRespond()
                    call.respondText(body, contentType, status)
                }
            }
        }
    }.start(wait = false)
    val failures = IdentitySafeFailureAccumulator()
    var interrupted = false
    fun record(failure: Throwable) {
        if (failure is InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
        failures.add(failure)
    }
    try {
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            block("http://127.0.0.1:$port")
        } catch (failure: Throwable) {
            record(failure)
        }
    } finally {
        try {
            runBlocking { withContext(Dispatchers.IO) { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) } }
        } catch (failure: Throwable) {
            record(failure)
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
    failures.failure?.let { throw it }
}

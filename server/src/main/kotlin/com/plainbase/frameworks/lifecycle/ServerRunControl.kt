package com.plainbase.frameworks.lifecycle

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.runtime.DeferredObjectHistory
import com.plainbase.frameworks.runtime.ObjectHistoryCallbacks
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.search.SearchDb
import org.koin.core.KoinApplication

/** Narrow observations and real production operations owned by one [com.plainbase.runServer] invocation. */
internal class ServerRunControl(
    val startServer: (KtorServer) -> Unit = { it.start(wait = true) },
    val onHookInstalled: (Thread) -> Unit = {},
    val onBootAvailability: (RootAvailability) -> Unit = {},
    val onContextAcquired: (KoinApplication) -> Unit = {},
    val onRuntimeContext: (RouteContext) -> Unit = {},
    val afterRouteContextBuilt: (RouteContext) -> Unit = {},
    val buildRouteContext: (() -> RouteContext) -> RouteContext = { build -> build() },
    val createHttpServer: (PlainbaseConfig, RouteContext) -> KtorServer = ::KtorServer,
    val onHttpAcquired: (KtorServer) -> Unit = {},
    val onWatcherRegistration: (RootName) -> Unit = {},
    val onWatcherAcquired: (RootName, AutoCloseable) -> Unit = { _, _ -> },
    val createScheduler: (IndexBuilder) -> RebuildScheduler = { builder ->
        RebuildScheduler(rebuild = { builder.rebuild() }, alarm = ExecutorAlarm())
    },
    val initialRebuild: (IndexBuilder) -> Unit = { it.rebuild() },
    val onDrAcquired: (GitBundleDr) -> Unit = {},
    val armObjectHistory: (DeferredObjectHistory, ObjectHistoryCallbacks) -> Unit = { history, callbacks ->
        history.arm(callbacks)
    },
    val restoreBundle: (GitBundleDr) -> GitBundleDr.Restored = { it.restore() },
    val hydrateObject: (ObjectContentStore, Boolean) -> Unit = { store, strict -> store.hydrate(strict) },
    val reconcileBundle: (GitBundleDr, GitBundleDr.Restored) -> Unit = { bundleDr, restored ->
        bundleDr.reconcileBootCommit(restored)
    },
    val afterDrArm: (GitBundleDr) -> Unit = {},
    val afterDrRestore: (GitBundleDr) -> Unit = {},
    val afterObjectHydrate: (ObjectContentStore) -> Unit = {},
    val afterDrReconcile: (GitBundleDr) -> Unit = {},
    val closeHttp: (KtorServer) -> Unit = { it.stop() },
    val closeWatcher: (AutoCloseable) -> Unit = { it.close() },
    val closeDr: (GitBundleDr) -> Unit = { it.close() },
    val closeDriver: (SqlDriver) -> Unit = { it.close() },
    val closeSearch: (SearchDb) -> Unit = { it.close() },
    val closeObject: (ObjectContentStore) -> Unit = { it.close() },
    val closeContext: (KoinApplication) -> Unit = { it.close() },
)

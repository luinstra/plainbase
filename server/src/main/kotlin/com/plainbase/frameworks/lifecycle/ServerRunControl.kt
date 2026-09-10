package com.plainbase.frameworks.lifecycle

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.search.SearchDb
import org.koin.core.KoinApplication

/** Narrow observations and real production operations owned by one [com.plainbase.runServer] invocation. */
internal class ServerRunControl(
    val startServer: (KtorServer) -> Unit = { it.start(wait = true) },
    val onHookInstalled: (Thread) -> Unit = {},
    val closeDriver: (SqlDriver) -> Unit = { it.close() },
    val closeSearch: (SearchDb) -> Unit = { it.close() },
    val closeObject: (ObjectContentStore) -> Unit = { it.close() },
    val closeContext: (KoinApplication) -> Unit = { it.close() },
)

package com.plainbase.frameworks.runtime

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import java.nio.file.Path

/** Typed constructor operations shared by the two offline commands and their resource-observing fixtures. */
internal class OfflineStoreOperations(
    val openDriver: (Path) -> SqlDriver = { path -> DatabaseFactory.createDriver(path) },
    val openReadOnlyDriver: (Path) -> SqlDriver = { path -> DatabaseFactory.createReadOnlyDriver(path) },
    val openSearch: (Path) -> SearchDb = { path -> SearchDb(path) },
    val openLocal: (LocalStoreInputs) -> LocalContentStore = { inputs -> RootStoreFactory.local(inputs) },
    val openObject: (
        PlainbaseConfig,
        IgnoreRules,
        () -> Set<TreePath>,
        (TreePath) -> Boolean,
        () -> RowsAtStart,
    ) -> ObjectContentStore = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
        RootStoreFactory.objectStore(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
    },
    val hydrateObject: (ObjectContentStore) -> Unit = { store -> store.hydrate() },
    val closeObject: (ObjectContentStore) -> Unit = { store -> store.close() },
)

package com.plainbase.frameworks.runtime

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.FileAtomics
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import java.nio.file.Path

/** Inputs kept typed at the LOCAL constructor boundary so later lifecycle tests can observe a real store. */
internal data class LocalStoreInputs(
    val root: Path,
    val ignoreRules: IgnoreRules,
    val exclusions: List<Path>,
    val rootName: RootName,
    val onRootUnavailable: () -> Unit,
    val onIdentityRebind: () -> Unit,
)

/** The finite set of real constructor seams used by one serving run. */
internal class ServerOpeners(
    val openDriver: (Path) -> SqlDriver = { path -> DatabaseFactory.createDriver(path) },
    val openLocal: (LocalStoreInputs) -> LocalContentStore = { inputs ->
        LocalContentStore(
            root = inputs.root,
            ignoreRules = inputs.ignoreRules,
            exclusions = inputs.exclusions,
            atomics = FileAtomics.Real,
            rootName = inputs.rootName,
            onRootUnavailable = inputs.onRootUnavailable,
            onIdentityRebind = inputs.onIdentityRebind,
        )
    },
    val openObject: (
        PlainbaseConfig,
        IgnoreRules,
        () -> Set<TreePath>,
        (TreePath) -> Boolean,
        () -> RowsAtStart,
    ) -> ObjectContentStore = { config, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
        ObjectContentStoreFactory.build(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
    },
    val openSearch: (Path) -> SearchDb = { path -> SearchDb(path) },
)

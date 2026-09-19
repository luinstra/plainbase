package com.plainbase.frameworks.runtime

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.FileAtomics
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory

/** Shared LOCAL/OBJECT store construction from prepared roots and runtime inputs. */
internal object RootStoreFactory {
    fun local(inputs: LocalStoreInputs): LocalContentStore =
        LocalContentStore(
            root = inputs.root,
            ignoreRules = inputs.ignoreRules,
            exclusions = inputs.exclusions,
            atomics = FileAtomics.Real,
            rootName = inputs.rootName,
            onRootUnavailable = inputs.onRootUnavailable,
            onIdentityRebind = inputs.onIdentityRebind,
            policy = inputs.policy,
        )

    fun objectStore(
        config: PlainbaseConfig,
        ignoreRules: IgnoreRules,
        dirtyPaths: () -> Set<TreePath>,
        isDirty: (TreePath) -> Boolean,
        rowsAtStart: () -> RowsAtStart,
    ): ObjectContentStore = ObjectContentStoreFactory.build(config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)

    fun primary(
        backend: StorageBackend,
        local: () -> LocalContentStore,
        objectStore: () -> ObjectContentStore,
    ): ContentStore = when (backend) {
        StorageBackend.LOCAL -> local()
        StorageBackend.OBJECT -> objectStore()
    }

    fun roots(
        registry: RootRegistry,
        primary: ContentStore,
        localExtra: (Root) -> ContentStore,
    ): RootStores {
        val byRoot = linkedMapOf(registry.primary.name to primary)
        registry.extras.forEach { root -> byRoot[root.name] = localExtra(root) }
        return RootStores(byRoot)
    }
}

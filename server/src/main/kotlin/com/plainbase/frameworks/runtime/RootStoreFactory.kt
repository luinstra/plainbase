package com.plainbase.frameworks.runtime

import com.plainbase.frameworks.filesystem.FileAtomics
import com.plainbase.frameworks.filesystem.LocalContentStore

/** The server's minimal LOCAL construction recipe; preparation supplies all root-specific inputs. */
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
        )
}

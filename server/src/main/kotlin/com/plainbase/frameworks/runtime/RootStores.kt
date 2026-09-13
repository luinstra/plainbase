package com.plainbase.frameworks.runtime

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.root.ObjectManifestProvider
import com.plainbase.domain.root.RootName

/** The per-root content stores, built from the registry and looked up by the named root. */
class RootStores(private val byRoot: Map<RootName, ContentStore>) {

    /** Missing roots indicate a wiring error and retain the root name in the failure. */
    operator fun get(root: RootName): ContentStore = requireNotNull(byRoot[root]) {
        "no store for root '$root': a per-root lookup ran on an unregistered root - resolve PageRootResolver.statusOf first"
    }

    /** Returns the object manifest source for an object-backed root, or null for a local root. */
    fun manifestsOrNull(root: RootName): ObjectManifestProvider? = byRoot[root] as? ObjectManifestProvider
}

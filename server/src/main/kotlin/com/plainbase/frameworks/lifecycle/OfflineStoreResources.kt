package com.plainbase.frameworks.lifecycle

import com.plainbase.frameworks.objectstore.ObjectContentStore

/** Owns the one raw OBJECT transport acquired by an offline command. */
internal class OfflineStoreResources(
    private val closeObject: (ObjectContentStore) -> Unit,
) : AutoCloseable {

    private var ownedObject: ObjectContentStore? = null

    fun ownObject(store: ObjectContentStore): ObjectContentStore {
        check(ownedObject == null) { "offline command already owns an object store" }
        ownedObject = store
        return store
    }

    override fun close() {
        val store = ownedObject ?: return
        ownedObject = null
        closeObject(store)
    }
}

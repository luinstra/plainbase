package com.plainbase.frameworks.runtime

import com.plainbase.domain.content.TreePath
import java.util.concurrent.atomic.AtomicReference

internal data class ObjectHistoryCallbacks(
    val repoPath: (TreePath) -> String,
    val onCommit: () -> Unit,
)

internal class ObjectHistoryNotReady : IllegalStateException("object history callbacks are not armed")

/** Holds the object history path and ship callbacks until the post-lock OBJECT graph is complete. */
internal class DeferredObjectHistory {
    private val callbacks = AtomicReference<ObjectHistoryCallbacks?>(null)

    fun arm(callbacks: ObjectHistoryCallbacks) {
        check(this.callbacks.compareAndSet(null, callbacks)) { "object history callbacks already armed" }
    }

    fun requireReady(): ObjectHistoryCallbacks = callbacks.get() ?: throw ObjectHistoryNotReady()

    fun repoPath(path: TreePath): String = requireReady().repoPath(path)

    fun onCommit() {
        requireReady().onCommit()
    }
}

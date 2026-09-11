package com.plainbase

import java.util.Collections
import java.util.IdentityHashMap

/** Keeps one original failure while suppressing each distinct throwable at most once by identity. */
internal class IdentitySafeFailureAccumulator {
    private val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    private var primary: Throwable? = null

    val failure: Throwable?
        get() = primary

    fun add(candidate: Throwable?) {
        if (candidate == null || !seen.add(candidate)) return
        val current = primary
        if (current == null) {
            primary = candidate
        } else {
            current.addSuppressed(candidate)
        }
    }
}

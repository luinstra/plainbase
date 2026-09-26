package com.plainbase.domain.service

/**
 * One process-wide monitor shared by page saves and discussion writes across all roots, so their read/update steps
 * cannot interleave. The lock is internal so cooperating module code uses [withLock], keeping lock acquisition behind
 * this boundary.
 */
class ContentWriteMonitor {
    /** Shared synchronization identity across roots; production callers coordinate through [withLock]. */
    internal val lock = Any()

    fun <T> withLock(block: () -> T): T = synchronized(lock, block)
}

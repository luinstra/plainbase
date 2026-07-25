@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.filesystem

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.createDirectories

/**
 * A cross-process advisory lock over `DATA_DIR/plainbase.lock` (Resolution 1b). The in-process
 * `@Synchronized`/`SearchDb` monitor serializes writers within ONE JVM but cannot span processes,
 * and SQLite WAL + `busy_timeout` guard against *corruption*, not *freshness*: an offline
 * `plainbase reindex` could otherwise silently publish an OLDER generation over the newer one a
 * live server just synced. This lock makes "one writer of search.db at a time" an enforced
 * precondition.
 *
 *  - The server acquires it for its whole lifetime in `Application.serve()` and refuses to start a
 *    second instance on the same DATA_DIR.
 *  - The `plainbase reindex` CLI [tryAcquire]s it FIRST and exits 1 with a clear message if a
 *    server holds it, never writing search.db underneath a running server.
 *
 * The lockfile is an empty marker — NOT `search.db` itself, which SQLite manages. [close] releases
 * the lock and closes the channel; the file may linger harmlessly (an advisory OS lock, not its
 * presence, is what excludes).
 */
class DataDirLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /**
     * Idempotent, per the AutoCloseable contract — and load-bearing since graceful shutdown: the SIGTERM hook
     * and the clean-exit `finally` in `serve()` can BOTH reach it. `FileLock.release()` on an already-released
     * lock is not a no-op when the channel is gone (it throws ClosedChannelException), so the guard is here.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        const val LOCK_FILE_NAME: String = "plainbase.lock"

        /**
         * The `plainbase root` lock (C5 D-C5-9). Deliberately NOT [LOCK_FILE_NAME]: a running server holds
         * that one for its whole lifetime, and restart-to-apply means an operator MUST be able to stage a
         * topology change WHILE the server runs - requiring them to stop the server to edit config they can
         * only apply by restarting is a worse ritual for no safety. The root commands touch no database and
         * no search index, so they need nothing the DATA_DIR lock protects; what they DO need is exclusion
         * against each other, because two concurrent `root add`s doing read-modify-write on `roots.conf`
         * would lose an update.
         */
        const val ROOTS_LOCK_FILE_NAME: String = "roots.lock"

        private val logger = KotlinLogging.logger {}

        /**
         * Tries to acquire an exclusive DATA_DIR-scoped lock, returning null when another process holds it
         * (`FileChannel.tryLock` returns null on contention). The caller decides what a null means
         * (the server refuses to start; the CLI exits 1; `plainbase root` retries on a bounded poll).
         *
         * [fileName] selects WHICH lock: the DATA_DIR lock by default, or [ROOTS_LOCK_FILE_NAME] for the root
         * commands. The retry policy lives in the CALLER, never here - `serve`'s "held means someone else owns
         * this DATA_DIR, refuse now" semantics must stay exactly as they are.
         */
        fun tryAcquire(dataDir: Path, fileName: String = LOCK_FILE_NAME): DataDirLock? {
            dataDir.createDirectories()
            val path = dataDir.resolve(fileName)
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Another holder in THIS JVM already owns the region (tryLock is JVM-wide): treat it
                // exactly like cross-process contention — held, caller decides what that means.
                channel.close()
                logger.debug { "DATA_DIR lock $path is already held in this JVM" }
                return null
            } catch (e: Exception) {
                channel.close()
                throw e
            }
            if (lock == null) {
                channel.close()
                logger.debug { "DATA_DIR lock $path is held by another process" }
                return null
            }
            return DataDirLock(channel, lock)
        }
    }
}

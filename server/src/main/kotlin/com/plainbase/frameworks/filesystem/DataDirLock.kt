package com.plainbase.frameworks.filesystem

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        const val LOCK_FILE_NAME: String = "plainbase.lock"

        private val logger = KotlinLogging.logger {}

        /**
         * Tries to acquire the exclusive DATA_DIR lock, returning null when another process holds it
         * (`FileChannel.tryLock` returns null on contention). The caller decides what a null means
         * (the server refuses to start; the CLI exits 1).
         */
        fun tryAcquire(dataDir: Path): DataDirLock? {
            dataDir.createDirectories()
            val path = dataDir.resolve(LOCK_FILE_NAME)
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

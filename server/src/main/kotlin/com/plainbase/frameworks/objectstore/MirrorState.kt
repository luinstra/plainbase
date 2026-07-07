package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.filesystem.FileAtomics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The persisted etag map of the object-backend mirror (plan M1): which bucket generation each
 * mirror file is KNOWN to hold. One JSON document at `DATA_DIR/mirror-state`, kotlinx.serialization
 * (the only serialization stack), derived and deletable like everything in DATA_DIR - a missing,
 * unparseable, or wrong-version file loads EMPTY (the cold path: the next hydrate re-GETs).
 *
 * **The single invariant (M1/Q8b):** an entry asserts "the mirror bytes at this path ARE the bucket
 * generation this etag names"; whenever that is not known-true, the entry must be ABSENT. Entry
 * absence is the one staleness marker - an absent key is a CACHE MISS the next CAS resolves by
 * reading the bucket back, never an error.
 *
 * **Exactly two map-write seams** ([recordConfirmed] and [invalidate]) mutate the in-memory map -
 * the structural choke point (M1). [recordConfirmed] may be called ONLY after the inner mirror
 * write returned success, and only under the hybrid apply monitor; a "record before mirror
 * confirmation" call site is unrepresentable, not merely forbidden. [persist] is a FILE operation,
 * deliberately separate from the map seams: the per-op write path flushes after each mutation, the
 * poll/hydrate apply loops flush once per batch - the cadence never folds into the mutators.
 *
 * Methods are synchronized for map consistency only (the poll thread and writer threads share this
 * object); no network or long I/O ever runs under it besides [persist]'s local file write.
 */
class MirrorState(
    private val file: Path,
    private val atomics: FileAtomics = FileAtomics.Real,
) {

    private val entries: MutableMap<TreePath, String> = load()

    /** The etag the mirror is known to hold for [path], or null - a miss is normal (seam h). */
    @Synchronized
    fun etagOf(path: TreePath): String? = entries[path]

    /** An immutable copy of the whole map, for the poll/boot diff. */
    @Synchronized
    fun snapshot(): Map<TreePath, String> = entries.toMap()

    /**
     * Records that the mirror file at [path] now holds the bucket generation [etag] names. Call-site
     * law: ONLY after the inner mirror write returned success, only under the hybrid apply monitor.
     * Does NOT persist - the caller owns the flush cadence.
     */
    @Synchronized
    fun recordConfirmed(path: TreePath, etag: String) {
        entries[path] = etag
    }

    /** Drops [path]'s entry: the mirror-vs-bucket relation is no longer known-true (Q8b). */
    @Synchronized
    fun invalidate(path: TreePath) {
        entries.remove(path)
    }

    /**
     * Flushes the current map to [file]: temp-sibling + ATOMIC_MOVE (the `LocalContentStore.write`
     * idiom), copy+delete on filesystems without atomic rename. Never mutates the map.
     *
     * BEST-EFFORT: a flush fault (a full or faulted DATA_DIR) is caught and WARNed, never rethrown. The
     * in-memory map is authoritative and persist never mutates it, so a failed flush only leaves a stale
     * on-disk copy that the next successful flush or a boot hydrate self-corrects (the cold-path load
     * already tolerates a stale/absent/corrupt file). Letting it propagate would kill the poll thread or
     * turn an honest Q8b 503 (mirror write failed => invalidate => persist on the same full disk) into a
     * raw 500 - so persist stays a non-throwing optimization.
     */
    @Synchronized
    fun persist() {
        try {
            Files.createDirectories(file.parent)
            val document = Document(version = VERSION, entries = entries.entries.associate { (path, etag) -> path.value to etag })
            val tmp = Files.createTempFile(file.parent, ".mirror-state.", ".tmp")
            try {
                Files.writeString(tmp, Json.encodeToString(document))
                try {
                    atomics.atomicMove(tmp, file)
                } catch (_: AtomicMoveNotSupportedException) {
                    logger.warn { "ATOMIC_MOVE unsupported for $file; falling back to copy+delete (non-atomic)" }
                    atomics.copyReplace(tmp, file)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        } catch (e: IOException) {
            logger.warn {
                "mirror-state persist to $file failed (${e.message}); keeping the authoritative in-memory map, retrying next flush"
            }
        }
    }

    /** The cold-path load: missing / unparseable / wrong version / invalid key => start EMPTY. */
    private fun load(): MutableMap<TreePath, String> {
        if (!Files.isRegularFile(file)) return mutableMapOf()
        val document = try {
            Json.decodeFromString<Document>(Files.readString(file))
        } catch (e: Exception) {
            logger.warn { "mirror-state at $file is unreadable (${e.message}); starting empty (cold path, derived state)" }
            return mutableMapOf()
        }
        if (document.version != VERSION) {
            logger.warn { "mirror-state at $file has version ${document.version} (expected $VERSION); starting empty (cold path)" }
            return mutableMapOf()
        }
        val loaded = mutableMapOf<TreePath, String>()
        for ((raw, etag) in document.entries) {
            val path = TreePath.of(raw)
            if (path == null) {
                logger.warn { "mirror-state at $file names an invalid path '$raw'; starting empty (cold path)" }
                return mutableMapOf()
            }
            loaded[path] = etag
        }
        return loaded
    }

    @Serializable
    private data class Document(val version: Int, val entries: Map<String, String>)

    companion object {
        private const val VERSION = 1
        private val logger = KotlinLogging.logger {}
    }
}

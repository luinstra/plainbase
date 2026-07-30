package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.frameworks.filesystem.FOLDER_META_NAME
import com.plainbase.frameworks.filesystem.FileAtomics
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.filesystem.withDirectoryStream
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class ObjectMirrorFiles(
    private val mirrorRoot: Path,
    private val mirror: LocalContentStore,
    private val ignoreRules: IgnoreRules,
    private val atomics: FileAtomics,
    private val scan: () -> ScanResult,
) {
    fun eligibleTreePath(rawRelative: String): TreePath? {
        // The DECISION is single-sourced in MirrorKeyFunnel. The SECURITY escape guard runs first:
        // a foreign key that would land outside the mirror is rejected before reaching any write sink.
        val path = MirrorKeyFunnel.eligible(rawRelative, mirrorRoot, ignoreRules)
        if (path == null) {
            when {
                MirrorKeyFunnel.escapesRoot(rawRelative, mirrorRoot) ->
                    logger.warn { "skipping bucket key that could escape the mirror root: '$rawRelative'" }
                rawRelative.startsWith(APP_OWNED_PREFIX) ->
                    logger.debug { "skipping app-owned bucket key: '$rawRelative'" }
                else ->
                    logger.warn { "skipping ineligible bucket key (ignored or unparseable): '$rawRelative'" }
            }
        }
        return path
    }

    /**
     * Writes at the literal raw relative location so hydration and polling preserve the bucket's byte form.
     * The inner LocalContentStore then owns NFC normalization, collision resolution, and raw-name retention.
     */
    fun writeRaw(rawRelative: String, bytes: ByteArray, fullNfcSweep: Boolean) {
        if (MirrorKeyFunnel.escapesRoot(rawRelative, mirrorRoot)) {
            logger.warn { "refusing mirror write of a key that escapes the mirror root: '$rawRelative'" }
            throw ObjectStoreException("mirror key '$rawRelative' resolves outside the mirror root")
        }
        val target = mirrorRoot.resolve(rawRelative)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".pbtmp", ".tmp")
        try {
            Files.write(tmp, bytes)
            runCatching {
                atomics.atomicMove(tmp, target)
            }.getOrElse { failure ->
                when (failure) {
                    is AtomicMoveNotSupportedException -> {
                        logger.warn { "ATOMIC_MOVE unsupported for '$rawRelative'; falling back to copy+delete (non-atomic)" }
                        atomics.copyReplace(tmp, target)
                    }
                    else -> throw failure
                }
            }
            // Always sweep: a retry after a failed sweep must not record a confirmed generation while
            // a stale NFC-equivalent sibling can still win LocalContentStore collision resolution.
            runCatching {
                if (fullNfcSweep) removeStaleNfcSiblings(target) else removeStaleNfcSiblingsTargeted(target)
            }.getOrElse { failure ->
                when (failure) {
                    is IOException -> {
                        runCatching { Files.deleteIfExists(target) }.onFailure { rollbackFailure ->
                            logger.warn {
                                "rollback delete of '$rawRelative' after a failed NFC sweep ALSO failed " +
                                    "(${causeOf(rollbackFailure)}); the next apply always re-sweeps"
                            }
                        }
                        throw ObjectStoreException(
                            "mirror NFC-sibling sweep failed for '$rawRelative': ${causeOf(failure)}",
                            failure,
                        )
                    }
                    else -> throw failure
                }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** True only for a regular file at the exact raw name; directories and symlinks are not mirror bytes. */
    fun holdsRaw(rawRelative: String): Boolean =
        Files.isRegularFile(mirrorRoot.resolve(rawRelative), LinkOption.NOFOLLOW_LINKS)

    private fun removeStaleNfcSiblingsTargeted(target: Path) {
        val parent = target.parent ?: return
        val name = target.fileName.toString()
        val candidates = setOf(Nfc.normalize(name), Nfc.decompose(name)) - name
        candidates.asSequence()
            .map { candidateName -> candidateName to parent.resolve(candidateName) }
            .filter { (_, entry) ->
                Files.exists(entry, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
            }
            .filterNot { (_, entry) -> Files.isSameFile(entry, target) }
            .forEach { (candidateName, entry) ->
                logger.warn { "removing stale NFC-equivalent mirror sibling '$candidateName' superseded by '$name'" }
                Files.deleteIfExists(entry)
            }
    }

    private fun removeStaleNfcSiblings(target: Path) {
        val parent = target.parent ?: return
        val wantNfc = Nfc.normalize(target.fileName.toString())
        withDirectoryStream(parent) { stream ->
            stream.asSequence()
                .filterNot { entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) }
                .filter { entry -> Nfc.normalize(entry.fileName.toString()) == wantNfc }
                .filterNot { entry -> Files.isSameFile(entry, target) }
                .forEach { entry ->
                    logger.warn {
                        "removing stale NFC-equivalent mirror sibling '${entry.fileName}' superseded by '${target.fileName}'"
                    }
                    Files.deleteIfExists(entry)
                }
        }
    }

    /** Every bucket-managed file currently held by the mirror, including folder metadata sidecars. */
    fun paths(): Set<TreePath> {
        val scanned = scan()
        val sidecars = scanned.folders.filter { it.meta != null }.map { it.path.resolveChild(FOLDER_META_NAME) }
        return (scanned.files.map { it.path } + sidecars).toSet()
    }

    /**
     * Deletes the raw-name-aware mirror file and its now-empty parents. False means the caller must
     * retain the state entry because the mirror can still serve the file.
     */
    fun delete(path: TreePath): Boolean {
        val target = mirror.onDiskTarget(path)
        logger.info { "deleting mirror file absent from the bucket: ${path.value}" }
        return runCatching {
            Files.deleteIfExists(target)
            dropEmptyParents(target.parent)
            true
        }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                when (failure) {
                    is IOException -> {
                        logger.warn { "mirror delete of '${path.value}' failed (${causeOf(failure)}); keeping state" }
                        false
                    }
                    else -> throw failure
                }
            },
        )
    }

    private fun dropEmptyParents(start: Path?) {
        var dir = start?.toAbsolutePath()?.normalize()
        while (dir != null && dir != mirrorRoot && dir.startsWith(mirrorRoot)) {
            val empty = runCatching {
                Files.isDirectory(dir) && withDirectoryStream(dir) { !it.iterator().hasNext() }
            }.getOrElse { failure ->
                when (failure) {
                    is IOException -> return
                    else -> throw failure
                }
            }
            if (!empty) return
            runCatching { Files.delete(dir) }.getOrElse { failure ->
                when (failure) {
                    is IOException -> return
                    else -> throw failure
                }
            }
            dir = dir.parent
        }
    }

    private fun causeOf(failure: Throwable): String = failure.message ?: failure::class.simpleName ?: "failure"

    companion object {
        private const val APP_OWNED_PREFIX = ".plainbase/"
        private val logger = KotlinLogging.logger {}
    }
}

internal class ObjectBucketLister(
    private val client: ObjectStoreClient,
    private val keyPrefix: String,
    private val binding: RootBinding,
    private val rowsAtStart: () -> RowsAtStart,
    private val mirrorFiles: ObjectMirrorFiles,
) {
    fun listGeneration(): ObjectGeneration {
        val boundary = rowsAtStart()
        return ObjectGeneration(
            binding = binding,
            listed = listBucket(),
            rowsAtStart = boundary.rows,
            bindingEpoch = boundary.bindingEpoch,
        )
    }

    private fun listBucket(): Map<TreePath, MirrorListedEntry> {
        val entries = mutableMapOf<TreePath, MirrorListedEntry>()
        runBlocking {
            client.forEachListedObject(keyPrefix) { wire ->
                val raw = S3WireKey.decode(wire.key)
                if (!raw.startsWith(keyPrefix)) {
                    logger.warn { "skipping bucket key outside the configured prefix: '$raw'" }
                    return@forEachListedObject
                }
                val relative = raw.removePrefix(keyPrefix)
                val path = mirrorFiles.eligibleTreePath(relative) ?: return@forEachListedObject
                val existing = entries[path]
                when {
                    existing == null -> entries[path] = MirrorListedEntry(relative, wire.etag, wire.size)
                    RawByteOrder.compare(relative, existing.rawRelative) < 0 -> {
                        logger.warn {
                            "NFC key collision at '${path.value}': winner raw='$relative', loser raw='${existing.rawRelative}'"
                        }
                        entries[path] = MirrorListedEntry(relative, wire.etag, wire.size)
                    }
                    else -> logger.warn {
                        "NFC key collision at '${path.value}': winner raw='${existing.rawRelative}', loser raw='$relative'"
                    }
                }
            }
        }
        return entries
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/** [declaredSize] is LIST's advisory byte count (null when the provider omits it) - see [ListResponseParser.Entry.size]. */
internal data class MirrorListedEntry(val rawRelative: String, val etag: String, val declaredSize: Long?)

internal data class ObjectGeneration(
    val binding: RootBinding,
    val listed: Map<TreePath, MirrorListedEntry>,
    val rowsAtStart: Set<BindingRef>,
    val bindingEpoch: BindingEpoch,
)

internal class ObjectHistoryBundleStore(
    private val client: ObjectStoreClient,
    private val keyPrefix: String,
) {
    private val bundleKey get() = keyPrefix + APP_OWNED_PREFIX + "history.bundle"

    fun fetchTo(target: Path): Boolean =
        runBlocking {
            client.getToFile(
                bundleKey,
                target,
                requestTimeoutMillis = ObjectContentStore.BUNDLE_TRANSFER_TIMEOUT_MILLIS,
            )
        }

    fun put(bytes: ByteArray) {
        runBlocking {
            client.put(
                bundleKey,
                bytes,
                PutCondition.None,
                contentType = null,
                requestTimeoutMillis = ObjectContentStore.BUNDLE_TRANSFER_TIMEOUT_MILLIS,
            )
        }
    }

    fun putFrom(source: Path) {
        runBlocking {
            client.putFromFile(
                bundleKey,
                source,
                contentType = null,
                requestTimeoutMillis = ObjectContentStore.BUNDLE_TRANSFER_TIMEOUT_MILLIS,
            )
        }
    }

    companion object {
        private const val APP_OWNED_PREFIX = ".plainbase/"
    }
}

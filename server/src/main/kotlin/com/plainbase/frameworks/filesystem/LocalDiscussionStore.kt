package com.plainbase.frameworks.filesystem

import com.plainbase.domain.discussion.BootTombstone
import com.plainbase.domain.discussion.COLLECTION_DIR
import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.EntryVersion
import com.plainbase.domain.discussion.MARKER_NAME
import com.plainbase.domain.discussion.MAX_COMMENT_ENTRIES
import com.plainbase.domain.discussion.RESERVED_COLLECTION_ROOT
import com.plainbase.domain.discussion.RawEntry
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.discussion.Tombstone
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.RootUnavailable
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Instant

/** Local filesystem adapter for the reserved per-root discussion collection. */
class LocalDiscussionStore(
    roots: Map<RootName, Path>,
    private val atomics: FileAtomics = FileAtomics.Real,
    private val delete: (Path) -> Unit = Files::delete,
    private val hashFile: (Path, Int) -> EntryVersion = ::cappedSha256,
    private val readEntryBytes: ((Path, Int) -> ByteArray)? = null,
    private val readCreatedDirectoryAttributes: (Path) -> BasicFileAttributes = { path ->
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    },
    private val probeRoot: (Path) -> Boolean = ::rootIsTraversable,
    private val onRootUnavailable: (RootName) -> Unit = {},
    private val tempSuffix: (() -> String)? = null,
    private val onTempPath: ((Path) -> Unit)? = null,
) : DiscussionStore {
    private val roots = roots.mapValues { (_, path) -> path.toAbsolutePath().normalize() }

    override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit = rooted(
        root = root,
        ambiguous = { it is CollectionVisit.Absent || it is CollectionVisit.Failed },
        operation = { base -> visitLocked(base, visitor) },
    )

    override fun stamp(root: RootName, id: DiscussionId): Stamp? = rooted(
        root = root,
        ambiguous = { it == null },
        operation = { base -> stampLocked(base, id) },
    )

    override fun sweepBootResidue(root: RootName, now: Instant, minAge: Duration): List<BootTombstone> = rooted(
        root = root,
        ambiguous = { false },
        operation = { base -> sweepBootResidueLocked(base, now, minAge) },
    )

    override fun tombstoneBytes(root: RootName, tombstone: Tombstone): ByteArray? = rooted(
        root = root,
        ambiguous = { it == null },
        operation = { base -> tombstoneBytesLocked(base, tombstone) },
    )

    override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead = rooted(
        root = root,
        ambiguous = { it is EntriesRead.Absent || it is EntriesRead.Failed },
        operation = { base -> readLocked(base, id, only) },
    )

    override fun createFiles(root: RootName, id: DiscussionId, puts: List<EntryPut>): StoreWrite = rooted(
        root = root,
        ambiguous = { it.isRootAmbiguous() },
        operation = { base -> createFilesLocked(root, base, id, puts) },
    )

    override fun replace(root: RootName, entry: EntryPath, version: EntryVersion, bytes: ByteArray): StoreWrite = rooted(
        root = root,
        ambiguous = { it.isRootAmbiguous() },
        operation = { base -> replaceLocked(root, base, entry, version, bytes) },
    )

    override fun purge(root: RootName, entry: EntryPath, version: EntryVersion): StoreWrite = rooted(
        root = root,
        ambiguous = { it.isRootAmbiguous() },
        operation = { base -> purgeLocked(root, base, entry, version) },
    )

    override fun restore(root: RootName, tombstone: Tombstone): StoreWrite = rooted(
        root = root,
        ambiguous = { it.isRootAmbiguous() },
        operation = { base -> restoreLocked(root, base, tombstone) },
    )

    override fun discard(root: RootName, tombstone: Tombstone): StoreWrite = rooted(
        root = root,
        ambiguous = { it.isRootAmbiguous() },
        operation = { base -> discardLocked(root, base, tombstone) },
    )

    private fun visitLocked(base: Path, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit = try {
        when (val dirs = collectionDirectories(base, create = false)) {
                is DirectoryWalk.Refused -> {
                    logger.debug { "Discussion list encountered a symbolic link at ${dirs.name}" }
                    CollectionVisit.Symlinked
                }
            DirectoryWalk.Absent, DirectoryWalk.NotDirectory -> CollectionVisit.Absent
            is DirectoryWalk.Ready -> {
                var count = 0
                withDirectoryStream(dirs.path) { stream ->
                    stream.forEach { child ->
                        val raw = child.fileName.toString()
                        val id = DiscussionId.of(raw)?.takeIf { it.value == raw } ?: return@forEach
                        when (inspect(child).kind) {
                            NodeKind.DIRECTORY -> {
                                count++
                                visitor(id, false)
                            }
                            NodeKind.SYMLINK -> {
                                count++
                                visitor(id, true)
                            }
                            else -> Unit
                        }
                    }
                }
                CollectionVisit.Visited(count)
            }
        }
    } catch (failure: IOException) {
        CollectionVisit.Failed(message(failure))
    }

    private fun stampLocked(base: Path, id: DiscussionId): Stamp? = when (val dirs = collectionDirectories(base, create = false)) {
            is DirectoryWalk.Refused -> Stamp("symlink:${dirs.name}")
            DirectoryWalk.Absent, DirectoryWalk.NotDirectory -> null
            is DirectoryWalk.Ready -> {
                val idPath = dirs.path.resolve(id.value)
                when (inspect(idPath).kind) {
                    NodeKind.MISSING, NodeKind.OTHER, NodeKind.REGULAR -> null
                    NodeKind.SYMLINK -> Stamp("symlink:${id.value}/")
                    NodeKind.DIRECTORY -> stampDirectory(idPath)
                }
            }
        }

    private fun stampDirectory(idPath: Path): Stamp? {
        val entries = mutableListOf<StampedEntry>()
        var oversized = false
        withDirectoryStream(idPath) { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val child = iterator.next()
                val entry = EntryName.parse(child.fileName.toString())?.let { name ->
                    val snapshot = inspect(child)
                    when (snapshot.kind) {
                        NodeKind.SYMLINK -> StampedEntry(name.fileName, "symlink", 0, 0, "")
                        NodeKind.REGULAR -> snapshot.attributes?.let { attrs ->
                            StampedEntry(
                                name.fileName,
                                "regular",
                                attrs.size(),
                                attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                                attrs.fileKey()?.toString().orEmpty(),
                            )
                        }
                        else -> null
                    }
                }
                if (entry != null) {
                    if (entries.size >= MAX_COMMENT_ENTRIES + 1) {
                        oversized = true
                        break
                    }
                    entries += entry
                }
            }
        }
        if (oversized) return null
        return stampDirectorySnapshot(idPath, entries)
    }

    private fun stampDirectorySnapshot(idPath: Path, entries: MutableList<StampedEntry>): Stamp? {
        val directory = inspect(idPath)
        val directoryAttributes = when (directory.kind) {
            NodeKind.DIRECTORY -> directory.attributes ?: return null
            NodeKind.SYMLINK -> return Stamp("symlink:${idPath.fileName}/")
            else -> return null
        }
        entries.sortBy { it.name }
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, "directory")
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(directoryAttributes.size()).array())
        val directoryModifiedNanos = directoryAttributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(directoryModifiedNanos).array())
        val directoryIdentity = directoryAttributes.fileKey()?.toString()
            ?: "created:${directoryAttributes.creationTime().to(TimeUnit.NANOSECONDS)}"
        update(digest, directoryIdentity)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(entries.size).array())
        entries.forEach { entry ->
            update(digest, entry.name)
            update(digest, entry.kind)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(entry.size).array())
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(entry.modifiedNanos).array())
            update(digest, entry.fileKey)
        }
        val nowMillis = System.currentTimeMillis()
        val newestMillis = entries.fold(TimeUnit.NANOSECONDS.toMillis(directoryModifiedNanos)) { newest, entry ->
            maxOf(newest, TimeUnit.NANOSECONDS.toMillis(entry.modifiedNanos))
        }
        val racy = newestMillis > nowMillis || nowMillis - newestMillis <= RACY_STAMP_WINDOW_MILLIS
        return Stamp("sha256:" + digest.digest().toHexString(), racy)
    }

    private fun sweepBootResidueLocked(base: Path, now: Instant, minAge: Duration): List<BootTombstone> {
        require(minAge >= Duration.ZERO)
        val dirs = when (val walk = collectionDirectories(base, create = false)) {
            DirectoryWalk.Absent -> return emptyList()
            DirectoryWalk.NotDirectory -> throw IOException("discussion collection is not a directory")
            is DirectoryWalk.Refused -> throw IOException("symlink:${walk.name}")
            is DirectoryWalk.Ready -> walk.path
        }
        val nowMillis = now.toEpochMilliseconds()
        val cutoff = nowMillis - minAge.inWholeMilliseconds
        val found = mutableListOf<BootTombstone>()
        withDirectoryStream(dirs) { stream ->
            stream.forEach { child ->
                val rawId = child.fileName.toString()
                val id = DiscussionId.of(rawId)?.takeIf { it.value == rawId } ?: return@forEach
                if (inspect(child).kind != NodeKind.DIRECTORY) return@forEach
                val tombstones = mutableListOf<BootTombstone>()
                withDirectoryStream(child) { entries ->
                    entries.forEach entryLoop@{ entry ->
                        val fileName = entry.fileName.toString()
                        val attrs = try {
                            Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        } catch (_: IOException) {
                            return@entryLoop
                        } catch (_: SecurityException) {
                            return@entryLoop
                        }
                        val tempMatch = TEMP_PATTERN.matches(fileName)
                        if (tempMatch && attrs.isRegularFile && attrs.lastModifiedTime().toMillis() <= cutoff) {
                            try {
                                delete(entry)
                            } catch (_: IOException) {
                                logger.warn { "Could not sweep aged discussion temp $entry" }
                            } catch (_: SecurityException) {
                                logger.warn { "Could not sweep aged discussion temp $entry" }
                            }
                        }
                        val tombstoneMatch = TOMBSTONE_PATTERN.matchEntire(fileName)
                        if (tombstoneMatch != null) {
                            val targetName = tombstoneMatch.groupValues[1]
                            val target = EntryName.parse(targetName)
                            if (target != null && attrs.isRegularFile) {
                                val targetMissing = try {
                                    inspect(child.resolve(targetName)).kind == NodeKind.MISSING
                                } catch (_: IOException) {
                                    false
                                } catch (_: SecurityException) {
                                    false
                                }
                                tombstones += BootTombstone(Tombstone(EntryPath(id, target), fileName), targetMissing)
                            }
                        }
                    }
                }
                found += tombstones
                try {
                    if (!directoryHasEntries(child)) delete(child)
                } catch (_: IOException) {
                    logger.warn { "Could not check discussion directory $child for boot cleanup" }
                } catch (_: SecurityException) {
                    logger.warn { "Could not check discussion directory $child for boot cleanup" }
                }
            }
        }
        return found
    }

    private fun tombstoneBytesLocked(base: Path, tombstone: Tombstone): ByteArray? {
        if (validateTombstone(tombstone) != null) return null
        val dirs = (collectionDirectories(base, create = false) as? DirectoryWalk.Ready)?.path ?: return null
        val idPath = dirs.resolve(tombstone.entry.id.value)
        if (inspect(idPath).kind != NodeKind.DIRECTORY) return null
        val source = idPath.resolve(tombstone.fileName)
        return try {
            readEntry(source, tombstone.entry.name).takeIf { it.complete }?.take()
        } catch (_: NoSuchFileException) {
            null
        } catch (_: FileSystemLinkException) {
            null
        }
    }

    private fun readLocked(base: Path, id: DiscussionId, only: Set<EntryName>?): EntriesRead = try {
        when (val dirs = collectionDirectories(base, create = false)) {
            DirectoryWalk.Absent, DirectoryWalk.NotDirectory -> EntriesRead.Absent
            is DirectoryWalk.Refused -> symlinkedRead(dirs.name)
            is DirectoryWalk.Ready -> readDiscussionDirectory(dirs.path, id, only)
        }
    } catch (failure: IOException) {
        EntriesRead.Failed(message(failure))
    }

    private fun readDiscussionDirectory(
        collection: Path,
        id: DiscussionId,
        only: Set<EntryName>?,
    ): EntriesRead {
        val idPath = collection.resolve(id.value)
        return when (inspect(idPath).kind) {
            NodeKind.MISSING, NodeKind.OTHER, NodeKind.REGULAR -> EntriesRead.Absent
            NodeKind.SYMLINK -> symlinkedRead("${id.value}/")
            NodeKind.DIRECTORY -> readEntriesInDirectory(idPath, only)
        }
    }

    private fun readEntriesInDirectory(idPath: Path, only: Set<EntryName>?): EntriesRead {
        val scan = scanEntryNames(idPath, only)
        if (only == null && scan.commentCount > MAX_COMMENT_ENTRIES) return readTooMany(idPath, scan)
        scan.firstSymlink?.let { return symlinkedRead(it.fileName) }
        return readSelectedEntries(idPath, scan)
    }

    private fun scanEntryNames(idPath: Path, only: Set<EntryName>?): EntryScan {
        val scan = EntryScan()
        withDirectoryStream(idPath) { stream ->
            stream.forEach { child ->
                val name = EntryName.parse(child.fileName.toString()) ?: return@forEach
                when (inspect(child).kind) {
                    NodeKind.REGULAR -> recordRegularEntry(name, only, scan)
                    NodeKind.SYMLINK -> recordSymlinkEntry(name, only, scan)
                    else -> Unit
                }
            }
        }
        return scan
    }

    private fun recordRegularEntry(name: EntryName, only: Set<EntryName>?, scan: EntryScan) {
        when (name) {
            EntryName.Marker -> if (only == null || name in only) scan.regularNames += name
            is EntryName.Comment -> {
                scan.commentCount++
                when {
                    only != null && name in only -> scan.regularNames += name
                    only == null && scan.commentCount <= MAX_COMMENT_ENTRIES -> scan.regularNames += name
                    only == null && scan.commentCount == MAX_COMMENT_ENTRIES + 1 -> {
                        scan.regularNames.removeAll { it is EntryName.Comment }
                        if (scan.firstSymlink is EntryName.Comment) scan.firstSymlink = null
                    }
                }
            }
        }
    }

    private fun recordSymlinkEntry(name: EntryName, only: Set<EntryName>?, scan: EntryScan) {
        if (name == EntryName.Marker) scan.markerSymlink = true
        val withinLimit = only != null || name == EntryName.Marker || scan.commentCount <= MAX_COMMENT_ENTRIES
        if (withinLimit && (only == null || name in only)) {
            val current = scan.firstSymlink
            if (current == null || name.fileName < current.fileName) scan.firstSymlink = name
        }
    }

    private fun readTooMany(idPath: Path, scan: EntryScan): EntriesRead {
        if (scan.markerSymlink) return symlinkedRead(MARKER_NAME)
        val marker = if (EntryName.Marker in scan.regularNames) {
            try {
                readEntry(idPath.resolve(MARKER_NAME), EntryName.Marker)
            } catch (failure: FileSystemLinkException) {
                return symlinkedRead(failure.entry)
            }
        } else {
            null
        }
        return EntriesRead.TooMany(scan.commentCount, marker)
    }

    private fun readSelectedEntries(idPath: Path, scan: EntryScan): EntriesRead {
        val entries = mutableListOf<RawEntry>()
        scan.regularNames.sortedBy { it.fileName }.forEach { name ->
            try {
                entries += readEntry(idPath.resolve(name.fileName), name)
            } catch (failure: FileSystemLinkException) {
                return symlinkedRead(failure.entry)
            }
        }
        return EntriesRead.Present(entries, scan.commentCount)
    }

    private fun readEntry(path: Path, name: EntryName, retry: Boolean = true): RawEntry =
        try {
            readEntryOnce(path, name)
        } catch (failure: UnstableDiscussionEntryException) {
            if (retry) readEntry(path, name, retry = false) else throw IOException(failure.message, failure)
        }

    private fun readEntryOnce(path: Path, name: EntryName): RawEntry {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        requireRegularEntry(attrs, name)
        val bytes = try {
            readEntryBytes?.invoke(path, name.cap + 1) ?: readBounded(path, name.cap + 1)
        } catch (failure: IOException) {
            throwIfSymbolicLink(path, name)
            throw failure
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        requireRegularEntry(after, name)
        if (!sameIdentity(attrs, after)) {
            throw UnstableDiscussionEntryException("entry identity changed while reading")
        }
        val withinCap = attrs.size() <= name.cap && bytes.size <= name.cap
        val version = contentVersion(bytes, after.size(), name.cap)
        val failureDetail = if (!withinCap) "entry exceeds its size cap" else null
        return RawEntry(name, bytes, version, complete = failureDetail == null, failureDetail = failureDetail)
    }

    private fun requireRegularEntry(attrs: BasicFileAttributes, name: EntryName) {
        if (attrs.isSymbolicLink) throw FileSystemLinkException(name.fileName)
        if (!attrs.isRegularFile) throw UnstableDiscussionEntryException("entry is not a regular file")
    }

    private fun throwIfSymbolicLink(path: Path, name: EntryName) {
        if (inspect(path).kind == NodeKind.SYMLINK) throw FileSystemLinkException(name.fileName)
    }

    private fun createFilesLocked(root: RootName, base: Path, id: DiscussionId, puts: List<EntryPut>): StoreWrite {
        if (puts.isEmpty() || puts.map { it.name }.toSet().size != puts.size) return StoreWrite.Refused("invalid entry")
        if (puts.any { it.bytes.size > it.name.cap }) return StoreWrite.Failed("entry exceeds its size cap")
        val markerPut = puts.any { it.name == EntryName.Marker }
        return try {
            when (val dirs = collectionDirectories(base, create = markerPut)) {
                is DirectoryWalk.Refused -> refuseWrite(root, dirs.name, "symlink")
                DirectoryWalk.NotDirectory -> refuseWrite(root, RESERVED_COLLECTION_ROOT, "not a directory")
                DirectoryWalk.Absent -> if (markerPut) StoreWrite.Failed("collection directory is missing") else StoreWrite.Missing
                is DirectoryWalk.Ready -> createWithinCollection(root, dirs.path, id, puts, markerPut)
            }
        } catch (failure: IOException) {
            StoreWrite.Failed(message(failure))
        }
    }

    private fun createWithinCollection(
        root: RootName,
        collection: Path,
        id: DiscussionId,
        puts: List<EntryPut>,
        createId: Boolean,
    ): StoreWrite {
        val idPath = collection.resolve(id.value)
        val madeIdAttributes = when (val setup = ensureIdDirectory(root, idPath, createId)) {
            is IdDirectorySetup.Ready -> setup.createdAttributes
            is IdDirectorySetup.Finished -> return setup.result
        }

        val ordered = puts.sortedBy { if (it.name == EntryName.Marker) 1 else 0 }
        val created = mutableListOf<CreatedFile>()
        val versions = linkedMapOf<EntryName, EntryVersion>()
        var residual = false
        var failureText: String? = null
        try {
            for (put in ordered) {
                val target = idPath.resolve(put.name.fileName)
                val temp = temporaryPath(idPath)
                var tempCreated = false
                try {
                    onTempPath?.invoke(temp)
                    val channel = Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    tempCreated = true
                    channel.use { writeBytes(it, put.bytes) }
                    val identity = Files.readAttributes(temp, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val version = contentVersion(put.bytes, put.name.cap)
                    try {
                        atomics.createLink(target, temp)
                        created += CreatedFile(target, put.name, identity, version)
                        versions[put.name] = version
                    } catch (failure: FileAlreadyExistsException) {
                        throw failure
                    } catch (failure: IOException) {
                        residual = recordFailedLink(target, put.name, identity, version, created) || residual
                        throw failure
                    } catch (failure: UnsupportedOperationException) {
                        residual = recordFailedLink(target, put.name, identity, version, created) || residual
                        throw failure
                    } catch (failure: SecurityException) {
                        residual = recordFailedLink(target, put.name, identity, version, created) || residual
                        throw failure
                    }
                } finally {
                    if (tempCreated) {
                        val deleteFailure = tryDelete(temp)
                        if (deleteFailure != null) {
                            residual = true
                            failureText = failureText ?: message(deleteFailure)
                        }
                    }
                }
            }
        } catch (failure: IOException) {
            failureText = message(failure)
        } catch (failure: UnsupportedOperationException) {
            failureText = message(failure)
        } catch (failure: SecurityException) {
            failureText = message(failure)
        }
        if (failureText != null) return rollbackCreate(idPath, madeIdAttributes, created, failureText, residual)
        return StoreWrite.Written(versions)
    }

    private fun ensureIdDirectory(root: RootName, idPath: Path, createId: Boolean): IdDirectorySetup {
        return when (inspect(idPath).kind) {
            NodeKind.MISSING -> {
                if (!createId) return IdDirectorySetup.Finished(StoreWrite.Missing)
                try {
                    Files.createDirectory(idPath)
                    val attributes = try {
                        readCreatedDirectoryAttributes(idPath)
                    } catch (failure: IOException) {
                        return IdDirectorySetup.Finished(StoreWrite.Failed(message(failure), residual = true))
                    } catch (failure: UnsupportedOperationException) {
                        return IdDirectorySetup.Finished(StoreWrite.Failed(message(failure), residual = true))
                    } catch (failure: SecurityException) {
                        return IdDirectorySetup.Finished(StoreWrite.Failed(message(failure), residual = true))
                    }
                    IdDirectorySetup.Ready(attributes)
                } catch (_: FileAlreadyExistsException) {
                    val result = if (inspect(idPath).kind == NodeKind.DIRECTORY) {
                        StoreWrite.Exists
                    } else {
                        refuseWrite(root, idPath.fileName.toString(), "not a directory")
                    }
                    IdDirectorySetup.Finished(result)
                }
            }
            NodeKind.SYMLINK -> IdDirectorySetup.Finished(refuseWrite(root, idPath.fileName.toString(), "symlink"))
            NodeKind.DIRECTORY -> if (createId) {
                IdDirectorySetup.Finished(StoreWrite.Exists)
            } else {
                IdDirectorySetup.Ready(null)
            }
            else -> IdDirectorySetup.Finished(refuseWrite(root, idPath.fileName.toString(), "not a directory"))
        }
    }

    private fun recordFailedLink(
        target: Path,
        name: EntryName,
        identity: BasicFileAttributes,
        version: EntryVersion,
        created: MutableList<CreatedFile>,
    ): Boolean {
        return try {
            val current = inspect(target)
            if (current.kind == NodeKind.MISSING) return false
            val attributes = current.attributes
            if (current.kind != NodeKind.REGULAR || attributes == null || !sameIdentity(identity, attributes)) return true
            if (hashFile(target, name.cap) != version) return true
            val latest = inspect(target)
            val latestAttributes = latest.attributes
            if (latest.kind != NodeKind.REGULAR || latestAttributes == null || !sameIdentity(identity, latestAttributes)) {
                return true
            }
            created += CreatedFile(target, name, identity, version)
            false
        } catch (_: IOException) {
            true
        } catch (_: UnsupportedOperationException) {
            true
        } catch (_: SecurityException) {
            true
        }
    }

    private fun rollbackCreate(
        idPath: Path,
        madeIdAttributes: BasicFileAttributes?,
        created: List<CreatedFile>,
        failureText: String,
        priorResidual: Boolean,
    ): StoreWrite.Failed {
        var residual = priorResidual
        var failure = failureText
        created.asReversed().forEach { entry ->
            val detail = rollbackCreatedFile(entry)
            if (detail != null) {
                residual = true
                failure = "$failure; $detail"
            }
        }
        val directoryDetail = rollbackCreatedDirectory(idPath, madeIdAttributes)
        if (directoryDetail != null) {
            residual = true
            failure = "$failure; $directoryDetail"
        }
        return StoreWrite.Failed(failure, residual)
    }

    private fun rollbackCreatedFile(entry: CreatedFile): String? = try {
        val snapshot = inspect(entry.path)
        if (snapshot.kind == NodeKind.MISSING) return null
        val current = snapshot.attributes
        if (snapshot.kind != NodeKind.REGULAR || current == null || !sameIdentity(entry.identity, current)) {
            return "rollback left changed entry ${entry.name.fileName}"
        }
        if (hashFile(entry.path, entry.name.cap) != entry.version) {
            return "rollback left changed entry ${entry.name.fileName}"
        }
        val latest = inspect(entry.path)
        val latestAttributes = latest.attributes
        if (latest.kind != NodeKind.REGULAR || latestAttributes == null || !sameIdentity(entry.identity, latestAttributes)) {
            return "rollback left changed entry ${entry.name.fileName}"
        }
        deleteIfPresent(entry.path)
        null
    } catch (failure: IOException) {
        "rollback: ${message(failure)}"
    } catch (failure: UnsupportedOperationException) {
        "rollback: ${message(failure)}"
    } catch (failure: SecurityException) {
        "rollback: ${message(failure)}"
    }

    private fun rollbackCreatedDirectory(path: Path, created: BasicFileAttributes?): String? {
        if (created == null) return null
        return try {
            val snapshot = inspect(path)
            if (snapshot.kind == NodeKind.MISSING) return null
            val current = snapshot.attributes
            if (snapshot.kind != NodeKind.DIRECTORY || current == null || !sameDirectoryIdentity(created, current)) {
                return "rollback left changed directory ${path.fileName}"
            }
            if (!directoryHasEntries(path)) {
                deleteIfPresent(path)
            }
            null
        } catch (failure: IOException) {
            "rollback directory: ${message(failure)}"
        } catch (failure: UnsupportedOperationException) {
            "rollback directory: ${message(failure)}"
        } catch (failure: SecurityException) {
            "rollback directory: ${message(failure)}"
        }
    }

    private fun replaceLocked(
        root: RootName,
        base: Path,
        entry: EntryPath,
        version: EntryVersion,
        bytes: ByteArray,
    ): StoreWrite {
        if (bytes.size > entry.name.cap) return StoreWrite.Failed("replacement exceeds entry cap")
        return try {
            when (val location = existingEntry(base, entry)) {
                is ExistingEntry.Refused -> refuseWrite(root, location.name, location.reason)
                ExistingEntry.Missing -> StoreWrite.Missing
                is ExistingEntry.Ready -> replaceTarget(root, location.path, entry.name, version, bytes)
            }
        } catch (failure: IOException) {
            StoreWrite.Failed(message(failure))
        }
    }

    private fun replaceTarget(
        root: RootName,
        path: Path,
        name: EntryName,
        expected: EntryVersion,
        bytes: ByteArray,
    ): StoreWrite {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (before.isSymbolicLink) return refuseWrite(root, name.fileName, "symlink")
        if (!before.isRegularFile) return StoreWrite.Missing
        val current = hashFile(path, name.cap)
        if (current != expected) return StoreWrite.Mismatch(current)
        val temp = temporaryPath(path.parent)
        var tempNeedsDelete = false
        try {
            onTempPath?.invoke(temp)
            val channel = Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            tempNeedsDelete = true
            channel.use { writeBytes(it, bytes) }
            val after = inspect(path)
            if (after.kind != NodeKind.REGULAR) {
                return StoreWrite.Mismatch(null)
            }
            if (!sameIdentity(before, checkNotNull(after.attributes))) {
                val changed = hashFile(path, name.cap)
                return StoreWrite.Mismatch(changed)
            }
            val rechecked = hashFile(path, name.cap)
            if (rechecked != expected) return StoreWrite.Mismatch(rechecked)
            val finalAttributes = inspect(path)
            if (finalAttributes.kind != NodeKind.REGULAR ||
                !sameIdentity(before, checkNotNull(finalAttributes.attributes))
            ) {
                val latest = if (finalAttributes.kind == NodeKind.REGULAR) hashFile(path, name.cap) else null
                return StoreWrite.Mismatch(latest)
            }
            try {
                atomics.atomicMove(temp, path)
                tempNeedsDelete = false
                return StoreWrite.Written(mapOf(name to contentVersion(bytes, name.cap)))
            } catch (_: AtomicMoveNotSupportedException) {
                return StoreWrite.Failed("atomic move is unavailable")
            }
        } finally {
            if (tempNeedsDelete) deleteIfPresent(temp)
        }
    }

    private fun purgeLocked(root: RootName, base: Path, entry: EntryPath, version: EntryVersion): StoreWrite = try {
        when (val location = existingEntry(base, entry)) {
            is ExistingEntry.Refused -> refuseWrite(root, location.name, location.reason)
            ExistingEntry.Missing -> StoreWrite.Missing
            is ExistingEntry.Ready -> purgeTarget(root, location.path, entry, version)
        }
    } catch (failure: IOException) {
        StoreWrite.Failed(message(failure))
    }

    @Suppress("TooGenericExceptionCaught")
    private fun purgeTarget(root: RootName, path: Path, entry: EntryPath, expected: EntryVersion): StoreWrite {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (before.isSymbolicLink) return refuseWrite(root, entry.name.fileName, "symlink")
        if (!before.isRegularFile) return StoreWrite.Missing
        val current = hashFile(path, entry.name.cap)
        if (current != expected) return StoreWrite.Mismatch(current)
        val tombstoneName = ".pbpurge.${entry.name.fileName}.${randomHex()}"
        val tombstone = path.parent.resolve(tombstoneName)
        if (inspect(tombstone).kind != NodeKind.MISSING) return StoreWrite.Failed("purge tombstone name already exists")
        try {
            Files.move(path, tombstone)
        } catch (failure: IOException) {
            return StoreWrite.Failed(message(failure))
        }
        val tombstoneRef = Tombstone(entry, tombstoneName)
        return try {
            val movedVersion = hashFile(tombstone, entry.name.cap)
            if (movedVersion != expected) {
                if (moveBack(tombstone, path)) {
                    StoreWrite.Mismatch(movedVersion)
                } else {
                    StoreWrite.Failed("purge version changed and tombstone could not be restored", residual = true)
                }
            } else {
                StoreWrite.Written(emptyMap(), tombstoneRef)
            }
        } catch (failure: Exception) {
            if (moveBack(tombstone, path)) {
                StoreWrite.Failed(message(failure))
            } else {
                StoreWrite.Failed("${message(failure)}; tombstone could not be restored", residual = true)
            }
        }
    }

    private fun restoreLocked(root: RootName, base: Path, tombstone: Tombstone): StoreWrite {
        val invalid = validateTombstone(tombstone)
        if (invalid != null) return StoreWrite.Refused(invalid)
        return try {
            when (val dirs = collectionDirectories(base, create = false)) {
                is DirectoryWalk.Refused -> refuseWrite(root, dirs.name, "symlink")
                DirectoryWalk.Absent, DirectoryWalk.NotDirectory -> StoreWrite.Missing
                is DirectoryWalk.Ready -> {
                    val idPath = dirs.path.resolve(tombstone.entry.id.value)
                    when (inspect(idPath).kind) {
                        NodeKind.MISSING -> StoreWrite.Missing
                        NodeKind.SYMLINK -> refuseWrite(root, idPath.fileName.toString(), "symlink")
                        NodeKind.DIRECTORY -> restoreInDirectory(root, idPath, tombstone)
                        else -> StoreWrite.Missing
                    }
                }
            }
        } catch (failure: IOException) {
            StoreWrite.Failed(message(failure))
        }
    }

    private fun restoreInDirectory(root: RootName, idPath: Path, tombstone: Tombstone): StoreWrite {
        val source = idPath.resolve(tombstone.fileName)
        val target = idPath.resolve(tombstone.entry.name.fileName)
        return when (inspect(source).kind) {
            NodeKind.MISSING -> StoreWrite.Missing
            NodeKind.SYMLINK -> refuseWrite(root, tombstone.fileName, "symlink")
            else -> when (inspect(target).kind) {
                NodeKind.MISSING -> {
                    Files.move(source, target)
                    StoreWrite.Written(emptyMap())
                }
                else -> StoreWrite.Exists
            }
        }
    }

    private fun discardLocked(root: RootName, base: Path, tombstone: Tombstone): StoreWrite {
        val invalid = validateTombstone(tombstone)
        if (invalid != null) return StoreWrite.Refused(invalid)
        return try {
            when (val dirs = collectionDirectories(base, create = false)) {
                is DirectoryWalk.Refused -> refuseWrite(root, dirs.name, "symlink")
                DirectoryWalk.Absent, DirectoryWalk.NotDirectory -> StoreWrite.Missing
                is DirectoryWalk.Ready -> {
                    val idPath = dirs.path.resolve(tombstone.entry.id.value)
                    when (inspect(idPath).kind) {
                        NodeKind.MISSING -> StoreWrite.Missing
                        NodeKind.SYMLINK -> refuseWrite(root, idPath.fileName.toString(), "symlink")
                        NodeKind.DIRECTORY -> {
                            val source = idPath.resolve(tombstone.fileName)
                            when (inspect(source).kind) {
                                NodeKind.MISSING -> StoreWrite.Missing
                                NodeKind.SYMLINK -> refuseWrite(root, tombstone.fileName, "symlink")
                                else -> {
                                    delete(source)
                                    cleanupEmptyIdDirectory(root, tombstone.entry.id, idPath)
                                    StoreWrite.Written(emptyMap())
                                }
                            }
                        }
                        else -> StoreWrite.Missing
                    }
                }
            }
        } catch (failure: IOException) {
            StoreWrite.Failed(message(failure), residual = true)
        } catch (failure: UnsupportedOperationException) {
            StoreWrite.Failed(message(failure), residual = true)
        } catch (failure: SecurityException) {
            StoreWrite.Failed(message(failure), residual = true)
        }
    }

    private fun <T> rooted(
        root: RootName,
        ambiguous: (T) -> Boolean,
        operation: (Path) -> T,
    ): T {
        val base = roots[root] ?: throw IllegalArgumentException("unknown root: ${root.value}")
        if (!probeRoot(base)) rootGone(root)
        val outcome = try {
            operation(base)
        } catch (failure: IOException) {
            if (probeRoot(base)) throw failure
            rootGone(root)
        }
        if (ambiguous(outcome) && !probeRoot(base)) rootGone(root)
        return outcome
    }

    private fun rootGone(root: RootName): Nothing {
        onRootUnavailable(root)
        throw RootUnavailable(root, UnavailableCause.VANISHED)
    }

    private fun collectionDirectories(base: Path, create: Boolean): DirectoryWalk {
        val appDir = base.resolve(RESERVED_COLLECTION_ROOT)
        when (inspect(appDir).kind) {
            NodeKind.MISSING -> if (!create) return DirectoryWalk.Absent else createDirectory(appDir)?.let { return it }
            NodeKind.SYMLINK -> return DirectoryWalk.Refused(RESERVED_COLLECTION_ROOT)
            NodeKind.DIRECTORY -> Unit
            else -> return DirectoryWalk.NotDirectory
        }
        val collection = appDir.resolve(COLLECTION_DIR)
        when (inspect(collection).kind) {
            NodeKind.MISSING -> if (!create) return DirectoryWalk.Absent else createDirectory(collection)?.let { return it }
            NodeKind.SYMLINK -> return DirectoryWalk.Refused("$RESERVED_COLLECTION_ROOT/$COLLECTION_DIR")
            NodeKind.DIRECTORY -> Unit
            else -> return DirectoryWalk.NotDirectory
        }
        return DirectoryWalk.Ready(collection)
    }

    private fun createDirectory(path: Path): DirectoryWalk? = try {
        Files.createDirectory(path)
        null
    } catch (_: FileAlreadyExistsException) {
        when (inspect(path).kind) {
            NodeKind.DIRECTORY -> null
            NodeKind.SYMLINK -> DirectoryWalk.Refused(path.fileName.toString())
            else -> DirectoryWalk.NotDirectory
        }
    }

    private fun existingEntry(base: Path, entry: EntryPath): ExistingEntry {
        when (val dirs = collectionDirectories(base, create = false)) {
            is DirectoryWalk.Refused -> return ExistingEntry.Refused(dirs.name, "symlink")
            DirectoryWalk.Absent -> return ExistingEntry.Missing
            DirectoryWalk.NotDirectory -> return ExistingEntry.Refused(RESERVED_COLLECTION_ROOT, "not a directory")
            is DirectoryWalk.Ready -> {
                val idPath = dirs.path.resolve(entry.id.value)
                when (inspect(idPath).kind) {
                    NodeKind.MISSING -> return ExistingEntry.Missing
                    NodeKind.SYMLINK -> return ExistingEntry.Refused(entry.id.value, "symlink")
                    NodeKind.DIRECTORY -> Unit
                    else -> return ExistingEntry.Refused(entry.id.value, "not a directory")
                }
                val path = idPath.resolve(entry.name.fileName)
                when (inspect(path).kind) {
                    NodeKind.MISSING -> return ExistingEntry.Missing
                    NodeKind.SYMLINK -> return ExistingEntry.Refused(entry.name.fileName, "symlink")
                    NodeKind.REGULAR -> return ExistingEntry.Ready(path)
                    else -> return ExistingEntry.Missing
                }
            }
        }
    }

    private fun inspect(path: Path): NodeSnapshot = try {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val kind = when {
            attrs.isSymbolicLink -> NodeKind.SYMLINK
            attrs.isDirectory -> NodeKind.DIRECTORY
            attrs.isRegularFile -> NodeKind.REGULAR
            else -> NodeKind.OTHER
        }
        NodeSnapshot(kind, attrs)
    } catch (_: NoSuchFileException) {
        NodeSnapshot(NodeKind.MISSING, null)
    }

    private fun refuseWrite(root: RootName, name: String, reason: String): StoreWrite.Refused {
        logger.warn { "Refused discussion write for root ${root.value} at $name: $reason" }
        return StoreWrite.Refused(reason)
    }

    private fun symlinkedRead(entry: String): EntriesRead.Symlinked {
        logger.debug { "Discussion read encountered a symbolic link at $entry" }
        return EntriesRead.Symlinked(entry)
    }

    private fun cleanupEmptyIdDirectory(root: RootName, id: DiscussionId, idPath: Path) {
        try {
            if (!directoryHasEntries(idPath)) delete(idPath)
        } catch (failure: IOException) {
            logger.warn(failure) {
                "discarded discussion tombstone for root ${root.value}, discussion ${id.value}; " +
                    "empty-directory cleanup failed: ${message(failure)}"
            }
        } catch (failure: UnsupportedOperationException) {
            logger.warn(failure) {
                "discarded discussion tombstone for root ${root.value}, discussion ${id.value}; " +
                    "empty-directory cleanup failed: ${message(failure)}"
            }
        } catch (failure: SecurityException) {
            logger.warn(failure) {
                "discarded discussion tombstone for root ${root.value}, discussion ${id.value}; " +
                    "empty-directory cleanup failed: ${message(failure)}"
            }
        }
    }

    private fun deleteIfPresent(path: Path) {
        try {
            delete(path)
        } catch (_: NoSuchFileException) {
            // The path is already absent.
        }
    }

    private fun tryDelete(path: Path): Exception? = try {
        deleteIfPresent(path)
        null
    } catch (failure: IOException) {
        failure
    } catch (failure: UnsupportedOperationException) {
        failure
    } catch (failure: SecurityException) {
        failure
    }

    private fun directoryHasEntries(path: Path): Boolean = withDirectoryStream(path) { stream ->
        stream.iterator().hasNext()
    }

    private fun sameIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        sameFileIdentity(before, after) && before.lastModifiedTime() == after.lastModifiedTime() &&
            before.size() == after.size()

    private fun sameDirectoryIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        sameFileIdentity(before, after)

    private fun sameFileIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        if (before.fileKey() != null && after.fileKey() != null) {
            before.fileKey() == after.fileKey()
        } else {
            before.creationTime() == after.creationTime()
        }

    private fun moveBack(source: Path, target: Path): Boolean = try {
        Files.move(source, target)
        true
    } catch (_: IOException) {
        false
    }

    private fun validateTombstone(tombstone: Tombstone): String? {
        val expected = Regex("\\.pbpurge\\.${Regex.escape(tombstone.entry.name.fileName)}\\.[0-9a-f]{16}")
        return if (expected.matches(tombstone.fileName)) null else "invalid tombstone"
    }

    private fun randomHex(): String = ByteArray(8).also(random::nextBytes).joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

    private fun temporaryPath(parent: Path): Path = parent.resolve(".pbtmp.${tempSuffix?.invoke() ?: randomHex()}.tmp")

    private fun writeBytes(channel: SeekableByteChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun readBounded(path: Path, maximum: Int): ByteArray = Files.newByteChannel(
        path,
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
        val fileSize = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
        val buffer = ByteBuffer.allocate(boundedReadSize(fileSize, maximum))
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) break
        }
        buffer.flip()
        ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun message(failure: Exception): String = failure.message ?: failure.javaClass.simpleName

    private enum class NodeKind { MISSING, SYMLINK, DIRECTORY, REGULAR, OTHER }

    private data class NodeSnapshot(val kind: NodeKind, val attributes: BasicFileAttributes?)

    private data class EntryScan(
        val regularNames: MutableSet<EntryName> = mutableSetOf(),
        var firstSymlink: EntryName? = null,
        var markerSymlink: Boolean = false,
        var commentCount: Int = 0,
    )

    private data class StampedEntry(
        val name: String,
        val kind: String,
        val size: Long,
        val modifiedNanos: Long,
        val fileKey: String,
    )

    private sealed interface IdDirectorySetup {
        data class Ready(val createdAttributes: BasicFileAttributes?) : IdDirectorySetup
        data class Finished(val result: StoreWrite) : IdDirectorySetup
    }

    private data class CreatedFile(
        val path: Path,
        val name: EntryName,
        val identity: BasicFileAttributes,
        val version: EntryVersion,
    )

    private sealed interface DirectoryWalk {
        data object Absent : DirectoryWalk
        data object NotDirectory : DirectoryWalk
        data class Refused(val name: String) : DirectoryWalk
        data class Ready(val path: Path) : DirectoryWalk
    }

    private sealed interface ExistingEntry {
        data object Missing : ExistingEntry
        data class Refused(val name: String, val reason: String) : ExistingEntry
        data class Ready(val path: Path) : ExistingEntry
    }

    private class FileSystemLinkException(val entry: String) : IOException("symbolic link: $entry")

    private class UnstableDiscussionEntryException(message: String) : IOException(message)

    companion object {
        private val logger = KotlinLogging.logger {}
        private val random = SecureRandom()
        private const val RACY_STAMP_WINDOW_MILLIS = 2_000
        private val TEMP_PATTERN = Regex("^\\.pbtmp\\.[0-9a-f]{16}\\.tmp$")
        private val TOMBSTONE_PATTERN = Regex("^\\.pbpurge\\.(.+\\.md)\\.[0-9a-f]{16}$")
    }
}

private fun update(digest: MessageDigest, value: String) {
    val bytes = value.encodeToByteArray()
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    digest.update(bytes)
}

private fun cappedSha256(path: Path, cap: Int): EntryVersion {
    val initialAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val bytes = Files.newByteChannel(
        path,
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
        val buffer = ByteBuffer.allocate(boundedReadSize(initialAttributes.size(), cap + 1))
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) break
        }
        buffer.flip()
        ByteArray(buffer.remaining()).also(buffer::get)
    }
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (attributes.isSymbolicLink || !attributes.isRegularFile) throw IOException("entry is not a regular file")
    val size = attributes.size()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
    val base = "sha256:$digest"
    return EntryVersion(if (size > cap) "$base:$size" else base)
}

private fun boundedReadSize(fileSize: Long, maximum: Int): Int =
    minOf(fileSize.coerceAtMost((maximum - 1).toLong()).toInt(), maximum - 1) + 1

private fun StoreWrite.isRootAmbiguous(): Boolean =
    this is StoreWrite.Missing || (this is StoreWrite.Mismatch && current == null) || this is StoreWrite.Failed

private fun contentVersion(bytes: ByteArray, cap: Int): EntryVersion = contentVersion(bytes, bytes.size.toLong(), cap)

private fun contentVersion(bytes: ByteArray, size: Long, cap: Int): EntryVersion {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
    val base = "sha256:$digest"
    return EntryVersion(if (size > cap) "$base:$size" else base)
}

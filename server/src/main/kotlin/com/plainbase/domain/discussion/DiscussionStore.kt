package com.plainbase.domain.discussion

import com.plainbase.domain.root.RootName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Filesystem-facing port for the raw files that remain authoritative for a discussion. */
interface DiscussionStore {
    fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit
    fun stamp(root: RootName, id: DiscussionId): Stamp?
    fun sweepBootResidue(root: RootName, now: Instant, minAge: Duration = 24.hours): List<BootTombstone>
    fun tombstoneBytes(root: RootName, tombstone: Tombstone): ByteArray? = null
    fun read(root: RootName, id: DiscussionId, only: Set<EntryName>? = null): EntriesRead
    fun createFiles(root: RootName, id: DiscussionId, puts: List<EntryPut>): StoreWrite
    fun replace(root: RootName, entry: EntryPath, version: EntryVersion, bytes: ByteArray): StoreWrite
    fun purge(root: RootName, entry: EntryPath, version: EntryVersion): StoreWrite
    fun restore(root: RootName, tombstone: Tombstone): StoreWrite
    fun discard(root: RootName, tombstone: Tombstone): StoreWrite
}

data class EntryVersion(val token: String)

data class EntryPath(val id: DiscussionId, val name: EntryName)

data class Tombstone(val entry: EntryPath, val fileName: String)

class EntryPut(val name: EntryName, val bytes: ByteArray)

class RawEntry(
    val name: EntryName,
    bytes: ByteArray,
    val version: EntryVersion,
    val complete: Boolean,
    val failureDetail: String? = null,
) {
    private var storedBytes: ByteArray? = bytes

    fun take(): ByteArray = checkNotNull(storedBytes) { "entry bytes were already consumed" }.also { storedBytes = null }
}

sealed interface CollectionVisit {
    data object Absent : CollectionVisit
    data object Symlinked : CollectionVisit
    data class Failed(val cause: String) : CollectionVisit
    data class Visited(val count: Int) : CollectionVisit
}

data class BootTombstone(val tombstone: Tombstone, val targetMissing: Boolean)

sealed interface EntriesRead {
    data object Absent : EntriesRead
    data class Symlinked(val entry: String) : EntriesRead
    data class Failed(val cause: String) : EntriesRead
    data class TooMany(val count: Int, val marker: RawEntry?) : EntriesRead
    data class Present(val entries: List<RawEntry>, val commentCount: Int) : EntriesRead
}

sealed interface StoreWrite {
    data class Written(val versions: Map<EntryName, EntryVersion>, val tombstone: Tombstone? = null) : StoreWrite
    data object Exists : StoreWrite
    data object Missing : StoreWrite
    data class Mismatch(val current: EntryVersion?) : StoreWrite
    data class Refused(val reason: String) : StoreWrite
    data class Failed(val cause: String, val residual: Boolean = false) : StoreWrite
}

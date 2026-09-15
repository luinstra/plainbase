package com.plainbase.frameworks.config

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.ReservedSegments
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import java.nio.file.Path

/** Where the root topology came from. */
enum class RootsOrigin {
    SYNTHESIZED,
    EXPLICIT,
}

/** The ordered root topology and its managed-file provenance. */
@ConsistentCopyVisibility
data class RootsConfig private constructor(
    val list: List<Root>,
    val origin: RootsOrigin,
    val primaryDeclared: Boolean,
    val managed: Set<RootName>,
) {
    val primary: Root = list.first { it.name == RootName.PRIMARY }
    val extras: List<Root> = list.filter { it.name != RootName.PRIMARY }

    companion object {
        /** Takes one defensive snapshot and validates the construction-time invariants. */
        fun of(
            list: List<Root>,
            origin: RootsOrigin,
            primaryDeclared: Boolean = origin == RootsOrigin.EXPLICIT,
            managed: Set<RootName> = emptySet(),
        ): RootsConfig {
            val snapshot = list.toList()
            require(snapshot.any { it.name == RootName.PRIMARY }) {
                "no 'docs' root in the roots list (parse and synthesis both guarantee one; a directly-constructed " +
                    "RootsConfig must include it)"
            }
            val duplicates = snapshot.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate root name(s): ${duplicates.joinToString(", ") { it.value }}" }
            val reserved = snapshot.map { it.name }.filter(ReservedSegments::isReserved)
            require(reserved.isEmpty()) { "reserved root name(s): ${reserved.joinToString(", ") { it.value }}" }
            return RootsConfig(snapshot, origin, primaryDeclared, managed)
        }

        /** Synthesizes the legacy primary root from the normalized content/storage values. */
        fun synthesized(contentDir: Path, storage: StorageConfig): RootsConfig {
            val backend = when (storage.backend) {
                StorageBackend.LOCAL -> RootBackend.Local(contentDir)
                StorageBackend.OBJECT -> RootBackend.Object(storage.bucket.orEmpty(), storage.prefix)
            }
            return of(
                list = listOf(Root(RootName.PRIMARY, backend, editable = true, history = HistoryMode.AUTO)),
                origin = RootsOrigin.SYNTHESIZED,
            )
        }
    }
}

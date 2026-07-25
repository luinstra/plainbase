package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The three C4 claimant queries (`selectRootsHoldingId` / `selectRetiredRootsHoldingId` / `selectRetiredAt`) round-trip
 * through the REAL driver in-image. Every bare-id resolution the One/Ambiguous/None contract makes runs through them,
 * and all three cross the typed-column seam in both directions - a `RootName`-typed projection column on the way out,
 * a 16-byte `PageId` BLOB on the way in.
 *
 * The tombstone is minted the way production mints one: a second [SqlDelightIdMapRepository.bind] at the SAME
 * (root, path) DISPLACES the first id, retiring it in that transaction. No raw SQL, so the test exercises the adapter
 * rather than a hand-built row.
 *
 * @Tag("native") + kotlin.test - the xerial JDBC/JNI seam, run under the native image.
 */
@Tag("native")
class IdMapClaimantQueriesNativeTest {

    @Test
    fun `rootsHoldingId, retiredRootsHoldingId and retiredAt round-trip in-image`() {
        val dir = Files.createTempDirectory("pb-native-claimants")
        try {
            DatabaseFactory.createDriver(dir.resolve("plainbase.db")).use { driver ->
                val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                val path = RootedPath(RootName.MAIN, TreePath.require("guides/a.md"))
                val displaced = PageId.require("01010101-0101-0101-0101-010101010101")
                val successor = PageId.require("02020202-0202-0202-0202-020202020202")
                val unknown = PageId.require("03030303-0303-0303-0303-030303030303")

                repo.bind(path, displaced, materialized = true)
                assertEquals(listOf(RootName.MAIN), repo.rootsHoldingId(displaced))
                assertEquals(emptyList(), repo.retiredRootsHoldingId(displaced))
                assertNull(repo.retiredAt(RootName.MAIN, displaced))

                repo.bind(path, successor, materialized = true) // displaces + tombstones `displaced`
                assertEquals(listOf(RootName.MAIN), repo.rootsHoldingId(successor))
                assertEquals(emptyList(), repo.rootsHoldingId(displaced))
                assertEquals(listOf(RootName.MAIN), repo.retiredRootsHoldingId(displaced))
                val tombstone = assertNotNull(repo.retiredAt(RootName.MAIN, displaced))
                assertEquals(displaced, tombstone.id)
                assertEquals(path, tombstone.path)
                // The (root, id) key is exact: the tombstone does not answer under a root that never held it.
                assertNull(repo.retiredAt(RootName.require("notes"), displaced))

                assertEquals(emptyList(), repo.rootsHoldingId(unknown))
                assertEquals(emptyList(), repo.retiredRootsHoldingId(unknown))
                assertNull(repo.retiredAt(RootName.MAIN, unknown))
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `the claimant queries return BOTH rows for a dual-root id in-image`() {
        val dir = Files.createTempDirectory("pb-native-claimants-dual")
        try {
            DatabaseFactory.createDriver(dir.resolve("plainbase.db")).use { driver ->
                val db = DatabaseFactory.createDatabase(driver)
                val repo = SqlDelightIdMapRepository(db)
                val extra = RootName.require("extra")
                val pA = RootedPath(RootName.MAIN, TreePath.require("guides/a.md"))
                val pB = RootedPath(extra, TreePath.require("notes/b.md"))
                val x = PageId.require("01010101-0101-0101-0101-010101010101")
                val y1 = PageId.require("02020202-0202-0202-0202-020202020202")
                val y2 = PageId.require("03030303-0303-0303-0303-030303030303")

                // X lives in BOTH roots - rootsHoldingId returns both (a state UNIQUE(id) made unreachable).
                repo.bind(pA, x, materialized = false)
                repo.bind(pB, x, materialized = false)
                assertEquals(setOf(RootName.MAIN, extra), repo.rootsHoldingId(x).toSet())

                // Displace X in BOTH roots - two tombstones for one id; the list-shaped queries return both.
                repo.bind(pA, y1, materialized = false)
                repo.bind(pB, y2, materialized = false)
                assertEquals(setOf(RootName.MAIN, extra), repo.retiredRootsHoldingId(x).toSet())
                assertEquals(2, db.idMapQueries.selectRetiredRowsById(x).executeAsList().size)
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

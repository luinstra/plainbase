package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-root identity (C5): the SAME id may be tombstoned under TWO roots at once, each reading back its OWN row. This
 * replaces the deleted `SingleTombstoneInvariantTest`, whose premise (one tombstone per id) the flip legalizes.
 *
 * The dual-tombstone state is built through the PUBLIC bind path only (no raw `retire`): each root binds X, then
 * displaces it with a different id at the same (root, path). Restoring the pre-flip inline `id BLOB ... UNIQUE`
 * constraint reds this test - under `UNIQUE(id)` the second LIVE bind of X in `extra` is REFUSED by `main`'s
 * (global) tombstone reservation, so the dual seed is unreachable; only `UNIQUE(id, root)` makes the reservation
 * per-root and lets `extra` bind X freely.
 */
@Tag("native")
class PerRootTombstoneTest {

    @Test
    fun `two roots each hold their own tombstone for one id in-image`() {
        val dir = Files.createTempDirectory("pb-native-per-root-tombstone")
        try {
            DatabaseFactory.createDriver(dir.resolve("plainbase.db")).use { driver ->
                val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                val main = RootName.PRIMARY
                val extra = RootName.require("extra")
                val x = PageId.require("01010101-0101-0101-0101-010101010101")
                val yMain = PageId.require("02020202-0202-0202-0202-020202020202")
                val zExtra = PageId.require("03030303-0303-0303-0303-030303030303")
                val pMain = RootedPath(main, TreePath.require("a.md"))
                val pExtra = RootedPath(extra, TreePath.require("b.md"))

                // Tombstone X in main: bind X, then displace it with a different id at the SAME (root, path).
                assertEquals(BindOutcome.Bound, repo.bind(pMain, x, materialized = false))
                assertEquals(BindOutcome.Bound, repo.bind(pMain, yMain, materialized = false))
                // Tombstone X in extra the SAME way. The FIRST bind here is what UNIQUE(id) forbids (main's
                // tombstone reserves X globally); under UNIQUE(id, root) the reservation is per-root, so it binds.
                assertEquals(BindOutcome.Bound, repo.bind(pExtra, x, materialized = false))
                assertEquals(BindOutcome.Bound, repo.bind(pExtra, zExtra, materialized = false))

                // Each root holds its OWN tombstone for X at its own last-known path - TWO live tombstones for one id.
                assertEquals(pMain, repo.retiredAt(main, x)?.path)
                assertEquals(pExtra, repo.retiredAt(extra, x)?.path)
                val tombstonedX = repo.retiredBindings().filter { it.id == x }
                assertEquals(2, tombstonedX.size)
                assertEquals(setOf(pMain, pExtra), tombstonedX.map { it.path }.toSet())
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

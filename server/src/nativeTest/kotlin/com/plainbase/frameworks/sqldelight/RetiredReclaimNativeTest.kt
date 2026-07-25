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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The xerial-backed bind path removes a same-path tombstone when the retired id returns in the native image. */
@Tag("native")
class RetiredReclaimNativeTest {

    @Test
    fun `same-path bind reclaims a retired id and removes its tombstone in-image`() {
        val dir = Files.createTempDirectory("pb-native-reclaim")
        try {
            DatabaseFactory.createDriver(dir.resolve("plainbase.db")).use { driver ->
                val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                val path = RootedPath(RootName.MAIN, TreePath.require("doc.md"))
                val retired = PageId.require("01010101-0101-0101-0101-010101010101")
                val successor = PageId.require("02020202-0202-0202-0202-020202020202")

                assertEquals(BindOutcome.Bound, repo.bind(path, retired, materialized = true))
                assertEquals(BindOutcome.Bound, repo.bind(path, successor, materialized = true))
                assertNotNull(repo.retiredAt(RootName.MAIN, retired))
                assertEquals(emptyList(), repo.rootsHoldingId(retired))

                assertEquals(BindOutcome.Bound, repo.bind(path, retired, materialized = true))
                assertNull(repo.retiredAt(RootName.MAIN, retired))
                assertEquals(listOf(RootName.MAIN), repo.rootsHoldingId(retired))
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

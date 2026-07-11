package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.PreviousUrl
import com.plainbase.domain.root.RootName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * SqlDelightPageCheckpointRepository over an in-memory SQLite db: the C2 rooted checkpoint -
 * root round-trips (null urlPath for collision losers included), and the §B3 advisory posture is
 * unchanged: a row the adapters cannot decode (a corrupt root here) degrades [load] to the empty
 * checkpoint instead of failing startup.
 */
class SqlDelightPageCheckpointRepositoryTest : FunSpec({

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    val idA = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val idB = PageId.require("f47ac10b-58cc-4372-a567-0e02b2c3d479")

    test("replace/load round-trip the root, including a null urlPath (collision loser)") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val repo = SqlDelightPageCheckpointRepository(DatabaseFactory.createDatabase(driver))
            val checkpoint = mapOf(
                idA to PreviousUrl(main, TreePath.require("guides/deploy-guide")),
                idB to PreviousUrl(extra, null),
            )
            repo.replace(checkpoint)
            repo.load() shouldBe checkpoint
        }
    }

    test("a corrupt root name degrades load() to the empty checkpoint (§B3 advisory, never a startup failure)") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val repo = SqlDelightPageCheckpointRepository(DatabaseFactory.createDatabase(driver))
            repo.replace(mapOf(idA to PreviousUrl(main, TreePath.require("guides/deploy-guide"))))
            driver.execute(null, "UPDATE page_checkpoint SET root = 'NOT A SLUG'", 0)
            repo.load().shouldBeEmpty()
        }
    }
})

package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * STRUCTURAL (a): a single id can never hold TWO tombstones (C4, 6i). The global `selectRetired(id)` reservation in
 * `bind` refuses any bind whose (root, path) differs from an existing tombstone's, so an id is retired at its ONE
 * live (root, path) and no second live binding - hence no second tombstone - can arise. This loud tripwire seeds the
 * forbidden state by hand (raw `retire` under two roots) and asserts `retired(id)` THROWS on the two-row read, then
 * traces the POSITIVE path proving the reservation refuses the would-be second claimant.
 */
class SingleTombstoneInvariantTest : FunSpec({

    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val other = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5b")
    val main = RootName.MAIN
    val extra = RootName.require("extra")

    test("two raw retired_binding rows for one id make retired(id) THROW (the invariant tripwire)") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val repo = SqlDelightIdMapRepository(db)
            // Force the forbidden state directly (bind never can): the SAME id tombstoned under TWO roots.
            db.idMapQueries.retire(id = id, root = main, path = TreePath.require("a.md"), materialized = false, retiredAt = 1)
            db.idMapQueries.retire(id = id, root = extra, path = TreePath.require("b.md"), materialized = false, retiredAt = 2)
            shouldThrowAny { repo.retired(id) } // selectRetired(id) returns TWO rows -> executeAsOneOrNull throws
        }
    }

    test("the reservation forbids a second live binding for a tombstoned id (so a second tombstone can never form)") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val repo = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
            val pathA = RootedPath(main, TreePath.require("a.md"))
            // Bind id at (main, a.md), then displace it there (a different id at the SAME key) -> id is tombstoned at A.
            repo.bind(pathA, id, materialized = false)
            repo.bind(pathA, other, materialized = false)
            repo.retired(id)?.path shouldBe pathA

            // A claimant of the retired id at a DIFFERENT (root, path) is REFUSED - it never earns a live binding, so
            // it never earns a second tombstone.
            val outcome = repo.bind(RootedPath(extra, TreePath.require("b.md")), id, materialized = false)
            outcome.shouldBeInstanceOf<BindOutcome.Refused>()
            outcome.retired shouldBe true
        }
    }
})

package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.render.RenderedSection
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
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

    fun page(id: PageId, root: RootName, path: String) = IndexedPage(
        id = id,
        root = root,
        path = TreePath.require(path),
        slug = path.substringAfterLast('/').removeSuffix(".md"),
        urlPath = TreePath.require(path.removeSuffix(".md")),
        title = "T",
        frontmatter = Frontmatter.EMPTY,
        materialized = false,
        markdown = "",
        contentHash = "h",
        commit = null,
        html = "",
        headings = emptyList(),
        links = emptyList(),
        sections = listOf(RenderedSection(null, "body")),
    )

    fun section(root: RootName, vararg pages: IndexedPage) = RootSection(root, pages.toList(), emptyList(), emptySet())

    test("R18: replaceFrom retains a DOWN root's (root, id) checkpoint when a LIVE root holds the SAME id") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val repo = SqlDelightPageCheckpointRepository(DatabaseFactory.createDatabase(driver))
            // Root `main` is down this pass; a prior publish left its checkpoint row for the shared id X.
            repo.replace(mapOf(RootedPageId(main, idA) to TreePath.require("guides/a")))
            // The snapshot covers only LIVE root `extra`, which holds ITS OWN copy of X. Nothing is retired,
            // so the down root's (main, X) row must SURVIVE - the §3.6 reduction, not the old bare-id drop.
            repo.replaceFrom(PageIndex(listOf(section(extra, page(idA, extra, "notes/b.md")))), retired = emptySet())

            repo.load() shouldBe mapOf(
                RootedPageId(main, idA) to TreePath.require("guides/a"),
                RootedPageId(extra, idA) to TreePath.require("notes/b"),
            )
        }
    }

    test("replace/load round-trip the rooted key, including a null urlPath (collision loser)") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val repo = SqlDelightPageCheckpointRepository(DatabaseFactory.createDatabase(driver))
            val checkpoint = mapOf(
                RootedPageId(main, idA) to TreePath.require("guides/deploy-guide"),
                RootedPageId(extra, idB) to null,
            )
            repo.replace(checkpoint)
            repo.load() shouldBe checkpoint
        }
    }

    test("a corrupt root name degrades load() to the empty checkpoint (§B3 advisory, never a startup failure)") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val repo = SqlDelightPageCheckpointRepository(DatabaseFactory.createDatabase(driver))
            repo.replace(mapOf(RootedPageId(main, idA) to TreePath.require("guides/deploy-guide")))
            driver.execute(null, "UPDATE page_checkpoint SET root = 'NOT A SLUG'", 0)
            repo.load().shouldBeEmpty()
        }
    }
})

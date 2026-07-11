package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * SqlDelightDirtyPageRepository over an in-memory SQLite db: the C2 rooted-key surface - root
 * round-trips through mark/all/get, and the MINOR-1 `isDirty` existence probe is root-scoped
 * (the same relative path under another root is a different journal key).
 */
class SqlDelightDirtyPageRepositoryTest : FunSpec({

    fun <T> withRepo(block: (SqlDelightDirtyPageRepository) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(SqlDelightDirtyPageRepository(DatabaseFactory.createDatabase(driver)))
        }

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val path = RootedPath(main, TreePath.require("guides/a.md"))

    test("mark/all/get round-trip the root") {
        withRepo { repo ->
            repo.mark(pageId, path, expectedHash = "sha256:abc", stage = Stage.WRITING)
            val expected = DirtyPage(pageId, path, "sha256:abc", Stage.WRITING)
            repo.all() shouldContainExactly listOf(expected)
            repo.get(pageId) shouldBe expected
            repo.clear(pageId)
            repo.get(pageId).shouldBeNull()
        }
    }

    test("isDirty is root-scoped: the same relative path under another root does not match") {
        withRepo { repo ->
            repo.mark(pageId, path, expectedHash = "sha256:abc", stage = Stage.WRITING)
            repo.isDirty(path) shouldBe true
            repo.isDirty(RootedPath(extra, path.path)) shouldBe false
        }
    }
})

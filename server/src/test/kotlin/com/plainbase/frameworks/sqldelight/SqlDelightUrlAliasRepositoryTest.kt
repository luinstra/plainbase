package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.UrlAlias
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * SqlDelightUrlAliasRepository over an in-memory SQLite db: the chunk 4b alias-machinery criteria —
 * write/read round-trip, chain-collapse on write (§A4 one-hop guarantee), the canonical-shadow drop
 * recorded as an issue (the pairing chunk 5's IndexBuilder executes), and binary-at-rest over
 * `url_alias.id`. Alias paths are URL-slug [TreePath]s; ROW population from moves is chunk 5.
 */
class SqlDelightUrlAliasRepositoryTest : FunSpec({

    fun <T> withRepos(block: (SqlDelightUrlAliasRepository, SqlDelightIdMapRepository, SqlDriver) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            block(SqlDelightUrlAliasRepository(db), SqlDelightIdMapRepository(db), driver)
        }

    val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val otherId = PageId.require("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    val oldPath = TreePath.require("guides/deploy-guide")
    val olderPath = TreePath.require("guides/old/deployment")

    test("register/find round-trip and aliases()") {
        withRepos { aliases, _, _ ->
            aliases.find(oldPath).shouldBeNull()
            aliases.register(oldPath, pageId)
            aliases.find(oldPath) shouldBe pageId
            aliases.aliases() shouldContainExactly listOf(UrlAlias(oldPath, pageId))
        }
    }

    test("chain-collapse on write: after two moves both old paths point at the page id, one hop each") {
        withRepos { aliases, _, _ ->
            // Move 1 records the first old path; move 2 records the second. register() only accepts
            // a PAGE id, so neither row can reference the other — the chain is collapsed by
            // construction and every lookup is a single hop.
            aliases.register(olderPath, pageId)
            aliases.register(oldPath, pageId)
            aliases.find(olderPath) shouldBe pageId
            aliases.find(oldPath) shouldBe pageId
            aliases.aliases() shouldContainExactlyInAnyOrder listOf(
                UrlAlias(olderPath, pageId),
                UrlAlias(oldPath, pageId),
            )
        }
    }

    test("re-registering a path re-points it (one row per path)") {
        withRepos { aliases, _, _ ->
            aliases.register(oldPath, pageId)
            aliases.register(oldPath, otherId)
            aliases.find(oldPath) shouldBe otherId
            aliases.aliases() shouldContainExactly listOf(UrlAlias(oldPath, otherId))
        }
    }

    test("canonical-shadow drop: the shadowed alias is removed and recorded as a redirect_conflict issue") {
        withRepos { aliases, idMap, _ ->
            aliases.register(oldPath, pageId)

            // A live canonical page now claims oldPath (§A4: live canonical always wins) — the
            // chunk-5 caller pattern: drop the shadowed alias, then persist the issue.
            val dropped = aliases.dropShadowed(oldPath)
            dropped shouldBe UrlAlias(oldPath, pageId)
            aliases.find(oldPath).shouldBeNull()

            val issue = IdentityIssue.RedirectConflict(
                path = oldPath,
                message = "alias to page ${dropped?.id} dropped: shadowed by a live canonical path",
            )
            idMap.record(issue)
            idMap.issues() shouldContainExactly listOf(issue)
        }
    }

    test("dropShadowed is null when no alias claims the canonical path") {
        withRepos { aliases, _, _ ->
            aliases.dropShadowed(oldPath).shouldBeNull()
        }
    }

    test("binary at rest: every stored url_alias.id is exactly 16 bytes (direct SQL, below the adapter)") {
        withRepos { aliases, _, driver ->
            aliases.register(oldPath, pageId)
            aliases.register(olderPath, otherId)
            driver.queryLong("SELECT count(*) FROM url_alias") shouldBe 2L
            driver.queryLong("SELECT count(*) FROM url_alias WHERE length(id) != 16") shouldBe 0L
        }
    }
})

package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.UrlAlias
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * SqlDelightUrlAliasRepository over an in-memory SQLite db: the chunk 4b alias-machinery criteria —
 * write/read round-trip, chain-collapse on write (§A4 one-hop guarantee), the canonical-shadow drop
 * recorded as an issue (the pairing chunk 5's IndexBuilder executes), per-root keying (C2), and
 * binary-at-rest over `url_alias.id`. Alias paths are URL-slug rooted paths; ROW population from
 * moves is chunk 5.
 */
class SqlDelightUrlAliasRepositoryTest : FunSpec({

    fun <T> withRepos(block: (SqlDelightUrlAliasRepository, SqlDelightIdMapRepository, SqlDriver) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            block(SqlDelightUrlAliasRepository(db), SqlDelightIdMapRepository(db), driver)
        }

    val main = RootName.PRIMARY
    val extra = RootName.require("extra")
    val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val otherId = PageId.require("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    val target = RootedPageId(main, pageId)
    val otherTarget = RootedPageId(main, otherId)
    val oldPath = RootedPath(main, TreePath.require("guides/deploy-guide"))
    val olderPath = RootedPath(main, TreePath.require("guides/old/deployment"))

    test("register/find round-trip and aliases()") {
        withRepos { aliases, _, _ ->
            aliases.find(oldPath).shouldBeNull()
            aliases.register(oldPath, target)
            aliases.find(oldPath) shouldBe target
            aliases.aliases() shouldContainExactly listOf(UrlAlias(oldPath, target))
        }
    }

    test("a cross-root target round-trips its OWN root, not the alias path's root (C2)") {
        withRepos { aliases, _, _ ->
            // The alias lives in main's URL space but its target page lives under extra: find() must
            // carry the target's root, not the row's root.
            val crossRoot = RootedPageId(extra, pageId)
            aliases.register(oldPath, crossRoot)
            aliases.find(oldPath) shouldBe crossRoot
            aliases.aliases() shouldContainExactly listOf(UrlAlias(oldPath, crossRoot))
        }
    }

    test("chain-collapse on write: after two moves both old paths point at the page id, one hop each") {
        withRepos { aliases, _, _ ->
            // Move 1 records the first old path; move 2 records the second. register() only accepts
            // a PAGE (root, id), so neither row can reference the other — the chain is collapsed by
            // construction and every lookup is a single hop.
            aliases.register(olderPath, target)
            aliases.register(oldPath, target)
            aliases.find(olderPath) shouldBe target
            aliases.find(oldPath) shouldBe target
            aliases.aliases() shouldContainExactlyInAnyOrder listOf(
                UrlAlias(olderPath, target),
                UrlAlias(oldPath, target),
            )
        }
    }

    test("re-registering a path re-points it (one row per rooted path)") {
        withRepos { aliases, _, _ ->
            aliases.register(oldPath, target)
            aliases.register(oldPath, otherTarget)
            aliases.find(oldPath) shouldBe otherTarget
            aliases.aliases() shouldContainExactly listOf(UrlAlias(oldPath, otherTarget))
        }
    }

    test("re-pointing an alias to a target in a DIFFERENT root carries the new target_root (FIX BLOCKING 1)") {
        withRepos { aliases, _, _ ->
            // First point at extra's X, then re-point the SAME (root, path) to main's Y. The DO UPDATE
            // arm must re-set target_root or the read-back keeps the STALE extra root.
            aliases.register(oldPath, RootedPageId(extra, pageId))
            aliases.register(oldPath, RootedPageId(main, otherId))
            aliases.find(oldPath) shouldBe RootedPageId(main, otherId)
        }
    }

    test("the same alias path in two roots is two independent rows (per-root URL space, C2)") {
        withRepos { aliases, _, _ ->
            val mirrored = RootedPath(extra, oldPath.path)
            aliases.register(oldPath, target)
            aliases.register(mirrored, otherTarget)
            aliases.find(oldPath) shouldBe target
            aliases.find(mirrored) shouldBe otherTarget
            aliases.aliases() shouldContainExactlyInAnyOrder listOf(
                UrlAlias(oldPath, target),
                UrlAlias(mirrored, otherTarget),
            )
        }
    }

    test("canonical-shadow drop: the shadowed alias is removed and recorded as a redirect_conflict issue") {
        withRepos { aliases, idMap, _ ->
            aliases.register(oldPath, target)

            // A live canonical page now claims oldPath (§A4: live canonical always wins) — the
            // chunk-5 caller pattern: drop the shadowed alias, then persist the issue.
            val dropped = aliases.dropShadowed(oldPath)
            dropped shouldBe UrlAlias(oldPath, target)
            aliases.find(oldPath).shouldBeNull()

            val issue = IdentityIssue.RedirectConflict(
                root = oldPath.root,
                path = oldPath.path,
                message = "alias to page ${dropped?.target?.id} dropped: shadowed by a live canonical path",
            )
            idMap.record(issue)
            idMap.issues() shouldContainExactly listOf(issue)
        }
    }

    test("dropShadowed only drops its own root's row (a cross-root canonical never sheds another root's alias)") {
        withRepos { aliases, _, _ ->
            val mirrored = RootedPath(extra, oldPath.path)
            aliases.register(oldPath, target)
            aliases.register(mirrored, otherTarget)
            aliases.dropShadowed(mirrored) shouldBe UrlAlias(mirrored, otherTarget)
            aliases.find(mirrored).shouldBeNull()
            aliases.find(oldPath) shouldBe target
        }
    }

    test("dropShadowed is null when no alias claims the canonical path") {
        withRepos { aliases, _, _ ->
            aliases.dropShadowed(oldPath).shouldBeNull()
        }
    }

    test("binary at rest: every stored url_alias.id is exactly 16 bytes (direct SQL, below the adapter)") {
        withRepos { aliases, _, driver ->
            aliases.register(oldPath, target)
            aliases.register(olderPath, otherTarget)
            driver.queryLong("SELECT count(*) FROM url_alias") shouldBe 2L
            driver.queryLong("SELECT count(*) FROM url_alias WHERE length(id) != 16") shouldBe 0L
        }
    }
})

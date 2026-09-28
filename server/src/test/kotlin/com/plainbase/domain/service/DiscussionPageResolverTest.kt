package com.plainbase.domain.service

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DiscussionPageResolverTest : FunSpec({
    test("resolution goldens") {
        withPageResolverWorld { world ->
            val existing = page(ROOT, pageId(1), "guide/existing.md")
            val replacement = page(ROOT, pageId(2), "guide/renamed.md")
            val snapshot = PageIndex(listOf(section(ROOT, listOf(existing, replacement))))
            val resolver = DiscussionPageResolver(
                DiscussionSyncState(setOf(ROOT, RootName.require("extra"))),
                world.availability,
                AbsenceClassifier(world.idMap),
            )

            resolver.resolve(ROOT, existing.id, existing.path, snapshot) shouldBe
                DiscussionPageResolution.Found(existing, DiscussionPageResolution.Match.BY_ID)
            resolver.resolve(ROOT, pageId(3), replacement.path, snapshot) shouldBe
                DiscussionPageResolution.Found(replacement, DiscussionPageResolution.Match.BY_PATH)
            resolver.resolve(ROOT, pageId(4), null, snapshot) shouldBe DiscussionPageResolution.Orphaned
        }
    }

    test("an id map only rename is unavailable while its old binding is in limbo") {
        withPageResolverWorld { world ->
            val id = pageId(5)
            val oldPath = TreePath.require("guide/old.md")
            world.idMap.bind(RootedPath(ROOT, oldPath), id, materialized = true)
            val resolver = DiscussionPageResolver(
                DiscussionSyncState(setOf(ROOT, RootName.require("extra"))),
                world.availability,
                AbsenceClassifier(world.idMap, testPolicies()),
            )

            resolver.resolve(ROOT, id, null, PageIndex(emptyList())) shouldBe DiscussionPageResolution.Unavailable
            resolver.resolve(ROOT, id, oldPath, PageIndex(emptyList())) shouldBe DiscussionPageResolution.Unavailable

            val proof = AbsenceProof.accepted(
                root = ROOT,
                source = ProofSource.OPERATOR,
                observationId = world.retirements.observation(ROOT),
                bindingEpoch = world.retirements.bindingEpoch(ROOT),
                covers = setOf(BindingRef(oldPath, id)),
            )
            world.retirements.applyProofs(listOf(proof), emptySet(), unavailableNow = { emptySet() }) shouldBe
                setOf(RootedPageId(ROOT, id))
            resolver.resolve(ROOT, id, null, PageIndex(emptyList())) shouldBe DiscussionPageResolution.Orphaned
        }
    }

    test("root down is unavailable") {
        withPageResolverWorld { world ->
            world.availability.markUnavailable(ROOT, com.plainbase.domain.root.UnavailableCause.VANISHED)
            val resolver = DiscussionPageResolver(
                DiscussionSyncState(setOf(ROOT, RootName.require("extra"))),
                world.availability,
                AbsenceClassifier(world.idMap, testPolicies()),
            )

            resolver.resolve(ROOT, pageId(6), null, PageIndex(emptyList())) shouldBe DiscussionPageResolution.Unavailable
        }
    }

    test("resolution is keyed per root") {
        withPageResolverWorld { world ->
            val extra = RootName.require("extra")
            val id = pageId(7)
            val page = page(extra, id, "guide/extra.md")
            val snapshot = PageIndex(listOf(section(extra, listOf(page))))
            val resolver = DiscussionPageResolver(
                DiscussionSyncState(setOf(ROOT, RootName.require("extra"))),
                world.availability,
                AbsenceClassifier(world.idMap, testPolicies()),
            )

            resolver.resolve(ROOT, id, page.path, snapshot) shouldBe DiscussionPageResolution.Orphaned
            resolver.resolve(extra, id, page.path, snapshot) shouldBe
                DiscussionPageResolution.Found(page, DiscussionPageResolution.Match.BY_ID)
        }
    }

    test("a non scope root is refused") {
        withPageResolverWorld { world ->
            val resolver = DiscussionPageResolver(
                DiscussionSyncState(setOf(ROOT, RootName.require("extra"))),
                world.availability,
                AbsenceClassifier(world.idMap, testPolicies()),
            )

            val failure = shouldThrow<IllegalArgumentException> {
                resolver.resolve(RootName.require("archive"), pageId(9), null, PageIndex(emptyList()))
            }

            failure.message shouldBe "not a discussion root: archive"
        }
    }

    test("an id map failure never resolves to orphaned") {
        withPageResolverWorld { world ->
            val failing = object : IdMapRepository by world.idMap {
                override fun bindingInRoot(root: RootName, id: PageId): com.plainbase.domain.repository.IdBinding? =
                    throw IllegalStateException("id map unavailable")
            }
            val resolver = DiscussionPageResolver(
                DiscussionSyncState(setOf(ROOT, RootName.require("extra"))),
                world.availability,
                AbsenceClassifier(failing, testPolicies()),
            )

            shouldThrow<IllegalStateException> {
                resolver.resolve(ROOT, pageId(8), null, PageIndex(emptyList()))
            }
        }
    }
})

private val ROOT = RootName.require("docs")

private fun testPolicies() = mapOf(
    ROOT to ContentPathPolicy.ALL,
    RootName.require("extra") to ContentPathPolicy.ALL,
)

private fun withPageResolverWorld(block: (AbsenceWorld) -> Unit) {
    withAbsenceTrees { main, extra -> AbsenceWorld(main, extra).use(block) }
}

private fun page(root: RootName, id: PageId, value: String) = IndexedPage(
    id = id,
    root = root,
    path = TreePath.require(value),
    slug = value.substringAfterLast('/').substringBeforeLast('.'),
    urlPath = TreePath.require(value),
    title = value,
    frontmatter = Frontmatter.EMPTY,
    materialized = true,
    markdown = "",
    contentHash = "hash-${id.value}",
    commit = null,
    html = "",
    headings = emptyList(),
    links = emptyList(),
    sections = emptyList(),
)

private fun section(root: RootName, pages: List<IndexedPage>) = RootSection(root, pages, emptyList(), emptySet())

private fun pageId(value: Int): PageId = PageId.require("01900000-0000-4000-8000-${value.toString(16).padStart(12, '0')}")

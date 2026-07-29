package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * PageIdentityService — the frozen precedence (frontmatter id > id_map > minted UUIDv7), the §A4
 * canonical-shape validity gate for frontmatter ids, and the §5.2 within-root duplicate-id policy
 * (frozen; the pre-C2 cases below are regression pins). Under per-root identity (C5) the ADR-0011
 * D17 cross-root rank contest is DISSOLVED: `ownerOf` is root-scoped, so a cross-root duplicate is
 * no longer a contest and both roots keep the id (the one collapsed case below). Pure logic with a
 * deterministic [TestIdProvider] so minted ids are predictable (and never collide with the fixtures'
 * frontmatter ids).
 */
class PageIdentityServiceTest : FunSpec({

    val main = RootName.PRIMARY
    val extra = RootName.require("extra")
    val service = PageIdentityService(TestIdProvider())

    val pathA = RootedPath(main, TreePath.require("guides/a.md"))
    val pathB = RootedPath(main, TreePath.require("guides/b.md"))
    val pathX = RootedPath(extra, TreePath.require("guides/x.md"))
    val validId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    val noOwner = { _: PageId -> null }

    test("valid frontmatter id wins over id_map and minting") {
        val a = service.resolve(
            pathA,
            rawFrontmatterId = validId.value,
            mappedId = null,
            ownerOf = noOwner,
        )
        a.id shouldBe validId
        a.source shouldBe PageIdentityService.Source.FRONTMATTER
        a.issue.shouldBeNull()
    }

    test("a shape-invalid frontmatter id is treated as absent (§A4 spec-owned validity, not JDK leniency)") {
        // `1-1-1-1-1` is JDK-lenient-valid but not canonical-shape -> absent -> id_map entry kept.
        val mapped = PageId.require("f47ac10b-58cc-4372-a567-0e02b2c3d479")
        val r = service.resolve(
            pathA,
            rawFrontmatterId = "1-1-1-1-1",
            mappedId = mapped,
            ownerOf = noOwner,
        )
        r.id shouldBe mapped
        r.source shouldBe PageIdentityService.Source.ID_MAP
    }

    test("a well-formed v4 frontmatter id is accepted as valid identity (version-agnostic, owner ruling)") {
        val v4 = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        val r = service.resolve(pathA, rawFrontmatterId = v4, mappedId = null, ownerOf = { null })
        r.source shouldBe PageIdentityService.Source.FRONTMATTER
        r.id shouldBe PageId.require(v4)
    }

    test("no valid frontmatter id, no map entry -> a fresh UUIDv7 is minted") {
        val r = service.resolve(pathA, rawFrontmatterId = null, mappedId = null, ownerOf = { null })
        r.source shouldBe PageIdentityService.Source.MINTED
    }

    test("no valid frontmatter id, map entry present -> the map entry is kept") {
        val r = service.resolve(pathA, rawFrontmatterId = null, mappedId = validId, ownerOf = { null })
        r.id shouldBe validId
        r.source shouldBe PageIdentityService.Source.ID_MAP
    }

    test("duplicate frontmatter id (copied file): previously-bound path keeps it, copy gets a fresh id + issue") {
        // pathA already owns validId; pathB carries the same frontmatter id.
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(
            pathB,
            rawFrontmatterId = validId.value,
            mappedId = null,
            ownerOf = ownedByA,
        )
        r.source shouldBe PageIdentityService.Source.MINTED
        r.id shouldNotBe validId // freshly minted, not the duplicated id
        val issue = r.issue.shouldBeInstanceOf<IdentityIssue.DuplicateId>()
        issue.id shouldBe validId
        issue.root shouldBe main
        issue.keptPath shouldBe pathA.path
        issue.reassignedPath shouldBe pathB.path
    }

    test("the within-root policy ignores supersedability entirely: the previously-bound path keeps the id either way") {
        // Same root, so no rank contest exists to gate - the §5.2 copied-file rule is frozen and unconditional.
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(
            pathB,
            rawFrontmatterId = validId.value,
            mappedId = null,
            ownerOf = ownedByA,
        )
        r.source shouldBe PageIdentityService.Source.MINTED
        r.issue.shouldBeInstanceOf<IdentityIssue.DuplicateId>()
    }

    test("duplicate rescan is stable: a copy with an existing id_map binding keeps it (source ID_MAP), no fresh mint") {
        // Run 2 of the copied-file scenario: pathB already carries an id_map binding from run 1's
        // reassignment, and still sees the conflicting frontmatter id owned by pathA. It must reuse
        // that binding (stable /p/{root}/{id}), not mint a new id — while still raising the duplicate issue.
        val reassigned = PageId.require("0197b111-2222-7333-8444-555566667777")
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(
            pathB,
            rawFrontmatterId = validId.value,
            mappedId = reassigned,
            ownerOf = ownedByA,
        )
        r.id shouldBe reassigned
        r.source shouldBe PageIdentityService.Source.ID_MAP
        val issue = r.issue.shouldBeInstanceOf<IdentityIssue.DuplicateId>()
        issue.id shouldBe validId
        issue.keptPath shouldBe pathA.path
        issue.reassignedPath shouldBe pathB.path
    }

    test("frontmatter id already bound to THIS path is honored (re-adoption, not a duplicate)") {
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(
            pathA,
            rawFrontmatterId = validId.value,
            mappedId = null,
            ownerOf = ownedByA,
        )
        r.source shouldBe PageIdentityService.Source.FRONTMATTER
        r.id shouldBe validId
        r.issue.shouldBeNull()
    }

    test("a cross-root duplicate is NOT a contest (per-root identity, C5): BOTH roots keep the same id, no issue") {
        // validId lives in main (pathA) AND extra (pathX). `ownerOf` is ROOT-SCOPED in production, so resolving one
        // root's page never sees the other root's owner: main re-adopts its own id, extra keeps the SAME id, neither
        // reassigns and NO IdentityIssue is raised. This ONE case replaces the seven D17/D16 cross-root rank-contest
        // cases the flip dissolved (a single-root pin would go vacuously green - both roots are asserted here).
        val ownsInMain = { id: PageId -> if (id == validId) pathA else null } // main's own root-scoped owner
        val inMain = service.resolve(pathA, rawFrontmatterId = validId.value, mappedId = null, ownerOf = ownsInMain)
        inMain.id shouldBe validId
        inMain.source shouldBe PageIdentityService.Source.FRONTMATTER
        inMain.issue.shouldBeNull()

        val inExtra = service.resolve(pathX, rawFrontmatterId = validId.value, mappedId = null, ownerOf = { null })
        inExtra.id shouldBe validId // extra keeps validId too - both roots hold their own page under it
        inExtra.source shouldBe PageIdentityService.Source.FRONTMATTER
        inExtra.issue.shouldBeNull()
    }

    // ---- a loser's mappedId is a CLAIM like any other, and it can be lost like any other ------------
    //
    // The reassignment gate reads `ownerOf(mappedId)`, not `mappedId != the contested id`. The difference is
    // a loser whose binding names a DIFFERENT id that another claimant of this same pass has already won:
    // an id comparison sees nothing wrong with it, hands it over, and two live pages end up holding one id -
    // PageIndex's byRootedId check throws, AFTER the durable binds have run. Both duplicate arms share the gate,
    // so both cases are pinned.

    test("within-root loser: a mappedId ANOTHER claimant has already won is never reused - it mints fresh") {
        val stale = PageId.require("0197b111-2222-7333-8444-555566667777")
        // The reassign gate reads ownerOf(mappedId), not `mappedId != contested`. Here pathA (same root) owns BOTH
        // the contested frontmatter id AND the stale id pathB's own id_map row still names, so pathB's stale mapped
        // id has been won by another live page: reusing it would hand one id to two live pages in one root. Mint fresh.
        val owners = { id: PageId -> if (id == validId || id == stale) pathA else null }
        val r = service.resolve(
            pathB,
            rawFrontmatterId = validId.value,
            mappedId = stale,
            ownerOf = owners,
        )
        r.source shouldBe PageIdentityService.Source.MINTED
        r.id shouldNotBe stale
        r.issue.shouldBeInstanceOf<IdentityIssue.DuplicateId>() // still the copied-file duplicate it is
    }
})

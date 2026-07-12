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
 * canonical-shape validity gate for frontmatter ids, the §5.2 within-root duplicate-id policy
 * (frozen; the pre-C2 cases below are regression pins, not rewrites), and the C2 cross-root
 * rank-contest arms (ADR-0011 D17) — including the D16 arm where rank is NOT enough, because the
 * owner sits in a root the pass never scanned. Pure logic with a deterministic [TestIdProvider] so
 * minted ids are predictable (and never collide with the fixtures' frontmatter ids); the rank source
 * is a plain map lookup standing in for the registry.
 */
class PageIdentityServiceTest : FunSpec({

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    // Registry (D7) order: extra declared FIRST outranks main - no rank-0-main assumption anywhere.
    val rank = mapOf(extra to 0, main to 1)
    val service = PageIdentityService(TestIdProvider(), rank::getValue)

    val pathA = RootedPath(main, TreePath.require("guides/a.md"))
    val pathB = RootedPath(main, TreePath.require("guides/b.md"))
    val pathX = RootedPath(extra, TreePath.require("guides/x.md"))
    val validId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    // The full-visibility pass (every root scanned - the runtime steady state since C4): every owner is
    // contestable, so rank alone decides. The D16 arm below flips this to model an owner the pass could not see.
    val everyRootScanned = { _: RootedPath -> true }
    val ownerRootUnscanned = { _: RootedPath -> false }
    val noOwner = { _: PageId -> null }

    test("valid frontmatter id wins over id_map and minting") {
        val a = service.resolve(
            pathA,
            rawFrontmatterId = validId.value,
            mappedId = null,
            ownerOf = noOwner,
            supersedable = everyRootScanned,
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
            supersedable = everyRootScanned,
        )
        r.id shouldBe mapped
        r.source shouldBe PageIdentityService.Source.ID_MAP
    }

    test("a well-formed v4 frontmatter id is accepted as valid identity (version-agnostic, owner ruling)") {
        val v4 = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        val r = service.resolve(pathA, rawFrontmatterId = v4, mappedId = null, ownerOf = { null }, supersedable = everyRootScanned)
        r.source shouldBe PageIdentityService.Source.FRONTMATTER
        r.id shouldBe PageId.require(v4)
    }

    test("no valid frontmatter id, no map entry -> a fresh UUIDv7 is minted") {
        val r = service.resolve(pathA, rawFrontmatterId = null, mappedId = null, ownerOf = { null }, supersedable = everyRootScanned)
        r.source shouldBe PageIdentityService.Source.MINTED
    }

    test("no valid frontmatter id, map entry present -> the map entry is kept") {
        val r = service.resolve(pathA, rawFrontmatterId = null, mappedId = validId, ownerOf = { null }, supersedable = everyRootScanned)
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
            supersedable = everyRootScanned,
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
            supersedable = ownerRootUnscanned,
        )
        r.source shouldBe PageIdentityService.Source.MINTED
        r.issue.shouldBeInstanceOf<IdentityIssue.DuplicateId>()
    }

    test("duplicate rescan is stable: a copy with an existing id_map binding keeps it (source ID_MAP), no fresh mint") {
        // Run 2 of the copied-file scenario: pathB already carries an id_map binding from run 1's
        // reassignment, and still sees the conflicting frontmatter id owned by pathA. It must reuse
        // that binding (stable /p/{id}), not mint a new id — while still raising the duplicate issue.
        val reassigned = PageId.require("0197b111-2222-7333-8444-555566667777")
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(
            pathB,
            rawFrontmatterId = validId.value,
            mappedId = reassigned,
            ownerOf = ownedByA,
            supersedable = everyRootScanned,
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
            supersedable = everyRootScanned,
        )
        r.source shouldBe PageIdentityService.Source.FRONTMATTER
        r.id shouldBe validId
        r.issue.shouldBeNull()
    }

    test("cross-root duplicate: the higher-ranked root WINS the id regardless of the prior binding (D17), no issue on the winner") {
        // main holds the binding, but extra outranks main - rank beats previously-bound. Both roots were
        // scanned, so main's page re-resolves later in the same pass and records its own loser issue there.
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(
            pathX,
            rawFrontmatterId = validId.value,
            mappedId = null,
            ownerOf = ownedByA,
            supersedable = everyRootScanned,
        )
        r.id shouldBe validId
        r.source shouldBe PageIdentityService.Source.FRONTMATTER
        r.issue.shouldBeNull() // the winner's page never carries the issue
    }

    test("D16: an outranking claimant whose owner's root was NOT scanned still LOSES - rank cannot settle a contest one side missed") {
        // The same inputs as the winner case above, with the ONE difference that matters: the pass could
        // not look at main's disk (it is unavailable, or this is a single-root adopt). Superseding would
        // delete a durable binding we have no authority over, while main's carried section still holds the
        // page - so extra's page reassigns instead, and the contest waits for a pass that can see both.
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(
            pathX,
            rawFrontmatterId = validId.value,
            mappedId = null,
            ownerOf = ownedByA,
            supersedable = ownerRootUnscanned,
        )
        r.id shouldNotBe validId
        r.source shouldBe PageIdentityService.Source.MINTED
        val issue = r.issue.shouldBeInstanceOf<IdentityIssue.CrossRootDuplicateId>()
        issue.id shouldBe validId
        issue.kept shouldBe pathA // the unscanned owner keeps it
        issue.reassigned shouldBe pathX
    }

    test("D16 rescan stability: the reassigned claimant reuses its own binding while the unscanned owner keeps the id") {
        // Pass 2 of the case above (the outage has not ended): the claimant must not mint a NEW id every
        // rebuild, or its permalink would churn for the whole outage.
        val reassigned = PageId.require("0197b111-2222-7333-8444-555566667777")
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(
            pathX,
            rawFrontmatterId = validId.value,
            mappedId = reassigned,
            ownerOf = ownedByA,
            supersedable = ownerRootUnscanned,
        )
        r.id shouldBe reassigned
        r.source shouldBe PageIdentityService.Source.ID_MAP
        r.issue.shouldBeInstanceOf<IdentityIssue.CrossRootDuplicateId>()
    }

    test("cross-root duplicate: the lower-ranked root LOSES even when it holds the binding, and records the issue") {
        // extra holds the binding AND outranks main: main's page is reassigned (detection order flipped).
        val ownedByX = { id: PageId -> if (id == validId) pathX else null }
        val r = service.resolve(
            pathA,
            rawFrontmatterId = validId.value,
            mappedId = null,
            ownerOf = ownedByX,
            supersedable = everyRootScanned,
        )
        r.source shouldBe PageIdentityService.Source.MINTED
        r.id shouldNotBe validId
        val issue = r.issue.shouldBeInstanceOf<IdentityIssue.CrossRootDuplicateId>()
        issue.id shouldBe validId
        issue.kept shouldBe pathX
        issue.reassigned shouldBe pathA
    }

    test("cross-root loser rescan is stable: a distinct id_map binding is reused (source ID_MAP), no fresh mint") {
        val reassigned = PageId.require("0197b111-2222-7333-8444-555566667777")
        val ownedByX = { id: PageId -> if (id == validId) pathX else null }
        val r = service.resolve(
            pathA,
            rawFrontmatterId = validId.value,
            mappedId = reassigned,
            ownerOf = ownedByX,
            supersedable = everyRootScanned,
        )
        r.id shouldBe reassigned
        r.source shouldBe PageIdentityService.Source.ID_MAP
        r.issue.shouldBeInstanceOf<IdentityIssue.CrossRootDuplicateId>()
    }

    test("D17 mint guard: a loser whose own stale binding IS the contested id MINTS FRESH (the prior-owner steal/crash case)") {
        // Unreachable in every pass (bind-inline + UNIQUE(id) preclude it) - this synthetic ownerOf is the
        // one place the belt CAN be exercised; see the service KDoc.
        val ownedByX = { id: PageId -> if (id == validId) pathX else null }
        val r = service.resolve(
            pathA,
            rawFrontmatterId = validId.value,
            mappedId = validId,
            ownerOf = ownedByX,
            supersedable = everyRootScanned,
        )
        r.source shouldBe PageIdentityService.Source.MINTED
        r.id shouldNotBe validId // reusing it would steal the winner's fresh row or crash byId uniqueness
        r.issue.shouldBeInstanceOf<IdentityIssue.CrossRootDuplicateId>()
    }
})

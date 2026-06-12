package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.UuidV7
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random

/**
 * PageIdentityService — the frozen precedence (frontmatter id > id_map > minted UUIDv7), the §A4
 * canonical-shape validity gate for frontmatter ids, and the §5.2 duplicate-id policy. Pure logic
 * with a fixed clock+random UuidV7 so minted ids are deterministic.
 */
class PageIdentityServiceTest : FunSpec({

    val mintedClock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    val service = PageIdentityService(UuidV7(mintedClock, Random(0)))

    val pathA = TreePath.require("guides/a.md")
    val pathB = TreePath.require("guides/b.md")
    val validId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    test("valid frontmatter id wins over id_map and minting") {
        val a = service.resolve(pathA, rawFrontmatterId = validId.value, mappedId = null, ownerOf = { null })
        a.id shouldBe validId
        a.source shouldBe PageIdentityService.Source.FRONTMATTER
        a.issue.shouldBeNull()
    }

    test("a shape-invalid frontmatter id is treated as absent (§A4 spec-owned validity, not JDK leniency)") {
        // `1-1-1-1-1` is JDK-lenient-valid but not canonical-shape -> absent -> id_map entry kept.
        val mapped = PageId.require("f47ac10b-58cc-4372-a567-0e02b2c3d479")
        val r = service.resolve(pathA, rawFrontmatterId = "1-1-1-1-1", mappedId = mapped, ownerOf = { null })
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
        r.id.uuid.version() shouldBe 7
    }

    test("no valid frontmatter id, map entry present -> the map entry is kept") {
        val r = service.resolve(pathA, rawFrontmatterId = null, mappedId = validId, ownerOf = { null })
        r.id shouldBe validId
        r.source shouldBe PageIdentityService.Source.ID_MAP
    }

    test("duplicate frontmatter id (copied file): previously-bound path keeps it, copy gets a fresh id + issue") {
        // pathA already owns validId; pathB carries the same frontmatter id.
        val r = service.resolve(pathB, rawFrontmatterId = validId.value, mappedId = null, ownerOf = { if (it == validId) pathA else null })
        r.source shouldBe PageIdentityService.Source.MINTED
        r.id.uuid.version() shouldBe 7
        r.id shouldNotBe validId // freshly minted, not the duplicated id
        val issue = r.issue.shouldBeInstanceOf<IdentityIssue.DuplicateId>()
        issue.id shouldBe validId
        issue.keptPath shouldBe pathA
        issue.reassignedPath shouldBe pathB
    }

    test("duplicate rescan is stable: a copy with an existing id_map binding keeps it (source ID_MAP), no fresh mint") {
        // Run 2 of the copied-file scenario: pathB already carries an id_map binding from run 1's
        // reassignment, and still sees the conflicting frontmatter id owned by pathA. It must reuse
        // that binding (stable /p/{id}), not mint a new id — while still raising the duplicate issue.
        val reassigned = PageId.require("0197b111-2222-7333-8444-555566667777")
        val ownedByA = { id: PageId -> if (id == validId) pathA else null }
        val r = service.resolve(pathB, rawFrontmatterId = validId.value, mappedId = reassigned, ownerOf = ownedByA)
        r.id shouldBe reassigned
        r.source shouldBe PageIdentityService.Source.ID_MAP
        val issue = r.issue.shouldBeInstanceOf<IdentityIssue.DuplicateId>()
        issue.id shouldBe validId
        issue.keptPath shouldBe pathA
        issue.reassignedPath shouldBe pathB
    }

    test("frontmatter id already bound to THIS path is honored (re-adoption, not a duplicate)") {
        val r = service.resolve(pathA, rawFrontmatterId = validId.value, mappedId = null, ownerOf = { if (it == validId) pathA else null })
        r.source shouldBe PageIdentityService.Source.FRONTMATTER
        r.id shouldBe validId
        r.issue.shouldBeNull()
    }
})

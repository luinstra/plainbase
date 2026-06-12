package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/**
 * Runtime temp-dir criteria (chunk 5): the same-parent slug collision (`a b.md` vs `a-b.md` —
 * raw-byte-order winner, loser `url = null` yet id-resolvable, `path_slug_collision` persisted),
 * the ADR-0002 page-vs-folder NON-collision, move-alias recording with chain collapse across two
 * moves, `redirect_from` conflicts, and the live-canonical shadow drop. All built at runtime —
 * never trusting committed odd filenames.
 */
class IndexBuilderRescanTest : FunSpec({

    val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    fun pageWithId(title: String) = "---\nid: ${pageId.value}\ntitle: $title\n---\n\n# $title\n"

    test("slug collision: raw-byte-order winner owns the path; loser is id-only with a persisted issue") {
        withTempTree(seed = { root ->
            writePage(root, "a b.md", "---\ntitle: Spaced\n---\n\n# Spaced\n")
            writePage(root, "a-b.md", "---\ntitle: Hyphenated\n---\n\n# Hyphenated\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()

                val winner = snapshot.byPath.getValue(TreePath.require("a b.md"))
                val loser = snapshot.byPath.getValue(TreePath.require("a-b.md"))
                winner.url shouldBe "/docs/a-b" // 'a b.md' (0x20 at index 1) sorts before 'a-b.md' (0x2D)
                loser.url.shouldBeNull()
                loser.slug shouldBe "a-b" // the slug itself is uncontested; only the path is

                // The loser remains fully resolvable by id; emitted links go to its permalink (§A4).
                snapshot.byId.getValue(loser.id) shouldBe loser
                snapshot.byUrlPath[TreePath.require("a-b")] shouldBe winner
                snapshot.pageUrl(loser.path) shouldBe "/p/${loser.id.value}"

                harness.idMap.issues().filterIsInstance<IdentityIssue.PathSlugCollision>() shouldContainExactly listOf(
                    IdentityIssue.PathSlugCollision(keptPath = winner.path, loserPath = loser.path),
                )
            }
        }
    }

    test("a page and a sibling folder sharing a slug both resolve — no collision (ADR-0002)") {
        withTempTree(seed = { root ->
            writePage(root, "docs/setup.md", "---\ntitle: Setup\n---\n\n# Setup\n")
            writePage(root, "docs/setup/intro.md", "---\ntitle: Intro\n---\n\n# Intro\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()
                snapshot.byPath.getValue(TreePath.require("docs/setup.md")).url shouldBe "/docs/docs/setup"
                snapshot.byPath.getValue(TreePath.require("docs/setup/intro.md")).url shouldBe "/docs/docs/setup/intro"
                harness.idMap.issues().filterIsInstance<IdentityIssue.PathSlugCollision>() shouldBe emptyList()
            }
        }
    }

    test("move alias: re-index after a move records old path -> id; a second move collapses the chain") {
        withTempTree(seed = { root ->
            writePage(root, "docs/start.md", pageWithId("Start"))
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild().byId.getValue(pageId).url shouldBe "/docs/docs/start"

                // Move 1: docs/start.md -> archive/start.md (the id travels in the frontmatter).
                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/start.md"), root.resolve("archive/start.md"))
                val afterFirst = harness.builder.rebuild()
                afterFirst.byId.getValue(pageId).url shouldBe "/docs/archive/start"
                harness.registry.all() shouldContainExactly mapOf(TreePath.require("docs/start") to pageId)

                // Move 2: the chain collapses — BOTH old paths map straight to the id, one hop each.
                Files.createDirectories(root.resolve("attic"))
                Files.move(root.resolve("archive/start.md"), root.resolve("attic/start.md"))
                harness.builder.rebuild().byId.getValue(pageId).url shouldBe "/docs/attic/start"
                harness.registry.all() shouldContainExactly mapOf(
                    TreePath.require("docs/start") to pageId,
                    TreePath.require("archive/start") to pageId,
                )
                // Persisted, not just in-memory (the registry is write-through to url_alias).
                harness.aliases.find(TreePath.require("docs/start")) shouldBe pageId
                harness.aliases.find(TreePath.require("archive/start")) shouldBe pageId
            }
        }
    }

    test("a slug-only change is a move too: editing frontmatter slug records the old canonical path") {
        withTempTree(seed = { root ->
            writePage(root, "guide.md", pageWithId("Guide"))
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild().byId.getValue(pageId).url shouldBe "/docs/guide"
                writePage(root, "guide.md", "---\nid: ${pageId.value}\ntitle: Guide\nslug: handbook\n---\n\n# Guide\n")
                harness.builder.rebuild().byId.getValue(pageId).url shouldBe "/docs/handbook"
                harness.registry.find(TreePath.require("guide")) shouldBe pageId
            }
        }
    }

    test("live canonical shadows an alias: the alias is dropped and a redirect_conflict issue persisted") {
        withTempTree(seed = { root ->
            writePage(root, "docs/start.md", pageWithId("Start"))
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/start.md"), root.resolve("archive/start.md"))
                harness.builder.rebuild()
                harness.registry.find(TreePath.require("docs/start")) shouldBe pageId

                // A NEW page (fresh identity) now claims the vacated canonical path: live wins.
                writePage(root, "docs/start.md", "---\ntitle: New Start\n---\n\n# New Start\n")
                val snapshot = harness.builder.rebuild()
                val newcomer = snapshot.byUrlPath.getValue(TreePath.require("docs/start"))
                newcomer.id shouldNotBe pageId

                harness.registry.find(TreePath.require("docs/start")).shouldBeNull()
                harness.aliases.find(TreePath.require("docs/start")).shouldBeNull()
                harness.idMap.issues().filterIsInstance<IdentityIssue.RedirectConflict>()
                    .single { it.path == TreePath.require("docs/start") }
                    .message shouldBe "alias to page $pageId dropped: shadowed by a live canonical path"
            }
        }
    }

    test("redirect_from claiming a live canonical path is refused with a redirect_conflict issue") {
        withTempTree(seed = { root ->
            writePage(root, "real.md", "---\ntitle: Real\n---\n\n# Real\n")
            writePage(root, "wannabe.md", "---\ntitle: Wannabe\nredirect_from: [/real.md]\n---\n\n# Wannabe\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                harness.registry.find(TreePath.require("real")).shouldBeNull() // never registered
                harness.idMap.issues().filterIsInstance<IdentityIssue.RedirectConflict>()
                    .single().path shouldBe TreePath.require("real")
            }
        }
    }

    test("redirect_from registers and survives a rescan; scalar form is accepted (the §C2 list collapse)") {
        withTempTree(seed = { root ->
            writePage(root, "guide.md", "---\ntitle: Guide\nredirect_from: /old/guide.md\n---\n\n# Guide\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val first = harness.builder.rebuild()
                val id = first.byPath.getValue(TreePath.require("guide.md")).id
                harness.registry.find(TreePath.require("old/guide")) shouldBe id
                harness.builder.rebuild() // idempotent across rescans
                harness.registry.find(TreePath.require("old/guide")) shouldBe id
                harness.idMap.issues().filterIsInstance<IdentityIssue.RedirectConflict>() shouldBe emptyList()
            }
        }
    }

    test("an NFC scan collision is persisted as a path_collision identity issue") {
        withTempTree(seed = { root ->
            // Runtime NFD/NFC pair (the FIXTURES.md rule: never trust committed NFD names). On a
            // normalization-on-create FS only one file lands and no issue is expected.
            writePage(root, "re\u0301union.md", "NFD content\n") // e + combining acute, built at runtime
            writePage(root, "r\u00e9union.md", "NFC content\n") // precomposed e-acute
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()
                val onDisk = Files.newDirectoryStream(root).use { stream -> stream.toList() }
                val issues = harness.idMap.issues().filterIsInstance<IdentityIssue.PathCollision>()
                if (onDisk.size == 2) {
                    val issue = issues.single()
                    issue.keptPath shouldBe TreePath.require("r\u00e9union.md")
                    // NFD raw bytes sort first (0x65 < 0xc3), so here the precomposed NFC name is
                    // the excluded loser \u2014 persisted verbatim as the on-disk string, never rebuilt
                    // through TreePath normalization.
                    issue.loserRawName shouldBe "r\u00e9union.md"
                } else {
                    issues shouldBe emptyList()
                }
                snapshot.byPath.getValue(TreePath.require("r\u00e9union.md")).shouldNotBeNull()
            }
        }
    }
})

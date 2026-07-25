package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
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
    val mainPage = RootedPageId(RootName.MAIN, pageId)

    fun pageWithId(title: String) = "---\nid: ${pageId.value}\ntitle: $title\n---\n\n# $title\n"

    fun rooted(path: String) = RootedPath(RootName.MAIN, TreePath.require(path))

    test("slug collision: raw-byte-order winner owns the path; loser is id-only with a persisted issue") {
        withTempTree(seed = { root ->
            writePage(root, "a b.md", "---\ntitle: Spaced\n---\n\n# Spaced\n")
            writePage(root, "a-b.md", "---\ntitle: Hyphenated\n---\n\n# Hyphenated\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()

                val winner = snapshot.byPath.getValue(rooted("a b.md"))
                val loser = snapshot.byPath.getValue(rooted("a-b.md"))
                winner.url shouldBe "/docs/main/a-b" // 'a b.md' (0x20 at index 1) sorts before 'a-b.md' (0x2D)
                loser.url.shouldBeNull()
                loser.slug shouldBe "a-b" // the slug itself is uncontested; only the path is

                // The loser remains fully resolvable by id; emitted links go to its permalink (§A4).
                snapshot.pageAt(loser.rooted)!! shouldBe loser
                snapshot.byUrlPath[rooted("a-b")] shouldBe winner
                snapshot.view(RootName.MAIN).pageUrl(loser.path) shouldBe "/p/main/${loser.id.value}"

                harness.idMap.issues().filterIsInstance<IdentityIssue.PathSlugCollision>() shouldContainExactly listOf(
                    IdentityIssue.PathSlugCollision(root = RootName.MAIN, keptPath = winner.path, loserPath = loser.path),
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
                snapshot.byPath.getValue(rooted("docs/setup.md")).url shouldBe "/docs/main/docs/setup"
                snapshot.byPath.getValue(rooted("docs/setup/intro.md")).url shouldBe "/docs/main/docs/setup/intro"
                harness.idMap.issues().filterIsInstance<IdentityIssue.PathSlugCollision>() shouldBe emptyList()
            }
        }
    }

    test("move alias: re-index after a move records old path -> id; a second move collapses the chain") {
        withTempTree(seed = { root ->
            writePage(root, "docs/start.md", pageWithId("Start"))
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild().pageAt(RootedPageId(RootName.MAIN, pageId))!!.url shouldBe "/docs/main/docs/start"

                // Move 1: docs/start.md -> archive/start.md (the id travels in the frontmatter).
                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/start.md"), root.resolve("archive/start.md"))
                val afterFirst = harness.builder.rebuild()
                afterFirst.pageAt(RootedPageId(RootName.MAIN, pageId))!!.url shouldBe "/docs/main/archive/start"
                harness.registry.all() shouldContainExactly mapOf(rooted("docs/start") to mainPage)

                // Move 2: the chain collapses — BOTH old paths map straight to the id, one hop each.
                Files.createDirectories(root.resolve("attic"))
                Files.move(root.resolve("archive/start.md"), root.resolve("attic/start.md"))
                harness.builder.rebuild().pageAt(RootedPageId(RootName.MAIN, pageId))!!.url shouldBe "/docs/main/attic/start"
                harness.registry.all() shouldContainExactly mapOf(
                    rooted("docs/start") to mainPage,
                    rooted("archive/start") to mainPage,
                )
                // Persisted, not just in-memory (the registry is write-through to url_alias).
                harness.aliases.find(rooted("docs/start")) shouldBe mainPage
                harness.aliases.find(rooted("archive/start")) shouldBe mainPage
            }
        }
    }

    test("a move alias never displaces a new live canonical that claims the vacated path") {
        withTempTree(seed = { root ->
            writePage(root, "docs/start.md", pageWithId("Start"))
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()

                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("docs/start.md"), root.resolve("archive/start.md"))
                writePage(root, "docs/start.md", "---\ntitle: New Start\n---\n\n# New Start\n")

                val snapshot = harness.builder.rebuild()
                snapshot.pageAt(RootedPageId(RootName.MAIN, pageId))!!.url shouldBe "/docs/main/archive/start"
                snapshot.byUrlPath.getValue(rooted("docs/start")).id shouldNotBe pageId
                harness.registry.find(rooted("docs/start")).shouldBeNull()
                harness.idMap.issues().filterIsInstance<IdentityIssue.RedirectConflict>()
                    .single { it.path == TreePath.require("docs/start") }
                    .message shouldBe "move alias for page $pageId dropped: shadowed by a live canonical path"
            }
        }
    }

    test("a slug-only change is a move too: editing frontmatter slug records the old canonical path") {
        withTempTree(seed = { root ->
            writePage(root, "guide.md", pageWithId("Guide"))
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild().pageAt(RootedPageId(RootName.MAIN, pageId))!!.url shouldBe "/docs/main/guide"
                writePage(root, "guide.md", "---\nid: ${pageId.value}\ntitle: Guide\nslug: handbook\n---\n\n# Guide\n")
                harness.builder.rebuild().pageAt(RootedPageId(RootName.MAIN, pageId))!!.url shouldBe "/docs/main/handbook"
                harness.registry.find(rooted("guide")) shouldBe mainPage
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
                harness.registry.find(rooted("docs/start")) shouldBe mainPage

                // A NEW page (fresh identity) now claims the vacated canonical path: live wins.
                writePage(root, "docs/start.md", "---\ntitle: New Start\n---\n\n# New Start\n")
                val snapshot = harness.builder.rebuild()
                val newcomer = snapshot.byUrlPath.getValue(rooted("docs/start"))
                newcomer.id shouldNotBe pageId

                harness.registry.find(rooted("docs/start")).shouldBeNull()
                harness.aliases.find(rooted("docs/start")).shouldBeNull()
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
                harness.registry.find(rooted("real")).shouldBeNull() // never registered
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
                val id = first.byPath.getValue(rooted("guide.md")).id
                harness.registry.find(rooted("old/guide")) shouldBe RootedPageId(RootName.MAIN, id)
                harness.builder.rebuild() // idempotent across rescans
                harness.registry.find(rooted("old/guide")) shouldBe RootedPageId(RootName.MAIN, id)
                harness.idMap.issues().filterIsInstance<IdentityIssue.RedirectConflict>() shouldBe emptyList()
            }
        }
    }

    test("redirect_from onto a path already aliased to a DIFFERENT page names the incumbent's BARE id, not its RootedPageId") {
        val incumbentId = PageId.require("01111111-1111-7111-8111-111111111111")
        withTempTree(seed = { root ->
            writePage(
                root,
                "incumbent.md",
                "---\nid: ${incumbentId.value}\ntitle: Incumbent\nredirect_from: [/shared]\n---\n\n# Incumbent\n",
            )
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                harness.registry.find(rooted("shared")) shouldBe RootedPageId(RootName.MAIN, incumbentId)

                // A DIFFERENT page now declares the SAME redirect_from path: the incumbent keeps the alias, and the
                // conflict message names the incumbent by its BARE id - a RootedPageId interpolation would leak the
                // wrapper text.
                writePage(root, "latecomer.md", "---\ntitle: Latecomer\nredirect_from: [/shared]\n---\n\n# Latecomer\n")
                harness.builder.rebuild()

                harness.registry.find(rooted("shared")) shouldBe RootedPageId(RootName.MAIN, incumbentId)
                harness.idMap.issues().filterIsInstance<IdentityIssue.RedirectConflict>()
                    .single { it.path == TreePath.require("shared") }
                    .message shouldBe "redirect_from of latecomer.md ignored: already an alias of page $incumbentId"
            }
        }
    }

    test("the first page claiming a redirect_from keeps it when another page makes the same claim") {
        withTempTree(seed = { root ->
            writePage(root, "alpha.md", "---\ntitle: Alpha\nredirect_from: /shared.md\n---\n\n# Alpha\n")
            writePage(root, "beta.md", "---\ntitle: Beta\nredirect_from: /shared.md\n---\n\n# Beta\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()
                val alpha = snapshot.byPath.getValue(rooted("alpha.md"))

                harness.registry.find(rooted("shared")) shouldBe RootedPageId(RootName.MAIN, alpha.id)
                harness.idMap.issues().filterIsInstance<IdentityIssue.RedirectConflict>()
                    .single { it.path == TreePath.require("shared") }
                    .message shouldBe
                    "redirect_from of beta.md ignored: already an alias of page ${alpha.id}"
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
                snapshot.byPath.getValue(rooted("r\u00e9union.md")).shouldNotBeNull()
            }
        }
    }
})

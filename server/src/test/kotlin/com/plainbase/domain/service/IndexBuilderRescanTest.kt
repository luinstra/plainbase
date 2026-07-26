package com.plainbase.domain.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
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

    // The sibling of the move test above, and the two are why the path-reuse gate cannot key on "the witnessed file
    // carries no id". BOTH pages here carry no id in their file; only `materialized` tells them apart. Above, the
    // incumbent PROMISED its id was in the file (materialized) and the file does not have it -> the path was reused.
    // Here the incumbent never promised anything (pre-materialized identity is path-keyed, `suspectDrafts`' rule) ->
    // it is simply witnessing itself, and a pasted copy must not take its permalink.
    test("a pasted copy does NOT take an UNMATERIALIZED page's id: the previously-bound path keeps it (master plan 5.2)") {
        withTempTree(seed = { root ->
            writePage(root, "original.md", "---\ntitle: Original\n---\n\n# Original\n") // no `id:`: id_map-only identity
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val assigned = harness.idMap.find(rooted("original.md")).shouldNotBeNull().id
                harness.idMap.find(rooted("original.md")).shouldNotBeNull().materialized shouldBe false

                writePage(root, "copy.md", "---\nid: ${assigned.value}\ntitle: Pasted Copy\n---\n\n# Pasted Copy\n")

                // The conflict never self-heals (the patcher refuses to overwrite copy.md's `id:` line), so the
                // ONLY way the operator learns of it is this WARN - the issues table has no production reader.
                val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                rootLogger.addAppender(appender)
                val snapshot = try {
                    harness.builder.rebuild()
                } finally {
                    rootLogger.detachAppender(appender)
                }
                // filter-then-size, not `single {}`: a miss here should read as "expected 1, got 0", not as a
                // bare NoSuchElementException from the matcher itself.
                val warns = appender.list.filter { it.level == Level.WARN && "duplicate_id" in it.formattedMessage }
                warns shouldHaveSize 1
                val warned = warns.single()
                // The WARN must name BOTH paths: "there is an issue" is not actionable, "original.md keeps it,
                // copy.md was reassigned" is. Asserting only the kind would pass on a message that named neither.
                warned.formattedMessage shouldContain "original.md resolved first"
                warned.formattedMessage shouldContain "copy.md reassigned"

                // The incumbent keeps the id and its permalink; the paste reassigns and is reported.
                snapshot.pageAt(RootedPageId(RootName.MAIN, assigned)).shouldNotBeNull().path shouldBe TreePath.require("original.md")
                val copyId = harness.idMap.find(rooted("copy.md")).shouldNotBeNull().id
                copyId shouldNotBe assigned
                val issue = harness.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>().single()
                issue.keptPath shouldBe TreePath.require("original.md")
                issue.reassignedPath shouldBe TreePath.require("copy.md")

                // RESCAN STABILITY: the steady state is stable, not oscillating. copy.md keeps its reassigned id
                // even though its file still asserts the other one, so the divergence is untidy, not harmful.
                val again = harness.builder.rebuild()
                again.pageAt(RootedPageId(RootName.MAIN, assigned)).shouldNotBeNull().path shouldBe TreePath.require("original.md")
                harness.idMap.find(rooted("copy.md")).shouldNotBeNull().id shouldBe copyId
                harness.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>() shouldHaveSize 1
            }
        }
    }

    // The THIRD row of the path-reuse gate, and the one that pins its narrowing. `a.md` re-identifies itself to Y
    // while `b.md` claims a.md's old id X. Treating "the file carries a DIFFERENT id" as freeing X made the
    // resolver award X to b.md, and the bind then REFUSED it (a.md's re-identification tombstones X at a.md, and
    // a tombstone is reclaimable only by the page returning to its own path) - resolver and bind gate disagreeing,
    // which throws. X is not free: it is retired.
    test("two files swapping ids: the claimant reassigns and the abandoned id is RETIRED, not handed over") {
        withTempTree(seed = { root ->
            writePage(root, "a.md", "---\nid: ${pageId.value}\ntitle: A\n---\n\n# A\n")
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val other = PageId.require("0197b555-1111-7222-8333-4444555566aa")
                writePage(root, "a.md", "---\nid: ${other.value}\ntitle: A\n---\n\n# A\n")
                writePage(root, "b.md", "---\nid: ${pageId.value}\ntitle: B\n---\n\n# B\n")

                val snapshot = harness.builder.rebuild() // must not throw

                snapshot.byPath.getValue(rooted("a.md")).id shouldBe other // a.md re-identified successfully
                snapshot.byPath.getValue(rooted("b.md")).id shouldNotBe pageId // b.md did NOT get to take X
                // X is retired at the path that abandoned it, so /p/{root}/{X} answers 410 rather than silently
                // resolving to b.md - the same "a dead link announces itself" rule the tombstone reservation exists for.
                harness.idMap.retiredAt(RootName.MAIN, pageId).shouldNotBeNull().path shouldBe rooted("a.md")
                // The contest IS reported, naming both paths. Note the wording is imprecise in this one corpus:
                // "kept by a.md" was true at resolve time, but a.md then re-identified to Y, so in the end NOBODY
                // holds X. It is not backwards - b.md really did lose - and the operator still gets both paths and
                // a 410, so this is recorded as known imprecision rather than fixed here.
                val issue = harness.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>().single()
                issue.id shouldBe pageId
                issue.keptPath shouldBe TreePath.require("a.md")
                issue.reassignedPath shouldBe TreePath.require("b.md")
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

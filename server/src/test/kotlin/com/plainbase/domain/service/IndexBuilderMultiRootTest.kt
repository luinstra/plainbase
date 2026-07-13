package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * The C2 multi-root matrix over the real IndexBuilder (ADR-0011 D2/D16/D17): the cross-root
 * duplicate-id rank contest at the pass level (including the round-1 panel crash case and the
 * prior-owner mint-guard case, each named), the D16 partial-visibility protection on BOTH sides of
 * the contest - which since C4 means a pass NEVER supersedes a binding under a root it did not
 * scan, however it ranks, and the outage cases that pins - per-root URL space, per-root lowercase
 * rescue, detached/re-add restore semantics, cross-root move aliasing, the rooted checkpoint, and
 * rooted link reports. Registries are synthetic 2-root worlds over temp trees; NO rank-0-main
 * assumption (the extra is seated ahead of main wherever the protection is exercised).
 */
class IndexBuilderMultiRootTest : FunSpec({

    val contested = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    /** A SECOND contested id, the one a loser's own `id_map` row names while another claimant wins it. */
    val mapped = PageId.require("0197b555-1111-7222-8333-444455556666")

    fun identified(id: PageId, title: String = "T") = "---\nid: ${id.value}\ntitle: $title\n---\n\n# $title\n\nbody\n"

    test("the round-1 panel crash case: the same id at the SAME relative path in two roots rebuilds cleanly, winner by registry order") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/page.md", identified(contested))
            writePage(extraDir, "guides/page.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                // No UNIQUE(id) crash; ids globally unique; the registry-order winner (main) owns the id.
                snapshot.byId.getValue(contested).root shouldBe RootName.MAIN
                val loser = snapshot.section(EXTRA).pages.single()
                loser.id shouldNotBe contested
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe
                    IdentityIssue.CrossRootDuplicateId(
                        id = contested,
                        kept = RootedPath(RootName.MAIN, TreePath.require("guides/page.md")),
                        reassigned = RootedPath(EXTRA, TreePath.require("guides/page.md")),
                    )

                // Loser rescan stability: the next rebuild reuses the loser's minted binding.
                val again = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                again.section(EXTRA).pages.single().id shouldBe loser.id
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>() shouldHaveSize 1
            }
        }
    }

    test("different-path cross-root duplicate: the winner follows registry order, never last-scanned (flip pin, D7)") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "a/main-copy.md", identified(contested))
            writePage(extraDir, "b/extra-copy.md", identified(contested))

            World(mainFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                snapshot.byId.getValue(contested).root shouldBe RootName.MAIN
            }
            World(extraFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                snapshot.byId.getValue(contested).root shouldBe EXTRA
            }
        }
    }

    test("the prior-owner case (the D17 mint-guard pin): the prior binder loses the contest, mints fresh, no crash, no steal") {
        withTrees { mainDir, extraDir ->
            // extra is the EXISTING id_map owner of the contested id (two checkouts of one repo);
            // main outranks and presents the same id. Both stay live.
            writePage(mainDir, "guides/doc.md", identified(contested))
            writePage(extraDir, "guides/doc.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(RootedPath(EXTRA, TreePath.require("guides/doc.md")), contested, materialized = true)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                snapshot.byId.getValue(contested).root shouldBe RootName.MAIN // main wins
                val loser = snapshot.section(EXTRA).pages.single()
                loser.id shouldNotBe contested // never reuses its own stale binding of the contested id
                world.idMap.pathOf(contested) shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/doc.md"))
            }
        }
    }

    test("the id_map-ONLY loser records its issue - a permalink is never reassigned SILENTLY") {
        withTrees { mainDir, extraDir ->
            // The loser's ONLY claim on the contested id is its id_map row: it carries no frontmatter id to lose
            // the contest WITH. Under a pass that bound INLINE, the winner's key-complete bind deleted that row
            // before the loser's own draft was ever resolved - so the loser read back unmapped AND unidentified,
            // i.e. indistinguishable from a page nobody had ever seen. It minted a fresh id in silence, and its
            // /p/{id} permalink moved to another page in another root with NO CrossRootDuplicateId recorded
            // anywhere. A durable permalink reassignment with no record is the precise outcome the D16/D17
            // loser-behalf issue recording exists to make impossible.
            writePage(mainDir, "guides/doc.md", identified(contested))
            writePage(extraDir, "guides/orphan.md", "---\ntitle: Orphan\n---\n\n# Orphan\n\nbody\n")
            val orphan = RootedPath(EXTRA, TreePath.require("guides/orphan.md"))
            World(mainFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(orphan, contested, materialized = false)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                snapshot.byId.getValue(contested).root shouldBe RootName.MAIN // main outranks and takes the id
                val loser = snapshot.section(EXTRA).pages.single()
                loser.id shouldNotBe contested
                // THE assertion: the reassignment is RECORDED. Not a mint that looks like a first sighting.
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe
                    IdentityIssue.CrossRootDuplicateId(
                        id = contested,
                        kept = RootedPath(RootName.MAIN, TreePath.require("guides/doc.md")),
                        reassigned = orphan,
                    )
            }
        }
    }

    test("the prior-owner case, registry flipped: the prior binder outranks, keeps the id; the other side mints") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/doc.md", identified(contested))
            writePage(extraDir, "guides/doc.md", identified(contested))
            World(extraFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(RootedPath(EXTRA, TreePath.require("guides/doc.md")), contested, materialized = true)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                snapshot.byId.getValue(contested).root shouldBe EXTRA
                snapshot.section(RootName.MAIN).pages.single().id shouldNotBe contested
            }
        }
    }

    // ---- a loser's id_map binding is a CLAIM, and another claimant can already have WON it ----------
    //
    // Both arms of the duplicate branch reassign a loser through the same gate: keep the loser's own
    // `mappedId` only if nobody else holds it. A loser that reached for its binding blind would hand one id
    // to TWO live pages - PageIndex's byId check throws, AFTER the durable binds have run, so the boot wedges
    // and the winner's root comes back with its binding wrongly moved. Neither shape needs the loser to have
    // contested the mapped id at all, which is why an `id != the contested id` check cannot see them.

    test("within-root loser: its id_map binding, just WON by an outranking root, is never reused - it mints") {
        withTrees { mainDir, extraDir ->
            // a.md and b.md are the §5.2 copied-file pair, so b.md is the within-root loser and reaches for
            // its own binding - which names `mapped`, the id extra (seated ahead) has just taken from it.
            writePage(mainDir, "guides/a.md", identified(contested))
            writePage(mainDir, "guides/b.md", identified(contested))
            writePage(extraDir, "mirror/holder.md", identified(mapped))
            val loser = RootedPath(RootName.MAIN, TreePath.require("guides/b.md"))
            World(extraFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(loser, mapped, materialized = false)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild() // must NOT throw

                snapshot.byId.getValue(mapped).root shouldBe EXTRA // the rank winner keeps it...
                snapshot.byPath.getValue(loser).id shouldNotBe mapped // ...and the loser mints rather than share it
                snapshot.byPath.getValue(loser).id shouldNotBe contested
                snapshot.pages.map { it.id }.toSet() shouldHaveSize snapshot.pages.size
                // Reassigned for the reason it actually lost: the copied frontmatter id, in its own root.
                world.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>().single() shouldBe
                    IdentityIssue.DuplicateId(
                        id = contested,
                        root = RootName.MAIN,
                        keptPath = TreePath.require("guides/a.md"),
                        reassignedPath = loser.path,
                    )
            }
        }
    }

    test("cross-root loser: its id_map binding, just WON by another page of the winning root, is never reused - it mints") {
        withTrees { mainDir, extraDir ->
            // main's page loses `contested` to extra's winner on rank, and its own binding names `mapped` -
            // which extra's OTHER page has just won from it. Two ids lost to one root, in one pass.
            writePage(extraDir, "mirror/holder.md", identified(mapped))
            writePage(extraDir, "mirror/winner.md", identified(contested))
            writePage(mainDir, "guides/claimant.md", identified(contested))
            val loser = RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md"))
            World(extraFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(loser, mapped, materialized = false)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild() // must NOT throw

                snapshot.byId.getValue(contested).path shouldBe TreePath.require("mirror/winner.md")
                snapshot.byId.getValue(mapped).path shouldBe TreePath.require("mirror/holder.md")
                snapshot.byPath.getValue(loser).id shouldNotBe mapped
                snapshot.pages.map { it.id }.toSet() shouldHaveSize snapshot.pages.size
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe
                    IdentityIssue.CrossRootDuplicateId(
                        id = contested,
                        kept = RootedPath(EXTRA, TreePath.require("mirror/winner.md")),
                        reassigned = loser,
                    )
            }
        }
    }

    test("D16, pass page loses: a main-only pass under an extra seated AHEAD leaves the unscanned binding intact and records in-pass") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            World(extraFirst(mainDir, extraDir)).use { world ->
                val foreign = RootedPath(EXTRA, TreePath.require("mirror/page.md"))
                world.idMap.bind(foreign, contested, materialized = true)

                val snapshot = world.builder(mainOnlySource(world.registry, mainDir)).rebuild()

                snapshot.section(RootName.MAIN).pages.single().id shouldNotBe contested // main reassigned
                world.idMap.pathOf(contested) shouldBe foreign // genuine protection, not a vacuous pin
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe
                    IdentityIssue.CrossRootDuplicateId(
                        id = contested,
                        kept = foreign,
                        reassigned = RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md")),
                    )
            }
        }
    }

    test("D16, pass page OUTRANKS an unscanned owner: it STILL loses - a pass never supersedes a binding it has no authority over") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            writePage(extraDir, "mirror/page.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                val foreign = RootedPath(EXTRA, TreePath.require("mirror/page.md"))
                world.idMap.bind(foreign, contested, materialized = true)

                // main OUTRANKS extra and would win a real contest - but this pass cannot see extra's disk,
                // so it cannot know extra still holds the page, and superseding would DELETE a durable
                // binding it has no authority over. main's page reassigns instead.
                world.builder(mainOnlySource(world.registry, mainDir)).rebuild()

                world.idMap.pathOf(contested) shouldBe foreign // the unscanned owner's binding SURVIVES
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe
                    IdentityIssue.CrossRootDuplicateId(
                        id = contested,
                        kept = foreign,
                        reassigned = RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md")),
                    )

                // ...and rank DOES settle it, on the first pass that can actually see both roots: main wins,
                // extra's page reassigns. Both events are audited - the deferral and the contest that finally
                // decided it - so the issues list holds one row per event, not one merged row.
                val full = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                full.byId.getValue(contested).root shouldBe RootName.MAIN
                full.section(EXTRA).pages.single().id shouldNotBe contested
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().last() shouldBe
                    IdentityIssue.CrossRootDuplicateId(
                        id = contested,
                        kept = RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md")),
                        reassigned = foreign,
                    )
            }
        }
    }

    test("D-C4-10: an UNAVAILABLE root's binding survives a higher-ranked claimant, and the carried snapshot holds no duplicate id") {
        // The outage shape of the case above, end to end: extra is DOWN (its section is carried forward
        // verbatim, so it still CONTAINS the contested page), and main - which outranks it - now presents
        // the same frontmatter id. Superseding here would both destroy extra's permalink and put the id in
        // the snapshot TWICE, throwing on PageIndex's byId check and wedging every later rebuild.
        withTrees { mainDir, extraDir ->
            writePage(extraDir, "mirror/page.md", identified(contested))
            writePage(mainDir, "placeholder.md", "# P\n")
            val availability = RootAvailability(Clock.System)
            World(mainFirst(mainDir, extraDir), availability).use { world ->
                // ONE builder across both passes: carrying a section forward means carrying the builder's OWN
                // last-published snapshot, so this is the live-server shape (a root that vanishes mid-run).
                val builder = world.builder(bothSources(world.registry, mainDir, extraDir))
                builder.rebuild()
                val foreign = RootedPath(EXTRA, TreePath.require("mirror/page.md"))
                world.idMap.pathOf(contested) shouldBe foreign

                // extra goes away; main gains a page carrying extra's id (a copy, or a half-finished move).
                extraDir.toFile().deleteRecursively()
                writePage(mainDir, "guides/claimant.md", identified(contested))
                val snapshot = builder.rebuild() // must NOT throw

                availability.current().isAvailable(EXTRA) shouldBe false
                world.idMap.pathOf(contested) shouldBe foreign // durable identity intact through the outage
                snapshot.byId.getValue(contested).root shouldBe EXTRA // still the down root's page, carried
                snapshot.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md"))).id shouldNotBe contested

                // ...and the scheduler/rescan paths keep working: a repeat rebuild neither throws nor churns
                // the claimant's minted id (rescan stability through the whole outage).
                val claimant = snapshot.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md"))).id
                val again = builder.rebuild()
                again.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md"))).id shouldBe claimant
                again.byId.getValue(contested).root shouldBe EXTRA
            }
        }
    }

    test("a carried page whose id a scanned root already holds is dropped from the SNAPSHOT only - its rows and its siblings survive") {
        // The belt behind the rule above, driven from the one state that can still reach it: an id_map that
        // has already LOST the carried page's binding (a row an older build superseded). Without it,
        // PageIndex's duplicate-id check throws and EVERY rebuild after the outage fails.
        withTrees { mainDir, extraDir ->
            writePage(extraDir, "mirror/page.md", identified(contested))
            writePage(extraDir, "mirror/keep.md", "# Keep\n")
            writePage(mainDir, "placeholder.md", "# P\n")
            val availability = RootAvailability(Clock.System)
            World(mainFirst(mainDir, extraDir), availability).use { world ->
                val builder = world.builder(bothSources(world.registry, mainDir, extraDir)) // ONE builder: see above
                builder.rebuild()

                extraDir.toFile().deleteRecursively()
                // The state that reaches the belt, produced through the port's own key-complete bind: the
                // contested id moves to a DETACHED root's row, which deletes extra's binding for it. Nothing
                // now tells the contest that extra owns the id (a detached owner is supersedable, D2) - while
                // extra's carried section still holds the page.
                world.idMap.bind(RootedPath(RootName.require("ghost"), TreePath.require("mirror/page.md")), contested, materialized = true)
                writePage(mainDir, "guides/claimant.md", identified(contested))

                val snapshot = builder.rebuild() // must NOT throw

                // The scanned side (live disk truth) keeps the id; the carried page steps out of the snapshot.
                snapshot.byId.getValue(contested).root shouldBe RootName.MAIN
                snapshot.section(EXTRA).pages.map { it.path.value } shouldBe listOf("mirror/keep.md")
                // Nothing durable was deleted for the down root: its OTHER page's binding is untouched.
                world.idMap.find(RootedPath(EXTRA, TreePath.require("mirror/keep.md"))).shouldNotBeNull()
            }
        }
    }

    test("a binding under an UNREGISTERED root is superseded by a live main claim with NO issue (detached, D2)") {
        withTrees { mainDir, _ ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            World(RootRegistry.of(listOf(localRoot("main", mainDir)))).use { world ->
                world.idMap.bind(RootedPath(RootName.require("ghost"), TreePath.require("mirror/page.md")), contested, materialized = true)

                world.builder(mainOnlySource(world.registry, mainDir)).rebuild()

                world.idMap.pathOf(contested) shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md"))
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().shouldBeEmpty()
            }
        }
    }

    test("detached/re-add restore is conditional: the re-added root's page loses to the live claimant that superseded it") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            writePage(extraDir, "mirror/page.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                // Bound under extra once; then extra leaves the config entirely (detached) while a
                // live main page claims the id: the live bind supersedes the detached row.
                world.idMap.bind(RootedPath(EXTRA, TreePath.require("mirror/page.md")), contested, materialized = true)
                val mainOnly = RootRegistry.of(listOf(localRoot("main", mainDir)))
                world.builder(mainOnlySource(mainOnly, mainDir), registry = mainOnly).rebuild()
                world.idMap.pathOf(contested) shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md"))

                // Re-adding extra does NOT restore its claim: its page now loses the rank contest
                // to the live main owner and mints fresh (the conditional-restore pin).
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                snapshot.byId.getValue(contested).root shouldBe RootName.MAIN
                snapshot.section(EXTRA).pages.single().id shouldNotBe contested
            }
        }
    }

    test("per-root URL space: identical slugs in two roots BOTH keep their urlPath and emit DISTINCT root-qualified urls (C3)") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/setup.md", "# Setup\n\nmain body\n")
            writePage(extraDir, "guides/setup.md", "# Setup\n\nextra body\n")
            // A within-root collision still produces one loser per root ("setup.md" wins by raw byte order).
            writePage(mainDir, "guides/zz-clash.md", "---\nslug: setup\n---\n\n# Clash\n")
            World(mainFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                val mainSetup = snapshot.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("guides/setup.md")))
                val extraSetup = snapshot.byPath.getValue(RootedPath(EXTRA, TreePath.require("guides/setup.md")))
                mainSetup.urlPath.shouldNotBeNull()
                extraSetup.urlPath.shouldNotBeNull()
                // Same relative urlPath, distinct wire urls: the root segment disambiguates (C3, ADR-0011 D3).
                mainSetup.urlPath shouldBe extraSetup.urlPath
                mainSetup.url shouldBe "/docs/main/guides/setup"
                extraSetup.url shouldBe "/docs/extra/guides/setup"

                val mainClash = snapshot.byPath.getValue(RootedPath(RootName.MAIN, TreePath.require("guides/zz-clash.md")))
                mainClash.urlPath.shouldBeNull() // the within-root loser (raw-byte-order winner keeps it)
                world.idMap.issues().filterIsInstance<IdentityIssue.PathSlugCollision>().single().root shouldBe RootName.MAIN
            }
        }
    }

    test("lowercase rescue is scoped per root: a case-variant in one root never rescues in the other") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "Docs/Page.md", "# Main\n")
            writePage(extraDir, "other.md", "# Extra\n")
            World(mainFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                val probe = TreePath.require("docs/page.md")
                snapshot.view(RootName.MAIN).caseInsensitiveMatches(probe) shouldBe listOf(TreePath.require("Docs/Page.md"))
                snapshot.view(EXTRA).caseInsensitiveMatches(probe).shouldBeEmpty()
            }
        }
    }

    test("a cross-root move records its alias in the OLD root's namespace") {
        withTrees { mainDir, extraDir ->
            writePage(extraDir, "notes/origin.md", identified(contested, title = "Origin"))
            World(mainFirst(mainDir, extraDir)).use { world ->
                writePage(mainDir, "placeholder.md", "# P\n") // main is never empty in practice
                val builder = world.builder(bothSources(world.registry, mainDir, extraDir))
                builder.rebuild()

                // The page moves roots (same id travels in the file), old copy gone; the SAME
                // builder rebuilds, so the previous-snapshot comparison path is the one exercised.
                Files.delete(extraDir.resolve("notes/origin.md"))
                writePage(mainDir, "notes/arrived.md", identified(contested, title = "Origin"))
                builder.rebuild()

                world.aliasRegistry.find(RootedPath(EXTRA, TreePath.require("notes/origin"))) shouldBe contested
                world.aliasRegistry.find(RootedPath(RootName.MAIN, TreePath.require("notes/origin"))).shouldBeNull()
            }
        }
    }

    test("the rooted checkpoint closes a down-time cross-root move: the first rebuild aliases from the persisted (root, url)") {
        withTrees { mainDir, extraDir ->
            writePage(extraDir, "notes/origin.md", identified(contested, title = "Origin"))
            writePage(mainDir, "placeholder.md", "# P\n")
            World(mainFirst(mainDir, extraDir)).use { world ->
                world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild() // persists the rooted checkpoint

                // "Downtime": the move happens with no builder running; a FRESH builder (EMPTY
                // holder) compares against the persisted checkpoint.
                Files.delete(extraDir.resolve("notes/origin.md"))
                writePage(mainDir, "notes/arrived.md", identified(contested, title = "Origin"))
                world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                world.aliasRegistry.find(RootedPath(EXTRA, TreePath.require("notes/origin"))) shouldBe contested
            }
        }
    }

    test("link reports are rooted: identical relative paths in two roots never merge their failures (the codex confirm-round pin)") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/g.md", "# G\n\n[broken](#nope)\n")
            writePage(extraDir, "guides/g.md", "# G\n\nno links here\n")
            World(mainFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                val broken = LinkChecker().check(snapshot).broken
                broken.single().page shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/g.md"))
                broken.filter { it.page == RootedPath(EXTRA, TreePath.require("guides/g.md")) }.shouldBeEmpty()
            }
        }
    }
})

private val EXTRA = RootName.require("extra")

private fun mainFirst(mainDir: Path, extraDir: Path): RootRegistry =
    RootRegistry.of(listOf(localRoot("main", mainDir), localRoot("extra", extraDir)))

private fun extraFirst(mainDir: Path, extraDir: Path): RootRegistry =
    RootRegistry.of(listOf(localRoot("extra", extraDir), localRoot("main", mainDir)))

private fun mainOnlySource(registry: RootRegistry, mainDir: Path): List<IndexBuilder.Source> =
    listOf(IndexBuilder.Source(registry.main, LocalContentStore(mainDir), NoOpHistoryProvider))

private fun bothSources(registry: RootRegistry, mainDir: Path, extraDir: Path): List<IndexBuilder.Source> = listOf(
    IndexBuilder.Source(registry.main, LocalContentStore(mainDir), NoOpHistoryProvider),
    IndexBuilder.Source(requireNotNull(registry.byName(EXTRA)), LocalContentStore(extraDir), NoOpHistoryProvider),
)

private fun <T> withTrees(block: (Path, Path) -> T): T {
    val mainDir = Files.createTempDirectory("plainbase-multiroot-main")
    val extraDir = Files.createTempDirectory("plainbase-multiroot-extra")
    return try {
        block(mainDir, extraDir)
    } finally {
        mainDir.toFile().deleteRecursively()
        extraDir.toFile().deleteRecursively()
    }
}

/**
 * One persistent app-DB world that can seat SEVERAL builder configurations (partial vs full
 * visibility, re-added roots) against the same identity state - what the D16/re-add pins need and
 * the single-builder [IndexHarness] cannot express.
 */
private class World(
    val registry: RootRegistry,
    /** Shared across the world's builders, so a root marked by one pass stays marked for the next (the C4 outage shape). */
    private val availability: RootAvailability = RootAvailability(Clock.System),
) : AutoCloseable {

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)

    val idMap = SqlDelightIdMapRepository(database)
    val aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
    val checkpoints = SqlDelightPageCheckpointRepository(database)

    fun builder(sources: List<IndexBuilder.Source>, registry: RootRegistry = this.registry): IndexBuilder = IndexBuilder(
        sources = sources,
        frontmatterParser = FrontmatterReader(),
        rendererFactory = { view -> FlexmarkRenderer(view) },
        identity = PageIdentityService(UuidV7IdProvider(), registry::rank),
        patcher = FrontmatterPatcher(),
        idMap = idMap,
        aliasRegistry = aliasRegistry,
        checkpoint = checkpoints,
        citations = CitationFactory(),
        rootRank = registry::rank,
        registeredRoots = registry.roots.map { it.name }.toSet(),
        listeners = listOf(IndexBuilder.PublicationListener(checkpoints::replaceFrom)),
        availability = availability,
    )

    override fun close() = driver.close()
}

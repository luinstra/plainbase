package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * The multi-root matrix over the real IndexBuilder, RE-DERIVED for per-root identity (C5, ADR-0012,
 * reversing ADR-0011 D2/D17): a cross-root duplicate id is NO LONGER a contest. The same frontmatter
 * id in two different roots is LEGAL - both pages keep it and each answers its own `/p/{root}/{id}` -
 * so rank decides SOURCE precedence only and never takes an id from anyone. What survives from the C2
 * matrix is the WITHIN-root duplicate policy (unchanged), the
 * per-root URL space, the per-root lowercase rescue, the carry-forward of a down root (which now
 * never drops a page whose bare id a scanned root shares - R17), the per-root move aliasing (a
 * cross-root "move" is undecidable and records no alias - R19), and rooted link reports. Registries
 * are synthetic 2-root worlds over temp trees; NO rank-0-main assumption (the extra is seated ahead
 * of main wherever a would-be contest is exercised, to prove order never decides identity).
 */
class IndexBuilderMultiRootTest : FunSpec({

    val contested = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    /** A SECOND id, the one a within-root loser's own `id_map` row names. */
    val mapped = PageId.require("0197b555-1111-7222-8333-444455556666")

    fun identified(id: PageId, title: String = "T") = "---\nid: ${id.value}\ntitle: $title\n---\n\n# $title\n\nbody\n"

    test("duplicate source roots are rejected before scan order can choose an accidental winner") {
        withTrees { mainDir, extraDir ->
            val registry = mainFirst(mainDir, extraDir)
            World(registry).use { world ->
                val source = IndexBuilder.Source(registry.primary, LocalContentStore(mainDir), NoOpHistoryProvider)

                val failure = shouldThrow<IllegalArgumentException> {
                    world.builder(listOf(source, source))
                }

                failure.message shouldContain "duplicate source root"
            }
        }
    }

    test("a source unknown to the registry rank is rejected instead of silently becoming highest priority") {
        withTrees { mainDir, extraDir ->
            val registry = mainFirst(mainDir, extraDir)
            World(registry).use { world ->
                val ghost = localRoot("ghost", extraDir)

                val failure = shouldThrow<IllegalArgumentException> {
                    world.builder(listOf(IndexBuilder.Source(ghost, LocalContentStore(extraDir), NoOpHistoryProvider)))
                }

                failure.message shouldContain "unknown to the registry rank"
            }
        }
    }

    test("the same id at the SAME relative path in two roots is legal per-root: BOTH keep it, no contest, no crash") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/page.md", identified(contested))
            writePage(extraDir, "guides/page.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                // No UNIQUE(id) crash; the id lives in BOTH roots, each at its own path, neither reassigned.
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("guides/page.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/page.md")

                // Rescan stability: the next rebuild keeps each root's id, no churn.
                val again = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                again.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/page.md")
                again.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/page.md")
            }
        }
    }

    test("different-path cross-root duplicate: BOTH roots keep the id, and registry order never takes it from either") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "a/main-copy.md", identified(contested))
            writePage(extraDir, "b/extra-copy.md", identified(contested))

            // Whichever root is seated first, per-root identity leaves BOTH pages holding the id at their own paths.
            World(mainFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("a/main-copy.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("b/extra-copy.md")
            }
            World(extraFirst(mainDir, extraDir)).use { world ->
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("a/main-copy.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("b/extra-copy.md")
            }
        }
    }

    test("R29 (STOP-4 arm 2): a full pass over two roots sharing an id binds BOTH and never refuses a foreign-root path") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/doc.md", identified(contested))
            writePage(extraDir, "notes/copy.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                // A REFUSAL of a foreign-root bind would trip the resolver's `check(outcome is Bound)` and throw here.
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/doc.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("notes/copy.md")
                world.idMap.bindingInRoot(RootName.PRIMARY, contested)?.path shouldBe
                    RootedPath(RootName.PRIMARY, TreePath.require("guides/doc.md"))
                world.idMap.bindingInRoot(EXTRA, contested)?.path shouldBe RootedPath(EXTRA, TreePath.require("notes/copy.md"))
            }
        }
    }

    test("the prior-owner case (two checkouts of one repo): each root keeps the id under its OWN binding, no reassign") {
        withTrees { mainDir, extraDir ->
            // extra is the EXISTING id_map owner of the id (two checkouts of one repo); main outranks and presents
            // the same id. Under per-root identity that is not a contest - each root binds its own copy.
            writePage(mainDir, "guides/doc.md", identified(contested))
            writePage(extraDir, "guides/doc.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(RootedPath(EXTRA, TreePath.require("guides/doc.md")), contested, materialized = true)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/doc.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/doc.md")
                world.idMap.bindingInRoot(RootName.PRIMARY, contested)?.path shouldBe
                    RootedPath(RootName.PRIMARY, TreePath.require("guides/doc.md"))
                world.idMap.bindingInRoot(EXTRA, contested)?.path shouldBe RootedPath(EXTRA, TreePath.require("guides/doc.md"))
            }
        }
    }

    test("an id_map-ONLY page in one root keeps its permalink when another root's file carries the same id") {
        withTrees { mainDir, extraDir ->
            // extra's orphan carries NO frontmatter id; its only claim on the id is its id_map row. main's file
            // carries the id in frontmatter. Per-root identity: main's claim is in another root and cannot reach
            // across, so the orphan keeps its /p/extra/{id} - a permalink is not reassigned SILENTLY, because it is
            // not reassigned at all.
            writePage(mainDir, "guides/doc.md", identified(contested))
            writePage(extraDir, "guides/orphan.md", "---\ntitle: Orphan\n---\n\n# Orphan\n\nbody\n")
            val orphan = RootedPath(EXTRA, TreePath.require("guides/orphan.md"))
            World(mainFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(orphan, contested, materialized = false)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/doc.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/orphan.md")
                world.idMap.bindingInRoot(EXTRA, contested)?.path shouldBe orphan
            }
        }
    }

    test("registry order does not decide identity: with the registry flipped, both roots STILL keep the shared id") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/doc.md", identified(contested))
            writePage(extraDir, "guides/doc.md", identified(contested))
            World(extraFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(RootedPath(EXTRA, TreePath.require("guides/doc.md")), contested, materialized = true)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/doc.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/doc.md")
            }
        }
    }

    // ---- WITHIN-root duplicate policy is UNCHANGED by the flip: the previously-bound path keeps the id, ----------
    // the copy reassigns with a DuplicateId. What the flip changes is that the copy MAY now reuse an id_map
    // binding that names an id ANOTHER ROOT holds - that is no longer a collision (per-root byRootedId), so no
    // crash and no cross-root steal; the reuse is legal in the copy's own root.

    test("within-root loser reuses its own id_map binding even when the named id lives in ANOTHER root - no crash") {
        withTrees { mainDir, extraDir ->
            // a.md and b.md are the copied-file pair (both carry `contested` in MAIN), so b.md is the within-root
            // loser and reaches for its own binding - which names `mapped`, an id extra (seated ahead) also holds.
            // Per-root that is legal: b.md keeps `mapped` in MAIN, extra keeps `mapped` in EXTRA.
            writePage(mainDir, "guides/a.md", identified(contested))
            writePage(mainDir, "guides/b.md", identified(contested))
            writePage(extraDir, "mirror/holder.md", identified(mapped))
            val loser = RootedPath(RootName.PRIMARY, TreePath.require("guides/b.md"))
            World(extraFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(loser, mapped, materialized = false)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild() // must NOT throw

                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe TreePath.require("guides/a.md")
                // `mapped` now lives in BOTH roots, each at its own path - the per-root reuse the old contest forbade.
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, mapped)).shouldNotBeNull().path shouldBe TreePath.require("guides/b.md")
                snapshot.pageAt(RootedPageId(EXTRA, mapped)).shouldNotBeNull().path shouldBe TreePath.require("mirror/holder.md")
                snapshot.pages.map { it.rooted }.toSet() shouldHaveSize snapshot.pages.size
                // Reassigned for the reason it actually lost: the copied frontmatter id, in its own root.
                world.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>().single() shouldBe
                    IdentityIssue.DuplicateId(
                        id = contested,
                        root = RootName.PRIMARY,
                        keptPath = TreePath.require("guides/a.md"),
                        reassignedPath = loser.path,
                    )
            }
        }
    }

    test("a page keeps its frontmatter id even when its stale id_map row names an id another root just claimed") {
        withTrees { mainDir, extraDir ->
            // main's claimant carries `contested` in frontmatter and a STALE id_map row naming `mapped`; extra holds
            // both ids at their own pages. Frontmatter wins outright, and per-root identity means extra's claims never
            // reach main - so main keeps `contested`, extra keeps `contested` AND `mapped`, all legal, no crash.
            writePage(extraDir, "mirror/holder.md", identified(mapped))
            writePage(extraDir, "mirror/winner.md", identified(contested))
            writePage(mainDir, "guides/claimant.md", identified(contested))
            val loser = RootedPath(RootName.PRIMARY, TreePath.require("guides/claimant.md"))
            World(extraFirst(mainDir, extraDir)).use { world ->
                world.idMap.bind(loser, mapped, materialized = false)

                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild() // must NOT throw

                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("guides/claimant.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("mirror/winner.md")
                snapshot.pageAt(RootedPageId(EXTRA, mapped)).shouldNotBeNull().path shouldBe TreePath.require("mirror/holder.md")
                snapshot.pages.map { it.rooted }.toSet() shouldHaveSize snapshot.pages.size
            }
        }
    }

    test("a main-only pass leaves an unscanned root's identical-id binding untouched, and both roots keep the id") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            World(extraFirst(mainDir, extraDir)).use { world ->
                val foreign = RootedPath(EXTRA, TreePath.require("mirror/page.md"))
                world.idMap.bind(foreign, contested, materialized = true)

                val snapshot = world.builder(mainOnlySource(world.registry, mainDir)).rebuild()

                // main keeps its frontmatter id (root-scoped - extra's binding is invisible to it), and extra's
                // durable binding is not touched by a pass that never scanned it.
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("guides/claimant.md")
                world.idMap.bindingInRoot(EXTRA, contested)?.path shouldBe foreign // the unscanned binding SURVIVES
            }
        }
    }

    test("both roots on disk with the same id: a full pass keeps each root's page under its own binding") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            writePage(extraDir, "mirror/page.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                val foreign = RootedPath(EXTRA, TreePath.require("mirror/page.md"))
                world.idMap.bind(foreign, contested, materialized = true)

                // A main-only pass cannot see extra, and leaves its binding intact.
                world.builder(mainOnlySource(world.registry, mainDir)).rebuild()
                world.idMap.bindingInRoot(EXTRA, contested)?.path shouldBe foreign

                // The full pass sees both roots - and since a cross-root duplicate is no longer a contest, BOTH keep
                // the id under their own binding, neither reassigned, no issue.
                val full = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                full.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("guides/claimant.md")
                full.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("mirror/page.md")
                world.idMap.bindingInRoot(RootName.PRIMARY, contested)?.path shouldBe
                    RootedPath(RootName.PRIMARY, TreePath.require("guides/claimant.md"))
                world.idMap.bindingInRoot(EXTRA, contested)?.path shouldBe foreign
            }
        }
    }

    test("D-C4-10: an UNAVAILABLE root's page is carried WITH its id even when a scanned root now holds the same id") {
        // extra is DOWN (its section is carried forward verbatim, so it still CONTAINS its page), and main - which
        // outranks it - now presents the same frontmatter id. Per-root identity: both the carried extra page and the
        // live main page keep the id under their own root, PageIndex's per-root byRootedId does not trip, and no
        // rebuild wedges.
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
                world.idMap.bindingInRoot(EXTRA, contested)?.path shouldBe foreign

                // extra goes away; main gains a page carrying extra's id (a copy, or a half-finished move).
                extraDir.toFile().deleteRecursively()
                writePage(mainDir, "guides/claimant.md", identified(contested))
                val snapshot = builder.rebuild() // must NOT throw

                availability.current().isAvailable(EXTRA) shouldBe false
                world.idMap.bindingInRoot(EXTRA, contested)?.path shouldBe foreign // durable identity intact through the outage
                // carried
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("mirror/page.md")
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("guides/claimant.md") // live

                // ...and the scheduler/rescan paths keep working: a repeat rebuild neither throws nor churns identity.
                val again = builder.rebuild()
                again.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("guides/claimant.md")
                again.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("mirror/page.md")
            }
        }
    }

    test("R17: a carried down-root page whose bare id a scanned root now holds SURVIVES the snapshot (nothing filters a carry)") {
        // Nothing filters a carried section any more (C7 deleted the rooted-id filter as a provable no-op), so a
        // down root's page is never dropped because a scanned root shares its bare id. Both pages, in their own
        // roots, sit in the same snapshot. This row is the regression guard on that, not a proof of the deletion.
        withTrees { mainDir, extraDir ->
            writePage(extraDir, "mirror/page.md", identified(contested))
            writePage(extraDir, "mirror/keep.md", "# Keep\n")
            writePage(mainDir, "placeholder.md", "# P\n")
            val availability = RootAvailability(Clock.System)
            World(mainFirst(mainDir, extraDir), availability).use { world ->
                val builder = world.builder(bothSources(world.registry, mainDir, extraDir)) // ONE builder: see above
                builder.rebuild()

                extraDir.toFile().deleteRecursively()
                writePage(mainDir, "guides/claimant.md", identified(contested))

                val snapshot = builder.rebuild() // must NOT throw

                // The scanned side keeps the id; the carried down-root page keeps ITS copy of the id - both survive.
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("guides/claimant.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("mirror/page.md")
                snapshot.section(EXTRA).pages.map { it.path.value } shouldContainExactlyInAnyOrder
                    listOf("mirror/page.md", "mirror/keep.md")
                // Nothing durable was deleted for the down root: its OTHER page's binding is untouched.
                world.idMap.find(RootedPath(EXTRA, TreePath.require("mirror/keep.md"))).shouldNotBeNull()
            }
        }
    }

    test("a live main claim coexists with an UNREGISTERED root's identical-id binding - no supersede, no issue (D2)") {
        withTrees { mainDir, _ ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            World(RootRegistry.of(listOf(localRoot("docs", mainDir)))).use { world ->
                // A binding under a root nobody registered; main then claims the same id live. Per-root identity means
                // the two coexist - main never needs to supersede the foreign row, and raises no issue.
                val ghost = RootedPath(RootName.require("ghost"), TreePath.require("mirror/page.md"))
                world.idMap.bind(ghost, contested, materialized = true)

                world.builder(mainOnlySource(world.registry, mainDir)).rebuild()

                world.idMap.bindingInRoot(RootName.PRIMARY, contested)?.path shouldBe
                    RootedPath(RootName.PRIMARY, TreePath.require("guides/claimant.md"))
                world.idMap.bindingInRoot(RootName.require("ghost"), contested)?.path shouldBe ghost // the foreign row SURVIVES
                world.idMap.issues().shouldBeEmpty() // "no issue" in the name, pinned over EVERY kind rather than one
            }
        }
    }

    test("detached/re-add is per-root: a re-added root's page keeps its own id, whatever a live claimant elsewhere holds") {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            writePage(extraDir, "mirror/page.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                // Bound under extra once; then extra leaves the config (detached) while a live main page claims the id.
                world.idMap.bind(RootedPath(EXTRA, TreePath.require("mirror/page.md")), contested, materialized = true)
                val mainOnly = RootRegistry.of(listOf(localRoot("docs", mainDir)))
                world.builder(mainOnlySource(mainOnly, mainDir), registry = mainOnly).rebuild()
                world.idMap.bindingInRoot(RootName.PRIMARY, contested)?.path shouldBe
                    RootedPath(RootName.PRIMARY, TreePath.require("guides/claimant.md"))

                // Re-adding extra restores its page under its OWN root: per-root identity, both keep the id, no contest.
                val snapshot = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                snapshot.pageAt(RootedPageId(RootName.PRIMARY, contested)).shouldNotBeNull().path shouldBe
                    TreePath.require("guides/claimant.md")
                snapshot.pageAt(RootedPageId(EXTRA, contested)).shouldNotBeNull().path shouldBe TreePath.require("mirror/page.md")
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

                val mainSetup = snapshot.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guides/setup.md")))
                val extraSetup = snapshot.byPath.getValue(RootedPath(EXTRA, TreePath.require("guides/setup.md")))
                mainSetup.urlPath.shouldNotBeNull()
                extraSetup.urlPath.shouldNotBeNull()
                // Same relative urlPath, distinct wire urls: the root segment disambiguates (C3, ADR-0011 D3).
                mainSetup.urlPath shouldBe extraSetup.urlPath
                mainSetup.url shouldBe "/docs/guides/setup"
                extraSetup.url shouldBe "/extra/guides/setup"

                val mainClash = snapshot.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guides/zz-clash.md")))
                mainClash.urlPath.shouldBeNull() // the within-root loser (raw-byte-order winner keeps it)
                world.idMap.issues().filterIsInstance<IdentityIssue.PathSlugCollision>().single().root shouldBe RootName.PRIMARY
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
                snapshot.view(RootName.PRIMARY).caseInsensitiveMatches(probe) shouldBe listOf(TreePath.require("Docs/Page.md"))
                snapshot.view(EXTRA).caseInsensitiveMatches(probe).shouldBeEmpty()
            }
        }
    }

    test("R19: a cross-root 'move' records NO alias - a bare-id cross-root move is undecidable (per-root identity, C5)") {
        withTrees { mainDir, extraDir ->
            writePage(extraDir, "notes/origin.md", identified(contested, title = "Origin"))
            World(mainFirst(mainDir, extraDir)).use { world ->
                writePage(mainDir, "placeholder.md", "# P\n") // main is never empty in practice
                val builder = world.builder(bothSources(world.registry, mainDir, extraDir))
                builder.rebuild()

                // extra's copy is deleted; main gains a file carrying the same id. That is a cross-root apparition,
                // NOT a decidable move - the id is legal in both roots - so no alias is registered in either.
                Files.delete(extraDir.resolve("notes/origin.md"))
                writePage(mainDir, "notes/arrived.md", identified(contested, title = "Origin"))
                builder.rebuild()

                world.aliasRegistry.find(RootedPath(EXTRA, TreePath.require("notes/origin"))).shouldBeNull()
                world.aliasRegistry.find(RootedPath(RootName.PRIMARY, TreePath.require("notes/origin"))).shouldBeNull()
            }
        }
    }

    test("R19: a SAME-root move still records its alias in that root's namespace") {
        withTrees { mainDir, extraDir ->
            writePage(extraDir, "notes/origin.md", identified(contested, title = "Origin"))
            writePage(mainDir, "placeholder.md", "# P\n")
            World(mainFirst(mainDir, extraDir)).use { world ->
                val builder = world.builder(bothSources(world.registry, mainDir, extraDir))
                builder.rebuild()

                // A move WITHIN extra: the same rooted id changes its (root, url) - a decidable move, aliased.
                Files.delete(extraDir.resolve("notes/origin.md"))
                writePage(extraDir, "notes/arrived.md", identified(contested, title = "Origin"))
                builder.rebuild()

                world.aliasRegistry.find(RootedPath(EXTRA, TreePath.require("notes/origin"))) shouldBe
                    RootedPageId(EXTRA, contested)
            }
        }
    }

    test("the rooted checkpoint closes a down-time SAME-root move: the first rebuild aliases from the persisted (root, url)") {
        withTrees { mainDir, extraDir ->
            writePage(extraDir, "notes/origin.md", identified(contested, title = "Origin"))
            writePage(mainDir, "placeholder.md", "# P\n")
            World(mainFirst(mainDir, extraDir)).use { world ->
                world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild() // persists the rooted checkpoint

                // "Downtime": the same-root move happens with no builder running; a FRESH builder (EMPTY holder)
                // compares against the persisted checkpoint and still aliases the old rooted path.
                Files.delete(extraDir.resolve("notes/origin.md"))
                writePage(extraDir, "notes/arrived.md", identified(contested, title = "Origin"))
                world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()

                world.aliasRegistry.find(RootedPath(EXTRA, TreePath.require("notes/origin"))) shouldBe
                    RootedPageId(EXTRA, contested)
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
                broken.single().page shouldBe RootedPath(RootName.PRIMARY, TreePath.require("guides/g.md"))
                broken.filter { it.page == RootedPath(EXTRA, TreePath.require("guides/g.md")) }.shouldBeEmpty()
            }
        }
    }
})

private val EXTRA = RootName.require("extra")

private fun mainFirst(mainDir: Path, extraDir: Path): RootRegistry =
    RootRegistry.of(listOf(localRoot("docs", mainDir), localRoot("extra", extraDir)))

private fun extraFirst(mainDir: Path, extraDir: Path): RootRegistry =
    RootRegistry.of(listOf(localRoot("extra", extraDir), localRoot("docs", mainDir)))

private fun mainOnlySource(registry: RootRegistry, mainDir: Path): List<IndexBuilder.Source> =
    listOf(IndexBuilder.Source(registry.primary, LocalContentStore(mainDir), NoOpHistoryProvider))

private fun bothSources(registry: RootRegistry, mainDir: Path, extraDir: Path): List<IndexBuilder.Source> = listOf(
    IndexBuilder.Source(registry.primary, LocalContentStore(mainDir), NoOpHistoryProvider),
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
        identity = PageIdentityService(UuidV7IdProvider()),
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

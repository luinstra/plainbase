package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.replaceFrom
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

/**
 * The C2 multi-root matrix over the real IndexBuilder (ADR-0011 D2/D16/D17): the cross-root
 * duplicate-id rank contest at the pass level (including the round-1 panel crash case and the
 * prior-owner mint-guard case, each named), the D16 partial-visibility protection on BOTH sides of
 * the contest, per-root URL space, per-root lowercase rescue, detached/re-add restore semantics,
 * cross-root move aliasing, the rooted checkpoint, and rooted link reports. Registries are
 * synthetic 2-root worlds over temp trees; NO rank-0-main assumption (the extra is seated ahead of
 * main wherever the protection is exercised).
 */
class IndexBuilderMultiRootTest : FunSpec({

    val contested = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

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

    test(
        "D16, pass page wins: the key-complete bind deletes the unscanned loser AND records the loser-behalf issue; the full rebuild converges without growing it",
    ) {
        withTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/claimant.md", identified(contested))
            writePage(extraDir, "mirror/page.md", identified(contested))
            World(mainFirst(mainDir, extraDir)).use { world ->
                val foreign = RootedPath(EXTRA, TreePath.require("mirror/page.md"))
                world.idMap.bind(foreign, contested, materialized = true)

                world.builder(mainOnlySource(world.registry, mainDir)).rebuild()

                // BOTH the delete and the loser-behalf record, at supersession time.
                world.idMap.pathOf(contested) shouldBe RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md"))
                val expected = IdentityIssue.CrossRootDuplicateId(
                    id = contested,
                    kept = RootedPath(RootName.MAIN, TreePath.require("guides/claimant.md")),
                    reassigned = foreign,
                )
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe expected

                // Convergence + natural-key dedup: the full two-source rebuild decides the same
                // outcome and the loser's own record dedups against the loser-behalf row.
                val full = world.builder(bothSources(world.registry, mainDir, extraDir)).rebuild()
                full.byId.getValue(contested).root shouldBe RootName.MAIN
                full.section(EXTRA).pages.single().id shouldNotBe contested
                world.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>() shouldHaveSize 1
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

    test("per-root URL space: identical slugs in two roots BOTH keep their urlPath and emit identical url strings (C2 documented state)") {
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
                mainSetup.url shouldBe extraSetup.url // no root segment until C3, deliberately

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
private class World(val registry: RootRegistry) : AutoCloseable {

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
    )

    override fun close() = driver.close()
}

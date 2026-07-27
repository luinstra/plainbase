package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * ONE ID, ONE PAGE - the precondition BOTH resolve-then-bind passes check immediately before they make a plan
 * DURABLE, and the reason it is a check at all: every rule upstream is MEANT to guarantee it, and a rule can be
 * wrong (`PageIdentityService.duplicate()` reused a `mappedId` blind for a whole release).
 *
 * A durable duplicate cannot be walked back. `id_map.bind` is key-complete, so the second bind of an id DELETES the
 * first page's row: the winner takes the permalink, the loser's binding is gone, and the only existing uniqueness
 * check (`PageIndex.byRootedId`) throws only AFTER all of it has landed - on every boot thereafter, over rows nothing
 * will now rewrite. Failing BEFORE the first bind aborts a pass that has changed nothing at all.
 *
 * The injected fault is a BROKEN [IdProvider] - one that violates its own documented "successive calls return
 * distinct ids" contract - because that is the honest shape of the bug this guards: not a caller doing something
 * exotic, but a collaborator quietly failing to keep a promise the passes rely on.
 */
class RequireDistinctIdsTest : FunSpec({

    test("IndexBuilder: a plan with ONE id for TWO pages throws BEFORE anything is bound - the id_map is untouched") {
        withVirginPages { root ->
            DistinctIdsWorld(root).use { world ->
                val builder = world.builder(collidingIds = true)

                val failure = shouldThrow<IllegalStateException> { builder.rebuild() }

                failure.message.orEmpty() shouldContain "ONE id for SEVERAL pages"
                withClue("a failed pass must change NOTHING: a bound duplicate is the state no later pass can repair") {
                    world.idMap.bindings().shouldBeEmpty()
                }
                withClue("...and the last-good snapshot stands, which is strictly the better failure") {
                    builder.current.pages.shouldBeEmpty()
                }
            }
        }
    }

    test("AdoptionPass: the SAME plan throws on the arm that makes it durable - no bind, no file rewritten") {
        withVirginPages { root ->
            DistinctIdsWorld(root).use { world ->
                val adopt = world.adoption(collidingIds = true)
                val before = Files.readString(root.resolve("alpha.md"))
                // PREVIEW renders the same plan and writes nothing, so the check belongs on `apply`, not on `plan`.
                val plan = adopt.plan(AdoptionPass.Mode.MATERIALIZE)

                val failure = shouldThrow<IllegalStateException> { adopt.apply(plan) }

                failure.message.orEmpty() shouldContain "ONE id for SEVERAL pages"
                world.idMap.bindings().shouldBeEmpty()
                withClue("adopt materializes ids INTO the files: a half-applied duplicate would be on disk, not just in a row") {
                    Files.readString(root.resolve("alpha.md")) shouldBe before
                }
            }
        }
    }

    test("the CONTROL: the same corpus with a HONEST id provider binds both pages, distinctly") {
        withVirginPages { root ->
            DistinctIdsWorld(root).use { world ->
                val snapshot = world.builder(collidingIds = false).rebuild()

                snapshot.pages.map { it.id }.toSet().size shouldBe 2
                world.idMap.bindings().size shouldBe 2
            }
        }
    }

    // R14: the check is PER-ROOT (C5) - it groups by (root, id), so the same id in two DIFFERENT roots is legal and a
    // within-root duplicate still fails. Driven at the internal function directly, the level the flip actually changed.
    val dup = PageId.require("01890a5d-ac96-774b-bcce-b302099a8057")

    test("R14: the SAME id in two DIFFERENT roots passes - a cross-root duplicate is legal per-root") {
        requireDistinctIds(
            mapOf(
                RootedPath(RootName.PRIMARY, TreePath.require("a.md")) to dup,
                RootedPath(RootName.require("extra"), TreePath.require("b.md")) to dup,
            ),
        ) // no throw
    }

    test("R14: the SAME id twice in ONE root still throws, naming ONE id for SEVERAL pages") {
        val failure = shouldThrow<IllegalStateException> {
            requireDistinctIds(
                mapOf(
                    RootedPath(RootName.PRIMARY, TreePath.require("a.md")) to dup,
                    RootedPath(RootName.PRIMARY, TreePath.require("b.md")) to dup,
                ),
            )
        }
        failure.message.orEmpty() shouldContain "ONE id for SEVERAL pages IN ONE ROOT"
    }
})

/** The one id the broken provider hands out to everything it is asked for. */
private val COLLIDING_ID = PageId.require("01890a5d-ac96-774b-bcce-b302099a8057")

/** Two pages with NO frontmatter id: both are virgin, so both take their id from the [IdProvider] - which is the seam. */
private fun <T> withVirginPages(block: (Path) -> T): T {
    val root = Files.createTempDirectory("plainbase-distinct-ids")
    return try {
        writePage(root, "alpha.md", "# Alpha\n\nbody\n")
        writePage(root, "beta.md", "# Beta\n\nbody\n")
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

/** One app-DB world over [root], seating either pass with an HONEST or a COLLIDING id provider. */
private class DistinctIdsWorld(private val root: Path) : AutoCloseable {

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    private val registry: RootRegistry = RootRegistry.of(listOf(localRoot("main", root)))

    val idMap = SqlDelightIdMapRepository(database)

    /** The broken collaborator: a provider that hands the SAME id to every virgin page it is asked about. */
    private fun idProvider(collidingIds: Boolean): IdProvider =
        if (collidingIds) IdProvider { COLLIDING_ID } else UuidV7IdProvider()

    private fun identity(collidingIds: Boolean) = PageIdentityService(idProvider(collidingIds))

    fun builder(collidingIds: Boolean): IndexBuilder = IndexBuilder(
        sources = listOf(IndexBuilder.Source(registry.primary, LocalContentStore(root), NoOpHistoryProvider)),
        frontmatterParser = FrontmatterReader(),
        rendererFactory = { view -> FlexmarkRenderer(view) },
        identity = identity(collidingIds),
        patcher = FrontmatterPatcher(),
        idMap = idMap,
        aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database)),
        checkpoint = SqlDelightPageCheckpointRepository(database),
        citations = CitationFactory(),
        rootRank = registry::rank,
        registeredRoots = registry.roots.map { it.name }.toSet(),
    )

    fun adoption(collidingIds: Boolean): AdoptionPass = AdoptionPass(
        sources = listOf(AdoptionPass.Source(RootName.PRIMARY, LocalContentStore(root))),
        idMap = idMap,
        identity = identity(collidingIds),
        patcher = FrontmatterPatcher(),
        rootLoss = RootLossClassifier(RootAvailability(Clock.System)),
        citations = CitationFactory(),
        rootRank = registry::rank,
        registeredRoots = registry.roots.map { it.name }.toSet(),
    )

    override fun close() = driver.close()
}

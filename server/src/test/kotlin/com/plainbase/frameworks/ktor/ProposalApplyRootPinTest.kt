package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.domain.service.WriteOrigin
import com.plainbase.domain.service.localRoot
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * An approved edit must land in the root it was APPROVED for (ADR-0012).
 *
 * A proposal decides its root at propose time, shows that root to the admin, and gates on it. Under per-root
 * identity the same frontmatter `id:` may be live in SEVERAL roots at once, so an id-addressed apply is not
 * following a page, it is choosing among candidates - and it can choose a DIFFERENT repository than the one the
 * admin reviewed. `base_hash` would not stop it: two checkouts of one repo (the routine case that makes duplicate
 * ids common in the first place) hold byte-identical files, so the CAS matches.
 *
 * `SaveRequest.expectedRoot` pins it. The id still picks the PATH — an in-root move still applies, which
 * `ProposalApplyAuthzRouteTest` pins from the other side — but it can no longer pick the ROOT.
 */
class ProposalApplyRootPinTest : FunSpec({

    val path = TreePath.require("guides/deploy.md")
    val pageId = PageId.require("01900000-0000-7000-8000-0000000000e1")
    val citations = CitationFactory()

    /** The two-checkouts-of-one-repo corpus: BYTE-IDENTICAL files carrying the SAME frontmatter id. */
    fun body(text: String) = "---\nid: ${pageId.value}\ntitle: Deploy\n---\n\n# Deploy\n\n$text\n"

    test("an approved edit lands in the root it was filed against, even when another root now shares the page id") {
        withTwoRoots { mainDir, mirrorDir ->
            // `mirror` is where the proposal was filed (it owns the id: main has no such page yet).
            Files.createDirectories(mirrorDir.resolve("guides"))
            Files.writeString(mirrorDir.resolve("guides/deploy.md"), body("original."))

            val registry = RootRegistry.of(listOf(localRoot("main", mainDir), localRoot("mirror", mirrorDir)))
            val mirror = RootName.require("mirror")
            val mainStore = LocalContentStore(mainDir, rootName = RootName.PRIMARY)
            val mirrorStore = LocalContentStore(mirrorDir, rootName = mirror)

            IndexHarness(
                root = mainDir,
                rootRegistry = registry,
                sources = listOf(
                    IndexBuilder.Source(registry.primary, mainStore, NoOpHistoryProvider),
                    IndexBuilder.Source(requireNotNull(registry.byName(mirror)), mirrorStore, NoOpHistoryProvider),
                ),
            ).use { harness ->
                harness.builder.rebuild()
                val proposed = harness.builder.current.byPath.getValue(RootedPath(mirror, path))
                proposed.id shouldBe pageId

                // The SAME repo gets checked out into `main` too (rank 0). Per-root identity: main takes NOTHING from
                // mirror - it holds its OWN page with the same id, and mirror keeps its page (and its proposal target).
                Files.createDirectories(mainDir.resolve("guides"))
                Files.writeString(mainDir.resolve("guides/deploy.md"), body("original."))
                harness.builder.rebuild()
                withClue("both roots now hold the id under per-root identity; mirror still owns the page its proposal named") {
                    harness.builder.current.pageAt(RootedPageId(RootName.PRIMARY, pageId))!!.root shouldBe RootName.PRIMARY
                    harness.builder.current.pageAt(RootedPageId(mirror, pageId))!!.root shouldBe mirror
                }

                val mutate = GuardedMutatingFacade(
                    policy = PolicyService(
                        harness.roleRepository,
                        harness.apiTokenRepository,
                        harness.auditRepository,
                        UuidV7IdProvider(),
                        Clock.System,
                        enforced = false,
                    ),
                    writePipeline = harness.writePipeline(),
                    stores = harness.stores,
                    indexBuilder = harness.builder,
                    availability = harness.availability,
                    resolver = harness.resolver,
                    absence = harness.absence,
                )

                // The apply, exactly as GuardedProposalFacade drives it: the row's page_id + base_hash, pinned to the
                // row's root. base_hash MATCHES main's file too — the two checkouts are byte-identical — so the CAS is
                // no defense here; only the pin is.
                val result = mutate.save(
                    Principal.Human("builtin", "admin"),
                    SaveRequest(
                        pageId = pageId,
                        baseHash = citations.contentHash(body("original.").toByteArray()),
                        bytes = body("approved edit.").toByteArray(),
                        origin = WriteOrigin.PROPOSAL_APPLY,
                        expectedRoot = mirror,
                    ),
                )

                    withClue("the pin resolves to mirror (its own page survives per-root), so the approved edit lands THERE") {
                    result.shouldBeInstanceOf<SaveResult.Written>()
                    Files.readString(mirrorDir.resolve("guides/deploy.md")) shouldContain "approved edit."
                }
                withClue("...and NOT into main, the repository nobody reviewed - the pin never follows the id across roots") {
                    Files.readString(mainDir.resolve("guides/deploy.md")) shouldContain "original."
                }
            }
        }
    }

    test("the pin does not disturb a same-root apply: the edit still lands where it was approved") {
        withTwoRoots { mainDir, mirrorDir ->
            Files.createDirectories(mirrorDir.resolve("guides"))
            Files.writeString(mirrorDir.resolve("guides/deploy.md"), body("original."))

            val registry = RootRegistry.of(listOf(localRoot("main", mainDir), localRoot("mirror", mirrorDir)))
            val mirror = RootName.require("mirror")

            IndexHarness(
                root = mainDir,
                rootRegistry = registry,
                sources = listOf(
                    IndexBuilder.Source(registry.primary, LocalContentStore(mainDir, rootName = RootName.PRIMARY), NoOpHistoryProvider),
                    IndexBuilder.Source(
                        requireNotNull(registry.byName(mirror)),
                        LocalContentStore(mirrorDir, rootName = mirror),
                        NoOpHistoryProvider,
                    ),
                ),
            ).use { harness ->
                harness.builder.rebuild()
                val target = harness.builder.current.byPath.getValue(RootedPath(mirror, path))

                val mutate = GuardedMutatingFacade(
                    policy = PolicyService(
                        harness.roleRepository,
                        harness.apiTokenRepository,
                        harness.auditRepository,
                        UuidV7IdProvider(),
                        Clock.System,
                        enforced = false,
                    ),
                    writePipeline = harness.writePipeline(),
                    stores = harness.stores,
                    indexBuilder = harness.builder,
                    availability = harness.availability,
                    resolver = harness.resolver,
                    absence = harness.absence,
                )

                val result = mutate.save(
                    Principal.Human("builtin", "admin"),
                    SaveRequest(
                        pageId = target.id,
                        baseHash = target.contentHash,
                        bytes = body("approved edit.").toByteArray(),
                        origin = WriteOrigin.PROPOSAL_APPLY,
                        expectedRoot = mirror,
                    ),
                )

                result.shouldBeInstanceOf<SaveResult.Written>()
                Files.readString(mirrorDir.resolve("guides/deploy.md")) shouldContain "approved edit."
            }
        }
    }
})

/** Two temp content roots, always cleaned up. */
private fun withTwoRoots(block: (main: Path, mirror: Path) -> Unit) {
    val main = Files.createTempDirectory("pb-apply-pin-main")
    val mirror = Files.createTempDirectory("pb-apply-pin-mirror")
    try {
        block(main, mirror)
    } finally {
        listOf(main, mirror).forEach { it.toFile().deleteRecursively() }
    }
}

package com.plainbase.frameworks.ktor

import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Clock

/**
 * The guarded mutating facade (A3 WI 3): `save` calls `checkEdit` FIRST, then delegates to the real
 * `WritePipeline.write` WITH the minted grant — proven by the on-disk side effect. A denied principal NEVER
 * reaches the mutator (the file is unchanged), and `rescan` is manage-gated. Runs ENFORCED (auth-on) over a real
 * pipeline so the grant flow is end-to-end, not mocked.
 */
class FacadeGuardTest : FunSpec({

    fun editor() = Principal.Human("builtin", "editor")
    fun viewer() = Principal.Human("builtin", "viewer")
    fun admin() = Principal.Human("builtin", "admin")

    test("save(editor) mints the grant and writes; save(viewer) throws and never reaches the mutator") {
        val root = Files.createTempDirectory("plainbase-facade")
        try {
            Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\noriginal.\n")
            IndexHarness(root, contentStore = LocalContentStore(root)).use { harness ->
                harness.builder.rebuild()
                harness.roleRepository.upsert("builtin", "editor", Role.EDITOR, Clock.System.now())
                harness.roleRepository.upsert("builtin", "viewer", Role.VIEWER, Clock.System.now())
                val ctx = harness.testRouteContext(
                    contentStore = LocalContentStore(root),
                    searchProvider = noopSearchProvider(),
                    enforced = true,
                )
                val page = harness.builder.current.pages.single()
                val newBytes = "---\ntitle: Doc\n---\n\n# Doc\n\nedited by editor.\n".toByteArray()

                // EDITOR: the write lands (the grant reached the pipeline). The facade resolves the page path
                // INTERNALLY from the snapshot — the route hands only id + base_hash + bytes.
                val result = ctx.mutate.save(editor(), SaveRequest(page.id, page.contentHash, newBytes))
                result.shouldBeInstanceOf<SaveResult.Written>()
                result.outcome.shouldBeInstanceOf<WriteOutcome.Written>()
                Files.readString(root.resolve("doc.md")) shouldBe String(newBytes)

                // VIEWER: denied BEFORE the mutator — the file is unchanged (the deny short-circuited the write).
                val refreshed = harness.builder.current.pages.single()
                val viewerBytes = "---\ntitle: Doc\n---\n\n# Doc\n\nedited by viewer.\n".toByteArray()
                shouldThrow<AccessDenied> {
                    ctx.mutate.save(viewer(), SaveRequest(refreshed.id, refreshed.contentHash, viewerBytes))
                }
                Files.readString(root.resolve("doc.md")) shouldBe String(newBytes) // unchanged: the mutator was never reached
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("a denied save audits exactly one denied-EDIT row (the audited edit-check precedes any read)") {
        val root = Files.createTempDirectory("plainbase-facade-audit")
        try {
            Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n\noriginal.\n")
            IndexHarness(root, contentStore = LocalContentStore(root)).use { harness ->
                harness.builder.rebuild()
                harness.roleRepository.upsert("builtin", "viewer", Role.VIEWER, Clock.System.now())
                val ctx = harness.testRouteContext(
                    contentStore = LocalContentStore(root),
                    searchProvider = noopSearchProvider(),
                    enforced = true,
                )
                val page = harness.builder.current.pages.single()
                // The VIEWER's PUT is denied. The denied decision MUST be the audited EDIT — not swallowed by an
                // unaudited read-check that runs first. Exactly ONE audit row: action EDIT, decision denied.
                shouldThrow<AccessDenied> {
                    ctx.mutate.save(viewer(), SaveRequest(page.id, page.contentHash, "x".toByteArray()))
                }
                val rows = harness.auditRepository.recent(10)
                rows shouldHaveSize 1
                rows.single().action shouldBe "EDIT"
                rows.single().decision shouldBe "denied"
                rows.single().resource shouldBe page.id.value
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("rescan is manage-gated: viewer denied, admin reaches the rebuild") {
        val root = Files.createTempDirectory("plainbase-facade-manage")
        try {
            Files.writeString(root.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n")
            IndexHarness(root, contentStore = LocalContentStore(root)).use { harness ->
                harness.builder.rebuild()
                harness.roleRepository.upsert("builtin", "viewer", Role.VIEWER, Clock.System.now())
                harness.roleRepository.upsert("builtin", "admin", Role.ADMIN, Clock.System.now())
                val ctx = harness.testRouteContext(
                    contentStore = LocalContentStore(root),
                    searchProvider = noopSearchProvider(),
                    enforced = true,
                )
                shouldThrow<AccessDenied> { ctx.mutate.rescan(viewer()) }
                ctx.mutate.rescan(admin()).pages.size shouldBe 1 // admin reaches the rebuild
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

/** A no-op SearchProvider so the facade harness needs no FTS engine for these gate assertions. */
private fun noopSearchProvider() = object : com.plainbase.domain.search.SearchProvider {
    override fun index(pages: List<com.plainbase.domain.search.PageDocuments>) = Unit
    override fun delete(ids: Collection<com.plainbase.domain.page.PageId>) = Unit
    override fun search(query: com.plainbase.domain.search.SearchQuery) = com.plainbase.domain.search.SearchResults(0, emptyList())
    override fun rebuild(pages: Sequence<com.plainbase.domain.search.PageDocuments>) = Unit
    override fun indexedState() = emptyMap<com.plainbase.domain.page.PageId, com.plainbase.domain.search.PageSearchState>()
}

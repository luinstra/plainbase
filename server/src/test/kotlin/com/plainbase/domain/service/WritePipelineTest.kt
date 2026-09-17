package com.plainbase.domain.service

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.ScanIssue
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * PB-WRITE-1 chunk W1 — the serialized write pipeline's per-save behavior (named tests 1, 3, 4, 5).
 * Built on [IndexHarness]/[withTempTree]/[writePage] over a real [com.plainbase.frameworks.filesystem
 * .LocalContentStore] + [CitationFactory]: the harness and the pipeline share ONE content store.
 */
class WritePipelineTest : FunSpec({

    val citations = CitationFactory()

    fun seedOne(root: Path) {
        writePage(root, "guides/edit-me.md", "---\ntitle: Edit Me\n---\n\n# Edit Me\n\noriginal body.\n")
    }

    fun seedTarget(root: Path, id: String, path: String = "target.md") {
        writePage(root, path, "---\nid: $id\ntitle: Target\n---\n\n# Target\n")
    }

    fun targetOf(harness: IndexHarness) = harness.builder.current.pages.single()

    fun runIneligibleCase(
        seed: (Path) -> Unit,
        target: RootedPath,
        rootRegistryFactory: (Path) -> RootRegistry = { root ->
            RootRegistry.of(listOf(localRoot("docs", root)))
        },
        contentStoreFactory: (Path) -> ContentStore = { root -> LocalContentStore(root) },
        sourcesFactory: (Path, RootRegistry, ContentStore) -> List<IndexBuilder.Source> =
            { _, registry, store -> listOf(IndexBuilder.Source(registry.primary, store, NoOpHistoryProvider)) },
        availability: RootAvailability = RootAvailability(Clock.System),
        registeredRootsOverride: (RootRegistry) -> Set<RootName>? = { null },
        assertFixture: (IndexHarness) -> Unit = {},
    ) {
        withTempTree(seed) { root ->
            val registry = rootRegistryFactory(root)
            val store = contentStoreFactory(root)
            val sources = sourcesFactory(root, registry, store)
            lateinit var recording: RecordingConfirmationIdMap
            IndexHarness(
                root,
                contentStore = store,
                rootRegistry = registry,
                sources = sources,
                availability = availability,
                decorateIdMap = { delegate ->
                    RecordingConfirmationIdMap(delegate, forcedConfirmationResult = true).also { recording = it }
                },
                registeredRootsOverride = registeredRootsOverride(registry),
            ).use { harness ->
                harness.builder.rebuild()
                assertFixture(harness)
                val bindsBefore = recording.bindCalls
                val snapshot = harness.builder.rebuildAfterCreate(target)
                recording.confirmationCalls shouldBe 0
                // Every non-confirmed, readable assignment uses the ordinary bind loop; a skipped source contributes
                // no new binds, so this also pins the unavailable/incomplete skip arms.
                recording.bindCalls shouldBe bindsBefore + snapshot.pages.size
            }
        }
    }

    // Test 1: disk-clobber — an external on-disk edit before the CAS is never clobbered (fix A, the #1 gate).
    test("an external on-disk edit before reindex is never clobbered (disk-authoritative)") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                val baseHash = page.contentHash // the hash the client last saw

                // Edit the file directly on disk WITHOUT a rebuild — the snapshot still shows the old bytes.
                val external = "---\ntitle: Edit Me\n---\n\n# Edit Me\n\nexternally edited.\n".toByteArray()
                Files.write(root.resolve("guides/edit-me.md"), external)

                val saveBytes = "---\ntitle: Edit Me\n---\n\n# Edit Me\n\nmy save.\n".toByteArray()
                val outcome = harness.writePipeline().write(
                    grantForTests(),
                    WriteIntent(page.id, RootName.PRIMARY, page.path, baseHash, saveBytes),
                )

                outcome.shouldBeInstanceOf<WriteOutcome.Conflict>().reason shouldBe "content_changed"
                // Disk-authoritative: the external bytes survive, NOT the save bytes.
                Files.readAllBytes(root.resolve("guides/edit-me.md")) shouldBe external
            }
        }
    }

    // Test 3: byte-fidelity round-trip — a matching base_hash writes bytes verbatim (master criterion 1, R7).
    test("a matching base_hash writes bytes verbatim and reflects in the snapshot") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                val saveBytes = "---\ntitle: Edit Me\n---\n\n# Edit Me\n\nverbatim new body.\n".toByteArray()

                val outcome = harness.writePipeline().write(
                    grantForTests(),
                    WriteIntent(page.id, RootName.PRIMARY, page.path, page.contentHash, saveBytes),
                )

                val written = outcome.shouldBeInstanceOf<WriteOutcome.Written>()
                written.newHash shouldBe citations.contentHash(saveBytes)
                Files.readAllBytes(root.resolve("guides/edit-me.md")) shouldBe saveBytes
                val reindexed = harness.builder.current.pageAt(page.rooted)!!
                reindexed.markdown shouldBe String(saveBytes, Charsets.UTF_8)
                reindexed.contentHash shouldBe citations.contentHash(saveBytes)
            }
        }
    }

    // Test 4: edit-classification rejection — slug/redirect_from/id changes are rejected (MUST-FIX 1).
    test("a slug change is rejected; a redirect_from change is rejected; an id change is rejected") {
        // A page with a frontmatter [id], [slug], and [redirect_from]; [body] varies the content edit.
        fun doc(id: String = "", slug: String = "original-slug", redirect: String = "old/path", body: String = "body.") = buildString {
            append("---\n")
            if (id.isNotEmpty()) append("id: $id\n")
            append("title: Slugged\n")
            append("slug: $slug\n")
            append("redirect_from:\n  - $redirect\n")
            append("---\n\n# Slugged\n\n$body\n")
        }.toByteArray()

        withTempTree({ root -> writePage(root, "guides/has-slug.md", String(doc(), Charsets.UTF_8)) }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                val before = Files.readAllBytes(root.resolve("guides/has-slug.md"))
                val urlBefore = page.urlPath
                val pipeline = harness.writePipeline()

                pipeline.write(grantForTests(), WriteIntent(page.id, RootName.PRIMARY, page.path, page.contentHash, doc(slug = "new-slug")))
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "slug"

                pipeline.write(
                    grantForTests(),
                    WriteIntent(page.id, RootName.PRIMARY, page.path, page.contentHash, doc(redirect = "other/path")),
                )
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "redirect_from"

                pipeline.write(
                    grantForTests(),
                    WriteIntent(page.id, RootName.PRIMARY, page.path, page.contentHash, doc(id = "00000000-0000-0000-0000-000000000000")),
                )
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "id"

                // File untouched on disk; the snapshot's urlPath unchanged; the journal stayed empty (no write happened).
                Files.readAllBytes(root.resolve("guides/has-slug.md")) shouldBe before
                harness.builder.current.pageAt(page.rooted)!!.urlPath shouldBe urlBefore
                harness.dirtyPages.all().isEmpty() shouldBe true
            }
        }
    }

    // Test 4b: id-classification compares like-for-like against the CURRENT id (the Codex FIX 1 holes).
    // (a) REMOVING a materialized id line is a change (null ≠ present) → rejected, file unchanged.
    // (b) CHANGING the id → rejected. Both compared against the file's own current id, not the pageId.
    test("removing a materialized id is rejected; changing it is rejected; the file is untouched") {
        val materialized = "---\nid: 0190aaaa-bbbb-7ccc-8ddd-eeeeeeeeeeee\ntitle: Stable\n---\n\n# Stable\n\nbody.\n"
        withTempTree({ root -> writePage(root, "guides/stable.md", materialized) }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                val before = Files.readAllBytes(root.resolve("guides/stable.md"))
                val pipeline = harness.writePipeline()

                // (a) Removing the id line entirely (null ≠ present) — a rename, rejected.
                val idRemoved = "---\ntitle: Stable\n---\n\n# Stable\n\nbody edited.\n".toByteArray()
                pipeline.write(grantForTests(), WriteIntent(page.id, RootName.PRIMARY, page.path, page.contentHash, idRemoved))
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "id"

                // (b) Changing the id to a different value — also rejected.
                val idChanged = "---\nid: 0190ffff-bbbb-7ccc-8ddd-eeeeeeeeeeee\ntitle: Stable\n---\n\n# Stable\n\nbody.\n".toByteArray()
                pipeline.write(grantForTests(), WriteIntent(page.id, RootName.PRIMARY, page.path, page.contentHash, idChanged))
                    .shouldBeInstanceOf<WriteOutcome.UnsupportedEdit>().field shouldBe "id"

                Files.readAllBytes(root.resolve("guides/stable.md")) shouldBe before // nothing written either time
                harness.dirtyPages.all().isEmpty() shouldBe true
            }
        }
    }

    // Test 4c: a body-only edit to a page whose on-disk id ≠ its ASSIGNED pageId is ALLOWED (FIX 1 hole b).
    // A duplicate (copied) page keeps its on-disk frontmatter id but is reassigned a fresh pageId; the
    // guard must compare the submitted id to the file's CURRENT id, not the pageId — so a stable-id body
    // edit on that page is a content edit, not a falsely-rejected rename.
    test("a body-only edit to a page whose on-disk id differs from its assigned pageId is allowed") {
        val sharedId = "0190aaaa-bbbb-7ccc-8ddd-eeeeeeeeeeee"
        fun dup(id: String, body: String) =
            "---\nid: $id\ntitle: Dup\n---\n\n# Dup\n\n$body\n"
        withTempTree({ root ->
            // Two files with the SAME frontmatter id: the older path keeps it, the newer is reassigned a
            // minted pageId while its on-disk id stays the shared one (PageIdentityService duplicate policy).
            writePage(root, "a-original.md", dup(sharedId, "original owner."))
            writePage(root, "b-copy.md", dup(sharedId, "the copy."))
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                // The copy: its on-disk frontmatter id is the shared id, but its assigned pageId is minted (different).
                val copy = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("b-copy.md")))
                copy.frontmatter.scalar("id") shouldBe sharedId
                copy.id.value shouldNotBe sharedId // the reassigned (minted) pageId genuinely differs

                // A body-only edit keeping the SAME on-disk id is a content edit — must be Written, not rejected.
                val edited = dup(sharedId, "the copy, edited.").toByteArray()
                harness.writePipeline().write(grantForTests(), WriteIntent(copy.id, RootName.PRIMARY, copy.path, copy.contentHash, edited))
                    .shouldBeInstanceOf<WriteOutcome.Written>()
                Files.readAllBytes(root.resolve("b-copy.md")) shouldBe edited
            }
        }
    }

    // Test 5: read-failure — an unreadable on-disk file yields a typed Unreadable, not a throw (MEDIUM 6).
    test("an unreadable on-disk file yields a typed Unreadable, not a throw") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                // A ContentStore whose CAS reports Unreadable (simulating an IOException on the internal read).
                val failingStore = object : ContentStore by realStoreDelegate(root) {
                    override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String) =
                        CasResult.Unreadable("simulated permission denied")
                }
                // Build a pipeline over the failing store but the harness's real builder/repos.
                val pipeline = WritePipeline(
                    stores = { failingStore },
                    indexBuilder = harness.builder,
                    citations = citations,
                    frontmatterParser = com.plainbase.frameworks.markdown.FrontmatterReader(),
                    dirtyPages = harness.dirtyPages,
                    idMap = harness.idMap,
                    aliasRegistry = harness.registry,
                    availability = harness.availability,
                )
                val outcome = pipeline.write(
                    grantForTests(),
                    WriteIntent(page.id, RootName.PRIMARY, page.path, page.contentHash, "x".toByteArray()),
                )
                outcome.shouldBeInstanceOf<WriteOutcome.Unreadable>().cause shouldBe "simulated permission denied"
                harness.dirtyPages.all().isEmpty() shouldBe true // nothing written ⇒ mark cleared
            }
        }
    }

    // C1b item 2: a CAS copy-fallback that may have TRUNCATED the target reports Unreadable(targetMutated
    // = true); the pipeline then RETAINS the write-ahead mark (inverse of test 5's cleared case) so
    // reconcile drift-skips a partial (stays marked) and commits+clears a fully-landed copy.
    test("a mutated-target Unreadable retains the dirty mark; reconcile drift-skips a partial and clears a fully-landed copy") {
        withTempTree(::seedOne) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = targetOf(harness)
                // CAS reports a mutated target WITHOUT writing — the on-disk bytes are controlled separately below.
                // The delegate is SCANNED so reconcile's read() sees the indexed page (a fresh store's read is null).
                val realStore = com.plainbase.frameworks.filesystem.LocalContentStore(root).also { it.scan() }
                val mutatingStore = object : ContentStore by realStore {
                    override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String) =
                        CasResult.Unreadable("copy truncated the target", targetMutated = true)
                }
                val pipeline = harness.writePipeline(store = mutatingStore)
                val saveBytes = "---\ntitle: Edit Me\n---\n\n# Edit Me\n\nthe intended save.\n".toByteArray()

                val outcome = pipeline.write(
                    grantForTests(),
                    WriteIntent(page.id, RootName.PRIMARY, page.path, page.contentHash, saveBytes),
                )
                outcome.shouldBeInstanceOf<WriteOutcome.Unreadable>()
                harness.dirtyPages.all().shouldHaveSize(1) // mark RETAINED over the mutated target (expectedHash = saveBytes')

                // Reconcile over the still-ORIGINAL on-disk bytes (a partial/never-completed copy): the hash
                // no longer matches expectedHash → drift-skip, the page stays marked.
                pipeline.reconcileDirtyPages()
                harness.dirtyPages.all().shouldHaveSize(1)

                // Reconcile over a fully-landed copy (the intended bytes are now on disk): hash matches
                // expectedHash → commit + reindex + clear the mark.
                Files.write(root.resolve("guides/edit-me.md"), saveBytes)
                pipeline.reconcileDirtyPages()
                harness.dirtyPages.all().isEmpty() shouldBe true
            }
        }
    }

    // C2 (Q8b create twin): a create whose target may have been mutated at the authority reports
    // Unreadable(targetMutated = true); the pipeline RETAINS the write-ahead mark (mirroring write()'s
    // CAS arm) so reconcile commits a fully-landed create or drift-skips. A nothing-landed create
    // Unreadable (the default) still clears, as today.
    test("a mutated-target create Unreadable retains the dirty mark; a nothing-landed one clears it") {
        withTempTree({}) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val real = realStoreDelegate(root)
                fun failingCreateStore(mutated: Boolean) = object : ContentStore by real {
                    override fun createExclusive(path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String) =
                        CreateResult.Unreadable("simulated create failure", targetMutated = mutated)
                }
                val pageId = PageId.require("01900000-0000-7000-8000-0000000000d1")
                val bytes = "---\nid: ${pageId.value}\ntitle: Doomed\n---\n\n# Doomed\n\nbody.\n".toByteArray()
                val intent = CreateIntent(pageId, RootName.PRIMARY, TreePath.require("doomed.md"), bytes)

                harness.writePipeline(store = failingCreateStore(mutated = true))
                    .create(createGrantForTests(), intent)
                    .shouldBeInstanceOf<WriteOutcome.Unreadable>()
                harness.dirtyPages.all().shouldHaveSize(1) // mark RETAINED (expectedHash = the intended bytes')

                harness.dirtyPages.clear(RootedPageId(RootName.PRIMARY, pageId))
                harness.writePipeline(store = failingCreateStore(mutated = false))
                    .create(createGrantForTests(), intent)
                    .shouldBeInstanceOf<WriteOutcome.Unreadable>()
                harness.dirtyPages.all().isEmpty() shouldBe true // nothing landed, so the mark clears
            }
        }
    }

    // W2 P2: a search-sync failure on CREATE surfaces as WrittenButUnindexed (NOT a clean Written), the
    // dirty row is RETAINED, and a later reconcile (once search recovers) clears it. Proves the create
    // path does NOT rely on rebuild()'s swallowed-listener search sync for its searchability guarantee.
    test("a create whose search sync fails is WrittenButUnindexed; the dirty row is retained and reconcile recovers it") {
        withTempTree({}) { root ->
            val provider = TogglingSearchProvider()
            lateinit var authority: com.plainbase.domain.repository.IdMapRepository
            val searchIndexer =
                SearchIndexer(provider, SectionSplitter(), { authority.retiredUnboundIds() }, { authority.isRetiredUnbound(it) })
            // No search-sync LISTENER on rebuild() — the propagating guarantee is the reindex() syncPage,
            // exactly what createAndIndex relies on; rebuild() still publishes the page (read-visible).
            IndexHarness(root, searchIndexer = searchIndexer).use { harness ->
                authority = harness.idMap
                harness.builder.rebuild()
                val pipeline = harness.writePipeline()
                val pageId = PageId.require("01900000-0000-7000-8000-0000000000a1")
                val bytes = "---\nid: ${pageId.value}\ntitle: Created\n---\n\n# Created\n\nbody.\n".toByteArray()

                provider.failOnIndex = true
                val outcome = pipeline.create(
                    createGrantForTests(),
                    CreateIntent(pageId, RootName.PRIMARY, TreePath.require("created.md"), bytes),
                )

                outcome.shouldBeInstanceOf<WriteOutcome.WrittenButUnindexed>()
                Files.readAllBytes(root.resolve("created.md")) shouldBe bytes // bytes ARE on disk
                harness.dirtyPages.all().shouldHaveSize(1) // dirty row RETAINED for reconcile

                // Search recovers; reconcile re-runs the (now-succeeding) single-page sync and clears the mark.
                provider.failOnIndex = false
                pipeline.reconcileDirtyPages()
                harness.dirtyPages.all().isEmpty() shouldBe true
                provider.indexedPageIds.contains(pageId) shouldBe true
            }
        }
    }

    // C1 (WI-3): a create threads the proposer->author + approver->committer attribution through CreateIntent into the
    // 4-arg historyHook.commit (a plain POST /pages leaves them null → the server identity).
    test("create threads author/committer through to the history hook") {
        withTempTree({}) { root ->
            var capturedAuthor: com.plainbase.domain.history.CommitIdentity? = null
            var capturedCommitter: com.plainbase.domain.history.CommitIdentity? = null
            val capturingHook = WriteHistoryHook { _, _, _, author, committer ->
                capturedAuthor = author
                capturedCommitter = committer
                "sha-1"
            }
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val pipeline = harness.writePipeline(capturingHook)
                val pageId = PageId.require("01900000-0000-7000-8000-0000000000c1")
                val bytes = "---\nid: ${pageId.value}\ntitle: Attr\n---\n\n# Attr\n\nbody.\n".toByteArray()
                val author = com.plainbase.domain.history.CommitIdentity("ci-bot", "pb_a@agent.plainbase.local")
                val committer = com.plainbase.domain.history.CommitIdentity("Alice Admin", "alice@builtin.plainbase.local")
                pipeline.create(
                    createGrantForTests(),
                    CreateIntent(pageId, RootName.PRIMARY, TreePath.require("attr.md"), bytes, author = author, committer = committer),
                ).shouldBeInstanceOf<WriteOutcome.Written>()
                capturedAuthor shouldBe author
                capturedCommitter shouldBe committer
            }
        }
    }

    test("only an eligible CREATE uses unchanged confirmation; ordinary rebuild stays on bind path") {
        withTempTree({ root ->
            writePage(root, "existing-a.md", "---\nid: 01900000-0000-7000-8000-0000000000d1\ntitle: Existing A\n---\n\n# Existing A\n")
            writePage(root, "existing-b.md", "---\nid: 01900000-0000-7000-8000-0000000000d2\ntitle: Existing B\n---\n\n# Existing B\n")
        }) { root ->
            lateinit var recording: RecordingConfirmationIdMap
            IndexHarness(
                root,
                decorateIdMap = { delegate -> RecordingConfirmationIdMap(delegate).also { recording = it } },
            ).use { harness ->
                harness.observe()
                harness.builder.rebuild()
                recording.confirmationCalls shouldBe 0
                recording.bindCalls shouldBe 2

                val pageId = PageId.require("01900000-0000-7000-8000-0000000000e1")
                val bytes = "---\nid: ${pageId.value}\ntitle: Confirmed\n---\n\n# Confirmed\n\nbody.\n".toByteArray()
                harness.writePipeline().create(
                    createGrantForTests(),
                    CreateIntent(pageId, RootName.PRIMARY, TreePath.require("confirmed.md"), bytes),
                ).shouldBeInstanceOf<WriteOutcome.Written>()

                recording.confirmationCalls shouldBe 1
                // The CREATE's initial durable bind is the only per-page bind; rebuild's resolver confirmed the set.
                recording.bindCalls shouldBe 3
            }
        }
    }

    test("a CREATE confirmation failure retains bytes, dirty recovery, and the old published snapshot") {
        withTempTree({}) { root ->
            lateinit var recording: RecordingConfirmationIdMap
            IndexHarness(
                root,
                decorateIdMap = { delegate -> RecordingConfirmationIdMap(delegate).also { recording = it } },
            ).use { harness ->
                harness.observe()
                val old = harness.builder.rebuild()
                val pipeline = harness.writePipeline()
                val pageId = PageId.require("01900000-0000-7000-8000-0000000000e3")
                val bytes = "---\nid: ${pageId.value}\ntitle: Confirmation failure\n---\n\n# Confirmation failure\n".toByteArray()
                val failure = IllegalStateException("confirmation failed after CREATE bytes landed")
                recording.confirmationFailure = failure

                val outcome = pipeline.create(
                    createGrantForTests(),
                    CreateIntent(pageId, RootName.PRIMARY, TreePath.require("confirmation-failure.md"), bytes),
                )

                val unindexed = outcome.shouldBeInstanceOf<WriteOutcome.WrittenButUnindexed>()
                unindexed.cause shouldBe failure.message
                Files.readAllBytes(root.resolve("confirmation-failure.md")) shouldBe bytes
                harness.dirtyPages.all().shouldHaveSize(1)
                harness.builder.current shouldBeSameInstanceAs old
                harness.builder.current.pages shouldBe emptyList()
                recording.confirmationCalls shouldBe 1

                // Startup-style recovery publishes the landed page before the existing targeted dirty replay.
                recording.confirmationFailure = null
                harness.builder.rebuild()
                pipeline.reconcileDirtyPages()
                harness.dirtyPages.all() shouldBe emptyList()
                harness.builder.current.byPath[RootedPath(RootName.PRIMARY, TreePath.require("confirmation-failure.md"))] shouldNotBe null
            }
        }
    }

    test("a declined CREATE confirmation falls back to the original ordered binds") {
        withTempTree({}) { root ->
            lateinit var recording: RecordingConfirmationIdMap
            IndexHarness(
                root,
                decorateIdMap = { delegate ->
                    RecordingConfirmationIdMap(delegate, forcedConfirmationResult = false).also { recording = it }
                },
            ).use { harness ->
                harness.observe()
                harness.builder.rebuild()
                val pageId = PageId.require("01900000-0000-0000-0000-0000000000e2")
                val bytes = "---\nid: ${pageId.value}\ntitle: Fallback\n---\n\n# Fallback\n\nbody.\n".toByteArray()

                harness.writePipeline().create(
                    createGrantForTests(),
                    CreateIntent(pageId, RootName.PRIMARY, TreePath.require("fallback.md"), bytes),
                ).shouldBeInstanceOf<WriteOutcome.Written>()

                recording.confirmationCalls shouldBe 1
                // Initial CREATE bind plus the resolver's ordinary bind after the confirmation declined.
                recording.bindCalls shouldBe 2
            }
        }
    }

    test("CREATE confirmation requires one exact registered local root") {
        val target = RootedPath(RootName.PRIMARY, TreePath.require("target.md"))
        runIneligibleCase(
            seed = { root ->
                writePage(root, "target.md", "---\nid: 01900000-0000-7000-8000-0000000000f1\ntitle: Target\n---\n\n# Target\n")
                writePage(root, "extra/other.md", "---\nid: 01900000-0000-7000-8000-0000000000f2\ntitle: Other\n---\n\n# Other\n")
            },
            target = target,
            rootRegistryFactory = { root ->
                RootRegistry.of(
                    listOf(
                        localRoot("docs", root),
                        localRoot("extra", root.resolve("extra")),
                    ),
                )
            },
            sourcesFactory = { root, registry, store ->
                listOf(
                    IndexBuilder.Source(registry.primary, store, NoOpHistoryProvider),
                    IndexBuilder.Source(
                        requireNotNull(registry.byName(RootName.require("extra"))),
                        com.plainbase.frameworks.filesystem.LocalContentStore(root.resolve("extra")),
                        NoOpHistoryProvider,
                    ),
                )
            },
            assertFixture = { harness ->
                harness.rootRegistry.roots shouldHaveSize 2
                harness.actualSources shouldHaveSize 2
                harness.builder.current.pages shouldHaveSize 3
            },
        )

        runIneligibleCase(
            seed = { root ->
                writePage(root, "target.md", "---\nid: 01900000-0000-7000-8000-0000000000f3\ntitle: Target\n---\n\n# Target\n")
            },
            target = target,
            rootRegistryFactory = { root ->
                RootRegistry.of(
                    listOf(
                        localRoot("docs", root),
                        localRoot("extra", root.resolve("unconfigured")),
                    ),
                )
            },
            assertFixture = { harness ->
                harness.rootRegistry.roots shouldHaveSize 2
                harness.actualSources shouldHaveSize 1
                harness.builder.current.pages shouldHaveSize 1
            },
        )

        runIneligibleCase(
            seed = { root ->
                writePage(root, "target.md", "---\nid: 01900000-0000-7000-8000-0000000000f4\ntitle: Target\n---\n\n# Target\n")
            },
            target = target,
            registeredRootsOverride = { setOf(RootName.require("extra")) },
            assertFixture = { harness ->
                harness.rootRegistry.roots.map { it.name } shouldBe listOf(RootName.PRIMARY)
                harness.builder.current.pages.single().root shouldBe RootName.PRIMARY
            },
        )

        runIneligibleCase(
            seed = { root ->
                writePage(root, "target.md", "---\nid: 01900000-0000-7000-8000-0000000000f5\ntitle: Target\n---\n\n# Target\n")
            },
            target = target,
            rootRegistryFactory = { root ->
                RootRegistry.of(
                    listOf(
                        Root(
                            name = RootName.PRIMARY,
                            backend = RootBackend.Object(bucket = "test-bucket", prefix = "docs"),
                            editable = true,
                            history = HistoryMode.OFF,
                        ),
                    ),
                )
            },
            assertFixture = { harness ->
                harness.actualSources.single().root.backend.shouldBeInstanceOf<RootBackend.Object>()
                harness.builder.current.pages shouldHaveSize 1
            },
        )
    }

    test("CREATE confirmation rejects incomplete, buffered, and unavailable evidence") {
        val target = RootedPath(RootName.PRIMARY, TreePath.require("target.md"))
        lateinit var incompleteScan: EligibilityStore
        runIneligibleCase(
            seed = { root -> seedTarget(root, "01900000-0000-7000-8000-0000000000f6") },
            target = target,
            contentStoreFactory = { root ->
                EligibilityStore(LocalContentStore(root), scanMutation = { scan -> scan.copy(complete = false) })
                    .also { incompleteScan = it }
            },
            assertFixture = { harness ->
                incompleteScan.observedScan!!.complete shouldBe false
                harness.builder.current.pages shouldHaveSize 1
            },
        )

        lateinit var incompletePageRead: EligibilityStore
        val unreadPath = TreePath.require("unread.md")
        runIneligibleCase(
            seed = { root ->
                seedTarget(root, "01900000-0000-7000-8000-0000000000f7")
                writePage(root, "unread.md", "---\nid: 01900000-0000-7000-8000-0000000000ff\ntitle: Unread\n---\n\n# Unread\n")
            },
            target = target,
            contentStoreFactory = { root ->
                EligibilityStore(LocalContentStore(root), unreadPath = unreadPath).also { incompletePageRead = it }
            },
            assertFixture = { harness ->
                incompletePageRead.readClassifiedPaths shouldContainExactly listOf(target.path, unreadPath)
                harness.builder.current.byPath[target] shouldNotBe null
                harness.builder.current.byPath[RootedPath(target.root, unreadPath)] shouldBe null
            },
        )

        lateinit var bufferedScan: EligibilityStore
        runIneligibleCase(
            seed = { root -> seedTarget(root, "01900000-0000-7000-8000-0000000000f8") },
            target = target,
            contentStoreFactory = { root ->
                EligibilityStore(
                    LocalContentStore(root),
                    scanMutation = { scan ->
                        scan.copy(
                            issues = scan.issues + ScanIssue.PathCollision(
                                path = TreePath.require("buffered.md"),
                                winnerRawName = "buffered.md",
                                loserRawName = "buffered-copy.md",
                            ),
                        )
                    },
                ).also { bufferedScan = it }
            },
            assertFixture = { harness ->
                bufferedScan.observedScan!!.issues shouldHaveSize 1
                harness.idMap.issues().filterIsInstance<IdentityIssue.PathCollision>() shouldHaveSize 1
            },
        )

        runIneligibleCase(
            seed = { root ->
                writePage(root, "a.md", "---\nid: 01900000-0000-7000-8000-0000000000f9\nslug: same\ntitle: A\n---\n\n# A\n")
                writePage(root, "b.md", "---\nid: 01900000-0000-7000-8000-0000000000fa\nslug: same\ntitle: B\n---\n\n# B\n")
            },
            target = RootedPath(RootName.PRIMARY, TreePath.require("a.md")),
            assertFixture = { harness ->
                harness.builder.current.pages shouldHaveSize 2
                harness.idMap.issues().filterIsInstance<IdentityIssue.PathSlugCollision>() shouldHaveSize 1
            },
        )

        val unavailable = RootAvailability(Clock.System)
        unavailable.markUnavailable(RootName.PRIMARY, UnavailableCause.VANISHED)
        runIneligibleCase(
            seed = { root -> seedTarget(root, "01900000-0000-7000-8000-0000000000fb") },
            target = target,
            availability = unavailable,
            assertFixture = { harness ->
                harness.availability.current().isAvailable(RootName.PRIMARY) shouldBe false
                harness.builder.current.pages shouldBe emptyList()
            },
        )
    }

    test("CREATE confirmation rejects absent, wrong-root, unmaterialized, and conflicted assignments") {
        val target = RootedPath(RootName.PRIMARY, TreePath.require("target.md"))
        runIneligibleCase(
            seed = { root -> seedTarget(root, "01900000-0000-7000-8000-0000000000fc", "present.md") },
            target = target,
            assertFixture = { harness -> harness.builder.current.byPath[target] shouldBe null },
        )

        runIneligibleCase(
            seed = { root -> seedTarget(root, "01900000-0000-7000-8000-0000000000fd") },
            target = RootedPath(RootName.require("extra"), TreePath.require("target.md")),
            assertFixture = { harness ->
                harness.builder.current.pages.single().root shouldBe RootName.PRIMARY
                harness.builder.current.byPath[RootedPath(RootName.require("extra"), TreePath.require("target.md"))] shouldBe null
            },
        )

        runIneligibleCase(
            seed = { root -> writePage(root, "target.md", "---\ntitle: Target\n---\n\n# Target\n") },
            target = target,
            assertFixture = { harness ->
                harness.idMap.find(target)!!.materialized shouldBe false
                harness.builder.current.pages.single().materialized shouldBe false
            },
        )

        runIneligibleCase(
            seed = { root ->
                writePage(root, "target.md", "---\nid: 01900000-0000-7000-8000-0000000000fe\ntitle: Target\n---\n\n# Target\n")
                writePage(root, "copy.md", "---\nid: 01900000-0000-7000-8000-0000000000fe\ntitle: Copy\n---\n\n# Copy\n")
            },
            target = target,
            assertFixture = { harness ->
                harness.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>() shouldHaveSize 1
                harness.builder.current.pages shouldHaveSize 2
            },
        )
    }
})

/**
 * A [SearchProvider] stand-in whose [index] throws while [failOnIndex] is set (simulating an FTS lock),
 * recording the page ids it successfully indexed otherwise. [rebuild]/[indexedState] never fail, so a
 * full `rebuild()` still publishes the snapshot (read-visibility) even while single-page sync fails.
 */
private class TogglingSearchProvider : SearchProvider {
    var failOnIndex = false
    val indexedPageIds = mutableSetOf<PageId>()

    override fun index(pages: List<PageDocuments>) {
        if (failOnIndex) throw java.io.IOException("simulated FTS lock on index")
        pages.forEach { indexedPageIds += it.pageId }
    }

    override fun delete(ids: Collection<RootedPageId>) = Unit
    override fun search(query: SearchQuery): SearchResults = SearchResults(total = 0, hits = emptyList())
    override fun rebuild(pages: Sequence<PageDocuments>, retired: Set<RootedPageId>?) = pages.forEach { indexedPageIds += it.pageId }
    override fun indexedState(): Map<RootedPageId, PageSearchState> = emptyMap()
}

/** A real LocalContentStore over [root] — the delegate behind a CAS-failing test stand-in. */
private fun realStoreDelegate(root: Path): ContentStore = com.plainbase.frameworks.filesystem.LocalContentStore(root)

private class RecordingConfirmationIdMap(
    private val delegate: IdMapRepository,
    private val forcedConfirmationResult: Boolean? = null,
) : IdMapRepository by delegate {
    var bindCalls = 0
    var confirmationCalls = 0
    var confirmationFailure: Throwable? = null

    override fun bind(path: RootedPath, id: PageId, materialized: Boolean, supersession: Supersession): BindOutcome {
        bindCalls++
        return delegate.bind(path, id, materialized, supersession)
    }

    override fun confirmUnchangedBindings(expected: List<IdBinding>): Boolean {
        confirmationCalls++
        confirmationFailure?.let { throw it }
        return forcedConfirmationResult ?: delegate.confirmUnchangedBindings(expected)
    }
}

private class EligibilityStore(
    private val delegate: ContentStore,
    private val scanMutation: (ScanResult) -> ScanResult = { it },
    private val unreadPath: TreePath? = null,
) : ContentStore by delegate {
    var observedScan: ScanResult? = null
    val readClassifiedPaths = mutableListOf<TreePath>()

    override fun scan(): ScanResult = scanMutation(delegate.scan()).also { observedScan = it }

    override fun readClassified(path: TreePath): StoreRead {
        readClassifiedPaths += path
        return if (path == unreadPath) StoreRead.NoBytes else delegate.readClassified(path)
    }
}

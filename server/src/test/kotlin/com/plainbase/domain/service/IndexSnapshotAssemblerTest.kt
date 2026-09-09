package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.render.RenderedPage
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs

private val UNREAD_ID = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
private val PEER_ID = PageId.require("0197b555-1111-7222-8333-444455556666")
private val EXTRA_A_ID = PageId.require("0197c666-1111-7222-8333-444455556666")
private val EXTRA_Z_ID = PageId.require("0197d777-1111-7222-8333-444455556666")
private val URL_PRIMARY_A_ID = PageId.require("0197e888-1111-7222-8333-444455556666")
private val URL_PRIMARY_Z_ID = PageId.require("0197f999-1111-7222-8333-444455556666")
private val URL_EXTRA_A_ID = PageId.require("0197aaaa-1111-7222-8333-444455556666")
private val URL_EXTRA_Z_ID = PageId.require("0197bbbb-1111-7222-8333-444455556666")

class IndexSnapshotAssemblerTest : FunSpec({

    test("should preserve an unread incumbent and publish a fresh peer on a complete walk") {
        withTempTree(
            seed = { root ->
                writePage(root, "unread.md", identifiedPage(UNREAD_ID, "Unread", "# Unread\n\nold unread body\n"))
                writePage(root, "peer.md", identifiedPage(PEER_ID, "Peer", "# Peer\n\nold peer body\n"))
            },
        ) { root ->
            val real = LocalContentStore(root)
            val unreadPath = TreePath.require("unread.md")
            val peerPath = TreePath.require("peer.md")
            val expectedBindings = listOf(
                IdBinding(RootedPath(RootName.PRIMARY, unreadPath), UNREAD_ID, true),
                IdBinding(RootedPath(RootName.PRIMARY, peerPath), PEER_ID, true),
            )
            var unread = false
            var unreadReads = 0
            val scans = mutableListOf<ScanResult>()
            val observedCurrents = mutableListOf<PageIndex>()
            val store = object : ContentStore by real {
                override fun scan(): ScanResult = real.scan().also { scans += it }

                override fun readClassified(path: TreePath): StoreRead {
                    if (unread && path == unreadPath) {
                        unreadReads++
                        return StoreRead.NoBytes
                    }
                    return real.readClassified(path)
                }
            }
            val published = mutableListOf<PageIndex>()
            lateinit var harness: IndexHarness

            harness = IndexHarness(
                root,
                contentStore = store,
                listeners = listOf(
                    IndexBuilder.PublicationListener { snapshot, _ ->
                        published += snapshot
                        observedCurrents += harness.builder.current
                    },
                ),
            )
            harness.use {
                val first = harness.builder.rebuild()
                val incumbent = first.byPath.getValue(RootedPath(RootName.PRIMARY, unreadPath))
                val firstPeer = first.byPath.getValue(RootedPath(RootName.PRIMARY, peerPath))
                val warmUnreadBinding = requireNotNull(harness.idMap.bindingInRoot(RootName.PRIMARY, UNREAD_ID))
                val warmPeerBinding = requireNotNull(harness.idMap.bindingInRoot(RootName.PRIMARY, PEER_ID))
                harness.idMap.bindings().toSet() shouldBe expectedBindings.toSet()
                first.section(RootName.PRIMARY).pages.map { it.path }.toSet() shouldBe setOf(unreadPath, peerPath)
                published.single() shouldBeSameInstanceAs first
                observedCurrents.single() shouldBeSameInstanceAs first
                harness.absence.classify(RootedPath(RootName.PRIMARY, unreadPath), StoreRead.NoBytes) shouldBe
                    ContentRead.AbsenceUnknown

                val newPeer = identifiedPage(PEER_ID, "Peer", "# Peer\n\nnew peer marker\n")
                writePage(root, peerPath.value, newPeer)
                unread = true
                val second = harness.builder.rebuild()

                scans.last().complete shouldBe true
                scans.last().files.map { it.path }.toSet() shouldBe setOf(unreadPath, peerPath)
                unreadReads shouldBe 1
                harness.idMap.bindings().toSet() shouldBe expectedBindings.toSet()
                second.byPath[RootedPath(RootName.PRIMARY, unreadPath)] shouldBe null
                harness.idMap.bindingInRoot(RootName.PRIMARY, UNREAD_ID) shouldBe warmUnreadBinding
                harness.idMap.retiredAt(RootName.PRIMARY, UNREAD_ID).shouldBeNull()
                harness.idMap.bindingInRoot(RootName.PRIMARY, PEER_ID) shouldBe warmPeerBinding
                harness.idMap.retiredAt(RootName.PRIMARY, PEER_ID).shouldBeNull()
                harness.limbo.holds(RootName.PRIMARY, UNREAD_ID) shouldBe true
                second.byPath.getValue(RootedPath(RootName.PRIMARY, peerPath)).let { peer ->
                    peer.id shouldBe PEER_ID
                    peer.title shouldBe "Peer"
                    peer.markdown shouldContain "new peer marker"
                    peer.contentHash shouldBe CitationFactory().contentHash(newPeer.toByteArray(Charsets.UTF_8))
                    peer.contentHash shouldNotBe firstPeer.contentHash
                    peer.html shouldContain "new peer marker"
                    peer.sections.single().text shouldContain "new peer marker"
                    peer shouldNotBe firstPeer
                }
                second.section(RootName.PRIMARY).pages.map { it.path } shouldBe listOf(peerPath)
                second.pages shouldNotBe first.pages
                incumbent.id shouldBe UNREAD_ID
                incumbent.markdown shouldContain "old unread body"
                harness.availability.current().isAvailable(RootName.PRIMARY) shouldBe true
                harness.builder.current shouldBeSameInstanceAs second
                published.last() shouldBeSameInstanceAs second
                observedCurrents.last() shouldBeSameInstanceAs second
            }
        }
    }

    test("should publish fresh peer content while omitting an incumbent from an incomplete walk") {
        withTempTree(
            seed = { root ->
                writePage(root, "notes/omitted.md", identifiedPage(UNREAD_ID, "Omitted", "# Omitted\n\nold omitted body\n"))
                writePage(root, "peer.md", identifiedPage(PEER_ID, "Peer", "# Peer\n\nold peer body\n"))
            },
        ) { root ->
            val real = LocalContentStore(root)
            val omittedPath = TreePath.require("notes/omitted.md")
            val peerPath = TreePath.require("peer.md")
            val expectedBindings = listOf(
                IdBinding(RootedPath(RootName.PRIMARY, omittedPath), UNREAD_ID, true),
                IdBinding(RootedPath(RootName.PRIMARY, peerPath), PEER_ID, true),
            )
            var short = false
            val scans = mutableListOf<ScanResult>()
            var realPaths = emptySet<TreePath>()
            var returnedPaths = emptySet<TreePath>()
            var returnedComplete = true
            val published = mutableListOf<PageIndex>()
            val observedCurrents = mutableListOf<PageIndex>()
            lateinit var harness: IndexHarness
            val shortWalk = object : ContentStore by real {
                override fun scan(): ScanResult {
                    val actual = real.scan()
                    realPaths = actual.files.map { it.path }.toSet()
                    val result = if (short) {
                        actual.copy(files = actual.files.filterNot { it.path == omittedPath }, complete = false)
                    } else {
                        actual
                    }
                    returnedPaths = result.files.map { it.path }.toSet()
                    returnedComplete = result.complete
                    scans += result
                    return result
                }
            }

            harness = IndexHarness(
                root,
                contentStore = shortWalk,
                listeners = listOf(
                    IndexBuilder.PublicationListener { snapshot, _ ->
                        published += snapshot
                        observedCurrents += harness.builder.current
                    },
                ),
            )
            harness.use {
                val first = harness.builder.rebuild()
                val warmOmittedBinding = requireNotNull(harness.idMap.bindingInRoot(RootName.PRIMARY, UNREAD_ID))
                val warmPeerBinding = requireNotNull(harness.idMap.bindingInRoot(RootName.PRIMARY, PEER_ID))
                harness.idMap.bindings().toSet() shouldBe expectedBindings.toSet()
                first.section(RootName.PRIMARY).pages.map { it.path }.toSet() shouldBe setOf(omittedPath, peerPath)
                published.single() shouldBeSameInstanceAs first
                observedCurrents.single() shouldBeSameInstanceAs first
                val newPeer = identifiedPage(PEER_ID, "Peer", "# Peer\n\nshort walk fresh marker\n")
                writePage(root, peerPath.value, newPeer)
                short = true
                val second = harness.builder.rebuild()

                realPaths shouldBe setOf(omittedPath, peerPath)
                returnedPaths shouldBe setOf(peerPath)
                returnedComplete shouldBe false
                scans.last().files.map { it.path }.toSet() shouldBe setOf(peerPath)
                scans.last().complete shouldBe false
                harness.idMap.bindings().toSet() shouldBe expectedBindings.toSet()
                second.byPath[RootedPath(RootName.PRIMARY, omittedPath)] shouldBe null
                harness.idMap.bindingInRoot(RootName.PRIMARY, UNREAD_ID) shouldBe warmOmittedBinding
                harness.idMap.retiredAt(RootName.PRIMARY, UNREAD_ID).shouldBeNull()
                harness.idMap.bindingInRoot(RootName.PRIMARY, PEER_ID) shouldBe warmPeerBinding
                harness.idMap.retiredAt(RootName.PRIMARY, PEER_ID).shouldBeNull()
                harness.limbo.holds(RootName.PRIMARY, UNREAD_ID) shouldBe true
                second.section(RootName.PRIMARY).pages.map { it.path } shouldBe listOf(peerPath)
                second.byPath.getValue(RootedPath(RootName.PRIMARY, peerPath)).let { peer ->
                    peer.title shouldBe "Peer"
                    peer.markdown shouldContain "short walk fresh marker"
                    peer.contentHash shouldBe CitationFactory().contentHash(newPeer.toByteArray(Charsets.UTF_8))
                    peer.html shouldContain "short walk fresh marker"
                    peer.sections.single().text shouldContain "short walk fresh marker"
                }
                harness.availability.current().isAvailable(RootName.PRIMARY) shouldBe true
                harness.builder.current shouldBeSameInstanceAs second
                published.last() shouldBeSameInstanceAs second
                observedCurrents.last() shouldBeSameInstanceAs second
            }
        }
    }

    test("should carry every skipped root page instance while publishing the readable root") {
        withTempTree(
            seed = { root ->
                writePage(root, "primary.md", identifiedPage(PEER_ID, "Primary", "# Primary\n\nold primary\n"))
            },
        ) { primary ->
            withTempTree(
                seed = { root ->
                    writePage(root, "extra-a.md", identifiedPage(EXTRA_A_ID, "Extra A", "# Extra A\n\nold extra a\n"))
                    writePage(root, "extra-z.md", identifiedPage(EXTRA_Z_ID, "Extra Z", "# Extra Z\n\nold extra z\n"))
                },
            ) { extra ->
                val extraName = RootName.require("extra")
                val registry = RootRegistry.of(listOf(localRoot("docs", primary), localRoot("extra", extra)))
                val realExtra = LocalContentStore(extra)
                var extraAvailable = true
                var extraScanCalls = 0
                val renderedRoots = mutableListOf<RootName>()
                val actualRenderCounts = mutableMapOf<RootName, Int>()
                val published = mutableListOf<PageIndex>()
                val observedCurrents = mutableListOf<PageIndex>()
                val skippedExtra = object : ContentStore by realExtra {
                    override fun available(): Boolean = extraAvailable && realExtra.available()

                    override fun scan(): ScanResult = realExtra.scan().also { extraScanCalls += 1 }
                }
                val sources = listOf(
                    IndexBuilder.Source(registry.primary, LocalContentStore(primary), NoOpHistoryProvider),
                    IndexBuilder.Source(requireNotNull(registry.byName(extraName)), skippedExtra, NoOpHistoryProvider),
                )
                val rendererFactory: (PageIndexView) -> MarkdownRenderer = { view ->
                    val root = when {
                        runCatching { view.pageUrl(TreePath.require("primary.md")) }.isSuccess -> RootName.PRIMARY
                        runCatching { view.pageUrl(TreePath.require("extra-a.md")) }.isSuccess -> extraName
                        else -> error("renderer view did not expose either characterized root")
                    }
                    renderedRoots += root
                    val delegate = FlexmarkRenderer(view)
                    object : MarkdownRenderer {
                        override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage =
                            delegate.render(sourcePath, source).also {
                                actualRenderCounts[root] = actualRenderCounts.getOrDefault(root, 0) + 1
                            }
                    }
                }
                lateinit var harness: IndexHarness

                harness = IndexHarness(
                    primary,
                    rootRegistry = registry,
                    sources = sources,
                    rendererFactory = rendererFactory,
                    listeners = listOf(
                        IndexBuilder.PublicationListener { snapshot, _ ->
                            published += snapshot
                            observedCurrents += harness.builder.current
                        },
                    ),
                )
                harness.use {
                    val first = harness.builder.rebuild()
                    val carried = first.section(extraName).pages.associateBy { it.path }
                    val expectedBindings = listOf(
                        IdBinding(RootedPath(RootName.PRIMARY, TreePath.require("primary.md")), PEER_ID, true),
                        IdBinding(RootedPath(extraName, TreePath.require("extra-a.md")), EXTRA_A_ID, true),
                        IdBinding(RootedPath(extraName, TreePath.require("extra-z.md")), EXTRA_Z_ID, true),
                    )
                    harness.idMap.bindings().toSet() shouldBe expectedBindings.toSet()
                    carried.keys shouldBe setOf(TreePath.require("extra-a.md"), TreePath.require("extra-z.md"))
                    val warmBindings = carried.mapValues { (_, page) ->
                        requireNotNull(harness.idMap.bindingInRoot(extraName, page.id))
                    }
                    renderedRoots shouldBe listOf(RootName.PRIMARY, extraName)
                    actualRenderCounts shouldBe mapOf(RootName.PRIMARY to 1, extraName to 2)
                    extraScanCalls shouldBe 1
                    published.single() shouldBeSameInstanceAs first
                    observedCurrents.single() shouldBeSameInstanceAs first

                    val newPrimary = identifiedPage(PEER_ID, "Primary", "# Primary\n\nnew primary marker\n")
                    writePage(primary, "primary.md", newPrimary)
                    writePage(extra, "extra-a.md", identifiedPage(EXTRA_A_ID, "Extra A", "# Extra A\n\nchanged extra a\n"))
                    extraAvailable = false
                    val second = harness.builder.rebuild()

                    extraScanCalls shouldBe 1
                    renderedRoots shouldBe listOf(RootName.PRIMARY, extraName, RootName.PRIMARY)
                    actualRenderCounts shouldBe mapOf(RootName.PRIMARY to 2, extraName to 2)
                    harness.idMap.bindings().toSet() shouldBe expectedBindings.toSet()
                    harness.availability.current().isAvailable(extraName) shouldBe false
                    carried.forEach { (path, page) ->
                        second.byPath.getValue(RootedPath(extraName, path)) shouldBeSameInstanceAs page
                        second.byPath.getValue(RootedPath(extraName, path)).markdown shouldContain "old extra"
                        harness.idMap.bindingInRoot(extraName, page.id) shouldBe warmBindings.getValue(path)
                        harness.idMap.retiredAt(extraName, page.id).shouldBeNull()
                        harness.limbo.holds(extraName, page.id) shouldBe true
                    }
                    second.section(extraName).pages shouldHaveSize carried.size
                    second.section(extraName).pages.map { it.root }.distinct() shouldBe listOf(extraName)
                    second.sections.map { it.root } shouldBe listOf(RootName.PRIMARY, extraName)
                    second.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("primary.md"))).let { primaryPage ->
                        primaryPage.id shouldBe PEER_ID
                        primaryPage.title shouldBe "Primary"
                        primaryPage.markdown shouldContain "new primary marker"
                        primaryPage.contentHash shouldBe CitationFactory().contentHash(newPrimary.toByteArray(Charsets.UTF_8))
                    }
                    harness.limbo.holds(RootName.PRIMARY, PEER_ID) shouldBe false
                    harness.availability.current().isAvailable(RootName.PRIMARY) shouldBe true
                    harness.builder.current shouldBeSameInstanceAs second
                    published.last() shouldBeSameInstanceAs second
                    observedCurrents.last() shouldBeSameInstanceAs second
                }
            }
        }
    }

    test("should render each root from its URL-complete view and copy the single render result") {
        withTempTree(seed = { root ->
            writePage(
                root,
                "a.md",
                "---\nid: ${URL_PRIMARY_A_ID.value}\ntitle: Scalar Docs\n---\n\n# A docs\n\n[target](z.md)\n",
            )
            writePage(
                root,
                "z.md",
                "---\nid: ${URL_PRIMARY_Z_ID.value}\nslug: docs-target\n---\n\n# H1-only target\n\nTarget body.\n",
            )
        }) { primary ->
            withTempTree(seed = { root ->
                writePage(root, "a.md", "---\nid: ${URL_EXTRA_A_ID.value}\n---\n\n[target](z.md)\n\nplain body\n")
                writePage(
                    root,
                    "z.md",
                    "---\nid: ${URL_EXTRA_Z_ID.value}\nslug: extra-target\n---\n\n# Extra target\n\nExtra body.\n",
                )
            }) { extra ->
                val extraName = RootName.require("extra")
                val registry = RootRegistry.of(listOf(localRoot("docs", primary), localRoot("extra", extra)))
                val expectedRoots = listOf(RootName.PRIMARY, extraName)
                val expectedTargetUrls = mapOf(
                    RootName.PRIMARY to "/docs/docs-target",
                    extraName to "/extra/extra-target",
                )
                val expectedPageUrls = mapOf(
                    RootName.PRIMARY to "/docs/a",
                    extraName to "/extra/a",
                )
                val factoryRoots = mutableListOf<RootName>()
                val captured = mutableMapOf<Pair<RootName, TreePath>, MutableList<RenderedPage>>()
                val renderCounts = mutableMapOf<Pair<RootName, TreePath>, Int>()
                val factory = { view: PageIndexView ->
                    val expectedRoot = expectedRoots.getOrNull(factoryRoots.size)
                        ?: error("unexpected renderer factory call ${factoryRoots.size + 1}")
                    factoryRoots += expectedRoot
                    view.pageUrl(TreePath.require("z.md")) shouldBe expectedTargetUrls.getValue(expectedRoot)
                    view.pageUrl(TreePath.require("a.md")) shouldBe expectedPageUrls.getValue(expectedRoot)
                    val delegate = FlexmarkRenderer(view)
                    object : MarkdownRenderer {
                        override fun render(sourcePath: TreePath, source: ByteArray): RenderedPage =
                            delegate.render(sourcePath, source).also { rendered ->
                                val key = expectedRoot to sourcePath
                                renderCounts[key] = renderCounts.getOrDefault(key, 0) + 1
                                captured.getOrPut(key) { mutableListOf() } += rendered
                            }
                    }
                }
                val sources = listOf(
                    IndexBuilder.Source(registry.primary, LocalContentStore(primary), NoOpHistoryProvider),
                    IndexBuilder.Source(requireNotNull(registry.byName(extraName)), LocalContentStore(extra), NoOpHistoryProvider),
                )

                IndexHarness(primary, rootRegistry = registry, sources = sources, rendererFactory = factory).use { harness ->
                    val snapshot = harness.builder.rebuild()

                    factoryRoots.size shouldBe expectedRoots.size
                    factoryRoots shouldBe expectedRoots
                    captured.size shouldBe 4
                    renderCounts shouldBe mapOf(
                        (RootName.PRIMARY to TreePath.require("a.md")) to 1,
                        (RootName.PRIMARY to TreePath.require("z.md")) to 1,
                        (extraName to TreePath.require("a.md")) to 1,
                        (extraName to TreePath.require("z.md")) to 1,
                    )
                    snapshot.sections.forEach { section ->
                        section.pages.forEach { page ->
                            captured[page.root to page.path].shouldNotBeNull().also { renders ->
                                renders shouldHaveSize 1
                                val render = renders.single()
                                page.html shouldBe render.html
                                page.headings shouldBe render.headings
                                page.links shouldBe render.links
                                page.sections shouldBe render.sections
                            }
                        }
                    }
                    snapshot.sections.map { it.root } shouldBe expectedRoots
                    snapshot.pages.filter { it.root == RootName.PRIMARY }.single { it.path == TreePath.require("a.md") }.title shouldBe
                        "Scalar Docs"
                    snapshot.pages.filter { it.root == RootName.PRIMARY }.single { it.path == TreePath.require("z.md") }.title shouldBe
                        "H1-only target"
                    snapshot.pages.filter { it.root == extraName }.single { it.path == TreePath.require("a.md") }.title shouldBe "a"
                    snapshot.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("a.md"))).id shouldBe URL_PRIMARY_A_ID
                    snapshot.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("z.md"))).id shouldBe URL_PRIMARY_Z_ID
                    snapshot.byPath.getValue(RootedPath(extraName, TreePath.require("a.md"))).id shouldBe URL_EXTRA_A_ID
                    snapshot.byPath.getValue(RootedPath(extraName, TreePath.require("z.md"))).id shouldBe URL_EXTRA_Z_ID
                    snapshot.pages.filter { it.root == RootName.PRIMARY }.single { it.path == TreePath.require("a.md") }.html shouldContain
                        "/docs/docs-target"
                    snapshot.pages.filter { it.root == extraName }.single { it.path == TreePath.require("a.md") }.html shouldContain
                        "/extra/extra-target"
                    snapshot.pages.filter { it.root == RootName.PRIMARY }.single { it.path == TreePath.require("a.md") }
                        .links.single().outcome shouldBe LinkOutcome.Resolved.Page(
                        TreePath.require("z.md"),
                        "/docs/docs-target",
                    )
                    snapshot.pages.filter { it.root == extraName }.single { it.path == TreePath.require("a.md") }
                        .links.single().outcome shouldBe LinkOutcome.Resolved.Page(
                        TreePath.require("z.md"),
                        "/extra/extra-target",
                    )
                    snapshot.pages.map { it.root to it.path }.toSet() shouldBe setOf(
                        RootName.PRIMARY to TreePath.require("a.md"),
                        RootName.PRIMARY to TreePath.require("z.md"),
                        extraName to TreePath.require("a.md"),
                        extraName to TreePath.require("z.md"),
                    )
                    snapshot.byPath.keys.map { it.root to it.path }.distinct() shouldHaveSize 4
                    snapshot.pages.isNotEmpty() shouldBe true
                }
            }
        }
    }
})

private fun identifiedPage(id: PageId, title: String, body: String): String =
    "---\nid: ${id.value}\ntitle: $title\n---\n\n$body"

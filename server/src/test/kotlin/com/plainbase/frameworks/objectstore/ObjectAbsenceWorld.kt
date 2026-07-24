package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRootTopologyRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import java.nio.file.Files
import java.nio.file.Path

/**
 * One OBJECT-backed install, across as many PROCESS LIFETIMES as a test needs (C3).
 *
 * The durable state - the app DB (`id_map`, `retired_binding`, `root_topology`, `root_observation`), the DATA_DIR
 * mirror, and the mirror-state etag map - lives HERE and survives every [boot]. Everything a process holds in memory
 * (the store, its LIST generation, the epochs, the builder) is rebuilt by [boot] and does not.
 *
 * **That split is the point.** The wrong-bucket wipe is not a one-poll bug: an earlier draft of this design limboed
 * the rows on the first poll and RECORDED the new binding, so the second poll saw the binding as "unchanged" and
 * reaped the corpus. Anything that survives a [boot] here survives a restart in production, and nothing else does.
 */
internal class ObjectAbsenceWorld : AutoCloseable {

    private val dataDir: Path = Files.createTempDirectory("pb-object-absence")
    val mirrorRoot: Path = dataDir.resolve("mirror")
    private val stateFile: Path = dataDir.resolve("mirror-state")

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)

    val idMap = SqlDelightIdMapRepository(database)
    val retirements = SqlDelightRetirementRepository(database)
    val topology = SqlDelightRootTopologyRepository(database)
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    val limbo = RootLimbo()

    private val root = Root(
        name = RootName.MAIN,
        backend = RootBackend.Object(bucket = "docs", prefix = ""),
        editable = true,
        history = HistoryMode.OFF,
    )
    private val registry: RootRegistry = RootRegistry.of(listOf(root))
    private val aliasRegistry = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))

    /** The store the last [boot] built - the poll seam (`pollOnce`) tests drive between rebuilds. */
    lateinit var store: ObjectContentStore
        private set

    /**
     * ONE process lifetime: build the store over [bucket], record where the root now points (the boot seam
     * `Application.kt` runs BEFORE the first LIST), hydrate the mirror, and hand back this process's builder.
     *
     * [binding] is what the CONFIG says. Handing it in separately from [bucket] is the whole test surface: a wrong
     * bucket is a real, reachable, perfectly healthy object store that simply is not ours.
     */
    fun boot(bucket: ObjectStoreClient, binding: RootBinding, hydrate: Boolean = true): IndexBuilder {
        val ignoreRules = IgnoreRules()
        val mirror = LocalContentStore(root = mirrorRoot, ignoreRules = ignoreRules)
        store = ObjectContentStore(
            client = bucket,
            mirror = mirror,
            state = MirrorState(stateFile),
            binding = binding,
            rowsAtStart = ::rowsOfMain,
            keyPrefix = "",
            pollSeconds = 3600,
            dirtyPaths = { emptySet() },
            mirrorRoot = mirrorRoot,
            ignoreRules = ignoreRules,
        )
        // The boot seam, in the order `Application.kt` runs it: record the binding, and an UNVERIFIED one re-derives
        // the mirror from the bucket it now names - a witness must be made of bytes we fetched, not bytes we kept.
        if (BindingLatch(topology).observe(RootName.MAIN, binding) != BindingStatus.TRUSTED) store.rebind()
        if (hydrate) store.hydrate()
        return builder()
    }

    /**
     * A fresh builder over the CURRENT store - a second rebuild in the same process lifetime. [history] defaults to
     * the fail-closed NoOp (byte-identical to every existing caller); the C4 backend-gate row injects a throwing spy
     * to prove an OBJECT root's git-over-the-mirror provider is never asked for the absence-oracle members.
     */
    fun builder(history: HistoryProvider = NoOpHistoryProvider): IndexBuilder = IndexBuilder(
        sources = listOf(IndexBuilder.Source(root, store, history, manifests = store)),
        frontmatterParser = FrontmatterReader(),
        rendererFactory = { view -> FlexmarkRenderer(view) },
        identity = PageIdentityService(UuidV7IdProvider()),
        patcher = FrontmatterPatcher(),
        idMap = idMap,
        aliasRegistry = aliasRegistry,
        checkpoint = checkpoints,
        citations = CitationFactory(),
        rootRank = registry::rank,
        registeredRoots = setOf(RootName.MAIN),
        listeners = listOf(IndexBuilder.PublicationListener(checkpoints::replaceFrom)),
        retirements = retirements,
        limbo = limbo,
        bindings = BindingLatch(topology),
    )

    /** Main's durable rows + binding_epoch - the same boundary `contentModule` wires as the LIST's `rowsAtStart`
     *  (epoch co-read FIRST, revoke-before-stamp C5). */
    fun rowsOfMain(): RowsAtStart {
        val bindingEpoch = retirements.bindingEpoch(RootName.MAIN)
        val rows = idMap.bindings().filter { it.path.root == RootName.MAIN }.mapTo(mutableSetOf()) { BindingRef(it.path.path, it.id) }
        return RowsAtStart(rows, bindingEpoch)
    }

    override fun close() {
        driver.close()
        dataDir.toFile().deleteRecursively()
    }
}

/** A page whose id lives in its own frontmatter - a MATERIALIZED page, the only kind a LIST-less bucket can witness. */
internal fun materializedPage(id: PageId, title: String): ByteArray =
    "---\nid: ${id.value}\n---\n\n# $title\n\nbody\n".toByteArray()

/** Seeds [bucket] with a materialized page at [path] and returns the id it carries. */
internal fun FakeObjectStore.seedPage(path: String, id: PageId, title: String = path): PageId {
    seed(path, materializedPage(id, title))
    return id
}

internal fun page(path: String): TreePath = TreePath.require(path)

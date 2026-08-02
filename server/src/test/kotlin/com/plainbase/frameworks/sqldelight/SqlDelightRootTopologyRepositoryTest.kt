package com.plainbase.frameworks.sqldelight

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.RootTopologyRepository
import com.plainbase.domain.root.AtRisk
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.ObjectManifest
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootTopology
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.slf4j.LoggerFactory

class SqlDelightRootTopologyRepositoryTest : FunSpec({

    val root = RootName.PRIMARY
    val binding = RootBinding("https://objects.example|handbook|")
    val id = PageId.require("01900000-0000-7000-9000-0000000000d1")
    val ref = BindingRef(TreePath.require("guides/deploy.md"), id)

    val corruptSnapshots = listOf(
        "malformed JSON" to "not-json",
        "future schema" to """{"version":2,"bindings":[]}""",
        "invalid binding" to """{"version":1,"bindings":[{"path":"../escape.md","id":"not-an-id"}]}""",
    )

    test("malformed, future-version, and invalid-binding snapshots all fail closed and can never earn trust") {
        corruptSnapshots.forEach { (case, raw) ->
            withClue(case) {
                DatabaseFactory.createInMemoryDriver().use { driver ->
                    val db = DatabaseFactory.createDatabase(driver)
                    db.rootTopologyQueries.upsertTopology(
                        root = root,
                        binding = binding.value,
                        status = BindingStatus.UNRESOLVED.name,
                        atRisk = raw,
                    )
                    val repository = SqlDelightRootTopologyRepository(db)
                    val topology = requireNotNull(repository.topology(root))

                    topology.atRisk shouldBe AtRisk.Unreadable
                    BindingLatch(repository).proven(
                        root,
                        ObjectManifest(binding = binding, listed = emptySet(), rowsAtStart = setOf(ref), bindingEpoch = BindingEpoch(0)),
                        witnessed = emptyMap(),
                    ).shouldBeEmpty()
                    requireNotNull(repository.topology(root)).status shouldBe BindingStatus.UNRESOLVED
                }
            }
        }
    }

    test("the ERROR level does not change what observeBinding RETURNS: Unreadable whether the level is off or on") {
        corruptSnapshots.forEach { (case, raw) ->
            withClue(case) {
                DatabaseFactory.createInMemoryDriver().use { driver ->
                    val db = DatabaseFactory.createDatabase(driver)
                    db.rootTopologyQueries.upsertTopology(
                        root = root,
                        binding = binding.value,
                        status = BindingStatus.UNRESOLVED.name,
                        atRisk = raw,
                    )
                    val repository = SqlDelightRootTopologyRepository(db)

                    // OFF and ERROR run against ONE fixture: the unchanged-binding early return writes nothing, so the
                    // second call sees exactly the row the first did.
                    withCapture(Level.OFF) { events ->
                        // Unreadable is also what proves we are on the unchanged-binding path, where the level flag
                        // gates the decode failure's capture: a CHANGE re-snapshots from id_map and never calls decode.
                        repository.observeBinding(root, binding).atRisk shouldBe AtRisk.Unreadable
                        events() shouldBe emptyList()
                    }

                    // The control that makes the zero above mean something. ERROR (not DEBUG) leaves WARN off, so a
                    // fixture that slipped onto the CHANGE path would emit nothing here and fail loudly instead.
                    withCapture(Level.ERROR) { events ->
                        repository.observeBinding(root, binding).atRisk shouldBe AtRisk.Unreadable
                        val captured = events()
                        captured.size shouldBe 1
                        captured.single().level shouldBe Level.ERROR
                        // A prefix, not the whole text: the undecodable case's detail is a kotlinx-serialization
                        // message, and this control's job is to prove the input emits rather than to freeze texts.
                        captured.single().formattedMessage shouldStartWith "root '${root.value}''s at-risk snapshot"
                    }
                }
            }
        }
    }

    test("a manifest from the previous binding cannot promote the currently configured binding") {
        val current = RootBinding("https://objects.example|current|")
        val stale = RootBinding("https://objects.example|previous|")
        val repository = RecordingTopologyRepository(
            RootTopology(root, current, BindingStatus.UNRESOLVED, AtRisk.Bindings(emptySet())),
        )

        BindingLatch(repository).proven(
            root,
            ObjectManifest(binding = stale, listed = emptySet(), rowsAtStart = setOf(ref), bindingEpoch = BindingEpoch(0)),
            witnessed = emptyMap(),
        ).shouldBeEmpty()

        repository.trustCalls shouldBe 0
        requireNotNull(repository.topology(root)).status shouldBe BindingStatus.UNRESOLVED
    }
})

private val captured = ListAppender<ILoggingEvent>()

/**
 * Levels are set PER CASE and restored, never spec-wide: the level is the variable under test here, and without an
 * explicit set the coverage would silently follow the ambient PLAINBASE_LOG_LEVEL.
 */
private fun withCapture(level: Level, body: (() -> List<ILoggingEvent>) -> Unit) {
    val logger = LoggerFactory.getLogger(SqlDelightRootTopologyRepository::class.java) as Logger
    val previousLevel = logger.level
    captured.list.clear()
    captured.start()
    try {
        logger.level = level
        logger.addAppender(captured)
        body { captured.list.toList() }
    } finally {
        logger.detachAppender(captured)
        captured.stop()
        captured.list.clear()
        logger.level = previousLevel
    }
}

private class RecordingTopologyRepository(private var row: RootTopology) : RootTopologyRepository {
    var trustCalls = 0
        private set

    override fun topology(root: RootName): RootTopology? = row.takeIf { it.root == root }

    override fun observeBinding(root: RootName, binding: RootBinding): RootTopology = row

    override fun trust(root: RootName) {
        trustCalls += 1
        row = row.copy(status = BindingStatus.TRUSTED)
    }
}

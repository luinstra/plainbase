package com.plainbase.frameworks.sqldelight

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

class SqlDelightRootTopologyRepositoryTest : FunSpec({

    val root = RootName.MAIN
    val binding = RootBinding("https://objects.example|handbook|")
    val id = PageId.require("01900000-0000-7000-9000-0000000000d1")
    val ref = BindingRef(TreePath.require("guides/deploy.md"), id)

    test("malformed, future-version, and invalid-binding snapshots all fail closed and can never earn trust") {
        val corruptSnapshots = listOf(
            "malformed JSON" to "not-json",
            "future schema" to """{"version":2,"bindings":[]}""",
            "invalid binding" to """{"version":1,"bindings":[{"path":"../escape.md","id":"not-an-id"}]}""",
        )

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

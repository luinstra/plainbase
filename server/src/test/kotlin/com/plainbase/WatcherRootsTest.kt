package com.plainbase

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.UnavailableCause
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.nio.file.Path
import kotlin.time.Clock

class WatcherRootsTest : FunSpec({
    test("only available editable local roots receive discussion watches") {
        val editable = localRoot("docs", editable = true)
        val unavailable = localRoot("offline", editable = true)
        val readOnly = localRoot("reference", editable = false)
        val objectRoot = Root(
            name = RootName.require("archive"),
            backend = RootBackend.Object("bucket", "archive"),
            editable = true,
            history = HistoryMode.OFF,
        )
        val registry = RootRegistry.of(listOf(editable, unavailable, readOnly, objectRoot))
        val availability = RootAvailability(Clock.System).also {
            it.markUnavailable(unavailable.name, UnavailableCause.VANISHED)
        }

        watcherRoots(registry, availability) shouldContainExactly listOf(editable)
    }
    test("disabled editable roots have no discussion watcher while content roots stay registered") {
        val enabled = localRoot("docs", true)
        val disabled = localRoot("extra", true).copy(discussionsEnabled = false)
        val registry = RootRegistry.of(listOf(enabled, disabled))
        watcherRoots(registry, RootAvailability(Clock.System)) shouldContainExactly listOf(enabled)
        registry.roots shouldContainExactly listOf(enabled, disabled)
        watcherRoots(RootRegistry.of(listOf(enabled.copy(discussionsEnabled = false))), RootAvailability(Clock.System)) shouldContainExactly
            emptyList()
    }
})

private fun localRoot(name: String, editable: Boolean): Root = Root(
    name = RootName.require(name),
    backend = RootBackend.Local(Path.of("/roots", name)),
    editable = editable,
    history = HistoryMode.OFF,
)

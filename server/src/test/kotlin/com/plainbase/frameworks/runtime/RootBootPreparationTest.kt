package com.plainbase.frameworks.runtime

import com.plainbase.RootGateVerdict
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.rootGateVerdicts
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class RootBootPreparationTest : FunSpec({

    test("availability is checked before history and all ranked roots are evaluated") {
        val extra = RootName.require("extra")
        val extraDir = Files.createTempDirectory("plainbase-probe-extra")
        val primaryDir = Files.createTempDirectory("plainbase-probe-primary")
        try {
            val registry = RootRegistry.of(
                listOf(
                    Root(extra, RootBackend.Local(extraDir), editable = true, history = HistoryMode.AUTO),
                    Root(RootName.PRIMARY, RootBackend.Local(primaryDir), editable = true, history = HistoryMode.AUTO),
                ),
            )
            val calls = mutableListOf<String>()
            val failure = IllegalStateException("git gate failed")
            val probes = mapOf(
                extra to recordingProbe(available = false, calls, "extra"),
                RootName.PRIMARY to recordingProbe(available = true, calls, "docs") { throw failure },
            )

            val verdicts = rootGateVerdicts(registry, probes)

            verdicts.map { it.root } shouldContainExactly listOf(extra, RootName.PRIMARY)
            verdicts[0] shouldBe RootGateVerdict.Unavailable(extra, registry.byName(extra)?.localPath)
            verdicts[1] shouldBe RootGateVerdict.Refused(RootName.PRIMARY, "git gate failed")
            calls shouldContainExactly listOf("extra:available", "docs:available")
        } finally {
            extraDir.toFile().deleteRecursively()
            primaryDir.toFile().deleteRecursively()
        }
    }

    test("a refused earlier root does not prevent evaluation of a later root") {
        val extra = RootName.require("extra")
        val registry = RootRegistry.of(
            listOf(
                Root(RootName.PRIMARY, RootBackend.Local(Path.of("/roots/docs")), editable = true, history = HistoryMode.AUTO),
                Root(extra, RootBackend.Local(Path.of("/roots/extra")), editable = true, history = HistoryMode.AUTO),
            ),
        )
        val calls = mutableListOf<String>()
        val probes = mapOf(
            RootName.PRIMARY to recordingProbe(available = true, calls, "docs") {
                calls += "docs:refused"
                throw IllegalStateException("docs refused")
            },
            extra to recordingProbe(available = true, calls, "extra") {
                calls += "extra:gate"
            },
        )

        val verdicts = rootGateVerdicts(registry, probes)

        verdicts shouldBe listOf(
            RootGateVerdict.Refused(RootName.PRIMARY, "docs refused"),
            RootGateVerdict.Ready(extra),
        )
        calls shouldContainExactly listOf(
            "docs:available",
            "docs:refused",
            "extra:available",
            "extra:gate",
        )
    }

    test("a root gate Error escapes with the exact instance") {
        val failure = AssertionError("root gate error sentinel")
        val registry = RootRegistry.of(
            listOf(
                Root(
                    RootName.PRIMARY,
                    RootBackend.Local(Path.of("/roots/docs")),
                    editable = true,
                    history = HistoryMode.AUTO,
                ),
            ),
        )
        val probes = mapOf(
            RootName.PRIMARY to recordingProbe(available = true, mutableListOf(), "docs") { throw failure },
        )

        shouldThrow<AssertionError> { rootGateVerdicts(registry, probes) } shouldBeSameInstanceAs failure
    }

    test("deferred object history exposes one immutable callback pair") {
        val deferred = DeferredObjectHistory()
        val path = TreePath.require("café.md")
        shouldThrow<ObjectHistoryNotReady> { deferred.requireReady() }.message shouldBe
            "object history callbacks are not armed"

        var commits = 0
        val callbacks = ObjectHistoryCallbacks(
            repoPath = { "raw/${it.value}" },
            onCommit = { commits += 1 },
        )
        deferred.arm(callbacks)

        deferred.requireReady() shouldBeSameInstanceAs callbacks
        deferred.repoPath(path) shouldBe "raw/café.md"
        deferred.onCommit()
        commits shouldBe 1
        val replacement = shouldThrow<IllegalStateException> {
            deferred.arm(ObjectHistoryCallbacks({ "replacement" }, {}))
        }
        replacement.message shouldBe "object history callbacks already armed"
    }

    test("deferred path and ship callbacks preserve their original Throwable identities") {
        val deferred = DeferredObjectHistory()
        val pathFailure = IllegalStateException("path sentinel")
        val shipFailure = IllegalArgumentException("ship sentinel")
        deferred.arm(
            ObjectHistoryCallbacks(
                repoPath = { throw pathFailure },
                onCommit = { throw shipFailure },
            ),
        )

        shouldThrow<IllegalStateException> { deferred.repoPath(TreePath.require("café.md")) } shouldBeSameInstanceAs pathFailure
        shouldThrow<IllegalArgumentException> { deferred.onCommit() } shouldBeSameInstanceAs shipFailure
    }

    test("preparation constructs and returns the same LOCAL stores and callbacks") {
        val primaryDir = Files.createTempDirectory("plainbase-prepared-primary")
        val dataDir = Files.createTempDirectory("plainbase-prepared-data")
        try {
            val config = ConfigLoader.fromEnv(
                mapOf(
                    "CONTENT_DIR" to primaryDir.toString(),
                    "DATA_DIR" to dataDir.toString(),
                ),
            )
            val captured = mutableMapOf<RootName, LocalContentStore>()
            val capturedInputs = mutableMapOf<RootName, LocalStoreInputs>()
            val inputs = prepareRootBootInputs(config, openLocal = { storeInputs ->
                val store = RootStoreFactory.local(storeInputs)
                captured[storeInputs.rootName] = store
                capturedInputs[storeInputs.rootName] = storeInputs
                store
            })

            inputs.localStores.keys shouldBe setOf(RootName.PRIMARY)
            inputs.localStores[RootName.PRIMARY] shouldBeSameInstanceAs captured.getValue(RootName.PRIMARY)
            capturedInputs.getValue(RootName.PRIMARY).ignoreRules shouldBeSameInstanceAs inputs.ignoreRules
            capturedInputs.getValue(RootName.PRIMARY).exclusions shouldBe listOf(dataDir)
            inputs.probes.getValue(RootName.PRIMARY).available() shouldBe true
            inputs.signals.broke(RootName.PRIMARY, BreakCause.IDENTITY_REBIND)
            inputs.signals.arm { _, _ -> Unit }
        } finally {
            primaryDir.toFile().deleteRecursively()
            dataDir.toFile().deleteRecursively()
        }
    }

    test("OBJECT preparation does not construct a local primary") {
        val dataDir = Files.createTempDirectory("plainbase-prepared-object")
        val before = contentDirStoreConstructions.get()
        try {
            val config = ConfigLoader.fromEnv(
                mapOf(
                    "DATA_DIR" to dataDir.toString(),
                    "PLAINBASE_STORAGE_BACKEND" to "object",
                    "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                    "PLAINBASE_S3_BUCKET" to "docs",
                    "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
                    "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
                ),
            )
            val inputs = prepareRootBootInputs(config, ServerOpeners().openLocal)
            inputs.localStores shouldBe emptyMap()
            inputs.probes.getValue(RootName.PRIMARY).available() shouldBe true
            contentDirStoreConstructions.get() shouldBe before
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    test("OBJECT gate evaluation does not call deferred history callbacks") {
        val dataDir = Files.createTempDirectory("plainbase-object-gate-deferred")
        try {
            val config = ConfigLoader.fromEnv(
                mapOf(
                    "DATA_DIR" to dataDir.toString(),
                    "PLAINBASE_STORAGE_BACKEND" to "object",
                    "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                    "PLAINBASE_S3_BUCKET" to "docs",
                    "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
                    "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
                    "PLAINBASE_GIT_ENABLED" to "true",
                ),
            )
            val unarmedInputs = prepareRootBootInputs(config, ServerOpeners().openLocal)
            rootGateVerdicts(unarmedInputs.registry, unarmedInputs.probes) shouldBe listOf(
                RootGateVerdict.Ready(RootName.PRIMARY),
            )

            val inputs = prepareRootBootInputs(config, ServerOpeners().openLocal)
            val pathCalls = AtomicInteger()
            val shipCalls = AtomicInteger()
            inputs.history.objectHistory.arm(
                ObjectHistoryCallbacks(
                    repoPath = {
                        pathCalls.incrementAndGet()
                        "unused"
                    },
                    onCommit = { shipCalls.incrementAndGet() },
                ),
            )

            rootGateVerdicts(inputs.registry, inputs.probes) shouldBe listOf(
                RootGateVerdict.Ready(RootName.PRIMARY),
            )
            pathCalls.get() shouldBe 0
            shipCalls.get() shouldBe 0
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    test("OBJECT boot probe matches the real store before mirror hydration") {
        listOf(false, true).forEach { mirrorPresent ->
            val dataDir = Files.createTempDirectory("plainbase-object-probe-$mirrorPresent")
            try {
                val config = ConfigLoader.fromEnv(
                    mapOf(
                        "DATA_DIR" to dataDir.toString(),
                        "PLAINBASE_STORAGE_BACKEND" to "object",
                        "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                        "PLAINBASE_S3_BUCKET" to "docs",
                        "PLAINBASE_S3_ACCESS_KEY_ID" to "key",
                        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
                    ),
                )
                val inputs = prepareRootBootInputs(config, ServerOpeners().openLocal)
                val mirror = dataDir.resolve("mirror")
                if (mirrorPresent) {
                    Files.createDirectories(mirror)
                    Files.writeString(mirror.resolve("already-there.md"), "not hydrated")
                }
                val store: ObjectContentStore = ServerOpeners().openObject(
                    config,
                    inputs.ignoreRules,
                    { emptySet() },
                    { false },
                    { com.plainbase.domain.root.RowsAtStart(emptySet(), BindingEpoch(0)) },
                )
                try {
                    inputs.probes.getValue(RootName.PRIMARY).available() shouldBe store.available()
                    inputs.probes.getValue(RootName.PRIMARY).available() shouldBe true
                } finally {
                    store.close()
                }
            } finally {
                dataDir.toFile().deleteRecursively()
            }
        }
    }
})

private fun recordingProbe(
    available: Boolean,
    calls: MutableList<String>,
    name: String,
    gateCheckAction: () -> Unit = { calls += "$name:gate" },
): RootBootProbe = object : RootBootProbe {
    override fun available(): Boolean {
        calls += "$name:available"
        return available
    }

    override fun gateCheck() = gateCheckAction()
}

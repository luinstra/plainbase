package com.plainbase.frameworks.config

import com.plainbase.TOPOLOGY_REFUSAL_KINDS
import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.RootName
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files

class ReaderBootRefusalTest : FunSpec({
    fun withConfig(
        objectBacked: Boolean = true,
        conf: String = "",
        overrides: Map<String, String> = emptyMap(),
        reader: Boolean = true,
        managedConf: String? = null,
        block: (PlainbaseConfig) -> Unit,
    ) {
        val base = Files.createTempDirectory("pb-reader-refusals")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            val text = (if (reader) "profile = reader\n" else "") + conf.replace("CONTENT", content.toString())
            Files.writeString(data.resolve("plainbase.conf"), text)
            managedConf?.let { Files.writeString(data.resolve("roots.conf"), it.replace("CONTENT", content.toString())) }
            val objectEnv = if (objectBacked) {
                mapOf(
                    "PLAINBASE_STORAGE_BACKEND" to "object",
                    "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
                    "PLAINBASE_S3_BUCKET" to "docs",
                    "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
                    "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
                    "PLAINBASE_S3_PREFIX" to "releases/r1",
                )
            } else {
                emptyMap()
            }
            val config = ConfigLoader.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString()) + objectEnv + overrides,
            )
            config.profile shouldBe if (reader) DeploymentProfile.READER else DeploymentProfile.WORKSPACE
            block(config)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    fun onlyReaderRefusal(config: PlainbaseConfig): BootRefusal {
        val refusals = ConfigBootInspector.bootRefusals(config)
        refusals.map { it.kind } shouldBe listOf(BootRefusal.Kind.READER_PROFILE)
        refusals.single().roots shouldBe emptySet()
        return refusals.single()
    }

    context("reader data directory freshness") {
        fun onlyDataDirRefusal(config: PlainbaseConfig): BootRefusal {
            val refusals = ConfigBootInspector.readerDataDirRefusals(config)
            refusals.map { it.kind } shouldBe listOf(BootRefusal.Kind.READER_DATA_DIR)
            refusals.single().roots shouldBe emptySet()
            return refusals.single()
        }

        test("should accept the operator file in a fresh data directory") {
            withConfig { config -> ConfigBootInspector.readerDataDirRefusals(config).shouldBeEmpty() }
        }

        listOf(
            "plainbase.db" to false, "search.db-wal" to false, "git-home" to true, "roots.conf" to false,
            "roots.lock" to false, "mirror" to true, "themes" to true,
            "lost+found" to true, ".DS_Store" to false,
        ).forEach { (name, directory) ->
            test("should refuse $name in an object reader data directory") {
                withConfig { config ->
                    val path = config.dataDir.resolve(name)
                    if (directory) Files.createDirectory(path) else Files.writeString(path, if (name == "roots.conf") "roots {}" else "")
                    val message = onlyDataDirRefusal(config).message
                    message shouldContain name
                    message shouldContain "stop it first"
                    message shouldContain "safe to remove"
                    if (name == "roots.conf") message shouldContain "synthesized"
                }
            }
        }

        test("should allow a process lock file beside the operator file") {
            withConfig { config ->
                Files.writeString(config.dataDir.resolve("plainbase.lock"), "")
                ConfigBootInspector.readerDataDirRefusals(config).shouldBeEmpty()
            }
        }

        test("should refuse retained state even under the held lock") {
            withConfig { config ->
                Files.writeString(config.dataDir.resolve("plainbase.lock"), "")
                Files.writeString(config.dataDir.resolve("plainbase.db"), "")
                onlyDataDirRefusal(config).message shouldContain "plainbase.db"
            }
        }

        test("should turn a mid-iteration listing failure into a freshness refusal") {
            withConfig { config ->
                val refusals = ConfigBootInspector.readerDataDirRefusals(config, readNames = {
                    throw DirectoryIteratorException(IOException("boom"))
                })
                refusals.map { it.kind } shouldBe listOf(BootRefusal.Kind.READER_DATA_DIR)
                refusals.single().message shouldContain "cannot list DATA_DIR to prove it is fresh"
                refusals.single().message shouldContain "boom"
            }
        }

        test("should sort and cap the reported stray names") {
            withConfig { config ->
                (12 downTo 1).forEach { number ->
                    Files.writeString(config.dataDir.resolve("stray-${number.toString().padStart(2, '0')}"), "")
                }
                val refusals = ConfigBootInspector.readerDataDirRefusals(config, readNames = { stream ->
                    stream.map { it.fileName.toString() }.sortedDescending()
                })
                refusals.map { it.kind } shouldBe listOf(BootRefusal.Kind.READER_DATA_DIR)
                val message = refusals.single().message
                message shouldContain "stray-01, stray-02, stray-03, stray-04, stray-05, stray-06, stray-07, stray-08, stray-09, stray-10"
                message shouldContain "+2 more"
                message shouldNotContain "stray-11"
                message shouldNotContain "stray-12"
            }
        }

        test("should leave workspace object data directory contents outside freshness inspection") {
            withConfig(reader = false) { config ->
                Files.writeString(config.dataDir.resolve("plainbase.db"), "")
                ConfigBootInspector.readerDataDirRefusals(config).shouldBeEmpty()
            }
        }

        test("should leave local reader data directory contents outside freshness inspection") {
            withConfig(objectBacked = false) { config ->
                Files.writeString(config.dataDir.resolve("plainbase.db"), "")
                ConfigBootInspector.readerDataDirRefusals(config).shouldBeEmpty()
            }
        }

        test("should keep freshness inspection separate from the shared boot gate") {
            withConfig { config ->
                Files.writeString(config.dataDir.resolve("plainbase.db"), "")
                ConfigBootInspector.bootRefusals(config).filter { it.kind == BootRefusal.Kind.READER_DATA_DIR }.shouldBeEmpty()
                onlyDataDirRefusal(config).message shouldContain "plainbase.db"
            }
        }

        test("should load empty managed roots under object workspace before freshness inspection") {
            withConfig(reader = false, managedConf = "roots {}") { config ->
                config.storage.backend shouldBe StorageBackend.OBJECT
                config.roots.origin shouldBe RootsOrigin.SYNTHESIZED
            }
        }

        test("should refuse managed root declarations at object configuration load") {
            shouldThrow<IllegalArgumentException> {
                withConfig(managedConf = "roots { notes { path = \"CONTENT\" } }") { }
            }.message shouldContain "roots {} cannot be combined with storage.backend=object"
        }
    }

    listOf(false, true).forEach { objectBacked ->
        test("should accept a clean reader configuration with object storage $objectBacked") {
            withConfig(objectBacked = objectBacked) { config ->
                ConfigBootInspector.bootRefusals(config).shouldBeEmpty()
            }
        }
    }

    val globalCases = listOf(
        ReaderRefusalCase(
            "git enablement",
            overrides = mapOf("PLAINBASE_GIT_ENABLED" to "1"),
            key = "PLAINBASE_GIT_ENABLED / git.enabled",
        ),
        ReaderRefusalCase("builtin authentication", conf = "auth { mode = builtin }", key = "PLAINBASE_AUTH_MODE / auth.mode"),
        ReaderRefusalCase("proxy authentication", conf = "auth { mode = proxy }", key = "PLAINBASE_AUTH_MODE / auth.mode"),
        ReaderRefusalCase(
            "environment insecure HTTP",
            overrides = mapOf("PLAINBASE_INSECURE_HTTP" to "1"),
            key = "PLAINBASE_INSECURE_HTTP / auth.insecureHttp",
        ),
        ReaderRefusalCase(
            "file insecure HTTP",
            conf = "auth { insecureHttp = true }",
            key = "PLAINBASE_INSECURE_HTTP / auth.insecureHttp",
        ),
        ReaderRefusalCase(
            "trusted proxy CIDRs",
            overrides = mapOf("PLAINBASE_TRUSTED_PROXY" to "10.0.0.0/8"),
            key = "PLAINBASE_TRUSTED_PROXY / auth.trustedProxy",
        ),
        ReaderRefusalCase(
            "proxy secret",
            overrides = mapOf("PLAINBASE_PROXY_SECRET" to "s"),
            key = "PLAINBASE_PROXY_SECRET / auth.proxySecret",
        ),
    )
    globalCases.forEach { row ->
        test("should refuse reader ${row.name} naming the environment and file keys") {
            withConfig(conf = row.conf, overrides = row.overrides) { config ->
                if (row.name == "proxy authentication") {
                    val refusals = ConfigBootInspector.bootRefusals(config)
                    refusals.firstOrNull { it.kind in TOPOLOGY_REFUSAL_KINDS }?.kind shouldBe BootRefusal.Kind.READER_PROFILE
                    refusals.map { it.kind } shouldBe listOf(BootRefusal.Kind.READER_PROFILE, BootRefusal.Kind.BIND_GUARD)
                    refusals.first().roots shouldBe emptySet()
                    refusals.first().message shouldContain row.key
                } else {
                    onlyReaderRefusal(config).message shouldContain row.key
                }
            }
        }

        test("should leave workspace ${row.name} outside reader refusals") {
            withConfig(conf = row.conf, overrides = row.overrides, reader = false) { config ->
                ConfigBootInspector.bootRefusals(config).filter { it.kind == BootRefusal.Kind.READER_PROFILE }.shouldBeEmpty()
            }
        }
    }

    test("should refuse local reader git enablement through boot inspection") {
        withConfig(objectBacked = false, overrides = mapOf("PLAINBASE_GIT_ENABLED" to "1")) { config ->
            config.git.enabled shouldBe true
            config.roots.primary.history shouldBe HistoryMode.OFF
            onlyReaderRefusal(config).message shouldContain "PLAINBASE_GIT_ENABLED / git.enabled"
        }
    }

    test("should accept a local reader with explicit default root capabilities") {
        withConfig(objectBacked = false, conf = "roots { docs { path = \"CONTENT\" } }") { config ->
            ConfigBootInspector.bootRefusals(config).shouldBeEmpty()
        }
    }

    listOf("editable = true", "discussionsEnabled = true", "history = auto", "history = native").forEach { field ->
        test("should refuse an explicit reader root with $field keyed by that root") {
            withConfig(objectBacked = false, conf = "roots { docs { path = \"CONTENT\", $field } }") { config ->
                when (field) {
                    "editable = true" -> config.roots.primary.editable shouldBe true
                    "discussionsEnabled = true" -> config.roots.primary.discussionsEnabled shouldBe true
                    "history = auto" -> config.roots.primary.history shouldBe HistoryMode.AUTO
                    "history = native" -> config.roots.primary.history shouldBe HistoryMode.NATIVE
                }
                val refusals = ConfigBootInspector.bootRefusals(config)
                refusals.map { it.kind } shouldBe listOf(BootRefusal.Kind.READER_PROFILE)
                refusals.single().roots shouldBe setOf(RootName.PRIMARY)
                refusals.single().message shouldContain field.substringBefore(" =")
            }
        }
    }

    test("should name every contradicting capability in one refusal for a reader root") {
        withConfig(
            objectBacked = false,
            conf = "roots { docs { path = \"CONTENT\", editable = true, discussionsEnabled = true, history = native } }",
        ) { config ->
            val refusals = ConfigBootInspector.bootRefusals(config)
            refusals.map { it.kind } shouldBe listOf(BootRefusal.Kind.READER_PROFILE)
            refusals.single().roots shouldBe setOf(RootName.PRIMARY)
            listOf("editable", "discussionsEnabled", "history").forEach { field -> refusals.single().message shouldContain field }
        }
    }

    test("should refuse an empty object reader prefix without a file declaration") {
        withConfig(overrides = mapOf("PLAINBASE_S3_PREFIX" to "")) { config ->
            config.storage.prefix shouldBe ""
            config.storage.prefixDeclaredInFile shouldBe false
            onlyReaderRefusal(config).message shouldContain "PLAINBASE_S3_PREFIX"
        }
    }

    test("should refuse a file-declared reader prefix even when a valid environment prefix wins") {
        withConfig(conf = "storage.object.prefix = \"releases/r1\"") { config ->
            config.storage.prefix shouldBe "releases/r1"
            config.storage.prefixDeclaredInFile shouldBe true
            val message = onlyReaderRefusal(config).message
            message shouldContain "remove storage.object.prefix from plainbase.conf"
            message shouldContain "set PLAINBASE_S3_PREFIX"
        }
    }

    test("should collect reader contradictions in stable order before the bind refusal") {
        withConfig(
            conf = "auth { mode = proxy }\nstorage.object.prefix = \"releases/r1\"",
            overrides = mapOf("PLAINBASE_GIT_ENABLED" to "1", "PLAINBASE_INSECURE_HTTP" to "1", "PLAINBASE_S3_PREFIX" to ""),
        ) { config ->
            val refusals = ConfigBootInspector.bootRefusals(config)
            refusals.map { it.kind } shouldBe List(5) { BootRefusal.Kind.READER_PROFILE } + BootRefusal.Kind.BIND_GUARD
            val keys = listOf(
                "PLAINBASE_GIT_ENABLED / git.enabled", "PLAINBASE_AUTH_MODE / auth.mode",
                "PLAINBASE_INSECURE_HTTP / auth.insecureHttp", "storage.object.prefix", "set PLAINBASE_S3_PREFIX",
            )
            keys.forEachIndexed { index, key -> refusals[index].message shouldContain key }
        }
    }
})

private data class ReaderRefusalCase(
    val name: String,
    val conf: String = "",
    val overrides: Map<String, String> = emptyMap(),
    val key: String,
)

package com.plainbase

import com.plainbase.frameworks.cli.CommandOutputFixture
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.lifecycle.ServerRunControl
import com.plainbase.frameworks.objectstore.FakeObjectStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import com.plainbase.frameworks.runtime.ServerOpeners
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ReaderServeGateTest : FunSpec({
    fun withReader(block: (ReaderServeWorld) -> Unit) {
        val base = Files.createTempDirectory("pb-reader-serve")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            Files.writeString(data.resolve("plainbase.conf"), "profile = reader")
            val closedPort = ServerSocket(0).use { it.localPort }
            val env = mapOf(
                "DATA_DIR" to data.toString(),
                "CONTENT_DIR" to content.toString(),
                "PLAINBASE_STORAGE_BACKEND" to "object",
                "PLAINBASE_S3_ENDPOINT" to "https://127.0.0.1:$closedPort",
                "PLAINBASE_S3_BUCKET" to "docs",
                "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
                "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
                "PLAINBASE_S3_PREFIX" to "releases/r1",
                "PLAINBASE_PROFILE" to "reader",
                "PLAINBASE_GIT_ENABLED" to "false",
                "PLAINBASE_HOST" to "127.0.0.1",
            )
            block(ReaderServeWorld(data, env))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    test("reader retained state refuses under the data directory lock before any opener") {
        withReader { world ->
            Files.writeString(world.data.resolve("plainbase.db"), "")
            val status = runServer(world.config(), world.output.output, world.openers)
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "serve: "
                world.errors().single() shouldContain "plainbase.db"
                world.driverOpens.get() shouldBe 0
                world.objectOpens.get() shouldBe 0
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe true
            }
        }
    }

    test("reader git enablement refuses before any opener") {
        withReader { world ->
            val config = ConfigLoader.fromEnvAndFile(world.env + ("PLAINBASE_GIT_ENABLED" to "1"))
            val status = runServer(config, world.output.output, world.openers)
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "PLAINBASE_GIT_ENABLED / git.enabled"
                world.driverOpens.get() shouldBe 0
                world.objectOpens.get() shouldBe 0
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe false
            }
        }
    }

    test("reader checks retained state introduced before the lock before opening the driver") {
        withReader { world ->
            val status = runServer(
                world.config(),
                world.output.output,
                world.openers,
                ServerRunControl(onBootAvailability = { Files.writeString(world.data.resolve("plainbase.db"), "") }),
            )
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "plainbase.db"
                world.driverOpens.get() shouldBe 0
                world.objectOpens.get() shouldBe 0
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe true
            }
        }
    }

    test("reader accepts a preexisting process lock file and reaches object hydration") {
        withReader { world ->
            Files.writeString(world.data.resolve("plainbase.lock"), "")
            val status = runServer(world.config(), world.output.output, world.openers)
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "object storage endpoint is unreachable"
                world.driverOpens.get() shouldBe 1
                world.objectOpens.get() shouldBe 1
            }
        }
    }

    test("environment reader boots without an operator file through the command loader and freshness gate") {
        withReader { world ->
            Files.delete(world.data.resolve("plainbase.conf"))
            world.useFakeObjectStore = true
            var starts = 0
            val status = ConfigLoader.loadForCommand("serve", world.output.output::error) {
                ConfigLoader.fromEnvAndFile(world.env)
            }?.let { config ->
                config.profile shouldBe DeploymentProfile.READER
                config.roots.primary.editable shouldBe false
                config.roots.primary.discussionsEnabled shouldBe false
                runServer(config, world.output.output, world.openers, ServerRunControl(startServer = { starts++ }))
            } ?: 1
            assertSoftly {
                status shouldBe 0
                starts shouldBe 1
                world.errors() shouldBe emptyList()
                world.driverOpens.get() shouldBe 1
                world.objectOpens.get() shouldBe 1
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe true
                world.bucket.listCount shouldBe 1
                world.bucket.putCount shouldBe 0
            }
        }
    }

    test("fresh reader accepts its own lock and reaches object hydration") {
        withReader { world ->
            val status = runServer(world.config(), world.output.output, world.openers)
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "object storage endpoint is unreachable"
                world.driverOpens.get() shouldBe 1
                world.objectOpens.get() shouldBe 1
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe true
            }
        }
    }
})

private class ReaderServeWorld(val data: Path, val env: Map<String, String>) {
    val output = CommandOutputFixture()
    val driverOpens = AtomicInteger()
    val objectOpens = AtomicInteger()
    val bucket = FakeObjectStore()
    var useFakeObjectStore = false
    private val defaults = ServerOpeners()
    val openers = ServerOpeners(
        openDriver = { path ->
            driverOpens.incrementAndGet()
            defaults.openDriver(path)
        },
        openObject = { config, ignore, dirty, isDirty, rows ->
            objectOpens.incrementAndGet()
            if (useFakeObjectStore) {
                ObjectContentStoreFactory.buildWithClient(config, ignore, dirty, isDirty, rows, clientFactory = { bucket })
            } else {
                defaults.openObject(config, ignore, dirty, isDirty, rows)
            }
        },
    )

    fun config(): PlainbaseConfig = ConfigLoader.fromEnvAndFile(env)

    fun errors(): List<String> = output.stderr.lineSequence().filter { it.isNotBlank() }.toList()
}

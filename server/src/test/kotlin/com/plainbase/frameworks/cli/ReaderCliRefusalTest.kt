package com.plainbase.frameworks.cli

import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.runtime.OfflineStoreOperations
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class ReaderCliRefusalTest : FunSpec({
    fun withData(conf: String? = "profile = reader", block: (ReaderCliWorld) -> Unit) {
        val base = Files.createTempDirectory("pb-reader-cli")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            if (conf != null) Files.writeString(data.resolve("plainbase.conf"), conf)
            block(ReaderCliWorld(data, content))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    val modes = listOf(emptyList(), listOf("--write-ids"), listOf("--write-ids", "--dry-run"))
    modes.filterNot { "--dry-run" in it }.forEach { args ->
        test("reader adopt $args should refuse before any opener or state file") {
            withData { world ->
                val config = ConfigLoader.fromEnvAndFile(world.env)
                val result = runCatching { AdoptCommand.run(args, config, { _, store -> store }, world.output.output, world.operations) }
                assertSoftly {
                    result.getOrNull() shouldBe 1
                    world.driverCalls shouldBe 0
                    world.readOnlyCalls shouldBe 0
                    world.objectCalls shouldBe 0
                    world.errors().singleOrNull().orEmpty() shouldContain "adopt: "
                    world.errors().singleOrNull().orEmpty() shouldContain "profile = reader"
                    Files.exists(world.data.resolve("plainbase.lock")) shouldBe false
                    Files.exists(world.data.resolve("plainbase.db")) shouldBe false
                }
            }
        }
    }

    test("reader adopt --write-ids --dry-run should reach only the read-only driver opener without state files") {
        withData { world ->
            val config = ConfigLoader.fromEnvAndFile(world.env)
            val result = runCatching {
                AdoptCommand.run(listOf("--write-ids", "--dry-run"), config, { _, store -> store }, world.output.output, world.operations)
            }
            assertSoftly {
                result.exceptionOrNull()?.message shouldBe "driver sentinel"
                world.driverCalls shouldBe 0
                world.readOnlyCalls shouldBe 1
                world.objectCalls shouldBe 0
                world.errors() shouldBe emptyList()
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe false
                Files.exists(world.data.resolve("plainbase.db")) shouldBe false
            }
        }
    }

    listOf(false, true).forEach { objectBacked ->
        test("reader adoption preview with object storage $objectBacked should finish without writing files") {
            withData { world ->
                val page = world.content.resolve("page.md")
                Files.writeString(page, "# Page\n")
                val pageBefore = Files.readAllBytes(page)
                val inputs = world.env + ("PLAINBASE_STORAGE_BACKEND" to if (objectBacked) "object" else "local")
                val config = ConfigLoader.fromEnvAndFile(inputs)
                val before = Files.list(world.data).use { it.map { entry -> entry.fileName.toString() }.toList() }
                AdoptCommand.run(listOf("--write-ids", "--dry-run"), config, world.output.output) shouldBe 0
                world.output.stdout shouldContain "dry run: nothing was written"
                world.errors() shouldBe emptyList()
                Files.list(world.data).use { it.map { entry -> entry.fileName.toString() }.toList() } shouldBe before
                Files.readAllBytes(page) shouldBe pageBefore
            }
        }
    }

    modes.forEach { args ->
        test("workspace adopt $args should reach the matching driver opener") {
            withData("profile = workspace") { world ->
                val config = ConfigLoader.fromEnvAndFile(world.env)
                val result = runCatching { AdoptCommand.run(args, config, { _, store -> store }, world.output.output, world.operations) }
                result.exceptionOrNull()?.message shouldBe "driver sentinel"
                world.driverCalls shouldBe if ("--dry-run" in args) 0 else 1
                world.readOnlyCalls shouldBe if ("--dry-run" in args) 1 else 0
                world.objectCalls shouldBe 0
            }
        }
    }

    test("reader adopt should keep usage errors ahead of the profile refusal") {
        withData { world ->
            AdoptCommand.run(
                listOf("--bogus"), ConfigLoader.fromEnvAndFile(world.env), { _, store -> store }, world.output.output, world.operations,
            ) shouldBe 2
            world.output.stderr shouldContain "usage:"
            world.driverCalls + world.readOnlyCalls + world.objectCalls shouldBe 0
        }
    }
})

private class ReaderCliWorld(val data: Path, val content: Path) {
    val output = CommandOutputFixture()
    val env = mapOf(
        "DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString(), "PLAINBASE_STORAGE_BACKEND" to "object",
        "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com", "PLAINBASE_S3_BUCKET" to "docs",
        "PLAINBASE_S3_ACCESS_KEY_ID" to "key", "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
        "PLAINBASE_S3_PREFIX" to "releases/r1", "PLAINBASE_GIT_ENABLED" to "false",
    )
    var driverCalls = 0
    var readOnlyCalls = 0
    var objectCalls = 0
    val operations = OfflineStoreOperations(
        openDriver = {
            driverCalls++
            error("driver sentinel")
        },
        openReadOnlyDriver = {
            readOnlyCalls++
            error("driver sentinel")
        },
        openObject = { _, _, _, _, _ ->
            objectCalls++
            error("object sentinel")
        },
    )

    fun errors(): List<String> = output.stderr.lineSequence().filter { it.isNotBlank() }.toList()
}

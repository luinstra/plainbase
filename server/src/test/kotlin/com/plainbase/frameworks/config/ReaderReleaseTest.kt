package com.plainbase.frameworks.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class ReaderReleaseTest : FunSpec({
    fun withDataDir(block: (Map<String, String>) -> Unit) {
        val data = Files.createTempDirectory("pb-reader-release")
        try {
            block(mapOf("DATA_DIR" to data.toString(), "PLAINBASE_PROFILE" to "reader"))
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    fun objectEnv(env: Map<String, String>, prefix: String): Map<String, String> = env + mapOf(
        "PLAINBASE_STORAGE_BACKEND" to "object",
        "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
        "PLAINBASE_S3_BUCKET" to "docs",
        "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
        "PLAINBASE_S3_PREFIX" to prefix,
    )

    listOf("releases/r1" to "r1", "r1" to "r1", "a/b/c" to "c").forEach { (prefix, expected) ->
        test("reader release is the final segment of $prefix") {
            withDataDir { env ->
                val config = ConfigLoader.fromEnvAndFile(objectEnv(env, prefix))
                config.storage.prefix shouldBe prefix
                readerRelease(config.storage) shouldBe expected
            }
        }
    }

    test("empty object prefix has no reader release") {
        withDataDir { env ->
            val config = ConfigLoader.fromEnvAndFile(objectEnv(env, ""))
            config.storage.backend shouldBe StorageBackend.OBJECT
            config.storage.prefix shouldBe ""
            readerRelease(config.storage) shouldBe null
        }
    }

    test("local storage has no reader release") {
        withDataDir { env ->
            val config = ConfigLoader.fromEnvAndFile(env)
            config.storage.backend shouldBe StorageBackend.LOCAL
            config.storage.prefix shouldBe ""
            readerRelease(config.storage) shouldBe null
        }
    }

    listOf("releases/r1/", "/releases/r1").forEach { prefix ->
        test("reader prefix $prefix is refused through the command loader") {
            withDataDir { env ->
                val errors = mutableListOf<String>()
                ConfigLoader.loadForCommand("serve", errors::add) {
                    ConfigLoader.fromEnvAndFile(objectEnv(env, prefix))
                } shouldBe null
                errors.size shouldBe 1
                errors.single() shouldContain "storage.object.prefix"
            }
        }
    }
})

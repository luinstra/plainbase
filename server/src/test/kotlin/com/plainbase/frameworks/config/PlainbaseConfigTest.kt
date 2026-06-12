package com.plainbase.frameworks.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The CONTENT_DIR startup guard: serve must fail fast with an operator-actionable message that
 * NAMES the offending path — never the scan's bare `NoSuchFileException`, never a silently empty
 * tree.
 */
class PlainbaseConfigTest : FunSpec({

    fun configWith(contentDir: Path) = PlainbaseConfig(
        contentDir = contentDir,
        dataDir = contentDir.resolveSibling("data"),
        host = "127.0.0.1",
        port = PlainbaseConfig.DEFAULT_PORT,
    )

    test("a missing CONTENT_DIR fails fast with a message naming the path") {
        val parent = Files.createTempDirectory("pb-config")
        try {
            val missing = parent.resolve("does-not-exist")
            val failure = shouldThrow<IllegalArgumentException> { configWith(missing).requireContentDir() }
            failure.message shouldContain "CONTENT_DIR does not exist or is not a directory"
            failure.message shouldContain missing.toString()
        } finally {
            Files.deleteIfExists(parent)
        }
    }

    test("a CONTENT_DIR that is a regular file fails fast with the same actionable message") {
        val file = Files.createTempFile("pb-config", ".txt")
        try {
            val failure = shouldThrow<IllegalArgumentException> { configWith(file).requireContentDir() }
            failure.message shouldContain "CONTENT_DIR does not exist or is not a directory"
            failure.message shouldContain file.toString()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("an existing directory passes the guard and is returned") {
        val dir = Files.createTempDirectory("pb-config-content")
        try {
            configWith(dir).requireContentDir() shouldBe dir
        } finally {
            Files.deleteIfExists(dir)
        }
    }
})

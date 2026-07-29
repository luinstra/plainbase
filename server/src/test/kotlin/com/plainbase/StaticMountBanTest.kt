package com.plainbase

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class StaticMountBanTest : FunSpec({
    test("no source under server/src/main/kotlin mounts static content") {
        val sourceRoot = mainSourceRoot()
        val offenders = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" }
                .toList()
                .filter { source ->
                    val text = source.readText()
                    text.contains("staticResources(") || text.contains("staticFiles(")
                }
        }

        withClue(offenders.map { sourceRoot.relativize(it).toString() }) {
            offenders.shouldBeEmpty()
        }
    }

    test("KtorServer registers no index.html default") {
        val ktorServer = mainSourceRoot().resolve("frameworks/ktor/KtorServer.kt")

        ktorServer.readText().contains("default(\"index.html\")") shouldBe false
    }
})

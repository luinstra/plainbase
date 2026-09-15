package com.plainbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Stage1 source guard for the config/net ownership seam. This is a known-pattern guard plus a reviewed call trace,
 * not a whole-program purity proof.
 */
class ConfigSeparationArchitectureTest : FunSpec({

    val mainRoot = mainSourceRoot()
    val configRoot = mainRoot.resolve("frameworks/config")
    val netRoot = mainRoot.resolve("frameworks/net")
    val configFiles = kotlinFiles(configRoot)
    val netFiles = kotlinFiles(netRoot)
    val remote = netRoot.resolve("RemoteAddress.kt")
    val oldRemote = mainRoot.resolve("frameworks/ktor/RemoteAddress.kt")
    val policy = configRoot.resolve("TransportSecurityPolicy.kt")

    test("config and net scans are nonempty and the address/policy homes are unique") {
        configFiles.shouldNotBeEmpty()
        netFiles.shouldNotBeEmpty()
        remote.isRegularFile() shouldBe true
        policy.isRegularFile() shouldBe true
        oldRemote.isRegularFile() shouldBe false

        Regex("(?m)^object RemoteAddress\\b").findAll(stripComments(remote.readText())).count() shouldBe 1
        Regex("(?m)^internal object TransportSecurityPolicy\\b").findAll(stripComments(policy.readText())).count() shouldBe 1
        Regex("(?m)^internal data class TransportSecurityValues\\b")
            .findAll(stripComments(policy.readText())).count() shouldBe 1
    }

    test("config and net production code do not depend on Ktor or HTTP") {
        val forbiddenPackages = listOf("com.plainbase.frameworks.ktor", "io.ktor")
        val violations = (configFiles + netFiles).flatMap { file ->
            val code = stripComments(file.readText())
            forbiddenPackages.filter(code::contains).map { "${mainRoot.relativize(file)} references $it" }
        }
        violations shouldBe emptyList()
    }

    test("net production code does not depend on config") {
        val violations = netFiles.flatMap { file ->
            val code = stripComments(file.readText())
            listOf("com.plainbase.frameworks.config", "PlainbaseConfig")
                .filter(code::contains)
                .map { "${mainRoot.relativize(file)} references $it" }
        }
        violations shouldBe emptyList()
    }

    test("the landed policy and address files stay free of effectful operations") {
        val forbidden = listOf(
            "Files",
            "FileInputStream",
            "FileOutputStream",
            // Kotlin path extensions that observe or mutate filesystem state; Path factories remain permitted.
            "exists",
            "isRegularFile",
            "isDirectory",
            "isSymbolicLink",
            "readText",
            "readBytes",
            "writeText",
            "writeBytes",
            "readString",
            "writeString",
            "createTempDirectory",
            "createDirectory",
            "createDirectories",
            "listDirectoryEntries",
            "copyTo",
            "moveTo",
            "deleteRecursively",
            "deleteIfExists",
            "ProcessBuilder",
            "Socket",
            "HttpClient",
            "URLConnection",
            "getByName",
            "getAllByName",
            "getLocalHost",
            "InetAddressResolver",
            "transaction",
            "Database",
            "KotlinLogging",
            "Logger",
            "Koin",
            "println",
        )
        val landed = listOf(policy, remote)
        val violations = landed.flatMap { file ->
            val code = stripComments(file.readText())
            forbidden.filter { referencesToken(code, it) }.map { "${mainRoot.relativize(file)} references $it" }
        }
        violations shouldBe emptyList()
    }
})

private fun kotlinFiles(root: Path): List<Path> =
    Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }.toList()
    }

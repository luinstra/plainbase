package com.plainbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Source guard for the config/net ownership seams. This is a known-pattern guard plus reviewed call traces,
 * not a whole-program purity proof.
 */
class ConfigSeparationArchitectureTest : FunSpec({

    val mainRoot = mainSourceRoot()
    val configRoot = mainRoot.resolve("frameworks/config")
    val netRoot = mainRoot.resolve("frameworks/net")
    val productionFiles = kotlinFiles(mainRoot)
    val configFiles = kotlinFiles(configRoot)
    val netFiles = kotlinFiles(netRoot)
    val plainbase = configRoot.resolve("PlainbaseConfig.kt")
    val inspector = configRoot.resolve("ConfigBootInspector.kt")
    val remote = netRoot.resolve("RemoteAddress.kt")
    val oldRemote = mainRoot.resolve("frameworks/ktor/RemoteAddress.kt")
    val policy = configRoot.resolve("TransportSecurityPolicy.kt")
    val valueFiles = listOf(
        configRoot.resolve("ConfigSource.kt"),
        configRoot.resolve("StorageConfig.kt"),
        configRoot.resolve("RootsConfig.kt"),
        configRoot.resolve("GitConfig.kt"),
        configRoot.resolve("AuthConfig.kt"),
        configRoot.resolve("ConfigValuePolicy.kt"),
        plainbase,
    )
    val loader = configRoot.resolve("ConfigLoader.kt")
    val decoder = configRoot.resolve("ConfigDecoder.kt")
    val commonEffectTokens = listOf(
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
        "toRealPath",
        "isReadable",
        "isExecutable",
        "File",
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
    val inspectorReadTokens = setOf(
        "Files",
        "exists",
        "isRegularFile",
        "isDirectory",
        "isSymbolicLink",
        "toRealPath",
        "isReadable",
        "isExecutable",
    )
    val inspectorForbiddenTokens = commonEffectTokens.filterNot { it in inspectorReadTokens } + listOf(
        "ConfigLoader",
        "ConfigDecoder",
        "ConfigSources",
        "ConfigFactory",
        "parseFile",
        "parseString",
        "loadManagedRoots",
        "fromEnv",
        "fromEnvAndFile",
        "fromEnvAndCandidateRoots",
        "loadForCommand",
        "DataDirLock",
        "RootAvailability",
        "History",
        "prepareRootBootInputs",
        "hydrate",
        "write",
        "createFile",
        "newOutputStream",
        "newBufferedWriter",
        "copy",
        "move",
        "delete",
        "FileChannel",
        "OutputStream",
        "PrintWriter",
        "Process",
        "Runtime",
        "exec",
        "start",
        "waitFor",
        "ServerSocket",
        "DatagramSocket",
        "InetAddress",
        "InetSocketAddress",
        "System",
        "stdout",
        "stderr",
        "print",
        "CommandOutput",
        "error",
        "warn",
    )

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

    test("each value, policy, loader, decoder and address owner has one nonempty home") {
        valueFiles.forEach { it.isRegularFile() shouldBe true }
        loader.isRegularFile() shouldBe true
        decoder.isRegularFile() shouldBe true
        valueFiles.forEach { stripComments(it.readText()).isNotBlank() shouldBe true }
        stripComments(loader.readText()).isNotBlank() shouldBe true
        stripComments(decoder.readText()).isNotBlank() shouldBe true
        Regex("(?m)^enum class ConfigSource\\b").findAll(stripComments(valueFiles[0].readText())).count() shouldBe 1
        Regex("(?m)^enum class StorageBackend\\b").findAll(stripComments(valueFiles[1].readText())).count() shouldBe 1
        Regex("(?m)^data class StorageConfig\\b").findAll(stripComments(valueFiles[1].readText())).count() shouldBe 1
        Regex("(?m)^data class RootsConfig\\b").findAll(stripComments(valueFiles[2].readText())).count() shouldBe 1
        Regex("(?m)^data class GitConfig\\b").findAll(stripComments(valueFiles[3].readText())).count() shouldBe 1
        Regex("(?m)^enum class AuthMode\\b").findAll(stripComments(valueFiles[4].readText())).count() shouldBe 1
        Regex("(?m)^data class AuthConfig\\b").findAll(stripComments(valueFiles[4].readText())).count() shouldBe 1
        Regex("(?m)^internal object ConfigValuePolicy\\b").findAll(stripComments(valueFiles[5].readText())).count() shouldBe 1
        Regex("(?m)^data class PlainbaseConfig\\b").findAll(stripComments(plainbase.readText())).count() shouldBe 1
        val policyCode = stripComments(valueFiles[5].readText())
        policyCode shouldContain "fun storageWarnings(config: PlainbaseConfig): List<String>"
        policyCode shouldContain "fun ignoredContentDirWarning(config: PlainbaseConfig): String?"
        policyCode shouldContain "fun editableGlobWarnings(config: PlainbaseConfig): List<String>"
        Regex("(?m)^internal object ConfigLoader\\b").findAll(stripComments(loader.readText())).count() shouldBe 1
        Regex("(?m)^internal object ConfigDecoder\\b").findAll(stripComments(decoder.readText())).count() shouldBe 1
        Regex("(?m)^private object RootsConfigParser\\b").findAll(stripComments(decoder.readText())).count() shouldBe 1
    }

    test("config and net production code do not depend on Ktor or HTTP") {
        val forbiddenPackages = listOf("com.plainbase.frameworks.ktor", "io.ktor")
        val violations = (configFiles + netFiles).flatMap { file ->
            val code = stripComments(file.readText())
            forbiddenPackages.filter(code::contains).map { "${mainRoot.relativize(file)} references $it" }
        }
        violations shouldBe emptyList()
    }

    test("boot inspection has one nonempty stateless owner") {
        inspector.isRegularFile() shouldBe true
        val code = stripComments(inspector.readText())
        Regex("(?m)^internal object ConfigBootInspector\\b").findAll(code).count() shouldBe 1
        code shouldContain "fun requireContentDir(config: PlainbaseConfig): Path"
        code shouldContain "fun bootRefusals(config: PlainbaseConfig): List<BootRefusal>"
        code shouldContain "fun rootsWarnings(config: PlainbaseConfig): List<String>"
        val plainbaseCode = stripComments(plainbase.readText())
        val removedApis = listOf(
            "fromEnv",
            "dataDirFrom",
            "fromEnvAndFile",
            "fromEnvAndCandidateRoots",
            "loadForCommand",
            "requireContentDir",
            "bootRefusals",
            "storageWarnings",
            "rootsWarnings",
            "bindGuardRefusal",
            "isNonLoopbackBind",
            "secureCookie",
            "mcpHostAllowlist",
            "mcpOriginAllowlist",
            "agentDirectCommitGlobs",
            "isAbsoluteHttpUrl",
            "isHttpsUrl",
        )
        removedApis.forEach { api ->
            Regex("(?m)^\\s*(?:internal\\s+)?fun\\s+$api\\b").findAll(plainbaseCode).count() shouldBe 0
            referencesToken(plainbaseCode, api) shouldBe false
        }
        listOf(
            "ConfigLoader",
            "ConfigDecoder",
            "ConfigSources",
            "ConfigValuePolicy",
            "ConfigBootInspector",
            "TransportSecurityPolicy",
            "getenv",
        ).forEach { forbidden ->
            referencesToken(plainbaseCode, forbidden) shouldBe false
        }
    }

    test("the boot inspector is read-only and does not load, wire, log, or mutate") {
        val code = stripComments(inspector.readText())
        inspectorForbiddenTokens.filter { referencesToken(code, it) }.shouldBeEmpty()
        code shouldContain "Files.isDirectory"
        code shouldContain "Files.isReadable"
        code shouldContain "Files.isExecutable"
        code shouldContain "toRealPath()"
        code shouldContain "ManagedRootsFile.backupPath"
        Regex("ManagedRootsFile\\.").findAll(code).count() shouldBe 1
    }

    test("the inspector guard catches spaced calls, callable references and compatibility routes") {
        listOf(
            "Files.writeString (path, \"probe\")" to "writeString",
            "Files::deleteIfExists" to "deleteIfExists",
            "Runtime.getRuntime()::exec" to "Runtime",
            "ConfigLoader.fromEnvAndFile(env)" to "fromEnvAndFile",
            "PlainbaseConfig.fromEnvAndFile(env)" to "fromEnvAndFile",
        ).forEach { (source, expectedToken) ->
            referencesToken(source, expectedToken) shouldBe true
            inspectorForbiddenTokens.any { it == expectedToken } shouldBe true
        }
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
        val landed = listOf(policy, remote)
        val violations = landed.flatMap { file ->
            val code = stripComments(file.readText())
            commonEffectTokens.filter { referencesToken(code, it) }.map { "${mainRoot.relativize(file)} references $it" }
        }
        violations shouldBe emptyList()
    }

    test("pure config owners stay free of parsing, filesystem, logging and wiring effects") {
        val forbidden = commonEffectTokens + listOf(
            "ConfigFactory",
            "parseFile",
            "parseString",
        )
        val pureFiles = valueFiles + listOf(decoder)
        val violations = pureFiles.flatMap { file ->
            val code = stripComments(file.readText())
            forbidden.filter { referencesToken(code, it) }.map { "${mainRoot.relativize(file)} references $it" }
        }
        violations shouldBe emptyList()
    }

    test("HOCON parsing and ConfigSources have one loader/decoder ownership path") {
        val plainbaseCode = stripComments(plainbase.readText())
        val hoconReferences = Regex(
            "\\b(com\\.typesafe\\.config|ConfigFactory|ConfigException|ConfigObject|ConfigValue|ConfigResolveOptions)\\b",
        )
            .findAll(plainbaseCode)
            .map { it.value }
            .toList()
        hoconReferences.shouldBeEmpty()

        val sourcesOwners = productionFiles.filter { "ConfigSources" in stripComments(it.readText()) }
            .map { mainRoot.relativize(it).toString().replace('\\', '/') }
            .toSet()
        sourcesOwners shouldBe setOf("frameworks/config/ConfigLoader.kt", "frameworks/config/ConfigDecoder.kt")

        val decoderCode = stripComments(decoder.readText())
        decoderCode.contains("ConfigLoader") shouldBe false
        decoderCode.contains("ConfigDecoder.") shouldBe false
        val decoderCalls = productionFiles.flatMap { file ->
            Regex("\\bConfigDecoder\\s*\\.").findAll(stripComments(file.readText())).map {
                mainRoot.relativize(file).toString().replace('\\', '/')
            }.toList()
        }
        decoderCalls shouldBe listOf("frameworks/config/ConfigLoader.kt", "frameworks/config/ConfigLoader.kt")
        val hoconOwners = productionFiles.filter { file ->
            referencesToken(stripComments(file.readText()), "com.typesafe.config")
        }
            .map { mainRoot.relativize(it).toString().replace('\\', '/') }
            .toSet()
        hoconOwners shouldBe setOf("frameworks/config/ConfigLoader.kt", "frameworks/config/ConfigDecoder.kt")
        val decoderResolutionMethods = listOf("resolve", "resolveWith").filter { referencesToken(decoderCode, it) }
        decoderResolutionMethods.shouldBeEmpty()
        val parserOwners = productionFiles.flatMap { file ->
            val code = stripComments(file.readText())
            listOf("ConfigFactory", "ConfigResolveOptions")
                .filter { referencesToken(code, it) }
                .map { mainRoot.relativize(file).toString().replace('\\', '/') }
        }.toSet()
        parserOwners shouldBe setOf("frameworks/config/ConfigLoader.kt")
        val loaderCode = stripComments(loader.readText())
        loaderCode shouldContain "ConfigFactory.parseFile"
        loaderCode shouldContain "ConfigFactory.parseString"
        loaderCode shouldContain ".resolve(ConfigResolveOptions.defaults())"
        decoderCode.contains("ConfigFactory") shouldBe false
    }
})

private fun kotlinFiles(root: Path): List<Path> =
    Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }.toList()
    }

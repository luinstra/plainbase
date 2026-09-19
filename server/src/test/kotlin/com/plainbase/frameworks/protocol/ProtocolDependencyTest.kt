package com.plainbase.frameworks.protocol

import com.plainbase.mainSourceRoot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

private val PROTOCOL_IMPORT_PREFIXES = listOf(
    "kotlin.",
    "kotlinx.serialization.",
    "com.plainbase.domain.",
    "com.plainbase.frameworks.protocol.",
)
private val HTTP_IMPORT_PREFIXES = listOf("com.plainbase.frameworks.ktor.", "io.ktor.")

class ProtocolDependencyTest : FunSpec({
    val main = mainSourceRoot()
    val protocol = main.resolve("frameworks/protocol")
    val mcp = main.resolve("frameworks/mcp")
    val protocolFiles = kotlinSources(protocol)
    val mcpFiles = kotlinSources(mcp).filterNot { it.name == "McpMount.kt" }

    test("the protocol source scan is non-empty") {
        protocolFiles.size shouldBeGreaterThan 0
    }

    test("protocol imports stay within the durable package prefixes") {
        val violations = protocolFiles.flatMap { file ->
            imports(file).filterNot { target -> PROTOCOL_IMPORT_PREFIXES.any(target::startsWith) }
                .map { "${protocol.relativize(file)}: import $it" }
        }
        check(violations.isEmpty()) { "protocol import violations:\n${violations.joinToString("\n")}" }
    }

    test("the non-mount MCP source scan sees the server") {
        mcpFiles.size shouldBeGreaterThan 0
        mcpFiles.any { it.name == "PlainbaseMcpServer.kt" }.shouldBeTrue()
    }

    test("non-mount MCP sources do not import HTTP mounting glue") {
        val violations = mcpFiles.flatMap { file ->
            imports(file).filter { target -> HTTP_IMPORT_PREFIXES.any(target::startsWith) }
                .map { "${mcp.relativize(file)}: import $it" }
        }
        check(violations.isEmpty()) { "MCP import violations:\n${violations.joinToString("\n")}" }
    }
})

private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { stream ->
    stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
}

private fun imports(file: Path): List<String> = file.readLines()
    .filter { it.startsWith("import ") }
    .map { it.removePrefix("import ").trim() }

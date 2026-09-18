package com.plainbase.frameworks.mcp

import com.plainbase.frameworks.protocol.maskKotlinSource
import com.plainbase.mainSourceRoot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class McpMountArchitectureTest : FunSpec({
    val sourceFile = mainSourceRoot().resolve("frameworks/mcp/McpMount.kt")
    val publicDelegation = Regex(
        """(?ms)^fun\s+Route\.plainbaseMcp\s*\(\s*ctx:\s*RouteContext\s*\)\s*=\s*""" +
            """plainbaseMcp\s*\(\s*ctx\s*,\s*::buildPlainbaseMcpServer\s*\)""" +
            """\s*(?=internal\s+fun\s+Route\.plainbaseMcp\s*\()""",
    )

    test("the public MCP mount delegates directly to the real narrowed factory") {
        check(Files.isRegularFile(sourceFile)) { "missing MCP mount source: $sourceFile" }
        val source = maskKotlinSource(Files.readString(sourceFile))
        check(source.isNotBlank()) { "empty MCP mount source: $sourceFile" }

        publicDelegation.findAll(source).count() shouldBe 1
        Regex("buildPlainbaseMcpServer").findAll(source).count() shouldBe 1
    }

    test("the public delegation guard rejects work appended to the factory call") {
        val source = """
            fun Route.plainbaseMcp(ctx: RouteContext) = plainbaseMcp(ctx, ::buildPlainbaseMcpServer).also { extraWork() }

            internal fun Route.plainbaseMcp(ctx: RouteContext, buildServer: Factory) {}
        """.trimIndent()

        publicDelegation.findAll(source).count() shouldBe 0
    }

    test("the MCP server factory accepts only the narrowed guarded capabilities") {
        val serverFile = mainSourceRoot().resolve("frameworks/mcp/PlainbaseMcpServer.kt")
        check(Files.isRegularFile(serverFile)) { "missing MCP server source: $serverFile" }
        val source = maskKotlinSource(Files.readString(serverFile))
        check(source.isNotBlank()) { "empty MCP server source: $serverFile" }

        Regex(
            """(?s)fun\s+buildPlainbaseMcpServer\s*\(\s*principal:\s*Principal\.Agent\s*,\s*read:\s*ReadFacade\s*,\s*proposals:\s*ProposalFacade\s*,\s*roots:\s*Set<RootName>\s*,?\s*\)\s*:\s*Server""",
        ).findAll(source).count() shouldBe 1
        source shouldNotContain "RouteContext"
    }
})

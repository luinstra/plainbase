package com.plainbase.frameworks.mcp

import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.frameworks.protocol.ListChangesResponse
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val INTERNAL_ERROR_BODY = "{\"error\":{\"code\":\"internal\",\"message\":\"Internal error\"}}"
private const val FORBIDDEN_ERROR_BODY = "{\"error\":{\"code\":\"forbidden\",\"message\":\"You do not have permission for this action\"}}"
private const val REQUEST_LABEL = "list_changes-containment"

private class StableContainmentFailure : IllegalStateException("stable list failure")

/** Direct helper containment plus one real SSE session proving recovery and live-token revocation ordering. */
class McpErrorContainmentTest : FunSpec({
    test("toolResult contains a thrown sentinel with the frozen internal error bytes") {
        val result = toolResult(ListChangesResponse.serializer()) { throw StableContainmentFailure() }

        assertExactError(result, INTERNAL_ERROR_BODY, REQUEST_LABEL, 0)
    }

    test("list_changes recovers after one repository failure and checks revocation before repository access") {
        val calls = AtomicInteger()
        val throwOnce = AtomicBoolean()
        McpHarness(
            proposalRepositoryDecorator = { delegate ->
                object : ProposalRepository by delegate {
                    override fun all() = delegate.all().also {
                        calls.incrementAndGet()
                        if (throwOnce.compareAndSet(true, false)) throw StableContainmentFailure()
                    }
                }
            },
        ).use { harness ->
            harness.session(harness.proposeBearer) { client ->
                withTimeout(15_000) {
                    throwOnce.set(true)
                    val failed = client.call("list_changes")
                    calls.get() shouldBe 1
                    assertExactError(failed, INTERNAL_ERROR_BODY, REQUEST_LABEL, calls.get())

                    val recovered = client.call("list_changes")
                    calls.get() shouldBe 2
                    recovered.isErr() shouldBe false
                    recovered.text() shouldBe "{\"proposals\":[]}"

                    harness.revokeProposeToken()
                    throwOnce.set(true)
                    val denied = client.call("list_changes")
                    calls.get() shouldBe 2
                    assertExactError(denied, FORBIDDEN_ERROR_BODY, REQUEST_LABEL, calls.get())
                }
            }
        }
    }
})

private fun assertExactError(result: CallToolResult, expected: String, label: String, calls: Int) {
    val renderedContent = result.content.joinToString { content ->
        if (content is TextContent) content.text else content.toString()
    }
    withClue("$label calls=$calls response=$renderedContent") {
        result.content shouldHaveSize 1
        result.isErr() shouldBe true
        result.content.single().shouldBeInstanceOf<TextContent>().text shouldBe expected
    }
}

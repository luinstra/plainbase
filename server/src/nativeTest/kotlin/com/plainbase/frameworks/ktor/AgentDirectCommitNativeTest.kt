package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.service.CommitGlob
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P5 native-image smoke: an in-glob agent COMMIT direct commit applies in the closed-world image. The direct path is
 * the already-native-proven `writePipeline.write`; this proves the P5 gate (the pure decision + `agentModeFor` lookup
 * + the DIRECT_PUT branch) compiles + runs natively. One assertion to bound native-test cost. kotlin.test + @Tag.
 */
@Tag("native")
class AgentDirectCommitNativeTest {

    private val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    private val markdown = ContentType.parse("text/markdown")

    @Test
    fun `an in-glob agent COMMIT direct-commits natively`() {
        val original = "---\nid: $pageId\ntitle: Native Direct\n---\n\n# Native Direct\n\nbody.\n"
        withRestServices(
            pages = mapOf("docs/native.md" to original),
            agentDirectCommitGlobs = listOf(CommitGlob.parse("docs/**")),
        ) { services ->
            // A COMMIT bearer authenticates in every mode (the bearer path is mode-independent).
            val bearer = "Bearer " + services.tokens.mint(label = "ci", mode = AgentMode.COMMIT).plaintext
            testApplication {
                application { plainbaseModule(services) }
                val tag = client.get("/api/v1/pages/$pageId") { header(HttpHeaders.Authorization, bearer) }
                    .headers[HttpHeaders.ETag]!!
                val put = client.put("/api/v1/pages/$pageId") {
                    header(HttpHeaders.Authorization, bearer)
                    header(HttpHeaders.IfMatch, tag)
                    contentType(markdown)
                    setBody(original + "\nnative direct edit.\n")
                }
                // In-glob → a direct 200 Written (NOT a 202 degrade), proving the P5 decision path runs natively.
                assertEquals(HttpStatusCode.OK, put.status)
            }
        }
    }
}

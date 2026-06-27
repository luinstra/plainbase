package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The debate-REQUIRED test (synthesis §"WIDEN" 4): an agent `pb_` bearer mutation (PUT /api/v1/pages/{id}) succeeds
 * with NO `X-CSRF-Token` — the guard EXEMPTS bearer-resolved principals (no ambient cookie). The SAME mutation via
 * a cookie session WITHOUT a CSRF token is rejected 403 — proving the predicate is "cookie-authenticated AND
 * mutating", not "any mutation".
 */
class BearerExemptCsrfTest : FunSpec({

    // The seeded page has no `id:` line, so the edited body must also omit it (the PB-WRITE-1 id-tamper guard
    // rejects ADDING an id as a rename → 422). The CSRF behavior is what this test asserts, not id materialization.
    fun pageBody() = "---\ntitle: Doc\n---\n\nedited body.\n"

    test("an agent bearer PUT is ACCEPTED with NO X-CSRF-Token (bearer is CSRF-exempt)") {
        authRouteTest(enforced = true) { harness ->
            val (id, hash) = harness.seedPage("doc.md", "---\ntitle: Doc\n---\n\nbody.\n")
            val bearer = harness.mintAgentToken(AgentMode.COMMIT) // an agent that may edit
            val response = client.put("/api/v1/pages/$id") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                header(HttpHeaders.IfMatch, "\"$hash\"")
                contentType(ContentType.parse("text/markdown"))
                setBody(pageBody())
            }
            // The harness configures no agentDirectCommit.globs (the default), so a P5 COMMIT agent's write DEGRADES to
            // a proposal (202). The point this test makes is CSRF EXEMPTION: the bearer was ACCEPTED (202, NOT a 403
            // csrf_failed) with no X-CSRF-Token — the cookie-CSRF case below proves the contrast.
            response.status shouldBe HttpStatusCode.Accepted
        }
    }

    test("the SAME PUT via a cookie session WITHOUT a CSRF token → 403 csrf_failed") {
        authRouteTest(enforced = true) { harness ->
            val (id, hash) = harness.seedPage("doc.md", "---\ntitle: Doc\n---\n\nbody.\n")
            harness.seedUser("alice", "pw", Role.EDITOR)
            val client = cookieClient()
            client.post("/api/v1/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","password":"pw"}""")
            }
            val response = client.put("/api/v1/pages/$id") {
                header(HttpHeaders.IfMatch, "\"$hash\"")
                contentType(ContentType.parse("text/markdown"))
                setBody(pageBody()) // NO X-CSRF-Token
            }
            response.status shouldBe HttpStatusCode.Forbidden
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe
                "csrf_failed"
        }
    }
})

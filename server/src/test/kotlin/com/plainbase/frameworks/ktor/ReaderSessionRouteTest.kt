package com.plainbase.frameworks.ktor

import com.plainbase.domain.repository.AgentMode
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.protocol.RestJson
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject

class ReaderSessionRouteTest : FunSpec({
    test("local reader session has only anonymous reader state and sets no cookie") {
        readerTest(Fixtures.demoDocs) {
            val response = client.get("/api/v1/session")
            val body = RestJson.parseToJsonElement(response.bodyAsText()).jsonObject
            assertSoftly {
                response.status shouldBe HttpStatusCode.OK
                body shouldBe RestJson.parseToJsonElement("""{"authenticated":false,"auth_mode":"reader"}""")
                body.containsKey("csrf_token") shouldBe false
                body.containsKey("username") shouldBe false
                body.containsKey("release") shouldBe false
                response.headers[HttpHeaders.SetCookie] shouldBe null
            }
        }
    }

    test("reader session forwards its release and ignores all four credentials without setting a cookie") {
        readerTest(Fixtures.demoDocs, prefix = "releases/r1") { harness ->
            val token = harness.index.apiTokens.mint("probe", AgentMode.COMMIT)
            val session = harness.index.sessionService.create("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val bare = client.get("/api/v1/session")
            val bareBody = bare.bodyAsBytes()
            val credentialed = client.get("/api/v1/session") {
                header(HttpHeaders.Authorization, "Bearer ${token.plaintext}")
                header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=${session.plaintext}")
                header("X-Forwarded-User", "alice")
                header(PROXY_SECRET_HEADER, "s")
            }
            val credentialedBody = credentialed.bodyAsBytes()
            val parsed = RestJson.parseToJsonElement(bareBody.decodeToString()).jsonObject
            val expected = """{"authenticated":false,"auth_mode":"reader","release":"r1"}"""
            assertSoftly {
                bare.status shouldBe HttpStatusCode.OK
                bareBody shouldBe expected.encodeToByteArray()
                parsed shouldBe RestJson.parseToJsonElement(expected)
                parsed.containsKey("csrf_token") shouldBe false
                parsed.containsKey("username") shouldBe false
                credentialed.status shouldBe bare.status
                credentialedBody shouldBe bareBody
                bare.headers.getAll(HttpHeaders.SetCookie) shouldBe null
                credentialed.headers.getAll(HttpHeaders.SetCookie) shouldBe null
            }
        }
    }
})

package com.plainbase.frameworks.ktor

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class HeadWireTest : FunSpec({
    listOf(
        "/api/v1/nope" to "HTTP/1.1 404 Not Found",
        "/api/v1/nope?q=%" to "HTTP/1.1 400 Bad Request",
        "/api/v1/pages/by-path/%GG" to "HTTP/1.1 400 Bad Request",
    ).forEach { (path, statusLine) ->
        test("HEAD $path matches GET metadata without body bytes before the next response") {
            wireServer { port ->
                val get = observeWire(port, "GET", path)
                val head = observeWire(port, "HEAD", path)
                withClue("GET=$get; HEAD=$head") {
                    val getNext = get.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                    val headNext = head.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                    assertSoftly {
                        get.head.statusLine shouldBe statusLine
                        head.head.statusLine shouldBe statusLine
                        get.head.header("Content-Type") shouldBe listOf("application/json")
                        get.head.header("Content-Length") shouldBe listOf(getNext.leftover.toString())
                        (getNext.leftover > 0) shouldBe true
                        listOf("Content-Type", "Content-Length").forEach { name ->
                            withClue(name) { head.head.header(name) shouldBe get.head.header(name) }
                        }
                        getNext.statusLine shouldBe "HTTP/1.1 200 OK"
                        headNext.statusLine shouldBe "HTTP/1.1 200 OK"
                        withClue("HEAD leftover") { headNext.leftover shouldBe 0 }
                    }
                }
            }
        }
    }
})

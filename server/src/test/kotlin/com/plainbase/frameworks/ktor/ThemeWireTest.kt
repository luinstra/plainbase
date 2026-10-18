package com.plainbase.frameworks.ktor

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ThemeWireTest : FunSpec({
    test("GET detector observes the complete namespace body before the next response") {
        wireServer { port ->
            val observed = observeWire(port, "GET", "/_plainbase/nope.css")
            withClue(observed) {
                val next = observed.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                assertSoftly {
                    observed.head.statusLine shouldBe "HTTP/1.1 404 Not Found"
                    next.statusLine shouldBe "HTTP/1.1 200 OK"
                    (next.leftover > 0) shouldBe true
                    observed.head.header("Content-Length") shouldBe listOf(next.leftover.toString())
                }
            }
        }
    }
    listOf(
        "/_plainbase",
        "/_plainbase/",
        "/_plainbase/nope.css",
        "/_plainbase/plainbase-theme-light.css",
        "/_plainbase/plainbase-theme-dark.css",
    ).forEach { path ->
        test("HEAD $path matches GET metadata without body bytes before the next response") {
            wireServer { port ->
                val get = observeWire(port, "GET", path)
                val head = observeWire(port, "HEAD", path)
                withClue("GET=$get; HEAD=$head") {
                    val getNext = get.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                    val headNext = head.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                    assertSoftly {
                        get.head.statusLine shouldBe "HTTP/1.1 404 Not Found"
                        head.head.statusLine shouldBe get.head.statusLine
                        get.head.header("Content-Type") shouldBe listOf("application/json")
                        get.head.header("Cache-Control") shouldBe listOf("no-store")
                        get.head.header("X-Content-Type-Options") shouldBe listOf("nosniff")
                        get.head.header("Content-Length") shouldBe listOf(getNext.leftover.toString())
                        (getNext.leftover > 0) shouldBe true
                        listOf("Content-Type", "Cache-Control", "X-Content-Type-Options", "Content-Length").forEach { name ->
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

    listOf("/_plainbase/theme-light.css", "/_plainbase/theme-dark.css", "/_plainbase/custom.css").forEach { path ->
        test("HEAD $path refuses the method without body bytes before the next response") {
            wireServer { port ->
                val observed = observeWire(port, "HEAD", path)
                withClue(observed) {
                    val next = observed.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                    assertSoftly {
                        observed.head.statusLine shouldBe "HTTP/1.1 405 Method Not Allowed"
                        observed.head.header("Allow") shouldBe listOf("GET")
                        observed.head.header("Cache-Control") shouldBe listOf("no-store")
                        observed.head.header("X-Content-Type-Options") shouldBe listOf("nosniff")
                        observed.head.header("Content-Length") shouldBe listOf("0")
                        next.statusLine shouldBe "HTTP/1.1 200 OK"
                        next.leftover shouldBe 0
                    }
                }
            }
        }
    }
})

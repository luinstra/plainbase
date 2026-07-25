package com.plainbase.frameworks.cli

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

class CommandOutputTest : FunSpec({
    val intent = WriteIntent(
        pageId = "01900000-0000-7000-8000-000000000001",
        page = RootedPath(RootName.require("notes"), TreePath.require("guides/start.md")),
        qualifyRoot = true,
    )

    test("result and error preserve their exact channels and newline contract") {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val output = StreamCommandOutput(stdout.printStream(), stderr.printStream(), CommandEventSink {})

        output.result("first", newline = false)
        output.result(" second")
        output.error("refused")

        stdout.toString(Charsets.UTF_8) shouldBe "first second\n"
        stderr.toString(Charsets.UTF_8) shouldBe "refused\n"
    }

    test("plain intent retains the legacy transcript and supports an unqualified single root") {
        val stream = ByteArrayOutputStream()
        val sink = PlainCommandEventSink(stream.printStream())

        sink.publish(intent.copy(qualifyRoot = false))

        stream.toString(Charsets.UTF_8) shouldBe
            "intent: write id ${intent.pageId} -> guides/start.md\n"
    }

    test("container intent is one typed JSON line on its dedicated stream") {
        val stream = ByteArrayOutputStream()

        JsonCommandEventSink(stream.printStream()).publish(intent)

        val line = stream.toString(Charsets.UTF_8)
        line.count { it == '\n' } shouldBe 1
        val event = Json.parseToJsonElement(line).toString()
        event shouldContain "\"type\":\"write_intent\""
        event shouldContain "\"pageId\":\"${intent.pageId}\""
        event shouldContain "\"root\":\"notes\""
        event shouldContain "\"path\":\"guides/start.md\""
        event shouldNotContain "token"
        event shouldNotContain "secret"
    }

    test("a failed checked intent stream fails closed") {
        val broken = PrintStream(object : OutputStream() {
            override fun write(value: Int) = throw java.io.IOException("closed")
        })

        shouldThrow<IllegalStateException> {
            PlainCommandEventSink(broken).publish(intent)
        }.message shouldContain "command event"
    }
})

private fun ByteArrayOutputStream.printStream() = PrintStream(this, true, Charsets.UTF_8)

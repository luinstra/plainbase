package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.frameworks.markdown.FrontmatterReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.parser.ParserException
import java.nio.file.Files

/**
 * Original interoperability fixtures exercise the real reader and raw-save contracts alongside the independent oracle.
 * Format validity is distinct from reader projection, reserved role, link health, and whole-directory certification.
 * Negative oracle fixtures remain accepted author-owned source; they do not test producer rejection.
 */
class OkfDocumentInteropTest : FunSpec({
    val citations = CitationFactory()
    val cases = listOf(
        "minimal.md" to "Reference", // type-only LF concept, optional families absent
        "crlf.md" to "Reference", // authored CRLF interoperates without rewrite
        "bom.md" to "Reference", // UTF-8 BOM interoperability, preserved on raw save
        "rich.md" to "Operational Note", // unknown type, nested metadata and a broken link remain valid format
        "malformed.md" to null, // YAML failure, still author-owned bytes
        "missing-type.md" to null,
        "empty-type.md" to null,
        "null-type.md" to null,
        "numeric-type.md" to null,
        "list-type.md" to null,
        "dotted-closer.md" to null, // legacy detection accepts ..., not an exact generated OKF fence
        "index.md" to null, // headerless listing; no concept-header assertion applies
        "root-index.md" to null, // root-only version exception, not a concept
        "legacy-section.md" to null, // title-bearing legacy landing, not an OKF index
        "log.md" to null, // headerless history; no frontmatter type condition applies
        "typed-log.md" to "History", // ordinary concept filename with log-like content
    )

    for ((name, expectedType) in cases) {
        test("$name preserves authoritative bytes through reader admission and raw save regardless of format role") {
            val bytes = fixtureBytes(name)
            if (name == "crlf.md") {
                bytes.copyOfRange(0, 5) shouldBe byteArrayOf(0x2D, 0x2D, 0x2D, 0x0D, 0x0A)
                bytes.decodeToString().replace("\r\n", "").contains('\n') shouldBe false
            }
            if (name == "bom.md") bytes.copyOfRange(0, 3) shouldBe byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            if (expectedType != null) {
                independentHeaderType(bytes) shouldBe expectedType
            } else {
                when (name) {
                    "malformed.md" -> shouldThrow<ParserException> { independentHeader(bytes) }
                    "index.md", "log.md", "dotted-closer.md" ->
                        shouldThrow<IllegalArgumentException> { independentHeader(bytes) }.message shouldBe
                            "Expected exact frontmatter fences"
                    else -> {
                        // A valid mapping must parse before the missing/empty/non-string type condition is tested.
                        val header = independentHeader(bytes)
                        val typeNode = header.value.singleOrNull { (it.keyNode as? ScalarNode)?.value == "type" }?.valueNode
                        when (name) {
                            "missing-type.md", "root-index.md", "legacy-section.md" -> typeNode shouldBe null
                            "empty-type.md" -> {
                                val scalar = typeNode.shouldBeInstanceOf<ScalarNode>()
                                scalar.tag shouldBe Tag.STR
                                scalar.value shouldBe ""
                            }
                            "null-type.md" -> typeNode.shouldBeInstanceOf<ScalarNode>().tag shouldBe Tag.NULL
                            "numeric-type.md" -> typeNode.shouldBeInstanceOf<ScalarNode>().tag shouldBe Tag.INT
                            "list-type.md" -> typeNode.shouldBeInstanceOf<SequenceNode>().tag shouldBe Tag.SEQ
                            else -> error("Unclassified negative fixture: $name")
                        }
                        header.stringValue("type") shouldBe if (name == "empty-type.md") "" else null
                        if (name == "missing-type.md") header.stringValue("title") shouldBe "Legacy"
                        if (name == "legacy-section.md") header.stringValue("title") shouldBe "Guides"
                        shouldThrow<IllegalArgumentException> { independentHeaderType(bytes) }.message shouldBe
                            "Expected nonempty string type"
                    }
                }
            }
            val projection = FrontmatterReader().parse(bytes)
            if (expectedType != null) projection.scalar("type") shouldBe expectedType
            if (name == "rich.md") {
                projection.scalar("title") shouldBe "Recovery notes"
                projection.values.containsKey("by") shouldBe false
                projection.values.containsKey("nested") shouldBe false
            }
            if (name == "dotted-closer.md") {
                (FrontmatterBlock.detect(bytes) is FrontmatterBlock.Detection.Present) shouldBe true
                projection.scalar("type") shouldBe "Reference"
            }
            if (name == "root-index.md") independentHeader(bytes).stringValue("okf_version") shouldBe "0.2"

            val root = Files.createTempDirectory("okf-interop")
            val id = TestIdProvider().next()
            try {
                Files.write(root.resolve(name), bytes)
                writeRestTest(root, seed = { it.bind(RootedPath(RootName.PRIMARY, TreePath.require(name)), id, false) }) { harness ->
                    val response = client.get("/api/v1/pages/${id.value}")
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag] shouldBe "\"${citations.contentHash(bytes)}\""
                    val edited = bytes + "\nAuthor body edit.\n".toByteArray()
                    val saved = client.put("/api/v1/pages/${id.value}") {
                        header(HttpHeaders.IfMatch, response.headers[HttpHeaders.ETag])
                        contentType(ContentType.parse("text/markdown"))
                        setBody(edited)
                    }
                    saved.status shouldBe HttpStatusCode.OK
                    harness.diskBytes(name) shouldBe edited
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    test("raw author saves can add and remove type without changing honored identity or nested metadata") {
        val id = TestIdProvider().next()
        val authored = fixtureBytes("rich.md").decodeToString().replaceFirst("---\n", "---\nid: ${id.value}\n")
        val root = Files.createTempDirectory("okf-raw-type")
        try {
            Files.writeString(root.resolve("rich.md"), authored)
            writeRestTest(root) { harness ->
                for (edit in listOf(authored.replace("type: Operational Note\n", ""), authored)) {
                    val got = client.get("/api/v1/pages/${id.value}")
                    got.status shouldBe HttpStatusCode.OK
                    val saved = client.put("/api/v1/pages/${id.value}") {
                        header(HttpHeaders.IfMatch, got.headers[HttpHeaders.ETag])
                        contentType(ContentType.parse("text/markdown"))
                        setBody(edit.toByteArray())
                    }
                    saved.status shouldBe HttpStatusCode.OK
                    harness.diskBytes("rich.md") shouldBe edit.toByteArray()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

private fun fixtureBytes(name: String): ByteArray =
    requireNotNull(OkfDocumentInteropTest::class.java.getResourceAsStream("/okf-documents/$name")).use { it.readBytes() }

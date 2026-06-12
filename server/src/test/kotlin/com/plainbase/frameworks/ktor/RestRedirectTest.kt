package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.nio.file.Files

/**
 * §A4 alias/redirect semantics over HTTP: move aliases (301, one hop), `redirect_from` (301,
 * incl. the collision-loser permalink fallback), and `/browse/{file-path}` (302, decode-once +
 * NFC) — including the encoded-space and unicode filename rows.
 */
class RestRedirectTest : FunSpec({

    test("moving a page 301s its old /docs URL to the new one (one hop), recorded at rescan") {
        withTempTree(seed = { root ->
            // The id is MATERIALIZED in the frontmatter: pre-materialization identity is path-keyed
            // by accepted design (§5.2), so move detection — and therefore the alias — exists only
            // for ids that survive the move (the MoveFileIntegrationTest covers the adopt path).
            writePage(root, "guides/movable.md", "---\nid: 0197c4d5-1234-7abc-8def-0123456789ab\ntitle: Movable\n---\n\n# Movable\n")
        }) { root ->
            restTest(root) {
                val client = restClient()
                client.get("/docs/guides/movable").status shouldBe HttpStatusCode.OK // canonical: shell

                Files.createDirectories(root.resolve("archive"))
                Files.move(root.resolve("guides/movable.md"), root.resolve("archive/movable.md"))
                client.post("/api/v1/admin/rescan").status shouldBe HttpStatusCode.OK

                val old = client.get("/docs/guides/movable")
                old.status shouldBe HttpStatusCode.MovedPermanently // 301 — aliases are stable
                old.headers[HttpHeaders.Location] shouldBe "/docs/archive/movable"
            }
        }
    }

    test("redirect_from: [/old/deployment.md] 301s /docs/old/deployment to the canonical URL") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()
            val response = client.get("/docs/old/deployment")
            response.status shouldBe HttpStatusCode.MovedPermanently
            response.headers[HttpHeaders.Location] shouldBe "/docs/guides/deploy-guide"
        }
    }

    test("an alias to a collision loser 301s to its /p/{id} permalink — its one durable URL") {
        withTempTree(seed = { root ->
            // Both slugify to `a-b`; raw-byte order makes `a b.md` (0x20) win, so `a-b.md` is the
            // path-space loser (url = null). Its redirect_from alias must still land SOMEWHERE:
            // the same fallback /browse uses — the permalink, never the shell.
            writePage(root, "a b.md", "---\ntitle: Winner\n---\n\n# Winner\n")
            writePage(root, "a-b.md", "---\ntitle: Loser\nredirect_from: [/old/loser.md]\n---\n\n# Loser\n")
        }) { root ->
            restTest(root) { harness ->
                val client = restClient()
                val loser = harness.builder.current.byPath.getValue(TreePath.require("a-b.md"))

                val response = client.get("/docs/old/loser")
                response.status shouldBe HttpStatusCode.MovedPermanently
                response.headers[HttpHeaders.Location] shouldBe "/p/${loser.id.value}"
            }
        }
    }

    test("/browse/{file-path} 302s the exact content-relative file path to the canonical URL") {
        restTest(Fixtures.demoDocs) {
            val client = restClient()

            val plain = client.get("/browse/guides/deploy-guide.md")
            plain.status shouldBe HttpStatusCode.Found
            plain.headers[HttpHeaders.Location] shouldBe "/docs/guides/deploy-guide"

            // Encoded space: decode-once yields the on-disk name `release notes 2026.md`.
            val spaced = client.get("/browse/notes/release%20notes%202026.md")
            spaced.status shouldBe HttpStatusCode.Found
            spaced.headers[HttpHeaders.Location] shouldBe "/docs/notes/release-notes-2026"

            // Unicode filename: percent-decoded once to NFC; the Location re-encodes on emit.
            val unicode = client.get("/browse/notes/%E6%97%A5%E6%9C%AC%E8%AA%9E%E3%82%AC%E3%82%A4%E3%83%89.md")
            unicode.status shouldBe HttpStatusCode.Found
            unicode.headers[HttpHeaders.Location] shouldBe "/docs/notes/%E6%97%A5%E6%9C%AC%E8%AA%9E%E3%82%AC%E3%82%A4%E3%83%89"

            client.get("/browse/no/such/file.md").status shouldBe HttpStatusCode.NotFound
            client.get("/browse/%2e%2e/escape.md").status shouldBe HttpStatusCode.BadRequest
        }
    }
})

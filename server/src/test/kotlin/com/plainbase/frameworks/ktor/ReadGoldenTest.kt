package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * PB-READ-2 FOREVER golden corpus (Phase 5, chunk P2) — the frozen `validate_links` + `get_page_metadata` wire
 * shapes, rendered against a DEDICATED tiny fixture corpus written to a temp dir (NOT `Fixtures.demoDocs`, so the
 * brace-heavy `javascript:` target cannot trip the flexmark render-length golden suites). Comparison is
 * parsed-JSON-tree equality (the [RestGolden] idiom); `content_hash` is recomputed from the fixture bytes at test
 * time, never committed.
 *
 * `broken.md` is the PINNED gate (master §2.6 acceptance 3): it carries EXACTLY 2 broken links
 * (`broken_missing` + `blocked_scheme`, two distinct `Unresolved` reason strings) + 1 broken anchor
 * (`broken_anchor`), plus a VALID link and a VALID same-page anchor as controls (proving clean entries are NOT
 * reported). EDITING ANY FIXTURE FILE OR GOLDEN IS A DELIBERATE PB-READ-2 REGENERATION — these shapes are
 * re-exposed VERBATIM by P3 MCP; a shape change is a contract break across BOTH REST and MCP.
 */
class ReadGoldenTest : FunSpec({

    val brokenId = "0197c000-0000-7000-8000-00000000b00c"
    val cleanId = "0197c000-0000-7000-8000-00000000c1ea"

    // `broken.md`: 2 Unresolved (broken_missing, blocked_scheme) + 1 UnknownAnchor (broken_anchor); plus a VALID
    // internal link (to clean.md) and a VALID same-page anchor (#intro) so the per-page filter's clean entries are
    // proven NOT reported. The valid same-page anchor needs a real heading, so the page has `# Intro`.
    val brokenSource =
        """
        ---
        title: Broken Fixture
        ---

        # Intro

        A [valid link](./clean.md) and a [valid anchor](#intro) (controls — never reported).

        A [gone](./does-not-exist.md) link (broken_missing), a [bad](javascript:alert) link
        (blocked_scheme), and a [top](#no-such-heading) anchor (broken_anchor).
        """.trimIndent() + "\n"

    val cleanSource =
        """
        ---
        title: Clean Fixture
        ---

        # Welcome

        A [self link](#welcome) — every link and anchor resolves.
        """.trimIndent() + "\n"

    fun withCorpus(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        val root: Path = Files.createTempDirectory("plainbase-read-golden")
        try {
            Files.writeString(root.resolve("broken.md"), brokenSource)
            Files.writeString(root.resolve("clean.md"), cleanSource)
            val seed: (IdMapRepository) -> Unit = { idMap ->
                idMap.bind(TreePath.require("broken.md"), PageId.require(brokenId), materialized = false)
                idMap.bind(TreePath.require("clean.md"), PageId.require(cleanId), materialized = false)
            }
            restTest(root, seed) { block() }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("validate-links-broken.json — the PINNED 2-unresolved + 1-broken-anchor gate (master §2.6 acceptance 3)") {
        withCorpus {
            val resp = client.get("/api/v1/pages/$brokenId/validate-links")
            resp.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(resp.bodyAsText()) shouldBe RestGolden.load("validate-links-broken.json")
        }
    }

    test("validate-links-clean.json — a page whose every link + anchor resolves reports `{broken:[]}`") {
        withCorpus {
            val resp = client.get("/api/v1/pages/$cleanId/validate-links")
            resp.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(resp.bodyAsText()) shouldBe RestGolden.load("validate-links-clean.json")
        }
    }

    test("page-metadata.json — a get_page_metadata projection (id/path/url/content_hash/commit/title/headings)") {
        withCorpus {
            val resp = client.get("/api/v1/pages/$brokenId/metadata")
            resp.status shouldBe HttpStatusCode.OK
            // content_hash is recomputed from the SAME verbatim fixture bytes the index hashes — never a committed
            // value (the §A4 golden policy); the golden carries the {{content_hash}} placeholder.
            Json.parseToJsonElement(resp.bodyAsText()) shouldBe
                RestGolden.load("page-metadata.json", mapOf("content_hash" to contentHashOf(brokenSource)))
        }
    }
})

/** The §5.3 content-hash form over the verbatim UTF-8 fixture bytes (the same hash the index computes). */
private fun contentHashOf(source: String): String =
    "sha256:" + java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)),
    )

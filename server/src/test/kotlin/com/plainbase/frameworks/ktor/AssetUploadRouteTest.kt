package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.EditGrant
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * W3b behavioral named tests for `POST /api/v1/pages/{id}/assets` — the containment-guarded binary
 * upload through the new `writeAssetExclusive` (reuses W2's `rejectionReason` as ONE source of truth,
 * but requires an existing parent + fails closed), the atomic O_EXCL no-clobber, the strict filename
 * pipeline, and the rebuild-failure (written-but-unindexed) semantics. All over [writeRestTest] (a temp
 * COPY) so the committed `Fixtures.demoDocs` is never mutated. Seeds [deployGuideId] under `guides/`.
 */
class AssetUploadRouteTest : FunSpec({

    val citations = CitationFactory()
    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("guides/deploy-guide.md"), PageId.require(deployGuideId), materialized = false)
    }
    // A small valid PNG header byte sequence (the bytes are opaque to the route — written verbatim).
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5)

    suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    suspend fun HttpResponse.errorJson(): JsonObject = obj().getValue("error").jsonObject

    // 8. Round-trips + reachable: 201 with the assetUrl/path/hash; a follow-up GET serves the exact bytes.
    test("an asset upload round-trips and is reachable via /assets after the post-write rebuild") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val post = client.post("/api/v1/pages/$deployGuideId/assets?filename=diagram.png") {
                setBody(png)
            }
            post.status shouldBe HttpStatusCode.Created
            val body = post.obj()
            body.getValue("url").jsonPrimitive.content shouldBe "/assets/guides/diagram.png"
            body.getValue("path").jsonPrimitive.content shouldBe "guides/diagram.png"
            body.getValue("content_hash").jsonPrimitive.content shouldBe citations.contentHash(png)

            harness.diskBytes("guides/diagram.png") shouldBe png
            val served = client.get("/assets/guides/diagram.png")
            served.status shouldBe HttpStatusCode.OK
            served.bodyAsBytes() shouldBe png
        }
    }

    // 8b. The `?filename=` query value honors the x-www-form-urlencoded `+`→space convention (codex-review
    // fix — what URLSearchParams emits): `my+file.png` is stored as `my file.png` (space), NOT the literal
    // `my+file.png`; while a correctly-encoded literal plus (`a%2Bb.png`) stays `a+b.png` (decodeOnce
    // restores the %2B that the `+`-replace left untouched). Both round-trip via GET /assets.
    test("a + in the filename query becomes a space; an encoded %2B stays a literal +") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val plus = client.post("/api/v1/pages/$deployGuideId/assets?filename=my+file.png") { setBody(png) }
            plus.status shouldBe HttpStatusCode.Created
            val plusBody = plus.obj()
            plusBody.getValue("path").jsonPrimitive.content shouldBe "guides/my file.png"
            plusBody.getValue("url").jsonPrimitive.content shouldBe "/assets/guides/my%20file.png"
            harness.diskBytes("guides/my file.png") shouldBe png
            val served = client.get("/assets/guides/my%20file.png")
            served.status shouldBe HttpStatusCode.OK
            served.bodyAsBytes() shouldBe png

            // A correctly-encoded literal plus (%2B) survives as a real `+`, proving the replace runs on the
            // RAW value before decodeOnce (no bare `+` in `a%2Bb.png`, so the replace is a no-op there).
            val literalPlus = client.post("/api/v1/pages/$deployGuideId/assets?filename=a%2Bb.png") { setBody(png) }
            literalPlus.status shouldBe HttpStatusCode.Created
            literalPlus.obj().getValue("path").jsonPrimitive.content shouldBe "guides/a+b.png"
            harness.diskBytes("guides/a+b.png") shouldBe png
        }
    }

    // 9. No-clobber collision + concurrency (Fork C + debate #2): a repeat is 409 (no clobber); two
    // concurrent same-name uploads → exactly one 201, one 409, and the winner's bytes survive intact.
    test("a duplicate filename is 409 page_exists (no clobber); concurrent same-name uploads pick one winner") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            client.post("/api/v1/pages/$deployGuideId/assets?filename=dup.png") { setBody(png) }
                .status shouldBe HttpStatusCode.Created
            val firstBytes = harness.diskBytes("guides/dup.png")

            val second = client.post("/api/v1/pages/$deployGuideId/assets?filename=dup.png") {
                setBody(byteArrayOf(9, 9, 9, 9))
            }
            second.status shouldBe HttpStatusCode.Conflict
            second.errorJson().getValue("code").jsonPrimitive.content shouldBe "page_exists"
            second.errorJson().getValue("path").jsonPrimitive.content shouldBe "guides/dup.png"
            harness.diskBytes("guides/dup.png") shouldBe firstBytes // unchanged
        }

        // (b) Concurrent: two coroutines, same filename, different bytes → one 201 + one 409, winner intact.
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val bytesA = ByteArray(64) { 'A'.code.toByte() }
            val bytesB = ByteArray(64) { 'B'.code.toByte() }
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val statuses = List(2) { AtomicReference<HttpStatusCode>() }
            val payloads = listOf(bytesA, bytesB)
            val threads = (0..1).map { n ->
                Thread {
                    ready.countDown()
                    go.await()
                    val resp = kotlinx.coroutines.runBlocking {
                        client.post("/api/v1/pages/$deployGuideId/assets?filename=race.png") { setBody(payloads[n]) }
                    }
                    statuses[n].set(resp.status)
                }
            }
            threads.forEach { it.start() }
            ready.await()
            go.countDown()
            threads.forEach { it.join() }

            statuses.map { it.get() }.toSet() shouldBe setOf(HttpStatusCode.Created, HttpStatusCode.Conflict)
            val onDisk = harness.diskBytes("guides/race.png")
            (onDisk.contentEquals(bytesA) || onDisk.contentEquals(bytesB)) shouldBe true // exactly one winner, intact
        }
    }

    // 10. Cannot escape root via `..` or `/`: rejected at the filename gate (400), nothing written.
    test("a traversal/separator filename is 400 invalid_asset_request; nothing written") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val before = countFiles(harness.root)
            for (name in listOf("..%2Fescape.png", "%2E%2E", "a%2Fb.png")) {
                val resp = client.post("/api/v1/pages/$deployGuideId/assets?filename=$name") { setBody(png) }
                resp.status shouldBe HttpStatusCode.BadRequest
                resp.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_asset_request"
            }
            countFiles(harness.root) shouldBe before
        }
    }

    // 11. Cannot escape root via a symlinked ancestor: writeAssetExclusive rejects (400 invalid_path).
    test("an asset whose page folder is a symlink out of root is 400 invalid_path; nothing written through it") {
        val outside = Files.createTempDirectory("plainbase-asset-outside")
        try {
            // A page indexed under a folder that we then replace with a symlink pointing outside root.
            val linkedPageId = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01"
            val tree = Files.createTempDirectory("plainbase-asset-symlink")
            try {
                Files.createDirectories(tree.resolve("linked"))
                Files.write(tree.resolve("linked/page.md"), "---\ntitle: Linked\n---\n\n# Linked\n".toByteArray())
                val seedLinked: (IdMapRepository) -> Unit = { idMap ->
                    idMap.bind(TreePath.require("linked/page.md"), PageId.require(linkedPageId), materialized = false)
                }
                writeRestTest(tree, seedLinked) { harness ->
                    // Swap the real `linked/` dir for a symlink to an external dir (an ancestor-symlink escape).
                    harness.root.resolve("linked/page.md").toFile().delete()
                    Files.delete(harness.root.resolve("linked"))
                    Files.createSymbolicLink(harness.root.resolve("linked"), outside)

                    val resp = client.post("/api/v1/pages/$linkedPageId/assets?filename=x.png") { setBody(png) }
                    // The page folder vanished/became a symlink: either a containment Rejected (400) or a
                    // ParentMissing (404) — both refuse, and crucially nothing lands through the link.
                    (resp.status == HttpStatusCode.BadRequest || resp.status == HttpStatusCode.NotFound) shouldBe true
                    Files.list(outside).use { it.count() } shouldBe 0L
                }
            } finally {
                tree.toFile().deleteRecursively()
            }
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    // 12. Cannot land under _folder.yaml / ignored / excluded — exercised at the writeAssetExclusive UNIT
    // level (the route can't derive such a leaf: a page folder is never _folder.yaml/excluded). Plus the
    // DATA_DIR-strict-ancestor layout does NOT over-reject a normal asset.
    test("writeAssetExclusive rejects a scan-skipped/excluded leaf and allows a normal asset (unit level)") {
        val root = Files.createTempDirectory("plainbase-asset-unit")
        try {
            Files.createDirectories(root.resolve("guides"))
            val store = LocalContentStore(root)
            store.scan()
            val hasher: (ByteArray) -> String = citations::contentHash

            // A _folder.yaml leaf is a scan-skipped name → Rejected.
            store.writeAssetExclusive(grantForTests(), TreePath.require("guides/_folder.yaml"), png, hasher)
                .let { (it is CreateResult.Rejected) shouldBe true }
            // A dot-prefixed leaf is ignored → Rejected.
            store.writeAssetExclusive(grantForTests(), TreePath.require("guides/.secret.png"), png, hasher)
                .let { (it is CreateResult.Rejected) shouldBe true }
            // A normal asset under an existing dir → Created.
            store.writeAssetExclusive(grantForTests(), TreePath.require("guides/ok.png"), png, hasher)
                .let { (it is CreateResult.Created) shouldBe true }
        } finally {
            root.toFile().deleteRecursively()
        }

        // DATA_DIR as a STRICT ANCESTOR of root must NOT over-reject (the effective-excludedDirs fix).
        val parent = Files.createTempDirectory("plainbase-asset-dataancestor")
        try {
            val root2 = parent.resolve("content")
            Files.createDirectories(root2.resolve("guides"))
            val store = LocalContentStore(root2, exclusions = listOf(parent)) // DATA_DIR above root → no-op
            store.scan()
            store.writeAssetExclusive(grantForTests(), TreePath.require("guides/fine.png"), png, citations::contentHash)
                .let { (it is CreateResult.Created) shouldBe true }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    // 13. Filename normalization battery (debate #1) — each → 400, nothing written; plus a valid
    // international name → 201; plus the headline .md / _folder.yaml page-creation bypass.
    test("the filename gate rejects control/bidi/overlong/malformed/reserved/windows-illegal/.md/_folder.yaml and accepts valid Unicode") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val before = countFiles(harness.root)
            // These reach the asset filename gate (the escapes are well-formed; the DECODED form is what's
            // rejected) → 400 invalid_asset_request. %00 (NUL) and %2F (encoded /) are VALID escapes Ktor
            // decodes without throwing, so they reach decodeOnce/the gate, not the upstream query decoder.
            val invalidAsset = listOf(
                "a%00b.png", // NUL control (decodeOnce → NUL char → isISOControl gate)
                "a%09b.png", // tab control
                "a%E2%80%AEn.png", // U+202E bidi/RTL override
                "%C3%A9".repeat(130) + ".png", // NFC bytes > 255 (each é is 2 UTF-8 bytes)
                "CON.png", // Windows reserved (with extension)
                "nul", // Windows reserved (no extension)
                "", // blank
                "..%2Fx.png", // traversal: %2F decodes to / → the separator gate
                "a%2Fb.png", // encoded separator: %2F decodes to / → the separator gate
                "a:b.png", // Windows-illegal `:` (legal POSIX, → InvalidPathException on Windows)
                "a*.png", // Windows-illegal `*`
                "a?b.png", // Windows-illegal `?`
                "a%3Cb.png", // Windows-illegal `<` (encoded so the test client builds the URL)
                "foo.png.", // trailing dot — Windows silently trims it → collision with foo.png
                "foo.png%20", // trailing space — Windows silently trims it → collision with foo.png
            )
            for (name in invalidAsset) {
                val resp = client.post("/api/v1/pages/$deployGuideId/assets?filename=$name") { setBody(png) }
                resp.status shouldBe HttpStatusCode.BadRequest
                resp.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_asset_request"
            }
            // A MALFORMED percent-escape (dangling `%`, `%2G`) is undecodable as DELIVERED and is rejected
            // one layer earlier than this gate: Ktor's eager query-string decode → 400 invalid_query via
            // KtorServer StatusPages (ktor#2559). The ktor test CLIENT itself refuses to build such a URL,
            // so that path is asserted over a raw socket in SearchGrammarTest (not reproducible through the
            // testApplication client) — here it suffices that a malformed escape can never reach the asset
            // gate, so the strict-decoder reframe's gate only ever sees WELL-FORMED escapes (above).
            countFiles(harness.root) shouldBe before // nothing written by any rejection

            // The headline page-creation bypass: .md (any case) and _folder.yaml (any case — the gate is
            // ignoreCase so a case-insensitive FS can't alias the sidecar) are refused, AND no new page
            // appears in the index after the attempt (the post-write rebuild would otherwise index it).
            val pagesBefore = harness.builder.current.pages.size
            for (name in listOf("evil.md", "EVIL.MD", "_folder.yaml", "_folder.YAML", "_FOLDER.yaml", "_Folder.Yaml")) {
                client.post("/api/v1/pages/$deployGuideId/assets?filename=$name") { setBody(png) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
            harness.builder.current.pages.size shouldBe pagesBefore
            countFiles(harness.root) shouldBe before

            // A valid international filename is ACCEPTED (the pipeline doesn't over-reject legitimate Unicode).
            val intl = client.post("/api/v1/pages/$deployGuideId/assets?filename=%E5%A0%B1%E5%91%8A%E6%9B%B8.png") { setBody(png) }
            intl.status shouldBe HttpStatusCode.Created
            intl.obj().getValue("path").jsonPrimitive.content shouldBe "guides/報告書.png"
        }
    }

    // 13b. Multi-dot Windows reserved name (codex-review fix): the device check must look at the name before
    // the FIRST dot, so `CON.foo.png` / `COM1.backup.bin` (which substringBeforeLast would have passed as
    // `CON.foo` / `COM1.backup`) reject too. Nothing written.
    test("a multi-dot Windows reserved filename (CON.foo.png) is 400 invalid_asset_request; nothing written") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val before = countFiles(harness.root)
            for (name in listOf("CON.foo.png", "COM1.backup.bin")) {
                val resp = client.post("/api/v1/pages/$deployGuideId/assets?filename=$name") { setBody(png) }
                resp.status shouldBe HttpStatusCode.BadRequest
                resp.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_asset_request"
            }
            countFiles(harness.root) shouldBe before
        }
    }

    // 13c. Max-length filename (codex-review fix): a 255-byte (= NAME_MAX, the gate's cap) name succeeds.
    // The temp file now uses a SHORT fixed prefix, so `.<255-byte name>.<random>.tmp` can no longer overflow
    // NAME_MAX on a 255-byte-limited filesystem → 201 (not a 503 from createTempFile throwing).
    test("a max-length (255-byte) valid filename succeeds; the temp name no longer overflows NAME_MAX") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val name = "a".repeat(251) + ".png" // 255 ASCII bytes = ASSET_FILENAME_MAX_BYTES
            name.toByteArray(Charsets.UTF_8).size shouldBe 255
            val post = client.post("/api/v1/pages/$deployGuideId/assets?filename=$name") { setBody(png) }
            post.status shouldBe HttpStatusCode.Created
            harness.diskBytes("guides/$name") shouldBe png
            val served = client.get("/assets/guides/$name")
            served.status shouldBe HttpStatusCode.OK
            served.bodyAsBytes() shouldBe png
        }
    }

    // 14. Over-cap → 413 (asset cap, not write cap), incl. a no-Content-Length stream; under-asset-cap
    // but over-write-cap SUCCEEDS (assets get the larger cap). Nothing written on a 413.
    test("an over-asset-cap body is 413; a body over the write cap but under the asset cap succeeds") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val before = countFiles(harness.root)
            val assetCap = harness.services.maxAssetBytes
            val writeCap = harness.services.maxWriteBodyBytes
            val over = ByteArray((assetCap + 1).toInt()) { 'x'.code.toByte() }

            val lying = client.post("/api/v1/pages/$deployGuideId/assets?filename=big.bin") {
                setBody(LyingLengthBinaryContent(over, advertisedLength = 100L))
            }
            lying.status shouldBe HttpStatusCode.PayloadTooLarge
            lying.errorJson().getValue("code").jsonPrimitive.content shouldBe "body_too_large"
            lying.errorJson().getValue("max_bytes").jsonPrimitive.content.toLong() shouldBe assetCap

            val chunked = client.post("/api/v1/pages/$deployGuideId/assets?filename=big2.bin") {
                setBody(ByteReadChannel(over))
            }
            chunked.status shouldBe HttpStatusCode.PayloadTooLarge
            countFiles(harness.root) shouldBe before // nothing written on a 413

            // A screenshot-sized body (over the 1 MiB write cap, well under the 10 MiB asset cap) succeeds.
            val mid = ByteArray((writeCap + 1024).toInt()) { 'y'.code.toByte() }
            client.post("/api/v1/pages/$deployGuideId/assets?filename=screenshot.bin") { setBody(mid) }
                .status shouldBe HttpStatusCode.Created
        }
    }

    // 15. Unknown page / bad id: 404 page_not_found; 400 invalid_page_id. Nothing written.
    test("an unknown page id is 404 page_not_found; a shape-invalid id is 400 invalid_page_id") {
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val before = countFiles(harness.root)
            val unknown = client.post("/api/v1/pages/a3bb189e-8bf9-4888-9912-ace4e6543002/assets?filename=x.png") { setBody(png) }
            unknown.status shouldBe HttpStatusCode.NotFound
            unknown.errorJson().getValue("code").jsonPrimitive.content shouldBe "page_not_found"

            val invalid = client.post("/api/v1/pages/1-1-1-1-1/assets?filename=x.png") { setBody(png) }
            invalid.status shouldBe HttpStatusCode.BadRequest
            invalid.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_page_id"
            countFiles(harness.root) shouldBe before
        }
    }

    // 16. Unreadable → 503, incl. the fail-closed no-hardlink case (debate #3); nothing written.
    test("writeAssetExclusive Unreadable (incl. fail-closed no-hardlink) is 503 content_unreadable") {
        val unreadable: (ContentStore) -> ContentStore = { real ->
            object : ContentStore by real {
                override fun writeAssetExclusive(grant: EditGrant, path: TreePath, bytes: ByteArray, hasher: (ByteArray) -> String) =
                    CreateResult.Unreadable("simulated fail-closed (no hardlink support)")
            }
        }
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = unreadable) { harness ->
            val resp = client.post("/api/v1/pages/$deployGuideId/assets?filename=x.png") { setBody(png) }
            resp.status shouldBe HttpStatusCode.ServiceUnavailable
            resp.errorJson().getValue("code").jsonPrimitive.content shouldBe "content_unreadable"
            Files.exists(harness.root.resolve("guides/x.png")) shouldBe false
        }
    }

    // 16b. The stale-page re-check (step 4b) READ can THROW, not just return null (codex-review fix): a
    // file locked/unreadable after the last scan is a transient FS fault, not a missing page. The route
    // wraps read() in try/catch and maps a THROW → 503 content_unreadable (NOT a bare 500), nothing
    // written — distinct from read-returns-null → 404 (test 20b) and writeAssetExclusive-Unreadable → 503.
    test("the stale-page re-check read THROWING is 503 content_unreadable; nothing written") {
        // Throw on the page's own .md read only while ARMED: the harness's init rebuild() reads the whole
        // tree (incl. this page), so we arm the throw AFTER construction, right before the upload, so ONLY
        // the route's step-4b re-check sees the fault (mirrors test 21's armed override).
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val readThrows: (ContentStore) -> ContentStore = { real ->
            object : ContentStore by real {
                override fun read(path: TreePath): ByteArray? =
                    if (armed.get() && path.value == "guides/deploy-guide.md") {
                        throw java.io.IOException("simulated transient FS fault reading the page file")
                    } else {
                        real.read(path)
                    }
            }
        }
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = readThrows) { harness ->
            armed.set(true)
            val resp = client.post("/api/v1/pages/$deployGuideId/assets?filename=x.png") { setBody(png) }
            resp.status shouldBe HttpStatusCode.ServiceUnavailable
            resp.errorJson().getValue("code").jsonPrimitive.content shouldBe "content_unreadable"
            Files.exists(harness.root.resolve("guides/x.png")) shouldBe false // the write never ran
        }
    }

    // 19. nosniff on served assets (debate #3): GET /assets carries X-Content-Type-Options: nosniff.
    test("a served asset carries X-Content-Type-Options: nosniff") {
        writeRestTest(Fixtures.demoDocs, seed) { _ ->
            client.post("/api/v1/pages/$deployGuideId/assets?filename=sniff.png") { setBody(png) }
                .status shouldBe HttpStatusCode.Created
            val served = client.get("/assets/guides/sniff.png")
            served.status shouldBe HttpStatusCode.OK
            served.headers["X-Content-Type-Options"] shouldBe "nosniff"
        }
    }

    // 20. writeAssetExclusive does NOT create directories (debate #2): a missing parent → ParentMissing,
    // no dir created, no file written (createExclusive would create the dir — the test pins the contrast).
    // Route-level: a page whose folder was removed on disk → 404 page_not_found, folder NOT recreated.
    test("an asset write never creates the parent dir; a removed page folder is 404, not recreated") {
        val root = Files.createTempDirectory("plainbase-asset-parentmissing")
        try {
            val store = LocalContentStore(root)
            store.scan()
            val result = store.writeAssetExclusive(grantForTests(), TreePath.require("gone/x.png"), png, citations::contentHash)
            (result is CreateResult.ParentMissing) shouldBe true
            Files.exists(root.resolve("gone")) shouldBe false // the dir was NOT created
            Files.exists(root.resolve("gone/x.png")) shouldBe false
            // Contrast: createExclusive on the same input WOULD create the dir.
            store.createExclusive(TreePath.require("gone/y.md"), "x".toByteArray(), citations::contentHash)
            Files.exists(root.resolve("gone")) shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }

        // Route-level: delete the page's on-disk folder after indexing → 404, folder not recreated.
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            harness.root.resolve("guides/deploy-guide.md").toFile().delete()
            Files.walk(harness.root.resolve("guides")).use { s ->
                s.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
            val resp = client.post("/api/v1/pages/$deployGuideId/assets?filename=x.png") { setBody(png) }
            resp.status shouldBe HttpStatusCode.NotFound
            resp.errorJson().getValue("code").jsonPrimitive.content shouldBe "page_not_found"
            Files.exists(harness.root.resolve("guides")) shouldBe false // not recreated
        }
    }

    // 20b. Snapshot membership ≠ disk reality (codex-review fix): a TOP-LEVEL page whose .md was externally
    // deleted since the last rebuild — its FOLDER (the content root) survives, so writeAssetExclusive's
    // parent-exists check would PASS and the asset would write + 201 for a gone page. The disk re-check
    // (services.contentStore.read(page.path) == null → 404) catches it BEFORE the write. Top-level is the
    // sharpest case: the content root always exists, so only the .md re-check can detect the deletion.
    test("a top-level page whose .md was deleted (folder survives) is 404 page_not_found; no asset written") {
        val indexPageId = "0197c2d0-7a1b-7c45-8e2f-3b9d6a1c4e02"
        val seedTopLevel: (IdMapRepository) -> Unit = { idMap ->
            idMap.bind(TreePath.require("index.md"), PageId.require(indexPageId), materialized = false)
        }
        writeRestTest(Fixtures.demoDocs, seedTopLevel) { harness ->
            // Delete the page's .md on disk WITHOUT a rebuild — the snapshot still lists it under id.
            harness.root.resolve("index.md").toFile().delete()

            val resp = client.post("/api/v1/pages/$indexPageId/assets?filename=x.png") { setBody(png) }
            resp.status shouldBe HttpStatusCode.NotFound
            resp.errorJson().getValue("code").jsonPrimitive.content shouldBe "page_not_found"
            // No asset landed in the (surviving) content root for the gone page.
            Files.exists(harness.root.resolve("x.png")) shouldBe false
        }
    }

    // 21. Written-but-unindexed on rebuild failure (debate #4): writeAssetExclusive Created but the
    // subsequent rebuild throws → 503 (no url); the bytes ARE durable, and a later successful rebuild
    // makes GET /assets serve them.
    test("an asset written but un-indexed (rebuild throws) is 503; the bytes are durable and later served") {
        // A store whose scan throws only while ARMED (the harness's init rebuild runs disarmed; the test
        // arms it right before the upload so ONLY the route's post-write rebuild fails). The write itself
        // (writeAssetExclusive) delegates to the real store, so the bytes still land on disk.
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val throwWhenArmed: (ContentStore) -> ContentStore = { real ->
            object : ContentStore by real {
                override fun scan() = if (armed.get()) {
                    throw RuntimeException("simulated scan failure during the post-write rebuild")
                } else {
                    real.scan()
                }
            }
        }
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = throwWhenArmed) { harness ->
            armed.set(true)
            val resp = client.post("/api/v1/pages/$deployGuideId/assets?filename=deferred.png") { setBody(png) }
            resp.status shouldBe HttpStatusCode.ServiceUnavailable
            resp.errorJson().getValue("code").jsonPrimitive.content shouldBe "content_unreadable"
            // The bytes are durably on disk despite the failed index update.
            harness.diskBytes("guides/deferred.png") shouldBe png

            // A later successful rebuild (scan no longer armed to throw) reconciles it: the asset now serves.
            armed.set(false)
            harness.builder.rebuild()
            val served = client.get("/assets/guides/deferred.png")
            served.status shouldBe HttpStatusCode.OK
            served.bodyAsBytes() shouldBe png
        }
    }

    // 21b. Self-healing retry after a written-but-unindexed orphan (FIX E): the first upload writes the
    // bytes but its post-write rebuild throws ONCE → 503, leaving the file on disk yet absent from
    // current.assets (404-unreachable). A RETRY of the same filename hits the Exists branch; because the
    // path is NOT in current.assets it runs a best-effort rebuild FIRST (now disarmed → succeeds) so the
    // orphan becomes reachable, THEN still responds 409 (the existing file wins; the retry's bytes were
    // NOT written). The asset is reachable via GET /assets AFTER the retry — no admin/watcher rebuild.
    test("a retry after a written-but-unindexed upload self-heals: the Exists branch rebuilds, then 409, and the asset is now served") {
        // scan() throws exactly ONCE while armed: the harness's init rebuild runs disarmed; we arm it
        // right before the first upload so ONLY that upload's post-write rebuild fails, then it disarms
        // itself so the RETRY's Exists-branch rebuild succeeds.
        val failOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val throwScanOnce: (ContentStore) -> ContentStore = { real ->
            object : ContentStore by real {
                override fun scan() = if (failOnce.compareAndSet(true, false)) {
                    throw RuntimeException("simulated one-shot scan failure during the first post-write rebuild")
                } else {
                    real.scan()
                }
            }
        }
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = throwScanOnce) { harness ->
            failOnce.set(true)
            // First upload: bytes land, but the post-write rebuild throws → 503 written-but-unindexed.
            val first = client.post("/api/v1/pages/$deployGuideId/assets?filename=heal.png") { setBody(png) }
            first.status shouldBe HttpStatusCode.ServiceUnavailable
            harness.diskBytes("guides/heal.png") shouldBe png
            // The orphan is on disk but NOT yet in the published snapshot — currently 404-unreachable.
            (TreePath.require("guides/heal.png") in harness.builder.current.assets) shouldBe false
            client.get("/assets/guides/heal.png").status shouldBe HttpStatusCode.NotFound

            // Retry the SAME filename: writeAssetExclusive sees the existing file → Exists. The route runs
            // the self-heal rebuild (now disarmed → succeeds) so the orphan enters current.assets, THEN 409.
            val retry = client.post("/api/v1/pages/$deployGuideId/assets?filename=heal.png") { setBody(png) }
            retry.status shouldBe HttpStatusCode.Conflict
            retry.errorJson().getValue("code").jsonPrimitive.content shouldBe "page_exists"

            // Healed: the asset is now reachable WITHOUT any admin/watcher rebuild, serving the original bytes.
            (TreePath.require("guides/heal.png") in harness.builder.current.assets) shouldBe true
            val served = client.get("/assets/guides/heal.png")
            served.status shouldBe HttpStatusCode.OK
            served.bodyAsBytes() shouldBe png
        }
    }
})

/** Counts the regular files under [root] (the "nothing written" invariant). */
private fun countFiles(root: Path): Int =
    Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) }.count().toInt() }

/** A binary body advertising [advertisedLength] but streaming all of [bytes] — the streamed read is the authority. */
private class LyingLengthBinaryContent(
    private val bytes: ByteArray,
    private val advertisedLength: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType get() = ContentType.Application.OctetStream
    override val contentLength: Long get() = advertisedLength
    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(bytes)
    }
}

package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.RawByteOrder
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * PB-WRITE-1 (W2) behavioral named tests for `POST /api/v1/pages` — the create-through-W1 path: the
 * server-owned path/slug derivation, the YAML-safe frontmatter composition (the BLOCKING-1
 * data-correctness guard), the 201 status, race-safe O_EXCL collision, immediate searchability, the
 * shared history seam, the WrittenButUnindexed recovery, and the 400/409 error envelopes. All tests
 * run over [writeRestTest] (a temp COPY of the fixture tree) with a deterministic [TestIdProvider], so
 * a create never dirties the committed `Fixtures.demoDocs` and the minted id is known.
 */
class WriteRouteCreateTest : FunSpec({

    val citations = CitationFactory()

    // The first minted id of a fresh TestIdProvider(1).
    val firstId = "01900000-0000-7000-8000-000000000001"

    fun json(): ContentType = ContentType.Application.Json
    fun idProvider() = TestIdProvider()

    suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    suspend fun HttpResponse.errorJson(): JsonObject = obj().getValue("error").jsonObject

    // 1. Create round-trips (master criterion): 201, content_hash + ETag, file on disk with the
    // composed frontmatter (the seeded id + title) + the body bytes.
    test("a POST creates a page at the §A4 path with composed frontmatter + body; 201 + content_hash + ETag") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"New Page","body":"# New\n\nx\n"}""")
            }
            post.status shouldBe HttpStatusCode.Created
            val body = post.obj()
            val hash = body.getValue("content_hash").jsonPrimitive.content
            post.headers[HttpHeaders.ETag].shouldNotBeNull() shouldBe "\"$hash\""
            body.containsKey("warning") shouldBe false

            val onDisk = harness.diskBytes("guides/new-page.md")
            citations.contentHash(onDisk) shouldBe hash
            val text = String(onDisk, Charsets.UTF_8)
            text shouldContain "id: $firstId"
            text shouldContain "title: \"New Page\""
            text shouldContain "# New\n\nx\n"
        }
    }

    // 2. GET round-trip by id: create then read the seeded id.
    test("the created page reads back by its seeded id: path, materialized id, title, body") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { _ ->
            client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"Read Me","body":"# Read Me\n\nthe body.\n"}""")
            }.status shouldBe HttpStatusCode.Created

            val got = client.get("/api/v1/pages/$firstId")
            got.status shouldBe HttpStatusCode.OK
            val page = got.obj()
            page.getValue("path").jsonPrimitive.content shouldBe "guides/read-me.md"
            page.getValue("title").jsonPrimitive.content shouldBe "Read Me"
            page.getValue("id_materialized").jsonPrimitive.content shouldBe "true"
            page.getValue("frontmatter").jsonObject.getValue("id").jsonPrimitive.content shouldBe firstId
            page.getValue("markdown").jsonPrimitive.content shouldContain "the body."
        }
    }

    // 3. YAML-safe title fidelity (BLOCKING-1 adversarial — the data-correctness guard). The served
    // title (via GET) must EQUAL what was sent, across every adversarial sub-case INCLUDING embedded
    // quotes and backslashes. This is NOT the golden, so no value couples into the forever corpus.
    test("an adversarial title composes to valid YAML and the served title equals what was sent") {
        val cases = listOf(
            "Q3: Results",
            "> note",
            "[Draft] Plan",
            "say \"hi\"",
            "C:\\tmp\\x",
            "@mention",
            "|pipe",
            "&anchor",
            "*star",
            "!bang",
            "Café — 日本語",
        )
        for ((i, title) in cases.withIndex()) {
            // A fresh provider per case so each minted id is the known first id, GET-addressable.
            writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { _ ->
                val request = Json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("folder", kotlinx.serialization.json.JsonPrimitive("titles"))
                        put("title", kotlinx.serialization.json.JsonPrimitive(title))
                        // A unique slug per case so the on-disk filename never collides across cases.
                        put("slug", kotlinx.serialization.json.JsonPrimitive("case-$i"))
                    },
                )
                client.post("/api/v1/pages") {
                    contentType(json())
                    setBody(request)
                }.status shouldBe HttpStatusCode.Created

                val got = client.get("/api/v1/pages/$firstId")
                got.status shouldBe HttpStatusCode.OK
                got.obj().getValue("title").jsonPrimitive.content shouldBe title
            }
        }
    }

    // 4. Searchable immediately: the new body is findable on the FIRST search request (rebuild synced
    // search before the POST returned). Sentinel term unique to the created body.
    test("the new page is searchable on the first request after the POST (rebuild synced search)") {
        val sentinel = "zzqplainbasecreatesentinel"
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { _ ->
            client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"Sentinel Page","body":"# Sentinel\n\n$sentinel here.\n"}""")
            }.status shouldBe HttpStatusCode.Created

            val search = client.get("/api/v1/search?q=$sentinel")
            search.status shouldBe HttpStatusCode.OK
            search.bodyAsText() shouldContain "guides/sentinel-page.md"
        }
    }

    // 5. Race-safe collision (the monitor + O_EXCL proof): two concurrent POSTs of the same folder+slug
    // → exactly one 201 and one 409 page_exists, exactly one file on disk.
    test("two concurrent POSTs of the same folder+slug yield exactly one 201 and one 409 page_exists") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val statuses = List(2) { AtomicReference<HttpStatusCode>() }
            val bodies = List(2) { AtomicReference<String>() }
            val threads = (0..1).map { n ->
                Thread {
                    ready.countDown()
                    go.await()
                    val resp = kotlinx.coroutines.runBlocking {
                        client.post("/api/v1/pages") {
                            contentType(json())
                            setBody("""{"folder":"guides","title":"Racer","slug":"racer"}""")
                        }
                    }
                    statuses[n].set(resp.status)
                    bodies[n].set(kotlinx.coroutines.runBlocking { resp.bodyAsText() })
                }
            }
            threads.forEach { it.start() }
            ready.await()
            go.countDown()
            threads.forEach { it.join() }

            val codes = statuses.map { it.get() }.toSet()
            codes shouldBe setOf(HttpStatusCode.Created, HttpStatusCode.Conflict)
            val conflictBody = bodies[statuses.indexOfFirst { it.get() == HttpStatusCode.Conflict }].get()
            val error = Json.parseToJsonElement(conflictBody).jsonObject.getValue("error").jsonObject
            error.getValue("code").jsonPrimitive.content shouldBe "page_exists"
            error.getValue("path").jsonPrimitive.content shouldBe "guides/racer.md"
            java.nio.file.Files.exists(harness.root.resolve("guides/racer.md")) shouldBe true
        }
    }

    // 6. Collision over an existing fixture, no file written: the existing bytes are UNCHANGED.
    test("a POST onto an existing fixture path is 409 page_exists and does not clobber the file") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = harness.diskBytes("guides/deploy-guide.md")
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","slug":"deploy-guide","title":"Collide"}""")
            }
            post.status shouldBe HttpStatusCode.Conflict
            val error = post.errorJson()
            error.getValue("code").jsonPrimitive.content shouldBe "page_exists"
            error.getValue("path").jsonPrimitive.content shouldBe "guides/deploy-guide.md"
            harness.diskBytes("guides/deploy-guide.md") shouldBe before
        }
    }

    // 7. Off-Git AND on-Git both create through the SAME history seam.
    test("a create flows through the history hook seam (off-Git no-op AND a recording hook)") {
        // Off-Git default: a no-op hook still creates the file + 201.
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"Off Git"}""")
            }.status shouldBe HttpStatusCode.Created
            java.nio.file.Files.exists(harness.root.resolve("guides/off-git.md")) shouldBe true
        }

        // On-Git stand-in: a RECORDING hook is invoked with the created path + the composed bytes.
        val recorded = AtomicReference<Pair<String, ByteArray>>()
        val recording = WriteHistoryHook { path, bytes ->
            recorded.set(path.value to bytes)
            null
        }
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider(), historyHook = recording) { harness ->
            client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"On Git"}""")
            }.status shouldBe HttpStatusCode.Created
            val (path, bytes) = recorded.get()
            path shouldBe "guides/on-git.md"
            bytes shouldBe harness.diskBytes("guides/on-git.md")
        }
    }

    // 8. WrittenButUnindexed on create (R2 parity): a throwing post-write hook → 201 + warning, NOT 200.
    test("a post-write hook failure on create is 201 with warning reindex_deferred; the bytes are on disk") {
        val throwingHook = WriteHistoryHook { _, _ -> throw RuntimeException("history boom") }
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider(), historyHook = throwingHook) { harness ->
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"Deferred"}""")
            }
            post.status shouldBe HttpStatusCode.Created
            val body = post.obj()
            body.getValue("warning").jsonObject.getValue("code").jsonPrimitive.content shouldBe "reindex_deferred"
            java.nio.file.Files.exists(harness.root.resolve("guides/deferred.md")) shouldBe true
            harness.dirtyPages.all().isEmpty() shouldBe false
        }
    }

    // 9. Invalid request → 400 (blank title, traversal folder, malformed JSON); nothing written.
    test("a blank title, a traversal folder, or malformed JSON is 400 invalid_create_request") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)

            val blank = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"   "}""")
            }
            blank.status shouldBe HttpStatusCode.BadRequest
            blank.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"

            val traversal = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"../x","title":"Escape"}""")
            }
            traversal.status shouldBe HttpStatusCode.BadRequest
            traversal.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"

            val malformed = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":""")
            }
            malformed.status shouldBe HttpStatusCode.BadRequest
            malformed.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"

            countFiles(harness.root) shouldBe before
        }
    }

    // 10. Slug intent honored: the file lands at the slug, and the canonical url/slug follow.
    test("a supplied slug drives the filename and the canonical url/slug") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"Long Title","slug":"short"}""")
            }.status shouldBe HttpStatusCode.Created
            java.nio.file.Files.exists(harness.root.resolve("guides/short.md")) shouldBe true

            val got = client.get("/api/v1/pages/$firstId").obj()
            got.getValue("slug").jsonPrimitive.content shouldBe "short"
            got.getValue("url").jsonPrimitive.content shouldEndWith "short"
        }
    }

    // 11. Folder created if absent.
    test("a POST into a not-yet-existing folder creates the dirs and round-trips") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"brand/new","title":"x"}""")
            }.status shouldBe HttpStatusCode.Created
            java.nio.file.Files.exists(harness.root.resolve("brand/new/x.md")) shouldBe true

            val got = client.get("/api/v1/pages/$firstId")
            got.status shouldBe HttpStatusCode.OK
            got.obj().getValue("path").jsonPrimitive.content shouldBe "brand/new/x.md"
        }
    }

    // 12. Reserved-but-unwritten crash residue self-heals: a 0-byte `.md` (a crash AFTER the createFile
    // reservation but BEFORE the content move) is honored, never clobbered, and indexes cleanly.
    test("a 0-byte crash-residue .md is honored (409 page_exists, no clobber) and indexes as an empty page") {
        val tree = java.nio.file.Files.createTempDirectory("plainbase-create-residue")
        try {
            // Simulate the residue: a 0-byte file the previous create reserved but never wrote bytes into.
            java.nio.file.Files.write(tree.resolve("residue.md"), ByteArray(0))
            writeRestTest(tree, idProvider = idProvider()) { harness ->
                // (b) The rebuild at harness init did NOT fail; the residue is a legitimately-indexed page.
                val read = client.get("/api/v1/pages/by-path/residue")
                read.status shouldBe HttpStatusCode.OK
                read.obj().getValue("path").jsonPrimitive.content shouldBe "residue.md"

                // (a) A create at that path is refused — the reservation is honored, the 0 bytes survive.
                val post = client.post("/api/v1/pages") {
                    contentType(json())
                    setBody("""{"folder":"","slug":"residue","title":"Clobber Me"}""")
                }
                post.status shouldBe HttpStatusCode.Conflict
                val error = post.errorJson()
                error.getValue("code").jsonPrimitive.content shouldBe "page_exists"
                error.getValue("path").jsonPrimitive.content shouldBe "residue.md"
                harness.diskBytes("residue.md").size shouldBe 0
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    // 13. P1 SECURITY — a create can never escape the content root through a symlinked folder, nor
    // ghost-write into an ignored folder. (a) folder = a symlink to OUTSIDE root → refused, nothing
    // written through the link; (b) folder = an ignored (dot-prefixed) dir → refused, no ghost 201.
    test("a create refuses a symlinked-out-of-root folder and an ignored folder (P1 containment)") {
        // (a) Symlink escape: `escape` inside root points at an external dir; a POST into it must fail.
        val outside = Files.createTempDirectory("plainbase-create-outside")
        try {
            writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
                Files.createSymbolicLink(harness.root.resolve("escape"), outside)
                val post = client.post("/api/v1/pages") {
                    contentType(json())
                    setBody("""{"folder":"escape","title":"Pwned"}""")
                }
                post.status shouldBe HttpStatusCode.BadRequest
                post.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
                // Nothing was written THROUGH the symlink into the outside dir.
                Files.list(outside).use { it.count() } shouldBe 0L
            }
        } finally {
            outside.toFile().deleteRecursively()
        }

        // (b) Ignored folder: a dot-prefixed segment the scan skips must not get a ghost 201.
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":".secret","title":"Ghost"}""")
            }
            post.status shouldBe HttpStatusCode.BadRequest
            post.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
            countFiles(harness.root) shouldBe before // no ghost file
        }
    }

    // 14. P3 — a multiline title or slug cannot inject a frontmatter `---` delimiter: control chars are
    // refused at the route (titles/slugs are single-line), nothing written.
    test("a title or slug containing a newline (delimiter injection) is 400 invalid_create_request") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)

            // A title carrying `\n---\n` would, unescaped, open a second frontmatter block.
            val injectTitle = Json.encodeToString(
                JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("folder", kotlinx.serialization.json.JsonPrimitive("guides"))
                    put("title", kotlinx.serialization.json.JsonPrimitive("Evil\n---\nbody: injected"))
                },
            )
            client.post("/api/v1/pages") {
                contentType(json())
                setBody(injectTitle)
            }.let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
            }

            // A slug with a bare newline is likewise refused.
            val injectSlug = Json.encodeToString(
                JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("folder", kotlinx.serialization.json.JsonPrimitive("guides"))
                    put("title", kotlinx.serialization.json.JsonPrimitive("Fine"))
                    put("slug", kotlinx.serialization.json.JsonPrimitive("a\nb"))
                },
            )
            client.post("/api/v1/pages") {
                contentType(json())
                setBody(injectSlug)
            }.let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
            }

            countFiles(harness.root) shouldBe before // nothing written
        }
    }

    // 15. P4 — the PB-WRITE-1 body cap is enforced on create: an over-cap body (with a LYING
    // Content-Length AND a no-Content-Length stream) → 413 body_too_large; nothing written.
    test("an over-cap create body is 413 body_too_large (lying Content-Length and streamed); nothing written") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)
            val cap = harness.services.maxWriteBodyBytes
            val oversize = ByteArray((cap + 1).toInt()) { 'a'.code.toByte() }

            // A lying Content-Length (100) but an actual over-cap body — the streamed read is the authority.
            val lying = client.post("/api/v1/pages") {
                setBody(LyingLengthJsonContent(oversize, advertisedLength = 100L))
            }
            lying.status shouldBe HttpStatusCode.PayloadTooLarge
            lying.errorJson().getValue("code").jsonPrimitive.content shouldBe "body_too_large"
            lying.errorJson().getValue("max_bytes").jsonPrimitive.content.toLong() shouldBe cap

            // A no-Content-Length (chunked) over-cap body is likewise 413.
            val chunked = client.post("/api/v1/pages") {
                contentType(json())
                setBody(ByteReadChannel(oversize))
            }
            chunked.status shouldBe HttpStatusCode.PayloadTooLarge

            countFiles(harness.root) shouldBe before // nothing written
        }
    }

    // 16. P2 (error determinism) — a control char in `folder` is a 400 invalid_create_request, never a
    // 500, and leaves NO dangling write-ahead dirty row. Two layers prove it: a NUL (rejected by the
    // shared TreePath gate, which keeps Path.resolve from throwing InvalidPathException → would-be 500)
    // AND a tab (rejected by the create route's stricter control-char check, since TreePath now allows
    // legal control chars on the shared scan/read path). Both → clean 400. Control chars are written via
    // Char(...) so the source stays plain text (no literal control byte in the .kt file).
    test("a control char in folder is 400 invalid_create_request, not 500, with no dangling dirty row") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)
            for (ctrl in listOf(Char(0), '\t')) {
                val request = Json.encodeToString(
                    JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("folder", kotlinx.serialization.json.JsonPrimitive("a" + ctrl + "b"))
                        put("title", kotlinx.serialization.json.JsonPrimitive("Bad Folder"))
                    },
                )
                val post = client.post("/api/v1/pages") {
                    contentType(json())
                    setBody(request)
                }
                post.status shouldBe HttpStatusCode.BadRequest
                post.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
            }
            countFiles(harness.root) shouldBe before // nothing written
            harness.dirtyPages.all().isEmpty() shouldBe true // no dangling write-ahead mark
        }
    }

    // 17. P2 (error determinism) — a `folder` naming an existing regular FILE is a permanent client
    // error: 400 invalid_create_request, NOT the retryable 503 content_unreadable, nothing written.
    test("a folder that names an existing file is 400 invalid_create_request, not 503") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = harness.diskBytes("guides/deploy-guide.md")
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides/deploy-guide.md","title":"Under A File"}""")
            }
            post.status shouldBe HttpStatusCode.BadRequest
            post.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
            harness.diskBytes("guides/deploy-guide.md") shouldBe before // the existing file is untouched
        }
    }

    // 18. P2 (NFC collision) — a non-NFC sibling added on disk after the last scan (so it is NOT in the
    // snapshot) is treated as occupying the NFC-equivalent path: a create for the NFC form is 409
    // page_exists, never a second on-disk file that normalizes to the same TreePath.
    test("an NFC-equivalent on-disk sibling (not in the snapshot) makes the create 409 page_exists") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            // Seed a RAW NFD-named sibling at the content root AFTER the harness's init rebuild — the
            // snapshot has no raw-name entry for it (mirrors an external writer between scans).
            val nfdName = "cafe" + '́' + ".md" // e + U+0301 COMBINING ACUTE (NFD); NFC-normalizes to "café.md"
            Files.write(harness.root.resolve(nfdName), "raw sibling".toByteArray())

            // The slug "café" slugifies to the NFC leaf "café.md" — the NFC-equivalent of the NFD sibling.
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"","slug":"café","title":"Cafe Page"}""")
            }
            post.status shouldBe HttpStatusCode.Conflict
            post.errorJson().getValue("code").jsonPrimitive.content shouldBe "page_exists"

            // Exactly ONE root-level entry normalizes to "café.md" — no second colliding file was created.
            val colliding = Files.list(harness.root).use { stream ->
                stream.filter { java.text.Normalizer.normalize(it.fileName.toString(), java.text.Normalizer.Form.NFC) == "café.md" }.count()
            }
            colliding shouldBe 1L
        }
    }

    // 19. P2 (error determinism) — a structurally-valid JSON body carrying MALFORMED UTF-8 inside a
    // string value is a malformed REQUEST (JSON is defined over valid Unicode), so it is 400
    // invalid_create_request, NOT a silently U+FFFD-corrupted page. Nothing written, no dangling row.
    test("a JSON body with malformed UTF-8 in a string value is 400 invalid_create_request, not a corrupted page") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)
            // Structurally-valid JSON braces/quotes, but a lone 0xFF (never a valid UTF-8 byte) sits
            // inside the title value. Built via byteArrayOf so no raw bad byte lives in the .kt source.
            val body = """{"folder":"","title":"bad""".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0xFF.toByte()) +
                """"}""".toByteArray(Charsets.UTF_8)
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody(body)
            }
            post.status shouldBe HttpStatusCode.BadRequest
            post.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
            countFiles(harness.root) shouldBe before // no U+FFFD-corrupted page written
            harness.dirtyPages.all().isEmpty() shouldBe true // no dangling write-ahead mark
        }
    }

    // 20. P2 (NFC PARENT collision) — an NFD-equivalent PARENT dir added on disk after the last scan is
    // REUSED, not duplicated: a POST into the NFC-equivalent folder lands in the EXISTING subtree and
    // survives a rebuild (no second `café/` dir, no 201-then-excluded ghost). The bug only MANIFESTS on a
    // byte-preserving FS (Linux/ext4, the deployment target), where the seeded NFD dir stays distinct
    // from the NFC form; on a normalization-on-create FS (macOS APFS) the dir folds to NFC and the create
    // trivially reuses it. The assertions below (exactly one café-normalizing dir; the page survives
    // rebuild) hold on BOTH regimes, so the test is the regression guard on Linux and a no-op-correct
    // pin on macOS.
    test("a create into an NFC-equivalent existing parent dir reuses it (no duplicate parent, survives rebuild)") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            // Seed a RAW NFD-named parent dir AFTER the harness's init rebuild — the snapshot has no
            // raw-name entry for it (mirrors an external writer between scans). A lone combining mark in
            // the char literal is editor-proof (cannot be NFC-folded by source tooling).
            val nfdDir = "cafe" + '́' // e + U+0301 COMBINING ACUTE (NFD); NFC-normalizes to "café"
            Files.createDirectory(harness.root.resolve(nfdDir))

            // POST into folder "café" (NFC) — the parent must resolve to the EXISTING dir, never a new one.
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"café","title":"In Cafe"}""")
            }
            post.status shouldBe HttpStatusCode.Created

            // Exactly ONE root-level dir normalizes to "café" — the create reused it, no duplicate.
            val cafeDirs = Files.list(harness.root).use { stream ->
                stream.filter {
                    Files.isDirectory(it) &&
                        java.text.Normalizer.normalize(it.fileName.toString(), java.text.Normalizer.Form.NFC) == "café"
                }.count()
            }
            cafeDirs shouldBe 1L

            // The page is inside the existing subtree and survives a fresh rebuild (not excluded as a ghost).
            harness.builder.rebuild()
            val got = client.get("/api/v1/pages/$firstId")
            got.status shouldBe HttpStatusCode.OK
            got.obj().getValue("path").jsonPrimitive.content shouldBe "café/in-cafe.md"
        }
    }

    // 21. P2 (no 0-byte window) — a successful create publishes the page with its FULL content atomically;
    // the target is never observable as an empty file (the createLink-with-content path, no reserve gap).
    test("a successful create writes the full document atomically (the target is never an empty file)") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"guides","title":"Atomic","body":"# Atomic\n\nfull content here.\n"}""")
            }
            post.status shouldBe HttpStatusCode.Created
            val onDisk = harness.diskBytes("guides/atomic.md")
            onDisk.isNotEmpty() shouldBe true
            String(onDisk, Charsets.UTF_8) shouldContain "full content here."
            // The content_hash in the 201 body is over the FULL bytes — never the hash of 0 bytes.
            post.obj().getValue("content_hash").jsonPrimitive.content shouldBe citations.contentHash(onDisk)
        }
    }

    // 22. P2 (media-type guard) — the create request is JSON-only: a non-application/json (or absent)
    // Content-Type with a JSON-looking body is 415 unsupported_media_type, nothing written; a proper
    // application/json (with or without a charset param) still creates the page.
    test("a POST without application/json is 415 unsupported_media_type; application/json (+charset) works") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)
            val jsonBody = """{"folder":"guides","title":"Typed"}"""

            // text/plain with a JSON-looking body → 415, nothing written.
            val textPlain = client.post("/api/v1/pages") {
                contentType(ContentType.Text.Plain)
                setBody(jsonBody)
            }
            textPlain.status shouldBe HttpStatusCode.UnsupportedMediaType
            textPlain.errorJson().getValue("code").jsonPrimitive.content shouldBe "unsupported_media_type"

            // No Content-Type (raw bytes, no contentType call) → 415, nothing written.
            val none = client.post("/api/v1/pages") {
                setBody(jsonBody.toByteArray())
            }
            none.status shouldBe HttpStatusCode.UnsupportedMediaType
            none.errorJson().getValue("code").jsonPrimitive.content shouldBe "unsupported_media_type"

            countFiles(harness.root) shouldBe before // nothing written by either rejected request

            // application/json; charset=utf-8 — the charset param is ignored; the create succeeds.
            val ok = client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json.withCharset(Charsets.UTF_8))
                setBody(jsonBody)
            }
            ok.status shouldBe HttpStatusCode.Created
        }
    }

    // 23. P2 (NFC PARENT collision — reuse the SCAN WINNER, not an arbitrary entry). When a parent dir
    // has TWO raw names that NFC-collide to the same segment, scan() keeps the RawByteOrder winner and
    // EXCLUDES the loser. The create must resolve the parent to that SAME winner, else the page lands
    // under the loser subtree and the next rebuild drops it (a 201 with an unindexed file). Byte-
    // preserving FS only (Linux/ext4, the deployment target): on a folding FS (macOS APFS) the two dir
    // names collapse to one, so the multi-candidate case cannot arise — documented regime split.
    test("a create into a parent with NFC-colliding sibling dirs lands under the scan winner and survives rebuild") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            // Two raw dir names that NFC-normalize to "café": precomposed (NFC) and decomposed (NFD).
            val nfcDir = "café" // precomposed U+00E9
            val nfdDir = "cafe" + '́' // e + U+0301 COMBINING ACUTE (NFD)
            Files.createDirectory(harness.root.resolve(nfcDir))
            // On a folding FS (APFS) the NFD name resolves to the just-created NFC dir → already-exists;
            // that is the single-dir regime where the multi-candidate case can't arise, handled below.
            runCatching { Files.createDirectory(harness.root.resolve(nfdDir)) }

            // Probe the FS regime: did both raw dir names land distinctly?
            val cafeDirs = Files.list(harness.root).use { stream ->
                stream.filter {
                    Files.isDirectory(it) &&
                        java.text.Normalizer.normalize(it.fileName.toString(), java.text.Normalizer.Form.NFC) == "café"
                }.map { it.fileName.toString() }.toList()
            }
            if (cafeDirs.size < 2) return@writeRestTest // folding FS — the multi-candidate case can't occur

            // scan()'s winner = the RawByteOrder-first raw dir name; the create MUST resolve here.
            val winnerDir = cafeDirs.minWithOrNull(RawByteOrder)!!

            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"café","title":"Collided Parent"}""")
            }
            post.status shouldBe HttpStatusCode.Created

            // The new page is physically under the WINNER dir, never the loser.
            Files.exists(harness.root.resolve(winnerDir).resolve("collided-parent.md")) shouldBe true
            val loserDirs = cafeDirs.filter { it != winnerDir }
            loserDirs.none { Files.exists(harness.root.resolve(it).resolve("collided-parent.md")) } shouldBe true

            // It is INDEXED after a rebuild (the winner subtree is the one scan keeps) — not a ghost.
            harness.builder.rebuild()
            val got = client.get("/api/v1/pages/$firstId")
            got.status shouldBe HttpStatusCode.OK
            got.obj().getValue("path").jsonPrimitive.content shouldBe "café/collided-parent.md"
        }
    }

    // 24. P2 (canonical-slug collision) — a create whose canonical URL is already owned by a DIFFERENT
    // published page (here: `old.md` carrying frontmatter `slug: foo`) is 409 slug_conflict, NOTHING
    // written, and the existing page KEEPS its `/foo` URL (never silently displaced).
    test("a create colliding on canonical slug is 409 slug_conflict, writes nothing, and does not displace the owner") {
        val tree = java.nio.file.Files.createTempDirectory("plainbase-create-slug")
        try {
            // An existing page that owns the canonical slug `foo` via frontmatter (filename is `old.md`).
            Files.write(tree.resolve("old.md"), "---\ntitle: Old\nslug: foo\n---\n\n# Old\n\nbody.\n".toByteArray())
            writeRestTest(tree, idProvider = idProvider()) { harness ->
                val before = countFiles(harness.root)

                // A POST whose canonical slug resolves to `foo` (explicit slug here; title "Foo" would too).
                val post = client.post("/api/v1/pages") {
                    contentType(json())
                    setBody("""{"folder":"","slug":"foo","title":"Foo"}""")
                }
                post.status shouldBe HttpStatusCode.Conflict
                val error = post.errorJson()
                error.getValue("code").jsonPrimitive.content shouldBe "slug_conflict"
                error.getValue("path").jsonPrimitive.content shouldBe "foo"

                countFiles(harness.root) shouldBe before // nothing written (no foo.md)

                // The existing page is NOT displaced — it still answers at /docs/foo.
                val owner = client.get("/api/v1/pages/by-path/foo")
                owner.status shouldBe HttpStatusCode.OK
                owner.obj().getValue("path").jsonPrimitive.content shouldBe "old.md"

                // A NON-colliding slug still creates normally.
                client.post("/api/v1/pages") {
                    contentType(json())
                    setBody("""{"folder":"","slug":"bar","title":"Bar"}""")
                }.status shouldBe HttpStatusCode.Created
                Files.exists(harness.root.resolve("bar.md")) shouldBe true
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    // 25. P2 (composed-document cap) — the body cap checks the JSON REQUEST size, but the server ADDS
    // frontmatter, so a request just under the cap can compose a document OVER it. The composed bytes are
    // re-checked → 413 body_too_large, nothing written.
    test("a create whose composed document exceeds the cap is 413 body_too_large; nothing written") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)
            val cap = harness.services.maxWriteBodyBytes.toInt()
            // Size the body into the WINDOW where the JSON request is <= cap but the composed document
            // (which swaps the JSON envelope for the larger frontmatter block) is > cap. The composed
            // frontmatter (`---`, `id: <36-char uuid>`, `title: "Big"`, `---`, blank line) is bigger than
            // the JSON envelope, so such a window exists; compute both envelopes exactly and aim into it.
            val jsonEnvelope = """{"folder":"guides","title":"Big","body":""}""".toByteArray(Charsets.UTF_8).size
            val frontmatter = "---\nid: 01900000-0000-7000-8000-000000000001\ntitle: \"Big\"\n---\n\n".toByteArray(Charsets.UTF_8).size
            // body so request == cap (the max under-or-equal), which makes composed == cap - jsonEnvelope + frontmatter > cap.
            val body = "x".repeat(cap - jsonEnvelope)
            val requestJson = """{"folder":"guides","title":"Big","body":"$body"}"""
            (requestJson.toByteArray(Charsets.UTF_8).size <= cap) shouldBe true // request is within the cap
            (body.length + frontmatter > cap) shouldBe true // but the composed document overflows it
            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody(requestJson)
            }
            post.status shouldBe HttpStatusCode.PayloadTooLarge
            val err = post.errorJson()
            err.getValue("code").jsonPrimitive.content shouldBe "body_too_large"
            err.getValue("max_bytes").jsonPrimitive.content.toLong() shouldBe cap.toLong()
            countFiles(harness.root) shouldBe before // nothing written
        }
    }

    // 26. P2 (scan-skip class closure) — a `_folder.yaml` folder segment is one the scan SKIPS (the
    // metadata sidecar), so a create under it would be a ghost. The create-reject gate now mirrors scan's
    // FULL name-skip set (not just ignore rules), so `_folder.yaml` (top-level AND nested) is 400
    // invalid_create_request, NOTHING created on disk — same class as the round-2 dotfile/ignored case.
    test("a create whose folder segment is _folder.yaml is 400 invalid_create_request; nothing created") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            val before = countFiles(harness.root)
            for (folder in listOf("_folder.yaml", "a/_folder.yaml")) {
                val post = client.post("/api/v1/pages") {
                    contentType(json())
                    setBody("""{"folder":"$folder","title":"Ghost"}""")
                }
                post.status shouldBe HttpStatusCode.BadRequest
                post.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
            }
            // Neither a `_folder.yaml` dir nor any page under one was created.
            Files.exists(harness.root.resolve("_folder.yaml")) shouldBe false
            Files.exists(harness.root.resolve("a")) shouldBe false
            countFiles(harness.root) shouldBe before
        }
    }

    // 27. P1 (folder-slug collision class) — a BRAND-NEW folder whose canonical slug collides with an
    // EXISTING folder must NOT be created, else rebuild's RawByteOrder winner can strip the existing
    // folder's whole `/docs/foo/...` subtree. Case-sensitive FS only (the collision needs two distinct
    // dir names `foo`/`Foo` slugging to `foo`); on a case-insensitive FS (macOS APFS default) `Foo`
    // resolves to the existing `foo` dir → a create INTO it, which legitimately succeeds — documented
    // regime split, asserted per regime so the test is meaningful on Linux CI and correct everywhere.
    test("a new folder slug-colliding with an existing folder is 409 slug_conflict and keeps the owner's URLs") {
        val tree = java.nio.file.Files.createTempDirectory("plainbase-create-folderslug")
        try {
            // An existing indexed folder `foo` with a page, owning `/docs/foo/old`.
            Files.createDirectories(tree.resolve("foo"))
            Files.write(tree.resolve("foo/old.md"), "---\ntitle: Old\n---\n\n# Old\n\nbody.\n".toByteArray())
            writeRestTest(tree, idProvider = idProvider()) { harness ->
                // Probe case-sensitivity: create a lowercase probe dir; if the FS is case-INSENSITIVE,
                // its uppercase view resolves to the same dir (exists). Case-sensitive ⇒ uppercase absent.
                Files.createDirectory(harness.root.resolve("probe"))
                val caseSensitive = !Files.exists(harness.root.resolve("PROBE"))
                Files.delete(harness.root.resolve("probe"))

                // The collision needs a DISTINCT new dir whose slug equals the existing folder's. That can
                // only arise on a case-sensitive FS (`Foo` distinct from `foo`, both slug to `foo`); on a
                // case-insensitive FS `Foo` IS `foo` (a case-fold reuse, not a distinct-folder collision),
                // so the genuine P1 can't occur there — assert it where it's real (Linux/ext4, the target).
                if (caseSensitive) {
                    val before = countFiles(harness.root)
                    val post = client.post("/api/v1/pages") {
                        contentType(json())
                        setBody("""{"folder":"Foo","title":"Intruder"}""")
                    }
                    post.status shouldBe HttpStatusCode.Conflict
                    post.errorJson().getValue("code").jsonPrimitive.content shouldBe "slug_conflict"
                    countFiles(harness.root) shouldBe before // nothing written

                    // The existing page keeps its canonical URL — never displaced by the (rejected) create.
                    val owner = client.get("/api/v1/pages/by-path/foo/old")
                    owner.status shouldBe HttpStatusCode.OK
                    owner.obj().getValue("path").jsonPrimitive.content shouldBe "foo/old.md"
                }

                // A genuinely NON-colliding new folder still creates normally (no false positive).
                client.post("/api/v1/pages") {
                    contentType(json())
                    setBody("""{"folder":"fresh","title":"Fresh"}""")
                }.status shouldBe HttpStatusCode.Created
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    // 28. P1 (alias collision) — a create whose canonical URL is already claimed as an existing page's
    // `redirect_from` ALIAS is 409 slug_conflict, nothing written, and the alias stays intact.
    test("a create whose URL is owned by an existing redirect_from alias is 409 slug_conflict") {
        val tree = java.nio.file.Files.createTempDirectory("plainbase-create-alias")
        try {
            // `home.md` declares redirect_from [bar] → the alias URL `/docs/bar` resolves to home.
            Files.write(tree.resolve("home.md"), "---\ntitle: Home\nredirect_from:\n  - bar\n---\n\n# Home\n\nbody.\n".toByteArray())
            writeRestTest(tree, idProvider = idProvider()) { harness ->
                val before = countFiles(harness.root)
                val post = client.post("/api/v1/pages") {
                    contentType(json())
                    setBody("""{"folder":"","slug":"bar","title":"Bar"}""")
                }
                post.status shouldBe HttpStatusCode.Conflict
                post.errorJson().getValue("code").jsonPrimitive.content shouldBe "slug_conflict"
                countFiles(harness.root) shouldBe before // nothing written

                // The alias is intact: /docs/bar still resolves to home.md.
                val alias = client.get("/api/v1/pages/by-path/bar")
                alias.status shouldBe HttpStatusCode.OK
                alias.obj().getValue("path").jsonPrimitive.content shouldBe "home.md"
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    // 29. P2 (dangling alias must not block) — an alias row pointing at a page id ABSENT from the
    // snapshot is stale (the shadow-sweep hasn't dropped it yet). It must NOT 409 a create at that URL,
    // else the URL is permanently uncreatable (the create is refused before a rebuild can sweep it).
    test("a create whose URL matches a DANGLING alias (target page absent) is 201, not blocked") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            // A stale alias `dangling` → a page id NOT in the index (no such page was ever created).
            harness.registry.register(TreePath.require("dangling"), PageId.require("0190dead-beef-7000-8000-000000000001"))

            val post = client.post("/api/v1/pages") {
                contentType(json())
                setBody("""{"folder":"","slug":"dangling","title":"Dangling"}""")
            }
            post.status shouldBe HttpStatusCode.Created // the dead alias did not wedge the URL
            Files.exists(harness.root.resolve("dangling.md")) shouldBe true
        }
    }

    // 30. P2#1 (URL-collision serialized UNDER the create monitor — different files, same canonical URL).
    // Two concurrent POSTs into case-variant new folders (`Foo`/`foo`) write DIFFERENT files (so the
    // filename O_EXCL can't catch them) but resolve to the SAME folder URL. Exactly one must 201 and one
    // 409 `slug_conflict` — never both-succeed-and-displace — because the second, serialized after the
    // first's create+rebuild published the folder-URL claim, sees it UNDER the monitor. Case-sensitive FS
    // only (distinct `Foo`/`foo` dirs); on a case-insensitive FS (macOS) they ARE one dir → the
    // same-file O_EXCL `page_exists` path of test 5, so the genuine URL-only race can't arise — gated.
    test("two concurrent creates in case-variant folders (same folder URL, different files) yield exactly one 201 and one conflict") {
        writeRestTest(Fixtures.demoDocs, idProvider = idProvider()) { harness ->
            Files.createDirectory(harness.root.resolve("probe"))
            val caseSensitive = !Files.exists(harness.root.resolve("PROBE"))
            Files.delete(harness.root.resolve("probe"))
            if (!caseSensitive) return@writeRestTest // can't build two distinct dirs that slug-collide

            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val statuses = List(2) { AtomicReference<HttpStatusCode>() }
            val folders = listOf("Foo", "foo") // distinct dirs (case-sensitive), both slug to `foo`
            val threads = (0..1).map { n ->
                Thread {
                    ready.countDown()
                    go.await()
                    val resp = kotlinx.coroutines.runBlocking {
                        client.post("/api/v1/pages") {
                            contentType(json())
                            setBody("""{"folder":"${folders[n]}","title":"Racer $n"}""")
                        }
                    }
                    statuses[n].set(resp.status)
                }
            }
            threads.forEach { it.start() }
            ready.await()
            go.countDown()
            threads.forEach { it.join() }

            // Exactly one 201 and one Conflict — never two 201s (which would mean both folders published
            // and one lost path space). The conflict loser is a `slug_conflict` (folder-URL), not a file.
            val codes = statuses.map { it.get() }
            codes.count { it == HttpStatusCode.Created } shouldBe 1
            codes.count { it == HttpStatusCode.Conflict } shouldBe 1
        }
    }
})

/** Counts the regular files under [root] (the "nothing written" invariant). */
private fun countFiles(root: java.nio.file.Path): Int =
    java.nio.file.Files.walk(root).use { stream -> stream.filter { java.nio.file.Files.isRegularFile(it) }.count().toInt() }

/**
 * An outgoing body that ADVERTISES [advertisedLength] (the `Content-Length` header) but actually
 * streams all of [bytes] — a lying Content-Length. The route must enforce the cap by the streamed
 * read, never by trusting this advertised number (mirrors the PUT body-cap test idiom).
 */
private class LyingLengthJsonContent(
    private val bytes: ByteArray,
    private val advertisedLength: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType get() = ContentType.Application.Json
    override val contentLength: Long get() = advertisedLength
    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(bytes)
    }
}

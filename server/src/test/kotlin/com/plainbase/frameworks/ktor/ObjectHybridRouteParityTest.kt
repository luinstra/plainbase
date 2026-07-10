package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.objectstore.FakeObjectStore
import com.plainbase.frameworks.objectstore.MirrorState
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.PutCondition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * PB-WRITE-1 outcome parity between the LOCAL and OBJECT backends, over the SAME
 * [writeRestTest]/`storeOverride` harness the frozen local suites use (`WriteRouteTest.kt`,
 * `WriteRouteCreateTest.kt`, `WriteGoldenTest.kt`). NET-NEW work (only `WriteRouteTest` uses
 * `storeOverride` today, for a failing-write arm) - this file wraps the harness's temp-dir
 * [LocalContentStore] as the MIRROR inside an [ObjectContentStore] over a [FakeObjectStore],
 * pre-seeded with the fixture tree's own bytes/etags, then re-runs a representative scenario per
 * named outcome family. The frozen wire is asserted byte-identical: same status, same JSON tree
 * shape, same `content_hash`.
 */
class ObjectHybridRouteParityTest : FunSpec({

    val citations = CitationFactory()
    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("guides/deploy-guide.md"), PageId.require(deployGuideId), materialized = false)
    }

    fun markdown(): ContentType = ContentType.parse("text/markdown")
    fun etag(hash: String) = "\"$hash\""
    suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    suspend fun HttpResponse.errorJson(): JsonObject = json().getValue("error").jsonObject

    test("edit Written: 200, content_hash, on-disk identical - byte-identical to the local suite") {
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = hybridOverride) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val edited = original + "\nhybrid ok.\n".toByteArray()
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                contentType(markdown())
                setBody(edited)
            }
            put.status shouldBe HttpStatusCode.OK
            put.json().getValue("content_hash").jsonPrimitive.content shouldBe citations.contentHash(edited)
            harness.diskBytes("guides/deploy-guide.md") shouldBe edited
            // Dirty-journal end-state parity (finding 7): a fully-successful Written CLEARS the mark, same
            // as the local backend (WriteRouteTest never leaves a row after a clean 200).
            harness.dirtyPages.all().isEmpty() shouldBe true
        }
    }

    test("edit Conflict: a stale If-Match is 409 content_changed with current_* - byte-identical to the local suite") {
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = hybridOverride) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            val aBytes = original + "\nA's edit.\n".toByteArray()
            client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(aBytes)
            }.status shouldBe HttpStatusCode.OK

            val b = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(original + "\nB's edit.\n".toByteArray())
            }
            b.status shouldBe HttpStatusCode.Conflict
            val err = b.errorJson()
            err.getValue("reason").jsonPrimitive.content shouldBe "content_changed"
            err.getValue("current_hash").jsonPrimitive.content shouldBe citations.contentHash(aBytes)
        }
    }

    test(
        "retry-honesty (composed REST, object backend): after the client's own write is DURABLE at the bucket " +
            "but the mirror is behind (the post-Q8b state), a stale-baseHash retry surfaces a 409 whose " +
            "current_content == the client's OWN durable submission - not the stale mirror bytes",
    ) {
        val fakeSlot = arrayOfNulls<FakeObjectStore>(1)
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = hybridOverride(fakeSlot)) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val firstDurable = original + "\nmy first (durable) edit.\n".toByteArray()
            // Simulate the state a Q8b durable_but_unmirrored leaves: the client's FIRST PUT of `firstDurable`
            // landed durably at the bucket (the client saw a 503 and does NOT know), while the mirror stayed at
            // the original. (The PUT-then-mirror-throw mechanics are store-tested in ObjectContentStoreQ8Test;
            // here we prove the COMPOSED REST retry over the real hybrid + route.)
            fakeSlot[0]!!.seed("guides/deploy-guide.md", firstDurable)

            // A retry with the SAME stale baseHash but DIFFERENT bytes (an idempotent same-bytes retry would be
            // a 200 no-op - itself honest): the 409 must reveal the client's OWN durable first write, so the
            // client learns its write landed and can reconcile - NEVER the phantom original mirror bytes.
            val retry = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                contentType(markdown())
                setBody(original + "\na different retry edit.\n".toByteArray())
            }
            retry.status shouldBe HttpStatusCode.Conflict
            val err = retry.errorJson()
            err.getValue("reason").jsonPrimitive.content shouldBe "content_changed"
            err.getValue("current_content").jsonPrimitive.content shouldBe String(firstDurable, Charsets.UTF_8)
            err.getValue("current_hash").jsonPrimitive.content shouldBe citations.contentHash(firstDurable)
        }
    }

    test("edit Deleted: the object is gone at the BUCKET (the authority) - 409 page_deleted with current_* null") {
        // For the hybrid, "deleted" means gone at the bucket (the authority), not merely absent from the
        // local mirror render - deleting only the mirror file would let the fake bucket's copy heal it
        // right back (a legitimate hybrid behavior, not the scenario under test). Delete via the fake.
        val fakeSlot = arrayOfNulls<FakeObjectStore>(1)
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = hybridOverride(fakeSlot)) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val hBase = citations.contentHash(original)
            Files.delete(harness.root.resolve("guides/deploy-guide.md"))
            runBlocking { fakeSlot[0]!!.delete("guides/deploy-guide.md") }
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(hBase))
                contentType(markdown())
                setBody(original)
            }
            put.status shouldBe HttpStatusCode.Conflict
            put.errorJson().getValue("reason").jsonPrimitive.content shouldBe "page_deleted"
            put.errorJson().getValue("current_content").toString() shouldBe "null"
        }
    }

    test("edit Unreadable: a CAS-failing store is 503 content_unreadable; nothing written - same as local") {
        val failing: (ContentStore) -> ContentStore = { real ->
            object : ContentStore by real {
                override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String) =
                    com.plainbase.domain.content.CasResult.Unreadable("simulated hybrid outage")
            }
        }
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = { mirror -> failing(hybridOverride(mirror)) }) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                contentType(markdown())
                setBody(original + "x".toByteArray())
            }
            put.status shouldBe HttpStatusCode.ServiceUnavailable
            put.errorJson().getValue("code").jsonPrimitive.content shouldBe "content_unreadable"
        }
    }

    test("create Created: 201, content_hash + ETag, composed frontmatter on disk - byte-identical to the local suite") {
        writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider(), storeOverride = hybridOverride) { harness ->
            val post = client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody("""{"folder":"guides","title":"Hybrid Page","body":"# Hybrid\n\nbody.\n"}""")
            }
            post.status shouldBe HttpStatusCode.Created
            val body = post.json()
            val hash = body.getValue("content_hash").jsonPrimitive.content
            post.headers[HttpHeaders.ETag].shouldNotBeNull() shouldBe "\"$hash\""
            val onDisk = harness.diskBytes("guides/hybrid-page.md")
            citations.contentHash(onDisk) shouldBe hash
            // Dirty-journal end-state parity (finding 7): a clean 201 create leaves no dirty row, same as local.
            harness.dirtyPages.all().isEmpty() shouldBe true
        }
    }

    test("create AlreadyExists: a POST onto an existing fixture path is 409 page_exists, no clobber - same as local") {
        writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider(), storeOverride = hybridOverride) { harness ->
            val before = harness.diskBytes("guides/deploy-guide.md")
            val post = client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody("""{"folder":"guides","slug":"deploy-guide","title":"Clash"}""")
            }
            post.status shouldBe HttpStatusCode.Conflict
            post.errorJson().getValue("code").jsonPrimitive.content shouldBe "page_exists"
            harness.diskBytes("guides/deploy-guide.md") shouldBe before
        }
    }

    test("create InvalidLocation: a traversal folder is 400 invalid_create_request - same as local") {
        writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider(), storeOverride = hybridOverride) { _ ->
            val post = client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody("""{"folder":"../escape","title":"Escape"}""")
            }
            post.status shouldBe HttpStatusCode.BadRequest
            post.errorJson().getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
        }
    }

    test("create SlugConflict: a canonical-slug collision is 409 slug_conflict, writes nothing - same as local") {
        val tree = Files.createTempDirectory("plainbase-hybrid-parity-slug")
        try {
            Files.write(tree.resolve("old.md"), "---\ntitle: Old\nslug: foo\n---\n\n# Old\n\nbody.\n".toByteArray())
            writeRestTest(tree, idProvider = TestIdProvider(), storeOverride = hybridOverride) { harness ->
                val post = client.post("/api/v1/pages") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"folder":"","slug":"foo","title":"Foo"}""")
                }
                post.status shouldBe HttpStatusCode.Conflict
                post.errorJson().getValue("code").jsonPrimitive.content shouldBe "slug_conflict"
                java.nio.file.Files.exists(harness.root.resolve("foo.md")) shouldBe false
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    test("WrittenButUnindexed: a post-write hook failure is 200 with warning reindex_deferred, bytes on disk") {
        val throwingHook = com.plainbase.domain.service.WriteHistoryHook { _, _, _, _ -> throw RuntimeException("boom") }
        writeRestTest(Fixtures.demoDocs, seed, historyHook = throwingHook, storeOverride = hybridOverride) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val edited = original + "\ndeferred.\n".toByteArray()
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                contentType(markdown())
                setBody(edited)
            }
            put.status shouldBe HttpStatusCode.OK
            put.json().getValue("warning").jsonObject.getValue("code").jsonPrimitive.content shouldBe "reindex_deferred"
            harness.diskBytes("guides/deploy-guide.md") shouldBe edited
            // Dirty-journal end-state parity (finding 7, matching WriteRouteTest's local twin): a deferred
            // reindex RETAINS the write-ahead mark, and its expectedHash is the edited bytes' hash - the
            // exact recovery record reconcileDirtyPages replays. Same row + expectedHash on both backends.
            val dirty = harness.dirtyPages.all()
            dirty.isEmpty() shouldBe false
            dirty.single().expectedHash shouldBe citations.contentHash(edited)
        }
    }

    test("byte-exact wire golden: write-put-ok shape holds identically over the hybrid") {
        writeRestTest(Fixtures.demoDocs, seed, storeOverride = hybridOverride) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            val edited = original + "\ngolden hybrid.\n".toByteArray()
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                contentType(markdown())
                setBody(edited)
            }
            put.status shouldBe HttpStatusCode.OK
            val tree = put.json()
            tree.keys shouldBe setOf("content_hash", "commit")
            tree.getValue("content_hash").jsonPrimitive.content shouldBe citations.contentHash(edited)
            tree.getValue("commit").toString() shouldBe "null"
        }
    }
})

/**
 * Wraps the harness's temp-dir [LocalContentStore] as the MIRROR inside an [ObjectContentStore]
 * over a fresh [FakeObjectStore]. `storeOverride` runs during [WriteRestHarness] PROPERTY
 * initialization - strictly BEFORE its `init {}` block copies the fixture tree onto [mirror]'s root
 * - so the bucket cannot be pre-seeded here (the mirror is still empty). Instead, the returned
 * store seeds the fake bucket from the mirror's CURRENT files on its first [ContentStore.scan]
 * (which `IndexBuilder.rebuild()` - always called AFTER the fixture copy - triggers first), so
 * base-hash CAS/create comparisons resolve against matching bucket state from then on.
 *
 * [fakeSlot], when given, is populated with the constructed [FakeObjectStore] so a test can reach
 * BUCKET-level state directly (e.g. deleting an object at the authority - the hybrid's actual
 * "deleted" scenario, distinct from merely removing the local mirror render).
 */
private fun hybridOverride(fakeSlot: Array<FakeObjectStore?>? = null): (LocalContentStore) -> ContentStore = { mirror ->
    val fake = FakeObjectStore()
    fakeSlot?.set(0, fake)
    val stateFile = Files.createTempFile("pb-hybrid-parity-mirror-state", ".json").also { Files.deleteIfExists(it) }
    val real = ObjectContentStore(
        client = fake,
        mirror = mirror,
        state = MirrorState(stateFile),
        keyPrefix = "",
        pollSeconds = 3_600,
        dirtyPaths = { emptySet() },
        // Point mirrorRoot at the REAL mirror's root, not a throwaway temp dir: harmless today (this parity
        // harness never hydrates/polls), but a divergent mirrorRoot is a latent footgun for anything that would.
        mirrorRoot = requireNotNull(mirror.onDiskTarget(com.plainbase.domain.content.TreePath.require("pb-root-probe")).parent),
    )
    var seeded = false
    object : ContentStore by real {
        override fun scan(): com.plainbase.domain.content.ScanResult {
            if (!seeded) {
                seeded = true
                mirror.scan().files.forEach { file ->
                    val bytes = mirror.read(file.path) ?: return@forEach
                    runBlocking { fake.put(mirror.resolveRepoRelativePath(file.path), bytes, PutCondition.None) }
                }
            }
            return real.scan()
        }
    }
}

/** The zero-arg form every OTHER scenario in this file uses (no bucket-level backdoor needed). */
private val hybridOverride: (LocalContentStore) -> ContentStore = hybridOverride(null)

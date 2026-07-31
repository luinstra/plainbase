package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * The per-chunk memory bound is enforced over ACTUALLY-FETCHED bytes, not over what LIST declared: a provider
 * that misdeclares (all-zero sizes, say) packs a whole corpus into one chunk, and only fetch-time accounting
 * stops that chunk from holding every body resident on the boot path. The bound closes the chunk early and
 * DEFERS the tail, so both fetch loops run a worklist - which makes convergence (every key still heals, in
 * bounded passes) the property these rows exist to hold, alongside the accounting itself.
 */
class ObjectContentStoreFetchBoundTest : FunSpec({

    fun entries(vararg declared: Pair<String, Long?>): List<Map.Entry<String, MirrorListedEntry>> =
        declared.associate { (key, size) -> key to MirrorListedEntry(key, "\"e-$key\"", size) }.entries.toList()

    fun body(size: Int) = FetchedObject(ByteArray(size), "\"fetched\"")

    fun List<ChunkFetchOutcome<String>>.tags(): List<String> = map {
        when (it) {
            is ChunkFetchOutcome.Fetched -> "fetched"
            is ChunkFetchOutcome.Absent -> "absent"
            is ChunkFetchOutcome.Failed -> "failed"
            is ChunkFetchOutcome.Deferred -> "deferred"
        }
    }

    fun List<ChunkFetchOutcome<String>>.keys(): List<String> = map {
        when (it) {
            is ChunkFetchOutcome.Fetched -> it.entry.key
            is ChunkFetchOutcome.Absent -> it.key
            is ChunkFetchOutcome.Failed -> it.key
            is ChunkFetchOutcome.Deferred -> it.entry.key
        }
    }

    test("F1: the counter closes a chunk at exactly >= the budget, over fetched bytes and not declared ones") {
        // Budget 200 rather than 150 on purpose: at 150 both `>` and `>=` would defer the third entry, so the
        // row could not tell them apart. At 200 only `>=` defers it, which pins the threshold semantics.
        val outcomes = ObjectContentStore.fetchChunkBounded(
            entries("a" to 0L, "b" to 0L, "c" to 0L),
            parallelism = 1,
            byteBudget = 200,
            onFailure = { _, _ -> },
        ) { body(100) }
        outcomes.tags() shouldBe listOf("fetched", "fetched", "deferred")
    }

    test("F2: a 404 and a failure count nothing toward the budget, and neither is deferred") {
        val failures = mutableListOf<String>()
        val outcomes = ObjectContentStore.fetchChunkBounded(
            entries("a" to 100L, "b" to 100L, "c" to 100L),
            parallelism = 1,
            byteBudget = 150,
            onFailure = { key, _ -> failures += key },
        ) { entry ->
            when (entry.rawRelative) {
                "a" -> null
                "b" -> throw ObjectStoreException("simulated GET failure")
                else -> body(100)
            }
        }
        // Entry "c" fetches only because the counter is still 0: a 404 and an exception retained no bytes.
        outcomes.tags() shouldBe listOf("absent", "failed", "fetched")
        failures shouldBe listOf("b")
    }

    test("F3: no entry is lost or duplicated across the fetch/defer split") {
        val outcomes = ObjectContentStore.fetchChunkBounded(
            entries("a" to 0L, "b" to 0L, "c" to 0L, "d" to 0L, "e" to 0L, "f" to 0L, "g" to 0L, "h" to 0L),
            parallelism = 1,
            byteBudget = 250,
            onFailure = { _, _ -> },
        ) { entry ->
            when (entry.rawRelative) {
                "b" -> null
                "d" -> throw ObjectStoreException("simulated GET failure")
                else -> body(100)
            }
        }
        outcomes.keys() shouldBe listOf("a", "b", "c", "d", "e", "f", "g", "h")
    }

    test("F2b: hydrate does not re-queue a failed GET into the worklist") {
        HybridFixture().use { hybrid ->
            val paths = listOf("a.md", "b.md", "c.md").map { TreePath.require(it) }
            paths.forEach { hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(it), "x".repeat(100).toByteArray()) }
            val failing = hybrid.mirror.resolveRepoRelativePath(paths[1])
            hybrid.fake.failNextGetFor += failing

            hybrid.store.hydrate()

            // Exactly one GET per key: the failure is terminal for this hydrate. A re-queue would spend a fourth
            // GET which, `failNextGetFor` being one-shot, would SUCCEED and heal the key.
            hybrid.fake.getCount shouldBe 3
            hybrid.state.etagOf(paths[1]) shouldBe null
            hybrid.state.etagOf(paths[0]).shouldNotBeNull()
            hybrid.state.etagOf(paths[2]).shouldNotBeNull()
        }
    }

    test("F4: an all-zero-misdeclaring provider still heals every key, over multiple passes with applies in between") {
        HybridFixture(fetchByteBudget = 150, fetchParallelism = 1).use { hybrid ->
            // FakeObjectStore.list sorts keys, so LIST order (and therefore fetch order) is LEXICAL: a.md, b.md, c.md.
            val paths = listOf("a.md", "b.md", "c.md").map { TreePath.require(it) }
            paths.forEach { hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(it), ByteArray(100)) }
            hybrid.fake.declaredSizeOf = { _, _ -> 0L } // one packed chunk of three; only fetch-time bytes can split it
            val firstMirrorFile = hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(paths[0]))
            val thirdKey = hybrid.mirror.resolveRepoRelativePath(paths[2])
            // At parallelism 1 the arithmetic is fixed: a.md fetches at 0, b.md at 100 < 150, c.md sees 200 >= 150
            // and DEFERS. So c.md's GET happens on a LATER pass, after pass 1 already applied a.md - which is what
            // this probe observes. The AssertionError it can throw is an Error, so the helper rethrows it rather
            // than filing it as a failed fetch.
            hybrid.fake.onGetKey = { key -> if (key == thirdKey) Files.exists(firstMirrorFile) shouldBe true }

            hybrid.store.hydrate()

            paths.forEach { path ->
                Files.exists(hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(path))) shouldBe true
                hybrid.state.etagOf(path).shouldNotBeNull()
            }
            hybrid.fake.getCount shouldBe 3
        }
    }

    // blockingTest: hydrate is blocking, non-suspending code, so Kotest's coroutine timeout can only interrupt it
    // from a dedicated thread. Without this the livelock these rows guard against hangs the run instead of failing it.
    test("F5: hydrate terminates when every entry after the first defers, pass after pass")
        .config(timeout = 30.seconds, blockingTest = true) {
        HybridFixture(fetchByteBudget = 1, fetchParallelism = 1).use { hybrid ->
            // Budget 1: after the first 100-byte body every later check sees 100 >= 1, so each pass fetches exactly
            // one key and the hydrate takes five passes. The first-permit-always-fetches guarantee is what keeps
            // that from being a livelock; the timeout is the observing gate if it ever stops holding.
            val paths = (1..5).map { TreePath.require("p$it.md") }
            paths.forEach { hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(it), ByteArray(100)) }
            hybrid.fake.declaredSizeOf = { _, _ -> 0L }

            hybrid.store.hydrate()

            paths.forEach { path ->
                Files.exists(hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(path))) shouldBe true
                hybrid.state.etagOf(path).shouldNotBeNull()
            }
        }
    }

    test("F6: pollOnce under the same misdeclaring provider converges within one cycle") {
        HybridFixture(fetchByteBudget = 150).use { hybrid ->
            val paths = listOf("a.md", "b.md", "c.md").map { TreePath.require(it) }
            paths.forEach { hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(it), ByteArray(100)) }
            hybrid.fake.declaredSizeOf = { _, _ -> 0L }
            val events = mutableListOf<TreePath>()

            hybrid.store.pollOnce { events += it } // poll is parallelism 1 by design: c.md defers, then a second pass takes it

            paths.forEach { path ->
                Files.exists(hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(path))) shouldBe true
                hybrid.state.etagOf(path).shouldNotBeNull()
            }
            events.toSet() shouldBe paths.toSet()
        }
    }

    test("F6b: the poll's 404 silent-drop survives the shared helper, and the next cycle reconciles it") {
        HybridFixture().use { hybrid ->
            // The one place seedExisting is right: this row needs a HEALED key (mirror + state at v1) that the
            // bucket has since moved past, so the poll sees it as changed and owes it a GET.
            val path = TreePath.require("moves.md")
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.seedExisting(path, "v1".toByteArray())
            val v1Etag = hybrid.state.etagOf(path)
            hybrid.fake.seed(key, "v2".toByteArray())
            hybrid.fake.onGetKey = { k -> if (k == key) hybrid.fake.remove(key) } // 404 in the LIST-to-GET window
            val events = mutableListOf<TreePath>()

            hybrid.store.pollOnce { events += it }

            // Silent and total: no invalidation, no mirror change, no event. Hydrate would have invalidated here.
            hybrid.state.etagOf(path) shouldBe v1Etag
            Files.readString(hybrid.mirrorRoot.resolve(key)) shouldBe "v1"
            events shouldBe emptyList()

            hybrid.fake.onGetKey = {}
            hybrid.store.pollOnce { events += it } // the key is absent from LIST now: the delete phase reconciles it

            Files.exists(hybrid.mirrorRoot.resolve(key)) shouldBe false
            hybrid.state.etagOf(path) shouldBe null
            events shouldBe listOf(path)
        }
    }

    test("F8: deferral is structural at production parallelism, with more entries than permits")
        .config(timeout = 30.seconds, blockingTest = true) {
        HybridFixture(fetchByteBudget = 150).use { hybrid ->
            // 66 entries against 64 permits: whatever the interleaving, the 66th acquire follows at least two
            // releases, and every release either counted a 100-byte body or was itself a deferral that saw the
            // budget already tripped. So at least one entry defers on pass 1 - which entry is not this row's
            // business; that every one of them still heals is.
            val paths = (1..66).map { TreePath.require("p%02d.md".format(it)) }
            paths.forEach { hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(it), ByteArray(100)) }
            hybrid.fake.declaredSizeOf = { _, _ -> 0L }

            hybrid.store.hydrate()

            paths.forEach { path ->
                Files.exists(hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(path))) shouldBe true
                hybrid.state.etagOf(path).shouldNotBeNull()
            }
        }
    }
})

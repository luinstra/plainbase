package com.plainbase.frameworks.objectstore

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [ObjectContentStore.packForFetch] bounds hydrate's per-chunk buffering by DECLARED bytes, not only
 * key count: a chunk's fetched bodies all sit in memory until its applies run, and the mirror funnel
 * admits assets as well as pages, so a count-only chunk of large objects would hold the whole set
 * resident on the boot path.
 */
class ObjectContentStorePackingTest : FunSpec({

    fun entries(vararg sizes: Long?): List<Map.Entry<String, MirrorListedEntry>> =
        sizes.mapIndexed { index, size -> "k$index" to MirrorListedEntry("k$index.md", "\"e$index\"", size) }
            .toMap(LinkedHashMap())
            .entries
            .toList()

    test("entries under the byte budget pack into one chunk, order preserved") {
        val input = entries(100, 200, 300)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 1000, countCap = 10)
        chunks.map { chunk -> chunk.map { it.key } } shouldBe listOf(listOf("k0", "k1", "k2"))
    }

    test("a chunk closes when the next entry would exceed the byte budget") {
        val input = entries(400, 400, 400)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 1000, countCap = 10)
        chunks.map { chunk -> chunk.map { it.key } } shouldBe listOf(listOf("k0", "k1"), listOf("k2"))
    }

    test("a single entry larger than the budget still gets a chunk, alone") {
        val input = entries(50, 5000, 50)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 1000, countCap = 10)
        chunks.map { chunk -> chunk.map { it.key } } shouldBe listOf(listOf("k0"), listOf("k1"), listOf("k2"))
    }

    test("an unknown declared size counts as a 1/64 budget share, bounding unknowns per chunk without solo-packing them") {
        // Treating null as the WHOLE budget had a cliff: one sized entry in an otherwise-null listing
        // forced every null to pack alone (parallelism 1, one apply pass per key). A 1/64 share caps a
        // chunk at 64 unknowns - the pre-packer exposure ceiling - while letting them share chunks.
        val input = entries(50, null, 50)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 6400, countCap = 10)
        // share = 6400/64 = 100: 50 + 100 + 50 = 200, well under budget - one chunk.
        chunks.map { chunk -> chunk.map { it.key } } shouldBe listOf(listOf("k0", "k1", "k2"))
    }

    test("unknown sizes close a chunk at 64 entries even when the count cap is higher") {
        val input = entries(*arrayOfNulls<Long>(70))
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 6400, countCap = 256)
        chunks.map { it.size } shouldBe listOf(64, 6)
    }

    test("the count cap closes a chunk even when bytes remain") {
        val input = entries(1, 1, 1, 1, 1)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 1000, countCap = 2)
        chunks.map { chunk -> chunk.map { it.key } } shouldBe listOf(listOf("k0", "k1"), listOf("k2", "k3"), listOf("k4"))
    }

    test("a Long.MAX_VALUE declared size cannot overflow the budget sum and void the bound") {
        // The naive `bytes + declared > budget` wraps negative after a MAX_VALUE first entry, making the
        // comparison vacuously false forever - the chunk then fills to countCap, the exact unbounded
        // buffering the packer exists to prevent. Overflow-safe comparison closes after the giant entry.
        val input = entries(Long.MAX_VALUE, 300, 300)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 1000, countCap = 10)
        chunks.map { chunk -> chunk.map { it.key } } shouldBe listOf(listOf("k0"), listOf("k1", "k2"))
    }

    test("an all-null listing still packs multiple entries per chunk (the count cap can close first)") {
        val input = entries(null, null, null, null, null)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 1000, countCap = 2)
        chunks.map { chunk -> chunk.map { it.key } } shouldBe listOf(listOf("k0", "k1"), listOf("k2", "k3"), listOf("k4"))
    }

    test("no entry is lost or duplicated across any packing") {
        val input = entries(700, null, 1, 999, 1000, 3, null, 250)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 1000, countCap = 3)
        chunks.flatten().map { it.key } shouldBe input.map { it.key }
    }

    test("empty input packs to no chunks") {
        ObjectContentStore.packForFetch(entries(), byteBudget = 1000, countCap = 3) shouldBe emptyList()
    }
})

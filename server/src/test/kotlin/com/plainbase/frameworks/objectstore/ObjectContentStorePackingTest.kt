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

    test("an unknown declared size counts as the whole budget, so it packs alone") {
        val input = entries(50, null, 50)
        val chunks = ObjectContentStore.packForFetch(input, byteBudget = 1000, countCap = 10)
        chunks.map { chunk -> chunk.map { it.key } } shouldBe listOf(listOf("k0"), listOf("k1"), listOf("k2"))
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

    test("when NO entry declares a size, packing falls back to count-only chunks") {
        // All-null would otherwise pack every entry alone: parallelism collapses to 1 and each one-entry
        // chunk pays a whole apply pass - O(N^2) boot work for a provider that omits <Size> entirely.
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

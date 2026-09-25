package com.plainbase.domain.discussion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class KmpMatcherTest : FunSpec({
    test("overlapping matches are counted") {
        val found = KmpMatcher.scan("banana".encodeToByteArray(), 0, 6, "ana".encodeToByteArray())
        found.count shouldBe 2
        found.first shouldContainExactly listOf(1, 3)
    }

    test("matches crossing either window edge are excluded") {
        val text = "xabcx".encodeToByteArray()
        KmpMatcher.scan(text, 2, 4, "ab".encodeToByteArray()).count shouldBe 0
        KmpMatcher.scan(text, 1, 3, "bc".encodeToByteArray()).count shouldBe 0
    }

    test("a pattern longer than its window has no matches") {
        KmpMatcher.scan("abc".encodeToByteArray(), 0, 2, "abc".encodeToByteArray()).count shouldBe 0
    }

    test("the first list is capped without capping the count") {
        val found = KmpMatcher.scan("aaaaa".encodeToByteArray(), 0, 5, "a".encodeToByteArray(), keep = 2)
        found.count shouldBe 5
        found.first shouldContainExactly listOf(0, 1)
    }

    test("kmp agrees with a naive matcher") {
        for (seed in 0 until 1_000) {
            val random = Random(seed)
            val text = ByteArray(random.nextInt(0, 201)) { if (random.nextBoolean()) 'a'.code.toByte() else 'b'.code.toByte() }
            val pattern = ByteArray(random.nextInt(1, 9)) { if (random.nextBoolean()) 'a'.code.toByte() else 'b'.code.toByte() }
            val from = random.nextInt(text.size + 1)
            val until = random.nextInt(from, text.size + 1)
            val expected = naive(text, from, until, pattern)
            val actual = KmpMatcher.scan(text, from, until, pattern)
            actual.count shouldBe expected.size
            actual.first shouldContainExactly expected.take(AnchorLimits.MAX_CANDIDATES)
        }
    }

    test("context filtering agrees with a naive per occurrence pass") {
        for (seed in 0 until 1_000) {
            val random = Random(seed)
            val text = ByteArray(random.nextInt(0, 201)) { if (random.nextBoolean()) 'a'.code.toByte() else 'b'.code.toByte() }
            val quote = ByteArray(random.nextInt(1, 9)) { if (random.nextBoolean()) 'a'.code.toByte() else 'b'.code.toByte() }
            val prefix = ByteArray(random.nextInt(0, 9)) { if (random.nextBoolean()) 'a'.code.toByte() else 'b'.code.toByte() }
            val suffix = ByteArray(random.nextInt(0, 9)) { if (random.nextBoolean()) 'a'.code.toByte() else 'b'.code.toByte() }
            val bodyStart = random.nextInt(text.size + 1)
            val expected = (bodyStart..text.size).filter { start ->
                start + quote.size <= text.size && text.matchesAt(start, quote) &&
                    start - prefix.size >= bodyStart && text.matchesAt(start - prefix.size, prefix) &&
                    text.matchesAt(start + quote.size, suffix)
            }
            val actual = KmpMatcher.scan(text, bodyStart, text.size, prefix + quote + suffix)
                .first.map { it + prefix.size }
            actual shouldContainExactly expected.take(AnchorLimits.MAX_CANDIDATES)
            KmpMatcher.scan(text, bodyStart, text.size, prefix + quote + suffix).count shouldBe expected.size
        }
    }
})

private fun naive(text: ByteArray, from: Int, until: Int, pattern: ByteArray): List<Int> =
    (from..until).filter { start -> start + pattern.size <= until && text.matchesAt(start, pattern) }

private fun ByteArray.matchesAt(start: Int, pattern: ByteArray): Boolean =
    start >= 0 && start + pattern.size <= size && pattern.indices.all { this[start + it] == pattern[it] }

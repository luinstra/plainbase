package com.plainbase.domain.root

import com.plainbase.domain.page.PageId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * The root-name grammar (ADR-0011): `[a-z0-9][a-z0-9-]*`, max 32 chars, excluding page-id-shaped values.
 * Pure logic, JVM-only (the TreePathTest pattern).
 */
class RootNameTest : FunSpec({

    test("valid slugs construct and round-trip their text") {
        listOf("main", "a", "0", "a-b-1", "memoria", "g".repeat(32)).forEach { raw ->
            val name = RootName.of(raw)
            name.shouldNotBeNull()
            name.value shouldBe raw
            name.toString() shouldBe raw
        }
    }

    test("invalid slugs cannot be constructed") {
        listOf(
            "", // empty
            "Main", // uppercase
            "-a", // leading hyphen
            "a_b", // underscore
            "a/b", // slash
            "caf\u00e9", // non-ASCII (escaped so an editor cannot silently renormalize it, the TreePathTest rule)
            "a b", // space
            "a".repeat(33), // over the length cap
        ).forEach { raw ->
            RootName.of(raw).shouldBeNull()
        }
    }

    test("require throws naming the slug rule where of returns null") {
        val failure = shouldThrow<IllegalArgumentException> { RootName.require("Not-Valid") }
        failure.message shouldContain "Not-Valid"
        failure.message shouldContain "[a-z0-9][a-z0-9-]*"
        RootName.require("main") shouldBe RootName.MAIN
    }

    test("MAIN is the reserved primary") {
        RootName.MAIN.value shouldBe "main"
        RootName.of("main") shouldBe RootName.MAIN
    }

    test("of never accepts a string the slug rule rejects (property)") {
        val slug = Regex("[a-z0-9][a-z0-9-]*")
        checkAll(Arb.string(0..40)) { raw ->
            (RootName.of(raw) != null) shouldBe (raw.length <= 32 && slug.matches(raw) && PageId.of(raw) == null)
        }
    }

    test("of accepts every in-rule string (property over the slug alphabet)") {
        val alphabet = ('a'..'z') + ('0'..'9') + '-'
        checkAll(Arb.int(1..32), Arb.int(0..alphabet.lastIndex)) { length, seed ->
            // Deterministic in-alphabet string with a guaranteed legal head (no leading hyphen).
            val head = alphabet[seed].takeIf { it != '-' } ?: 'a'
            val raw = head + List(length - 1) { alphabet[(seed + it) % alphabet.size] }.joinToString("")
            if (PageId.of(raw) == null) RootName.of(raw).shouldNotBeNull().value shouldBe raw
        }
    }
})

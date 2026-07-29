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
 * The root-name grammar (ADR-0011): `[a-z][a-z0-9]*(-[a-z0-9]+)*`, 2 to 32 chars, excluding page-id-shaped
 * values. Pure logic, JVM-only (the TreePathTest pattern).
 */
class RootNameTest : FunSpec({

    test("valid slugs construct and round-trip their text") {
        listOf("main", "ab", "a-b-1", "memoria", "g".repeat(32)).forEach { raw ->
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
            "a", // single character
            "0", // single character AND digit-leading
            // Digit-leading, so no bare year can name a root. The `v1`/`v2` API spellings are NOT covered here:
            // they are legal slugs, and ReservedSegments refuses them at registration instead.
            "9lives",
            "p", // the mechanism that reserves the single-character namespace, /p/{id} included
            "a-", // trailing hyphen
            "a--b", // doubled hyphen
        ).forEach { raw ->
            RootName.of(raw).shouldBeNull()
        }
    }

    test("require throws naming the slug rule where of returns null") {
        val failure = shouldThrow<IllegalArgumentException> { RootName.require("Not-Valid") }
        failure.message shouldContain "Not-Valid"
        failure.message shouldContain "[a-z][a-z0-9]*(-[a-z0-9]+)*"
        RootName.require("docs") shouldBe RootName.PRIMARY
    }

    test("PRIMARY is the reserved primary") {
        RootName.PRIMARY.value shouldBe "docs"
        RootName.of("docs") shouldBe RootName.PRIMARY
    }

    test("of never accepts a string the slug rule rejects (property)") {
        val slug = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
        checkAll(Arb.string(0..40)) { raw ->
            (RootName.of(raw) != null) shouldBe (raw.length in 2..32 && slug.matches(raw) && PageId.of(raw) == null)
        }
    }

    test("of accepts every in-rule string (property over the slug alphabet)") {
        // Head and tail are drawn WITHOUT the hyphen: a name may not start or end with one. Doubled hyphens are
        // unreachable here by construction (the body's indices walk the alphabet consecutively and '-' occupies
        // exactly one of them), so the no-`--` rule is pinned by the "a--b" literal above and by nothing here.
        val alphabet = ('a'..'z') + ('0'..'9') + '-'
        val edges = ('a'..'z') + ('0'..'9')
        checkAll(Arb.int(2..32), Arb.int(0..alphabet.lastIndex)) { length, seed ->
            val head = ('a'..'z').elementAt(seed % 26)
            val body = List(length - 2) { alphabet[(seed + it) % alphabet.size] }.joinToString("")
            val raw = head + body + edges[(seed + length) % edges.size]
            if (PageId.of(raw) == null) RootName.of(raw).shouldNotBeNull().value shouldBe raw
        }
    }
})

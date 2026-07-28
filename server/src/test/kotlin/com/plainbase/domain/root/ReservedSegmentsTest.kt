package com.plainbase.domain.root

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The reserved word list, the two reserved prefixes and the `v[0-9]+` version shape. The refusals that
 * CONSULT this predicate are tested where they fire (`RootsConfigTest` for config load and the type-level
 * backstop, `RootCommandTest` for `root add`); what is tested here is membership.
 */
class ReservedSegmentsTest : FunSpec({

    fun reserved(raw: String) = ReservedSegments.isReserved(RootName.require(raw))

    test("live route segments, SPA routes and bundle directories are reserved") {
        listOf("api", "assets", "browse", "healthz", "fonts", "admin", "new", "review").forEach {
            reserved(it) shouldBe true
        }
    }

    test("the product prefixes are reserved, and so are their stems") {
        reserved("pb-docs") shouldBe true
        reserved("plainbase-internal") shouldBe true
        reserved("pb") shouldBe true
        reserved("plainbase") shouldBe true
    }

    test("a name that merely STARTS with a stem is not reserved - the prefix rule needs the hyphen") {
        // Without these the prefix rows pass under a bare `startsWith("pb")`, which would eat every root name
        // beginning with those letters.
        reserved("pbx") shouldBe false
        reserved("pbdocs") shouldBe false
        reserved("plainbased") shouldBe false
    }

    test("every letter-v-plus-digits spelling is reserved, so an API version can never want a taken segment") {
        listOf("v1", "v2", "v10").forEach { reserved(it) shouldBe true }
    }

    test("a name that merely STARTS with v is not reserved - the version rule needs the digits and nothing after") {
        // Without these the version rows pass under a bare `startsWith("v")`, which would eat every root name
        // beginning with that letter.
        reserved("vector") shouldBe false
        reserved("v1x") shouldBe false
        // Bare `v` never reaches the predicate at all, and for the same reason `p` does not: minimum length 2.
        RootName.of("v").shouldBeNull()
    }

    test("the primary root's own name is NOT reserved, or every install would boot-refuse it") {
        reserved("docs") shouldBe false
    }

    test("the former primary name is reserved because migration stamps freeze 'main'") {
        reserved("main") shouldBe true
    }

    test("corpus vocabulary a docs tree plausibly owns stays legal") {
        listOf("guides", "notes", "changelog", "team", "handbook", "memoria").forEach { reserved(it) shouldBe false }
    }

    test("every word in the list is itself a legal root name, except the one the grammar already reserves") {
        val unparseable = ReservedSegments.words.filterTo(mutableSetOf()) { RootName.of(it) == null }
        unparseable shouldBe setOf("p")
    }
})

package com.plainbase.domain.root

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The reserved word list and the two reserved prefixes. The refusals that CONSULT this predicate are tested
 * where they fire (`RootsConfigTest` for config load and the type-level backstop, `RootCommandTest` for
 * `root add`); what is tested here is membership.
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

    test("the primary root's own name is NOT reserved, or every install would boot-refuse it") {
        reserved("docs") shouldBe false
        reserved("main") shouldBe false
    }

    test("corpus vocabulary a docs tree plausibly owns stays legal") {
        listOf("guides", "notes", "changelog", "team", "handbook", "memoria").forEach { reserved(it) shouldBe false }
    }

    test("every word in the list is itself a legal root name, except the one the grammar already reserves") {
        val unparseable = ReservedSegments.words.filterTo(mutableSetOf()) { RootName.of(it) == null }
        unparseable shouldBe setOf("p")
    }
})

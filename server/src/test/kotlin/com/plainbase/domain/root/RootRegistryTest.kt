package com.plainbase.domain.root

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * The immutable topology snapshot: main is required, names are distinct (defense for programmatic
 * construction - HOCON parsing can never produce duplicates), and the GIVEN order is preserved
 * verbatim (buildRoots produces origin-line-with-name-tiebreak order, ADR-0011 D7 - the contract
 * source precedence inherits, ADR-0012).
 */
class RootRegistryTest : FunSpec({

    fun root(name: String) = Root(
        name = RootName.require(name),
        backend = RootBackend.Local(Path.of("/roots", name)),
        editable = false,
        history = HistoryMode.OFF,
    )

    test("a root named main is required") {
        val failure = shouldThrow<IllegalArgumentException> { RootRegistry.of(listOf(root("extra"))) }
        failure.message shouldContain "main"
    }

    test("duplicate names are rejected, naming the duplicates") {
        val failure = shouldThrow<IllegalArgumentException> {
            RootRegistry.of(listOf(root("main"), root("extra"), root("extra")))
        }
        failure.message shouldContain "duplicate root name"
        failure.message shouldContain "extra"
    }

    test("the given order is preserved verbatim and primary resolves") {
        val given = listOf(root("zeta"), root("main"), root("alpha"))
        val registry = RootRegistry.of(given)
        registry.roots shouldBe given
        registry.primary shouldBe given[1]
    }

    test("a caller-held mutable list cannot mutate the registry") {
        val given = mutableListOf(root("main"), root("extra"))
        val registry = RootRegistry.of(given)
        given.removeAt(1)
        registry.roots.map { it.name.value } shouldBe listOf("main", "extra")
    }

    test("byName resolves a present root and misses an absent one") {
        val registry = RootRegistry.of(listOf(root("main"), root("extra")))
        registry.byName(RootName.require("extra"))?.name shouldBe RootName.require("extra")
        registry.byName(RootName.require("absent")).shouldBeNull()
    }

    test("extras is a partition of roots in D7 order, never a reordering - primary keeps its declared rank") {
        val registry = RootRegistry.of(listOf(root("zeta"), root("main"), root("alpha")))
        registry.extras.map { it.name.value } shouldBe listOf("zeta", "alpha")
        registry.roots.map { it.name.value } shouldBe listOf("zeta", "main", "alpha")
        registry.rank(RootName.PRIMARY) shouldBe 1 // NOT 0: primary is a typed accessor, not a promotion
    }

    test("of() snapshots the caller's list: mutating it afterwards cannot desync roots, primary, extras or rank") {
        val declared = mutableListOf(root("zeta"), root("main"))
        val registry = RootRegistry.of(declared)
        declared.add(root("alpha"))
        declared.removeAt(0)
        registry.roots.map { it.name.value } shouldBe listOf("zeta", "main")
        registry.primary.name shouldBe RootName.PRIMARY
        registry.extras.map { it.name.value } shouldBe listOf("zeta")
        registry.rank(RootName.PRIMARY) shouldBe 1
    }
})

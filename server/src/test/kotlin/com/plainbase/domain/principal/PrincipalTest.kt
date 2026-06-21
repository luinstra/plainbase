package com.plainbase.domain.principal

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The frozen [Principal] contract (Phase 5 inherits it): identity-only, sealed so A3's `check()` is total.
 *
 * Domain purity is asserted structurally by [com.plainbase.DomainPurityTest], which source-scans the whole
 * `domain/` tree (including `domain/principal/Principal.kt`) for forbidden framework imports — there is no
 * per-file purity test to author.
 */
class PrincipalTest : FunSpec({

    test("the sealed hierarchy exhausts Human / Agent / Anonymous (a when needs no else)") {
        val principals = listOf(Principal.Human("builtin", "u1"), Principal.Agent("pb_abc"), Principal.Anonymous)
        val labels = principals.map { p ->
            when (p) { // no `else` branch — the compiler proves exhaustiveness
                is Principal.Human -> "human:${p.issuer}/${p.externalId}"
                is Principal.Agent -> "agent:${p.tokenId}"
                Principal.Anonymous -> "anonymous"
            }
        }
        labels shouldBe listOf("human:builtin/u1", "agent:pb_abc", "anonymous")
    }

    test("Human carries non-null issuer + externalId and has value equality") {
        Principal.Human("builtin", "u1") shouldBe Principal.Human("builtin", "u1")
        (Principal.Human("builtin", "u1") == Principal.Human("proxy", "u1")) shouldBe false
    }

    test("Anonymous is a singleton (data object)") {
        Principal.Anonymous shouldBeSameInstanceAs Principal.Anonymous
    }
})

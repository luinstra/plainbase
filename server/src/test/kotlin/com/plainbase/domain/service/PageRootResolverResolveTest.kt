package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.frameworks.ktor.AmbiguousIdMap
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * The Option B resolver core (C4, 6l): `resolve` selects id_map-FIRST, so a [IdResolution.One] ALWAYS names a durable
 * claimant (`resolve(One).root in rootsHoldingId(id)`), and the claimant COUNT is unconditional (a snapshot-present
 * id with two durable claimants is still Ambiguous - the resolver never consults a snapshot). The FAKE is the only
 * way to pose Ambiguity under `UNIQUE(id)`. RED: none of these hold if `resolve` reverted to snapshot-first.
 */
class PageRootResolverResolveTest : FunSpec({

    val main = RootName.PRIMARY
    val notes = RootName.require("notes")
    val extra = RootName.require("extra")
    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    /** One resolver over a throwaway temp tree + in-memory DB, both released when [block] returns. */
    fun withResolver(liveRoots: List<RootName>, block: (PageRootResolver) -> Unit) {
        val dir = Files.createTempDirectory("pb-resolver-test")
        try {
            val registry = RootRegistry.of(listOf(localRoot("main", dir), localRoot("notes", dir), localRoot("extra", dir)))
            DatabaseFactory.createInMemoryDriver().use { driver ->
                val real = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                block(PageRootResolver(AmbiguousIdMap(real, id, liveRoots = liveRoots), registry))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("One always names a durable claimant: resolve(One).root in rootsHoldingId(id)") {
        listOf(listOf(main), listOf(notes), listOf(extra)).forEach { live ->
            withResolver(live) { resolver ->
                val res = resolver.resolve(id)
                (res as IdResolution.One)
                resolver.bindsLive(res.root, id) shouldBe true // == root in rootsHoldingId(id)
                res.root shouldBe live.single()
            }
        }
    }

    test("no claimant -> None") {
        withResolver(emptyList()) { it.resolve(id) shouldBe IdResolution.None }
    }

    test("COUNT is unconditional: two durable claimants -> Ambiguous, ranked in D7 order (never collapsed by a snapshot)") {
        // main and notes both hold it - FAKE-only, since UNIQUE(id) forbids two real live rows. Snapshot presence is
        // irrelevant: the resolver reads only rootsHoldingId.
        withResolver(listOf(notes, main)) { resolver ->
            val res = resolver.resolve(id)
            (res as IdResolution.Ambiguous).candidates shouldContainExactly listOf(main, notes) // main outranks notes (D7)
        }
    }

    test("an UNREGISTERED claimant is filtered out (detached root)") {
        // rootsHoldingId names a root not in the registry -> None (a binding under a detached root).
        withResolver(listOf(RootName.require("ghost"))) { it.resolve(id) shouldBe IdResolution.None }
    }

    // ---- resolvePinned: the pinned-WRITE validation the four mutating entries share ----------------------

    test("resolvePinned: a DETACHED pin is None even when it durably binds the id") {
        val ghost = RootName.require("ghost") // holds the binding, but the registry has never heard of it
        withResolver(listOf(ghost)) { it.resolvePinned(ghost, id) shouldBe IdResolution.None }
    }

    test("resolvePinned: a REGISTERED pin that holds no live binding is None (fail-CLOSED, never a re-resolve)") {
        // `notes` is the durable claimant; pinning `main` must NOT walk the write into notes - it reads as gone.
        withResolver(listOf(notes)) { it.resolvePinned(main, id) shouldBe IdResolution.None }
    }

    test("resolvePinned: a registered pin holding a LIVE binding is One(pin)") {
        withResolver(listOf(extra)) { it.resolvePinned(extra, id) shouldBe IdResolution.One(extra) }
    }

    // There used to be a row here posing an UNAVAILABLE snapshot and asserting the pin still resolved One. It is gone
    // because `resolvePinned` no longer TAKES a snapshot: "UNAVAILABLE is not the pin's business" is now a fact about
    // the signature rather than a behavior a test has to defend, and the caller's own gate still owns the 503.
})

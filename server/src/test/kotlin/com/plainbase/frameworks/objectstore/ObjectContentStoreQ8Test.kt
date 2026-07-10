package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.service.CitationFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The Q8a/Q8b/Q8c/Q8d injected-failure arms that are not clean oracle-pair properties, plus the
 * REV 4 map-absent CAS sub-cases (seam h) - every arm named in the C4 addendum step 5.
 */
class ObjectContentStoreQ8Test : FunSpec({

    val hasher = CitationFactory()::contentHash

    test("Q8a CAS: the PUT threw ambiguously but did NOT land (etag == prior) => Unreadable(false)") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8a-not-landed.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.fake.ambiguousBeforeApply += key // throws before the write applies - nothing landed

            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), "v2".toByteArray(), hasher)

            val unreadable = result.shouldBeInstanceOf<CasResult.Unreadable>()
            unreadable.targetMutated shouldBe false
            hybrid.fake.currentBytes(key) shouldBe original // nothing landed at the bucket
        }
    }

    test("Q8a CAS: the PUT threw ambiguously but DID land (our own bytes) => Written, apply completes") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8a-landed.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            val updated = "v2".toByteArray()
            hybrid.fake.ambiguousAfterApply += key // the write DOES land, then the client sees a throw

            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), updated, hasher)

            result shouldBe CasResult.Written(hasher(updated))
            hybrid.store.read(path) shouldBe updated
        }
    }

    test(
        "Q8a CAS: the PUT threw ambiguously and an EXTERNAL writer's bytes are what the bucket now holds " +
            "=> Mismatch(the external bytes), healing the mirror",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8a-external-winner.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            // Model "our PUT throws ambiguously, but a concurrent writer's bytes are what actually landed":
            // the map still names the ORIGINAL etag (our own prior HEAD/GET would have seen it), so the
            // disambiguation's `stat.etag == priorEtag` check must see something DIFFERENT - advance the
            // bucket to the external winner's bytes/etag before the CAS runs.
            hybrid.fake.seed(key, "external wins".toByteArray())
            hybrid.fake.ambiguousBeforeApply += key

            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), "our bytes".toByteArray(), hasher)

            val mismatch = result.shouldBeInstanceOf<CasResult.Mismatch>()
            mismatch.currentBytes shouldBe "external wins".toByteArray()
            hybrid.store.read(path) shouldBe "external wins".toByteArray() // healed
        }
    }

    test("Q8a CAS: the disambiguating read-back itself fails => outcome_unknown, mark retained (targetMutated=true)") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8a-unknown.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.fake.ambiguousBeforeApply += key
            // The PUT itself throws ambiguously (op #0, via ambiguousBeforeApply); the disambiguating
            // HEAD that follows (op #1+) must ALSO fail - isolated via a call-index hook so the PUT's
            // OWN ambiguous throw fires first, undisturbed.
            val opIndex = java.util.concurrent.atomic.AtomicInteger(0)
            hybrid.fake.onNetworkOp = { if (opIndex.getAndIncrement() >= 1) throw ObjectStoreException("simulated disambiguation failure") }

            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), "v2".toByteArray(), hasher)

            val unreadable = result.shouldBeInstanceOf<CasResult.Unreadable>()
            unreadable.targetMutated shouldBe true
            unreadable.cause shouldContain "outcome_unknown"
        }
    }

    test("map-absent CAS (a): a cache-miss read-back agrees with baseHash and the PUT succeeds => Written") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("map-absent-written.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            hybrid.state.invalidate(path) // force the map-absent path - no `!!`

            val updated = "v2".toByteArray()
            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), updated, hasher)

            result shouldBe CasResult.Written(hasher(updated))
            hybrid.state.etagOf(path).shouldNotBeNull() // healed by the successful apply
        }
    }

    test(
        "map-absent CAS (b): a concurrent writer lands strictly BETWEEN our read-back and our conditional " +
            "PUT (a genuine PreconditionFailed, not just a pre-PUT base-hash mismatch) => Mismatch(bucketBytes)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("map-absent-precondition.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            hybrid.state.invalidate(path) // force the map-absent read-back path
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            val concurrent = "concurrent write".toByteArray()
            // The map-absent path issues exactly one GET (the read-back) THEN one PUT. Race the concurrent
            // writer in on the SECOND network op - right before our PUT's own precondition check - so our
            // baseHash compare (against the read-back's ORIGINAL bytes) still PASSES, and the PUT itself is
            // what genuinely gets refused (the bucket moved out from under our captured etag).
            val opIndex = java.util.concurrent.atomic.AtomicInteger(0)
            hybrid.fake.onNetworkOp = { if (opIndex.getAndIncrement() == 1) hybrid.fake.seed(key, concurrent) }

            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), "our bytes".toByteArray(), hasher)

            val mismatch = result.shouldBeInstanceOf<CasResult.Mismatch>()
            mismatch.currentBytes shouldBe concurrent
            hybrid.store.read(path) shouldBe concurrent // healed
        }
    }

    test("map-absent CAS (c): the cache-miss read-back GET itself throws => Unreadable(targetMutated=false), never NPE") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("map-absent-unreadable.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            hybrid.state.invalidate(path)
            hybrid.fake.connectRefusal = true

            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), "v2".toByteArray(), hasher)

            val unreadable = result.shouldBeInstanceOf<CasResult.Unreadable>()
            unreadable.targetMutated shouldBe false
        }
    }

    test(
        "Q8b: post-Stored mirror-write failure => entry absent, mark-retained Unreadable(true), tagged, " +
            "immediate reconcile heals, and a subsequent honest re-save precondition-fails (never a lost update)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8b-cas.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            val updated = "v2".toByteArray()
            // Fail the initial apply's two retries, then heal on the immediate reconcile (3rd call).
            hybrid.mirrorAtomics.failFirst(2)

            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), updated, hasher)

            val unreadable = result.shouldBeInstanceOf<CasResult.Unreadable>()
            unreadable.targetMutated shouldBe true
            unreadable.cause shouldContain "durable_but_unmirrored"
            // The immediate reconcile healed the entry (the 3rd mirror-write attempt succeeds).
            hybrid.state.etagOf(path).shouldNotBeNull()
            hybrid.store.read(path) shouldBe updated

            // A follow-up save honestly precondition-fails at the bucket if it retries the STALE base
            // hash (the client's own prior submission was durable; retrying with the OLD baseHash now
            // mismatches the healed, current bytes) - never a silently lost update.
            val retry = hybrid.store.compareAndSwapWrite(path, hasher(original), "yet another".toByteArray(), hasher)
            val mismatch = retry.shouldBeInstanceOf<CasResult.Mismatch>()
            mismatch.currentBytes shouldBe updated
        }
    }

    test(
        "Q8b retry-honesty end-to-end: a client retry with the SAME baseHash after a mirror-apply " +
            "failure heals via GET, fails the base compare, and resolves Mismatch(currentBytes == the " +
            "client's OWN submitted bytes) - then a rebased follow-up save no-ops to success",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8b-retry-honesty.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original)
            val submitted = "the client's own bytes".toByteArray()
            hybrid.mirrorAtomics.failFirst(2) // the initial save's mirror write fails; reconcile heals it

            val first = hybrid.store.compareAndSwapWrite(path, hasher(original), submitted, hasher)
            first.shouldBeInstanceOf<CasResult.Unreadable>()
            hybrid.store.read(path) shouldBe submitted // durable at the bucket AND healed by the reconcile

            // The client's blind retry, unaware the save actually landed, resends the SAME baseHash.
            val retry = hybrid.store.compareAndSwapWrite(path, hasher(original), submitted, hasher)
            val mismatch = retry.shouldBeInstanceOf<CasResult.Mismatch>()
            mismatch.currentBytes shouldBe submitted // == the client's own submission, byte for byte
            mismatch.currentHash shouldBe hasher(submitted)

            // A rebased follow-up (same bytes, current hash as base) no-ops to success.
            val rebased = hybrid.store.compareAndSwapWrite(path, hasher(submitted), submitted, hasher)
            rebased shouldBe CasResult.Written(hasher(submitted))
        }
    }

    test(
        "Q8b create twin: post-create-PUT mirror-write failure => Unreadable(targetMutated=true), " +
            "no map entry, tagged, mark retained (the C2 field used)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8b-create.md")
            val bytes = "fresh".toByteArray()
            hybrid.mirrorAtomics.failAlways() // never heals - proves the field is USED, no NPE either way

            val result = hybrid.store.createExclusive(path, bytes, hasher)

            val unreadable = result.shouldBeInstanceOf<CreateResult.Unreadable>()
            unreadable.targetMutated shouldBe true
            unreadable.cause shouldContain "durable_but_unmirrored"
            hybrid.state.etagOf(path).shouldBeNull() // never recorded over a failed mirror write
        }
    }

    test(
        "Q8c: the audit HEAD runs exactly once per write() call, drift logs a WARN, and never gates " +
            "(a null or a throwing HEAD still lets the unconditional PUT land)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8c-write.md")
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.mirror.write(path, "seed".toByteArray()) // mirror-only - the bucket has never seen this key
            hybrid.mirror.scan()

            hybrid.store.write(path, "unconditional".toByteArray()) // HEAD sees null (bucket-absent) - must not gate
            hybrid.fake.headCalls[key] shouldBe 1
            hybrid.store.read(path) shouldBe "unconditional".toByteArray()

            // A throwing HEAD (isolated via `failNextHead`, distinct from the PUT itself failing - that
            // class is ObjectOutageTest's job) must not gate either: the unconditional PUT still lands.
            hybrid.fake.failNextHead = true
            hybrid.store.write(path, "unconditional-2".toByteArray())
            hybrid.store.read(path) shouldBe "unconditional-2".toByteArray()

            // Drift: the map names a DIFFERENT prior etag than what HEAD now reports - a WARN-worthy
            // audit outcome, still non-gating.
            hybrid.seedExisting(TreePath.require("q8c-drift.md"), "v1".toByteArray())
            val driftPath = TreePath.require("q8c-drift.md")
            val driftKey = hybrid.mirror.resolveRepoRelativePath(driftPath)
            hybrid.fake.seed(driftKey, "an external drift".toByteArray()) // advances the bucket etag behind our back
            hybrid.store.write(driftPath, "overwritten anyway".toByteArray())
            hybrid.store.read(driftPath) shouldBe "overwritten anyway".toByteArray()
        }
    }

    test(
        "create-family NFC-collision close (codex R2): a Q8b durable_but_unmirrored create resolves to " +
            "Exists on retry (via the exact-key IfAbsent PUT -> 412 -> GET-heal), keeping EXACTLY ONE bucket " +
            "key and healing the mirror - NO dirtyPaths() pre-heal needed (opus R3)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("collide.md")
            hybrid.mirrorAtomics.failAlways() // the create's apply AND its immediate reconcile heal both fail (Q8b)

            val first = hybrid.store.createExclusive(path, "durable but unmirrored".toByteArray(), hasher)
            first.shouldBeInstanceOf<CreateResult.Unreadable>() // durable at the bucket, absent from the mirror
            hybrid.mirror.read(path).shouldBeNull()
            hybrid.fake.keyCount() shouldBe 1 // the bucket holds the object

            hybrid.mirrorAtomics.shouldFail = { false } // the mirror is writable again
            val second = hybrid.store.createExclusive(path, "a different generation".toByteArray(), hasher)

            second shouldBe CreateResult.Exists(path) // IfAbsent hits our own key -> 412 -> existsAfterRefusedCreate
            hybrid.fake.keyCount() shouldBe 1 // NOT two NFC-equivalent keys
            // The mirror was healed from the bucket ON DISK by existsAfterRefusedCreate's GET-heal (mirror.read
            // is snapshot-based and this create did not rescan; the gate's live directory listing saw it).
            java.nio.file.Files.exists(hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(path))) shouldBe true
        }
    }

    test(
        "create-family NFC-collision close, writeAssetExclusive twin: a Q8b durable_but_unmirrored asset " +
            "resolves to Exists on retry (one bucket key), via the same IfAbsent -> 412 -> GET-heal path",
    ) {
        HybridFixture().use { hybrid ->
            java.nio.file.Files.createDirectories(hybrid.mirrorRoot.resolve("folder"))
            hybrid.mirror.scan()
            val path = TreePath.require("folder/pic.png")
            hybrid.mirrorAtomics.failAlways()

            val first = hybrid.store.writeAssetExclusive(grantForTests(), path, "binary v1".toByteArray(), hasher)
            first.shouldBeInstanceOf<CreateResult.Unreadable>()
            hybrid.fake.keyCount() shouldBe 1

            hybrid.mirrorAtomics.shouldFail = { false }
            val second = hybrid.store.writeAssetExclusive(grantForTests(), path, "binary v2".toByteArray(), hasher)

            second shouldBe CreateResult.Exists(path)
            hybrid.fake.keyCount() shouldBe 1
        }
    }

    test(
        "clean create is network-light (opus R3): a fresh createExclusive - WITH the path already " +
            "write-ahead-marked dirty, exactly as WritePipeline.create does before calling it - issues " +
            "EXACTLY ONE PUT and ZERO GET/HEAD/LIST (locks out any dirtyPaths()-gated read-back regression)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("fresh.md")
            hybrid.dirtyPaths += path // production ALWAYS marks the page dirty before createExclusive

            val result = hybrid.store.createExclusive(path, "brand new".toByteArray(), hasher)

            result.shouldBeInstanceOf<CreateResult.Created>()
            hybrid.fake.putCount shouldBe 1
            hybrid.fake.getCount shouldBe 0 // NOT re-introduced by a dirtyPaths() disjunct
            hybrid.fake.headCount shouldBe 0
            hybrid.fake.listCount shouldBe 0
        }
    }

    test(
        "finding-6 recovery on create: a create over a path whose mirror file was DELETED but whose " +
            "MirrorState entry survived eagerly heals from the bucket first, then returns Exists (one key)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("recovered.md")
            hybrid.seedExisting(path, "on the bucket".toByteArray()) // bucket + mirror + state consistent
            // Simulate `rm DATA_DIR/mirror/recovered.md` while mirror-state survives (deletable derived state).
            java.nio.file.Files.delete(hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(path)))
            hybrid.mirror.scan()
            hybrid.store.read(path).shouldBeNull() // gone from the mirror; state.etagOf still present

            val result = hybrid.store.createExclusive(path, "a fresh attempt".toByteArray(), hasher)

            result shouldBe CreateResult.Exists(path) // healed at the gate -> the sibling is visible
            hybrid.fake.keyCount() shouldBe 1 // no second key
        }
    }

    test(
        "BLOCKING 3: a mirror-read IOException on the CAS state-hit path is handled TYPED - it recovers via " +
            "the bucket authority instead of escaping UNTYPED after WritePipeline's write-ahead dirty mark",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("readfault.md")
            val bytes = "v1".toByteArray()
            hybrid.seedExisting(path, bytes) // state.etagOf != null -> the cache-hit mirror.read path
            val onDisk = hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(path))
            // Make the mirror file UNREADABLE so mirror.read()'s Files.readAllBytes throws AccessDeniedException.
            val enforced = try {
                java.nio.file.Files.setPosixFilePermissions(onDisk, emptySet())
                runCatching { java.nio.file.Files.readAllBytes(onDisk) }.isFailure
            } catch (_: UnsupportedOperationException) {
                false // non-POSIX filesystem
            }
            if (enforced) {
                // With the fix the untyped IOException is caught and the CAS reads the bucket authority back
                // (never escapes), resolving the write against the durable bucket bytes.
                val result = hybrid.store.compareAndSwapWrite(path, hasher(bytes), "v2".toByteArray(), hasher)
                result shouldBe CasResult.Written(hasher("v2".toByteArray()))
            }
            // If the platform does not enforce read perms (root / non-POSIX), there is nothing to inject - skip.
        }
    }

    test(
        "BLOCKING: Q8b where BOTH the mirror write AND the subsequent MirrorState.persist() fault (a full disk) " +
            "still returns a TYPED Unreadable(durable_but_unmirrored, targetMutated=true), never an escaped 500",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("q8b-persist.md")
            val original = "v1".toByteArray()
            hybrid.seedExisting(path, original) // records state + persists while both atomics are healthy
            hybrid.mirrorAtomics.failAlways() // the mirror write (and its reconcile heal) fail -> Q8b
            hybrid.stateAtomics.failAlways() // the invalidate-then-persist flush ALSO faults - the full-disk case

            val result = hybrid.store.compareAndSwapWrite(path, hasher(original), "v2".toByteArray(), hasher)

            val unreadable = result.shouldBeInstanceOf<CasResult.Unreadable>()
            unreadable.targetMutated shouldBe true
            unreadable.cause shouldContain "durable_but_unmirrored"
        }
    }
})

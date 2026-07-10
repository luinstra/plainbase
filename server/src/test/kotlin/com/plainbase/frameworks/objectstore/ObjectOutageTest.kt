package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.CitationFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Q13 (fail-closed outage) + R16 (fail-closed TLS/signature self-check) arms, as a dedicated file.
 */
class ObjectOutageTest : FunSpec({

    val hasher = CitationFactory()::contentHash

    test("outage: reads keep serving mirror bytes (delegation is local, no network involved)") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("readable.md")
            val bytes = "still here".toByteArray()
            hybrid.seedExisting(path, bytes)
            hybrid.fake.connectRefusal = true

            hybrid.store.read(path) shouldBe bytes
            hybrid.store.scan().files.map { it.path } shouldBe listOf(path)
            hybrid.store.list(null).map { it.path } shouldBe listOf(path)
        }
    }

    test("outage: CAS on an outage => Unreadable(targetMutated=false), the frozen retryable 503") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("cas-outage.md")
            val bytes = "v1".toByteArray()
            hybrid.seedExisting(path, bytes)
            hybrid.fake.connectRefusal = true

            val result = hybrid.store.compareAndSwapWrite(path, hasher(bytes), "v2".toByteArray(), hasher)

            val unreadable = result.shouldBeInstanceOf<CasResult.Unreadable>()
            unreadable.targetMutated shouldBe false
        }
    }

    test("outage: createExclusive on an outage => Unreadable(targetMutated=false)") {
        HybridFixture().use { hybrid ->
            hybrid.fake.connectRefusal = true
            val result = hybrid.store.createExclusive(TreePath.require("new.md"), "x".toByteArray(), hasher)
            val unreadable = result.shouldBeInstanceOf<com.plainbase.domain.content.CreateResult.Unreadable>()
            unreadable.targetMutated shouldBe false
        }
    }

    test("outage: write() retries once then throws (the port contract), never silently swallowing the failure") {
        HybridFixture().use { hybrid ->
            hybrid.fake.connectRefusal = true
            shouldThrow<java.net.ConnectException> {
                hybrid.store.write(TreePath.require("audit.md"), "x".toByteArray())
            }
        }
    }

    test("outage: the poll logs and mutates nothing, retrying next cycle") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("poll-outage.md")
            hybrid.seedExisting(path, "v1".toByteArray())
            hybrid.fake.connectRefusal = true

            val events = mutableListOf<TreePath>()
            hybrid.store.pollOnce { events += it }

            events.shouldBeEmpty()
            hybrid.store.read(path) shouldBe "v1".toByteArray() // untouched
        }
    }

    test("outage: object-mode boot (hydrate) against a connect-refusing bucket fails fast, actionably") {
        HybridFixture().use { hybrid ->
            hybrid.fake.connectRefusal = true
            val failure = shouldThrow<ObjectStoreException> { hybrid.store.hydrate() }
            failure.message shouldContain "unreachable"
        }
    }

    test(
        "R16: object-mode boot against a TLS/signature-rejecting bucket refuses operator-actionably, " +
            "naming the TLS cause specifically - never disable certificate validation",
    ) {
        HybridFixture().use { hybrid ->
            hybrid.fake.tlsRejection = true
            val failure = shouldThrow<ObjectStoreException> { hybrid.store.hydrate() }
            failure.message shouldContain "TLS"
            failure.message shouldContain "never disable certificate validation"
        }
    }
})

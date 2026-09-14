package com.plainbase.frameworks.lifecycle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.koin.core.error.ClosedScopeException
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Slice1 controls for ownership, constructor admission and rollback. */
class ServerResourceOwnerTest : FunSpec({

    test("should close owned resources in phase order and leave borrowed objects untouched") {
        val owner = ServerResourceOwner()
        val closed = ConcurrentLinkedQueue<String>()

        owner.own(ServerResourcePhase.APP_DATABASE, "driver") { closed += it }
        owner.own(ServerResourcePhase.SEARCH_DATABASE, "search") { closed += it }
        val borrowedCloseCount = AtomicInteger()
        val borrowedObject = AutoCloseable { borrowedCloseCount.incrementAndGet() }

        owner.close()
        owner.close()

        closed.toList() shouldContainExactly listOf("search", "driver")
        borrowedCloseCount.get() shouldBe 0
        borrowedObject.close()
        borrowedCloseCount.get() shouldBe 1
    }

    test("should reject self-drain before sealing and roll back acquired entries on construction failure") {
        val owner = ServerResourceOwner()
        val closeCount = AtomicInteger()
        val effects = AtomicInteger()
        val primary = IllegalStateException("provider failed")

        val selfDrain = shouldThrow<IllegalStateException> {
            owner.construct("self-draining provider") { owner.drainServices() }
        }
        selfDrain.message shouldContain "cannot drain"

        val selfClose = shouldThrow<IllegalStateException> {
            owner.construct("self-closing provider") { owner.close() }
        }
        selfClose.message shouldContain "cannot drain"

        owner.construct("post-self-drain provider") { effects.incrementAndGet() }
        effects.get() shouldBe 1

        val actual = shouldThrow<IllegalStateException> {
            owner.construct("failing provider") {
                owner.own(ServerResourcePhase.SEARCH_DATABASE, Any()) { closeCount.incrementAndGet() }
                throw primary
            }
        }

        actual shouldBe primary
        closeCount.get() shouldBe 1
        owner.close()
        closeCount.get() shouldBe 1
    }

    test("should let an admitted nested construction finish after sealing while rejecting new work") {
        val owner = ServerResourceOwner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nested = CountDownLatch(1)
        val constructorFailure = AtomicReference<Throwable?>()
        val drainerFailure = AtomicReference<Throwable?>()

        val constructor = thread(name = "owner-constructor") {
            runCatching {
                owner.construct("outer provider") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "constructor release timed out" }
                    owner.construct("nested provider") {
                        owner.own(ServerResourcePhase.APP_DATABASE, Any()) { nested.countDown() }
                    }
                }
            }.onFailure(constructorFailure::set)
        }
        var drainer: Thread? = null
        try {
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            drainer = thread(name = "owner-drainer") {
                runCatching { owner.drainServices() }.onFailure(drainerFailure::set)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var rejected = false
            while (System.nanoTime() < deadline && !rejected) {
                try {
                    owner.construct("independent probe") { Unit }
                } catch (failure: IllegalStateException) {
                    failure.message shouldContain "sealed"
                    rejected = true
                }
                if (!rejected) Thread.yield()
            }
            rejected shouldBe true
            release.countDown()
            constructor.join(5_000)
            val currentDrainer = checkNotNull(drainer)
            currentDrainer.join(5_000)

            constructor.isAlive shouldBe false
            currentDrainer.isAlive shouldBe false
            constructorFailure.get() shouldBe null
            drainerFailure.get() shouldBe null
            nested.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            release.countDown()
            constructor.join(5_000)
            drainer?.join(5_000)
            if (!constructor.isAlive && drainer?.isAlive != true) {
                owner.close()
            } else {
                error("owner constructor/drainer did not stop before cleanup")
            }
        }
    }

    test("should admit an owner-driven nested Koin construction after service drain seals") {
        val owner = ServerResourceOwner()
        val app = koinApplication()
        owner.own(ServerResourcePhase.KOIN_CONTEXT, app) { it.close() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        app.modules(
            module {
                single<Int> { owner.construct("nested Koin provider") { 42 } }
                single<String> {
                    owner.construct("outer Koin provider") {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "constructor release timed out" }
                        "value=${get<Int>()}"
                    }
                }
            },
        )

        val value = AtomicReference<String>()
        val failure = AtomicReference<Throwable?>()
        val drainerFailure = AtomicReference<Throwable?>()
        val resolver = thread(name = "koin-owner-constructor") {
            runCatching { app.koin.get<String>() }
                .onSuccess(value::set)
                .onFailure(failure::set)
        }
        var drainer: Thread? = null
        try {
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val currentDrainer = thread(name = "koin-owner-drainer") {
                runCatching { owner.drainServices() }.onFailure(drainerFailure::set)
            }
            drainer = currentDrainer
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var rejected = false
            while (System.nanoTime() < deadline && !rejected) {
                try {
                    owner.construct("independent post-seal provider") { Unit }
                } catch (postSeal: IllegalStateException) {
                    postSeal.message shouldContain "sealed"
                    rejected = true
                }
                if (!rejected) Thread.yield()
            }
            rejected shouldBe true
            release.countDown()
            resolver.join(5_000)
            currentDrainer.join(5_000)

            resolver.isAlive shouldBe false
            currentDrainer.isAlive shouldBe false
            failure.get() shouldBe null
            drainerFailure.get() shouldBe null
            value.get() shouldBe "value=42"
        } finally {
            release.countDown()
            resolver.join(5_000)
            drainer?.join(5_000)
            if (!resolver.isAlive && drainer?.isAlive != true) {
                owner.close()
            } else {
                error("Koin owner constructor/drainer did not stop before cleanup")
            }
        }
    }

    test("should roll back a standalone Koin construction after Koin closes its scope first") {
        val owner = ServerResourceOwner()
        val app = koinApplication()
        owner.own(ServerResourcePhase.KOIN_CONTEXT, app) { it.close() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCallbackEntered = CountDownLatch(1)
        app.modules(
            module {
                single<Int> { owner.construct("nested closed-scope provider") { 42 } }
                single<AutoCloseable> { AutoCloseable {} } onClose {
                    closeCallbackEntered.countDown()
                    owner.drainServices()
                }
                single<String> {
                    owner.construct("outer closed-scope provider") {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "constructor release timed out" }
                        "value=${get<Int>()}"
                    }
                }
            },
        )

        app.koin.get<AutoCloseable>()
        val failure = AtomicReference<Throwable?>()
        val closerFailure = AtomicReference<Throwable?>()
        val resolver = thread(name = "standalone-koin-constructor") {
            runCatching { app.koin.get<String>() }.onFailure(failure::set)
        }
        var closer: Thread? = null
        try {
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val currentCloser = thread(name = "standalone-koin-closer") {
                runCatching { app.close() }.onFailure(closerFailure::set)
            }
            closer = currentCloser
            closeCallbackEntered.await(5, TimeUnit.SECONDS) shouldBe true
            release.countDown()
            resolver.join(5_000)
            currentCloser.join(5_000)

            resolver.isAlive shouldBe false
            currentCloser.isAlive shouldBe false
            closerFailure.get() shouldBe null
            val actualFailure = requireNotNull(failure.get())
            var current: Throwable? = actualFailure
            var sawClosedScope = false
            while (current != null) {
                if (current is ClosedScopeException) sawClosedScope = true
                current = current.cause
            }
            sawClosedScope shouldBe true
        } finally {
            release.countDown()
            resolver.join(5_000)
            closer?.join(5_000)
            if (!resolver.isAlive && closer?.isAlive != true) {
                owner.close()
            } else {
                error("standalone Koin constructor/closer did not stop before cleanup")
            }
        }
    }

    test("should drain from uncreated and aliased Koin callbacks without resolving at close") {
        val owner = ServerResourceOwner()
        val app = koinApplication()
        val callbackCount = AtomicInteger()
        val handle = TrackedHandle()
        owner.own(ServerResourcePhase.SEARCH_DATABASE, handle) { it.close() }
        owner.own(ServerResourcePhase.KOIN_CONTEXT, app) { it.close() }
        app.modules(
            module {
                (
                    single<TrackedHandle> {
                    owner.construct("aliased owned resource") { handle }
                    } bind TrackedAlias::class
                ).onClose {
                    callbackCount.incrementAndGet()
                    owner.drainServices()
                }
                single<TrackedHandle>(named("uncreated")) { error("uncreated resource must not resolve during close") }
                    .onClose {
                        callbackCount.incrementAndGet()
                        owner.drainServices()
                    }
            },
        )

        app.koin.get<TrackedAlias>()
        app.close()

        callbackCount.get() shouldBe 3
        handle.closed shouldBe true
        handle.closeCount.get() shouldBe 1
        owner.close()
    }

    test("should retain a cached nested owned dependency when a later outer provider fails") {
        val owner = ServerResourceOwner()
        val app = koinApplication()
        owner.own(ServerResourcePhase.KOIN_CONTEXT, app) { it.close() }
        val failingEntered = CountDownLatch(1)
        val failingRelease = CountDownLatch(1)
        val holderEntered = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)
        val failingResult = AtomicReference<Throwable?>()
        val holderResult = AtomicReference<Throwable?>()
        val holderValue = AtomicReference<String>()
        val handleRef = AtomicReference<TrackedHandle>()

        app.modules(
            module {
                single<TrackedHandle> {
                    owner.construct("nested owned dependency") {
                        TrackedHandle().also { handle ->
                            handleRef.set(handle)
                            owner.own(ServerResourcePhase.SEARCH_DATABASE, handle) { it.close() }
                        }
                    }
                }
                single<String>(named("failing")) {
                    owner.construct("failing outer provider") {
                        get<TrackedHandle>()
                        failingEntered.countDown()
                        check(failingRelease.await(5, TimeUnit.SECONDS)) { "failing provider release timed out" }
                        error("outer provider failed")
                    }
                }
                single<String>(named("holder")) {
                    owner.construct("admitted holder provider") {
                        val handle = get<TrackedHandle>()
                        holderEntered.countDown()
                        check(holderRelease.await(5, TimeUnit.SECONDS)) { "holder release timed out" }
                        check(!handle.closed) { "cached dependency closed beneath an admitted user" }
                        "held"
                    }
                }
            },
        )

        val failing = thread(name = "koin-failing-provider") {
            runCatching { app.koin.get<String>(named("failing")) }.onFailure(failingResult::set)
        }
        var holder: Thread? = null
        try {
            failingEntered.await(5, TimeUnit.SECONDS) shouldBe true
            holder = thread(name = "koin-holding-provider") {
                runCatching { app.koin.get<String>(named("holder")) }
                    .onSuccess(holderValue::set)
                    .onFailure(holderResult::set)
            }
            holderEntered.await(5, TimeUnit.SECONDS) shouldBe true
            failingRelease.countDown()
            failing.join(5_000)

            failing.isAlive shouldBe false
            var currentFailure: Throwable? = requireNotNull(failingResult.get())
            var sawPrimary = false
            while (currentFailure != null) {
                if (currentFailure.message?.contains("outer provider failed") == true) sawPrimary = true
                currentFailure = currentFailure.cause
            }
            sawPrimary shouldBe true
            val handle = requireNotNull(handleRef.get())
            handle.closed shouldBe false

            holderRelease.countDown()
            holder.join(5_000)
            holder.isAlive shouldBe false
            holderResult.get() shouldBe null
            holderValue.get() shouldBe "held"
            handle.closed shouldBe false

            owner.drainServices()
            handle.closed shouldBe true
            handle.closeCount.get() shouldBe 1
        } finally {
            failingRelease.countDown()
            holderRelease.countDown()
            failing.join(5_000)
            holder?.join(5_000)
            if (!failing.isAlive && holder?.isAlive != true) {
                owner.close()
            } else {
                error("cached dependency fixture threads did not stop before cleanup")
            }
        }
    }
})

private interface TrackedAlias

private class TrackedHandle : AutoCloseable, TrackedAlias {
    val closeCount = AtomicInteger()

    @Volatile
    var closed = false
        private set

    override fun close() {
        closeCount.incrementAndGet()
        closed = true
    }
}

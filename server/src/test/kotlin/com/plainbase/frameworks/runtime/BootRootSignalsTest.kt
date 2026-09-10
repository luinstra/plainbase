package com.plainbase.frameworks.runtime

import com.plainbase.domain.root.BreakCause
import com.plainbase.domain.root.RootName
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BootRootSignalsTest : FunSpec({

    test("pre-arm notifications drain once in FIFO order and retain reentrant appends") {
        val signals = BootRootSignals()
        val events = mutableListOf<String>()
        val primary = RootName.PRIMARY
        val extra = RootName.require("extra")

        signals.broke(primary, BreakCause.OVERFLOW)
        signals.broke(extra, BreakCause.SCAN_FAILED)
        signals.arm { root, cause ->
            events += "${root.value}:${cause.name}"
            if (cause == BreakCause.OVERFLOW) signals.broke(primary, BreakCause.IDENTITY_REBIND)
        }

        events shouldContainExactly listOf(
            "docs:OVERFLOW",
            "extra:SCAN_FAILED",
            "docs:IDENTITY_REBIND",
        )
        signals.broke(extra, BreakCause.COVERAGE_LOST)
        events.last() shouldBe "extra:COVERAGE_LOST"
    }

    test("startup drain failure preserves the delivered prefix and terminalizes the relay") {
        val signals = BootRootSignals()
        val failure = IllegalStateException("startup callback failed")
        val extra = RootName.require("extra")
        val tail = RootName.require("tail")
        val events = mutableListOf<Pair<RootName, BreakCause>>()
        signals.broke(RootName.PRIMARY, BreakCause.OVERFLOW)
        signals.broke(extra, BreakCause.SCAN_FAILED)
        signals.broke(tail, BreakCause.COVERAGE_LOST)

        val actual = shouldThrow<IllegalStateException> {
            signals.arm { root, cause ->
                events += root to cause
                if (root == extra) throw failure
            }
        }
        actual shouldBeSameInstanceAs failure
        events shouldBe listOf(
            RootName.PRIMARY to BreakCause.OVERFLOW,
            extra to BreakCause.SCAN_FAILED,
        )

        val terminal = shouldThrow<IllegalStateException> {
            signals.broke(tail, BreakCause.COVERAGE_LOST)
        }
        terminal.message shouldBe "boot root signals failed"
        terminal.cause shouldBeSameInstanceAs failure
        val secondArm = shouldThrow<IllegalStateException> { signals.arm { _, _ -> } }
        secondArm.cause shouldBeSameInstanceAs failure
    }

    test("post-arm callback failure is per-call and does not terminalize the relay") {
        val signals = BootRootSignals()
        val failure = IllegalArgumentException("runtime callback failed")
        var calls = 0
        signals.arm { _, _ ->
            calls += 1
            if (calls == 1) throw failure
        }

        val actual = shouldThrow<IllegalArgumentException> {
            signals.broke(RootName.PRIMARY, BreakCause.IDENTITY_REBIND)
        }
        actual shouldBeSameInstanceAs failure
        signals.broke(RootName.PRIMARY, BreakCause.OVERFLOW)
        calls shouldBe 2
    }

    test("second arm is refused after a successful arm") {
        val signals = BootRootSignals()
        signals.arm { _, _ -> }
        shouldThrow<IllegalStateException> { signals.arm { _, _ -> } }
    }

    test("repeated identical queued signals are delivered once per queued event") {
        val signals = BootRootSignals()
        val events = mutableListOf<Pair<RootName, BreakCause>>()
        repeat(3) { signals.broke(RootName.PRIMARY, BreakCause.IDENTITY_REBIND) }

        signals.arm { root, cause -> events += root to cause }

        events shouldBe listOf(
            RootName.PRIMARY to BreakCause.IDENTITY_REBIND,
            RootName.PRIMARY to BreakCause.IDENTITY_REBIND,
            RootName.PRIMARY to BreakCause.IDENTITY_REBIND,
        )
    }

    test("concurrent startup drain blocks C behind queued A and B, then releases in order") {
        val signals = BootRootSignals()
        val extra = RootName.require("extra")
        val third = RootName.require("third")
        val events = Collections.synchronizedList(mutableListOf<String>())
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val startedC = CountDownLatch(1)
        val completedC = CountDownLatch(1)
        val armFailure = AtomicReference<Throwable?>(null)
        var cThread: Thread? = null

        signals.broke(RootName.PRIMARY, BreakCause.OVERFLOW)
        signals.broke(extra, BreakCause.SCAN_FAILED)
        val armThread = thread(start = false, isDaemon = true, name = "boot-signals-arm") {
            try {
                signals.arm { root, cause ->
                    events += "${root.value}:${cause.name}"
                    if (root == RootName.PRIMARY && cause == BreakCause.OVERFLOW) {
                        enteredA.countDown()
                        releaseA.await()
                    }
                }
            } catch (failure: Throwable) {
                armFailure.set(failure)
            }
        }
        armThread.start()

        try {
            check(enteredA.await(1, TimeUnit.SECONDS))
            cThread = thread(start = false, isDaemon = true, name = "boot-signals-C") {
                startedC.countDown()
                signals.broke(third, BreakCause.COVERAGE_LOST)
                completedC.countDown()
            }
            cThread.start()
            check(startedC.await(1, TimeUnit.SECONDS))
            awaitThreadState(requireNotNull(cThread), Thread.State.BLOCKED)
            completedC.await(100, TimeUnit.MILLISECONDS) shouldBe false

            releaseA.countDown()
            armThread.join(1_000)
            requireNotNull(cThread).join(1_000)
            check(!armThread.isAlive) { "startup arm worker did not finish" }
            check(!requireNotNull(cThread).isAlive) { "C worker did not finish" }
            armFailure.get() shouldBe null
            events shouldBe listOf(
                "docs:OVERFLOW",
                "extra:SCAN_FAILED",
                "third:COVERAGE_LOST",
            )
        } finally {
            releaseA.countDown()
            joinSafely(armThread, 1_000)
            cThread?.let { joinSafely(it, 1_000) }
        }
    }
})

private fun awaitThreadState(thread: Thread, expected: Thread.State) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (thread.state != expected && System.nanoTime() < deadline) Thread.yield()
    thread.state shouldBe expected
}

private fun joinSafely(thread: Thread, timeoutMillis: Long) {
    var interrupted = Thread.interrupted()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    try {
        while (thread.isAlive && System.nanoTime() < deadline) {
            try {
                thread.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

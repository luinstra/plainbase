package com.plainbase.frameworks.lifecycle

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Short 02b controls for cleanup-worker identity, constructor interruption, and owner warning integration. */
class ServerResourceOwnerCheckpoint02bTest : FunSpec({

    test("cleanup workers are non-daemon when launched by a daemon caller") {
        val gracefulEntered = CountDownLatch(1)
        val gracefulRelease = CountDownLatch(1)
        val graceful = GracefulShutdown(
            listOf(
                GracefulShutdown.Step("held") {
                    gracefulEntered.countDown()
                    check(gracefulRelease.await(5, TimeUnit.SECONDS)) { "graceful release timed out" }
                },
            ),
        )
        val gracefulCaller = thread(isDaemon = true, name = "02b-graceful-caller") { graceful.run() }
        var gracefulWorker: Thread? = null

        val serviceEntered = CountDownLatch(1)
        val serviceRelease = CountDownLatch(1)
        val serviceOwner = ServerResourceOwner()
        serviceOwner.own(ServerResourcePhase.SCHEDULER, Any()) {
            serviceEntered.countDown()
            check(serviceRelease.await(5, TimeUnit.SECONDS)) { "service release timed out" }
        }
        val serviceCaller = thread(isDaemon = true, name = "02b-service-caller") { serviceOwner.drainServices() }
        var serviceWorker: Thread? = null

        val overallEntered = CountDownLatch(1)
        val overallRelease = CountDownLatch(1)
        val overallOwner = ServerResourceOwner()
        overallOwner.own(ServerResourcePhase.KOIN_CONTEXT, Any()) {
            overallEntered.countDown()
            check(overallRelease.await(5, TimeUnit.SECONDS)) { "overall release timed out" }
        }
        val overallCaller = thread(isDaemon = true, name = "02b-overall-caller") { overallOwner.close() }
        var overallWorker: Thread? = null

        try {
            gracefulEntered.await(5, TimeUnit.SECONDS) shouldBe true
            gracefulWorker = awaitThread(graceful::workerForTest)
            gracefulWorker.isDaemon shouldBe false

            serviceEntered.await(5, TimeUnit.SECONDS) shouldBe true
            serviceWorker = awaitThread(serviceOwner::serviceWorkerForTest)
            serviceWorker.isDaemon shouldBe false

            overallEntered.await(5, TimeUnit.SECONDS) shouldBe true
            overallWorker = awaitThread(overallOwner::overallWorkerForTest)
            overallWorker.isDaemon shouldBe false
        } finally {
            gracefulRelease.countDown()
            serviceRelease.countDown()
            overallRelease.countDown()
            gracefulCaller.join(5_000)
            serviceCaller.join(5_000)
            overallCaller.join(5_000)
            gracefulWorker?.join(5_000)
            serviceWorker?.join(5_000)
            overallWorker?.join(5_000)
        }

        gracefulCaller.isAlive shouldBe false
        serviceCaller.isAlive shouldBe false
        overallCaller.isAlive shouldBe false
        checkNotNull(gracefulWorker).isAlive shouldBe false
        checkNotNull(serviceWorker).isAlive shouldBe false
        checkNotNull(overallWorker).isAlive shouldBe false
    }

    test("constructor wait remembers its consumed interrupt and still closes admitted resources") {
        val now = AtomicLong()
        val owner = ServerResourceOwner(CleanupWarningState(nowNanos = now::get))
        val constructorEntered = CountDownLatch(1)
        val constructorRelease = CountDownLatch(1)
        val resourceClosed = CountDownLatch(1)
        val constructor = thread(name = "02b-admitted-constructor") {
            owner.construct("held provider") {
                owner.own(ServerResourcePhase.HTTP, Any()) { resourceClosed.countDown() }
                constructorEntered.countDown()
                check(constructorRelease.await(5, TimeUnit.SECONDS)) { "constructor release timed out" }
            }
        }
        var caller: Thread? = null
        var worker: Thread? = null
        val warningCapture = WarningCapture().also { it.attach() }
        try {
            constructorEntered.await(5, TimeUnit.SECONDS) shouldBe true
            caller = thread(isDaemon = true, name = "02b-interrupted-drain-caller") { owner.drainServices() }
            worker = awaitThread(owner::serviceWorkerForTest)
            awaitWorkerWaiting(worker)
            now.set(6_000_000_000L)
            owner.warningState.poll()
            worker.interrupt()
            constructorRelease.countDown()
            constructor.join(5_000)
            checkNotNull(caller).join(5_000)
            checkNotNull(worker).join(5_000)

            constructor.isAlive shouldBe false
            checkNotNull(caller).isAlive shouldBe false
            resourceClosed.await(5, TimeUnit.SECONDS) shouldBe true
            worker.isAlive shouldBe false
            worker.isInterrupted shouldBe true
        } finally {
            constructorRelease.countDown()
            worker?.interrupt()
            constructor.join(5_000)
            caller?.join(5_000)
            worker?.join(5_000)
            if (!constructor.isAlive && caller?.isAlive != true) owner.close()
            warningCapture.detach()
        }
        val events = warningCapture.events()
        val warnings = events.filter { it.level == Level.WARN }.map(ILoggingEvent::getFormattedMessage)
        warnings.joinToString("\n") shouldContain "phase 'construction'"
        warnings.none { it.contains("phase 'http server'") } shouldBe true
    }

    test("late admitted ownership warns without moving the initial aggregate forecast") {
        val now = AtomicLong()
        val owner = ServerResourceOwner(CleanupWarningState(nowNanos = now::get))
        val constructorEntered = CountDownLatch(1)
        val constructorRelease = CountDownLatch(1)
        val phaseEntered = CountDownLatch(1)
        val phaseRelease = CountDownLatch(1)
        val resourceClosed = CountDownLatch(1)
        val constructor = thread(name = "02b-late-admitted-constructor") {
            owner.construct("late provider") {
                constructorEntered.countDown()
                check(constructorRelease.await(5, TimeUnit.SECONDS)) { "late constructor release timed out" }
                owner.own(ServerResourcePhase.SEARCH_DATABASE, Any()) {
                    phaseEntered.countDown()
                    check(phaseRelease.await(5, TimeUnit.SECONDS)) { "late phase release timed out" }
                    resourceClosed.countDown()
                }
            }
        }
        var caller: Thread? = null
        var worker: Thread? = null
        val warningCapture = WarningCapture().also { it.attach() }
        try {
            constructorEntered.await(5, TimeUnit.SECONDS) shouldBe true
            caller = thread(isDaemon = true, name = "02b-late-admission-caller") { owner.drainServices() }
            worker = awaitThread(owner::serviceWorkerForTest)
            awaitWorkerWaiting(worker)

            now.set(1_000_000_000L)
            constructorRelease.countDown()
            phaseEntered.await(5, TimeUnit.SECONDS) shouldBe true
            now.set(6_000_000_000L)
            owner.warningState.poll()

            owner.warningState.warningCountForTest() shouldBe 2

            phaseRelease.countDown()
            constructor.join(5_000)
            checkNotNull(caller).join(5_000)
            checkNotNull(worker).join(5_000)
            resourceClosed.await(5, TimeUnit.SECONDS) shouldBe true
            worker.isAlive shouldBe false
        } finally {
            phaseRelease.countDown()
            constructorRelease.countDown()
            worker?.interrupt()
            constructor.join(5_000)
            caller?.join(5_000)
            worker?.join(5_000)
            if (!constructor.isAlive && caller?.isAlive != true) owner.close()
            warningCapture.detach()
        }
        val events = warningCapture.events()
        val warnings = events.filter { it.level == Level.WARN }.map(ILoggingEvent::getFormattedMessage)
        warnings.joinToString("\n") shouldContain "initial aggregate forecast of 5000ms"
        warnings.joinToString("\n") shouldContain "phase 'search database'"
        warnings.joinToString("\n") shouldContain "has been pending for 5000ms"
    }

    test("early owner close initializes aggregate and phase warnings before steps exist") {
        val now = AtomicLong()
        val owner = ServerResourceOwner(CleanupWarningState(nowNanos = now::get))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        owner.own(ServerResourcePhase.APP_DATABASE, Any()) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "early-close release timed out" }
        }

        val warningCapture = WarningCapture().also { it.attach() }
        try {
            val caller = thread(isDaemon = true, name = "02b-early-close-caller") { owner.close() }
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val worker = awaitThread(owner::overallWorkerForTest)
                worker.isDaemon shouldBe false
                now.set(8_000_000_000L)
                owner.warningState.poll()
                owner.warningState.warningCountForTest() shouldBe 2
            } finally {
                release.countDown()
                caller.join(5_000)
            }
            caller.isAlive shouldBe false
        } finally {
            release.countDown()
            warningCapture.detach()
        }
        val events = warningCapture.events()
        val warnings = events.filter { it.level == Level.WARN }.map(ILoggingEvent::getFormattedMessage)
        warnings.joinToString("\n") shouldContain "initial aggregate forecast"
        warnings.joinToString("\n") shouldContain "phase 'app database'"
    }

    test("standalone service and an overall joiner share one phase clock and warning state") {
        val now = AtomicLong()
        val owner = ServerResourceOwner(CleanupWarningState(nowNanos = now::get))
        val serviceEntered = CountDownLatch(1)
        val serviceRelease = CountDownLatch(1)
        val closed = ConcurrentLinkedQueue<String>()
        owner.own(ServerResourcePhase.SCHEDULER, Any()) {
            serviceEntered.countDown()
            check(serviceRelease.await(5, TimeUnit.SECONDS)) { "service phase release timed out" }
            closed += "service"
        }
        owner.own(ServerResourcePhase.KOIN_CONTEXT, Any()) { closed += "koin" }

        val serviceCaller = thread(isDaemon = true, name = "02b-standalone-service-caller") { owner.drainServices() }
        var overallCaller: Thread? = null
        try {
            serviceEntered.await(5, TimeUnit.SECONDS) shouldBe true
            val serviceWorker = awaitThread(owner::serviceWorkerForTest)
            serviceWorker.isDaemon shouldBe false
            overallCaller = thread(isDaemon = true, name = "02b-joining-overall-caller") { owner.close() }
            val overallWorker = awaitThread(owner::overallWorkerForTest)
            overallWorker.isDaemon shouldBe false
            overallWorker.isAlive shouldBe true

            now.set(8_000_000_000L)
            owner.warningState.poll()
            owner.warningState.warningCountForTest() shouldBe 2
            serviceRelease.countDown()
            serviceCaller.join(5_000)
            overallCaller.join(5_000)

            serviceCaller.isAlive shouldBe false
            overallCaller.isAlive shouldBe false
            closed.toList() shouldContainExactly listOf("service", "koin")
        } finally {
            serviceRelease.countDown()
            serviceCaller.join(5_000)
            overallCaller?.join(5_000)
            if (!serviceCaller.isAlive && overallCaller?.isAlive != true) owner.close()
        }
    }
})

private fun awaitThread(read: () -> Thread?): Thread {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        read()?.let { return it }
        Thread.yield()
    }
    return checkNotNull(read()) { "cleanup worker was not published" }
}

private fun awaitWorkerWaiting(worker: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline && worker.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)) {
        Thread.yield()
    }
    worker.state shouldBe Thread.State.TIMED_WAITING
}

private class WarningCapture {
    val logger = LoggerFactory.getLogger(CleanupWarningState::class.java) as Logger
    private val captured = ConcurrentLinkedQueue<ILoggingEvent>()
    private val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            captured += event
        }
    }

    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    fun events(): List<ILoggingEvent> = captured.toList()
}

private fun captureWarningEvents(block: () -> Unit): List<ILoggingEvent> {
    val capture = WarningCapture().also { it.attach() }
    try {
        block()
    } finally {
        capture.detach()
    }
    return capture.events()
}

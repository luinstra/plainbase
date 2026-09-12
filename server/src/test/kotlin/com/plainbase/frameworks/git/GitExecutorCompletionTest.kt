package com.plainbase.frameworks.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.opentest4j.TestAbortedException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/** Checkpoint 03b decision, ownership, and modeled-liveness controls for [GitExecutor]. */
class GitExecutorCompletionTest : FunSpec({

    test("modeled OS identity retains same-PID handles and blocks completion on a later live identity") {
        // Modeled PID reuse only: distinct ProcessHandle doubles share a PID; no operating-system PID is reused here.
        val first = mockk<ProcessHandle>()
        val later = mockk<ProcessHandle>()
        every { first.pid() } returns 4242L
        every { later.pid() } returns 4242L
        every { first.isAlive } returns false
        every { later.isAlive } returns true

        val retention = GitProcessRetention()
        val firstObservation = requireNotNull(retention.retain(first, "descendant"))
        firstObservation.firstStartTicks = 11L
        retention.retain(first, "descendant") shouldBe null
        val laterObservation = requireNotNull(retention.retain(later, "descendant"))
        retention.retain(later, "descendant") shouldBe null

        val snapshot = retention.snapshot()
        snapshot.size shouldBe 2
        snapshot[0] shouldBeSameInstanceAs firstObservation
        snapshot[1] shouldBeSameInstanceAs laterObservation
        snapshot[0].firstStartTicks shouldBe 11L

        retention.allComplete { observation -> !observation.handle.isAlive }.shouldBeFalse()
        every { later.isAlive } returns false
        retention.allComplete { observation -> !observation.handle.isAlive }.shouldBeTrue()
    }

    test("the Linux stat parser uses the final command close and validates identity fields") {
        val stat = parseLinuxProcessStat(procStat(pid = 321, command = "name with ) spaces", state = 'Z', threads = 1, ticks = 88))

        stat shouldNotBe null
        requireNotNull(stat).pid shouldBe 321L
        stat.state shouldBe 'Z'
        stat.numThreads shouldBe 1L
        stat.startTicks shouldBe 88L
        linuxStatProvesOriginalSingleThreadZombie(requireNotNull(stat), expectedPid = 321L, expectedStartTicks = 88L)
            .shouldBeTrue()
        linuxStatProvesOriginalSingleThreadZombie(requireNotNull(stat), expectedPid = 322L, expectedStartTicks = 88L)
            .shouldBeFalse()
        linuxStatProvesOriginalSingleThreadZombie(requireNotNull(stat), expectedPid = 321L, expectedStartTicks = 89L)
            .shouldBeFalse()
    }

    test("synthetic Linux completion decisions remain pending for live states and sibling zombies") {
        // Synthetic stat records only: this is not a live pthread experiment. Bare Z is deliberately insufficient.
        for (state in listOf('R', 'S', 'D', 'T')) {
            val stat = parseLinuxProcessStat(procStat(pid = 321, command = "worker", state = state, threads = 1, ticks = 88))
            linuxStatProvesOriginalSingleThreadZombie(requireNotNull(stat), expectedPid = 321L, expectedStartTicks = 88L)
                .shouldBeFalse()
        }
        val siblingZombie = parseLinuxProcessStat(
            procStat(pid = 321, command = "thread-group leader", state = 'Z', threads = 2, ticks = 88),
        )
        linuxStatProvesOriginalSingleThreadZombie(requireNotNull(siblingZombie), expectedPid = 321L, expectedStartTicks = 88L)
            .shouldBeFalse()
        parseLinuxProcessStat("321 (truncated) Z 0 0") shouldBe null
        parseLinuxProcessStat("321 (worker) Z 0 0 0 0 0") shouldBe null
    }

    test("modeled Linux observation requires known original liveness around stat reads") {
        val zombie = parseLinuxProcessStat(procStat(pid = 321, command = "worker", state = 'Z', threads = 1, ticks = 88))
        val accepted = decideLinuxProcessObservation(
            before = GitHandleLiveness.LIVE,
            stat = zombie,
            after = GitHandleLiveness.LIVE,
            expectedPid = 321L,
            firstStartTicks = null,
        )
        accepted.complete.shouldBeTrue()
        accepted.firstStartTicks shouldBe 88L

        val unknownAfter = decideLinuxProcessObservation(
            before = GitHandleLiveness.LIVE,
            stat = zombie,
            after = GitHandleLiveness.UNKNOWN,
            expectedPid = 321L,
            firstStartTicks = null,
        )
        unknownAfter.complete.shouldBeFalse()
        unknownAfter.firstStartTicks shouldBe null

        val deadAfterReplacement = decideLinuxProcessObservation(
            before = GitHandleLiveness.LIVE,
            stat = parseLinuxProcessStat(
                procStat(pid = 999, command = "replacement", state = 'Z', threads = 1, ticks = 77),
            ),
            after = GitHandleLiveness.DEAD,
            expectedPid = 321L,
            firstStartTicks = null,
        )
        deadAfterReplacement.complete.shouldBeTrue()
        deadAfterReplacement.firstStartTicks shouldBe null

        val stableIdentity = decideLinuxProcessObservation(
            before = GitHandleLiveness.LIVE,
            stat = zombie,
            after = GitHandleLiveness.LIVE,
            expectedPid = 321L,
            firstStartTicks = 89L,
        )
        stableIdentity.complete.shouldBeFalse()
        stableIdentity.firstStartTicks shouldBe 89L
    }

    test("the first abnormal cause wins in barrier-ordered races") {
        val latch = GitAbnormalCauseLatch()
        val firstEstablished = CountDownLatch(1)
        val secondMayRun = CountDownLatch(1)
        val first = thread(start = false, isDaemon = true, name = "cause-first") {
            latch.latch(GitAbnormalCause.INTERRUPTION)
            firstEstablished.countDown()
            secondMayRun.await(1, TimeUnit.SECONDS)
        }
        val second = thread(start = false, isDaemon = true, name = "cause-second") {
            firstEstablished.await(1, TimeUnit.SECONDS)
            latch.latch(GitAbnormalCause.OUTPUT_OVERFLOW)
            secondMayRun.countDown()
        }

        first.start()
        second.start()
        first.join(2_000)
        second.join(2_000)

        latch.cause() shouldBe GitAbnormalCause.INTERRUPTION
        first.isAlive.shouldBeFalse()
        second.isAlive.shouldBeFalse()
    }

    test("an interrupted invocation wins before a later helper setup failure") {
        runInterruptionBeforeHelperSetupFailureCase()
    }

    test("stdout overflow wins before a later interruption") {
        runOverflowBeforeInterruptionCase(OutputStreamKind.STDOUT)
    }

    test("stderr overflow wins before a later interruption") {
        runOverflowBeforeInterruptionCase(OutputStreamKind.STDERR)
    }

    test("helper setup failure wins before a later interruption") {
        runHelperSetupFailureBeforeInterruptionCase()
    }

    test("timeout wins before a later interruption") {
        runTimeoutBeforeInterruptionCase()
    }

    test("controlled fake Git parent exits before its child and returns zero") {
        runControlledParentExitCase(0)
    }

    test("controlled fake Git parent exits before its child and returns nonzero") {
        runControlledParentExitCase(23)
    }

    test("controlled fake Git parent exit keeps the original deadline for a held child") {
        val observedPids = ConcurrentLinkedQueue<Long>()
        val completedHelpers = ConcurrentLinkedQueue<Thread>()
        val helperHold = CountDownLatch(1)
        val helperHoldEntered = CountDownLatch(1)
        val pendingWarningAtNanos = AtomicLong(0L)
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean {
                if (observation.role == "descendant") observedPids += observation.handle.pid()
                return !observation.handle.isAlive
            }

            override fun helperComplete(helper: Thread): Boolean {
                val complete = !helper.isAlive
                if (complete) {
                    completedHelpers += helper
                    if (pendingWarningAtNanos.get() != 0L &&
                        helper.name == "git-stdout-drain" &&
                        helperHoldEntered.count == 1L
                    ) {
                        helperHoldEntered.countDown()
                        return false
                    }
                    if (helper.name == "git-stdout-drain" && helperHoldEntered.count == 0L && helperHold.count != 0L) {
                        return false
                    }
                }
                return complete
            }
        }
        withOwnedFakeGit(
            "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\n" +
                "(while [ ! -f \"${'$'}HOME/release-child\" ]; do sleep 0.01; done) &\n" +
                "echo ${'$'}! > \"${'$'}HOME/child.pid\"\n" +
                "echo ready > \"${'$'}HOME/ancestry-ready\"\n" +
                "while [ ! -f \"${'$'}HOME/release-parent\" ]; do sleep 0.01; done\n" +
                "exit 0\n",
        ) { fixture ->
            val parentRelease = fixture.home.resolve("release-parent")
            val childRelease = fixture.home.resolve("release-child")
            fixture.releaseOnCleanup { Files.writeString(parentRelease, "release\n") }
            fixture.releaseOnCleanup { Files.writeString(childRelease, "release\n") }
            fixture.releaseOnCleanup { helperHold.countDown() }
            val result = AtomicReference<GitResult?>()
            val failure = AtomicReference<Throwable?>()
            val interruptRestored = AtomicBoolean(false)
            val warningAppender = GitPendingWarningAppender("original-deadline-caller") {
                pendingWarningAtNanos.compareAndSet(0L, System.nanoTime())
            }.apply { start() }
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            rootLogger.addAppender(warningAppender)
            val callerEntryAtNanos = AtomicLong(0L)
            val readyAtNanos = AtomicLong(0L)
            val parentExitedAtNanos = AtomicLong(0L)
            val releaseAtNanos = AtomicLong(0L)
            val caller = thread(start = false, isDaemon = true, name = "original-deadline-caller") {
                callerEntryAtNanos.set(System.nanoTime())
                runCatching {
                    result.set(
                        GitExecutor(
                            fixture.root,
                            fixture.home,
                            timeoutSeconds = 1,
                            gitBinary = fixture.binary,
                            completionObserver = observer,
                        ).run(listOf("status")),
                    )
                }.onFailure(failure::set)
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
            fixture.trackWorker(caller)
            try {
                caller.start()
                val parent = fixture.recordPid("parent.pid")
                val child = fixture.recordPid("child.pid")
                waitFor { Files.exists(fixture.home.resolve("ancestry-ready")) }
                waitFor { observedPids.contains(child.pid()) }
                readyAtNanos.set(System.nanoTime())
                val successfulStartUpperBoundNanos = readyAtNanos.get()
                val earliestOriginalDeadlineNanos =
                    callerEntryAtNanos.get() + TimeUnit.SECONDS.toNanos(1)
                val latestOriginalDeadlineNanos =
                    successfulStartUpperBoundNanos + TimeUnit.SECONDS.toNanos(1)
                if (readyAtNanos.get() >= callerEntryAtNanos.get() + TimeUnit.MILLISECONDS.toNanos(500L)) {
                    throw TestAbortedException(
                        "deadline control missed early readiness: startLower=${callerEntryAtNanos.get()} " +
                            "startUpper=$successfulStartUpperBoundNanos ready=${readyAtNanos.get()}",
                    )
                }
                awaitAt(callerEntryAtNanos.get() + TimeUnit.MILLISECONDS.toNanos(PARENT_EXIT_BEFORE_DEADLINE_MILLIS))
                Files.writeString(parentRelease, "release\n")
                waitFor { !parent.isAlive }
                parentExitedAtNanos.set(System.nanoTime())
                if (parentExitedAtNanos.get() >= earliestOriginalDeadlineNanos - TimeUnit.MILLISECONDS.toNanos(500L)) {
                    throw TestAbortedException(
                        "deadline control missed early parent exit: startLower=${callerEntryAtNanos.get()} " +
                            "startUpper=$successfulStartUpperBoundNanos ready=${readyAtNanos.get()} " +
                            "parentExit=${parentExitedAtNanos.get()} originalEarliest=$earliestOriginalDeadlineNanos",
                    )
                }
                parent.isAlive.shouldBeFalse()
                child.isAlive.shouldBeTrue()
                caller.isAlive.shouldBeTrue()
                val resetBudgetEarliestNanos = earliestOriginalDeadlineNanos + TimeUnit.SECONDS.toNanos(4)
                val releaseTargetNanos = maxOf(
                    latestOriginalDeadlineNanos + TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS),
                    readyAtNanos.get() + TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS),
                )
                if (releaseTargetNanos >= resetBudgetEarliestNanos - TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS)) {
                    throw TestAbortedException(
                        "deadline reset window is not separated: startLower=${callerEntryAtNanos.get()} " +
                            "startUpper=$successfulStartUpperBoundNanos ready=${readyAtNanos.get()} " +
                            "parentExit=${parentExitedAtNanos.get()} originalLatest=$latestOriginalDeadlineNanos " +
                            "resetEarliest=$resetBudgetEarliestNanos target=$releaseTargetNanos",
                    )
                }
                awaitAt(releaseTargetNanos)
                val releaseNanos = System.nanoTime()
                releaseAtNanos.set(releaseNanos)
                if (releaseNanos <= latestOriginalDeadlineNanos + TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS) ||
                    releaseNanos >= resetBudgetEarliestNanos - TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS)
                ) {
                    throw TestAbortedException(
                        "deadline reset window missed: startLower=${callerEntryAtNanos.get()} " +
                            "startUpper=$successfulStartUpperBoundNanos ready=${readyAtNanos.get()} " +
                            "parentExit=${parentExitedAtNanos.get()} release=$releaseNanos " +
                            "originalLatest=$latestOriginalDeadlineNanos resetEarliest=$resetBudgetEarliestNanos",
                    )
                }
                val timeoutObservedBeforeRelease =
                    pendingWarningAtNanos.get() != 0L && !child.isAlive && helperHoldEntered.count == 0L
                Files.writeString(childRelease, "release\n")
                if (timeoutObservedBeforeRelease) {
                    waitFor { helperHoldEntered.count == 0L }
                }
                timeoutObservedBeforeRelease.shouldBeTrue()
                caller.isAlive.shouldBeTrue()
                caller.interrupt()
                helperHold.countDown()
                awaitWithin(caller, "original deadline caller")
                failure.get() shouldBe null
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "timed out"
                interruptRestored.get().shouldBeTrue()
                parent.isAlive.shouldBeFalse()
                child.isAlive.shouldBeFalse()
                completedHelpers.toSet().size shouldBe 2
                completedHelpers.toSet().all { !it.isAlive }.shouldBeTrue()
                fixture.assertRecordedProcessesStopped()
            } finally {
                runCatching { Files.writeString(parentRelease, "release\n") }
                runCatching { Files.writeString(childRelease, "release\n") }
                helperHold.countDown()
                if (caller.isAlive) {
                    caller.interrupt()
                    runCatching { awaitWithin(caller, "original deadline caller cleanup") }
                }
                rootLogger.detachAppender(warningAppender)
                warningAppender.stop()
            }
        }
    }

    test("controlled fake Git retains a child that forks after parent exit") {
        val observedPids = ConcurrentLinkedQueue<Long>()
        val completedHelpers = ConcurrentLinkedQueue<Thread>()
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean {
                if (observation.role == "descendant") observedPids += observation.handle.pid()
                return !observation.handle.isAlive
            }

            override fun helperComplete(helper: Thread): Boolean {
                val complete = !helper.isAlive
                if (complete) completedHelpers += helper
                return complete
            }
        }
        withOwnedFakeGit(
            "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\n" +
                "(echo ready > \"${'$'}HOME/child-ready\"; " +
                "while [ ! -f \"${'$'}HOME/fork\" ]; do sleep 0.01; done; " +
                "(while [ ! -f \"${'$'}HOME/release-grandchild\" ]; do sleep 0.01; done) </dev/null >/dev/null 2>/dev/null & " +
                "echo ${'$'}! > \"${'$'}HOME/grandchild.pid\"; " +
                "echo grandchild-ready > \"${'$'}HOME/grandchild-ready\"; " +
                "while [ ! -f \"${'$'}HOME/release-child\" ]; do sleep 0.01; done; " +
                "exit 0) </dev/null >/dev/null 2>/dev/null &\n" +
                "echo ${'$'}! > \"${'$'}HOME/child.pid\"\n" +
                "while [ ! -f \"${'$'}HOME/release-parent\" ]; do sleep 0.01; done\n" +
                "exit 0\n",
        ) { fixture ->
            val parentRelease = fixture.home.resolve("release-parent")
            val fork = fixture.home.resolve("fork")
            val childRelease = fixture.home.resolve("release-child")
            val grandchildRelease = fixture.home.resolve("release-grandchild")
            fixture.releaseOnCleanup { Files.writeString(parentRelease, "release\n") }
            fixture.releaseOnCleanup { Files.writeString(fork, "fork\n") }
            fixture.releaseOnCleanup { Files.writeString(childRelease, "release\n") }
            fixture.releaseOnCleanup { Files.writeString(grandchildRelease, "release\n") }
            val result = AtomicReference<GitResult?>()
            val failure = AtomicReference<Throwable?>()
            val caller = thread(start = false, isDaemon = true, name = "late-fork-caller") {
                runCatching {
                    result.set(
                        GitExecutor(
                            fixture.root,
                            fixture.home,
                            timeoutSeconds = 2,
                            gitBinary = fixture.binary,
                            completionObserver = observer,
                        ).run(listOf("status")),
                    )
                }.onFailure(failure::set)
            }
            fixture.trackWorker(caller)
            caller.start()
            val parent = fixture.recordPid("parent.pid")
            val child = fixture.recordPid("child.pid")
            try {
                waitFor { Files.exists(fixture.home.resolve("child-ready")) }
                waitFor { observedPids.contains(child.pid()) }
                Files.writeString(parentRelease, "release\n")
                waitFor { !parent.isAlive }
                parent.isAlive.shouldBeFalse()
                child.isAlive.shouldBeTrue()
                caller.isAlive.shouldBeTrue()

                Files.writeString(fork, "fork\n")
                val grandchild = fixture.recordPid("grandchild.pid")
                waitFor { observedPids.contains(grandchild.pid()) }
                grandchild.isAlive.shouldBeTrue()
                Files.writeString(childRelease, "release\n")
                waitFor { !child.isAlive }
                child.isAlive.shouldBeFalse()
                grandchild.isAlive.shouldBeTrue()
                caller.isAlive.shouldBeTrue()
                result.get() shouldBe null

                Files.writeString(grandchildRelease, "release\n")
                awaitWithin(caller, "late fork caller")
                failure.get() shouldBe null
                requireNotNull(result.get()).exitCode shouldBe 0
                parent.isAlive.shouldBeFalse()
                child.isAlive.shouldBeFalse()
                grandchild.isAlive.shouldBeFalse()
                completedHelpers.toSet().size shouldBe 2
                completedHelpers.toSet().all { !it.isAlive }.shouldBeTrue()
                fixture.assertRecordedProcessesStopped()
            } finally {
                runCatching { Files.writeString(parentRelease, "release\n") }
                runCatching { Files.writeString(fork, "fork\n") }
                runCatching { Files.writeString(childRelease, "release\n") }
                runCatching { Files.writeString(grandchildRelease, "release\n") }
                if (caller.isAlive) {
                    caller.interrupt()
                    runCatching { awaitWithin(caller, "late fork caller cleanup") }
                }
            }
        }
    }

    test("a child surviving the original deadline produces sticky timeout after confirmation") {
        withOwnedFakeGit(
            "#!/bin/sh\nsleep 60 & echo ${'$'}! > \"${'$'}HOME/child.pid\"\nwait\n",
        ) { fixture ->
            val (result, worker) = runAndRecordChild(fixture, timeoutSeconds = 1)
            result.exitCode shouldBe -1
            result.stderr shouldContain "timed out"
            worker.isAlive.shouldBeFalse()
            fixture.assertRecordedProcessesStopped()
        }
    }

    test("repeated interruption keeps one sticky cause while confirmation continues") {
        val heldPid = AtomicLong(0L)
        val released = AtomicBoolean(false)
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean {
                if (observation.role == "descendant") heldPid.compareAndSet(0L, observation.handle.pid())
                return if (observation.handle.pid() == heldPid.get() && !released.get()) {
                    false
                } else {
                    !observation.handle.isAlive
                }
            }

            override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
        }
        withOwnedFakeGit("#!/bin/sh\nsleep 60 & echo ${'$'}! > \"${'$'}HOME/child.pid\"\nwait\n") { fixture ->
            fixture.releaseOnCleanup { released.set(true) }
            val result = AtomicReference<GitResult?>()
            val failure = AtomicReference<Throwable?>()
            val interruptRestored = AtomicBoolean(false)
            val caller = thread(start = false, isDaemon = true, name = "repeated-interrupt-caller") {
                runCatching {
                    result.set(
                        GitExecutor(
                            fixture.root,
                            fixture.home,
                            timeoutSeconds = 30,
                            gitBinary = fixture.binary,
                            completionObserver = observer,
                        ).run(listOf("status")),
                    )
                    interruptRestored.set(Thread.currentThread().isInterrupted)
                }.onFailure(failure::set)
            }
            fixture.trackWorker(caller)
            caller.start()
            try {
                val pid = fixture.awaitPid("child.pid")
                fixture.recordHandle(pid)
                waitFor { heldPid.get() != 0L }
                caller.interrupt()
                Thread.sleep(100)
                caller.interrupt()
                caller.isAlive.shouldBeTrue()

                released.set(true)
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                failure.get() shouldBe null
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "interrupted and was force-killed"
                interruptRestored.get().shouldBeTrue()
            } finally {
                released.set(true)
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
                }
            }
        }
    }

    test("partial helper setup failure stays inside the acquired process obligation") {
        val setupFailure = IllegalStateException("synthetic stderr helper setup failure")
        var helperCreations = 0
        val helperFactory: (String, () -> Unit) -> Thread = { name, block ->
            helperCreations += 1
            if (helperCreations == 2) throw setupFailure
            Thread(block, name).apply { isDaemon = true }
        }
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean = !observation.handle.isAlive

            override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
        }
        withOwnedFakeGit("#!/bin/sh\nsleep 60\n") { fixture ->
            val result = AtomicReference<GitResult?>()
            val caller = thread(start = false, isDaemon = true, name = "partial-helper-setup-caller") {
                result.set(
                    GitExecutor(
                        fixture.root,
                        fixture.home,
                        timeoutSeconds = 30,
                        gitBinary = fixture.binary,
                        completionObserver = observer,
                        helperFactory = helperFactory,
                    ).run(listOf("status")),
                )
            }
            fixture.trackWorker(caller)
            caller.start()
            try {
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "helper failed"
            } finally {
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
                }
            }
        }
    }

    test("a helper body failure after normal parent exit is not suppressed") {
        val completedHelpers = ConcurrentLinkedQueue<Thread>()
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean = !observation.handle.isAlive

            override fun helperComplete(helper: Thread): Boolean {
                val complete = !helper.isAlive
                if (complete) completedHelpers += helper
                return complete
            }
        }
        withOwnedFakeGit(
            "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\n" +
                "while [ ! -f \"${'$'}HOME/start\" ]; do sleep 0.01; done\n" +
                "exit 0\n",
        ) { fixture ->
            val result = AtomicReference<GitResult?>()
            val failure = AtomicReference<Throwable?>()
            val caller = thread(start = false, isDaemon = true, name = "helper-body-failure-caller") {
                runCatching {
                    result.set(
                        GitExecutor(
                            fixture.root,
                            fixture.home,
                            timeoutSeconds = 2,
                            gitBinary = fixture.binary,
                            completionObserver = observer,
                        ).run(listOf("status"), stdin = ByteArray(4 * 1024 * 1024) { 'x'.code.toByte() }),
                    )
                }.onFailure(failure::set)
            }
            fixture.trackWorker(caller)
            caller.start()
            val parent = fixture.recordPid("parent.pid")
            try {
                Files.writeString(fixture.home.resolve("start"), "start\n")
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                failure.get() shouldBe null
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "helper failed"
                parent.isAlive.shouldBeFalse()
                completedHelpers.toSet().size shouldBe 3
                completedHelpers.toSet().all { !it.isAlive }.shouldBeTrue()
                fixture.assertRecordedProcessesStopped()
            } finally {
                runCatching { Files.writeString(fixture.home.resolve("start"), "start\n") }
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
                }
            }
        }
    }

    test("a genuine helper observation failure keeps the acquired process obligation") {
        val failure = IllegalStateException("synthetic helper observation failure")
        val failedOnce = AtomicBoolean(false)
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean = !observation.handle.isAlive

            override fun helperComplete(helper: Thread): Boolean {
                if (helper.name == "git-stdout-drain" && failedOnce.compareAndSet(false, true)) throw failure
                return !helper.isAlive
            }
        }
        withOwnedFakeGit("#!/bin/sh\necho data\nexit 0\n") { fixture ->
            val result = AtomicReference<GitResult?>()
            val caller = thread(start = false, isDaemon = true, name = "helper-failure-caller") {
                result.set(
                    GitExecutor(
                        fixture.root,
                        fixture.home,
                        timeoutSeconds = 30,
                        gitBinary = fixture.binary,
                        completionObserver = observer,
                    ).run(listOf("status")),
                )
            }
            fixture.trackWorker(caller)
            caller.start()
            try {
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "helper failed"
            } finally {
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
                }
            }
        }
    }

    test("modeled confirmation keeps the actual caller lock and path until released") {
        val heldPid = AtomicLong(0L)
        val released = AtomicBoolean(false)
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean {
                if (observation.role == "descendant") heldPid.compareAndSet(0L, observation.handle.pid())
                return if (observation.handle.pid() == heldPid.get() && !released.get()) {
                    false
                } else {
                    !observation.handle.isAlive
                }
            }

            override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
        }
        withOwnedFakeGit("#!/bin/sh\nsleep 60 & echo ${'$'}! > \"${'$'}HOME/child.pid\"\nwait\n") { fixture ->
            fixture.releaseOnCleanup { released.set(true) }
            val lock = ReentrantLock()
            val retainedPath = fixture.root.resolve("bundle-under-ship")
            Files.writeString(retainedPath, "pending\n")
            val result = AtomicReference<GitResult?>()
            val caller = thread(start = false, isDaemon = true, name = "modeled-g4-caller") {
                lock.lock()
                try {
                    result.set(
                        GitExecutor(
                            fixture.root,
                            fixture.home,
                            timeoutSeconds = 1,
                            gitBinary = fixture.binary,
                            completionObserver = observer,
                        ).run(listOf("bundle", "create")),
                    )
                } finally {
                    Files.deleteIfExists(retainedPath)
                    lock.unlock()
                }
            }
            fixture.trackWorker(caller)
            caller.start()
            try {
                val pid = fixture.awaitPid("child.pid")
                fixture.recordHandle(pid)
                waitFor { heldPid.get() != 0L }
                Thread.sleep(1_500)

                caller.isAlive.shouldBeTrue()
                lock.tryLock().shouldBeFalse()
                Files.exists(retainedPath).shouldBeTrue()

                released.set(true)
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                requireNotNull(result.get()).exitCode shouldBe -1
                Files.exists(retainedPath).shouldBeFalse()
                fixture.assertRecordedProcessesStopped()
            } finally {
                released.set(true)
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
                }
            }
        }
    }

    test("modeled helper-only pending work retains the caller after the parent is gone") {
        val released = AtomicBoolean(false)
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean = !observation.handle.isAlive

            override fun helperComplete(helper: Thread): Boolean =
                if (helper.name == "git-stdout-drain" && !released.get()) false else !helper.isAlive
        }
        withOwnedFakeGit("#!/bin/sh\nexit 0\n") { fixture ->
            fixture.releaseOnCleanup { released.set(true) }
            val lock = ReentrantLock()
            val retainedPath = fixture.root.resolve("helper-only-bundle")
            Files.writeString(retainedPath, "pending\n")
            val result = AtomicReference<GitResult?>()
            val interrupted = AtomicBoolean(false)
            val caller = thread(start = false, isDaemon = true, name = "modeled-helper-only-caller") {
                lock.lock()
                try {
                    result.set(
                        GitExecutor(
                            fixture.root,
                            fixture.home,
                            timeoutSeconds = 1,
                            gitBinary = fixture.binary,
                            completionObserver = observer,
                        ).run(listOf("status")),
                    )
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted)
                    Files.deleteIfExists(retainedPath)
                    lock.unlock()
                }
            }
            fixture.trackWorker(caller)
            caller.start()
            try {
                Thread.sleep(1_500)
                caller.isAlive.shouldBeTrue()
                lock.tryLock().shouldBeFalse()
                Files.exists(retainedPath).shouldBeTrue()
                caller.interrupt()
                Thread.sleep(100)
                caller.isAlive.shouldBeTrue()

                released.set(true)
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                requireNotNull(result.get()).exitCode shouldBe -1
                interrupted.get().shouldBeTrue()
                Files.exists(retainedPath).shouldBeFalse()
            } finally {
                released.set(true)
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
                }
            }
        }
    }

    test("a final helper completion after the original deadline remains a timeout") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = AtomicBoolean(true)
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean = !observation.handle.isAlive

            override fun helperComplete(helper: Thread): Boolean {
                if (helper.name == "git-stdout-drain" && !helper.isAlive && held.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "late helper release timed out" }
                }
                return !helper.isAlive
            }
        }
        withOwnedFakeGit("#!/bin/sh\nexit 0\n") { fixture ->
            fixture.releaseOnCleanup { release.countDown() }
            val result = AtomicReference<GitResult?>()
            val caller = thread(start = false, isDaemon = true, name = "late-final-caller") {
                result.set(
                    GitExecutor(
                        fixture.root,
                        fixture.home,
                        timeoutSeconds = 1,
                        gitBinary = fixture.binary,
                        completionObserver = observer,
                    ).run(listOf("status")),
                )
            }
            fixture.trackWorker(caller)
            caller.start()
            try {
                entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                Thread.sleep(1_200)
                release.countDown()
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "timed out"
            } finally {
                release.countDown()
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
                }
            }
        }
    }

    test("a pending caller interruption at final completion becomes the invocation cause") {
        val interruptedAtCompletion = AtomicBoolean(false)
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean = !observation.handle.isAlive

            override fun helperComplete(helper: Thread): Boolean {
                if (helper.name == "git-stdout-drain" && !helper.isAlive &&
                    interruptedAtCompletion.compareAndSet(false, true)
                ) {
                    Thread.currentThread().interrupt()
                }
                return !helper.isAlive
            }
        }
        withOwnedFakeGit("#!/bin/sh\nexit 0\n") { fixture ->
            val result = AtomicReference<GitResult?>()
            val interruptRestored = AtomicBoolean(false)
            val caller = thread(start = false, isDaemon = true, name = "late-interrupt-caller") {
                result.set(
                    GitExecutor(
                        fixture.root,
                        fixture.home,
                        timeoutSeconds = 2,
                        gitBinary = fixture.binary,
                        completionObserver = observer,
                    ).run(listOf("status")),
                )
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
            fixture.trackWorker(caller)
            caller.start()
            caller.join(10_000)
            caller.isAlive.shouldBeFalse()
            requireNotNull(result.get()).exitCode shouldBe -1
            requireNotNull(result.get()).stderr shouldContain "interrupted and was force-killed"
            interruptRestored.get().shouldBeTrue()
        }
    }

    test("an overflow helper signals while the invocation caller owns process observation") {
        val callbackThreads = ConcurrentLinkedQueue<Thread>()
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean {
                callbackThreads += Thread.currentThread()
                return !observation.handle.isAlive
            }

            override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
        }
        withOwnedFakeGit(
            "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\n" +
                "while [ ! -f \"${'$'}HOME/start\" ]; do sleep 0.01; done\n" +
                "yes overflow\n",
        ) { fixture ->
            val result = AtomicReference<GitResult?>()
            val failure = AtomicReference<Throwable?>()
            val caller = thread(start = false, isDaemon = true, name = "overflow-caller") {
                runCatching {
                    result.set(
                        GitExecutor(
                            fixture.root,
                            fixture.home,
                            timeoutSeconds = 2,
                            gitBinary = fixture.binary,
                            maxStdoutBytes = 64L * 1024,
                            completionObserver = observer,
                        ).run(listOf("log")),
                    )
                }.onFailure(failure::set)
            }
            fixture.trackWorker(caller)
            caller.start()
            fixture.recordPid("parent.pid")
            Files.writeString(fixture.home.resolve("start"), "start\n")
            try {
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                failure.get() shouldBe null
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "exceeded the in-memory read cap"
                callbackThreads.toList().isNotEmpty().shouldBeTrue()
                callbackThreads.toList().all { it === caller }.shouldBeTrue()
            } finally {
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
                }
            }
        }
    }
})

private enum class OutputStreamKind(val helperName: String) {
    STDOUT("git-stdout-drain"),
    STDERR("git-stderr-drain"),
}

private fun runInterruptionBeforeHelperSetupFailureCase() {
    val setupFailure = IllegalStateException("ordered stderr helper setup failure")
    val stderrFactoryReached = CountDownLatch(1)
    val helperCreations = AtomicInteger()
    withOwnedFakeGit(
        "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\nsleep 60\n",
    ) { fixture ->
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean {
                fixture.recordProcess(observation.handle)
                return !observation.handle.isAlive
            }

            override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
        }
        val helperFactory: (String, () -> Unit) -> Thread = { name, block ->
            helperCreations.incrementAndGet()
            if (name == "git-stderr-drain") {
                stderrFactoryReached.countDown()
                throw setupFailure
            }
            Thread(block, name).apply {
                isDaemon = true
                fixture.trackWorker(this)
            }
        }
        val result = AtomicReference<GitResult?>()
        val failure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean(false)
        val caller = thread(start = false, isDaemon = true, name = "ordered-interruption-setup-caller") {
            Thread.currentThread().interrupt()
            runCatching {
                result.set(
                    GitExecutor(
                        fixture.root,
                        fixture.home,
                        timeoutSeconds = 30,
                        gitBinary = fixture.binary,
                        completionObserver = observer,
                        helperFactory = helperFactory,
                    ).run(listOf("status")),
                )
            }.onFailure(failure::set)
            interruptRestored.set(Thread.currentThread().isInterrupted)
        }
        fixture.trackWorker(caller)
        caller.start()
        try {
            stderrFactoryReached.await(10, TimeUnit.SECONDS).shouldBeTrue()
            awaitWithin(caller, "ordered interruption/setup caller")
            helperCreations.get() shouldBe 2
            failure.get() shouldBe null
            requireNotNull(result.get()).exitCode shouldBe -1
            requireNotNull(result.get()).stderr shouldContain "interrupted and was force-killed"
            interruptRestored.get().shouldBeTrue()
            fixture.assertRecordedProcessesStopped()
        } finally {
            if (caller.isAlive) {
                caller.interrupt()
                runCatching { awaitWithin(caller, "ordered interruption/setup caller cleanup") }
            }
        }
    }
}

private fun runOverflowBeforeInterruptionCase(kind: OutputStreamKind) {
    val selectedBlockReturned = CountDownLatch(1)
    val selectedHelperRelease = CountDownLatch(1)
    val selectedHelper = AtomicReference<Thread?>()
    val overflowReturnedAtNanos = AtomicLong(0L)
    val interruptSentAtNanos = AtomicLong(0L)
    val parentReleaseName = "release-parent"
    val overflowCommand = when (kind) {
        OutputStreamKind.STDOUT -> "head -c ${OUTPUT_CAP_BYTES + 1} /dev/zero"
        OutputStreamKind.STDERR -> "head -c ${OUTPUT_CAP_BYTES + 1} /dev/zero >&2"
    }
    withOwnedFakeGit(
        "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\n" +
            "while [ ! -f \"${'$'}HOME/start\" ]; do sleep 0.01; done\n" +
            "$overflowCommand\n" +
            "while [ ! -f \"${'$'}HOME/$parentReleaseName\" ]; do sleep 0.01; done\n" +
            "exit 0\n",
    ) { fixture ->
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean {
                fixture.recordProcess(observation.handle)
                return !observation.handle.isAlive
            }

            override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
        }
        val helperFactory: (String, () -> Unit) -> Thread = { name, block ->
            val helper = Thread(
                {
                    try {
                        block()
                    } finally {
                        if (name == kind.helperName) {
                            overflowReturnedAtNanos.compareAndSet(0L, System.nanoTime())
                            selectedBlockReturned.countDown()
                            selectedHelperRelease.await(10, TimeUnit.SECONDS)
                        }
                    }
                },
                name,
            ).apply { isDaemon = true }
            fixture.trackWorker(helper)
            if (name == kind.helperName) selectedHelper.set(helper)
            helper
        }
        fixture.releaseOnCleanup { Files.writeString(fixture.home.resolve("start"), "start\n") }
        fixture.releaseOnCleanup { Files.writeString(fixture.home.resolve(parentReleaseName), "release\n") }
        fixture.releaseOnCleanup { selectedHelperRelease.countDown() }
        val result = AtomicReference<GitResult?>()
        val failure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean(false)
        val caller = thread(start = false, isDaemon = true, name = "ordered-${kind.name.lowercase()}-overflow-caller") {
            runCatching {
                result.set(
                    GitExecutor(
                        fixture.root,
                        fixture.home,
                        timeoutSeconds = 30,
                        gitBinary = fixture.binary,
                        maxStdoutBytes = OUTPUT_CAP_BYTES,
                        maxStderrBytes = OUTPUT_CAP_BYTES,
                        completionObserver = observer,
                        helperFactory = helperFactory,
                    ).run(listOf("log")),
                )
            }.onFailure(failure::set)
            interruptRestored.set(Thread.currentThread().isInterrupted)
        }
        fixture.trackWorker(caller)
        caller.start()
        fixture.recordPid("parent.pid")
        try {
            Files.writeString(fixture.home.resolve("start"), "start\n")
            selectedBlockReturned.await(10, TimeUnit.SECONDS).shouldBeTrue()
            selectedHelper.get().shouldNotBe(null)
            caller.isAlive.shouldBeTrue()
            result.get() shouldBe null
            interruptSentAtNanos.set(System.nanoTime())
            (overflowReturnedAtNanos.get() < interruptSentAtNanos.get()).shouldBeTrue()
            caller.interrupt()
            selectedHelperRelease.countDown()
            awaitWithin(caller, "ordered overflow caller ${kind.name.lowercase()}")
            failure.get() shouldBe null
            requireNotNull(result.get()).exitCode shouldBe -1
            requireNotNull(result.get()).stderr shouldContain "exceeded the in-memory read cap"
            interruptRestored.get().shouldBeTrue()
            requireNotNull(selectedHelper.get()).isAlive.shouldBeFalse()
            fixture.assertRecordedProcessesStopped()
        } finally {
            selectedHelperRelease.countDown()
            runCatching { Files.writeString(fixture.home.resolve("start"), "start\n") }
            runCatching { Files.writeString(fixture.home.resolve(parentReleaseName), "release\n") }
            if (caller.isAlive) {
                caller.interrupt()
                runCatching { awaitWithin(caller, "ordered overflow caller cleanup") }
            }
        }
    }
}

private fun runHelperSetupFailureBeforeInterruptionCase() {
    val setupFailure = IllegalStateException("ordered stderr setup failure")
    val stderrFactoryReached = CountDownLatch(1)
    val stdinFactoryReached = CountDownLatch(1)
    val stdinGateEntered = CountDownLatch(1)
    val stdinRelease = CountDownLatch(1)
    val helperCreations = AtomicInteger()
    withOwnedFakeGit(
        "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\n" +
            "while [ ! -f \"${'$'}HOME/release-parent\" ]; do sleep 0.01; done\n" +
            "exit 0\n",
    ) { fixture ->
        val observer = object : GitInvocationCompletionObserver {
            override fun processComplete(observation: GitProcessObservation): Boolean {
                fixture.recordProcess(observation.handle)
                return !observation.handle.isAlive
            }

            override fun helperComplete(helper: Thread): Boolean = !helper.isAlive
        }
        val helperFactory: (String, () -> Unit) -> Thread = { name, block ->
            helperCreations.incrementAndGet()
            when (name) {
                "git-stderr-drain" -> {
                    stderrFactoryReached.countDown()
                    throw setupFailure
                }

                "git-stdin-writer" -> {
                    stdinFactoryReached.countDown()
                    Thread(
                        {
                            try {
                                block()
                            } finally {
                                stdinGateEntered.countDown()
                                stdinRelease.await(10, TimeUnit.SECONDS)
                            }
                        },
                        name,
                    ).apply { isDaemon = true }.also(fixture::trackWorker)
                }

                else -> Thread(block, name).apply { isDaemon = true }.also(fixture::trackWorker)
            }
        }
        fixture.releaseOnCleanup { Files.writeString(fixture.home.resolve("release-parent"), "release\n") }
        fixture.releaseOnCleanup { stdinRelease.countDown() }
        val result = AtomicReference<GitResult?>()
        val failure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean(false)
        val caller = thread(start = false, isDaemon = true, name = "ordered-setup-interruption-caller") {
            runCatching {
                result.set(
                    GitExecutor(
                        fixture.root,
                        fixture.home,
                        timeoutSeconds = 30,
                        gitBinary = fixture.binary,
                        completionObserver = observer,
                        helperFactory = helperFactory,
                    ).run(listOf("status"), stdin = ByteArray(4 * 1024 * 1024) { 'x'.code.toByte() }),
                )
            }.onFailure(failure::set)
            interruptRestored.set(Thread.currentThread().isInterrupted)
        }
        fixture.trackWorker(caller)
        caller.start()
        try {
            stderrFactoryReached.await(10, TimeUnit.SECONDS).shouldBeTrue()
            stdinFactoryReached.await(10, TimeUnit.SECONDS).shouldBeTrue()
            stdinGateEntered.await(10, TimeUnit.SECONDS).shouldBeTrue()
            caller.isAlive.shouldBeTrue()
            result.get() shouldBe null
            caller.interrupt()
            stdinRelease.countDown()
            awaitWithin(caller, "ordered helper setup caller")
            helperCreations.get() shouldBe 3
            failure.get() shouldBe null
            requireNotNull(result.get()).exitCode shouldBe -1
            requireNotNull(result.get()).stderr shouldContain "helper failed"
            interruptRestored.get().shouldBeTrue()
            fixture.assertRecordedProcessesStopped()
        } finally {
            stdinRelease.countDown()
            runCatching { Files.writeString(fixture.home.resolve("release-parent"), "release\n") }
            if (caller.isAlive) {
                caller.interrupt()
                runCatching { awaitWithin(caller, "ordered helper setup caller cleanup") }
            }
        }
    }
}

private fun runTimeoutBeforeInterruptionCase() {
    val observedPids = ConcurrentLinkedQueue<Long>()
    val completedHelpers = ConcurrentLinkedQueue<Thread>()
    val helperHoldEntered = CountDownLatch(1)
    val helperRelease = CountDownLatch(1)
    val pendingWarningAtNanos = AtomicLong(0L)
    val observer = object : GitInvocationCompletionObserver {
        override fun processComplete(observation: GitProcessObservation): Boolean {
            if (observation.role == "descendant") observedPids += observation.handle.pid()
            return !observation.handle.isAlive
        }

        override fun helperComplete(helper: Thread): Boolean {
            val complete = !helper.isAlive
            if (complete) {
                completedHelpers += helper
                if (pendingWarningAtNanos.get() != 0L &&
                    helper.name == "git-stdout-drain" &&
                    helperHoldEntered.count == 1L
                ) {
                    helperHoldEntered.countDown()
                    return false
                }
                if (helper.name == "git-stdout-drain" && helperHoldEntered.count == 0L && helperRelease.count != 0L) {
                    return false
                }
            }
            return complete
        }
    }
    val callerName = "timeout-before-interruption-caller"
    val warningAppender = GitPendingWarningAppender(callerName) {
        pendingWarningAtNanos.compareAndSet(0L, System.nanoTime())
    }.apply { start() }
    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    rootLogger.addAppender(warningAppender)
    try {
        withOwnedFakeGit(
            "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\n" +
                "(while [ ! -f \"${'$'}HOME/release-child\" ]; do sleep 0.01; done) &\n" +
                "echo ${'$'}! > \"${'$'}HOME/child.pid\"\n" +
                "echo ready > \"${'$'}HOME/ancestry-ready\"\n" +
                "while [ ! -f \"${'$'}HOME/release-parent\" ]; do sleep 0.01; done\n" +
                "exit 0\n",
        ) { fixture ->
            fixture.releaseOnCleanup { Files.writeString(fixture.home.resolve("release-parent"), "release\n") }
            fixture.releaseOnCleanup { Files.writeString(fixture.home.resolve("release-child"), "release\n") }
            fixture.releaseOnCleanup { helperRelease.countDown() }
            val result = AtomicReference<GitResult?>()
            val failure = AtomicReference<Throwable?>()
            val interruptRestored = AtomicBoolean(false)
            val startedAtNanos = AtomicLong(0L)
            val readyAtNanos = AtomicLong(0L)
            val parentExitedAtNanos = AtomicLong(0L)
            val caller = thread(start = false, isDaemon = true, name = callerName) {
                startedAtNanos.set(System.nanoTime())
                runCatching {
                    result.set(
                        GitExecutor(
                            fixture.root,
                            fixture.home,
                            timeoutSeconds = 1,
                            gitBinary = fixture.binary,
                            completionObserver = observer,
                        ).run(listOf("status")),
                    )
                }.onFailure(failure::set)
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
            fixture.trackWorker(caller)
            caller.start()
            val parent = fixture.recordPid("parent.pid")
            val child = fixture.recordPid("child.pid")
            try {
                awaitTimeoutDistinguishingWindow(fixture, observedPids, caller, parent, child, startedAtNanos, readyAtNanos)
                val timeoutObservedBeforeRelease =
                    pendingWarningAtNanos.get() != 0L && !child.isAlive && helperHoldEntered.count == 0L
                Files.writeString(fixture.home.resolve("release-child"), "release\n")
                if (timeoutObservedBeforeRelease) {
                    waitFor { helperHoldEntered.count == 0L }
                }
                timeoutObservedBeforeRelease.shouldBeTrue()
                caller.isAlive.shouldBeTrue()
                result.get() shouldBe null
                caller.interrupt()
                helperRelease.countDown()
                awaitWithin(caller, "timeout-before-interruption caller")
                failure.get() shouldBe null
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "timed out"
                interruptRestored.get().shouldBeTrue()
                pendingWarningAtNanos.get() shouldNotBe 0L
                parent.isAlive.shouldBeFalse()
                child.isAlive.shouldBeFalse()
                completedHelpers.toSet().size shouldBe 2
                completedHelpers.toSet().all { !it.isAlive }.shouldBeTrue()
                fixture.assertRecordedProcessesStopped()
            } finally {
                helperRelease.countDown()
                runCatching { Files.writeString(fixture.home.resolve("release-parent"), "release\n") }
                runCatching { Files.writeString(fixture.home.resolve("release-child"), "release\n") }
                if (caller.isAlive) {
                    caller.interrupt()
                    runCatching { awaitWithin(caller, "timeout-before-interruption caller cleanup") }
                }
            }
        }
    } finally {
        rootLogger.detachAppender(warningAppender)
        warningAppender.stop()
    }
}

private fun awaitTimeoutDistinguishingWindow(
    fixture: OwnedFakeGitFixture,
    observedPids: ConcurrentLinkedQueue<Long>,
    caller: Thread,
    parent: ProcessHandle,
    child: ProcessHandle,
    startedAtNanos: AtomicLong,
    readyAtNanos: AtomicLong,
) {
    waitFor { Files.exists(fixture.home.resolve("ancestry-ready")) }
    waitFor { observedPids.contains(child.pid()) }
    readyAtNanos.set(System.nanoTime())
    if (System.nanoTime() >= startedAtNanos.get() + TimeUnit.MILLISECONDS.toNanos(1_000L - DISTINGUISHING_MARGIN_MILLIS)) {
        throw TestAbortedException("timeout control missed the before-parent-exit original-budget window")
    }
    Files.writeString(fixture.home.resolve("release-parent"), "release\n")
    waitFor { !parent.isAlive }
    val parentExitedAtNanos = System.nanoTime()
    parent.isAlive.shouldBeFalse()
    child.isAlive.shouldBeTrue()
    caller.isAlive.shouldBeTrue()

    val originalDeadlineNanos = startedAtNanos.get() + TimeUnit.SECONDS.toNanos(1)
    val resetDeadlineNanos = parentExitedAtNanos + TimeUnit.SECONDS.toNanos(4)
    val releaseTargetNanos = maxOf(
        originalDeadlineNanos + TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS),
        readyAtNanos.get() + TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS),
    )
    if (releaseTargetNanos >= resetDeadlineNanos - TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS)) {
        throw TestAbortedException(
            "timeout/reset window is not separated: start=${startedAtNanos.get()} ready=${readyAtNanos.get()} " +
                "parentExit=$parentExitedAtNanos original=$originalDeadlineNanos reset=$resetDeadlineNanos " +
                "target=$releaseTargetNanos",
        )
    }
    awaitAt(releaseTargetNanos)
    val releaseAtNanos = System.nanoTime()
    if (releaseAtNanos <= originalDeadlineNanos + TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS) ||
        releaseAtNanos >= resetDeadlineNanos - TimeUnit.MILLISECONDS.toNanos(DISTINGUISHING_MARGIN_MILLIS)
    ) {
        throw TestAbortedException(
            "timeout/reset window missed: start=${startedAtNanos.get()} ready=${readyAtNanos.get()} " +
                "parentExit=$parentExitedAtNanos release=$releaseAtNanos original=$originalDeadlineNanos " +
                "reset=$resetDeadlineNanos",
        )
    }
}

private fun procStat(pid: Long, command: String, state: Char, threads: Long, ticks: Long): String {
    val fields = buildList {
        repeat(20) { index ->
            add(
                when (index) {
                    0 -> state.toString()
                    17 -> threads.toString()
                    19 -> ticks.toString()
                    else -> "0"
                },
            )
        }
    }
    return "$pid ($command) ${fields.joinToString(" ")}"
}

private class OwnedFakeGitFixture(
    val root: Path,
    val home: Path,
    val binary: String,
) {
    private val handles = mutableListOf<ProcessHandle>()
    private val workers = mutableListOf<Thread>()
    private val barrierReleases = mutableListOf<() -> Unit>()
    private val workerFailures = mutableListOf<Throwable>()

    fun trackWorker(worker: Thread) {
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
            synchronized(workerFailures) { workerFailures += failure }
        }
        synchronized(workers) { workers += worker }
    }

    fun releaseOnCleanup(release: () -> Unit) {
        synchronized(barrierReleases) { barrierReleases += release }
    }

    fun recordPid(fileName: String): ProcessHandle = recordHandle(awaitPid(fileName))

    fun recordHandle(pid: Long): ProcessHandle {
        val handle = ProcessHandle.of(pid).orElseThrow { IllegalStateException("recorded PID $pid was unavailable") }
        recordProcess(handle)
        return handle
    }

    fun recordProcess(handle: ProcessHandle) {
        synchronized(handles) {
            if (handles.none { it.pid() == handle.pid() }) handles += handle
        }
    }

    fun awaitPid(fileName: String): Long {
        val path = home.resolve(fileName)
        waitFor { Files.exists(path) }
        return Files.readString(path).trim().toLong()
    }

    fun assertRecordedProcessesStopped() {
        val snapshot = synchronized(handles) { handles.toList() }
        val survivors = snapshot.filter { it.isAlive }.joinToString(",") { handle -> handle.pid().toString() }
        survivors shouldBe ""
    }

    fun cleanup(): FixtureCleanup {
        var interrupted = Thread.interrupted()
        var cleanupFailure: Throwable? = null
        fun record(failure: Throwable) {
            if (failure is InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
            cleanupFailure = mergeFailure(cleanupFailure, failure)
        }
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                record(failure)
            }
        }

        synchronized(barrierReleases) { barrierReleases.toList() }.forEach { release -> attempt(release) }
        val processSnapshot = synchronized(handles) { handles.toList() }
        val workerSnapshot = synchronized(workers) { workers.toList() }
        processSnapshot.forEach { handle ->
            attempt {
                if (handle.isAlive && !handle.destroyForcibly() && handle.isAlive) {
                    error("recorded fake-git PID ${handle.pid()} rejected termination")
                }
            }
        }
        workerSnapshot.forEach { worker -> attempt { if (worker.isAlive) worker.interrupt() } }

        val processDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FIXTURE_CLEANUP_TIMEOUT_MILLIS)
        processSnapshot.forEach { handle ->
            attempt {
                while (handle.isAlive && System.nanoTime() < processDeadline) {
                    try {
                        Thread.sleep(FIXTURE_POLL_MILLIS)
                    } catch (failure: InterruptedException) {
                        record(failure)
                    }
                }
            }
        }
        val workerDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FIXTURE_CLEANUP_TIMEOUT_MILLIS)
        workerSnapshot.forEach { worker ->
            attempt {
                while (worker.isAlive && System.nanoTime() < workerDeadline) {
                    try {
                        val remaining = workerDeadline - System.nanoTime()
                        worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
                    } catch (failure: InterruptedException) {
                        record(failure)
                    }
                }
            }
        }
        synchronized(workerFailures) { workerFailures.toList() }.forEach { failure -> record(failure) }

        val liveProcesses = processSnapshot.filter { it.isAlive }
        val liveWorkers = workerSnapshot.filter { it.isAlive }
        if (liveProcesses.isNotEmpty() || liveWorkers.isNotEmpty()) {
            record(
                IllegalStateException(
                    "retaining fake-git fixture root=$root home=$home script=$binary; " +
                        "surviving PIDs=${liveProcesses.joinToString(",") { it.pid().toString() }} " +
                        "workers=${liveWorkers.joinToString(",") { it.name }}",
                ),
            )
        } else {
            attempt { Files.deleteIfExists(Path.of(binary)) }
            attempt {
                check(root.toFile().deleteRecursively() || Files.notExists(root)) {
                    "could not delete fake-git root $root"
                }
            }
            attempt {
                check(home.toFile().deleteRecursively() || Files.notExists(home)) {
                    "could not delete fake-git home $home"
                }
            }
        }
        return FixtureCleanup(cleanupFailure, interrupted)
    }
}

private data class FixtureCleanup(val failure: Throwable?, val interrupted: Boolean)

private const val FIXTURE_CLEANUP_TIMEOUT_MILLIS = 10_000L
private const val FIXTURE_POLL_MILLIS = 10L

private fun runControlledParentExitCase(expectedExitCode: Int) {
    val observedPids = ConcurrentLinkedQueue<Long>()
    val completedHelpers = ConcurrentLinkedQueue<Thread>()
    val observer = object : GitInvocationCompletionObserver {
        override fun processComplete(observation: GitProcessObservation): Boolean {
            if (observation.role == "descendant") observedPids += observation.handle.pid()
            return !observation.handle.isAlive
        }

        override fun helperComplete(helper: Thread): Boolean {
            val complete = !helper.isAlive
            if (complete) completedHelpers += helper
            return complete
        }
    }
    withOwnedFakeGit(
        "#!/bin/sh\necho ${'$'}${'$'} > \"${'$'}HOME/parent.pid\"\n" +
            "(while [ ! -f \"${'$'}HOME/release-child\" ]; do sleep 0.01; done) &\n" +
            "echo ${'$'}! > \"${'$'}HOME/child.pid\"\n" +
            "echo ready > \"${'$'}HOME/ancestry-ready\"\n" +
            "while [ ! -f \"${'$'}HOME/release-parent\" ]; do sleep 0.01; done\n" +
            "exit $expectedExitCode\n",
    ) { fixture ->
        val parentRelease = fixture.home.resolve("release-parent")
        val childRelease = fixture.home.resolve("release-child")
        fixture.releaseOnCleanup { Files.writeString(parentRelease, "release\n") }
        fixture.releaseOnCleanup { Files.writeString(childRelease, "release\n") }
        val result = AtomicReference<GitResult?>()
        val failure = AtomicReference<Throwable?>()
        val caller = thread(start = false, isDaemon = true, name = "parent-exit-$expectedExitCode-caller") {
            runCatching {
                result.set(
                    GitExecutor(
                        fixture.root,
                        fixture.home,
                        timeoutSeconds = 2,
                        gitBinary = fixture.binary,
                        completionObserver = observer,
                    ).run(listOf("status")),
                )
            }.onFailure(failure::set)
        }
        fixture.trackWorker(caller)
        caller.start()
        val parent = fixture.recordPid("parent.pid")
        val child = fixture.recordPid("child.pid")
        try {
            waitFor { Files.exists(fixture.home.resolve("ancestry-ready")) }
            waitFor { observedPids.contains(child.pid()) }
            Files.writeString(parentRelease, "release\n")
            waitFor { !parent.isAlive }
            parent.isAlive.shouldBeFalse()
            child.isAlive.shouldBeTrue()
            caller.isAlive.shouldBeTrue()

            Files.writeString(childRelease, "release\n")
            caller.join(10_000)
            caller.isAlive.shouldBeFalse()
            failure.get() shouldBe null
            requireNotNull(result.get()).exitCode shouldBe expectedExitCode
            parent.isAlive.shouldBeFalse()
            child.isAlive.shouldBeFalse()
            completedHelpers.toSet().size shouldBe 2
            completedHelpers.toSet().all { !it.isAlive }.shouldBeTrue()
            fixture.assertRecordedProcessesStopped()
        } finally {
            runCatching { Files.writeString(parentRelease, "release\n") }
            runCatching { Files.writeString(childRelease, "release\n") }
            if (caller.isAlive) {
                caller.interrupt()
                caller.join(10_000)
            }
        }
    }
}

private fun runAndRecordChild(fixture: OwnedFakeGitFixture, timeoutSeconds: Long): Pair<GitResult, Thread> {
    val result = AtomicReference<GitResult?>()
    val failure = AtomicReference<Throwable?>()
    val worker = thread(start = false, isDaemon = true, name = "recorded-child-caller") {
        runCatching {
            GitExecutor(fixture.root, fixture.home, timeoutSeconds = timeoutSeconds, gitBinary = fixture.binary)
                .run(listOf("status"))
        }.onSuccess(result::set).onFailure(failure::set)
    }
    fixture.trackWorker(worker)
    worker.start()
    fixture.recordPid("child.pid")
    worker.join(10_000)
    failure.get() shouldBe null
    return requireNotNull(result.get()) to worker
}

private fun <T> withOwnedFakeGit(script: String, block: (OwnedFakeGitFixture) -> T): T {
    val root = Files.createTempDirectory("plainbase-git-completion")
    val home = Files.createTempDirectory("plainbase-git-completion-home")
    val bin = Files.createTempFile("plainbase-fake-git", ".sh")
    Files.writeString(bin, script)
    Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"))
    val fixture = OwnedFakeGitFixture(root, home, bin.toString())
    var outcome: Result<T>? = null
    var primaryFailure: Throwable? = null
    var cleanupFailure: Throwable? = null
    var interrupted = Thread.interrupted()
    try {
        val completed = runCatching { block(fixture) }
        outcome = completed
        primaryFailure = completed.exceptionOrNull()
        if (primaryFailure is InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
    } finally {
        try {
            val cleanup = fixture.cleanup()
            interrupted = interrupted || cleanup.interrupted
            cleanup.failure?.let { cleanupFailure = mergeFailure(cleanupFailure, it) }
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
            cleanupFailure = mergeFailure(cleanupFailure, failure)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    if (primaryFailure != null) {
        cleanupFailure?.let { mergeFailure(primaryFailure, it) }
        throw primaryFailure
    }
    cleanupFailure?.let { throw it }
    return requireNotNull(outcome) { "owned fake-git fixture completed without a result" }.getOrThrow()
}

private fun mergeFailure(existing: Throwable?, candidate: Throwable): Throwable {
    if (existing == null) return candidate
    if (existing !== candidate && existing.suppressed.none { it === candidate }) existing.addSuppressed(candidate)
    return existing
}

private fun waitFor(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    condition().shouldBeTrue()
}

private fun awaitWithin(worker: Thread, description: String, timeoutMillis: Long = 10_000L) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (worker.isAlive) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0L) error("timed out waiting for $description")
        worker.join(minOf(25L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))
    }
    worker.isAlive.shouldBeFalse()
}

private fun awaitAt(targetNanos: Long) {
    val outerDeadline = targetNanos + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < targetNanos) {
        if (System.nanoTime() >= outerDeadline) error("timed out waiting for monotonic target $targetNanos")
        val remaining = targetNanos - System.nanoTime()
        Thread.sleep(minOf(10L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))
    }
}

private class GitPendingWarningAppender(
    private val expectedThreadName: String,
    private val onPendingWarning: () -> Unit,
) : AppenderBase<ILoggingEvent>() {
    private val events = ConcurrentLinkedQueue<ILoggingEvent>()

    override fun append(event: ILoggingEvent) {
        if (event.level == Level.WARN &&
            event.threadName == expectedThreadName &&
            event.formattedMessage.contains("git run completion pending")
        ) {
            events += event
            onPendingWarning()
        }
    }

    fun pendingWarnings(): List<ILoggingEvent> = events.toList()
}

private const val OUTPUT_CAP_BYTES = 64L * 1024L
private const val PARENT_EXIT_BEFORE_DEADLINE_MILLIS = 100L
private const val DISTINGUISHING_MARGIN_MILLIS = 500L

package com.plainbase.frameworks.git

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/** Checkpoint 03b decision, ownership, and modeled-liveness controls for [GitExecutor]. */
class GitExecutorCompletionTest : FunSpec({

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

    test("controlled fake Git parent exits before its child and returns zero") {
        runControlledParentExitCase(0)
    }

    test("controlled fake Git parent exits before its child and returns nonzero") {
        runControlledParentExitCase(23)
    }

    test("controlled fake Git parent exit keeps the original deadline for a held child") {
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
                "exit 0\n",
        ) { fixture ->
            val parentRelease = fixture.home.resolve("release-parent")
            val childRelease = fixture.home.resolve("release-child")
            fixture.releaseOnCleanup { Files.writeString(parentRelease, "release\n") }
            fixture.releaseOnCleanup { Files.writeString(childRelease, "release\n") }
            val result = AtomicReference<GitResult?>()
            val failure = AtomicReference<Throwable?>()
            val caller = thread(start = false, isDaemon = true, name = "original-deadline-caller") {
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

                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
                failure.get() shouldBe null
                requireNotNull(result.get()).exitCode shouldBe -1
                requireNotNull(result.get()).stderr shouldContain "timed out"
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
                "(while [ ! -f \"${'$'}HOME/release-grandchild\" ]; do sleep 0.01; done) & " +
                "echo ${'$'}! > \"${'$'}HOME/grandchild.pid\"; wait) &\n" +
                "echo ${'$'}! > \"${'$'}HOME/child.pid\"\n" +
                "while [ ! -f \"${'$'}HOME/release-parent\" ]; do sleep 0.01; done\n" +
                "exit 0\n",
        ) { fixture ->
            val parentRelease = fixture.home.resolve("release-parent")
            val fork = fixture.home.resolve("fork")
            val grandchildRelease = fixture.home.resolve("release-grandchild")
            fixture.releaseOnCleanup { Files.writeString(parentRelease, "release\n") }
            fixture.releaseOnCleanup { Files.writeString(fork, "fork\n") }
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
                caller.isAlive.shouldBeTrue()

                Files.writeString(grandchildRelease, "release\n")
                caller.join(10_000)
                caller.isAlive.shouldBeFalse()
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
                runCatching { Files.writeString(grandchildRelease, "release\n") }
                if (caller.isAlive) {
                    caller.interrupt()
                    caller.join(10_000)
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
        synchronized(handles) { handles += handle }
        return handle
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

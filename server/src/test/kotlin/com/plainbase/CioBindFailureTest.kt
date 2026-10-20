package com.plainbase

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.acceptance.SelectedSuite
import com.plainbase.acceptance.shouldHavePassed
import com.plainbase.frameworks.cli.CommandOutputFixture
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.ktor.RestGoldenTest
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue

class CioBindFailureTest : FunSpec({
    test("an occupied bind does not poison the next REST application test") {
        testApplication { startApplication() }
        withOccupiedCioBind { failure ->
            generateSequence(failure) { it.cause }.any { it is BindException } shouldBe true
        }
        val summary = SelectedSuite.run(RestGoldenTest::class)
        summary.failures.forEach { failure ->
            logger.error(failure.exception) {
                "REST application failure: ${failure.testIdentifier.displayName}; suppressed exceptions by cause: " +
                    generateSequence(failure.exception) { it.cause }.map { it.suppressed.toList() }.toList()
            }
        }
        summary.shouldHavePassed("RestGoldenTest after occupied bind", atLeastTests = 8)
    }

    test("an occupied bind is surfaced without global exception delivery") {
        testApplication { startApplication() }
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val engineLogger = LoggerFactory.getLogger(KtorServer::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        engineLogger.addAppender(appender)
        // This handler swap is process-global and relies on specs running sequentially.
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            uncaught.add(failure)
            if (previous != null) {
                previous.uncaughtException(thread, failure)
            } else {
                logger.error(failure) { "Uncaught exception on ${thread.name}" }
            }
        }
        try {
            withOccupiedCioBind { failure ->
                generateSequence(failure) { it.cause }.any { it is BindException } shouldBe true
            }
            val nextApplication = runCatching { testApplication { startApplication() } }
            assertSoftly {
                withClue("global coroutine exception delivery: ${nextApplication.exceptionOrNull()}") {
                    nextApplication.exceptionOrNull() shouldBe null
                }
                withClue("thread-default exceptions: ${uncaught.toList()}") { uncaught.toList() shouldBe emptyList() }
                withClue("engine failure remains visible in the error log") {
                    appender.list.any {
                        it.level == Level.ERROR &&
                            it.formattedMessage == "HTTP engine coroutine failed" &&
                            it.throwableProxy?.className == BindException::class.java.name
                    } shouldBe true
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            engineLogger.detachAppender(appender)
            appender.stop()
        }
    }
})

private fun withOccupiedCioBind(block: (Throwable) -> Unit) {
    ServerSocket().use { occupied ->
        occupied.bind(InetSocketAddress("127.0.0.1", 0))
        withOwnershipFixture { content, data ->
            val output = CommandOutputFixture()
            val failure = shouldThrow<Throwable> {
                boundedOwnershipRun { runServer(localConfigForOwnership(content, data, occupied.localPort), output.output) }
            }
            block(failure)
        }
    }
}

private val logger = KotlinLogging.logger {}

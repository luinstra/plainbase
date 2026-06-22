package com.plainbase.domain.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory

/**
 * A4a secret hygiene (synthesis R2-finding-2 / R1-finding-11): minting a setup token, consuming it, and logging in
 * emit NO log record carrying the setup-token plaintext or the session cookie plaintext. The plaintext lives ONLY
 * in the one-time return values (and the CLI's `println` / the route's cookie) — never a `logger.*` call.
 */
class AuthSecretHygieneTest : FunSpec({

    test("mint + consume + login never log the setup token or the session cookie plaintext") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val (setup, sessions) = setupServiceFixture(db)

            val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            val setupPlaintext: String
            val cookiePlaintext: String
            try {
                val token = setup.mintBootstrapToken()
                setupPlaintext = token.plaintext
                val created = setup.consumeBootstrap(token.plaintext, "alice", "pw".toCharArray()) as BootstrapOutcome.Created
                val session = sessions.create(created.userId)
                cookiePlaintext = session.plaintext
                sessions.authenticate(session.plaintext) // a successful read
            } finally {
                root.detachAppender(appender)
            }

            val messages = appender.list.map { it.formattedMessage }
            messages.filter { it.contains(setupPlaintext) || it.contains(cookiePlaintext) }.shouldBeEmpty()
        }
    }
})

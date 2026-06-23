package com.plainbase.domain.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.repository.AgentMode
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Secret hygiene (A2 WI 9): mint + authenticate (success AND failure) emit NO log record carrying the plaintext
 * token or its raw secret segment. The plaintext lives ONLY in
 * [com.plainbase.domain.principal.MintedToken.plaintext] (the one-time return) and the CLI's `println` — never
 * a `logger.*` call.
 */
class SecretHygieneTest : FunSpec({

    test("mint and authenticate never log the plaintext or the secret segment") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val hasher = TokenHasher()
            val service = ApiTokenService(
                minter = ApiTokenMinter(hasher),
                hasher = hasher,
                tokens = SqlDelightApiTokenRepository(DatabaseFactory.createDatabase(driver)),
                clock = Clock.System,
            )

            val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            val plaintext: String
            try {
                // A valid-format wrong secret / unknown id (16-hex id + 43-char base64url) so both reach the hasher.
                val wrongSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
                val minted = service.mint("ci-bot", AgentMode.READ_ONLY)
                plaintext = minted.plaintext
                service.authenticate(minted.plaintext) // success path
                service.authenticate("pb_${minted.id}_$wrongSecret") // wrong-secret path
                service.authenticate("pb_00112233445566ff_$wrongSecret") // unknown-id path
            } finally {
                root.detachAppender(appender)
            }

            val secretSegment = plaintext.substringAfterLast('_')
            val messages = appender.list.map { it.formattedMessage }
            messages.filter { it.contains(plaintext) || it.contains(secretSegment) }.shouldBeEmpty()
        }
    }
})

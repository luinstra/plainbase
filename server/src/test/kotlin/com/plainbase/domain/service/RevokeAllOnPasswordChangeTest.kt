package com.plainbase.domain.service

import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The debate-REQUIRED test (synthesis §"WIDEN" 1): a password change AND a reset BOTH revoke ALL of the user's
 * sessions (the §7 force-re-login). A wrong current password leaves sessions intact + does not change the hash.
 */
class RevokeAllOnPasswordChangeTest : FunSpec({

    fun <T> withDb(block: (PlainbaseDb) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver -> block(DatabaseFactory.createDatabase(driver)) }

    test("changePassword revokes ALL of the user's sessions; a wrong current password leaves them intact") {
        withDb { db ->
            val (setup, sessions) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            val userId = (setup.consumeBootstrap(token.plaintext, "alice", "old-pw".toCharArray()) as BootstrapOutcome.Created).userId

            val s1 = sessions.create(userId)
            val s2 = sessions.create(userId)
            sessions.authenticate(s1.plaintext).shouldNotBeNull()
            sessions.authenticate(s2.plaintext).shouldNotBeNull()

            // A wrong current password must NOT touch the hash or the sessions.
            setup.changePassword(userId, "wrong".toCharArray(), "new-pw".toCharArray()) shouldBe ChangeOutcome.WrongCurrentPassword
            sessions.authenticate(s1.plaintext).shouldNotBeNull()

            setup.changePassword(userId, "old-pw".toCharArray(), "new-pw".toCharArray()) shouldBe ChangeOutcome.Changed
            sessions.authenticate(s1.plaintext).shouldBeNull()
            sessions.authenticate(s2.plaintext).shouldBeNull()
        }
    }

    test("consumeReset revokes ALL of the user's sessions") {
        withDb { db ->
            val (setup, sessions) = setupServiceFixture(db)
            val token = setup.mintBootstrapToken()
            val userId = (setup.consumeBootstrap(token.plaintext, "alice", "old-pw".toCharArray()) as BootstrapOutcome.Created).userId

            // Admin issues a reset (which already revokes-on-issue), then a NEW session is created and consume revokes it too.
            val resetToken = setup.mintResetToken(userId).shouldNotBeNull()
            val live = sessions.create(userId)
            sessions.authenticate(live.plaintext).shouldNotBeNull()

            setup.consumeReset(resetToken.plaintext, "reset-pw".toCharArray()).shouldBeInstanceOf<ResetOutcome.Reset>()
            sessions.authenticate(live.plaintext).shouldBeNull()
        }
    }
})

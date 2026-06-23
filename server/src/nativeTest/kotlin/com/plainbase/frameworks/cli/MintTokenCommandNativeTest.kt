package com.plainbase.frameworks.cli

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Native-image smoke for `plainbase admin mint-token` (A2 WI 8): minting against a temp `plainbase.db` prints a
 * `pb_…` token; re-opening the same DB authenticates the printed token to [Principal.Agent]; and the printed
 * plaintext is NOT present in the persisted row (only `secret_hash` is). Exercises SecureRandom + SHA-256 + the
 * SQLDelight `api_tokens` create/migrate path under the closed-world image. kotlin.test + @Tag("native") only.
 */
@Tag("native")
class MintTokenCommandNativeTest {

    @Test
    fun `admin mint-token prints a recoverable token whose plaintext is never persisted`() {
        val data = Files.createTempDirectory("pb-native-admin-data")
        try {
            val config = PlainbaseConfig(
                contentDir = data.resolve("content"),
                dataDir = data,
                host = "127.0.0.1",
                port = 0,
            )

            val out = captureStdout { assertEquals(0, AdminCommand.run(listOf("mint-token", "ci-bot"), config)) }
            val token = out.lineSequence().first { it.startsWith("pb_") }
            assertTrue(token.matches(Regex("^pb_[A-Za-z0-9_-]+_[A-Za-z0-9_-]+$")), "unexpected token: $token")

            // Re-open the same on-disk DB and authenticate the printed token.
            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                val hasher = TokenHasher()
                val repo = SqlDelightApiTokenRepository(DatabaseFactory.createDatabase(driver))
                val service = ApiTokenService(
                    minter = ApiTokenMinter(hasher),
                    hasher = hasher,
                    tokens = repo,
                    clock = Clock.System,
                )
                assertTrue(service.authenticate(token) is Principal.Agent, "minted token did not authenticate")

                // The list/CLI surface is metadata only — no secret_hash member to leak.
                val rows = service.list()
                assertEquals(1, rows.size)

                // The by-id lookup carries the hash; it is the 32-byte SHA-256, never the plaintext bytes.
                val stored = repo.findById(rows.single().id)!!
                assertEquals(32, stored.secretHash.size)
                assertFalse(token.toByteArray().contentEquals(stored.secretHash))
            }
        } finally {
            Files.walk(data).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

/** Captures System.out for the duration of [block] — the CLI's stdout is its output contract. */
private fun captureStdout(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val previous = System.out
    System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setOut(previous)
    }
    return buffer.toString(Charsets.UTF_8)
}

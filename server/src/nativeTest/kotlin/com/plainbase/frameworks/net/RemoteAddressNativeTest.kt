package com.plainbase.frameworks.net

import com.plainbase.frameworks.config.PlainbaseConfig
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("native")
class RemoteAddressNativeTest {
    @Test
    fun `literal parsing preserves canonical mapped and zoned addresses`() {
        assertTrue(RemoteAddress.isLoopbackAddress("127.0.0.1"))
        assertTrue(RemoteAddress.isLoopbackAddress("::1%lo0"))
        assertTrue(RemoteAddress.isLoopbackAddress("::ffff:127.0.0.1"))
        assertTrue(RemoteAddress.isLoopbackAddress("[::ffff:127.0.0.1]:8080"))
        assertFalse(RemoteAddress.isLoopbackAddress("2001:db8::1"))
        assertTrue(RemoteAddress.isInAnyCidr("::ffff:127.0.0.1", listOf("127.0.0.0/8")))
        assertTrue(RemoteAddress.isInAnyCidr("[::ffff:127.0.0.1]:8080", listOf("127.0.0.0/8")))
        assertTrue(RemoteAddress.isInAnyCidr("fe80::1%eth0", listOf("fe80::/16")))
        assertTrue(RemoteAddress.isNonLoopbackBind("127.1"))
        assertFalse(RemoteAddress.isLoopbackAddress("127.0.0.1%lo0"))
        assertFalse(RemoteAddress.isInAnyCidr("127.0.0.1%lo0", listOf("127.0.0.0/8")))
        assertFalse(RemoteAddress.isInAnyCidr("10.0.0.5", listOf("2001:db8::/32")))
    }

    @Test
    fun `literal parsing rejects abbreviated unicode and mapped malformed IPv4`() {
        listOf(
            "127.1",
            "010.0.0.1",
            "127.000.0.1",
            "256.0.0.1",
            "::ffff:010.0.0.1",
            "::ffff:127.1",
            "::ffff:127.000.0.1",
            "١٢٧.0.0.1",
            "127.0.0.1%lo0",
            "127.0.1",
            "127..0.1",
            "2130706433",
            "127.+0.0.1",
            "127.-0.0.1",
            "dead.beef",
            ".::1",
            ":::1",
            "[::ffff:١٢٧.0.0.1]",
        ).forEach { input -> assertFalse(RemoteAddress.isLoopbackAddress(input), input) }
        assertFalse(RemoteAddress.isParseableCidr("١٢٧.0.0.0/8"))
        assertFalse(RemoteAddress.isParseableCidr("127.0.0.0%lo0/8"))
        assertFalse(RemoteAddress.isParseableCidr("::ffff:127.1/8"))
        assertFalse(RemoteAddress.isInAnyCidr("dead.beef", listOf("127.0.0.0/8")))
        assertFalse(RemoteAddress.isInAnyCidr("[127.0.0.1%:zone]", listOf("127.0.0.0/8")))
        assertFalse(RemoteAddress.isInAnyCidr("[::ffff:١٢٧.0.0.1]", listOf("127.0.0.0/8")))
        assertTrue(RemoteAddress.isNonLoopbackBind("[127.0.0.1%:zone]"))
        assertTrue(RemoteAddress.isNonLoopbackBind("127.0.0.1%lo0"))
    }

    @Test
    fun `literal parsing validates ports and preserves aliases`() {
        listOf(
            "127.0.0.1:0",
            "127.0.0.1:8080",
            "127.0.0.1:65535",
            "[::1]",
            "[::1]:0",
            "[::1]:8080",
            "[::1]:65535",
            "[::ffff:127.0.0.1]:8080",
        ).forEach { input ->
            assertTrue(RemoteAddress.isLoopbackAddress(input), input)
        }
        listOf(
            "127.0.0.1:abc",
            "127.0.0.1:",
            "127.0.0.1:٨٠",
            "127.0.0.1:+80",
            "127.0.0.1:-1",
            "127.0.0.1:8 0",
            "127.0.0.1:65536",
            "127.0.0.1:999999999999999999999999",
            "[127.0.0.1%:zone]",
            "[::1]:",
            "[::1]:abc",
            "[::1]:٨٠",
            "[::1]:+80",
            "[::1]:-1",
            "[::1]:8 0",
            "[::1]:65536",
            "[::1]:999999999999999999999999",
            "[::1]junk",
            "[::1",
            "[localhost]",
            "[127.0.0.1]",
        ).forEach { input -> assertFalse(RemoteAddress.isLoopbackAddress(input), input) }
        assertTrue(RemoteAddress.isLoopbackAddress("LOCALHOST:8080"))
        assertTrue(RemoteAddress.isLoopbackAddress("ip6-localhost"))
        assertFalse(RemoteAddress.isLoopbackAddress("::1:8080"))
    }

    @Test
    fun `config CIDR validation uses explicit data and content directories`() {
        val base = Files.createTempDirectory("plainbase-remote-address-native")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(base.resolve("content"))
            val env = mapOf(
                "DATA_DIR" to data.toString(),
                "CONTENT_DIR" to content.toString(),
                "PLAINBASE_TRUSTED_PROXY" to "10.0.0.0/8",
            )
            assertEquals(listOf("10.0.0.0/8"), PlainbaseConfig.fromEnv(env).auth.trustedProxyCidrs)

            val failure = assertFailsWith<IllegalArgumentException> {
                PlainbaseConfig.fromEnv(env + ("PLAINBASE_TRUSTED_PROXY" to "١٢٧.0.0.0/8"))
            }
            assertTrue(requireNotNull(failure.message).contains("PLAINBASE_TRUSTED_PROXY"))

            val zoneFailure = assertFailsWith<IllegalArgumentException> {
                PlainbaseConfig.fromEnv(env + ("PLAINBASE_TRUSTED_PROXY" to "127.0.0.0%lo0/8"))
            }
            assertTrue(requireNotNull(zoneFailure.message).contains("PLAINBASE_TRUSTED_PROXY"))
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}

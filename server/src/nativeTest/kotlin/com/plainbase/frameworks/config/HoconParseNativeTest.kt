package com.plainbase.frameworks.config

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Native proof (§D) that the HOCON file-PARSE path works inside the closed-world image: typesafe-config is
 * allowlisted but its `ConfigFactory.parseFile` path was previously unexercised under native. Writes a
 * `plainbase.conf` in a temp DATA_DIR, reads it via [PlainbaseConfig.fromEnvAndFile], asserts the parsed
 * values. kotlin.test + @Tag("native") only (the native gate's source set).
 */
@Tag("native")
class HoconParseNativeTest {

    @Test
    fun `fromEnvAndFile parses a plainbase_conf inside the native image`() {
        val data = Files.createTempDirectory("pb-native-hocon")
        try {
            Files.writeString(
                data.resolve("plainbase.conf"),
                """auth { mode = builtin, trustedProxy = ["10.0.0.0/8"] }""",
            )
            val config = PlainbaseConfig.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            assertEquals(AuthMode.BUILTIN, config.auth.mode)
            assertEquals(listOf("10.0.0.0/8"), config.auth.trustedProxyCidrs)
        } finally {
            Files.walk(data).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /**
     * The B3 substitution path under the image: a WITHIN-file `${proxyHost}` ref AND the optional
     * `${?PLAINBASE_HOST_FROM_FILE}` form (env-unset, so it drops silently — a bare `${…}` would throw). The
     * `.resolve(ConfigResolveOptions.defaults())` that `fromEnvAndFile` runs is the line this exercises natively:
     * without it the first typed getter throws `ConfigException.NotResolved` inside the closed-world image.
     */
    @Test
    fun `fromEnvAndFile resolves within-file and optional substitutions inside the native image`() {
        val data = Files.createTempDirectory("pb-native-hocon-subst")
        try {
            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                proxyHost = "192.168.0.0/24"
                # within-file ref: host reads the optional env (unset → drops) then falls to the within-file value
                host = ${'$'}{?PLAINBASE_HOST_FROM_FILE}
                host = "10.10.10.10"
                auth { mode = builtin, trustedProxy = [${'$'}{proxyHost}] }
                """.trimIndent(),
            )
            // No PLAINBASE_HOST_FROM_FILE in the env → the optional ref drops; the within-file ${proxyHost} resolves.
            val config = PlainbaseConfig.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            assertEquals("10.10.10.10", config.host)
            assertEquals(listOf("192.168.0.0/24"), config.auth.trustedProxyCidrs)
        } finally {
            Files.walk(data).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

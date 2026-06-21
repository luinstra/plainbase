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
}

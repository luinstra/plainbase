package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/** Verifies the production transport projection rather than a prebuilt test graph. */
class TransportSettingsTest : FunSpec({

    fun config(
        host: String,
        maxWriteBodyBytes: Long,
        maxAssetBytes: Long,
        auth: AuthConfig = AuthConfig(),
    ) = PlainbaseConfig(
        contentDir = Path.of("/tmp/content"),
        dataDir = Path.of("/tmp/data"),
        host = host,
        port = 8080,
        maxWriteBodyBytes = maxWriteBodyBytes,
        maxAssetBytes = maxAssetBytes,
        auth = auth,
    )

    test("transportSettings projects concrete defaults for a loopback config") {
        val settings = transportSettings(config("127.0.0.1", 1234L, 5678L))

        settings.maxWriteBodyBytes shouldBe 1234L
        settings.maxAssetBytes shouldBe 5678L
        settings.mcpAllowedHosts shouldBe listOf("127.0.0.1", "localhost")
        settings.mcpAllowedOrigins shouldBe listOf(
            "http://127.0.0.1:8080",
            "https://127.0.0.1:8080",
            "http://localhost:8080",
        )
        settings.secureCookie shouldBe false
    }

    test("transportSettings projects explicit MCP values and secure cookie for a routable config") {
        val settings = transportSettings(
            config(
                host = "docs.example.com",
                maxWriteBodyBytes = 2345L,
                maxAssetBytes = 6789L,
                auth = AuthConfig(
                    trustedProxyCidrs = listOf("10.0.0.0/8"),
                    mcpAllowedHosts = listOf("proxy.example.com", "proxy.example.com"),
                    mcpAllowedOrigins = listOf("https://proxy.example.com", "https://proxy.example.com"),
                ),
            ),
        )

        settings.maxWriteBodyBytes shouldBe 2345L
        settings.maxAssetBytes shouldBe 6789L
        settings.mcpAllowedHosts shouldBe listOf("proxy.example.com", "proxy.example.com")
        settings.mcpAllowedOrigins shouldBe listOf("https://proxy.example.com", "https://proxy.example.com")
        settings.secureCookie shouldBe true
    }
})

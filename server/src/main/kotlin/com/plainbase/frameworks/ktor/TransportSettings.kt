package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.TransportSecurityPolicy

/** Pure route-facing transport values derived from the normalized production configuration. */
internal class TransportSettings(
    val maxWriteBodyBytes: Long,
    val maxAssetBytes: Long,
    val mcpAllowedHosts: List<String>,
    val mcpAllowedOrigins: List<String>,
    val secureCookie: Boolean,
)

internal fun transportSettings(config: PlainbaseConfig): TransportSettings {
    val policy = TransportSecurityPolicy.derive(config)
    return TransportSettings(
        maxWriteBodyBytes = config.maxWriteBodyBytes,
        maxAssetBytes = config.maxAssetBytes,
        mcpAllowedHosts = policy.effectiveMcpHosts,
        mcpAllowedOrigins = policy.effectiveMcpOrigins,
        secureCookie = policy.secureCookie,
    )
}

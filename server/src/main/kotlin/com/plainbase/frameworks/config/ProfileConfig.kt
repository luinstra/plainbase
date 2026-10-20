package com.plainbase.frameworks.config

/** The deployment profile selected by the operator configuration. */
enum class DeploymentProfile {
    WORKSPACE,

    /** A reader declaration with file-only site metadata and an optional deployment assertion. */
    READER,
}

data class SiteConfig(
    val canonicalOrigin: String? = null,
    val name: String? = null,
)

package com.plainbase.frameworks.config

import com.plainbase.domain.content.TreePath

/** The deployment profile selected by the operator configuration. */
enum class DeploymentProfile {
    WORKSPACE,

    /**
     * Public read-only profile. Principal extraction returns Anonymous without reading a credential, header, cookie,
     * peer address or transport, and no session plugin is installed. This is safe only because Anonymous holds the whole
     * reader capability: READ through the READER policy mode.
     *
     * The reader binds loopback only. No override admits a non-loopback bind.
     */
    READER,
}

data class SiteConfig(
    val canonicalOrigin: String? = null,
    val name: String? = null,
)

internal fun readerRelease(storage: StorageConfig): String? = TreePath.of(storage.prefix)?.name

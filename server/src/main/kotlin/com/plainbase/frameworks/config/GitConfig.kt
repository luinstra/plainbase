package com.plainbase.frameworks.config

/** Git-history configuration; null [enabled] preserves automatic repository detection. */
data class GitConfig(
    val enabled: Boolean? = null,
    val authorName: String = PlainbaseConfig.DEFAULT_GIT_AUTHOR_NAME,
    val authorEmail: String = PlainbaseConfig.DEFAULT_GIT_AUTHOR_EMAIL,
)

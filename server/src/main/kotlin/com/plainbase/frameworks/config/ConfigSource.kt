package com.plainbase.frameworks.config

/** Where a collapsed env-wins configuration value came from. */
enum class ConfigSource {
    ENV,
    FILE,
    DEFAULT,
}

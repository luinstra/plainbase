package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.SiteConfig

/** The reader profile's release label and configured site identity. */
data class ReaderSurface(val release: String?, val site: SiteConfig)

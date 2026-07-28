package com.plainbase.domain.root

/**
 * The server-owned URL segments shared by domain URL emission and framework route registration.
 * Framework registrations import these values directly, so registrations and emitted links have one source.
 */
internal object ServerTopLevel {
    const val API = "api"
    const val ASSETS = "assets"
    const val BROWSE = "browse"
    const val HEALTHZ = "healthz"
    const val PERMALINK = "p"

    val segments = listOf(API, ASSETS, BROWSE, HEALTHZ, PERMALINK)
}

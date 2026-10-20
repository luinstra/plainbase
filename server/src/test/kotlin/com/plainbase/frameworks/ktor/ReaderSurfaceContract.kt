package com.plainbase.frameworks.ktor

import com.plainbase.domain.root.ServerTopLevel
import com.plainbase.frameworks.ktor.routes.FrontendBundle

internal val ReaderSurfaceContract: Set<String> = setOf(
    "GET /${ServerTopLevel.HEALTHZ}",
    "GET /${ServerTopLevel.API}/v1/session",
    "GET /${ServerTopLevel.API}/v1/pages/by-path/{path...}",
    "GET /${ServerTopLevel.API}/v1/pages/{id}",
    "GET /${ServerTopLevel.API}/v1/pages/{id}/html",
    "GET /${ServerTopLevel.API}/v1/pages/{id}/metadata",
    "GET /${ServerTopLevel.API}/v1/tree",
    "GET /${ServerTopLevel.API}/v1/search",
    "ANY /${ServerTopLevel.API}/{...}",
    "GET /${ServerTopLevel.ASSETS}/{path...}",
    "GET /${ServerTopLevel.PERMALINK}/{segments...}",
    "GET /${ServerTopLevel.BROWSE}/{path...}",
    "ANY /${ServerTopLevel.PLAINBASE}/theme-light.css",
    "ANY /${ServerTopLevel.PLAINBASE}/theme-dark.css",
    "ANY /${ServerTopLevel.PLAINBASE}/custom.css",
    "ANY /${ServerTopLevel.PLAINBASE}/{...}",
    "GET /{root}",
    "GET /{root}/{path...}",
    "GET /",
) + FrontendBundle.files.map { "GET /$it" } + FrontendBundle.directories.map { "GET /$it/{file}" }

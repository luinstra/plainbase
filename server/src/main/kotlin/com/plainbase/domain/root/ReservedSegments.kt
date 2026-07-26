package com.plainbase.domain.root

/**
 * The top-level segments a root may not take, checked at REGISTRATION - config load, `root add`, and the
 * `RootsConfig` snapshot constructor - and never inside [RootName.of]. A name that was legal when it was
 * written stays parseable forever; only registering a NEW root can be refused. [RootName]'s KDoc carries the
 * full argument for the split.
 *
 * Reservation is UNCONDITIONAL. Every auth, session, setup and admin surface in the binary is registered
 * conditionally on `auth.mode`, so a mode-aware list would let an `auth.mode=off` install take `session` and
 * then break the day the operator switches to builtin.
 *
 * The list only buys pretty names. The real reservoir is the [RootName] SHAPE, which is infinite and needs no
 * enumeration: a future global surface either nests under an already-reserved aggregator (`/admin`, `/api`,
 * `/auth`, `/settings`) or takes a shape no root name can have. That matters because the list has a one-way
 * ratchet on it from the first real install: growing it boot-refuses an install whose root already took the
 * new word, so it effectively freezes at 1.0 and today's generosity is the whole mechanism.
 */
object ReservedSegments {

    /**
     * `docs` is deliberately absent: it is the primary root's own name, so reserving it would boot-refuse the
     * required primary on every install. `p` is here as belt and braces only - what actually reserves the
     * single-character namespace is [RootName]'s minimum length of 2, and `RootNameTest` pins that.
     */
    val words: Set<String> = setOf(
        // Live server top-level routes, plus the two dot-free directories of the embedded frontend bundle.
        "api", "assets", "browse", "fonts", "healthz", "p",
        // Live SPA top-level routes.
        "admin", "new", "review",
        // Product-owned, and the stems of the prefixes below.
        "pb", "plainbase",
        // Foreseeable cross-root auth, admin, ops and discovery surfaces: each reads as a CAPABILITY rather
        // than as content a docs corpus would own, which is why `guides`, `changelog` and `team` are not here.
        "account", "accounts", "auth", "callback", "debug", "embed", "export", "favicon", "feed", "graphql",
        "health", "import", "livez", "login", "logout", "manifest", "mcp", "metrics", "notifications", "oauth",
        "oidc", "openapi", "password", "preview", "profile", "readyz", "register", "robots", "rpc", "saml",
        "search", "session", "sessions", "settings", "setup", "signin", "signout", "sitemap", "sso", "static",
        "status", "users", "webhooks", "well-known",
    )

    /** Namespaces the product can grow into without touching [words] again. Their stems are reserved too. */
    val prefixes: List<String> = listOf("pb-", "plainbase-")

    fun isReserved(name: RootName): Boolean = name.value in words || prefixes.any { name.value.startsWith(it) }
}

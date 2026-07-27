package com.plainbase.domain.root

/**
 * The top-level segments a root may not take, checked at REGISTRATION - config load, `root add`, and the
 * `RootsConfig` snapshot constructor - and never inside [RootName.of]. A word added here can only refuse a NEW
 * registration; it never makes an already-persisted name unparseable. Tightening [RootName]'s SHAPE does exactly
 * that, and this release did tighten it, which is the asymmetry the split turns on rather than a promise it
 * breaks: a shape change is paid once, by a reindex, while a growing word list would re-break installs forever.
 * [RootName]'s KDoc carries the full argument.
 *
 * Reservation is UNCONDITIONAL. Every auth, session, setup and admin surface in the binary is registered
 * conditionally on `auth.mode`, so a mode-aware list would let an `auth.mode=off` install take `session` and
 * then break the day the operator switches to builtin.
 *
 * The WORD list only buys pretty names. The real reservoir is SHAPE, which is infinite and needs no enumeration:
 * a future global surface either nests under an already-reserved aggregator (`/admin`, `/api`, `/auth`,
 * `/settings`) or takes a shape that never reaches a root. Most of that reservoir is [RootName]'s own grammar
 * (dots, underscores, digit-leading, single-character); the two [prefixes] and [VERSION] are the shapes that
 * parse as legal names and are refused here instead. That split matters because the word list has a one-way
 * ratchet on it from the first real install: growing it boot-refuses an install whose root already took the new
 * word, so it effectively freezes at 1.0 and today's generosity is the whole mechanism. The shape rules carry the
 * same ratchet, which is why they are broad now.
 */
object ReservedSegments {

    /**
     * `docs` is deliberately absent: it BECOMES the primary root's own name when the primary is renamed off
     * [RootName.MAIN], and reserving it would then boot-refuse the required primary on every install. `p` is here
     * as belt and braces only - what actually reserves the single-character namespace is [RootName]'s minimum
     * length of 2, and `RootNameTest` pins that.
     */
    val words: Set<String> = setOf(
        // Live server top-level routes, plus the embedded frontend bundle's own directories - which
        // `FrontendBundleTest` reads from the SERVED tree, so a new one that nothing here reserves goes red.
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

    /**
     * The API-version SHAPE: a `v` followed by digits and NOTHING else, so `v1` through `v999` and every longer
     * digit run are closed at once, with no enumeration and no [words] growth. Deliberately bounded: `v2beta`,
     * `v2-beta` and `v1alpha1` are legal root names and are NOT reserved. Say that plainly rather than claiming
     * every version spelling, which is the false-comment shape this rule was added to remove.
     *
     * Loosening a reservation later is additive and breaks nothing; tightening one boot-refuses an install that
     * already took the name, so this is claimed now rather than argued about later.
     */
    private val VERSION = Regex("v[0-9]+")

    fun isReserved(name: RootName): Boolean =
        name.value in words || prefixes.any { name.value.startsWith(it) } || VERSION.matches(name.value)
}

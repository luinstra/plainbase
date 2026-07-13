package com.plainbase.domain.root

/**
 * A root's name: the stable identity of one document directory, used as its config key and (from C3)
 * its `/docs/{root}/...` URL segment.
 *
 * The slug is deliberately tight - `[a-z0-9][a-z0-9-]*`, max 32 chars - so a name is always a clean
 * URL segment with no encoding concerns. No names beyond [MAIN] are reserved: every root URL lives under
 * the `/docs/` prefix, so a name can never collide with `/api`, `/p`, or `/assets`.
 *
 * A tight slug is NOT the same thing as an inert HOCON key, and the difference is a real bug we shipped
 * into review: `include` satisfies this regex and is also a HOCON directive. The writer therefore QUOTES
 * the key (`ManagedRootsFile.serialize`) rather than the name banning a reserved word, because the ban
 * list is the format's, it can grow, and it is not this type's business to track it.
 */
@JvmInline
value class RootName private constructor(val value: String) {

    override fun toString(): String = value

    companion object {

        /**
         * The reserved, REQUIRED primary root (ADR-0011 D1): config validation demands it, the CLI
         * refuses to remove or rename it, so `root = 'main'` migration stamps are safe forever.
         */
        val MAIN: RootName = RootName("main")

        private const val MAX_LENGTH = 32
        private val SLUG = Regex("[a-z0-9][a-z0-9-]*")

        /** Builds a [RootName] from [raw], or returns null unless it matches the slug rule above. */
        fun of(raw: String): RootName? =
            raw.takeIf { it.length <= MAX_LENGTH && SLUG.matches(it) }?.let(::RootName)

        /** Like [of] but throws [IllegalArgumentException] on invalid input. */
        fun require(raw: String): RootName =
            requireNotNull(of(raw)) { "not a valid root name: '$raw' (a lowercase slug [a-z0-9][a-z0-9-]*, max $MAX_LENGTH chars)" }

        /**
         * The REGISTERED name [raw] denotes, or null when [raw] is not a legal slug OR names no root in
         * [roots] - the ONE wire-string root resolution, shared by every surface that lets a client name a
         * root (`POST /pages` and the shared propose parser), each answering 400 `invalid_root` on null.
         *
         * PURE by design: it takes the name SET, never the [RootRegistry], so the transport-neutral propose
         * parser stays CALL-FREE and the two entries cannot drift into two hand-rolled two-step checks.
         */
        fun registered(raw: String, roots: Set<RootName>): RootName? = of(raw)?.takeIf { it in roots }
    }
}

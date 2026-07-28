package com.plainbase.domain.root

import com.plainbase.domain.page.PageId

/**
 * A root's name: the stable identity of one document directory, used as its config key and (from C3)
 * its URL segment.
 *
 * The slug is deliberately tight - `[a-z][a-z0-9]*(-[a-z0-9]+)*`, 2 to 32 chars - so a name is always a clean
 * URL segment with no encoding concerns. A root name also cannot parse as a [PageId], which keeps the shared
 * `/p/{segments...}` dispatcher unambiguous between bare page ids and rooted permalinks. The shape is also an
 * infinite reservoir the product can build in: a dotted, underscored, digit-leading or single-character
 * segment can never be a root name, so `/favicon.ico`, `/_internal` and `/p` are reserved forever with no list
 * to maintain. `/v2` is NOT one of them - it is a perfectly legal slug, closed instead by [ReservedSegments]'s
 * `v[0-9]+` rule, which is a shape too and so needs no list either.
 *
 * A tight slug is NOT the same thing as an inert HOCON key, and the difference is a real bug we shipped
 * into review: `include` satisfies this regex and is also a HOCON directive. The writer therefore QUOTES
 * the key (`ManagedRootsFile.serialize`) rather than the name banning a reserved word, because the ban
 * list is the format's, it can grow, and it is not this type's business to track it.
 *
 * [ReservedSegments] is the same argument applied to the product's own word list, and it is why the rules
 * that make a name UNPARSEABLE live here while the rules that only refuse a NEW registration (the word list,
 * the `pb-`/`plainbase-` prefixes, and the `v[0-9]+` version shape) live there. A shape THIS type rejects is
 * paid for once, by a reindex, while a growing registration refusal would turn every future addition into a
 * database that looks corrupt on an install nobody can tell to reindex.
 */
@JvmInline
value class RootName private constructor(val value: String) {

    override fun toString(): String = value

    companion object {

        /**
         * The reserved, REQUIRED primary root (`RootName.PRIMARY`, ADR-0011 D1): config validation demands it, the CLI
         * refuses to remove or rename it, and `main` is reserved so no live root can bind over its migration stamps.
         */
        val PRIMARY: RootName = RootName("docs")

        private const val MIN_LENGTH = 2
        private const val MAX_LENGTH = 32

        /**
         * Letter-leading, no trailing hyphen, no doubled hyphen. This pattern and its 2-32 bound are spelled out
         * again in eight hand-maintained places: five operator-facing messages ([require] here,
         * `PlainbaseConfig.parseRoot`, the `auth.agentDirectCommit.roots` refusal, and `root add`/`root remove`)
         * and three doc copies (`docs/configuration.md`'s shape paragraph AND its boot-validation bullets, plus
         * ADR-0011's URL-grammar supersession note). Different frames with different key names, so they are copies
         * rather than one awkward shared string. Three of the five MESSAGES are asserted by tests (`RootNameTest`,
         * `RootsConfigTest`, `RootCommandTest`); nothing gates the three doc copies. Changing the rule means editing
         * all eight by hand, plus the prose above and `RootNameTest`'s own restatement of the pattern.
         */
        private val SLUG = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")

        /** Builds a [RootName] from [raw], or returns null unless it matches the slug rule and is not page-id-shaped. */
        fun of(raw: String): RootName? =
            // The page-id guard is NOT subsumed by the shape rules: "a".repeat(32) satisfies every one of them
            // and is a valid 32-hex page id, so this is the only thing that rejects it.
            raw.takeIf { it.length in MIN_LENGTH..MAX_LENGTH && SLUG.matches(it) && PageId.of(it) == null }?.let(::RootName)

        /** Like [of] but throws when the slug is invalid or the value parses as a page id. */
        fun require(raw: String): RootName =
            requireNotNull(of(raw)) {
                "not a valid root name: '$raw' (a lowercase slug [a-z][a-z0-9]*(-[a-z0-9]+)*, " +
                    "$MIN_LENGTH-$MAX_LENGTH chars, and must not parse as a page id)"
            }

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

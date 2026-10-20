package com.plainbase.domain.service

/** The deployment's authorization policy, selected from normalized configuration. */
enum class PolicyMode {
    /** Local open access with mutating decisions audited. */
    OPEN,

    /** Role and live token capabilities govern access, with mutating decisions audited. */
    ENFORCED,

    /** Public reads with mutations refused and no security-state lookups or audit writes. */
    READER,
}

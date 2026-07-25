package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.service.ProposeCommand
import com.plainbase.frameworks.ktor.dto.ErrorCodes

/**
 * The shared `ProposeChangeRequest` -> `ProposeCommand` validation result (the F4 malformed-shape matrix), used by
 * BOTH the REST route and the MCP `propose_change` tool so their transport mappings cannot drift.
 */
sealed interface ProposeCommandParse {
    data class Ok(val command: ProposeCommand) : ProposeCommandParse

    /** [code] is the wire error code the caller emits — the default for every malformed shape, `invalid_root` for a
     * root that is not a legal slug or names no registered root. Carrying it HERE is what keeps the vocabulary ONE
     * across the three write surfaces instead of each mapping site hardcoding its own. */
    data class Invalid(val message: String, val code: String = ErrorCodes.INVALID_PROPOSE_REQUEST) : ProposeCommandParse
}

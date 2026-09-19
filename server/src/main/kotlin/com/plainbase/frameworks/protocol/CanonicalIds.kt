package com.plainbase.frameworks.protocol

/** Shared strict boundary shape for the two canonical hyphenated UUID consumers. */
private val CANONICAL_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

internal val CANONICAL_PAGE_ID = CANONICAL_UUID
internal val CANONICAL_PROPOSAL_ID = CANONICAL_UUID

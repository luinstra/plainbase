package com.plainbase.frameworks.mcp

import kotlinx.serialization.Serializable

@Serializable
data class McpAmbiguousCandidate(val root: String, val id: String)

/** Flat response: each root/id pair is a retry pin for the ambiguous page. */
@Serializable
data class McpAmbiguousResponse(
    val code: String,
    val id: String,
    val candidates: List<McpAmbiguousCandidate>,
    val message: String,
)

package com.plainbase.frameworks.ktor.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /api/v1/changes/{id}/reject` request — an optional reviewer comment. */
@Serializable
data class RejectChangeRequest(val comment: String? = null)

/**
 * `POST /api/v1/changes/{id}/approve` 200 applied body (P1b, append-only to PB-PROPOSE-1). [warnings] carries
 * `["reindex_deferred"]` for a `WrittenButUnindexed` apply, else absent. [commitSha] is present-null until Git produces one.
 */
@Serializable
data class ApplyResultResponse(
    @SerialName("new_hash") val newHash: String,
    @SerialName("commit_sha") val commitSha: String? = null,
    @SerialName("applied_at") val appliedAt: String,
    val warnings: List<String>? = null,
)

/** `POST /api/v1/changes/{id}/approve` 409 conflicted body (P1b). */
@Serializable
data class ConflictedResponse(
    val code: String = "conflicted",
    @SerialName("current_hash") val currentHash: String? = null,
    @SerialName("current_path") val currentPath: String? = null,
)

/** `POST /api/v1/changes/{id}/rebase` 200 body (P1b). */
@Serializable
data class RebasedResponse(
    @SerialName("new_base_hash") val newBaseHash: String,
    @SerialName("unified_diff") val unifiedDiff: String,
    val status: String = "PENDING",
)

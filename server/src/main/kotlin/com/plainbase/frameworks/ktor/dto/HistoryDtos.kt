package com.plainbase.frameworks.ktor.dto

import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.FileDiff
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The W5 history/diff read shapes (D-5). Plain server-as-authority DTOs, explicitly NOT a frozen PB-*
 * contract (no golden pins them): the history route owns its evolution, and the `git_enabled` flag lets
 * a client tell "Git off" (empty + false) apart from "Git on, no commits yet" (empty + true). The commit
 * timestamps are emitted as ISO-8601 text (the [kotlin.time.Instant] type never reaches the wire).
 */
@Serializable
data class CommitDto(
    val sha: String,
    @SerialName("author_name") val authorName: String,
    @SerialName("author_email") val authorEmail: String,
    @SerialName("author_time") val authorTime: String,
    @SerialName("committer_name") val committerName: String,
    @SerialName("committer_email") val committerEmail: String,
    @SerialName("committer_time") val committerTime: String,
    val message: String,
)

/**
 * `GET …/history` response. [commits] is newest-first; an empty list with [gitEnabled] true means
 * "no history yet" (distinct from Git off). Pagination is out of scope (W5) but the list shape admits
 * an additive cursor field later without breaking this non-frozen contract.
 */
@Serializable
data class HistoryResponse(
    @SerialName("git_enabled") val gitEnabled: Boolean,
    val commits: List<CommitDto>,
)

/** `GET …/diff` response — the file's unified diff between two commits; empty + false when Git is off. */
@Serializable
data class DiffResponse(
    @SerialName("git_enabled") val gitEnabled: Boolean,
    val from: String,
    val to: String,
    val path: String,
    @SerialName("unified_diff") val unifiedDiff: String,
)

fun Commit.toDto(): CommitDto = CommitDto(
    sha = sha,
    authorName = author.name,
    authorEmail = author.email,
    authorTime = authorTime.toString(),
    committerName = committer.name,
    committerEmail = committer.email,
    committerTime = committerTime.toString(),
    message = message,
)

fun FileDiff.toDto(gitEnabled: Boolean): DiffResponse = DiffResponse(
    gitEnabled = gitEnabled,
    from = from,
    to = to,
    path = path.value,
    unifiedDiff = unifiedDiff,
)

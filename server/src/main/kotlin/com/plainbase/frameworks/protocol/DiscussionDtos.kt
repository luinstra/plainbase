package com.plainbase.frameworks.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DiscussionPageDto(val id: String?, val path: String?, val resolution: String)

@Serializable
data class DiscussionActorDto(val key: String, val kind: String, val label: String)

@Serializable
data class DiscussionRangeDto(@SerialName("byte_start") val byteStart: Int, @SerialName("byte_end") val byteEnd: Int)

@Serializable
data class DiscussionCandidatesDto(val count: Int, val items: List<DiscussionRangeDto>, val truncated: Boolean)

@Serializable
data class DiscussionPlacementDto(val kind: String, val id: String?, val line: Long?)

@Serializable
data class DiscussionItemDto(
    val id: String,
    val page: DiscussionPageDto,
    val status: String?,
    val state: String,
    val reason: String?,
    val range: DiscussionRangeDto?,
    val candidates: DiscussionCandidatesDto?,
    val placement: DiscussionPlacementDto?,
    val quote: String?,
    @SerialName("comment_count") val commentCount: Int,
    val starter: DiscussionActorDto?,
    val created: String?,
    val updated: String?,
)

@Serializable
data class DiscussionListDto(
    val discussions: List<DiscussionItemDto>,
    val next: String?,
    @SerialName("discussions_available") val discussionsAvailable: Boolean,
    val reason: String?,
)

@Serializable
data class DiscussionCommentDto(
    val id: String,
    val author: DiscussionActorDto,
    val created: String,
    @SerialName("edited_at") val editedAt: String?,
    val retracted: Boolean,
    val html: String,
    val markdown: String,
)

@Serializable
data class DiscussionDetailDto(
    val discussion: JsonObject?,
    val comments: List<DiscussionCommentDto>,
    val next: String?,
    @SerialName("discussions_available") val discussionsAvailable: Boolean,
    val reason: String?,
)

@Serializable
data class DiscussionPreviewDto(
    @SerialName("content_hash") val contentHash: String,
    @SerialName("byte_start") val byteStart: Long,
    @SerialName("byte_end") val byteEnd: Long,
    val selection: String,
    @SerialName("quote_text") val quoteText: String,
)

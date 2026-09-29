package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.AnchorMatch
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.DiscussionRow
import com.plainbase.domain.discussion.IdentityDigest
import com.plainbase.domain.discussion.MatchRange
import com.plainbase.domain.discussion.PageBytes
import com.plainbase.domain.discussion.Placement
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AnchorMatches
import com.plainbase.domain.service.DetailPage
import com.plainbase.domain.service.DiscussionPageResolution
import com.plainbase.domain.service.DiscussionPageResolver
import com.plainbase.domain.service.DiscussionReadFailed
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionSummary
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.protocol.DiscussionActorDto
import com.plainbase.frameworks.protocol.DiscussionCandidatesDto
import com.plainbase.frameworks.protocol.DiscussionCommentDto
import com.plainbase.frameworks.protocol.DiscussionDetailDto
import com.plainbase.frameworks.protocol.DiscussionItemDto
import com.plainbase.frameworks.protocol.DiscussionListDto
import com.plainbase.frameworks.protocol.DiscussionPageDto
import com.plainbase.frameworks.protocol.DiscussionPlacementDto
import com.plainbase.frameworks.protocol.DiscussionRangeDto
import com.plainbase.frameworks.protocol.DiscussionReadRefused
import com.plainbase.frameworks.protocol.RestJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import java.time.Instant as JavaInstant

/** Projects derived rows against one caller-owned page snapshot. */
class DiscussionReadProjection(
    private val reads: DiscussionReads,
    private val pages: DiscussionPageResolver,
    private val matches: AnchorMatches,
    private val absence: AbsenceClassifier,
    private val stores: (RootName) -> ContentStore,
) {
    fun pageList(
        root: RootName,
        page: IndexedPage,
        snapshot: PageIndex,
        after: DiscussionId?,
        limit: Int,
    ): DiscussionListDto {
        val cache = PageSupplierCache(snapshot)
        val result = reads.pageDiscussions(root, page, snapshot, after, limit)
        return DiscussionListDto(result.discussions.map { project(root, it, snapshot, cache) }, result.next?.value, true, null)
    }

    fun rootList(
        root: RootName,
        snapshot: PageIndex,
        after: DiscussionId?,
        limit: Int,
        state: String?,
    ): DiscussionListDto {
        if (state != null && state !in STATES) throw DiscussionReadRefused(400, "invalid_query")
        val cache = PageSupplierCache(snapshot)
        val projected = HashMap<DiscussionId, DiscussionItemDto>()
        val page = reads.rootDiscussions(root, after, limit) { summary ->
            val item = project(root, summary, snapshot, cache)
            projected[summary.id] = item
            state == null || item.state == state
        }
        return DiscussionListDto(page.discussions.map { projected.getValue(it.id) }, page.next?.value, true, null)
    }

    fun detail(
        root: RootName,
        id: DiscussionId,
        snapshot: PageIndex,
        after: CommentId?,
        limit: Int,
    ): DiscussionDetailDto {
        val content = when (val result = reads.detail(root, id, after, limit)) {
            DetailPage.Absent -> throw DiscussionReadRefused(404, "discussion_not_found")
            is DetailPage.Content -> result
        }
        val record = (content.read as? DiscussionRead.Ok)?.files?.marker?.value
        val base = project(root, content.summary, snapshot, PageSupplierCache(snapshot))
        val item = if (record != null) base.copy(starter = author(record.startedBy)) else base
        val details = buildJsonObject {
            RestJson.encodeToJsonElement(DiscussionItemDto.serializer(), item).jsonObject.forEach { (key, value) -> put(key, value) }
            put("anchor", record?.anchor?.let(::anchor) ?: JsonNull)
            put(
                "reattachment",
                record?.reattachment?.let { reattachment ->
                    buildJsonObject {
                        put("by", actor(reattachment.by))
                        put("at", time(reattachment.at))
                        put("anchor", anchor(reattachment.anchor))
                    }
                } ?: JsonNull,
            )
        }
        val path = base.page.path?.let(TreePath::require) ?: record?.page?.path
        val renderer = path?.let { FlexmarkRenderer(snapshot.view(root)) }
        val comments = (content.read as? DiscussionRead.Ok)?.files?.comments.orEmpty().map { stored ->
            val comment = stored.value
            DiscussionCommentDto(
                id = comment.id.value,
                author = author(comment.author),
                created = time(comment.created),
                editedAt = comment.editedAt?.let(::time),
                retracted = comment.retraction != null,
                html = path?.let { checkNotNull(renderer).renderFragment(it, comment.body) } ?: "",
                markdown = comment.body,
            )
        }
        return DiscussionDetailDto(details, comments, content.nextComment?.value, true, null)
    }

    private fun project(
        root: RootName,
        summary: DiscussionSummary,
        snapshot: PageIndex,
        cache: PageSupplierCache,
    ): DiscussionItemDto {
        val location = location(root, summary, snapshot, cache)
        val masked = location.state == "incomplete" || location.state == "unreadable"
        val candidate = location.match as? AnchorMatch.Ambiguous
        return DiscussionItemDto(
            id = summary.id.value,
            page = DiscussionPageDto(
                if (location.state == "incomplete") null else summary.pageId?.value,
                if (location.state == "incomplete") null else location.path, location.resolution,
            ),
            status = if (masked) null else summary.status,
            state = location.state,
            reason = publicReason(summary, location),
            range = matchRange(location.match),
            candidates = candidate?.let { DiscussionCandidatesDto(it.count, it.candidates.take(20).map(::range), it.truncated) },
            placement = placementDto(location.match),
            quote = if (masked) null else summary.quotePreview,
            commentCount = summary.commentCount,
            starter = if (masked) {
                null
            } else {
                summary.starterKey?.let { key ->
                    DiscussionActorDto(key, summary.starterKind ?: "human", summary.starterLabel ?: "")
                }
            },
            created = if (masked) null else summary.created?.let(::time),
            updated = if (masked) null else summary.updated?.let(::time),
        )
    }

    private data class Location(val state: String, val resolution: String, val path: String?, val match: AnchorMatch? = null)

    private data class CachedPage(val id: PageId, val bytes: PageBytes)

    private fun publicReason(summary: DiscussionSummary, location: Location): String? = when {
        location.state != "unreadable" -> null
        summary.state == "failed" -> "read_failure"
        summary.state == "unreadable" -> summary.reason ?: "content_unreadable"
        else -> "anchor_unavailable"
    }

    private fun location(root: RootName, summary: DiscussionSummary, snapshot: PageIndex, cache: PageSupplierCache): Location {
        if (summary.state == "incomplete") return Location("incomplete", "unknown", null)
        if (summary.state == "unreadable" || summary.state == "failed") {
            return Location("unreadable", "unknown", summary.pagePath?.value)
        }
        val pageId = summary.pageId ?: return Location("orphaned", "orphaned", summary.pagePath?.value)
        return when (val found = pages.resolve(root, pageId, summary.pagePath, snapshot)) {
            DiscussionPageResolution.Orphaned -> Location("orphaned", "orphaned", summary.pagePath?.value)
            DiscussionPageResolution.Unavailable -> Location("unavailable", "unavailable", summary.pagePath?.value)
            is DiscussionPageResolution.Found -> {
                val matched = match(root, summary, found.page, cache)
                val state = matchState(matched)
                if (state == "unreadable") {
                    Location(state, "unknown", summary.pagePath?.value)
                } else {
                    Location(
                        state, if (found.match == DiscussionPageResolution.Match.BY_ID) "by_id" else "by_path",
                        found.page.path.value, matched,
                    )
                }
            }
        }
    }

    private fun matchState(match: AnchorMatch?): String = when (match) {
        AnchorMatch.PageLevel -> "page_level"
        is AnchorMatch.Exact -> "exact"
        is AnchorMatch.Moved -> "moved"
        is AnchorMatch.Ambiguous -> "ambiguous"
        is AnchorMatch.Changed -> "changed"
        null -> "unreadable"
    }

    private fun matchRange(match: AnchorMatch?): DiscussionRangeDto? = when (match) {
        is AnchorMatch.Exact -> range(match.range)
        is AnchorMatch.Moved -> range(match.range)
        else -> null
    }

    private fun placementDto(match: AnchorMatch?): DiscussionPlacementDto? = when (
        val placement = (match as? AnchorMatch.Changed)?.placement
    ) {
        is Placement.Heading -> DiscussionPlacementDto("heading", placement.id, null)
        is Placement.Line -> DiscussionPlacementDto("line", null, placement.line)
        null -> null
    }

    private fun match(root: RootName, summary: DiscussionSummary, page: IndexedPage, cache: PageSupplierCache): AnchorMatch? {
        val row = DiscussionRow(
            root, summary.id, summary.state, summary.reason, null, summary.pageId, summary.pagePath,
            summary.status, summary.anchorKind, summary.anchorHash, summary.starterKey, summary.created, summary.updated,
            summary.commentCount, summary.starterKind, summary.starterLabel, summary.quotePreview,
        )
        return matches.forPage(root, page.id, page.contentHash, listOf(row)) { cache.get(page) }
            .firstOrNull { it.id == summary.id }?.match
    }

    private inner class PageSupplierCache(private val snapshot: PageIndex) {
        private var current: CachedPage? = null

        fun get(page: IndexedPage): PageBytes {
            if (current?.id == page.id) return checkNotNull(current).bytes
            current = null
            val bytes = when (val read = absence.read(stores(page.root), RootedPath(page.root, page.path))) {
                is ContentRead.Bytes -> read.bytes
                ContentRead.RootDown -> throw RootUnavailable(page.root, UnavailableCause.VANISHED)
                ContentRead.AbsenceUnknown, ContentRead.ConfirmedAbsent -> throw DiscussionReadFailed(page.root, "page unavailable")
            }
            val rendered = FlexmarkRenderer(snapshot.view(page.root)).render(page.path, bytes)
            val pageBytes = PageBytes.of(bytes, rendered.headings)
            current = CachedPage(page.id, pageBytes)
            return pageBytes
        }
    }

    private fun range(value: MatchRange) = DiscussionRangeDto(value.byteStart, value.byteEnd)

    private fun author(value: Author) = DiscussionActorDto(IdentityDigest.of(value.actor.subject), value.kind.wire, value.actor.label)

    private fun actor(value: Actor): JsonObject = buildJsonObject {
        put("key", IdentityDigest.of(value.subject))
        put("label", value.label)
    }

    private fun anchor(value: Anchor): JsonObject = buildJsonObject {
        put("kind", if (value is Anchor.Page) "page" else "quote")
        put("content_hash", value.contentHash)
        put("commit", value.commit?.let(::JsonPrimitive) ?: JsonNull)
        if (value is Anchor.Quote) {
            val capture = value.capture
            put("quote", capture.quote)
            put("prefix", capture.prefix)
            put("suffix", capture.suffix)
            put("byte_start", capture.byteStart)
            put("byte_end", capture.byteEnd)
            put("body_start", capture.bodyStart)
            put("line", capture.line)
            put("selection", capture.selection.wire)
            put(
                "heading_path",
                buildJsonArray {
                    capture.headingPath.entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("level", entry.level)
                                put("text", entry.text)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun time(epoch: Long): String = time(Instant.fromEpochMilliseconds(epoch))

    private fun time(value: Instant): String = FORMAT.format(JavaInstant.ofEpochMilli(value.toEpochMilliseconds()))

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        val STATES = setOf(
            "page_level", "exact", "moved", "ambiguous", "changed", "orphaned", "unavailable",
            "unreadable", "incomplete",
        )
    }
}

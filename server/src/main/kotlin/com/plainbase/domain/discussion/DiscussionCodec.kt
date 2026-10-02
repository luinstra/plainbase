package com.plainbase.domain.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.SubjectKey
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import kotlin.time.Instant

private val pageIdPeekPattern = Regex("^page_id: \"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"$")

sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>
    data class Unreadable(val reason: UnreadableReason, val detail: String) : Decoded<Nothing>
}

enum class UnreadableReason(val wire: String) {
    DUPLICATE_KEY("duplicate_key"),
    MISSING_KEY("missing_key"),
    WRONG_KIND("wrong_kind"),
    BAD_VALUE("bad_value"),
    SYMLINK("symlink"),
    TOO_MANY_COMMENTS("too_many_comments"),
}

object DiscussionCodec {
    private const val DISCUSSION_FORMAT = "plainbase-discussion/1"
    private const val COMMENT_FORMAT = "plainbase-comment/1"
    private const val HASH_PREFIX = "sha256:"
    private val timePattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val integerPattern = Regex("^(?:0|[1-9][0-9]{0,9})$")
    private val timeFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .toFormatter()
        .withZone(ZoneOffset.UTC)
    private val headPattern = Regex("^([a-z_]+):")
    private val headingPattern = Regex("^([1-6]) (.*)$", RegexOption.DOT_MATCHES_ALL)
    private val commentHashPattern = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
    private val sha256Pattern = Regex("^sha256:[0-9a-f]{64}$")
    private val stringKeys = setOf(
        "format", "id", "page_id", "page_path", "status", "started_by_issuer", "started_by_id",
        "started_by_label", "started_by_kind", "status_changed_by_issuer", "status_changed_by_id",
        "status_changed_by_label", "status_changed_at", "anchor_kind", "anchor_content_hash", "anchor_commit",
        "anchor_quote", "anchor_prefix", "anchor_suffix", "anchor_selection", "reattached_by_issuer",
        "reattached_by_id", "reattached_by_label", "reattached_at", "reattach_content_hash", "reattach_commit",
        "reattach_quote", "reattach_prefix", "reattach_suffix", "reattach_selection", "discussion_id",
        "author_issuer", "author_id", "author_label", "author_kind", "created", "edited_at",
        "retracted_by_issuer", "retracted_by_id", "retracted_by_label", "retracted_at",
    )
    private val integerKeys = setOf(
        "anchor_byte_start", "anchor_byte_end", "anchor_body_start", "anchor_line",
        "reattach_byte_start", "reattach_byte_end", "reattach_body_start", "reattach_line",
    )
    private val listKeys = setOf("anchor_heading_path", "reattach_heading_path")
    private val discussionKeys = setOf(
        "format", "id", "page_id", "page_path", "status", "created", "started_by_issuer", "started_by_id",
        "started_by_label", "started_by_kind", "status_changed_by_issuer", "status_changed_by_id",
        "status_changed_by_label", "status_changed_at", "anchor_kind", "anchor_content_hash", "anchor_commit",
        "anchor_quote", "anchor_prefix", "anchor_suffix", "anchor_byte_start", "anchor_byte_end",
        "anchor_body_start", "anchor_line", "anchor_selection", "anchor_heading_path", "reattached_by_issuer",
        "reattached_by_id", "reattached_by_label", "reattached_at", "reattach_content_hash", "reattach_commit",
        "reattach_quote", "reattach_prefix", "reattach_suffix", "reattach_byte_start", "reattach_byte_end",
        "reattach_body_start", "reattach_line", "reattach_selection", "reattach_heading_path",
    )
    private val commentKeys = setOf(
        "format", "id", "discussion_id", "author_issuer", "author_id", "author_label", "author_kind", "created",
        "edited_at", "retracted_by_issuer", "retracted_by_id", "retracted_by_label", "retracted_at",
    )
    private val discussionBaseRequired = listOf(
        "format", "id", "page_id", "page_path", "status", "created", "started_by_issuer", "started_by_id",
        "started_by_label", "started_by_kind", "anchor_kind", "anchor_content_hash",
    )
    private val quoteRequired = listOf(
        "anchor_quote", "anchor_prefix", "anchor_suffix", "anchor_byte_start", "anchor_byte_end",
        "anchor_body_start", "anchor_line", "anchor_selection", "anchor_heading_path",
    )
    private val reattachmentRequired = listOf(
        "reattached_by_issuer", "reattached_by_id", "reattached_by_label", "reattached_at", "reattach_content_hash",
        "reattach_quote", "reattach_prefix", "reattach_suffix", "reattach_byte_start", "reattach_byte_end",
        "reattach_body_start", "reattach_line", "reattach_selection", "reattach_heading_path",
    )
    private val statusGroup = listOf(
        "status_changed_by_issuer", "status_changed_by_id", "status_changed_by_label", "status_changed_at",
    )
    private val commentBaseRequired = listOf(
        "format", "id", "discussion_id", "author_issuer", "author_id", "author_label", "author_kind", "created",
    )
    private val retractionGroup = listOf("retracted_by_issuer", "retracted_by_id", "retracted_by_label", "retracted_at")
    private val rawEscapeSet = (0x0000..0x001F).toSet() + (0x007F..0x009F).toSet() + setOf(0x2028, 0x2029, 0xFEFF, 0xFFFE, 0xFFFF)

    fun encodeDiscussion(record: DiscussionRecord): ByteArray {
        val out = StringBuilder()
        out.append("---\n")
        appendLeading(out, record.extras.leading)
        out.append("format: ").append(quote(DISCUSSION_FORMAT)).append('\n')
        out.append("id: ").append(quote(record.id.value)).append('\n')
        out.append("page_id: ").append(quote(record.page.pageId.value)).append('\n')
        out.append("page_path: ").append(quote(record.page.path.value)).append('\n')
        out.append("status: ").append(quote(record.status.wire)).append('\n')
        out.append("created: ").append(quote(formatTime(record.created))).append('\n')
        appendAuthor(out, "started_by", record.startedBy)
        record.statusChange?.let { change ->
            appendActor(out, "status_changed_by", change.by)
            out.append("status_changed_at: ").append(quote(formatTime(change.at))).append('\n')
        }
        out.append("anchor_kind: ").append(quote(if (record.anchor is Anchor.Page) "page" else "quote")).append('\n')
        out.append("anchor_content_hash: ").append(quote(record.anchor.contentHash)).append('\n')
        record.anchor.commit?.let { out.append("anchor_commit: ").append(quote(it)).append('\n') }
        if (record.anchor is Anchor.Quote) appendQuote(out, "anchor", record.anchor)
        record.reattachment?.let { reattachment ->
            appendActor(out, "reattached_by", reattachment.by)
            out.append("reattached_at: ").append(quote(formatTime(reattachment.at))).append('\n')
            out.append("reattach_content_hash: ").append(quote(reattachment.anchor.contentHash)).append('\n')
            reattachment.anchor.commit?.let { out.append("reattach_commit: ").append(quote(it)).append('\n') }
            appendQuote(out, "reattach", reattachment.anchor)
        }
        appendTrailing(out, record.extras.trailing, discussionKeys)
        out.append("---\n")
        return strictUtf8(out.toString())
    }

    fun encodeComment(record: CommentRecord): ByteArray {
        val out = StringBuilder()
        out.append("---\n")
        appendLeading(out, record.extras.leading)
        out.append("format: ").append(quote(COMMENT_FORMAT)).append('\n')
        out.append("id: ").append(quote(record.id.value)).append('\n')
        out.append("discussion_id: ").append(quote(record.discussionId.value)).append('\n')
        appendAuthor(out, "author", record.author)
        out.append("created: ").append(quote(formatTime(record.created))).append('\n')
        record.editedAt?.let { out.append("edited_at: ").append(quote(formatTime(it))).append('\n') }
        record.retraction?.let { retraction ->
            appendActor(out, "retracted_by", retraction.by)
            out.append("retracted_at: ").append(quote(formatTime(retraction.at))).append('\n')
        }
        appendTrailing(out, record.extras.trailing, commentKeys)
        out.append("---\n").append(record.body)
        return strictUtf8(out.toString())
    }

    fun decodeDiscussion(bytes: ByteArray): Decoded<DiscussionRecord> {
        val parsed = parseDocument(bytes, MAX_MARKER_BYTES, discussionKeys, includeBody = false)
        if (parsed is ParseResult.Error) return unreadable(parsed.error)
        parsed as ParseResult.Document
        val values = parsed.values
        discussionEnvelopeError(values)?.let { return it }

        return try {
            val fields = discussionFields(values)
            if (fields is Decoded.Unreadable) return fields
            fields as Decoded.Ok
            val anchors = discussionAnchors(values)
            if (anchors is Decoded.Unreadable) return anchors
            anchors as Decoded.Ok
            Decoded.Ok(
                DiscussionRecord(
                    id = fields.value.id,
                    page = fields.value.page,
                    status = fields.value.status,
                    created = fields.value.created,
                    startedBy = fields.value.startedBy,
                    statusChange = fields.value.statusChange,
                    anchor = anchors.value.anchor,
                    reattachment = anchors.value.reattachment,
                    extras = parsed.extras,
                ),
            )
        } catch (failure: IllegalArgumentException) {
            bad(failure.message ?: "invalid discussion")
        }
    }

    private fun discussionEnvelopeError(values: Map<String, Any>): Decoded.Unreadable? {
        firstMissing(discussionBaseRequired, values)?.let { return missing(it) }
        if (string(values, "format") != DISCUSSION_FORMAT) return bad("unsupported discussion format")
        if (string(values, "anchor_kind") == "quote" && quoteRequired.any { it !in values }) {
            return bad("incomplete quote anchor")
        }
        return null
    }

    private fun discussionFields(values: Map<String, Any>): Decoded<DiscussionFields> {
        val id = DiscussionId.of(string(values, "id")) ?: return bad("invalid discussion id")
        val pageIdText = string(values, "page_id")
        val pageId = PageId.of(pageIdText)?.takeIf { it.value == pageIdText } ?: return bad("invalid page id")
        val pagePathText = string(values, "page_path")
        val pagePath = TreePath.of(pagePathText)?.takeIf { it.value == pagePathText } ?: return bad("invalid page path")
        val status = DiscussionStatus.entries.firstOrNull { it.wire == string(values, "status") }
            ?: return bad("invalid status")
        val created = instant(string(values, "created")) ?: return bad("invalid created time")
        val startedBy = author(values, "started_by") ?: return bad("invalid starter")
        if (statusGroup.all(values::containsKey) && string(values, "status_changed_by_id").isBlank()) {
            return bad("status actor id is blank")
        }
        val statusChange = optionalActorGroup(values, statusGroup, "status_changed_by", "status_changed_at")
            ?: if (statusGroup.any(values::containsKey)) return bad("incomplete status group") else null
        return Decoded.Ok(DiscussionFields(id, PageRef(pageId, pagePath), status, created, startedBy, statusChange))
    }

    private fun discussionAnchors(values: Map<String, Any>): Decoded<DiscussionAnchors> {
        val contentHash = string(values, "anchor_content_hash")
        if (!sha256Pattern.matches(contentHash)) return bad("invalid content hash")
        if ("anchor_commit" in values && optionalCommit(values, "anchor_commit") == null) return bad("invalid anchor commit")
        val anchorCommit = optionalCommit(values, "anchor_commit")
        val hasReattachment = reattachmentKeys().any(values::containsKey)
        if (hasReattachment && reattachmentRequired.any { it !in values }) return bad("incomplete reattachment group")
        val anchor = when (string(values, "anchor_kind")) {
            "page" -> {
                if ((quoteRequired + reattachmentKeys()).any(values::containsKey)) return bad("page anchor has quote data")
                Anchor.Page(contentHash, anchorCommit)
            }
            "quote" -> quote(values, "anchor") ?: return bad("invalid quote")
            else -> return bad("invalid anchor kind")
        }
        val reattachment = buildReattachment(values)
        if (hasReattachment && reattachment == null) return bad("invalid reattachment group")
        if (anchor is Anchor.Page && reattachment != null) return bad("page anchor has reattachment")
        return Decoded.Ok(DiscussionAnchors(anchor, reattachment))
    }

    fun decodeComment(bytes: ByteArray): Decoded<CommentRecord> {
        val parsed = parseDocument(bytes, MAX_COMMENT_BYTES, commentKeys, includeBody = true)
        if (parsed is ParseResult.Error) return unreadable(parsed.error)
        parsed as ParseResult.Document
        val values = parsed.values
        firstMissing(commentBaseRequired, values)?.let { return missing(it) }

        return try {
            if (string(values, "format") != COMMENT_FORMAT) return bad("unsupported comment format")
            val id = CommentId.of(string(values, "id")) ?: return bad("invalid comment id")
            val discussionId = DiscussionId.of(string(values, "discussion_id")) ?: return bad("invalid discussion id")
            val author = author(values, "author") ?: return bad("invalid author")
            val created = instant(string(values, "created")) ?: return bad("invalid created time")
            val editedAt = values["edited_at"]?.let { instant(it as String) ?: return bad("invalid edit time") }
            if (retractionGroup.all(values::containsKey) && string(values, "retracted_by_id").isBlank()) {
                return bad("retraction actor id is blank")
            }
            val retraction = optionalActorGroup(values, retractionGroup, "retracted_by", "retracted_at")
                ?.let { Retraction(it.by, it.at) }
                ?: if (retractionGroup.any(values::containsKey)) return bad("incomplete retraction group") else null
            Decoded.Ok(
                CommentRecord(
                    id = id,
                    discussionId = discussionId,
                    author = author,
                    created = created,
                    editedAt = editedAt,
                    retraction = retraction,
                    body = parsed.body,
                    extras = parsed.extras,
                ),
            )
        } catch (failure: IllegalArgumentException) {
            bad(failure.message ?: "invalid comment")
        }
    }

    fun peekPageId(markerBytes: ByteArray): PageId? {
        val detection = FrontmatterBlock.detect(markerBytes) as? FrontmatterBlock.Detection.Present ?: return null
        var start = detection.innerStart
        while (start < detection.innerEnd) {
            var end = start
            while (end < detection.innerEnd && markerBytes[end] != '\n'.code.toByte() && markerBytes[end] != '\r'.code.toByte()) end++
            val line = strictDecodeOrNull(markerBytes.copyOfRange(start, end))
            if (line != null) {
                val match = pageIdPeekPattern.matchEntire(line)
                if (match != null) {
                    val text = match.groupValues[1]
                    return PageId.of(text)?.takeIf { it.value == text }
                }
            }
            while (end < detection.innerEnd && (markerBytes[end] == '\n'.code.toByte() || markerBytes[end] == '\r'.code.toByte())) end++
            start = end
        }
        return null
    }

    private fun parseDocument(bytes: ByteArray, cap: Int, documentKeys: Set<String>, includeBody: Boolean): ParseResult {
        if (bytes.size > cap) return ParseResult.Error(DecodeError(UnreadableReason.BAD_VALUE, "entry exceeds $cap bytes"))
        val detection = FrontmatterBlock.detect(bytes) as? FrontmatterBlock.Detection.Present
            ?: return ParseResult.Error(DecodeError(UnreadableReason.BAD_VALUE, "frontmatter is absent"))
        if (!validEols(bytes, detection.bomLength, detection.bodyStart)) {
            return ParseResult.Error(DecodeError(UnreadableReason.BAD_VALUE, "frontmatter has a lone carriage return"))
        }
        val inner = strictDecodeOrNull(bytes.copyOfRange(detection.innerStart, detection.innerEnd))
            ?: return ParseResult.Error(DecodeError(UnreadableReason.BAD_VALUE, "frontmatter is not strict UTF-8"))
        val normalized = inner.replace("\r\n", "\n")
        val lines = splitLines(normalized.removeSuffix("\n"))
        val body = if (includeBody) {
            strictDecodeOrNull(bytes.copyOfRange(detection.bodyStart, bytes.size))
                ?: return ParseResult.Error(DecodeError(UnreadableReason.BAD_VALUE, "comment body is not strict UTF-8"))
        } else {
            ""
        }
        val parsed = parseLines(lines, documentKeys)
        if (parsed is LineResult.Error) return ParseResult.Error(parsed.error)
        parsed as LineResult.Values
        return ParseResult.Document(parsed.values, parsed.extras, body)
    }

    private fun parseLines(lines: List<String>, documentKeys: Set<String>): LineResult {
        val leadingEnd = lines.indexOfFirst { it.isNotEmpty() && !it.startsWith(' ') }.let { if (it < 0) lines.size else it }
        val leadingLines = lines.take(leadingEnd).filter(String::isNotEmpty)
        val leading = leadingLines.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n", postfix = "\n")
        val values = linkedMapOf<String, Any>()
        val trailing = StringBuilder()
        var index = leadingEnd
        while (index < lines.size) {
            if (lines[index].isEmpty() || lines[index].startsWith(' ')) {
                index++
            } else {
                val start = index
                index++
                while (index < lines.size && (lines[index].isEmpty() || lines[index].startsWith(' '))) index++
                val block = lines.subList(start, index)
                val error = appendParsedBlock(block, values, trailing, documentKeys)
                if (error != null) return LineResult.Error(error)
            }
        }
        val leadingText = leading.orEmpty()
        return LineResult.Values(values, FrontmatterExtras(leadingText + trailing, leadingText.length))
    }

    private fun appendParsedBlock(
        block: List<String>,
        values: MutableMap<String, Any>,
        trailing: StringBuilder,
        documentKeys: Set<String>,
    ): DecodeError? {
        val head = headPattern.find(block.first())
        val key = head?.groupValues?.get(1)
        if (key == null || key !in documentKeys) {
            trailing.append(block.joinToString("\n")).append('\n')
            return null
        }
        if (values.containsKey(key)) return DecodeError(UnreadableReason.DUPLICATE_KEY, "duplicate key '$key'")
        return when (val parsed = parseKnownBlock(key, head.value.length, block)) {
            KnownResult.WrongKind -> DecodeError(UnreadableReason.WRONG_KIND, "wrong kind for '$key'")
            is KnownResult.Error -> DecodeError(UnreadableReason.BAD_VALUE, parsed.detail)
            is KnownResult.Value -> {
                values[key] = parsed.value
                null
            }
        }
    }

    private fun parseKnownBlock(key: String, colonEnd: Int, block: List<String>): KnownResult {
        val rest = block.first().substring(colonEnd)
        val actualKind = when {
            rest.isEmpty() -> ValueKind.LIST
            !rest.startsWith(' ') || rest.startsWith("  ") -> return KnownResult.Error("malformed value for '$key'")
            rest.substring(1) == "[]" -> ValueKind.LIST
            rest.substring(1).startsWith('"') -> ValueKind.STRING
            rest.substring(1).isNotEmpty() && rest.substring(1).all { it in '0'..'9' } -> ValueKind.INTEGER
            else -> return KnownResult.Error("malformed value for '$key'")
        }
        val expectedKind = when (key) {
            in stringKeys -> ValueKind.STRING
            in integerKeys -> ValueKind.INTEGER
            else -> ValueKind.LIST
        }
        if (actualKind != expectedKind) return KnownResult.WrongKind
        val continuation = block.drop(1).filter(String::isNotEmpty)
        return when (expectedKind) {
            ValueKind.STRING -> {
                if (continuation.isNotEmpty()) return KnownResult.Error("scalar '$key' has continuation lines")
                parseQuoted(rest.substring(1)).fold(
                    onSuccess = { KnownResult.Value(it) },
                    onFailure = { KnownResult.Error("invalid quoted value for '$key'") },
                )
            }
            ValueKind.INTEGER -> {
                if (continuation.isNotEmpty()) return KnownResult.Error("integer '$key' has continuation lines")
                val text = rest.substring(1)
                if (!integerPattern.matches(text)) return KnownResult.Error("invalid integer for '$key'")
                KnownResult.Value(text.toLong())
            }
            ValueKind.LIST -> {
                if (rest.isNotEmpty() && rest.substring(1) == "[]") {
                    if (continuation.isNotEmpty()) {
                        KnownResult.Error("empty list '$key' has items")
                    } else {
                        KnownResult.Value(emptyList<String>())
                    }
                } else {
                    if (continuation.isEmpty()) return KnownResult.Error("list '$key' has no items")
                    val items = mutableListOf<String>()
                    for (line in continuation) {
                        if (!line.startsWith("  - \"") || !line.endsWith('"')) return KnownResult.Error("malformed item for '$key'")
                        val value = parseQuoted(line.substring(4)).getOrElse { return KnownResult.Error("invalid item for '$key'") }
                        items += value
                    }
                    KnownResult.Value(items)
                }
            }
        }
    }

    private fun parseQuoted(text: String): Result<String> {
        if (text.length < 2 || text.first() != '"') return Result.failure(IllegalArgumentException("not quoted"))
        val out = StringBuilder()
        var index = 1
        while (index < text.length) {
            val char = text[index]
            if (char == '"') {
                return if (index == text.lastIndex) {
                    Result.success(out.toString())
                } else {
                    Result.failure(IllegalArgumentException("text follows closing quote"))
                }
            }
            if (char == '\\') {
                if (index + 1 >= text.length) return Result.failure(IllegalArgumentException("unfinished escape"))
                when (val escaped = text[index + 1]) {
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (index + 6 >= text.length) return Result.failure(IllegalArgumentException("short unicode escape"))
                        val hex = text.substring(index + 2, index + 6)
                        if (hex.length != 4 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                            return Result.failure(IllegalArgumentException("invalid unicode escape"))
                        }
                        val codePoint = hex.toInt(16)
                        if (codePoint in 0xD800..0xDFFF) return Result.failure(IllegalArgumentException("surrogate escape"))
                        out.append(codePoint.toChar())
                        index += 4
                    }
                    else -> return Result.failure(IllegalArgumentException("unsupported escape '$escaped'"))
                }
                index += 2
                continue
            }
            val codePoint = text.codePointAt(index)
            if (codePoint in rawEscapeSet) return Result.failure(IllegalArgumentException("raw YAML line break character"))
            out.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return Result.failure(IllegalArgumentException("missing closing quote"))
    }

    private val escapedCodePoints = (0x0000..0x001F).toSet() + (0x007F..0x009F).toSet() + rawEscapeSet

    private fun appendQuote(out: StringBuilder, prefix: String, anchor: Anchor.Quote) {
        val capture = anchor.capture
        out.append(prefix).append("_quote: ").append(quote(capture.quote)).append('\n')
        out.append(prefix).append("_prefix: ").append(quote(capture.prefix)).append('\n')
        out.append(prefix).append("_suffix: ").append(quote(capture.suffix)).append('\n')
        out.append(prefix).append("_byte_start: ").append(capture.byteStart).append('\n')
        out.append(prefix).append("_byte_end: ").append(capture.byteEnd).append('\n')
        out.append(prefix).append("_body_start: ").append(capture.bodyStart).append('\n')
        out.append(prefix).append("_line: ").append(capture.line).append('\n')
        out.append(prefix).append("_selection: ").append(quote(capture.selection.wire)).append('\n')
        out.append(prefix).append("_heading_path:")
        if (capture.headingPath.entries.isEmpty()) {
            out.append(" []\n")
        } else {
            out.append('\n')
            capture.headingPath.entries.forEach { entry ->
                out.append("  - ").append(quote("${entry.level} ${entry.text}")).append('\n')
            }
        }
    }

    private fun quote(values: Map<String, Any>, prefix: String): Anchor.Quote? {
        val hash = values["${prefix}_content_hash"] as? String ?: return null
        val commitKey = "${prefix}_commit"
        if (commitKey in values && optionalCommit(values, commitKey) == null) return null
        val commit = optionalCommit(values, commitKey)
        val quote = values["${prefix}_quote"] as? String ?: return null
        val before = values["${prefix}_prefix"] as? String ?: return null
        val after = values["${prefix}_suffix"] as? String ?: return null
        val start = values["${prefix}_byte_start"] as? Long ?: return null
        val end = values["${prefix}_byte_end"] as? Long ?: return null
        val bodyStart = values["${prefix}_body_start"] as? Long ?: return null
        val line = values["${prefix}_line"] as? Long ?: return null
        val selection = AnchorSelection.entries.firstOrNull { it.wire == values["${prefix}_selection"] } ?: return null
        val rawHeadings = values["${prefix}_heading_path"] as? List<*> ?: return null
        val headings = rawHeadings.map { raw ->
            val match = headingPattern.matchEntire(raw as? String ?: return null) ?: return null
            HeadingPath.Entry(match.groupValues[1].toInt(), match.groupValues[2])
        }
        if (!sha256Pattern.matches(hash)) return null
        return runCatching {
            Anchor.Quote(
                hash,
                commit,
                QuoteCapture(quote, before, after, start, end, bodyStart, line, selection, HeadingPath(headings)),
            )
        }.getOrNull()
    }

    private fun buildReattachment(values: Map<String, Any>): Reattachment? {
        val present = reattachmentKeys().any(values::containsKey)
        if (!present) return null
        if (reattachmentRequired.any { it !in values }) return null
        val actor = actor(values, "reattached_by") ?: return null
        val at = instant(values["reattached_at"] as? String ?: return null) ?: return null
        val anchor = quote(values, "reattach") ?: return null
        return Reattachment(actor, at, anchor)
    }

    private fun reattachmentKeys(): List<String> = reattachmentRequired + "reattach_commit"

    private fun optionalActorGroup(
        values: Map<String, Any>,
        keys: List<String>,
        actorPrefix: String,
        timeKey: String,
    ): StatusChange? {
        if (keys.none(values::containsKey)) return null
        if (keys.any { it !in values }) return null
        val actor = actor(values, actorPrefix) ?: return null
        val at = instant(values[timeKey] as? String ?: return null) ?: return null
        return StatusChange(actor, at)
    }

    private fun actor(values: Map<String, Any>, prefix: String): Actor? {
        val issuer = values["${prefix}_issuer"] as? String ?: return null
        val id = values["${prefix}_id"] as? String ?: return null
        val label = values["${prefix}_label"] as? String ?: return null
        if (issuer.isBlank() || id.isBlank()) return null
        return Actor(SubjectKey(issuer, id), label)
    }

    private fun author(values: Map<String, Any>, prefix: String): Author? {
        val actor = actor(values, prefix) ?: return null
        val kind = AuthorKind.entries.firstOrNull { it.wire == values["${prefix}_kind"] } ?: return null
        return Author(actor, kind)
    }

    private fun instant(value: String): Instant? {
        if (!timePattern.matches(value)) return null
        return runCatching {
            val epochMillis = java.time.Instant.parse(value).toEpochMilli()
            val result = Instant.fromEpochMilliseconds(epochMillis)
            if (formatTime(result) == value) result else null
        }.getOrNull()
    }

    private fun formatTime(value: Instant): String = timeFormatter.format(java.time.Instant.ofEpochMilli(value.toEpochMilliseconds()))

    private fun optionalCommit(values: Map<String, Any>, key: String): String? {
        if (key !in values) return null
        val text = values[key] as? String ?: return null
        return text.takeIf(commentHashPattern::matches)
    }

    private fun appendAuthor(out: StringBuilder, prefix: String, author: Author) {
        appendActor(out, prefix, author.actor)
        out.append(prefix).append("_kind: ").append(quote(author.kind.wire)).append('\n')
    }

    private fun appendActor(out: StringBuilder, prefix: String, actor: Actor) {
        out.append(prefix).append("_issuer: ").append(quote(actor.subject.issuer)).append('\n')
        out.append(prefix).append("_id: ").append(quote(actor.subject.id)).append('\n')
        out.append(prefix).append("_label: ").append(quote(actor.label)).append('\n')
    }

    private fun appendLeading(out: StringBuilder, leading: String?) {
        if (leading == null || leading.isEmpty()) return
        val normalized = normalizeExtras(leading)
        val lines = splitLines(normalized.removeSuffix("\n"))
        require(lines.all { it.isEmpty() || it.startsWith(' ') })
        out.append(normalized)
        if (!normalized.endsWith('\n')) out.append('\n')
    }

    private fun appendTrailing(out: StringBuilder, trailing: String, documentKeys: Set<String>) {
        if (trailing.isEmpty()) return
        val normalized = normalizeExtras(trailing)
        val lines = splitLines(normalized.removeSuffix("\n"))
        lines.filter { it.isNotEmpty() && !it.startsWith(' ') }.forEach { line ->
            val key = headPattern.find(line)?.groupValues?.get(1)
            require(key == null || key !in documentKeys)
        }
        out.append(normalized)
        if (!normalized.endsWith('\n')) out.append('\n')
    }

    private fun normalizeExtras(value: String): String {
        strictUtf8(value)
        val normalized = value.replace("\r\n", "\n")
        require('\r' !in normalized)
        return normalized
    }

    private fun quote(value: String): String {
        strictUtf8(value)
        val out = StringBuilder("\"")
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            when (codePoint) {
                '\\'.code -> out.append("\\\\")
                '"'.code -> out.append("\\\"")
                '\n'.code -> out.append("\\n")
                '\r'.code -> out.append("\\r")
                '\t'.code -> out.append("\\t")
                else -> if (codePoint in escapedCodePoints) {
                    out.append("\\u").append(codePoint.toString(16).uppercase().padStart(4, '0'))
                } else {
                    out.appendCodePoint(codePoint)
                }
            }
            index += Character.charCount(codePoint)
        }
        return out.append('"').toString()
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                lines += text.substring(start, index)
                start = index + 1
            }
        }
        lines += text.substring(start)
        return lines
    }

    private fun validEols(bytes: ByteArray, start: Int, end: Int): Boolean {
        var index = start
        while (index < end) {
            if (bytes[index] == '\r'.code.toByte()) {
                if (index + 1 >= end || bytes[index + 1] != '\n'.code.toByte()) return false
                index++
            }
            index++
        }
        return true
    }

    private fun strictDecodeOrNull(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private fun firstMissing(required: List<String>, values: Map<String, Any>): String? = required.firstOrNull { it !in values }

    private fun string(values: Map<String, Any>, key: String): String = values.getValue(key) as String

    private fun missing(key: String): Decoded.Unreadable =
        Decoded.Unreadable(UnreadableReason.MISSING_KEY, "missing key '$key'")

    private fun bad(detail: String): Decoded.Unreadable = Decoded.Unreadable(UnreadableReason.BAD_VALUE, detail)

    private fun unreadable(error: DecodeError): Decoded.Unreadable = Decoded.Unreadable(error.reason, error.detail)

    private sealed interface ParseResult {
        data class Document(val values: Map<String, Any>, val extras: FrontmatterExtras, val body: String) : ParseResult
        data class Error(val error: DecodeError) : ParseResult
    }

    private sealed interface LineResult {
        data class Values(val values: Map<String, Any>, val extras: FrontmatterExtras) : LineResult
        data class Error(val error: DecodeError) : LineResult
    }

    private sealed interface KnownResult {
        data class Value(val value: Any) : KnownResult
        data object WrongKind : KnownResult
        data class Error(val detail: String) : KnownResult
    }

    private enum class ValueKind { STRING, INTEGER, LIST }

    private data class DecodeError(val reason: UnreadableReason, val detail: String)

    private data class DiscussionFields(
        val id: DiscussionId,
        val page: PageRef,
        val status: DiscussionStatus,
        val created: Instant,
        val startedBy: Author,
        val statusChange: StatusChange?,
    )

    private data class DiscussionAnchors(val anchor: Anchor, val reattachment: Reattachment?)
}

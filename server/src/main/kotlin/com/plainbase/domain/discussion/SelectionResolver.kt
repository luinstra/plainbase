package com.plainbase.domain.discussion

import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.Heading
import com.plainbase.domain.render.SourceBlock
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val UTF8_VALIDATION_BUFFER_SIZE = 1_024

/** A SPA block selection or agent quote to resolve against page bytes. */
sealed interface SelectionRequest {
    val selectedText: String

    data class Spa(val blockStart: Long, val blockEnd: Long, override val selectedText: String) : SelectionRequest
    data class Agent(override val selectedText: String) : SelectionRequest
}

/** Stable reasons a selection request cannot be resolved. */
enum class SelectionRefusal(val code: String) {
    INVALID_ANCHOR("invalid_anchor"),
    ANCHOR_TOO_LARGE("anchor_too_large"),
    ANCHOR_NOT_FOUND("anchor_not_found"),
    ANCHOR_NOT_UNIQUE("anchor_not_unique"),
}

/** A captured quote or a refusal to resolve the requested selection. */
sealed interface SelectionResult {
    data class Resolved(val capture: QuoteCapture) : SelectionResult
    data class Refused(val refusal: SelectionRefusal) : SelectionResult
}

object SelectionResolver {
    /**
     * Resolves [request] against the page's raw UTF-8 [raw] bytes. [blocks] and [headings] must come from
     * rendering these same bytes; each block starts at or after the detected body start and its bounds lie on
     * UTF-8 character boundaries. Stale block ends past EOF are refused. Other renderer invariants are a caller
     * precondition. When there are no blocks, a strict UTF-8 body can still resolve Agent quotes; SPA requests
     * and non-strict bodies return [SelectionRefusal.INVALID_ANCHOR].
     */
    fun resolve(
        raw: ByteArray,
        blocks: List<SourceBlock>,
        headings: List<Heading>,
        request: SelectionRequest,
    ): SelectionResult {
        val bodyStart = FrontmatterBlock.detect(raw).bodyStart
        if (blocks.isEmpty() && (!bodyIsStrictUtf8(raw, bodyStart) || request is SelectionRequest.Spa)) {
            return refused(SelectionRefusal.INVALID_ANCHOR)
        }

        if (request is SelectionRequest.Agent && request.selectedText.isEmpty()) {
            return refused(SelectionRefusal.INVALID_ANCHOR)
        }
        val selected = try {
            request.selectedText.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return refused(SelectionRefusal.INVALID_ANCHOR)
        }

        return when (request) {
            is SelectionRequest.Agent -> resolveAgent(raw, bodyStart, headings, selected)
            is SelectionRequest.Spa -> resolveSpa(raw, bodyStart, blocks, headings, request, selected)
        }
    }

    private fun resolveAgent(
        raw: ByteArray,
        bodyStart: Int,
        headings: List<Heading>,
        selected: ByteArray,
    ): SelectionResult {
        if (selected.size > AnchorLimits.MAX_QUOTE_BYTES) return refused(SelectionRefusal.ANCHOR_TOO_LARGE)
        val occurrences = KmpMatcher.scan(raw, bodyStart, raw.size, selected)
        if (occurrences.count == 0) return refused(SelectionRefusal.ANCHOR_NOT_FOUND)
        if (occurrences.count > 1) return refused(SelectionRefusal.ANCHOR_NOT_UNIQUE)
        val start = occurrences.first.single()
        val end = start + selected.size
        return SelectionResult.Resolved(QuoteCapture.at(raw, start, end, headings, AnchorSelection.NARROWED))
    }

    private fun resolveSpa(
        raw: ByteArray,
        bodyStart: Int,
        blocks: List<SourceBlock>,
        headings: List<Heading>,
        request: SelectionRequest.Spa,
        selected: ByteArray,
    ): SelectionResult {
        val startBlock = blocks.firstOrNull { it.start.toLong() == request.blockStart }
        val endBlock = blocks.firstOrNull { it.end.toLong() == request.blockEnd }
        if (startBlock == null || endBlock == null || startBlock.start >= endBlock.end || endBlock.end > raw.size) {
            return refused(SelectionRefusal.INVALID_ANCHOR)
        }
        if (startBlock.start < bodyStart) {
            return refused(SelectionRefusal.INVALID_ANCHOR)
        }

        val start: Int
        val end: Int
        val selection: AnchorSelection
        if (selected.isEmpty()) {
            start = startBlock.start
            end = endBlock.end
            selection = AnchorSelection.SNAPPED
        } else {
            val occurrences = KmpMatcher.scan(raw, startBlock.start, endBlock.end, selected)
            if (occurrences.count == 1) {
                start = occurrences.first.single()
                end = start + selected.size
                selection = AnchorSelection.NARROWED
            } else {
                start = startBlock.start
                end = endBlock.end
                selection = AnchorSelection.SNAPPED
            }
        }
        if (end - start > AnchorLimits.MAX_QUOTE_BYTES) return refused(SelectionRefusal.ANCHOR_TOO_LARGE)
        return SelectionResult.Resolved(QuoteCapture.at(raw, start, end, headings, selection))
    }

    private fun bodyIsStrictUtf8(raw: ByteArray, bodyStart: Int): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(raw, bodyStart, raw.size - bodyStart)
        val output = CharBuffer.allocate(UTF8_VALIDATION_BUFFER_SIZE)
        return try {
            while (true) {
                val result = decoder.decode(input, output, true)
                if (result.isError) result.throwException()
                if (result.isUnderflow) break
                output.clear()
            }
            while (true) {
                val result = decoder.flush(output)
                if (result.isError) result.throwException()
                if (result.isUnderflow) break
                output.clear()
            }
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun refused(refusal: SelectionRefusal): SelectionResult.Refused = SelectionResult.Refused(refusal)
}

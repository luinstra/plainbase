package com.plainbase.frameworks.ktor.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * W3b — the NON-FROZEN response shapes for the two W1-only routes (`POST /api/v1/preview` and
 * `POST /api/v1/pages/{id}/assets`). Deliberately kept OUT of the frozen `RestDtos.kt`/`WriteDtos.kt`
 * so neither frozen file grows a field: preview is PRIVATE / non-contractual (the markup is the
 * renderer's evolving output; only the wire keys are stable-by-convention) and the asset shapes are
 * non-frozen PB-* surfaces governed like `RescanResponse`. NEITHER registers a `ForeverApiGoldenSuite`
 * row. Both encode through the scoped [RestJson].
 */

/**
 * `POST /api/v1/preview` 200 body: the sanitized body HTML plus the document-order heading list (the
 * editor TOC). [html] is `RenderedPage.html` — already §C3-sanitized and PB-LINK-1 link-rewritten —
 * and reuses the existing [HeadingDto]. NON-FROZEN.
 */
@Serializable
data class PreviewResponse(val html: String, val headings: List<HeadingDto>)

/**
 * `POST /api/v1/pages/{id}/assets` 201 body: the served `/assets/…` URL ([PageIndex.assetUrl] form,
 * computed after the post-write rebuild so the asset is in the snapshot), the content-relative path,
 * and the frozen `CitationFactory.contentHash`. NON-FROZEN.
 */
@Serializable
data class AssetUploadResponse(
    val url: String,
    val path: String,
    @SerialName("content_hash") val contentHash: String,
)

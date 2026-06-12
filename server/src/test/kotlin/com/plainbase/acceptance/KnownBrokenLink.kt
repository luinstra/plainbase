package com.plainbase.acceptance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One pinned entry of `golden/known-broken-links.json` — the manifest that makes the Phase-1 link
 * gate exact: `notes/broken-links.md` deliberately carries 2 broken links + 1 broken anchor (Phase 5
 * `validate_links` test material), and the manifest pins each by page, link text, raw target, and
 * error class. The gate compares the checker's WHOLE report against this list, so it doubles as the
 * broken-link golden test: an extra broken link anywhere in the fixtures, a missing expected entry,
 * or any field drift fails the build.
 *
 * The native acceptance test reads the same resource inside the native image (R6); keep the manifest
 * shape in sync with `Phase1AcceptanceNativeTest`'s loader.
 */
data class KnownBrokenLink(
    val page: String,
    val text: String,
    val target: String,
    val errorClass: String,
) {
    companion object {
        const val RESOURCE = "/golden/known-broken-links.json"

        /** Loads the manifest from the test classpath, in document order. */
        fun manifest(): List<KnownBrokenLink> {
            val json = KnownBrokenLink::class.java.getResource(RESOURCE)?.readText()
                ?: error("manifest resource not found on the test classpath: $RESOURCE")
            return Json.parseToJsonElement(json).jsonObject.getValue("broken").jsonArray.map { entry ->
                val fields = entry.jsonObject
                KnownBrokenLink(
                    page = fields.getValue("page").jsonPrimitive.content,
                    text = fields.getValue("text").jsonPrimitive.content,
                    target = fields.getValue("target").jsonPrimitive.content,
                    errorClass = fields.getValue("class").jsonPrimitive.content,
                )
            }
        }
    }
}

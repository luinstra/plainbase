package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.PageId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * PB-REST-1 golden support (§A4 golden policy):
 *  - goldens are JSON snapshots under `/golden/rest/`, authored with stable UUID literals from the
 *    start (seeded into the id_map);
 *  - comparison is PARSED-JSON-TREE equality (key set + types + values; `kotlinx` JsonObject
 *    equality is map equality) — formatting is free, shape drift fails;
 *  - `content_hash` values are NEVER committed: goldens carry the `{{content_hash}}` placeholder
 *    and the test substitutes a hash **independently recomputed from the fixture bytes on disk**
 *    at test time, so a stale committed value can neither pass nor mask drift.
 */
object RestGolden {

    /**
     * Loads a golden JSON resource with `{{placeholder}}` substitutions applied.
     *
     * Substitution happens in the PARSED JSON TREE, not in the raw text: the golden is parsed first,
     * then within each string [JsonPrimitive]'s CONTENT every `{{key}}` occurrence is replaced and the
     * primitive re-created via `JsonPrimitive(value)`. Because the replace runs on the already-decoded
     * string and `kotlinx` re-encodes the result, an arbitrary substituted value (a `current_content`
     * carrying a `"` or `\`, a literal newline, …) is escaped correctly — never spliced as raw text
     * that could make the JSON invalid (the old pre-parse `text.replace` failure mode). Substituting
     * into the string content (not only whole-value primitives) keeps embedded placeholders working
     * too — e.g. the PB-REST-1 citation `uri` `plainbase://<id>@{{content_hash}}`.
     */
    fun load(name: String, substitutions: Map<String, String> = emptyMap()): JsonElement {
        val resource = checkNotNull(javaClass.getResourceAsStream("/golden/rest/$name")) { "missing golden resource: $name" }
        val text = resource.use { it.readBytes().toString(Charsets.UTF_8) }
        return substitute(Json.parseToJsonElement(text), substitutions)
    }

    /** Recursively replaces `{{key}}` occurrences inside string primitives (re-encoded, so any value is safe). */
    private fun substitute(element: JsonElement, substitutions: Map<String, String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> substitute(value, substitutions) })
        is JsonArray -> JsonArray(element.map { substitute(it, substitutions) })
        is JsonPrimitive -> if (element.isString) substitutePrimitive(element, substitutions) else element
        else -> element
    }

    private fun substitutePrimitive(primitive: JsonPrimitive, substitutions: Map<String, String>): JsonPrimitive {
        var content = primitive.content
        if ("{{" !in content) return primitive
        for ((key, value) in substitutions) content = content.replace("{{$key}}", value)
        return JsonPrimitive(content)
    }

    /** The frozen content-hash form, recomputed from the on-disk [file] — never a committed value. */
    fun contentHashOf(file: Path): String =
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))

    /**
     * Normalizes a tree-response JSON for the SHAPE-scoped comparison (M4): children arrays are
     * recursively sorted by a neutral `(type, path)` key — child ORDERING is deliberately out of
     * this golden's reach (the separate ordering test owns it) — and page-node ids minted at index
     * time are replaced by `{{uuid}}` AFTER asserting they are canonical-shape, while ids in
     * [seededIds] stay literal (those are pinned by the golden).
     */
    fun normalizeTree(element: JsonElement, seededIds: Set<String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                when (key) {
                    "children" -> JsonArray(
                        (value as JsonArray)
                            .map { normalizeTree(it, seededIds) }
                            .sortedBy { node -> sortKey(node as JsonObject) },
                    )
                    "id" -> normalizeId(value, seededIds)
                    // Descend through every other object value too — the response wraps the
                    // first folder under "root", which must be normalized like any node.
                    else -> if (value is JsonObject) normalizeTree(value, seededIds) else value
                }
            },
        )
        else -> element
    }

    private fun normalizeId(value: JsonElement, seededIds: Set<String>): JsonElement {
        val id = value.jsonPrimitive.content
        if (id == "{{uuid}}") return value // an already-normalized golden value
        check(PageId.of(id) != null) { "tree page id is not canonical-shape: '$id'" }
        return if (id in seededIds) value else JsonPrimitive("{{uuid}}")
    }

    private fun sortKey(node: JsonObject): String {
        val type = node.getValue("type").jsonPrimitive.content
        val path = node.getValue("path").jsonPrimitive.content
        return "$type:$path"
    }
}

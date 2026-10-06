package com.plainbase.frameworks.ktor.routes

/*
 * The ONE shared SERVER-COMPOSED frontmatter+body composer: the single seam the direct `POST /api/v1/pages`
 * path, the agent create-degrade (via that same route, before the degrade), and the byte-identical differential test
 * reference, so they can never drift. The EXPLICIT-agent-propose path does NOT use this — it patches the agent's
 * whole-doc blob with the surgical `FrontmatterPatcher` instead. create-apply does NOT call this either: it
 * writes the stored bytes VERBATIM (it is a downstream consumer of the bytes this composed at propose/degrade time).
 *
 * Pure stdlib, frameworks-side. Legacy output is byte-frozen; typed callers opt into the additional type line.
 */

/**
 * Composes minted [id], optional [type], [title], optional [slug], then the verbatim [body].
 * Typed inputs pass the shared Unicode validator before quote-always emission; null type preserves
 * the exact legacy output and its existing Unicode limitations.
 */
internal fun composeDocument(id: String, title: String, slug: String?, body: String?, type: String? = null): ByteArray {
    require(type == null || invalidTypedCreateField(title, slug, type, body) == null) { "Invalid typed document input" }
    return buildString {
        append("---\n")
        append("id: ").append(id).append('\n')
        if (type != null) append("type: ").append(yamlDoubleQuoted(type)).append('\n')
        append("title: ").append(yamlDoubleQuoted(title)).append('\n')
        if (slug != null) append("slug: ").append(yamlDoubleQuoted(slug)).append('\n')
        append("---\n\n")
        append(body.orEmpty())
    }.toByteArray(Charsets.UTF_8)
}

/**
 * A YAML double-quoted scalar: `"` + the value with `\`, `"`, and control chars escaped + `"`. `\n`/`\r`/`\t` use
 * YAML's C-style escapes (the [FrontmatterReader] inverse decodes them, so they round-trip EXACTLY); any other C0
 * control char becomes `\uXXXX` so the scalar stays on one VALID flow line. Without this an unescaped newline would
 * flow-fold and break the round-trip — callers reject control chars upstream, so this is defense-in-depth on the seam.
 */
private fun yamlDoubleQuoted(value: String): String =
    buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(UNICODE_RADIX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

private const val UNICODE_RADIX = 16
private const val UNICODE_ESCAPE_WIDTH = 4

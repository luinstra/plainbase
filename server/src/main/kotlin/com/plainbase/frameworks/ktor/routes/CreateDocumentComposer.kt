package com.plainbase.frameworks.ktor.routes

/*
 * The ONE shared SERVER-COMPOSED frontmatter+body composer (C1, WI-2): the single seam the direct `POST /api/v1/pages`
 * path, the agent create-degrade (via that same route, before the degrade), and the byte-identical differential test
 * reference, so they can never drift. The EXPLICIT-agent-propose path does NOT use this — it patches the agent's
 * whole-doc blob with the surgical `FrontmatterPatcher` instead (SD-1). create-apply does NOT call this either: it
 * writes the stored bytes VERBATIM (it is a downstream consumer of the bytes this composed at propose/degrade time).
 *
 * Pure stdlib (no framework/domain imports), so it stays frameworks-side where its only callers live (`DomainPurityTest`
 * unaffected). The OUTPUT is byte-frozen — the W2 golden + the differential test depend on the exact bytes.
 */

/**
 * Composes the minimal frontmatter block (the minted [id], [title], optional [slug]) + [body], written
 * VERBATIM. The `id:` line is plain ASCII (the patcher's shape); `title`/`slug` are emitted as
 * YAML double-quoted scalars (quote-always) with `\`, `"`, and control chars escaped, so a value bearing
 * `:`/`[`/`>`/`@`/`|`/`&`/`*`/`!`/quotes/backslashes/unicode/newlines composes to VALID YAML the reader
 * reads back EXACTLY (the inverse of ADR-0001: the writer must never PRODUCE ambiguous YAML).
 */
internal fun composeDocument(id: String, title: String, slug: String?, body: String?): ByteArray =
    buildString {
        append("---\n")
        append("id: ").append(id).append('\n')
        append("title: ").append(yamlDoubleQuoted(title)).append('\n')
        if (slug != null) append("slug: ").append(yamlDoubleQuoted(slug)).append('\n')
        append("---\n\n")
        append(body.orEmpty())
    }.toByteArray(Charsets.UTF_8)

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
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

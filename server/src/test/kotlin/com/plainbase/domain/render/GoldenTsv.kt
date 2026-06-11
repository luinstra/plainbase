package com.plainbase.domain.render

/**
 * Minimal TSV loader for the golden corpora. Skips blank lines and `#`-comment lines, splits the rest
 * on tabs, and unescapes two sequences inside FIELDS:
 *
 *  - `\t` → a real TAB (so a heading source containing real tabs — PB-SLUG-1 row 17 — can be carried
 *    in a tab-separated file);
 *  - `\u{XXXX}` → the code point `U+XXXX` (1–6 hex digits). This lets NFD sequences (e.g. row 19's
 *    `re\u{0301}union`) be expressed in **visible ASCII** in the corpus file, immune to an editor
 *    silently NFC-normalizing invisible combining-mark bytes on save (the byte-fragility Fable flagged).
 *
 * Pure test infrastructure; zero flexmark imports.
 */
object GoldenTsv {

    private val UNICODE_ESCAPE = Regex("""\\u\{([0-9A-Fa-f]{1,6})}""")

    /** Loads [resourcePath] from the test classpath, returning the data rows as lists of fields. */
    fun load(resourcePath: String): List<List<String>> {
        val text = GoldenTsv::class.java.getResource(resourcePath)?.readText()
            ?: error("golden resource not found on the test classpath: $resourcePath")
        return text.lineSequence()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { line -> line.split('\t').map { unescape(it) } }
            .toList()
    }

    /** Unescapes `\u{XXXX}` code-point escapes, then the literal two-char sequence `\t` → real TAB. */
    private fun unescape(field: String): String {
        val withCodepoints = UNICODE_ESCAPE.replace(field) { m ->
            String(Character.toChars(m.groupValues[1].toInt(16)))
        }
        return withCodepoints.replace("\\t", "\t")
    }
}

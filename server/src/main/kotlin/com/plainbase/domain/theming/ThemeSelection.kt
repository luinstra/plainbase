package com.plainbase.domain.theming

enum class ThemeMode { LIGHT, DARK }

/**
 * A direct CSS filename. The `.css` suffix prevents a selection from naming `plainbase.conf`, `plainbase.db`,
 * `search.db`, `discussions.db` or `roots.conf`.
 */
@JvmInline
value class ThemeLeaf private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val LEAF = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.css")

        fun of(raw: String): ThemeLeaf? = raw.takeIf { LEAF.matches(it) && !namesOverride(it) }?.let(::ThemeLeaf)

        fun namesOverride(raw: String): Boolean = raw.equals("custom.css", ignoreCase = true)
    }
}

/** A null leaf uses the bundled definition for that mode. */
data class ThemeSelection(val light: ThemeLeaf?, val dark: ThemeLeaf?) {
    fun leaf(mode: ThemeMode): ThemeLeaf? = when (mode) {
        ThemeMode.LIGHT -> light
        ThemeMode.DARK -> dark
    }

    companion object {
        val BUNDLED: ThemeSelection = ThemeSelection(null, null)
    }
}

class ThemeBuiltins(val light: ByteArray, val dark: ByteArray) {
    fun definition(mode: ThemeMode): ByteArray = when (mode) {
        ThemeMode.LIGHT -> light
        ThemeMode.DARK -> dark
    }
}

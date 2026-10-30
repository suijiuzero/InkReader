package com.inkreader.parse

/** OOXML style IDs are opaque identifiers, including values such as "1" or "2". */
internal object DocxStyleLevels {
    data class Style(val name: String = "", val outline: Int? = null, val basedOn: String? = null)
    private val english = Regex("(?i)^heading\\s*([1-6])$")
    private val chinese = Regex("^标题\\s*([1-6])$")

    fun namedLevel(name: String): Int? {
        val value = name.trim()
        (english.matchEntire(value) ?: chinese.matchEntire(value))?.let { return it.groupValues[1].toInt() - 1 }
        if (value.equals("title", true)) return 0
        if (value.equals("subtitle", true)) return 1
        return null
    }

    fun resolve(styles: Map<String, Style>): Map<String, Int> = styles.keys.associateWith { id ->
        var current: String? = id
        val visited = HashSet<String>()
        var level = -1
        while (current != null && visited.size < 64 && visited.add(current)) {
            val spec = styles[current] ?: break
            if (spec.outline != null) {
                level = spec.outline.takeIf { it in 0..5 } ?: -1
                break
            }
            val named = namedLevel(spec.name) ?: namedLevel(current)
            if (named != null) { level = named; break }
            current = spec.basedOn
        }
        level
    }

    fun paragraph(style: String, outline: Int, styles: Map<String, Int>): Int {
        // Direct paragraph formatting overrides the style, including body-text level 9.
        if (outline >= 0) return outline.takeIf { it in 0..5 } ?: -1
        return styles[style.trim()] ?: namedLevel(style) ?: -1
    }
}

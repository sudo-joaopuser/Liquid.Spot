package app.liquidspot.xposed.a11y

class TextPattern private constructor(private val regex: Regex) {
    fun matches(label: CharSequence?): Boolean = label != null && regex.matches(label.trim())

    override fun toString() = regex.pattern

    companion object {
        private val PLACEHOLDER = Regex("%(\\d+\\$)?[sd]")

        fun fromFormat(format: String): TextPattern {
            val parts = PLACEHOLDER.split(format.trim()).map { Regex.escape(it) }
            return TextPattern(Regex(parts.joinToString("(.*)"), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)))
        }
    }
}

class TextMatcher(val patterns: List<TextPattern>) {
    fun matches(label: CharSequence?): Boolean = patterns.any { it.matches(label) }
    val isEmpty: Boolean get() = patterns.isEmpty()

    companion object {
        fun formats(texts: Collection<String>) = TextMatcher(texts.filter { it.isNotBlank() }.map(TextPattern::fromFormat))
    }
}

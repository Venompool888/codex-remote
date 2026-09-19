package app.codexremote.android

/** A deliberately small, bounded regular-expression subset for form validation. */
internal object SafeTextPattern {
    fun requireSupported(pattern: String) {
        require(pattern.length in 1..256) { "Unsupported text pattern; complete it on the host" }
        var index = 0
        if (pattern.startsWith("^")) index++
        val end = if (pattern.endsWith("$") && !pattern.endsWith("\\$")) pattern.lastIndex else pattern.length
        var atoms = 0
        while (index < end) {
            when (pattern[index]) {
                '\\' -> {
                    require(index + 1 < end && pattern[index + 1] !in "123456789bBAGZz") { unsupported() }
                    index += 2
                }
                '[' -> {
                    val close = pattern.indexOf(']', index + 1)
                    require(close > index + 1 && close < end && pattern.substring(index + 1, close).none { it == '[' }) { unsupported() }
                    index = close + 1
                }
                in "().|*+?" -> throw IllegalArgumentException(unsupported())
                else -> index++
            }
            atoms++
            if (index < end && pattern[index] == '{') {
                val close = pattern.indexOf('}', index + 1)
                require(close > index && close < end) { unsupported() }
                val bounds = pattern.substring(index + 1, close).split(',')
                require(bounds.size in 1..2 && bounds.all { it.isNotBlank() && it.all(Char::isDigit) }) { unsupported() }
                val lower = bounds.first().toIntOrNull() ?: throw IllegalArgumentException(unsupported())
                val upper = bounds.last().toIntOrNull() ?: throw IllegalArgumentException(unsupported())
                require(lower <= upper && upper <= 1_000) { unsupported() }
                index = close + 1
            }
        }
        require(atoms in 1..128) { unsupported() }
    }

    fun matches(pattern: String, value: String): Boolean {
        requireSupported(pattern)
        return Regex(pattern).containsMatchIn(value)
    }

    private fun unsupported() = "Unsupported text pattern; complete it on the host"
}

package app.codexremote.android

/** A bounded lexical pass: offsets refer to the unchanged source, including incomplete streams. */
internal object CodeHighlight {
    enum class Kind { STRING, COMMENT, KEYWORD, NUMBER, CALL, IDENTIFIER, OPERATOR }
    data class Token(val start: Int, val end: Int, val kind: Kind)
    fun tokens(source: String, language: String): List<Token> {
        val lang = language.trim().lowercase().substringBefore(' ')
        val python = lang in setOf("python", "py")
        val json = lang in setOf("json", "jsonc")
        val shell = lang in setOf("sh", "bash", "shell", "zsh")
        val cStyle = lang in setOf("javascript", "js", "typescript", "ts", "jsx", "tsx", "kotlin", "kt", "java", "c", "cpp", "c++", "c#", "csharp", "go", "rust", "swift")
        if (!(python || json || shell || cStyle) || source.length > 100_000) return emptyList()
        val keywords = when {
            python -> "def class if else elif return async await import from for while in is not and or try except finally with as yield pass None True False lambda raise global nonlocal del assert break continue"
            json -> "true false null"
            shell -> "if then else elif fi for while do done case esac function in export local"
            else -> "const let var val fun function class interface enum struct type if else when switch case return async await import export from for while in try catch finally throw throws new null true false public private protected static override suspend object package break continue void int double boolean string use fn impl pub mut match defer guard nil"
        }.split(' ').toSet()
        val result = mutableListOf<Token>()
        var at = 0
        while (at < source.length && result.size < 4096) {
            val start = at
            val ch = source[at]
            val next = source.getOrNull(at + 1)
            when {
                ((python || shell) && ch == '#') || ((cStyle || lang == "jsonc") && ch == '/' && next == '/') -> {
                    while (at < source.length && source[at] != '\n') at++
                    result += Token(start, at, Kind.COMMENT)
                }
                (cStyle || lang == "jsonc") && ch == '/' && next == '*' -> {
                    val end = source.indexOf("*/", at + 2)
                    at = if (end < 0) source.length else end + 2
                    result += Token(start, at, Kind.COMMENT)
                }
                ch == '"' || (!json && ch == '\'') || (cStyle && ch == '`') -> {
                    val triple = python && source.startsWith(ch.toString().repeat(3), at)
                    val delimiter = ch.toString().repeat(if (triple) 3 else 1)
                    at += delimiter.length
                    while (at < source.length) {
                        if (source[at] == '\\') { at = (at + 2).coerceAtMost(source.length); continue }
                        if (source.startsWith(delimiter, at)) { at += delimiter.length; break }
                        at++
                    }
                    result += Token(start, at, Kind.STRING)
                }
                ch.isDigit() && (at == 0 || !(source[at - 1].isLetterOrDigit() || source[at - 1] == '_')) -> {
                    at++
                    while (at < source.length && (source[at].isDigit() || source[at] in ".xXabcdefABCDEF_")) at++
                    result += Token(start, at, Kind.NUMBER)
                }
                ch.isLetter() || ch == '_' -> {
                    at++
                    while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_')) at++
                    val word = source.substring(start, at)
                    var after = at
                    while (after < source.length && source[after].isWhitespace()) after++
                    val kind = if (word in keywords) Kind.KEYWORD else if (!json && source.getOrNull(after) == '(') Kind.CALL
                        else if (python) Kind.IDENTIFIER else null
                    if (kind != null) result += Token(start, at, kind)
                }
                python && ch in "+-*/%@=<>!&|^~" -> {
                    at++
                    while (at < source.length && source[at] in "+-*/%@=<>!&|^~") at++
                    result += Token(start, at, Kind.OPERATOR)
                }
                else -> at++
            }
        }
        return result
    }
}

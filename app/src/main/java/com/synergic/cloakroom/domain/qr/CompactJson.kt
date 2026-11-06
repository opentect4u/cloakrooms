package com.synergic.cloakroom.domain.qr

/**
 * Minimal JSON for the receipt QR: one flat object whose values are strings or whole numbers,
 * written without spaces. Pure Kotlin, so it works in unit tests and on every Android version.
 */
object CompactJson {

    fun encode(values: Map<String, Any>): String = values.entries.joinToString(",", "{", "}") { (key, value) ->
        val v = when (value) {
            is Int, is Long -> value.toString()
            is String -> quote(value)
            else -> throw IllegalArgumentException("Only strings and whole numbers are supported")
        }
        "${quote(key)}:$v"
    }

    /** Parses a flat object; numbers come back as [Long], strings as [String]. Returns null if it is not valid. */
    fun decode(text: String): Map<String, Any>? = runCatching { Parser(text.trim()).parseObject() }.getOrNull()

    private fun quote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseObject(): Map<String, Any> {
            val result = LinkedHashMap<String, Any>()
            expect('{')
            skipSpaces()
            if (peek() == '}') { i++; return end(result) }
            while (true) {
                skipSpaces()
                val key = parseString()
                skipSpaces()
                expect(':')
                skipSpaces()
                result[key] = if (peek() == '"') parseString() else parseNumber()
                skipSpaces()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return end(result)
                    else -> error("Expected , or }")
                }
            }
        }

        private fun end(result: Map<String, Any>): Map<String, Any> {
            skipSpaces()
            check(i == s.length) { "Unexpected text after the object" }
            return result
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("Bad escape")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Long {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i).toLong()
        }

        private fun peek(): Char = s[i]
        private fun expect(c: Char) = check(s[i++] == c) { "Expected $c" }
        private fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}

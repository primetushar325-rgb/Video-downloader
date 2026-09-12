package com.vidx.core.json

/**
 * Minimal, dependency-free JSON parser (RFC 8259 subset used by VIDX).
 *
 * VIDX runs with ZERO third-party runtime dependencies; this parser covers objects,
 * arrays, strings (with escapes and \uXXXX), numbers, booleans and null. It is used by
 * every platform adapter and is fully unit-tested on the JVM (see core tests).
 *
 * Throws [JsonParseException] with a precise position on malformed input — callers
 * map that to user-facing "server returned invalid data" errors, never stack traces.
 */
class JsonParseException(message: String, val position: Int) : Exception("$message (at offset $position)")

sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val raw: String) : JsonValue() {
        fun double(): Double = raw.toDoubleOrNull() ?: 0.0
        fun long(): Long = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: 0L
        fun int(): Int = long().toInt()
        fun boolean(): Boolean = raw == "1" || raw.equals("true", ignoreCase = true)
    }
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue() {
        operator fun get(index: Int): JsonValue = items[index]
        fun size(): Int = items.size
        fun str(index: Int): String? = (items.getOrNull(index) as? Str)?.value
        fun obj(index: Int): Obj? = items.getOrNull(index) as? Obj
    }
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = fields[key]
        fun str(key: String): String? = (fields[key] as? Str)?.value
        fun strOr(key: String, default: String): String = str(key) ?: default
        fun int(key: String): Int? = (fields[key] as? Num)?.int()
        fun long(key: String): Long? = (fields[key] as? Num)?.long()
        fun double(key: String): Double? = (fields[key] as? Num)?.double()
        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): Arr? = fields[key] as? Arr

        fun path(vararg keys: String): JsonValue? {
            var cur: JsonValue? = this
            for (k in keys) cur = (cur as? Obj)?.get(k) ?: return null
            return cur
        }
        fun pathStr(vararg keys: String): String? = (path(*keys) as? Str)?.value
    }
}

object Json {
    fun parse(text: String): JsonValue {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWhitespace()
        if (!p.atEnd()) p.fail("unexpected trailing content")
        return v
    }

    /** Parse and coerce to object — the common case for API responses. */
    fun parseObject(text: String): JsonValue.Obj {
        val v = parse(text)
        return v as? JsonValue.Obj ?: throw JsonParseException("expected JSON object", 0)
    }

    private class Parser(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length
        fun fail(msg: String): Nothing = throw JsonParseException(msg, i)

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            return when (s[i]) {
                '{' -> parseObjectValue()
                '[' -> parseArrayValue()
                '"' -> JsonValue.Str(parseString())
                't' -> { expectWord("true"); JsonValue.Bool(true) }
                'f' -> { expectWord("false"); JsonValue.Bool(false) }
                'n' -> { expectWord("null"); JsonValue.Null }
                else -> if (s[i] == '-' || s[i] in '0'..'9') parseNumber() else fail("unexpected character '${s[i]}'")
            }
        }

        fun expectWord(w: String) {
            if (i + w.length > s.length || !s.regionMatches(i, w, 0, w.length)) fail("invalid literal")
            i += w.length
        }

        fun parseObjectValue(): JsonValue.Obj {
            i++ // '{'
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && s[i] == '}') { i++; return JsonValue.Obj(map) }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') fail("expected object key")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || s[i] != ':') fail("expected ':'")
                i++
                map[key] = parseValue()
                skipWhitespace()
                if (atEnd()) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonValue.Obj(map) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun parseArrayValue(): JsonValue.Arr {
            i++ // '['
            val list = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && s[i] == ']') { i++; return JsonValue.Arr(list) }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (atEnd()) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonValue.Arr(list) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("bad \\u escape")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        fun parseNumber(): JsonValue.Num {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            return JsonValue.Num(s.substring(start, i))
        }
    }
}

/** Tiny JSON writer for payloads (e.g. YouTube player requests) and local persistence. */
object JsonWriter {
    fun string(v: String): String {
        val sb = StringBuilder(v.length + 8)
        sb.append('"')
        for (c in v) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "${string(k)}:$v" }

    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")
    val NULL = "null"
    val TRUE = "true"
    val FALSE = "false"
}

package com.agentdroid.agent

/**
 * Minimal JSON reader/writer (no dependencies, so the agent core runs and is tested on a plain JVM).
 * Values: Map (object), List (array), String, Double/Long (number), Boolean, null.
 */
object Json {
    @JvmStatic
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw p.error("trailing data")
        return v
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): MutableMap<String, Any?> {
        val v = parse(text)
        if (v !is Map<*, *>) throw IllegalArgumentException("JSON object expected")
        return v as MutableMap<String, Any?>
    }

    @JvmStatic
    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (v is Double || v is Float) {
                    if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
                } else {
                    sb.append(v.toLong())
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k as String)
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (o in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, o)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value: " + v.javaClass)
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Convenience: object from key/value pairs. */
    @JvmStatic
    fun obj(vararg kv: Any?): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        var i = 0
        while (i + 1 < kv.size) {
            m[kv[i] as String] = kv[i + 1]
            i += 2
        }
        return m
    }

    private class Parser(private val s: String) {
        var i = 0

        fun error(msg: String): IllegalArgumentException = IllegalArgumentException("JSON: $msg at $i")

        fun ws() {
            while (i < s.length && Character.isWhitespace(s[i])) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw error("unexpected end")
            val c = s[i]
            if (c == '{') return readObject()
            if (c == '[') return readArray()
            if (c == '"') return string()
            if (s.startsWith("true", i)) {
                i += 4
                return true
            }
            if (s.startsWith("false", i)) {
                i += 5
                return false
            }
            if (s.startsWith("null", i)) {
                i += 4
                return null
            }
            return number()
        }

        fun readObject(): MutableMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw error("key expected")
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') throw error("':' expected")
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) throw error("unexpected end")
                val c = s[i++]
                if (c == '}') return m
                if (c != ',') throw error("',' or '}' expected")
            }
        }

        fun readArray(): MutableList<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (i >= s.length) throw error("unexpected end")
                val c = s[i++]
                if (c == ']') return l
                if (c != ',') throw error("',' or ']' expected")
            }
        }

        fun string(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw error("unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) throw error("bad escape")
                val e = s[i++]
                when (e) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'u' -> {
                        if (i + 4 > s.length) throw error("bad \\u escape")
                        sb.append(Integer.parseInt(s.substring(i, i + 4), 16).toChar())
                        i += 4
                    }
                    else -> throw error("bad escape \\$e")
                }
            }
        }

        fun number(): Any {
            val start = i
            while (i < s.length && "+-0123456789.eE".indexOf(s[i]) >= 0) i++
            if (start == i) throw error("unexpected character '" + s[i] + "'")
            val t = s.substring(start, i)
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return t.toLong()
                return t.toDouble()
            } catch (e: NumberFormatException) {
                throw error("bad number $t")
            }
        }
    }
}

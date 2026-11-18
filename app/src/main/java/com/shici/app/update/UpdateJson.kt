package com.shici.app.update

/**
 * Minimal, strict, dependency-free JSON reader.
 *
 * Why not `org.json`: the release-document parsing must be exercisable on a plain JVM host, where
 * `org.json` is a stubbed no-op, and this module may not add a JSON framework dependency. The
 * grammar accepted here is exactly RFC 8259 (no comments, no trailing commas, no NaN/Infinity,
 * no unquoted keys), and every input is bounded so a hostile document cannot exhaust memory.
 *
 * Values are returned as: [Map]<String, Any?>, [List]<Any?>, [String], [Boolean], [Long],
 * [Double] or null. Integers that fit in a Long are returned as [Long]; anything else as [Double].
 */
object UpdateJson {
    const val MAX_DEPTH = 24
    private const val MAX_STRING_CHARS = 1 shl 20
    // Bounds the number of parsed values: a 2 MiB release document stays far below this.
    private const val MAX_VALUES = 400_000

    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? = Parser(text).document()

    private class Parser(private val text: String) {
        private var at = 0
        private var values = 0

        fun document(): Any? {
            skipWhitespace()
            val value = value(0)
            skipWhitespace()
            if (at != text.length) fail("JSON 末尾存在多余内容")
            return value
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("JSON 嵌套过深")
            if (++values > MAX_VALUES) fail("JSON 节点过多")
            if (at >= text.length) fail("JSON 意外结束")
            return when (val c = text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("非法 JSON 字符")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            at++ // '{'
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { at++; return out }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("对象键不是字符串")
                val key = string()
                skipWhitespace()
                if (peek() != ':') fail("对象键后缺少冒号")
                at++
                skipWhitespace()
                if (out.containsKey(key)) fail("对象成员重复")
                out[key] = value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    '}' -> { at++; return out }
                    else -> fail("对象成员后缺少逗号或右花括号")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            at++ // '['
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { at++; return out }
            while (true) {
                skipWhitespace()
                out.add(value(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    ']' -> { at++; return out }
                    else -> fail("数组元素后缺少逗号或右方括号")
                }
            }
        }

        private fun string(): String {
            at++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (at >= text.length) fail("字符串未结束")
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(escape())
                    c < ' ' -> fail("字符串中出现控制字符")
                    else -> out.append(c)
                }
                if (out.length > MAX_STRING_CHARS) fail("字符串过长")
            }
        }

        private fun escape(): Char {
            if (at >= text.length) fail("转义未结束")
            return when (val c = text[at++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (at + 4 > text.length) fail("Unicode 转义不完整")
                    var code = 0
                    repeat(4) {
                        val digit = Character.digit(text[at++], 16)
                        if (digit < 0) fail("Unicode 转义不是十六进制")
                        code = code * 16 + digit
                    }
                    code.toChar()
                }
                else -> fail("非法转义 \\\\$c")
            }
        }

        private fun number(): Any {
            val start = at
            if (peek() == '-') at++
            if (peek() == '0') {
                at++
            } else {
                if (peek() !in '1'..'9') fail("非法数字")
                while (peek() in '0'..'9') at++
            }
            var integral = true
            if (peek() == '.') {
                integral = false
                at++
                if (peek() !in '0'..'9') fail("小数点后缺少数字")
                while (peek() in '0'..'9') at++
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                at++
                if (peek() == '+' || peek() == '-') at++
                if (peek() !in '0'..'9') fail("指数缺少数字")
                while (peek() in '0'..'9') at++
            }
            val raw = text.substring(start, at)
            if (!integral) return raw.toDoubleOrNull() ?: fail("非法数字")
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: fail("非法数字")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) fail("非法字面量")
            at += word.length
            return value
        }

        private fun skipWhitespace() {
            while (at < text.length) {
                when (text[at]) {
                    ' ', '\t', '\n', '\r' -> at++
                    else -> return
                }
            }
        }

        private fun peek(): Char = if (at < text.length) text[at] else '\u0000'

        private fun fail(message: String): Nothing =
            throw ParseException("$message（位置 $at）")
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asJsonObject(): Map<String, Any?>? = this as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Any?.asJsonArray(): List<Any?>? = this as? List<Any?>
internal fun Any?.asJsonString(): String? = this as? String
internal fun Any?.asJsonBoolean(): Boolean? = this as? Boolean
internal fun Any?.asJsonLong(): Long? = when (this) {
    is Long -> this
    is Double -> if (this == Math.rint(this) && !isInfinite() && Math.abs(this) < 9.0e18) toLong() else null
    else -> null
}

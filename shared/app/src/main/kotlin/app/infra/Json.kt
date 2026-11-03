// shared/app/infra/Json.kt —— 极简 JSON 读写（自包含，零三方依赖）
//
// ★ 2026-11-03（T05-C1）：从 desktop/core/infra/Json.kt 原样上移，正文 279 行**逐字未改**，
//   只动了三处：package、可见性 internal→public（跨模块就必须公开）、以及本段说明。
//   为什么现在上移：设置文件 / 导出清单 / 导出日志三处 JSON 两端都要写，
//   留在桌面端等于让安卓再手写一遍（ZIP 内的 StateJson 就是那么来的）。
//
// 为什么自己写而不用 kotlinx-serialization / Gson：
//   1. 本模块只需要「设置文件 + 导出清单 + 导出日志」三处极窄的 JSON 场景，
//      引入序列化框架会带进编译器插件与额外传递依赖，而桌面端最终要 jlink 裁剪运行时；
//   2. 与原工程风格一致（手写 OOXML、手写 ZIP、ZipExporter 内的自包含 StateJson）；
//   3. 唯一的风险点是"转义/大整数"，所以：数字一律保留原始字面量（Num.raw），
//      字符串按 RFC 8259 完整转义，并有针对性单测（中文/引号/反斜杠/控制字符/2^63 级整数）。
//
// ★ 行为约定：解析失败返回 null（不抛异常）——调用方（设置、清单）一律"读不出就当没有"，
//   绝不让一个坏文件把整个程序卡死。
package app.infra

/** JSON 值模型。 */
sealed interface JsonValue {
    data class Obj(val fields: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue

    data class Arr(val items: List<JsonValue> = emptyList()) : JsonValue

    data class Str(val value: String) : JsonValue

    /** 数字：保留原始字面量，避免 2^53 以上的 Long（如照片 id）被 Double 截断。 */
    data class Num(val raw: String) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** 便捷构造。 */
fun jsonNum(value: Long): JsonValue = JsonValue.Num(value.toString())
fun jsonNum(value: Int): JsonValue = JsonValue.Num(value.toString())
fun jsonNum(value: Double): JsonValue = if (value == value.toLong().toDouble()) {
    JsonValue.Num(value.toLong().toString())
} else {
    JsonValue.Num(value.toString())
}

/** 对象构造 DSL：`json { put("a", 1); put("b", "x") }`。 */
fun json(build: JsonObjBuilder.() -> Unit): JsonValue.Obj = JsonObjBuilder().apply(build).build()

class JsonObjBuilder {
    private val map = LinkedHashMap<String, JsonValue>()

    fun put(key: String, value: String?) {
        map[key] = value?.let { JsonValue.Str(it) } ?: JsonValue.Null
    }

    fun put(key: String, value: Long) {
        map[key] = jsonNum(value)
    }

    fun put(key: String, value: Int) {
        map[key] = jsonNum(value)
    }

    fun put(key: String, value: Double) {
        map[key] = jsonNum(value)
    }

    fun put(key: String, value: Boolean) {
        map[key] = JsonValue.Bool(value)
    }

    fun put(key: String, value: JsonValue) {
        map[key] = value
    }

    fun putStrings(key: String, values: List<String>) {
        map[key] = JsonValue.Arr(values.map { JsonValue.Str(it) })
    }

    fun putArray(key: String, values: List<JsonValue>) {
        map[key] = JsonValue.Arr(values)
    }

    fun build(): JsonValue.Obj = JsonValue.Obj(map)
}

// ── 读侧访问器（类型不符一律返回 null，不做隐式转换） ──────────────────────────

fun JsonValue.asObj(): JsonValue.Obj? = this as? JsonValue.Obj
fun JsonValue.asArr(): List<JsonValue>? = (this as? JsonValue.Arr)?.items
fun JsonValue.asStr(): String? = (this as? JsonValue.Str)?.value

fun JsonValue.Obj.objOrNull(key: String): JsonValue.Obj? = fields[key]?.asObj()
fun JsonValue.Obj.strOrNull(key: String): String? = fields[key]?.asStr()
fun JsonValue.Obj.longOrNull(key: String): Long? = (fields[key] as? JsonValue.Num)?.raw?.toLongOrNull()
fun JsonValue.Obj.intOrNull(key: String): Int? = (fields[key] as? JsonValue.Num)?.raw?.toIntOrNull()
fun JsonValue.Obj.doubleOrNull(key: String): Double? = (fields[key] as? JsonValue.Num)?.raw?.toDoubleOrNull()
fun JsonValue.Obj.boolOrNull(key: String): Boolean? = (fields[key] as? JsonValue.Bool)?.value
fun JsonValue.Obj.arrOrNull(key: String): List<JsonValue>? = fields[key]?.asArr()
fun JsonValue.Obj.stringList(key: String): List<String> =
    arrOrNull(key)?.mapNotNull { it.asStr() } ?: emptyList()

object Json {

    /** 解析；失败返回 null。 */
    fun parse(text: String): JsonValue? {
        val p = Parser(text)
        val v = runCatching { p.parseValue() }.getOrNull() ?: return null
        p.skipWs()
        return if (p.atEnd()) v else null
    }

    /** 序列化。[pretty] 为 true 时缩进 2 空格（便于人读/排障）。 */
    fun write(value: JsonValue, pretty: Boolean = false): String =
        StringBuilder().also { writeTo(it, value, pretty, 0) }.toString()

    private fun writeTo(sb: StringBuilder, v: JsonValue, pretty: Boolean, depth: Int) {
        when (v) {
            is JsonValue.Obj -> {
                if (v.fields.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var i = 0
                for ((k, child) in v.fields) {
                    if (i++ > 0) sb.append(',')
                    newline(sb, pretty, depth + 1)
                    writeString(sb, k)
                    sb.append(if (pretty) ": " else ":")
                    writeTo(sb, child, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append('}')
            }

            is JsonValue.Arr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.items.forEachIndexed { idx, child ->
                    if (idx > 0) sb.append(',')
                    newline(sb, pretty, depth + 1)
                    writeTo(sb, child, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append(']')
            }

            is JsonValue.Str -> writeString(sb, v.value)
            is JsonValue.Num -> sb.append(v.raw)
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            JsonValue.Null -> sb.append("null")
        }
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    /** RFC 8259 字符串转义。中文不转义（文件按 UTF-8 写，便于人读）。 */
    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): JsonValue {
            skipWs()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> if (c == '-' || c.isDigit()) parseNum() else error("unexpected char: $c")
            }
        }

        private fun parseObj(): JsonValue.Obj {
            i++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return JsonValue.Obj(map) }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(i < s.length && s[i] == ':') { "expected ':'" }
                i++
                map[key] = parseValue()
                skipWs()
                require(i < s.length) { "unexpected end in object" }
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonValue.Obj(map) }
                    else -> error("expected ',' or '}'")
                }
            }
        }

        private fun parseArr(): JsonValue.Arr {
            i++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return JsonValue.Arr(items) }
            while (true) {
                items.add(parseValue())
                skipWs()
                require(i < s.length) { "unexpected end in array" }
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonValue.Arr(items) }
                    else -> error("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            require(i < s.length && s[i] == '"') { "expected string" }
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        require(i + 1 < s.length) { "bad escape" }
                        when (val e = s[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 5 < s.length) { "bad unicode escape" }
                                sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("bad escape: $e")
                        }
                        i += 2
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            error("unterminated string")
        }

        private fun parseNum(): JsonValue.Num {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        s[i] == '+' || s[i] == '-')
            ) i++
            return JsonValue.Num(s.substring(start, i))
        }

        private fun expect(word: String) {
            require(s.startsWith(word, i)) { "expected $word" }
            i += word.length
        }
    }
}

package com.zxhhyj.prism.motion.clinative

/** 极简 JSON 输出器（Kotlin/Native 没有 String.format，全部手写）。 */
internal object Json {

    fun str(value: String?): String {
        if (value == null) return "null"
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    val code = c.code
                    for (shift in intArrayOf(12, 8, 4, 0)) {
                        sb.append("0123456789abcdef"[(code shr shift) and 0xF])
                    }
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun num(value: Number?): String = value?.toString() ?: "null"

    fun bool(value: Boolean?): String = value?.toString() ?: "null"

    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { "${str(it.first)}:${it.second}" }

    fun obj(pairs: Map<String, String>): String =
        pairs.entries.joinToString(",", "{", "}") { "${str(it.key)}:${it.value}" }

    fun arr(items: Iterable<String>): String = items.joinToString(",", "[", "]")
}

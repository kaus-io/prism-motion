package com.zxhhyj.prism.motion.cli

/** 极简 JSON 输出器：不引第三方序列化库，保证 CLI 只有一个 jar。 */
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
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
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

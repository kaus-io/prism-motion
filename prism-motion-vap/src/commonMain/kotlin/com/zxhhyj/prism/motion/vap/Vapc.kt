package com.zxhhyj.prism.motion.vap

/** vapc 里的一个矩形（rgb 内容区或 alpha 蒙版区）。 / One rectangle inside the vapc config. */
public class VapcRect(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
)

/** vapc box 的 JSON 模型。 / JSON model stored in the vapc box. */
public class Vapc(
    public val width: Int,
    public val height: Int,
    public val videoWidth: Int,
    public val videoHeight: Int,
    public val fps: Int,
    public val frameCount: Int,
    public val hasAlpha: Boolean,
    public val rgb: VapcRect,
    public val alpha: VapcRect,
) {
    public companion object {

        /** 解析 vapc JSON；字段缺失时返回 null。 / Parses vapc JSON; null when required fields are missing. */
        public fun parse(json: String): Vapc? {
            fun number(key: String): Int? =
                Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

            fun rect(key: String): List<Int> =
                Regex("\"$key\"\\s*:\\s*\\[([^]]+)]").find(json)?.groupValues?.get(1)
                    ?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(0, 0, 0, 0)

            val width = number("w") ?: return null
            val height = number("h") ?: return null
            val rgb = rect("rgbFrame")
            val alpha = rect("aFrame")
            return Vapc(
                width = width,
                height = height,
                videoWidth = number("videoW") ?: width,
                videoHeight = number("videoH") ?: height,
                fps = (number("fps") ?: 30).coerceAtLeast(1),
                frameCount = number("f") ?: 0,
                hasAlpha = Regex("\"hasAlpha\"\\s*:\\s*(true|false)")
                    .find(json)?.groupValues?.get(1) == "true",
                rgb = VapcRect(rgb.getOrElse(0) { 0 }, rgb.getOrElse(1) { 0 }, rgb.getOrElse(2) { width }, rgb.getOrElse(3) { height }),
                alpha = VapcRect(alpha.getOrElse(0) { 0 }, alpha.getOrElse(1) { 0 }, alpha.getOrElse(2) { 0 }, alpha.getOrElse(3) { 0 }),
            )
        }

        /** 生成 vapc JSON。 / Serializes the vapc config. */
        public fun toJson(config: Vapc): String =
            """{"info":{"v":3,"f":${config.frameCount},"w":${config.width},"h":${config.height},""" +
                """"videoW":${config.videoWidth},"videoH":${config.videoHeight},"fps":${config.fps},""" +
                """"aFrame":[${config.alpha.x},${config.alpha.y},${config.alpha.width},${config.alpha.height}],""" +
                """"rgbFrame":[${config.rgb.x},${config.rgb.y},${config.rgb.width},${config.rgb.height}],""" +
                """"hasAlpha":${config.hasAlpha}}}"""
    }
}

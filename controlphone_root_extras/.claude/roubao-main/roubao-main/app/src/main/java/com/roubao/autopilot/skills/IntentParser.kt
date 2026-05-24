package com.roubao.autopilot.skills

/**
 * 意图解析器 — 正则匹配用户指令，提取结构化参数
 *
 * 纯正则，零延迟。匹配不到返回 null，由上层降级到 VLM。
 * 新增意图模式只需在这里加一条规则。
 */
class IntentParser {

    companion object {
        private const val TAG = "[IntentParser]"
    }

    /**
     * 模式定义: Regex → (templateId, appInference, paramExtractor)
     */
    private val patterns = listOf(
        // === 微信相关 ===
        // "给X发Y" / "发微信给X说Y"
        PatternDef(
            regex = Regex("给(.+?)发(.+)"),
            templateId = "wechat_send_message",
            appInfer = { "微信" },
            paramExtract = { match ->
                mapOf("X" to match.groupValues[1].trim(), "Y" to match.groupValues[2].trim())
            }
        ),
        // "微信给X发Y" / "微信发给X Y"
        PatternDef(
            regex = Regex("微信.{0,2}给(.+?)发(?!送)(.+)"),
            templateId = "wechat_send_message",
            appInfer = { "微信" },
            paramExtract = { match ->
                mapOf("X" to match.groupValues[1].trim(), "Y" to match.groupValues[2].trim())
            }
        ),
        // "微信搜X"
        PatternDef(
            regex = Regex("微信.{0,1}搜(.+)"),
            templateId = "wechat_search",
            appInfer = { "微信" },
            paramExtract = { match ->
                mapOf("X" to match.groupValues[1].trim())
            }
        ),
        // "看朋友圈"
        PatternDef(
            regex = Regex("朋友圈|刷朋友圈|看朋友圈"),
            templateId = "wechat_moments",
            appInfer = { "微信" },
            paramExtract = { emptyMap() }
        ),

        // === 浏览器/搜索 ===
        // "搜X" / "搜索X"
        PatternDef(
            regex = Regex("^(?:搜|搜索|查)(.+)"),
            templateId = "browser_search",
            appInfer = { "浏览器" },
            paramExtract = { match ->
                mapOf("X" to match.groupValues[1].trim())
            }
        ),
        // "百度搜X" / "百度X"
        PatternDef(
            regex = Regex("百度.{0,1}搜?(.+)"),
            templateId = "browser_search",
            appInfer = { "浏览器" },
            paramExtract = { match ->
                mapOf("X" to match.groupValues[1].trim())
            }
        ),

        // === 抖音 ===
        // "抖音搜X"
        PatternDef(
            regex = Regex("抖音.{0,1}搜(.+)"),
            templateId = "douyin_search",
            appInfer = { "抖音" },
            paramExtract = { match ->
                mapOf("X" to match.groupValues[1].trim())
            }
        ),

        // === 淘宝 ===
        // "淘宝搜X" / "买X" / "逛淘宝"
        PatternDef(
            regex = Regex("淘宝.{0,1}搜(.+)"),
            templateId = "taobao_search",
            appInfer = { "淘宝" },
            paramExtract = { match ->
                mapOf("X" to match.groupValues[1].trim())
            }
        ),
        // "打开淘宝"
        PatternDef(
            regex = Regex("打开淘宝|逛淘宝|淘宝"),
            templateId = "taobao_open",
            appInfer = { "淘宝" },
            paramExtract = { emptyMap() }
        ),

        // === 设置 ===
        // "打开设置X" / "设置X"
        PatternDef(
            regex = Regex("(?:打开)?设置(.+)"),
            templateId = "settings_open",
            appInfer = { "设置" },
            paramExtract = { match ->
                mapOf("X" to match.groupValues[1].trim())
            }
        ),

        // === 扫码 ===
        // "扫一扫" / "扫码"
        PatternDef(
            regex = Regex("扫一扫|扫码|付款码"),
            templateId = "alipay_scan",
            appInfer = { "支付宝" },
            paramExtract = { emptyMap() }
        ),

        // === 通用：打开某应用 ===
        // "打开X应用" / "打开X"
        PatternDef(
            regex = Regex("打开(.{1,6}?)(?:应用|app|App)?$"),
            templateId = "open_app_only",
            appInfer = { match -> match.groupValues[1].trim() },
            paramExtract = { emptyMap() }
        )
    )

    /**
     * 解析用户指令
     * @return ParsedIntent 或 null（未匹配到任何模式）
     */
    fun parse(instruction: String): ParsedIntent? {
        val cleaned = instruction.trim()

        for (def in patterns) {
            val match = def.regex.find(cleaned)
            if (match != null) {
                val appName = def.appInfer(match)
                val params = def.paramExtract(match)

                println("$TAG 命中: ${def.templateId} → app=$appName, params=$params")
                return ParsedIntent(
                    templateId = def.templateId,
                    appName = appName,
                    params = params,
                    confidence = 0.85f
                )
            }
        }

        println("$TAG 未匹配: $instruction")
        return null
    }
}

/**
 * 意图模式定义
 */
private data class PatternDef(
    val regex: Regex,
    val templateId: String,
    val appInfer: (MatchResult) -> String,
    val paramExtract: (MatchResult) -> Map<String, String>
)

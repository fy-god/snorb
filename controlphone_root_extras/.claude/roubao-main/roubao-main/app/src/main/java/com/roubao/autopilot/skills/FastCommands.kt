package com.roubao.autopilot.skills

import com.roubao.autopilot.agent.Action

/**
 * 本地命令词直通层 — 零 VLM 调用，毫秒级响应
 *
 * 返回、滑动、Home 等高频基础操作匹配关键词直接执行，不走任何 AI。
 * 这类操作如果走截图+大模型，每步 3-6 秒太浪费。
 */
object FastCommands {

    data class FastMatch(
        val action: Action,
        val type: MatchType
    )

    enum class MatchType {
        EXACT,    // 精确匹配，直接执行
        HEURISTIC // 启发式匹配，可能需要看图确认位置
    }

    private val exactCommands = listOf(
        // 系统按键
        Command("返回",     Action(type = "system_button", button = "Back")),
        Command("回退",     Action(type = "system_button", button = "Back")),
        Command("后退",     Action(type = "system_button", button = "Back")),
        Command("回首页",   Action(type = "system_button", button = "Home")),
        Command("桌面",     Action(type = "system_button", button = "Home")),
        Command("回到桌面", Action(type = "system_button", button = "Home")),
        Command("确认",     Action(type = "system_button", button = "Enter")),

        // 方向滑动
        Command("往上滑",   Action(type = "swipe", direction = "up")),
        Command("上滑",     Action(type = "swipe", direction = "up")),
        Command("向上滑动", Action(type = "swipe", direction = "up")),
        Command("往上翻",   Action(type = "swipe", direction = "up")),
        Command("往下滑",   Action(type = "swipe", direction = "down")),
        Command("下滑",     Action(type = "swipe", direction = "down")),
        Command("向下滑动", Action(type = "swipe", direction = "down")),
        Command("往下翻",   Action(type = "swipe", direction = "down")),
        Command("下一页",   Action(type = "swipe", direction = "down")),
        Command("往左滑",   Action(type = "swipe", direction = "left")),
        Command("左滑",     Action(type = "swipe", direction = "left")),
        Command("往右滑",   Action(type = "swipe", direction = "right")),
        Command("右滑",     Action(type = "swipe", direction = "right")),

        // 高频操作
        Command("刷新",     Action(type = "swipe", direction = "down")),
        Command("下拉刷新", Action(type = "swipe", direction = "down")),
        Command("截屏",     Action(type = "answer", text = "请使用系统截屏功能")),
        Command("锁屏",     Action(type = "system_button", button = "Home")),
    )

    private val heuristicCommands = listOf(
        // 这些需要知道屏幕当前状态才能执行
        Command("确认按钮",        Action(type = "click", x = 540, y = 1500)),
        Command("点确定",          Action(type = "click", x = 540, y = 1500)),
        Command("关闭弹窗",        Action(type = "click", x = 540, y = 1200)),
        Command("取消",            Action(type = "click", x = 200, y = 1200)),
        Command("关闭",            Action(type = "click", x = 100, y = 100)),
        Command("下一步",          Action(type = "click", x = 950, y = 2200)),
    )

    /**
     * 尝试匹配本地命令词
     * @return FastMatch 或 null（未命中）
     */
    fun match(instruction: String): FastMatch? {
        val cleaned = instruction.trim()

        // 先精确匹配
        for (cmd in exactCommands) {
            if (cleaned.equals(cmd.keyword, ignoreCase = true) ||
                cleaned.startsWith(cmd.keyword) ||
                cleaned.endsWith(cmd.keyword)) {
                return FastMatch(cmd.action, MatchType.EXACT)
            }
        }

        // 再启发式匹配
        for (cmd in heuristicCommands) {
            if (cleaned.contains(cmd.keyword, ignoreCase = true)) {
                return FastMatch(cmd.action, MatchType.HEURISTIC)
            }
        }

        return null
    }

    /**
     * 判断是否应该跳过 VLM（纯本地命令）
     */
    fun isLocalOnlyCommand(instruction: String): Boolean {
        for (cmd in exactCommands) {
            if (cleanedEqualsOrContains(instruction, cmd.keyword)) return true
        }
        return false
    }

    private fun cleanedEqualsOrContains(instruction: String, keyword: String): Boolean {
        val cleaned = instruction.trim()
        return cleaned.equals(keyword, ignoreCase = true) ||
               cleaned.startsWith(keyword) ||
               cleaned.endsWith(keyword)
    }

    private data class Command(
        val keyword: String,
        val action: Action
    )
}

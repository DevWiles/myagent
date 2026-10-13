package dev.wiles.myagent.client.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把一组工具调用概括成一句话：「读取 3 个文件 · 执行 1 条命令」。
 *
 * <p>**措辞逐字对齐 `tui/src/format.ts`**——两块屏对同一组调用必须说同一句话，
 * 不然用户会以为是两个程序（这条理由最早是给 Java 版和 Ink 版对齐用的，
 * 现在轮到手机和终端）。改文案要两边一起改。
 */
object ToolSummary {

    /** 工具名 → [动词, 量词]。没登记的退回通用说法。 */
    private val WORDING = mapOf(
        "read_file" to ("读取" to "个文件"),
        "write_file" to ("写入" to "个文件"),
        "edit_file" to ("修改" to "处"),
        "list_dir" to ("列出" to "个目录"),
        "bash" to ("执行" to "条命令"),
        "create_project" to ("创建" to "批文件"),
        "grep" to ("搜索" to "次"),
        "glob" to ("找文件" to "次"),
    )

    /** 这些工具只调一次时，显示模式本身而不是次数——用户想知道的是**搜了什么**。 */
    private val SHOWS_PATTERN = setOf("grep", "glob")

    private const val MAX_PATTERN = 32

    fun summarize(calls: List<ToolCall>): String {
        val counts = LinkedHashMap<String, Int>()
        for (call in calls) counts.merge(call.tool, 1) { a, b -> a + b }

        return counts.entries.joinToString(" · ") { (tool, count) ->
            val wording = WORDING[tool]
                ?: return@joinToString "调用 $count 次 $tool"
            val only = if (count == 1) patternOf(calls.first { it.tool == tool }) else null
            if (only != null) "${wording.first} $only"
            else "${wording.first} $count ${wording.second}"
        }
    }

    // 用 kotlinx 而不是 org.json：后者在 JVM 单测里是 android.jar 的 stub（一调就抛），
    // 而这段摘要文案恰恰是要和 TUI 逐字对齐、必须有测试守着的
    private val json = Json { ignoreUnknownKeys = true }

    /** 从参数里取 pattern。参数不是合法 JSON 就当没有，绝不为它抛。 */
    private fun patternOf(call: ToolCall): String? {
        if (call.tool !in SHOWS_PATTERN) return null
        return try {
            val pattern = (json.parseToJsonElement(call.args) as? JsonObject)
                ?.get("pattern")?.jsonPrimitive?.contentOrNull
            when {
                pattern.isNullOrEmpty() -> null
                pattern.length > MAX_PATTERN -> pattern.take(MAX_PATTERN - 1) + "…"
                else -> pattern
            }
        } catch (e: Exception) {
            null
        }
    }
}

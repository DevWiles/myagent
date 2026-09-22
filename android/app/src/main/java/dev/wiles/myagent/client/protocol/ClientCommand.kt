package dev.wiles.myagent.client.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 客户端 → 服务端的命令（协议第 4 节）。
 *
 * 用构造函数拼 JsonObject 而不是 @Serializable：字段就这么几个，
 * 显式拼出来的报文和 docs/protocol.md 里的示例一一对得上，不用猜序列化器行为。
 */
object ClientCommand {

    private fun cmd(t: String, vararg fields: Pair<String, Any?>): String {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("t" to JsonPrimitive(t))
        for ((key, value) in fields) {
            when (value) {
                null -> {}
                is String -> map[key] = JsonPrimitive(value)
                is Int -> map[key] = JsonPrimitive(value)
                is Boolean -> map[key] = JsonPrimitive(value)
                is List<*> -> map[key] = JsonArray(value.filterIsInstance<String>().map { JsonPrimitive(it) })
                else -> error("拼不了这种字段: $key")
            }
        }
        return JsonObject(map).toString()
    }

    fun prompt(text: String) = cmd("prompt", "text" to text)
    fun plan(goal: String) = cmd("plan", "goal" to goal)
    fun team(goal: String) = cmd("team", "goal" to goal)
    fun resumePlan() = cmd("resume_plan")
    fun continueSession() = cmd("continue")
    fun planConfirmReply(id: Int, approved: Boolean) =
        cmd("plan_confirm_reply", "id" to id, "approved" to approved)

    fun cancel() = cmd("cancel")

    /**
     * `always` 时带 `rules`（从 suggestions[i].patterns 原样取），`no` 时可带 `feedback`。
     * 后端只接受它自己提议过的 pattern，别拼新的。
     */
    fun approvalReply(id: Int, decision: String, rules: List<String>? = null, feedback: String? = null) =
        cmd("approval_reply", "id" to id, "decision" to decision, "rules" to rules, "feedback" to feedback)

    fun reset() = cmd("reset")
    fun compact() = cmd("compact")
    fun memory(action: String) = cmd("memory", "action" to action)
    fun sessions() = cmd("sessions")
    fun sessionResume(id: String) = cmd("session_resume", "id" to id)
    fun ping() = cmd("ping")
}

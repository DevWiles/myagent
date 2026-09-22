package dev.wiles.myagent.client.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 和 `docs/protocol.md` / `tui/src/protocol.ts` 一一对应的线上协议（服务端 → 客户端）。
 *
 * 这里是 app 里唯一一处「知道后端长什么样」的地方。解析**手写而不是 @Serializable**：
 * 协议第 2 节要求客户端对不认识的 `t`、不认识的字段静默忽略，
 * 而 kotlinx 的严格反序列化正好相反——多一个字段就抛异常，少一个就构造失败。
 * 手取字段 + 全给默认值，老后端接新界面、新后端接老界面才都能活。
 */
const val PROTOCOL_VERSION = 1

data class ToolInfo(val name: String, val description: String)

data class RoleInfo(
    val id: String,
    val title: String,
    val summary: String,
    val mutates: Boolean,
)

data class PlanTaskWire(
    val id: String,
    val type: String,
    val role: String?,
    val roleTitle: String?,
    val description: String,
    val acceptance: String?,
    val status: String,
    val parentId: String?,
    val dependsOn: List<String>,
)

data class TeamSeatWire(
    val id: String,
    val role: String,
    val roleTitle: String,
    val summary: String,
    val status: String,
)

data class MemoryRow(
    val name: String,
    val description: String,
    val scope: String,
    val sourceLabel: String,
    val created: String,
    val supersededBy: String?,
)

data class SessionRow(
    val id: String,
    val title: String,
    val turns: Int,
    val model: String,
    val branch: String?,
    val savedAt: Long,
)

data class ApprovalSuggestion(val label: String, val patterns: List<String>)

data class Resumable(val goal: String, val remaining: Int, val total: Int, val savedAt: Long)

data class Continuable(
    val id: String,
    val branch: String?,
    val turns: Int,
    val messages: Int,
    val firstMessage: String,
    val savedAt: Long,
    val count: Int,
)

sealed interface ServerEvent {
    /** 广播事件的流水号（协议 1.2）。定向回执没有，是 null。 */
    val seq: Long?

    data class Ready(
        override val seq: Long?,
        val protocol: Int,
        val version: String,
        val workspace: String,
        val model: String,
        val planMode: Boolean,
        val hasApiKey: Boolean,
        val busy: Boolean,
        val features: List<String>,
        val tokens: Long,
        val sentTokens: Long,
        val contextLimit: Long,
        val workspaceWarning: String?,
        val roles: List<RoleInfo>,
        val branch: String?,
        val lastSeq: Long,
        val resumable: Resumable?,
        val continuable: Continuable?,
        val tools: List<ToolInfo>,
    ) : ServerEvent

    data class Thinking(override val seq: Long?, val step: Int) : ServerEvent
    data class TextDelta(override val seq: Long?, val text: String) : ServerEvent
    data class Thought(override val seq: Long?, val text: String) : ServerEvent
    data class ToolStarted(
        override val seq: Long?, val id: Int, val tool: String, val arguments: String,
    ) : ServerEvent

    data class ToolFinished(
        override val seq: Long?, val id: Int, val tool: String,
        val ok: Boolean, val output: String, val diff: String?,
    ) : ServerEvent

    data class Compacting(
        override val seq: Long?, val phase: String, val done: Int, val total: Int, val percent: Int,
    ) : ServerEvent

    data class Compacted(
        override val seq: Long?, val tokensBefore: Long, val tokensAfter: Long, val summarized: Boolean,
    ) : ServerEvent

    data class MemoryList(override val seq: Long?, val entries: List<MemoryRow>) : ServerEvent
    data class MemoryChanged(
        override val seq: Long?, val action: String, val name: String?,
        val description: String?, val removed: Int,
    ) : ServerEvent

    data class Done(override val seq: Long?, val text: String, val steps: Int) : ServerEvent
    data class Failed(override val seq: Long?, val reason: String) : ServerEvent

    data class ApprovalRequest(
        override val seq: Long?,
        val id: Int,
        val kind: String,
        val action: String,
        val detail: String,
        val reason: String,
        val preview: String,
        val origin: String,
        val suggestions: List<ApprovalSuggestion>,
        val preferred: Int,
    ) : ServerEvent

    data class Busy(
        override val seq: Long?, val busy: Boolean, val durationMs: Long?,
        val tokens: Long?, val sentTokens: Long?, val contextLimit: Long?,
    ) : ServerEvent

    data class ResetDone(override val seq: Long?) : ServerEvent

    /**
     * 用户说了一句话（协议 3.15）。广播给所有界面——手机上要能看见终端里说的话。
     * `cid` 是发起方的连接 id，自己那条要跳过（本地已即时回显）。
     */
    data class UserMessage(
        override val seq: Long?, val text: String, val mode: String, val cid: String,
    ) : ServerEvent
    data class SessionList(
        override val seq: Long?, val currentId: String, val sessions: List<SessionRow>,
    ) : ServerEvent

    data class SessionRestored(
        override val seq: Long?, val messages: Int, val turns: Int, val firstMessage: String,
        val tokens: Long?, val sentTokens: Long?, val contextLimit: Long?,
    ) : ServerEvent

    data class ErrorEvent(override val seq: Long?, val message: String) : ServerEvent
    data class Pong(override val seq: Long?) : ServerEvent

    data class PlanCreated(
        override val seq: Long?, val goal: String,
        val tasks: List<PlanTaskWire>, val layers: List<List<String>>,
    ) : ServerEvent

    data class PlanConfirmRequest(override val seq: Long?, val id: Int) : ServerEvent
    data class PlanTaskStarted(override val seq: Long?, val taskId: String) : ServerEvent
    data class PlanTaskFinished(
        override val seq: Long?, val taskId: String, val status: String,
        val durationMs: Long, val error: String?,
    ) : ServerEvent

    data class PlanTasksAdded(
        override val seq: Long?, val parentId: String, val tasks: List<PlanTaskWire>,
    ) : ServerEvent

    data class PlanRevised(
        override val seq: Long?, val failedTaskId: String, val tasks: List<PlanTaskWire>,
    ) : ServerEvent

    data class PlanFinished(
        override val seq: Long?, val status: String, val succeeded: Int?,
        val failed: Int?, val skipped: Int?, val durationMs: Long?,
    ) : ServerEvent

    data class ModeSwitched(override val seq: Long?, val reason: String) : ServerEvent

    data class TeamStarted(
        override val seq: Long?, val goal: String, val reason: String, val seats: List<TeamSeatWire>,
    ) : ServerEvent

    data class TeamSeatsAdded(override val seq: Long?, val seats: List<TeamSeatWire>) : ServerEvent
    data class TeamSeatStarted(override val seq: Long?, val seatId: String) : ServerEvent
    data class TeamSeatFinished(
        override val seq: Long?, val seatId: String, val status: String,
        val durationMs: Long, val opinion: String?, val error: String?,
    ) : ServerEvent

    data class TeamFinished(
        override val seq: Long?, val status: String, val spoke: Int,
        val silent: Int, val verdict: String?,
    ) : ServerEvent

    /** 断线重连时留底装不下，这一段永远补不回来了（协议 1.2）。 */
    data class ReplayGap(override val seq: Long?, val from: Long, val to: Long) : ServerEvent
}

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String, fallback: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: fallback

private fun JsonObject.strOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String, fallback: Int = 0): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: fallback

private fun JsonObject.long(key: String, fallback: Long = 0): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: fallback

private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: fallback

private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

private fun planTask(o: JsonObject) = PlanTaskWire(
    id = o.str("id"),
    type = o.str("type"),
    role = o.strOrNull("role"),
    roleTitle = o.strOrNull("roleTitle"),
    description = o.str("description"),
    acceptance = o.strOrNull("acceptance"),
    status = o.str("status", "PENDING"),
    parentId = o.strOrNull("parentId"),
    dependsOn = o.strings("dependsOn"),
)

private fun teamSeat(o: JsonObject) = TeamSeatWire(
    id = o.str("id"),
    role = o.str("role"),
    roleTitle = o.str("roleTitle"),
    summary = o.str("summary"),
    status = o.str("status", "PENDING"),
)

/**
 * 一行报文 → 事件。认不出的 `t` 和解析失败都返回 null：
 * 一条脏报文不该让整个界面挂掉，调用方把它当噪声丢掉就行（协议第 2 节）。
 */
fun parseServerEvent(raw: String): ServerEvent? {
    val o = try {
        json.parseToJsonElement(raw) as? JsonObject ?: return null
    } catch (e: Exception) {
        return null
    }
    val seq = o.longOrNull("seq")
    return when (o.str("t")) {
        "ready" -> ServerEvent.Ready(
            seq = seq,
            protocol = o.int("protocol"),
            version = o.str("version"),
            workspace = o.str("workspace"),
            model = o.str("model"),
            planMode = o.bool("planMode"),
            hasApiKey = o.bool("hasApiKey"),
            busy = o.bool("busy"),
            features = o.strings("features"),
            tokens = o.long("tokens"),
            sentTokens = o.long("sentTokens"),
            contextLimit = o.long("contextLimit"),
            workspaceWarning = o.strOrNull("workspaceWarning"),
            roles = o.objects("roles").map {
                RoleInfo(it.str("id"), it.str("title"), it.str("summary"), it.bool("mutates"))
            },
            branch = o.strOrNull("branch"),
            lastSeq = o.long("lastSeq"),
            resumable = (o["resumable"] as? JsonObject)?.let {
                Resumable(it.str("goal"), it.int("remaining"), it.int("total"), it.long("savedAt"))
            },
            continuable = (o["continuable"] as? JsonObject)?.let {
                Continuable(
                    it.str("id"), it.strOrNull("branch"), it.int("turns"), it.int("messages"),
                    it.str("firstMessage"), it.long("savedAt"), it.int("count"),
                )
            },
            tools = o.objects("tools").map { ToolInfo(it.str("name"), it.str("description")) },
        )

        "thinking" -> ServerEvent.Thinking(seq, o.int("step"))
        "text_delta" -> ServerEvent.TextDelta(seq, o.str("text"))
        "thought" -> ServerEvent.Thought(seq, o.str("text"))
        "tool_started" -> ServerEvent.ToolStarted(seq, o.int("id"), o.str("tool"), o.str("arguments"))
        "tool_finished" -> ServerEvent.ToolFinished(
            seq, o.int("id"), o.str("tool"), o.bool("ok"), o.str("output"), o.strOrNull("diff"),
        )

        "compacting" -> ServerEvent.Compacting(
            seq, o.str("phase"), o.int("done"), o.int("total"),
            // percent 后端算好的，可能带小数
            (o["percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt(),
        )

        "compacted" -> ServerEvent.Compacted(
            seq, o.long("tokensBefore"), o.long("tokensAfter"), o.bool("summarized"),
        )

        "memory_list" -> ServerEvent.MemoryList(seq, o.objects("entries").map {
            MemoryRow(
                it.str("name"), it.str("description"), it.str("scope"),
                it.str("sourceLabel"), it.str("created"), it.strOrNull("supersededBy"),
            )
        })

        "memory_changed" -> ServerEvent.MemoryChanged(
            seq, o.str("action"), o.strOrNull("name"), o.strOrNull("description"), o.int("removed"),
        )

        "done" -> ServerEvent.Done(seq, o.str("text"), o.int("steps"))
        "failed" -> ServerEvent.Failed(seq, o.str("reason"))

        "approval_request" -> ServerEvent.ApprovalRequest(
            seq = seq,
            id = o.int("id", -1),
            // 老后端不发 kind，当 bash 处理（同 TUI）
            kind = o.str("kind", "bash"),
            action = o.str("action"),
            detail = o.str("detail"),
            reason = o.str("reason"),
            preview = o.str("preview"),
            origin = o.str("origin"),
            suggestions = o.objects("suggestions").map {
                ApprovalSuggestion(it.str("label"), it.strings("patterns"))
            },
            preferred = o.int("preferred"),
        )

        "busy" -> ServerEvent.Busy(
            seq, o.bool("busy"), o.longOrNull("durationMs"),
            o.longOrNull("tokens"), o.longOrNull("sentTokens"), o.longOrNull("contextLimit"),
        )

        "reset_done" -> ServerEvent.ResetDone(seq)
        "user_message" -> ServerEvent.UserMessage(seq, o.str("text"), o.str("mode"), o.str("cid"))

        "session_list" -> ServerEvent.SessionList(seq, o.str("currentId"), o.objects("sessions").map {
            SessionRow(
                it.str("id"), it.str("title"), it.int("turns"), it.str("model"),
                it.strOrNull("branch"), it.long("savedAt"),
            )
        })

        "session_restored" -> ServerEvent.SessionRestored(
            seq, o.int("messages"), o.int("turns"), o.str("firstMessage"),
            o.longOrNull("tokens"), o.longOrNull("sentTokens"), o.longOrNull("contextLimit"),
        )

        "error" -> ServerEvent.ErrorEvent(seq, o.str("message"))
        "pong" -> ServerEvent.Pong(seq)

        "plan_created" -> ServerEvent.PlanCreated(
            seq, o.str("goal"), o.objects("tasks").map { planTask(it) },
            (o["layers"] as? kotlinx.serialization.json.JsonArray)
                ?.map { layer -> layer.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
                ?: emptyList(),
        )

        "plan_confirm_request" -> ServerEvent.PlanConfirmRequest(seq, o.int("id", -1))
        "plan_task_started" -> ServerEvent.PlanTaskStarted(seq, o.str("taskId"))
        "plan_task_finished" -> ServerEvent.PlanTaskFinished(
            seq, o.str("taskId"), o.str("status"), o.long("durationMs"), o.strOrNull("error"),
        )

        "plan_tasks_added" -> ServerEvent.PlanTasksAdded(
            seq, o.str("parentId"), o.objects("tasks").map { planTask(it) },
        )

        "plan_revised" -> ServerEvent.PlanRevised(
            seq, o.str("failedTaskId"), o.objects("tasks").map { planTask(it) },
        )

        "plan_finished" -> ServerEvent.PlanFinished(
            seq, o.str("status"),
            o["succeeded"]?.jsonPrimitive?.intOrNull,
            o["failed"]?.jsonPrimitive?.intOrNull,
            o["skipped"]?.jsonPrimitive?.intOrNull,
            o.longOrNull("durationMs"),
        )

        "mode_switched" -> ServerEvent.ModeSwitched(seq, o.str("reason"))

        "team_started" -> ServerEvent.TeamStarted(
            seq, o.str("goal"), o.str("reason"), o.objects("seats").map { teamSeat(it) },
        )

        "team_seats_added" -> ServerEvent.TeamSeatsAdded(seq, o.objects("seats").map { teamSeat(it) })
        "team_seat_started" -> ServerEvent.TeamSeatStarted(seq, o.str("seatId"))
        "team_seat_finished" -> ServerEvent.TeamSeatFinished(
            seq, o.str("seatId"), o.str("status"), o.long("durationMs"),
            o.strOrNull("opinion"), o.strOrNull("error"),
        )

        "team_finished" -> ServerEvent.TeamFinished(
            seq, o.str("status"), o.int("spoke"), o.int("silent"), o.strOrNull("verdict"),
        )

        "replay_gap" -> ServerEvent.ReplayGap(seq, o.long("from"), o.long("to"))

        // 协议第 2 节：不认识的 t 静默忽略，不报错、不断连
        else -> null
    }
}

package dev.wiles.myagent.client.state

import dev.wiles.myagent.client.protocol.ApprovalSuggestion
import dev.wiles.myagent.client.protocol.Continuable
import dev.wiles.myagent.client.protocol.Interrupted
import dev.wiles.myagent.client.protocol.MemoryRow
import dev.wiles.myagent.client.protocol.RoleInfo
import dev.wiles.myagent.client.protocol.SessionRow
import dev.wiles.myagent.client.protocol.SkillInfo
import dev.wiles.myagent.client.protocol.ToolInfo

/**
 * 界面自己的数据形态，和 `tui/src/store.ts` 的 Entry 模型一一对应——
 * 两个客户端对同一串事件必须长出同一份转录，不然查协议 bug 时两边各说各话。
 */

data class ToolCall(
    val id: Int,
    val tool: String,
    val args: String,
    val running: Boolean,
    val ok: Boolean? = null,
    val output: String? = null,
    /** 改了什么（统一 diff）。只有写文件这类工具才有 */
    val diff: String? = null,
)

sealed interface Entry {
    val key: String

    data class User(override val key: String, val text: String) : Entry
    data class Assistant(override val key: String, val text: String) : Entry
    data class Thought(override val key: String, val text: String) : Entry
    data class Tools(override val key: String, val calls: List<ToolCall>) : Entry
    data class Notice(override val key: String, val level: Level, val text: String) : Entry {
        enum class Level { INFO, WARN, ERROR }
    }

    data class Compacting(
        override val key: String, val phase: String, val done: Int, val total: Int, val percent: Int,
    ) : Entry

    data class Compacted(
        override val key: String, val before: Long, val after: Long, val summarized: Boolean,
    ) : Entry

    data class Memory(override val key: String, val rows: List<MemoryRow>) : Entry
}

/**
 * plan 模式的一道确认（协议 plan_confirm_request）。
 * [kind] = enter：模型提议先进 plan 模式；exit：模型交了计划 [plan]（Markdown），等批准。
 */
data class PlanConfirm(val id: Int, val kind: String, val plan: String)

data class Approval(
    val id: Int,
    /** bash | write。界面按它决定 preview 怎么画 */
    val kind: String,
    val action: String,
    val detail: String,
    val reason: String,
    /** 写文件时的 diff 正文，空串表示没有 */
    val preview: String,
    /** 「编码者 · t3」，空串表示就是用户刚说的那句话 */
    val origin: String,
    /** 「以后都同意」可选的几档松紧，从紧到松。空 = 这次记不成规则 */
    val suggestions: List<ApprovalSuggestion>,
    val preferred: Int,
)

data class ReadyInfo(
    val version: String,
    val workspace: String,
    val model: String,
    val planMode: Boolean,
    val hasApiKey: Boolean,
    val tools: List<ToolInfo>,
    val roles: List<RoleInfo>,
    val features: List<String>,
    val branch: String?,
    val continuable: Continuable?,
    val skills: List<SkillInfo> = emptyList(),
)

/**
 * 会话模式。状态在**服务端**：切换发 set_mode，以服务端广播回来的 `mode` 为准——
 * 模型也能提议进 plan 模式、交了计划被批准后自己退出，界面只是跟着显示。
 */
enum class Mode {
    NORMAL, PLAN;

    val wire: String get() = if (this == PLAN) "plan" else "normal"

    companion object {
        fun of(wire: String) = if (wire == "plan") PLAN else NORMAL
    }
}

/** 连接生命周期。手机和终端不同：断了不等于结束，automatic 重连是常态。 */
enum class Link { CONNECTING, CONNECTED, RECONNECTING, CLOSED }

data class SessionState(
    val ready: ReadyInfo? = null,
    /** 初始是 CLOSED（还没配对），connect() 才进 CONNECTING——首屏该停在连接页 */
    val link: Link = Link.CLOSED,
    /** link=CLOSED 时的原因 */
    val closedReason: String? = null,
    val entries: List<Entry> = emptyList(),
    val busy: Boolean = false,
    val step: Int = 0,
    val approval: Approval? = null,
    val planConfirm: PlanConfirm? = null,
    val mode: Mode = Mode.NORMAL,
    val tokens: Long = 0,
    /** 累计真正发出去的提示词，只增不减（协议 3.10） */
    val sentTokens: Long = 0,
    val contextLimit: Long = 0,
    val lastDurationMs: Long? = null,
    /** 会话选择器数据。null = 没开。不缓存，每次都重新问（同 TUI） */
    val sessionPicker: Pair<String, List<SessionRow>>? = null,
    /**
     * 主对话断在了半路，说一句「继续」就能接上。null = 没有可续的（同 TUI）。
     * 下一句话一跑起来（busy 变真）就作废。
     */
    val interrupted: Interrupted? = null,
)

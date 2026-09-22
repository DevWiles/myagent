package dev.wiles.myagent.client.state

import dev.wiles.myagent.client.protocol.ApprovalSuggestion
import dev.wiles.myagent.client.protocol.Continuable
import dev.wiles.myagent.client.protocol.MemoryRow
import dev.wiles.myagent.client.protocol.Resumable
import dev.wiles.myagent.client.protocol.RoleInfo
import dev.wiles.myagent.client.protocol.SessionRow
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

data class PlanTask(
    val id: String,
    val type: String,
    val role: String?,
    val roleTitle: String?,
    val description: String,
    val acceptance: String?,
    /** 失败后补出来的救场任务，面板上要和原计划区分开 */
    val recovery: Boolean = false,
    /** PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED */
    val status: String,
    val parentId: String?,
    val durationMs: Long? = null,
    val error: String? = null,
)

data class PlanSummary(val succeeded: Int, val failed: Int, val skipped: Int, val durationMs: Long)

data class PlanData(
    val goal: String,
    val tasks: List<PlanTask>,
    /** CREATED | RUNNING | COMPLETED | FAILED | CANCELLED */
    val status: String,
    val summary: PlanSummary? = null,
)

data class TeamSeat(
    val id: String,
    val role: String,
    val roleTitle: String,
    val summary: String,
    val status: String,
    /** 这道工序的定稿。流式那份混在一起，这份归到它名下 */
    val opinion: String? = null,
    val error: String? = null,
    val durationMs: Long? = null,
)

data class TeamData(
    val goal: String,
    /** 分工说明。花钱之前用户唯一一眼，要显眼 */
    val reason: String,
    val seats: List<TeamSeat>,
    /** CREATED | RUNNING | COMPLETED | PARTIAL | FAILED | CANCELLED */
    val status: String,
    /** PARTIAL 时非空是打回没修完（问题清单在里面），为空是验收没跑成——必须分开画 */
    val verdict: String? = null,
    val spoke: Int? = null,
    val silent: Int? = null,
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
    data class Plan(override val key: String, val plan: PlanData) : Entry
    data class Team(override val key: String, val team: TeamData) : Entry
}

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
    val resumable: Resumable?,
    val continuable: Continuable?,
)

/** 下一句话按什么方式跑。跨轮保持——模式是用户表过的态（同 TUI）。 */
enum class Mode { NORMAL, PLAN, TEAM }

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
    val planConfirm: Int? = null,
    val mode: Mode = Mode.NORMAL,
    val tokens: Long = 0,
    /** 累计真正发出去的提示词，只增不减（协议 3.10） */
    val sentTokens: Long = 0,
    val contextLimit: Long = 0,
    val lastDurationMs: Long? = null,
    /** 会话选择器数据。null = 没开。不缓存，每次都重新问（同 TUI） */
    val sessionPicker: Pair<String, List<SessionRow>>? = null,
)

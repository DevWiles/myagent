package dev.wiles.myagent.client.state

import dev.wiles.myagent.client.protocol.PlanTaskWire
import dev.wiles.myagent.client.protocol.ServerEvent
import dev.wiles.myagent.client.protocol.TeamSeatWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 事件 → 界面状态的 reducer，逐条对齐 `tui/src/store.ts` 的 apply()。
 *
 * <p>两个客户端对同一串事件必须长出同一份转录——这不是洁癖：查协议 bug 时
 * 「终端上对、手机上错」和「两边都错」是完全不同的两种排查路径，
 * 语义漂移会把前者伪装成后者。
 *
 * <p>StateFlow 天然合并背压：text_delta 一秒几十条，Compose 端收集到的
 * 是最新快照，不需要 TUI 那套 33ms 尾沿节流。
 */
class SessionStore {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var keySeq = 0
    private fun nextKey(prefix: String) = "$prefix-${++keySeq}"

    /**
     * 本连接的 id，随机、每次进程启动重新生成（协议 3.15）。
     * 发命令时带上，收到自己那条 user_message 回显就跳过——本地已经即时回显过了。
     * **不持久化**：持久化的话，重启后自己以前说过的话会被当成「本地已有」而整段消失。
     */
    val clientId: String = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    /** 当前那根压缩进度条 / 计划面板 / 流水线面板的 key。语义同 TUI（多半不在队尾）。 */
    private var compactingKey: String? = null
    private var planKey: String? = null
    private var teamKey: String? = null

    // ------------------------------------------------------------ 用户动作

    fun submitPrompt(text: String) = _state.update {
        it.copy(entries = it.entries + Entry.User(nextKey("user"), text), step = 0)
    }

    fun notice(level: Entry.Notice.Level, text: String) = _state.update {
        it.copy(entries = it.entries + Entry.Notice(nextKey("notice"), level, text))
    }

    fun setMode(mode: Mode) = _state.update { it.copy(mode = mode) }

    /** 回答完审批（发送由调用方负责），当场收框——工具跑得久时别让人对着答过的框发呆。 */
    fun clearApproval() = _state.update { it.copy(approval = null) }

    fun clearPlanConfirm() = _state.update { it.copy(planConfirm = null) }

    fun closeSessionPicker() = _state.update { it.copy(sessionPicker = null) }

    fun setLink(link: Link, reason: String? = null) = _state.update {
        // 断线时审批框收掉：后端那头等不到回复会自己超时/取消，
        // 留一个答了也发不出去的框比没有框更糟
        if (link == Link.CONNECTED) it.copy(link = link, closedReason = null)
        else it.copy(link = link, closedReason = reason, approval = null, planConfirm = null)
    }

    // ------------------------------------------------------------ 后端事件

    fun apply(event: ServerEvent) {
        when (event) {
            is ServerEvent.Ready -> _state.update {
                it.copy(
                    ready = ReadyInfo(
                        version = event.version,
                        workspace = event.workspace,
                        model = event.model,
                        planMode = event.planMode,
                        hasApiKey = event.hasApiKey,
                        tools = event.tools,
                        roles = event.roles,
                        features = event.features,
                        branch = event.branch,
                        resumable = event.resumable,
                        continuable = event.continuable,
                    ),
                    link = Link.CONNECTED,
                    closedReason = null,
                    busy = event.busy,
                    tokens = event.tokens,
                    sentTokens = event.sentTokens,
                    contextLimit = event.contextLimit,
                )
            }.also {
                val ready = event
                if (!ready.hasApiKey) notice(
                    Entry.Notice.Level.ERROR,
                    "后端没有配置 API key，无法对话。在电脑上设置 DEEPSEEK_API_KEY 后重启后端",
                )
                ready.workspaceWarning?.let { warn -> notice(Entry.Notice.Level.WARN, warn) }
            }

            is ServerEvent.Thinking -> _state.update { it.copy(step = event.step) }

            is ServerEvent.Thought -> _state.update { st ->
                // 同一句话常常既从 text_delta 流出来、又作为 Thought 发一遍——
                // 流式已经打过就别打第二遍；非流式后端只发 Thought，那时它是唯一来源（同 TUI）
                val tail = st.entries.lastOrNull()
                val already = tail is Entry.Assistant && tail.text.trim().contains(event.text.trim())
                if (already) st
                else st.copy(entries = st.entries + Entry.Thought(nextKey("thought"), event.text))
            }

            is ServerEvent.TextDelta -> _state.update { st ->
                val tail = st.entries.lastOrNull()
                if (tail is Entry.Assistant) {
                    st.copy(entries = st.entries.dropLast(1) + tail.copy(text = tail.text + event.text))
                } else {
                    st.copy(entries = st.entries + Entry.Assistant(nextKey("text"), event.text))
                }
            }

            is ServerEvent.ToolStarted -> _state.update { st ->
                val call = ToolCall(event.id, event.tool, event.arguments, running = true)
                val tail = st.entries.lastOrNull()
                // 连着调用的工具归同一组，界面上收成一行（同 TUI）
                if (tail is Entry.Tools) {
                    st.copy(entries = st.entries.dropLast(1) + tail.copy(calls = tail.calls + call))
                } else {
                    st.copy(entries = st.entries + Entry.Tools(nextKey("tools"), listOf(call)))
                }
            }

            is ServerEvent.ToolFinished -> _state.update { st ->
                val tail = st.entries.lastOrNull()
                if (tail !is Entry.Tools) st
                else st.copy(entries = st.entries.dropLast(1) + tail.copy(
                    calls = tail.calls.map { call ->
                        if (call.id == event.id) call.copy(
                            running = false, ok = event.ok, output = event.output, diff = event.diff,
                        ) else call
                    },
                ))
            }

            is ServerEvent.Compacting ->
                if (event.phase == "done") clearCompacting()
                else upsertCompacting(event.phase, event.done, event.total, event.percent)

            is ServerEvent.Compacted -> {
                // 条先撤再落结论，不然屏幕上同时留着 99% 的条和「已压缩」那行（同 TUI）
                clearCompacting()
                _state.update {
                    it.copy(entries = it.entries + Entry.Compacted(
                        nextKey("compact"), event.tokensBefore, event.tokensAfter, event.summarized,
                    ))
                }
            }

            is ServerEvent.MemoryList -> _state.update {
                it.copy(entries = it.entries + Entry.Memory(nextKey("memory"), event.entries))
            }

            is ServerEvent.MemoryChanged -> notice(
                Entry.Notice.Level.INFO,
                when {
                    event.action == "undo" ->
                        "已撤销自动记下的 ${event.name ?: "(未知)"}" +
                            (event.description?.let { " — $it" } ?: "")

                    event.removed == 0 -> "没有自动记录可清。"
                    else -> "已清掉 ${event.removed} 条自动记录，你自己让记的一条没动。"
                },
            )

            is ServerEvent.Done -> _state.update { st ->
                // 流式没走通时用 done 的全文兜底，否则这一轮一个字都不显示（同 TUI）
                val tail = st.entries.lastOrNull()
                if (tail is Entry.Assistant || event.text.isBlank()) st
                else st.copy(entries = st.entries + Entry.Assistant(nextKey("text"), event.text))
            }

            is ServerEvent.Failed -> notice(Entry.Notice.Level.ERROR, event.reason)

            is ServerEvent.ApprovalRequest -> _state.update {
                it.copy(approval = Approval(
                    id = event.id,
                    kind = event.kind,
                    action = event.action,
                    detail = event.detail,
                    reason = event.reason,
                    preview = event.preview,
                    origin = event.origin,
                    suggestions = event.suggestions,
                    preferred = event.preferred,
                ))
            }

            is ServerEvent.Busy -> {
                // 兜底：compacting done 那条事件丢了（断线重连）的话条会永远走不完（同 TUI）
                if (!event.busy) clearCompacting()
                _state.update {
                    it.copy(
                        busy = event.busy,
                        approval = if (event.busy) it.approval else null,
                        planConfirm = if (event.busy) it.planConfirm else null,
                        step = if (event.busy) it.step else 0,
                        tokens = event.tokens ?: it.tokens,
                        sentTokens = event.sentTokens ?: it.sentTokens,
                        contextLimit = event.contextLimit ?: it.contextLimit,
                        lastDurationMs = event.durationMs ?: it.lastDurationMs,
                    )
                }
            }

            is ServerEvent.UserMessage -> {
                // 只画别的界面说的话；自己那条在按发送时就画过了
                if (event.cid != clientId) {
                    _state.update {
                        it.copy(entries = it.entries + Entry.User(nextKey("user"), event.text))
                    }
                }
            }

            is ServerEvent.ResetDone -> {
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        step = 0,
                        // 后端 reset 后 continuable 入口该收（同 TUI clearContinuable）
                        ready = it.ready?.copy(continuable = null),
                    )
                }
                notice(Entry.Notice.Level.INFO, "对话已清空")
            }

            is ServerEvent.ErrorEvent -> notice(Entry.Notice.Level.ERROR, event.message)

            is ServerEvent.ModeSwitched -> notice(
                Entry.Notice.Level.INFO,
                if (event.reason.isNotBlank()) "${event.reason}，先拆个计划" else "这件事先拆个计划",
            )

            is ServerEvent.SessionList -> _state.update {
                it.copy(sessionPicker = event.currentId to event.sessions)
            }

            is ServerEvent.SessionRestored -> {
                _state.update {
                    it.copy(
                        ready = it.ready?.copy(continuable = null),
                        sessionPicker = null,
                        tokens = event.tokens ?: it.tokens,
                        sentTokens = event.sentTokens ?: it.sentTokens,
                        contextLimit = event.contextLimit ?: it.contextLimit,
                    )
                }
                notice(
                    Entry.Notice.Level.INFO,
                    "接上了上次的对话（${event.turns} 轮，${event.messages} 条消息）。" +
                        "上次首句：${event.firstMessage}",
                )
            }

            is ServerEvent.PlanCreated -> {
                val key = nextKey("plan")
                planKey = key
                _state.update {
                    it.copy(
                        ready = it.ready?.copy(resumable = null),
                        entries = it.entries + Entry.Plan(key, PlanData(
                            goal = event.goal,
                            tasks = event.tasks.map { t -> fromWire(t) },
                            status = "CREATED",
                        )),
                    )
                }
            }

            is ServerEvent.PlanConfirmRequest -> _state.update { it.copy(planConfirm = event.id) }

            is ServerEvent.PlanTaskStarted -> updatePlan { plan ->
                plan.copy(
                    status = "RUNNING",
                    tasks = plan.tasks.map { t ->
                        if (t.id == event.taskId) t.copy(status = "RUNNING") else t
                    },
                )
            }

            is ServerEvent.PlanTaskFinished -> updatePlan { plan ->
                plan.copy(tasks = plan.tasks.map { t ->
                    if (t.id == event.taskId) t.copy(
                        status = event.status, durationMs = event.durationMs, error = event.error,
                    ) else t
                })
            }

            is ServerEvent.PlanTasksAdded -> updatePlan { plan ->
                // 就地插到父任务（和先到的兄弟）后面，别 push 到末尾（同 TUI）
                val tasks = plan.tasks.toMutableList()
                var at = tasks.indexOfFirst { it.id == event.parentId }
                if (at < 0) {
                    tasks += event.tasks.map { fromWire(it) }
                } else {
                    while (at + 1 < tasks.size && tasks[at + 1].parentId == event.parentId) at++
                    tasks.addAll(at + 1, event.tasks.map { fromWire(it) })
                }
                plan.copy(tasks = tasks)
            }

            is ServerEvent.PlanRevised -> updatePlan { plan ->
                // 改道：补救任务接在失败那一步后面，不甩到末尾（同 TUI）
                val tasks = plan.tasks.toMutableList()
                val at = tasks.indexOfFirst { it.id == event.failedTaskId }
                val recovered = event.tasks.map { fromWire(it, recovery = true) }
                if (at < 0) tasks += recovered else tasks.addAll(at + 1, recovered)
                plan.copy(tasks = tasks)
            }

            is ServerEvent.PlanFinished -> {
                updatePlan { plan ->
                    plan.copy(
                        status = event.status,
                        summary = event.succeeded?.let {
                            PlanSummary(it, event.failed ?: 0, event.skipped ?: 0, event.durationMs ?: 0)
                        },
                    )
                }
                _state.update { it.copy(planConfirm = null) }
            }

            is ServerEvent.TeamStarted -> {
                val key = nextKey("team")
                teamKey = key
                _state.update {
                    it.copy(entries = it.entries + Entry.Team(key, TeamData(
                        goal = event.goal,
                        reason = event.reason,
                        seats = event.seats.map { s -> fromWire(s) },
                        status = "CREATED",
                    )))
                }
            }

            is ServerEvent.TeamSeatsAdded -> updateTeam { team ->
                team.copy(seats = team.seats + event.seats.map { fromWire(it) })
            }

            is ServerEvent.TeamSeatStarted -> updateTeam { team ->
                team.copy(
                    status = "RUNNING",
                    seats = team.seats.map { s ->
                        if (s.id == event.seatId) s.copy(status = "RUNNING") else s
                    },
                )
            }

            is ServerEvent.TeamSeatFinished -> updateTeam { team ->
                team.copy(seats = team.seats.map { s ->
                    if (s.id == event.seatId) s.copy(
                        status = event.status, opinion = event.opinion,
                        error = event.error, durationMs = event.durationMs,
                    ) else s
                })
            }

            is ServerEvent.TeamFinished -> updateTeam { team ->
                team.copy(
                    status = event.status, verdict = event.verdict,
                    spoke = event.spoke, silent = event.silent,
                )
            }

            is ServerEvent.ReplayGap -> notice(
                Entry.Notice.Level.WARN,
                "断线期间有 ${event.to - event.from + 1} 条事件补不回来了（${event.from}〜${event.to}）。" +
                    "转录从这儿往上可能不完整",
            )

            is ServerEvent.Pong -> {}
        }
    }

    // ------------------------------------------------------------ 面板原地更新

    private fun updatePlan(update: (PlanData) -> PlanData) {
        val key = planKey ?: return
        _state.update { st ->
            st.copy(entries = st.entries.map { entry ->
                if (entry is Entry.Plan && entry.key == key) entry.copy(plan = update(entry.plan))
                else entry
            })
        }
    }

    private fun updateTeam(update: (TeamData) -> TeamData) {
        val key = teamKey ?: return
        _state.update { st ->
            st.copy(entries = st.entries.map { entry ->
                if (entry is Entry.Team && entry.key == key) entry.copy(team = update(entry.team))
                else entry
            })
        }
    }

    /** 进度条第一条建、后面的原地改——不然转录区被几百根条刷爆（同 TUI）。 */
    private fun upsertCompacting(phase: String, done: Int, total: Int, percent: Int) {
        val key = compactingKey
        if (key == null) {
            val fresh = nextKey("compacting")
            compactingKey = fresh
            _state.update {
                it.copy(entries = it.entries + Entry.Compacting(fresh, phase, done, total, percent))
            }
            return
        }
        _state.update { st ->
            st.copy(entries = st.entries.map { entry ->
                if (entry is Entry.Compacting && entry.key == key) {
                    entry.copy(phase = phase, done = done, total = total, percent = percent)
                } else entry
            })
        }
    }

    /** 整条移走不留痕——凝固在 62% 的条只会让人以为还在跑（同 TUI）。 */
    private fun clearCompacting() {
        val key = compactingKey ?: return
        compactingKey = null
        _state.update { st -> st.copy(entries = st.entries.filter { it.key != key }) }
    }

    private fun fromWire(task: PlanTaskWire, recovery: Boolean = false) = PlanTask(
        id = task.id,
        type = task.type,
        role = task.role,
        roleTitle = task.roleTitle,
        description = task.description,
        acceptance = task.acceptance,
        recovery = recovery,
        status = task.status,
        parentId = task.parentId,
    )

    private fun fromWire(seat: TeamSeatWire) = TeamSeat(
        id = seat.id,
        role = seat.role,
        roleTitle = seat.roleTitle,
        summary = seat.summary,
        status = seat.status,
    )
}

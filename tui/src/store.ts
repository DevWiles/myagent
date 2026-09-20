/**
 * 会话状态。
 *
 * 用外部 store + useSyncExternalStore 而不是把 state 堆在 App 里，理由有两个：
 * 1. 事件来自 WebSocket 回调，不在 React 的调度里；外部 store 让「谁写」和「谁读」彻底分开
 * 2. text_delta 是按字符级别来的，一秒几十次；只有订阅了对应切片的组件才该被叫醒
 *
 * ## 渲染模型：全屏（备用屏幕）+ 自绘滚动
 *
 * transcript 是一个数组，全部生存在备用屏幕里，由 TranscriptView 自己做视口裁剪。
 * 这样每一条消息——包括很早以前的——都还是活的 React 元素，可以被展开/收起，
 * 将来接管鼠标后也能被点中。inline + <Static> 模式下历史会被冻进 scrollback、
 * 无法再交互，两者本质冲突，所以选了全屏。
 *
 * 退出后这段对话**不会**留在终端里——它随备用屏一起消失，只有你启动前的命令
 * 历史还在。这是刻意的（和 vim 一样），想留档就用 `--no-fullscreen`。
 */

import type { MemoryRow, PlanTaskWire, RoleInfo, ServerEvent, ToolInfo } from './protocol.ts'
import type { SessionRow } from './session-picker.ts'

export type ToolCall = {
  id: number
  tool: string
  args: string
  running: boolean
  ok?: boolean
  output?: string
}

/** 计划里的一个任务 + 它的执行进度。界面自己的形态，不直接用线上类型。 */
export type PlanTask = {
  id: string
  type: string
  /** 派给这一步的角色 id。老后端不发这个字段，那就只有 type 可显示。 */
  role?: string
  /** 角色的中文名，后端给的。界面不自己按 id 映射——两边措辞会漂。 */
  roleTitle?: string
  description: string
  /** 做到什么算完。可能没有——老计划里没这个字段。 */
  acceptance?: string
  /** 这一步是失败后补出来的救场任务。面板上要和原计划的步骤区分开。 */
  recovery?: boolean
  /** PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED */
  status: string
  parentId: string | null
  dependsOn: string[]
  durationMs?: number
  error?: string
}

/** 圆桌上的一席 + 它的进度。 */
export type TeamSeat = {
  id: string
  role: string
  roleTitle: string
  summary: string
  /** PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED */
  status: string
  /** 这一席的定稿。流式那份是混在一起的，这份是归到它名下的 */
  opinion?: string
  error?: string
  durationMs?: number
}

/**
 * 一场圆桌的完整状态，和计划一样作为一条 entry 活在 transcript 里。
 *
 * <p>`status` 里 **PARTIAL 必须画出来**：少了一席的合议，和全员到齐的合议
 * 长得一模一样——不标出来，用户会把两个人的共识当成三个人的共识。
 */
export type TeamData = {
  goal: string
  /** 为什么是这几位。花钱之前用户唯一一眼，要显眼 */
  reason: string
  seats: TeamSeat[]
  /** CREATED | RUNNING | COMPLETED | PARTIAL | FAILED | CANCELLED */
  status: string
  /** 正在合议。合议没有自己的席位，它是收口那一步 */
  synthesizing?: boolean
  /** 合议正文。取消和失败时没有——没有结论就别画一个出来 */
  verdict?: string
  spoke?: number
  silent?: number
}

export type PlanSummary = {
  succeeded: number
  failed: number
  skipped: number
  durationMs: number
}

/**
 * 一份计划的完整状态，作为一条 entry 活在 transcript 里。
 *
 * 为什么进 transcript 而不是钉在底部：计划面板要能往回滚、能和执行过程的
 * 流式输出交错着看，这正是「transcript 里全是活的 React 元素」这个渲染模型
 * 的用武之地。执行期间它被原地更新（✓/✗ 逐个点亮），滚上去随时能看进度。
 */
export type PlanData = {
  goal: string
  /** 服务端顺序；plan_tasks_added 的子任务插在父任务（和先来的兄弟）后面。 */
  tasks: PlanTask[]
  /** 拓扑分层，服务端算好的。只含创建时的根任务。 */
  layers: string[][]
  /** CREATED | RUNNING | COMPLETED | FAILED | CANCELLED */
  status: string
  summary?: PlanSummary
}

export type Entry =
  | { kind: 'user'; key: string; text: string }
  | { kind: 'assistant'; key: string; text: string }
  | { kind: 'thought'; key: string; text: string }
  | { kind: 'tools'; key: string; calls: ToolCall[] }
  | { kind: 'notice'; key: string; level: 'info' | 'warn' | 'error'; text: string }
  | { kind: 'compacting'; key: string; phase: 'map' | 'reduce'; done: number; total: number; percent: number }
  | { kind: 'compacted'; key: string; before: number; after: number; summarized: boolean }
  | { kind: 'memory'; key: string; rows: MemoryRow[] }
  | { kind: 'plan'; key: string; plan: PlanData }
  | { kind: 'team'; key: string; team: TeamData }

export type Approval = { id: number; action: string; detail: string; reason: string }

/**
 * 下一句话按什么方式跑。
 *
 * <p>这是 `/plan`、`/team` 两条斜杠命令的替代品：**同一句话，模式决定它怎么被处理**，
 * 而不是让用户在每句话前面手打一个命令名。好处不只是少敲几个字——
 * 模式是**看得见的状态**（左下角常驻一行），敲命令是转瞬即逝的动作，
 * 用户不必记住「我刚才有没有加 /plan」。
 */
export type Mode = 'normal' | 'plan' | 'team'

/** 切换顺序。normal 打头：最常用的排第一，Shift+Tab 一圈回到它。 */
export const MODE_CYCLE: readonly Mode[] = ['normal', 'plan', 'team']

/**
 * 这个模式要后端宣告哪个能力。normal 谁都能跑，所以是 null。
 *
 * <p>没宣告的模式<b>在切换时直接跳过</b>，不是切进去再报错：
 * 一个进得去、一用就说「后端不支持」的模式，比压根切不进去更让人困惑。
 */
const MODE_FEATURE: Record<Mode, string | null> = {
  normal: null,
  plan: 'plan',
  team: 'team',
}

export function modeIsAvailable(mode: Mode, features: readonly string[]): boolean {
  const needed = MODE_FEATURE[mode]
  return needed === null || features.includes(needed)
}

/**
 * 下一个可用的模式。
 *
 * <p>一圈都不可用时返回原地——后端只认基础协议时，Shift+Tab 就是个空操作，
 * 而不是把用户卡在一个跑不了的模式上。
 */
export function nextMode(current: Mode, features: readonly string[]): Mode {
  const start = MODE_CYCLE.indexOf(current)
  for (let step = 1; step <= MODE_CYCLE.length; step++) {
    const candidate = MODE_CYCLE[(start + step) % MODE_CYCLE.length]
    if (modeIsAvailable(candidate, features)) return candidate
  }
  return current
}

export type ReadyInfo = {
  version: string
  workspace: string
  model: string
  planMode: boolean
  hasApiKey: boolean
  tools: ToolInfo[]
  /** 后端的角色名册。老后端不发，那就是空数组。 */
  roles: RoleInfo[]
  /** 后端宣告的可选能力。没宣告的模式 Shift+Tab 会跳过（见 {@link nextMode}）。 */
  features: string[]
  workspaceWarning?: string
  /** 审计日志的位置。退出时的收尾信息要用它。 */
  auditLog?: string
  /** 工作区真正的 git 分支（不在仓库里就是 null）。状态栏显示它，不再拿目录名冒充。 */
  branch?: string | null
  /** 上次没跑完的计划。没有就是 undefined。 */
  resumable?: { goal: string; remaining: number; total: number; savedAt: number }
  /** 上次没聊完的对话（最近那一段）。和 resumable 是两件事：那个是计划，这个是对话。 */
  continuable?: {
    id: string
    branch?: string | null
    turns: number
    messages: number
    firstMessage: string
    savedAt: number
    /** 这个工作区一共几段会话。界面靠它决定要不要放出「挑一段」的入口。 */
    count: number
  }
}

export type SessionState = {
  ready: ReadyInfo | null
  /** 全部对话记录。渲染层负责视口裁剪，这里不关心屏幕。 */
  entries: Entry[]
  /** 展开了工具详情的 entry key。任何一条都可以，包括很早以前的。 */
  expanded: ReadonlySet<string>
  busy: boolean
  /** 当前跑到第几轮（ReAct step）。 */
  step: number
  approval: Approval | null
  /** 待回答的计划确认。和 approval 一样：后端此刻阻塞在等回答上。 */
  planConfirm: { id: number } | null
  /**
   * 底部那块计划面板该显示哪份计划，指向 entries 里的某条 plan。
   *
   * **存 key 而不是存一份 PlanData**：计划在 transcript 里已经有一份了，
   * 再复制一份到 state 上，两处各更新各的，迟早对不上——而这种不一致的表现是
   * 「面板上还是 PENDING，往上滚那份已经打勾了」，看着像 bug 其实是设计错了。
   *
   * 生命周期：plan_created 时指上，**用户发下一句话时清掉**。
   * 所以跑完的计划会一直留在屏幕底下，不会刚看完就消失。
   */
  /**
   * 下一句话按什么方式跑。**跨轮保持**——模式是用户表过的态，
   * 不该因为跑完一轮就自己弹回去；那样他每说一句话都得重按一次。
   */
  mode: Mode
  activePlanKey: string | null
  /** 当前圆桌面板的 key。和计划一样，用户说下一句话时让位 */
  activeTeamKey: string | null
  tokens: number
  /** 累计真正发出去的提示词。只增不减，压缩不会让它变小。 */
  sentTokens: number
  contextLimit: number
  /** 上一轮耗时，画在状态栏上。 */
  lastDurationMs: number | null
  startedAt: number | null
  /** 非空表示后端没了，界面该退出。 */
  closed: string | null
  /**
   * 会话选择器的数据。null = 面板没开。
   *
   * <p>**不缓存**：每次 /resume 都重新问一次后端。会话列表随时在变
   * （另一个界面可能刚聊了一轮、刚清过一段），拿着上次的列表让人挑，
   * 挑中的那个 id 可能已经不在了。
   */
  sessionPicker: { currentId: string; sessions: SessionRow[] } | null
}

const EMPTY_STATE: SessionState = {
  ready: null,
  entries: [],
  expanded: new Set(),
  busy: false,
  step: 0,
  approval: null,
  planConfirm: null,
  mode: 'normal',
  activePlanKey: null,
  activeTeamKey: null,
  tokens: 0,
  sentTokens: 0,
  contextLimit: 0,
  lastDurationMs: null,
  startedAt: null,
  closed: null,
  sessionPicker: null,
}

export class SessionStore {
  private state: SessionState = EMPTY_STATE
  private readonly listeners = new Set<() => void>()
  private seq = 0

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = (): SessionState => this.state

  private set(patch: Partial<SessionState>): void {
    this.state = { ...this.state, ...patch }
    for (const listener of this.listeners) listener()
  }

  private nextKey(prefix: string): string {
    return `${prefix}-${++this.seq}`
  }

  /** 最后一条。事件要不要接在它后面，全看它是什么。 */
  private tail(): Entry | undefined {
    return this.state.entries[this.state.entries.length - 1]
  }

  private append(entry: Entry): void {
    this.set({ entries: [...this.state.entries, entry] })
  }

  /** 替换最后一条（原地更新流式文本、回填工具结果）。 */
  private replaceTail(entry: Entry): void {
    const next = this.state.entries.slice(0, -1)
    next.push(entry)
    this.set({ entries: next })
  }

  /**
   * 当前那根压缩进度条的 key。
   *
   * 不用 replaceTail：压缩发生在 FORCE 档时是**一轮对话中途**的事，
   * 它后面随时会接上流式文本、工具组，条早就不在队尾了。
   */
  private compactingKey: string | null = null

  /**
   * 建一条进度、或者原地更新它。
   *
   * 进度事件一秒能来十几条，每条都 append 的话转录区会被几百根条刷爆——
   * 所以第一条建，后面的都改同一条。
   */
  private upsertCompacting(
    phase: 'map' | 'reduce',
    done: number,
    total: number,
    percent: number,
  ): void {
    const key = this.compactingKey
    if (key === null) {
      const fresh = this.nextKey('compacting')
      this.compactingKey = fresh
      this.append({ kind: 'compacting', key: fresh, phase, done, total, percent })
      return
    }
    this.set({
      entries: this.state.entries.map(entry =>
        entry.kind === 'compacting' && entry.key === key
          ? { ...entry, phase, done, total, percent }
          : entry,
      ),
    })
  }

  /**
   * 把进度条整条移走，不留痕。
   *
   * 留一根走完的条在转录里没有意义——结论由紧随其后的 `compacted` 那行给，
   * 而压缩被取消或者失败时，一根凝固在 62% 的条只会让人以为还在跑。
   */
  private clearCompacting(): void {
    const key = this.compactingKey
    if (key === null) return
    this.compactingKey = null
    this.set({ entries: this.state.entries.filter(entry => entry.key !== key) })
  }

  /** 当前计划面板的 key。执行期间流式输出会排在它后面，所以不能用 replaceTail。 */
  private planKey: string | null = null

  /** 原地改当前计划面板。计划面板多半不在队尾——按 key 找，不管它滚到哪儿了。 */
  private updatePlan(update: (plan: PlanData) => PlanData): void {
    const key = this.planKey
    if (!key) return
    this.set({
      entries: this.state.entries.map(entry =>
        entry.kind === 'plan' && entry.key === key
          ? { ...entry, plan: update(entry.plan) }
          : entry,
      ),
    })
  }

  /** 当前圆桌面板的 key。和 planKey 一个道理：面板多半不在队尾。 */
  private teamKey: string | null = null

  private updateTeam(update: (team: TeamData) => TeamData): void {
    const key = this.teamKey
    if (!key) return
    this.set({
      entries: this.state.entries.map(entry =>
        entry.kind === 'team' && entry.key === key
          ? { ...entry, team: update(entry.team) }
          : entry,
      ),
    })
  }

  private patchSeat(seatId: string, patch: Partial<TeamSeat>): void {
    this.updateTeam(team => ({
      ...team,
      seats: team.seats.map(seat => (seat.id === seatId ? { ...seat, ...patch } : seat)),
    }))
  }

  private patchTask(taskId: string, patch: Partial<PlanTask>): void {
    this.updatePlan(plan => ({
      ...plan,
      tasks: plan.tasks.map(task => (task.id === taskId ? { ...task, ...patch } : task)),
    }))
  }

  // ------------------------------------------------------------ 用户动作

  /**
   * 切到下一个模式（Shift+Tab）。
   *
   * <p><b>正忙时也允许切</b>：模式在<b>提交那一刻</b>才起作用，提前切好是很自然的用法
   * ——这一轮还在跑，我已经想好下一句要走规划。拦住它只会让人以为按键失灵。
   */
  cycleMode(): void {
    this.set({ mode: nextMode(this.state.mode, this.state.ready?.features ?? []) })
  }

  submitPrompt(text: string): void {
    this.set({
      entries: [...this.state.entries, { kind: 'user', key: this.nextKey('user'), text }],
      step: 0,
      startedAt: Date.now(),
      // 说下一句了，底部那块面板该让位——上一份的痕迹在 transcript 里还留着
      activePlanKey: null,
      activeTeamKey: null,
    })
  }

  /** 去掉「上次有计划没跑完」那条，其余 ready 信息原样保留。 */
  private clearResumable(): ReadyInfo | null {
    const ready = this.state.ready
    if (!ready?.resumable) return ready
    const { resumable, ...rest } = ready
    return rest
  }

  /**
   * 去掉「上次有对话没聊完」那条，其余 ready 信息原样保留。
   *
   * 只摘这一个字段——ready 里还有工具清单、能力清单、审计日志路径，
   * 跟着一起丢的话界面会当场少半截。
   */
  private clearContinuable(): ReadyInfo | null {
    const ready = this.state.ready
    if (!ready?.continuable) return ready
    const { continuable, ...rest } = ready
    return rest
  }

  /** 本地提示（错误、帮助、连接状态）。不经过后端。 */
  /** 收起会话选择器（用户按了 Esc，或者挑完了）。 */
  closeSessionPicker(): void {
    if (this.state.sessionPicker) this.set({ sessionPicker: null })
  }

  notice(level: 'info' | 'warn' | 'error', text: string): void {
    this.append({ kind: 'notice', key: this.nextKey('notice'), level, text })
  }

  /** 点中单条工具组：切换它的展开状态。鼠标点击走这条。 */
  toggleEntry(key: string): void {
    const next = new Set(this.state.expanded)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    this.set({ expanded: next })
  }

  /**
   * Ctrl-O：整体展开/收起全部工具组。有任何一条收着就全展开，全展开了就全收起，
   * 按一下总能得到确定结果，不卡在中间态。
   */
  toggleExpanded(): void {
    const keys = this.state.entries.filter(e => e.kind === 'tools').map(e => e.key)
    if (keys.length === 0) return
    const allExpanded = keys.every(k => this.state.expanded.has(k))
    this.set({ expanded: allExpanded ? new Set() : new Set(keys) })
  }

  /** 回答完计划确认（发送由调用方负责），把框收掉。 */
  clearPlanConfirm(): void {
    this.set({ planConfirm: null })
  }

  /**
   * 回答完审批（发送由调用方负责），把框收掉。
   *
   * 不收会怎样：`approval` 原本只在 `busy` 转 false 时才清，也就是整轮结束才消失。
   * 答完 y 之后工具开始跑，这期间框还挂在屏幕上——工具跑得久时（比如一条 bash）
   * 用户会对着一个已经答过的框发呆，再按一次 y 还会给一个后端早已不等的 id 补发回复。
   */
  clearApproval(): void {
    this.set({ approval: null })
  }

  clearTranscript(): void {
    this.set({ entries: [], expanded: new Set(), step: 0 })
  }

  markClosed(reason: string): void {
    this.set({ closed: reason, busy: false, approval: null })
  }

  // ------------------------------------------------------------ 后端事件

  apply(event: ServerEvent): void {
    switch (event.t) {
      case 'ready': {
        this.set({
          ready: {
            version: event.version,
            workspace: event.workspace,
            model: event.model,
            planMode: event.planMode,
            hasApiKey: event.hasApiKey,
            tools: event.tools,
            roles: event.roles ?? [],
            features: event.features ?? [],
            workspaceWarning: event.workspaceWarning,
            auditLog: event.auditLog,
            branch: event.branch,
            resumable: event.resumable,
            continuable: event.continuable,
          },
          busy: event.busy,
          tokens: event.tokens ?? 0,
          sentTokens: event.sentTokens ?? 0,
          contextLimit: event.contextLimit ?? 0,
        })
        if (!event.hasApiKey) {
          this.notice(
            'error',
            '没有配置 API key，无法对话。设置 DEEPSEEK_API_KEY，或写进 ~/.myagent/config.json',
          )
        }
        if (event.workspaceWarning) {
          this.notice('warn', event.workspaceWarning)
        }
        return
      }

      case 'thinking':
        this.set({ step: event.step })
        return

      case 'thought': {
        // 模型常常一边说「我先看看 README」一边发起调用，这句话会**同时**
        // 经由 text_delta 流出来和作为 Thought 发一遍。流式已经打过就别再打第二遍。
        // 保留这个分支而不是直接忽略：非流式的后端只会发 Thought，那时它是唯一来源。
        const tail = this.tail()
        const already = tail?.kind === 'assistant' && tail.text.trim().includes(event.text.trim())
        if (!already) {
          this.append({ kind: 'thought', key: this.nextKey('thought'), text: event.text })
        }
        return
      }

      case 'text_delta': {
        const tail = this.tail()
        if (tail?.kind === 'assistant') {
          this.replaceTail({ ...tail, text: tail.text + event.text })
        } else {
          this.append({ kind: 'assistant', key: this.nextKey('text'), text: event.text })
        }
        return
      }

      case 'tool_started': {
        const call: ToolCall = {
          id: event.id,
          tool: event.tool,
          args: event.arguments,
          running: true,
        }
        const tail = this.tail()
        // 连着调用的工具归进同一组，界面上收成一行「读取 3 个文件 · 执行 1 条命令」
        if (tail?.kind === 'tools') {
          this.replaceTail({ ...tail, calls: [...tail.calls, call] })
        } else {
          this.append({ kind: 'tools', key: this.nextKey('tools'), calls: [call] })
        }
        return
      }

      case 'tool_finished': {
        const tail = this.tail()
        if (tail?.kind !== 'tools') return
        this.replaceTail({
          ...tail,
          calls: tail.calls.map(call =>
            call.id === event.id
              ? { ...call, running: false, ok: event.ok, output: event.output }
              : call,
          ),
        })
        return
      }

      case 'compacting':
        if (event.phase === 'done') {
          this.clearCompacting()
        } else {
          this.upsertCompacting(event.phase, event.done, event.total, event.percent)
        }
        return

      case 'compacted':
        // 进度条先撤掉再落结论。不撤的话，压缩落地那一刻屏幕上会同时留着
        // 一根走到 99% 的条和一行「已压缩 48210 → 19044」，像是还有一次在跑
        this.clearCompacting()
        this.append({
          kind: 'compacted',
          key: this.nextKey('compact'),
          before: event.tokensBefore,
          after: event.tokensAfter,
          summarized: event.summarized,
        })
        return

      case 'memory_list':
        this.append({ kind: 'memory', key: this.nextKey('memory'), rows: event.entries ?? [] })
        return

      case 'memory_changed':
        // 结果用 notice 而不是新造一种转录条目：它只有一句话，不值当
        this.notice(
          'info',
          event.action === 'undo'
            ? `已撤销自动记下的 ${event.name ?? '(未知)'}${
                event.description ? ` — ${event.description}` : ''
              }。被它取代过的旧事实（如果有）已经重新生效。`
            : (event.removed ?? 0) === 0
              ? '没有自动记录可清。'
              : `已清掉 ${event.removed} 条自动记录，你自己让记的一条没动。`,
        )
        return

      case 'done': {
        // 流式没走通时（非流式后端、或被中途截断）用 done 带的全文兜底，
        // 否则这一轮会一个字都不显示
        const tail = this.tail()
        if (tail?.kind !== 'assistant' && event.text.trim()) {
          this.append({ kind: 'assistant', key: this.nextKey('text'), text: event.text })
        }
        return
      }

      case 'failed':
        this.append({
          kind: 'notice',
          key: this.nextKey('notice'),
          level: 'error',
          text: event.reason,
        })
        return

      case 'approval_request':
        this.set({
          approval: {
            id: event.id,
            action: event.action,
            detail: event.detail,
            reason: event.reason,
          },
        })
        return

      case 'busy':
        // 兜底：正常情况下 `compacting phase=done` 会把条收掉，但那条事件要是丢了
        // （旧后端、断线重连），一根永远走不完的条会一直挂在转录里
        if (!event.busy) this.clearCompacting()
        this.set({
          busy: event.busy,
          approval: event.busy ? this.state.approval : null,
          planConfirm: event.busy ? this.state.planConfirm : null,
          step: event.busy ? this.state.step : 0,
          tokens: event.tokens ?? this.state.tokens,
          sentTokens: event.sentTokens ?? this.state.sentTokens,
          contextLimit: event.contextLimit ?? this.state.contextLimit,
          lastDurationMs: event.durationMs ?? this.state.lastDurationMs,
          startedAt: event.busy ? (this.state.startedAt ?? Date.now()) : null,
        })
        return

      case 'reset_done':
        this.clearTranscript()
        // 后端 reset 时会把会话存档一起删掉，入口留着的话点了只会得到一条 error
        this.set({ ready: this.clearContinuable() })
        this.notice('info', '对话已清空')
        return

      case 'error':
        this.notice('error', event.message)
        return

      case 'mode_switched': {
        // 用一条普通提示带过，不占计划面板的位置——它马上就要出来了。
        // 要紧的是把「凭什么这么判」摆出来：用户发的是一句话，屏幕上却冒出个计划，
        // 不说清楚他只会觉得莫名其妙，也没机会说「不用，直接做」
        this.notice('info', event.reason ? `${event.reason}，先拆个计划` : '这件事先拆个计划')
        return
      }

      case 'session_list': {
        this.set({ sessionPicker: { currentId: event.currentId, sessions: event.sessions } })
        return
      }

      case 'session_restored': {
        // 存档已经用掉了，入口该收——留着会指向一段已经在内存里的历史。
        // 面板也收：挑完了还摆在屏幕上，下一次 ↑↓ 会落在一个已经接过的列表上
        this.set({ ready: this.clearContinuable(), sessionPicker: null })
        this.notice(
          'info',
          `接上了上次的对话（${event.turns} 轮，${event.messages} 条消息）。` +
            `上次首句：${event.firstMessage}`,
        )
        if (event.tokens !== undefined) {
          this.set({ tokens: event.tokens })
        }
        if (event.sentTokens !== undefined) {
          this.set({ sentTokens: event.sentTokens })
        }
        if (event.contextLimit !== undefined) {
          this.set({ contextLimit: event.contextLimit })
        }
        return
      }

      case 'plan_created': {
        const key = this.nextKey('plan')
        this.planKey = key
        // 提示该收了：存档要么刚被续上，要么正被这份新计划覆盖，
        // 留着它会指向一份已经不存在的进度
        this.set({ activePlanKey: key, ready: this.clearResumable() })
        this.append({
          kind: 'plan',
          key,
          plan: {
            goal: event.goal,
            tasks: event.tasks.map(task => fromWire(task)),
            layers: event.layers,
            status: 'CREATED',
          },
        })
        return
      }

      case 'team_started': {
        const key = this.nextKey('team')
        this.teamKey = key
        this.set({ activeTeamKey: key })
        this.append({
          kind: 'team',
          key,
          team: {
            goal: event.goal,
            reason: event.reason,
            seats: event.seats.map(seat => ({
              id: seat.id,
              role: seat.role,
              roleTitle: seat.roleTitle,
              summary: seat.summary,
              status: seat.status,
            })),
            status: 'CREATED',
          },
        })
        return
      }

      case 'team_seat_started':
        this.updateTeam(team => ({ ...team, status: 'RUNNING' }))
        this.patchSeat(event.seatId, { status: 'RUNNING' })
        return

      case 'team_seat_finished':
        this.patchSeat(event.seatId, {
          status: event.status,
          opinion: event.opinion ?? undefined,
          error: event.error ?? undefined,
          durationMs: event.durationMs,
        })
        return

      case 'team_synthesis_started':
        this.updateTeam(team => ({ ...team, synthesizing: true }))
        return

      case 'team_finished':
        this.updateTeam(team => ({
          ...team,
          status: event.status,
          synthesizing: false,
          verdict: event.verdict ?? undefined,
          spoke: event.spoke,
          silent: event.silent,
        }))
        return

      case 'plan_confirm_request':
        this.set({ planConfirm: { id: event.id } })
        return

      case 'plan_task_started':
        this.updatePlan(plan => ({ ...plan, status: 'RUNNING' }))
        this.patchTask(event.taskId, { status: 'RUNNING' })
        return

      case 'plan_task_finished':
        this.patchTask(event.taskId, {
          status: event.status,
          durationMs: event.durationMs,
          error: event.error ?? undefined,
        })
        return

      case 'plan_tasks_added': {
        // 就地插到父任务（和先到的兄弟）后面，别 push 到末尾——
        // 面板按数组顺序渲染，子任务排到别人家底下会看不懂
        this.updatePlan(plan => {
          const tasks = [...plan.tasks]
          let at = tasks.findIndex(task => task.id === event.parentId)
          if (at < 0) return { ...plan, tasks: [...tasks, ...event.tasks.map(task => fromWire(task))] }
          while (at + 1 < tasks.length && tasks[at + 1]?.parentId === event.parentId) at++
          tasks.splice(at + 1, 0, ...event.tasks.map(task => fromWire(task)))
          return { ...plan, tasks }
        })
        return
      }

      case 'plan_revised': {
        // 改道：补救任务接在**失败的那一步**后面，而不是甩到末尾。
        // 甩到末尾的话，用户得自己在一堆步骤里找「这几个是来救谁的」
        this.updatePlan(plan => {
          const tasks = [...plan.tasks]
          const at = tasks.findIndex(task => task.id === event.failedTaskId)
          const recovered = event.tasks.map(task => fromWire(task, true))
          if (at < 0) return { ...plan, tasks: [...tasks, ...recovered] }
          tasks.splice(at + 1, 0, ...recovered)
          return { ...plan, tasks }
        })
        return
      }

      case 'plan_finished': {
        const summary =
          event.succeeded !== undefined
            ? {
                succeeded: event.succeeded,
                failed: event.failed ?? 0,
                skipped: event.skipped ?? 0,
                durationMs: event.durationMs ?? 0,
              }
            : undefined
        this.updatePlan(plan => ({ ...plan, status: event.status, summary }))
        this.set({ planConfirm: null })
        return
      }

      case 'pong':
        return
    }
  }
}

/**
 * ⚠️ **调用时不要写 `map(fromWire)`**，要写 `map(task => fromWire(task))`。
 *
 * Array.map 会把 (元素, 下标, 数组) 三个参数全传进来，于是下标就成了第二个参数 recovery——
 * 第 0 个任务侥幸没事（0 是 falsy），第 1 个开始全被标成救场任务。
 * 这种错不会报错，只会让面板上凭空多出一堆「改道」标记。
 */
function fromWire(task: PlanTaskWire, recovery = false): PlanTask {
  return {
    id: task.id,
    type: task.type,
    role: task.role,
    roleTitle: task.roleTitle,
    description: task.description,
    acceptance: task.acceptance ?? undefined,
    status: task.status,
    parentId: task.parentId ?? null,
    dependsOn: task.dependsOn ?? [],
    ...(recovery ? { recovery: true } : {}),
  }
}

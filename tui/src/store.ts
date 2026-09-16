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

import type { PlanTaskWire, ServerEvent, ToolInfo } from './protocol.ts'

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
  description: string
  /** PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED */
  status: string
  parentId: string | null
  dependsOn: string[]
  durationMs?: number
  error?: string
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
  | { kind: 'compacted'; key: string; before: number; after: number; summarized: boolean }
  | { kind: 'plan'; key: string; plan: PlanData }

export type Approval = { id: number; action: string; detail: string; reason: string }

export type ReadyInfo = {
  version: string
  workspace: string
  model: string
  planMode: boolean
  hasApiKey: boolean
  tools: ToolInfo[]
  /** 后端宣告的可选能力。'plan' 在里面才放出 /plan 入口。 */
  features: string[]
  workspaceWarning?: string
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
  tokens: number
  contextLimit: number
  /** 上一轮耗时，画在状态栏上。 */
  lastDurationMs: number | null
  startedAt: number | null
  /** 非空表示后端没了，界面该退出。 */
  closed: string | null
}

const EMPTY_STATE: SessionState = {
  ready: null,
  entries: [],
  expanded: new Set(),
  busy: false,
  step: 0,
  approval: null,
  planConfirm: null,
  tokens: 0,
  contextLimit: 0,
  lastDurationMs: null,
  startedAt: null,
  closed: null,
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

  private patchTask(taskId: string, patch: Partial<PlanTask>): void {
    this.updatePlan(plan => ({
      ...plan,
      tasks: plan.tasks.map(task => (task.id === taskId ? { ...task, ...patch } : task)),
    }))
  }

  // ------------------------------------------------------------ 用户动作

  submitPrompt(text: string): void {
    this.set({
      entries: [...this.state.entries, { kind: 'user', key: this.nextKey('user'), text }],
      step: 0,
      startedAt: Date.now(),
    })
  }

  /** 本地提示（错误、帮助、连接状态）。不经过后端。 */
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
            features: event.features ?? [],
            workspaceWarning: event.workspaceWarning,
          },
          busy: event.busy,
          tokens: event.tokens ?? 0,
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

      case 'compacted':
        this.append({
          kind: 'compacted',
          key: this.nextKey('compact'),
          before: event.tokensBefore,
          after: event.tokensAfter,
          summarized: event.summarized,
        })
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
        this.set({
          busy: event.busy,
          approval: event.busy ? this.state.approval : null,
          planConfirm: event.busy ? this.state.planConfirm : null,
          step: event.busy ? this.state.step : 0,
          tokens: event.tokens ?? this.state.tokens,
          contextLimit: event.contextLimit ?? this.state.contextLimit,
          lastDurationMs: event.durationMs ?? this.state.lastDurationMs,
          startedAt: event.busy ? (this.state.startedAt ?? Date.now()) : null,
        })
        return

      case 'reset_done':
        this.clearTranscript()
        this.notice('info', '对话已清空')
        return

      case 'error':
        this.notice('error', event.message)
        return

      case 'plan_created': {
        const key = this.nextKey('plan')
        this.planKey = key
        this.append({
          kind: 'plan',
          key,
          plan: {
            goal: event.goal,
            tasks: event.tasks.map(fromWire),
            layers: event.layers,
            status: 'CREATED',
          },
        })
        return
      }

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
          if (at < 0) return { ...plan, tasks: [...tasks, ...event.tasks.map(fromWire)] }
          while (at + 1 < tasks.length && tasks[at + 1]?.parentId === event.parentId) at++
          tasks.splice(at + 1, 0, ...event.tasks.map(fromWire))
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

function fromWire(task: PlanTaskWire): PlanTask {
  return {
    id: task.id,
    type: task.type,
    description: task.description,
    status: task.status,
    parentId: task.parentId ?? null,
    dependsOn: task.dependsOn ?? [],
  }
}

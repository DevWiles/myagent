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

import { ASK_STAGE, askChoices, editFeedback, moveSelection, planChoices } from './approval.ts'
import type { ApprovalStage, ApprovalSuggestion, PlanAsk } from './approval.ts'
import type { Interrupted, MemoryRow, ServerEvent, SkillInfo, ToolInfo, TranscriptItem } from './protocol.ts'
import type { SessionRow } from './session-picker.ts'

export type ToolCall = {
  id: number
  tool: string
  args: string
  running: boolean
  ok?: boolean
  output?: string
  /** 改了什么（统一 diff）。只有写文件这类工具才有，见 protocol.ts 的 tool_finished */
  diff?: string
}

export type Entry =
  /** `display` 在时画它（大段粘贴折成占位符，见 paste-fold.ts），`text` 是真正发出去的全文 */
  | { kind: 'user'; key: string; text: string; display?: string }
  | { kind: 'assistant'; key: string; text: string }
  | { kind: 'thought'; key: string; text: string }
  | { kind: 'tools'; key: string; calls: ToolCall[] }
  | { kind: 'notice'; key: string; level: 'info' | 'warn' | 'error'; text: string }
  | { kind: 'compacting'; key: string; phase: 'map' | 'reduce'; done: number; total: number; percent: number }
  | { kind: 'compacted'; key: string; before: number; after: number; summarized: boolean }
  | { kind: 'memory'; key: string; rows: MemoryRow[] }
  /** 模型在 plan 模式下交上来等批的计划（Markdown）。进转录是为了能往回滚着读、批完也还在 */
  | { kind: 'plan'; key: string; text: string }

/**
 * 把接回来的对话摊成 entry，长得和当时实时跑出来的一样：
 * 连着的工具调用收成一组（同 tool_started 的规矩），没等到结果的那次标成失败——
 * 停在「运行中」的话，界面会对着一段旧历史转圈。
 */
export function transcriptEntries(items: TranscriptItem[], key: (prefix: string) => string): Entry[] {
  const out: Entry[] = []
  let callId = 0
  for (const item of items) {
    if (item.kind === 'user') {
      out.push({ kind: 'user', key: key('user'), text: item.text })
    } else if (item.kind === 'assistant') {
      out.push({ kind: 'assistant', key: key('text'), text: item.text })
    } else {
      const done = item.ok !== undefined
      const call: ToolCall = {
        id: ++callId,
        tool: item.tool,
        args: item.arguments,
        running: false,
        ok: done ? item.ok : false,
        output: done ? item.output : '没有执行（上次断在这一步）',
      }
      const last = out[out.length - 1]
      if (last?.kind === 'tools') {
        out[out.length - 1] = { ...last, calls: [...last.calls, call] }
      } else {
        out.push({ kind: 'tools', key: key('tools'), calls: [call] })
      }
    }
  }
  return out
}

export type Approval = {
  id: number
  /** `bash` | `write`。老后端不发这个字段，当成 bash 处理 */
  kind: string
  action: string
  detail: string
  reason: string
  /** 写文件时的 diff 正文。空串表示没有 */
  preview: string
  /** 「编码者 · t3」。空串表示就是用户刚说的那句话 */
  origin: string
  /** 「以后都同意」可选的几档松紧，从紧到松。空数组表示这次记不成规则 */
  suggestions: ApprovalSuggestion[]
  /** 默认选中第几档 */
  preferred: number
}

/**
 * 会话的模式（照 Claude Code，plan 模式是一种权限模式）。
 *
 * <p>**状态在后端**：模型也能提议进 plan 模式、计划批了后端自己退出，
 * 所以界面只是跟着 `mode` 事件走，Shift+Tab 发 `set_mode` 请后端切。
 * 模式是**看得见的状态**（左下角常驻一行），用户不必记住「我刚才有没有切过去」。
 */
export type Mode = 'normal' | 'plan'

/** 切换顺序。normal 打头：最常用的排第一，Shift+Tab 一圈回到它。 */
export const MODE_CYCLE: readonly Mode[] = ['normal', 'plan']

/**
 * 这个模式要后端宣告哪个能力。normal 谁都能跑，所以是 null。
 *
 * <p>没宣告的模式<b>在切换时直接跳过</b>，不是切进去再报错：
 * 一个进得去、一用就说「后端不支持」的模式，比压根切不进去更让人困惑。
 */
const MODE_FEATURE: Record<Mode, string | null> = {
  normal: null,
  plan: 'plan_mode',
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
  /** 模型服务的地址。老后端不发。 */
  baseUrl?: string
  /** 打过码的 key（`sk-****abcd`），没配是 null，老后端不发是 undefined。 */
  apiKeyMasked?: string | null
  tools: ToolInfo[]
  /** 后端宣告的可选能力。没宣告的模式 Shift+Tab 会跳过（见 {@link nextMode}）。 */
  features: string[]
  workspaceWarning?: string
  /** 审计日志的位置。退出时的收尾信息要用它。 */
  auditLog?: string
  /** 工作区真正的 git 分支（不在仓库里就是 null）。状态栏显示它，不再拿目录名冒充。 */
  branch?: string | null
  /** 用户能用 `/名字` 点的技能。老后端不发，那就是空数组。 */
  skills: SkillInfo[]
  /** 上次没聊完的对话（最近那一段）。 */
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

/** 和后端的连接处于哪一步。`connecting` 是首连（进度看 bootStatus），之后在另两者间切换。 */
export type LinkState =
  | { phase: 'connecting' }
  | { phase: 'online' }
  | {
      phase: 'reconnecting'
      /** 第几次重试，从 1 起。 */
      attempt: number
      reason: string
      /** 这次是在重启进程，不只是重连。 */
      restarting: boolean
    }

/**
 * plan 模式的一道确认。
 *
 * <p>`choice` 是光标停在哪个选项上；`feedback` 非 null 表示选了「我说说要改什么」、
 * 正在补那句话。和审批框那份状态一样放在 store 里：按键在 App 里处理，
 * 而矮终端上框可能压根不渲染。
 */
export type PlanConfirm = {
  id: number
  ask: PlanAsk
  /** exit 时是计划全文（Markdown），enter 时是空串 */
  plan: string
  choice: number
  feedback: string | null
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
  /**
   * 确认框停在哪一步（表态 / 挑规则 / 补一句话）。
   *
   * **放在 store 而不是组件里**：按键处理在 App，而组件在矮终端上可能压根不渲染。
   * 状态跟着组件走的话，那种终端上挪光标会没有任何反应——后端还等着，用户不知道为什么。
   */
  approvalStage: ApprovalStage
  /** 待回答的 plan 模式确认。和 approval 一样：后端此刻阻塞在等回答上。 */
  planConfirm: PlanConfirm | null
  /**
   * 会话眼下的模式。**跨轮保持**，由后端说了算（见 {@link Mode}）。
   */
  mode: Mode
  tokens: number
  /** 累计真正发出去的提示词。只增不减，压缩不会让它变小。 */
  sentTokens: number
  contextLimit: number
  /** 上一轮耗时，画在状态栏上。 */
  lastDurationMs: number | null
  startedAt: number | null
  /**
   * 非空表示后端**彻底**没了（协议不兼容、崩溃循环），界面该退出。
   *
   * <p>普通的断线不走这里——那是 {@link link} 的事，断了会自己重连。
   */
  closed: string | null
  /**
   * 和后端的连接此刻是什么状态。
   *
   * <p>原来只有「连着」和「死了」两种；现在断线是一等状态，界面照常活着，
   * 状态栏画「重连中」，敲的命令先攒着。抄的是 Claude Code 的形状。
   */
  link: LinkState
  /**
   * 后端还没 ready 时状态栏显示的那句话（「正在 build 后端…」「正在启动后端…」）。
   *
   * <p>ready 一到它就没人看了（状态栏优先画 ready.model），所以不用清。
   * 存在的理由：build 挪到界面起来**之后**做了，最长的那段等待（mvn 五秒起步）
   * 发生在界面上，不写进度的话就是一个看起来卡死的空界面。
   */
  bootStatus: string
  /**
   * 会话选择器的数据。null = 面板没开。
   *
   * <p>**不缓存**：每次 /resume 都重新问一次后端。会话列表随时在变
   * （另一个界面可能刚聊了一轮、刚清过一段），拿着上次的列表让人挑，
   * 挑中的那个 id 可能已经不在了。
   */
  sessionPicker: { currentId: string; sessions: SessionRow[] } | null
  /**
   * 主对话断在了半路，说一句「继续」就能接上。null = 没有可续的。
   *
   * <p>现场在后端的历史里（封口留着），这里只管**提醒**：不提醒的话，
   * 用户看到「已取消」就以为这一轮白干了。下一句话一发出去（busy 变真）就作废——
   * 不管他说的是「继续」还是别的，那一轮都已经接着现场在跑了。
   */
  interrupted: Interrupted | null
  /** 这段会话的摘要标题，终端标签页显示它（见 terminal-title.ts）。还没起好是 null */
  sessionTitle: string | null
}

const EMPTY_STATE: SessionState = {
  ready: null,
  entries: [],
  expanded: new Set(),
  busy: false,
  step: 0,
  approval: null,
  approvalStage: ASK_STAGE,
  planConfirm: null,
  mode: 'normal',
  tokens: 0,
  sentTokens: 0,
  contextLimit: 0,
  lastDurationMs: null,
  startedAt: null,
  closed: null,
  link: { phase: 'connecting' },
  bootStatus: '连接后端中…',
  sessionPicker: null,
  interrupted: null,
  sessionTitle: null,
}

export class SessionStore {
  private state: SessionState = EMPTY_STATE
  private readonly listeners = new Set<() => void>()
  private seq = 0

  /**
   * 本连接的 id，随机、**每次进程启动重新生成**（协议 3.15）。
   *
   * 发命令时带上它，收到自己那条 `user_message` 回显就跳过。
   */
  readonly clientId = Math.random().toString(36).slice(2, 10)

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

  // ------------------------------------------------------------ 用户动作

  /**
   * 切到下一个模式（Shift+Tab）。先在本地切过去（乐观更新），由调用方发 `set_mode`，
   * 后端回的 `mode` 事件是最终结果。
   *
   * <p><b>正忙时也允许切</b>：后端在下一步请求时就换菜单，和 Claude Code 一样。
   *
   * @returns 切到的模式；没有别的可用模式时返回 null（什么也不用发）
   */
  cycleMode(): Mode | null {
    const next = nextMode(this.state.mode, this.state.ready?.features ?? [])
    if (next === this.state.mode) return null
    this.set({ mode: next })
    return next
  }

  submitPrompt(text: string, display?: string): void {
    const shown = display !== undefined && display !== text ? { display } : {}
    this.set({
      entries: [...this.state.entries, { kind: 'user', key: this.nextKey('user'), text, ...shown }],
      step: 0,
      startedAt: Date.now(),
    })
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

  /** 回答完计划确认（发送由调用方负责），把框收掉。 */
  clearPlanConfirm(): void {
    this.set({ planConfirm: null })
  }

  /** 计划确认框里挪光标。同样不循环。补话那一步不动。 */
  movePlanChoice(delta: number): void {
    const confirm = this.state.planConfirm
    if (!confirm || confirm.feedback !== null) return
    this.set({
      planConfirm: {
        ...confirm,
        choice: moveSelection(confirm.choice, delta, planChoices(confirm.ask).length),
      },
    })
  }

  /** 计划确认框里光标直接跳到第 n 个（数字键直选）。越界不动。 */
  pickPlanChoice(index: number): void {
    const confirm = this.state.planConfirm
    if (!confirm || confirm.feedback !== null) return
    if (index < 0 || index >= planChoices(confirm.ask).length) return
    this.set({ planConfirm: { ...confirm, choice: index } })
  }

  /** 选了「不批准，我说说要改什么」：进补话那一步。 */
  beginPlanFeedback(): void {
    const confirm = this.state.planConfirm
    if (!confirm) return
    this.set({ planConfirm: { ...confirm, feedback: '' } })
  }

  /** 在计划确认的补话那一步敲了个键。返回 false 表示这个键不归它（回车、Esc、方向键） */
  typePlanFeedback(key: { name?: string; sequence?: string }): boolean {
    const confirm = this.state.planConfirm
    if (!confirm || confirm.feedback === null) return false
    const next = editFeedback(confirm.feedback, key)
    if (next === null) return false
    this.set({ planConfirm: { ...confirm, feedback: next } })
    return true
  }

  /**
   * 回答完审批（发送由调用方负责），把框收掉。
   *
   * 不收会怎样：`approval` 原本只在 `busy` 转 false 时才清，也就是整轮结束才消失。
   * 答完 y 之后工具开始跑，这期间框还挂在屏幕上——工具跑得久时（比如一条 bash）
   * 用户会对着一个已经答过的框发呆，再按一次 y 还会给一个后端早已不等的 id 补发回复。
   */
  clearApproval(): void {
    this.set({ approval: null, approvalStage: ASK_STAGE })
  }

  /**
   * 选了「以后都同意」：进「挑松紧」这一步。
   *
   * 后端一条都没提议时**留在原地**——多段命令里有段推不出规则，
   * 或者根本没有 subject。这时候让框跳进一个空的选择态，用户只会更糊涂。
   */
  beginRulePick(): void {
    const approval = this.state.approval
    if (!approval || approval.suggestions.length === 0) return
    this.set({ approvalStage: { step: 'rule', index: approval.preferred } })
  }

  /**
   * 光标上下挪一格。表态和挑松紧两步共用一个入口。
   *
   * <p>合成一个方法是因为 App 那边转发的是同一个按键：光标键按下去该动哪一列，
   * 取决于框此刻停在哪一步——而「停在哪一步」只有 store 知道。
   * 分成两个方法的话，调用方就得先判断步骤，等于把状态判断漏到外面去。
   */
  moveChoice(delta: number): void {
    const stage = this.state.approvalStage
    const approval = this.state.approval
    if (!approval) return
    if (stage.step === 'ask') {
      const count = askChoices(approval.suggestions.length > 0).length
      this.set({ approvalStage: { step: 'ask', index: moveSelection(stage.index, delta, count) } })
      return
    }
    if (stage.step === 'rule') {
      this.set({
        approvalStage: {
          step: 'rule',
          index: moveSelection(stage.index, delta, approval.suggestions.length),
        },
      })
    }
  }

  /** 光标直接跳到第 n 个（数字键直选）。越界不动。 */
  pickChoice(index: number): void {
    const stage = this.state.approvalStage
    const approval = this.state.approval
    if (!approval || stage.step !== 'ask') return
    if (index < 0 || index >= askChoices(approval.suggestions.length > 0).length) return
    this.set({ approvalStage: { step: 'ask', index } })
  }

  /** 选了拒绝：进「补一句话」这一步。 */
  beginFeedback(): void {
    if (!this.state.approval) return
    this.set({ approvalStage: { step: 'feedback', text: '' } })
  }

  /**
   * 在「补一句话」那一步敲了个键。
   *
   * @returns 这个键有没有被用掉。false 表示它不是可打印字符也不是退格
   *          （方向键、回车、Esc），交给上层去处理
   */
  typeFeedback(key: { name?: string; sequence?: string }): boolean {
    const stage = this.state.approvalStage
    if (stage.step !== 'feedback') return false
    const next = editFeedback(stage.text, key)
    if (next === null) return false
    this.set({ approvalStage: { step: 'feedback', text: next } })
    return true
  }

  /** 退回表态那一步。 */
  resetApprovalStage(): void {
    if (this.state.approvalStage.step === 'ask') return
    this.set({ approvalStage: ASK_STAGE })
  }

  clearTranscript(): void {
    this.set({ entries: [], expanded: new Set(), step: 0 })
  }

  /** 启动进行到哪一步了。ready 之前状态栏画的就是这句话。 */
  setBootStatus(text: string): void {
    this.set({ bootStatus: text })
  }

  markClosed(reason: string): void {
    this.set({ closed: reason, busy: false, approval: null, approvalStage: ASK_STAGE })
  }

  /** 连接状态变了（断了 / 重连中 / 又连上了）。 */
  setLink(link: LinkState): void {
    this.set({ link })
  }

  /**
   * 对面换了进程（本地 java 重启、云上后端重启）。
   *
   * <p>转录**留着**——那是用户已经看过的东西，不该因为后端崩一次就消失。
   * 但挂着的确认框要收：它问的是一个已经不存在的进程里的事，答了也没人收。
   * `busy` 由紧随其后的 ready 帧给出（新进程一定是闲的）。
   */
  markRestarted(): void {
    this.set({ approval: null, approvalStage: ASK_STAGE, planConfirm: null })
    this.clearCompacting()
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
            baseUrl: event.baseUrl,
            apiKeyMasked: event.apiKeyMasked,
            tools: event.tools,
            features: event.features ?? [],
            workspaceWarning: event.workspaceWarning,
            auditLog: event.auditLog,
            branch: event.branch,
            skills: event.skills ?? [],
            continuable: event.continuable,
          },
          mode: event.mode ?? 'normal',
          busy: event.busy,
          interrupted: event.interrupted ?? null,
          sessionTitle: event.sessionTitle ?? null,
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
              ? { ...call, running: false, ok: event.ok, output: event.output, diff: event.diff }
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

      case 'checkpoint_list': {
        if (!event.available) {
          this.notice('warn', '检查点不可用：这台机器上没有 git，或者影子仓库初始化失败')
          return
        }
        const items = event.items ?? []
        if (items.length === 0) {
          this.notice('info', '这段会话还没有检查点——每轮开跑前、每次改文件或跑命令前才会拍')
          return
        }
        const lines = items.map((c) => {
          const t = new Date(c.time)
          const hh = String(t.getHours()).padStart(2, '0')
          const mm = String(t.getMinutes()).padStart(2, '0')
          // 工具级的点缩进挂在它那一轮下面，一眼分得清「整轮」和「这一轮里的某一步」
          return c.kind === 'tool'
            ? `#${c.id}  ${hh}:${mm}    └ ${c.label} 之前`
            : `#${c.id}  ${hh}:${mm}  第 ${c.turn + 1} 句前  ${c.label}`
        })
        this.notice(
          'info',
          `检查点（保留 ${event.retentionDays} 天）：\n${lines.join('\n')}\n` +
            `用法：/rewind <编号> 回文件；/rewind <编号> conversation 回对话；/rewind <编号> both 都回；/undo 撤销上一轮`,
        )
        return
      }

      case 'rewound': {
        const files = event.restored ?? []
        const shown = files.slice(0, 8).join('、') + (files.length > 8 ? ` 等 ${files.length} 个` : '')
        if (event.userTurn >= 0) {
          // 对话回去了：第 userTurn 句用户消息及之后的都不该再画。
          // 按用户消息数截，而不是按后端的消息条数——转录里一条用户消息对应后端的一条 user 消息，
          // 中间的助手/工具行数量两边不一致，只有这个数是可靠的锚
          let users = 0
          let cut = this.state.entries.length
          for (let i = 0; i < this.state.entries.length; i++) {
            if (this.state.entries[i].kind === 'user') {
              if (users === event.userTurn) {
                cut = i
                break
              }
              users++
            }
          }
          this.set({ entries: this.state.entries.slice(0, cut) })
          if (event.tokens !== undefined) this.set({ tokens: event.tokens })
        }
        const what =
          event.scope === 'conversation'
            ? '对话已回到'
            : event.scope === 'both'
              ? '文件和对话已回到'
              : '文件已回到'
        this.notice(
          'info',
          `${what}检查点 #${event.id}「${event.label}」` +
            (event.scope === 'conversation'
              ? ''
              : files.length === 0
                ? '（文件没有差别）'
                : `：还原 ${files.length} 个文件（${shown}）`) +
            (event.remaining > 0 ? `，还有 ${event.remaining} 个检查点` : ''),
        )
        return
      }

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
        if (event.interrupted) {
          // 紧跟在原因后面，就在用户眼睛停着的地方。光有「已取消」三个字，
          // 他会以为得把要求从头再说一遍
          this.notice('info', continueHint(event.interrupted))
        }
        this.set({ interrupted: event.interrupted ?? null })
        return

      case 'approval_request':
        // 每个字段都给默认值：老后端不发新字段，界面不该因此显示 undefined。
        // 尤其 suggestions 缺省成空数组——那正好等于「这次记不成规则」，
        // 于是老后端接新界面时，a 键自然而然地不起作用，而不是发出一条后端不认的回复
        this.set({
          approval: {
            id: event.id,
            kind: event.kind ?? 'bash',
            action: event.action,
            detail: event.detail,
            reason: event.reason,
            preview: event.preview ?? '',
            origin: event.origin ?? '',
            suggestions: event.suggestions ?? [],
            preferred: event.preferred ?? 0,
          },
          approvalStage: ASK_STAGE,
        })
        return

      case 'busy':
        // 兜底：正常情况下 `compacting phase=done` 会把条收掉，但那条事件要是丢了
        // （旧后端、断线重连），一根永远走不完的条会一直挂在转录里
        if (!event.busy) this.clearCompacting()
        this.set({
          busy: event.busy,
          interrupted: event.busy ? null : this.state.interrupted,
          approval: event.busy ? this.state.approval : null,
          approvalStage: event.busy ? this.state.approvalStage : ASK_STAGE,
          planConfirm: event.busy ? this.state.planConfirm : null,
          step: event.busy ? this.state.step : 0,
          tokens: event.tokens ?? this.state.tokens,
          sentTokens: event.sentTokens ?? this.state.sentTokens,
          contextLimit: event.contextLimit ?? this.state.contextLimit,
          lastDurationMs: event.durationMs ?? this.state.lastDurationMs,
          startedAt: event.busy ? (this.state.startedAt ?? Date.now()) : null,
        })
        return

      case 'approval_resolved': {
        // 别的屏答掉了这道题。框还挂着且 id 对得上就收——
        // 不收的话它会一直杵到整轮结束，看起来像「问题还在等我」
        if (this.state.approval?.id === event.id) {
          this.set({ approval: null, approvalStage: ASK_STAGE })
        }
        return
      }

      case 'plan_confirm_resolved': {
        if (this.state.planConfirm?.id === event.id) {
          this.set({ planConfirm: null })
        }
        return
      }

      case 'user_message': {
        // 自己那条早在按回车时就画过了（submitPrompt），这里只画**别的界面**说的话。
        // cid 每次连接重新生成，所以重连后补发的老消息 cid 对不上、照常显示——
        // 持久化 cid 的话，重启后自己以前说过的话会被当成「本地已有」而整段消失
        if (event.cid && event.cid === this.clientId) return
        this.append({ kind: 'user', key: this.nextKey('user'), text: event.text })
        return
      }

      case 'session_title':
        this.set({ sessionTitle: event.title })
        return

      case 'reset_done':
        this.clearTranscript()
        this.set({ interrupted: null, sessionTitle: null })   // 那段对话不要了，它的断点也就不作数
        // 后端 reset 时会把会话存档一起删掉，入口留着的话点了只会得到一条 error
        this.set({ ready: this.clearContinuable() })
        this.notice('info', '对话已清空')
        return

      case 'error':
        this.notice('error', event.message)
        return

      case 'replay_gap':
        // 如实标出来，别装完整：静默跳过的话转录看起来是全的、实际上少了一段（协议 1.2）
        this.notice('warn', `断线期间有 ${event.to - event.from + 1} 条事件补不回来了（第 ${event.from}–${event.to} 号）`)
        return

      case 'session_list': {
        this.set({ sessionPicker: { currentId: event.currentId, sessions: event.sessions } })
        return
      }

      case 'session_restored': {
        // 存档已经用掉了，入口该收——留着会指向一段已经在内存里的历史。
        // 面板也收：挑完了还摆在屏幕上，下一次 ↑↓ 会落在一个已经接过的列表上
        // 接回来的那段要是断在半路，断点跟着回来（协议 3.9）；没有就清掉上一段的
        // 标题换成接回来那段的：后端会按它的首句重起一个、随后发 session_title
        this.set({ ready: this.clearContinuable(), sessionPicker: null, interrupted: event.interrupted ?? null,
          sessionTitle: null })
        // 对话本身原样摆回来（照 Claude Code），摘要那行跟在后面，说明上面这些是接回来的
        if (event.transcript && event.transcript.length > 0) {
          this.set({
            entries: [...this.state.entries, ...transcriptEntries(event.transcript, (p) => this.nextKey(p))],
          })
        }
        const shown = Boolean(event.transcript && event.transcript.length > 0)
        this.notice(
          'info',
          `接上了上次的对话（${event.turns} 轮，${event.messages} 条消息）。` +
            (shown ? '上面就是，接着说就行' : `上次首句：${event.firstMessage}`),
        )
        if (event.interrupted) this.notice('info', continueHint(event.interrupted))
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

      case 'mode':
        this.set({ mode: event.mode })
        return

      case 'plan_confirm_request': {
        const ask: PlanAsk = event.kind ?? 'exit'
        const plan = event.plan ?? ''
        // 计划进转录：框里放不下长计划，往回滚着读、批完以后回头对账都靠这一份
        if (ask === 'exit' && plan.trim()) {
          this.append({ kind: 'plan', key: this.nextKey('plan'), text: plan })
        }
        this.set({ planConfirm: { id: event.id, ask, plan, choice: 0, feedback: null } })
        return
      }

      case 'hook':
        this.notice(event.blocked ? 'warn' : 'info',
          event.blocked ? `这句话被 hook 拦下了，没有发给模型：${event.message}` : event.message)
        return

      case 'pong':
        return
    }
  }
}

/**
 * 「继续」那行提示。给 failed 之后的转录和输入框占位共用。
 *
 * <p>点名「继续」两个字：用户不知道这句话在这儿有用，就会把整个要求重说一遍——
 * 模型照样能干，但前面读过的、改过的它得自己从头对一遍账。
 */
export function continueHint(cut: Interrupted): string {
  return `现场还在（断在第 ${cut.steps} 步）· 说「继续」接着做，或者直接交代新的要求`
}

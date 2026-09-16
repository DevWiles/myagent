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
 * 这样每一条消息——包括很早以前的——都还是活的 React 元素，可以被鼠标点中、
 * 可以展开/收起。这是「鼠标点击展开」能成立的前提：inline + <Static> 模式下
 * 历史会被冻进 scrollback、无法再交互，两者本质冲突，所以选了全屏。
 *
 * 退出时 main.tsx 会把整段对话打印回主屏 scrollback，所以「退出后可回看」也没丢。
 */

import type { ServerEvent, ToolInfo } from './protocol.ts'

export type ToolCall = {
  id: number
  tool: string
  args: string
  running: boolean
  ok?: boolean
  output?: string
}

export type Entry =
  | { kind: 'user'; key: string; text: string }
  | { kind: 'assistant'; key: string; text: string }
  | { kind: 'thought'; key: string; text: string }
  | { kind: 'tools'; key: string; calls: ToolCall[] }
  | { kind: 'notice'; key: string; level: 'info' | 'warn' | 'error'; text: string }
  | { kind: 'compacted'; key: string; before: number; after: number; summarized: boolean }

export type Approval = { id: number; action: string; detail: string; reason: string }

export type ReadyInfo = {
  version: string
  workspace: string
  model: string
  planMode: boolean
  hasApiKey: boolean
  tools: ToolInfo[]
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

      case 'pong':
        return
    }
  }
}

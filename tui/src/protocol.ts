/**
 * 和 Java 侧 `dev.wiles.myagent.server.Protocol` 一一对应的线上协议。
 *
 * 这里是唯一一处「知道后端长什么样」的地方。界面层只认这些类型，
 * 不直接碰 WebSocket，也不解析裸 JSON——协议变了只需要改这个文件。
 */

/** 两边都硬编码这个值，对不上就拒绝连接，别带着半懂不懂往下跑。 */
export const PROTOCOL_VERSION = 1

// ------------------------------------------------------------ 服务端 → 客户端

export type ToolInfo = { name: string; description: string }

/** 计划里的一个任务，线上形态。status/parentId 在 plan_created 里分别是 PENDING/null。 */
export type PlanTaskWire = {
  id: string
  type: string
  description: string
  status: string
  parentId: string | null
  dependsOn: string[]
}

export type ServerEvent =
  /** 首帧。画第一屏要的静态信息一次给全，省掉一轮往返。 */
  | {
      t: 'ready'
      protocol: number
      version: string
      workspace: string
      model: string
      planMode: boolean
      hasApiKey: boolean
      busy: boolean
      /** 后端支持的可选能力。老后端没有这个字段——界面据此决定画不画对应入口。 */
      features?: string[]
      tokens?: number
      contextLimit?: number
      workspaceWarning?: string
      /** 审计日志的位置。界面退出时显示给用户，好让人知道去哪儿查这趟做了什么。 */
      auditLog?: string
      tools: ToolInfo[]
    }
  /** 第 step 轮开始，正在等模型响应。 */
  | { t: 'thinking'; step: number }
  /** 模型新吐出的一小段文本（增量）。 */
  | { t: 'text_delta'; text: string }
  /** 模型调用工具前说的话，ReAct 里的 Thought。 */
  | { t: 'thought'; text: string }
  | { t: 'tool_started'; id: number; tool: string; arguments: string }
  | { t: 'tool_finished'; id: number; tool: string; ok: boolean; output: string }
  /** 上下文被压缩了。用户有权知道——摘要意味着早期细节已经丢了。 */
  | { t: 'compacted'; tokensBefore: number; tokensAfter: number; summarized: boolean }
  | { t: 'done'; text: string; steps: number }
  | { t: 'failed'; reason: string }
  /** 需要用户确认某个危险动作。回 approval_reply 之前后端是卡住的。 */
  | { t: 'approval_request'; id: number; action: string; detail: string; reason: string }
  /** 一轮对话的开始/结束。结束时带上耗时和上下文占用。 */
  | { t: 'busy'; busy: boolean; durationMs?: number; tokens?: number; contextLimit?: number }
  | { t: 'reset_done' }
  | { t: 'error'; message: string }
  | { t: 'pong' }
  // ---- 先规划后执行（feature: 'plan'），时序见 docs/protocol.md 第 5 节 ----
  /** 规划产出。layers 是服务端算好的拓扑分层——界面直接照着画，别自己按 dependsOn 再推一遍。 */
  | { t: 'plan_created'; goal: string; tasks: PlanTaskWire[]; layers: string[][] }
  /** 等界面确认。必须回 plan_confirm_reply，900 秒不回按否决。 */
  | { t: 'plan_confirm_request'; id: number }
  | { t: 'plan_task_started'; taskId: string; taskType: string; description: string }
  | {
      t: 'plan_task_finished'
      taskId: string
      status: string
      durationMs: number
      result: string | null
      error: string | null
    }
  /** PLANNING 任务拆出了子任务，图长大了。就地插到父任务下面，别重画整张图。 */
  | { t: 'plan_tasks_added'; parentId: string; tasks: PlanTaskWire[] }
  /** 用户否决时只有 status（CANCELLED），没有那三个计数。 */
  | {
      t: 'plan_finished'
      status: string
      succeeded?: number
      failed?: number
      skipped?: number
      durationMs?: number
    }

// ------------------------------------------------------------ 客户端 → 服务端

export type ApprovalDecision = 'yes' | 'no' | 'always'

export type ClientCommand =
  | { t: 'prompt'; text: string }
  | { t: 'plan'; goal: string }
  | { t: 'plan_confirm_reply'; id: number; approved: boolean }
  | { t: 'cancel' }
  | { t: 'approval_reply'; id: number; decision: ApprovalDecision }
  | { t: 'reset' }
  | { t: 'ping' }

/** 后端启动时打在 stdout 的握手行，界面靠它知道该连哪儿。 */
export type ListeningLine = {
  t: 'listening'
  port: number
  token: string
  protocol: number
}

/**
 * 把收到的原始文本解析成事件。
 *
 * 解析失败返回 null 而不是抛异常：一条脏报文不该让整个界面挂掉，
 * 调用方把它当成一次噪声丢掉就行。
 */
export function parseEvent(raw: string): ServerEvent | null {
  try {
    const value = JSON.parse(raw)
    return value && typeof value.t === 'string' ? (value as ServerEvent) : null
  } catch {
    return null
  }
}

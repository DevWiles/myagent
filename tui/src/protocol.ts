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

/**
 * 一个角色在名册里的样子。
 *
 * `mutates` 是「它会不会动手改东西」——界面拿它分色比按 id 硬编码可靠：
 * 名册是会长的，硬编码的那张表只会停在写它的那天。
 */
/** 圆桌上的一席，线上形态。开场那帧里 status 一律是 PENDING。 */
export type TeamSeatWire = {
  id: string
  role: string
  roleTitle: string
  summary: string
  status: string
}

export type RoleInfo = {
  id: string
  title: string
  summary: string
  type: string
  mutates: boolean
  tools: string[]
}

/** 计划里的一个任务，线上形态。status/parentId 在 plan_created 里分别是 PENDING/null。 */
export type PlanTaskWire = {
  id: string
  type: string
  /**
   * 派给这一步的角色 id（`explorer` / `coder` / …）。老后端没有这个字段。
   *
   * 和 `type` 不是一回事：同一个 `FILE_READ` 底下既可能是 `explorer`（去查清楚）
   * 也可能是 `reviewer`（去挑毛病）——只画 type 的话这两件事长得一模一样。
   */
  role?: string
  /** 角色的中文名，后端给。界面**不要自己按 id 映射**——两边措辞会漂。 */
  roleTitle?: string
  description: string
  /** 做到什么算这一步完成。老计划里可能是 null。 */
  acceptance?: string | null
  status: string
  parentId: string | null
  dependsOn: string[]
}

/**
 * 一条长期记忆在列表里的样子。
 *
 * <p>`supersededBy` 非空表示它已被同主题的新事实顶掉：**不再注入提示词、也 recall 不到**，
 * 但仍留在盘上。界面该把它画得弱一些而不是隐藏——用户要能看见系统曾经记过什么，
 * 否则「它为什么突然改口了」永远查不清。
 */
export type MemoryRow = {
  name: string
  description: string
  scope: 'project' | 'global'
  source: string
  /** 给人看的来源标签，后端给，界面不要自己映射——两边措辞会漂。 */
  sourceLabel: string
  created: string
  topicKey: string | null
  supersededBy: string | null
  turnId: string | null
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
      /** 累计真正发出去的提示词。和 tokens 不是一回事，见 protocol 3.10。 */
      sentTokens?: number
      contextLimit?: number
      workspaceWarning?: string
      /**
       * 后端的角色名册。老后端没有这个字段。
       *
       * 整份发过来而不是让界面自己写一张表，理由和 `layers` 一样：
       * 同一份事实在两种语言里各写一遍，迟早有一边过期。
       */
      roles?: RoleInfo[]
      /** 审计日志的位置。界面退出时显示给用户，好让人知道去哪儿查这趟做了什么。 */
      auditLog?: string
      /**
       * 工作区真正的 git 分支（不在仓库里就是 null）。
       *
       * 状态栏原来显示的是工作区**目录名**冒充分支——myagent 仓库在 dev 上，
       * 状态栏却写 git:(myagent)。那不是简写，是错的。
       */
      branch?: string | null
      /**
       * 这个工作区上次没跑完的计划。没有就没这个字段。
       *
       * 放在首帧而不是让界面再问一次——画第一屏的时候就得知道要不要摆出「接着跑」。
       */
      resumable?: { goal: string; remaining: number; total: number; savedAt: number }
      /**
       * 这个工作区上次没聊完的对话。没有就没这个字段。
       *
       * 和 resumable 是两件事：那个续的是**计划**，这个续的是**对话**。
       */
      continuable?: {
        id: string
        /** 那段对话是在哪个 git 分支上聊的。不在仓库里、或是老存档时没有。 */
        branch?: string | null
        turns: number
        messages: number
        firstMessage: string
        savedAt: number
        /** 这个工作区一共有几段会话。只有一段时摆个选择器是纯噪音。 */
        count: number
      }
      tools: ToolInfo[]
    }
  /** 第 step 轮开始，正在等模型响应。 */
  | { t: 'thinking'; step: number }
  /** 模型新吐出的一小段文本（增量）。 */
  | { t: 'text_delta'; text: string }
  /** 模型调用工具前说的话，ReAct 里的 Thought。 */
  | { t: 'thought'; text: string }
  | { t: 'tool_started'; id: number; tool: string; arguments: string }
  /**
   * `diff` 是**只给人看的**那份改动（统一 diff），只有写文件这类工具才带，没有就不发这个字段。
   * 它不进对话历史——模型刚把新内容整段写出来，再回灌一份 diff 给它是白花 token；
   * 而用户没看过那段内容，「改了什么」恰恰是他唯一想知道的事。
   */
  | { t: 'tool_finished'; id: number; tool: string; ok: boolean; output: string; diff?: string }
  /**
   * 上次的对话已经立回后端内存了。
   *
   * 这条只是回执，**不带历史内容**——后端不负责替界面重播已经画过的东西。
   * messages 是**真正恢复的条数**，可能少于存档里的：残缺的尾巴会被修剪掉。
   */
  | {
      t: 'session_restored'
      messages: number
      turns: number
      firstMessage: string
      savedAt: number
      tokens?: number
      /** 累计真正发出去的提示词。和 tokens 不是一回事，见 protocol 3.10。 */
      sentTokens?: number
      contextLimit?: number
    }
  /**
   * 压缩正在进行，画进度条。
   *
   * 只有**用户等得到**的压缩才发：`/compact`，以及占用到硬线时那次不得不卡的同步压缩。
   * 80% 那条线上的后台预压缩**刻意不发**——它的全部价值就是无感。
   *
   * `phase` 为 `'done'` 表示收尾，进度条该撤了（压成了、压失败了、按 Esc 取消了，
   * 三种都会发它）。`percent` 的内层是估的：当前这一步的进度按「摘要吐了多少字 /
   * 按输入规模估的预期长度」算，所以条可能提前走完或者顶在 99% 等收尾。
   */
  | { t: 'compacting'; phase: 'map' | 'reduce' | 'done'; done: number; total: number; percent: number }
  /** 上下文被压缩了。用户有权知道——摘要意味着早期细节已经丢了。 */
  | { t: 'compacted'; tokensBefore: number; tokensAfter: number; summarized: boolean }
  | { t: 'memory_list'; entries: MemoryRow[] }
  | {
      t: 'memory_changed'
      action: 'undo' | 'clear_auto'
      /** undo 时给：被撤销的那条。 */
      name?: string
      description?: string
      /** clear_auto 时给：清掉几条。 */
      removed?: number
    }
  | { t: 'done'; text: string; steps: number }
  | { t: 'failed'; reason: string }
  /** 需要用户确认某个危险动作。回 approval_reply 之前后端是卡住的。 */
  | {
      t: 'approval_request'
      id: number
      /** 机器键：`bash` | `write`。规则按它分类，界面按它决定 preview 怎么画 */
      kind?: string
      action: string
      detail: string
      reason: string
      /** 写文件时是 diff 正文。没有就是空串 */
      preview?: string
      /** 计划/圆桌里是「编码者 · t3」。单轮对话里是空串 */
      origin?: string
      /**
       * 「以后都同意」可以存成哪几条规则，从紧到松。空数组表示这次不能记规则。
       *
       * `patterns` 才是回给后端的东西，`label` 只是显示。
       * **后端只接受它自己提议过的 pattern**——回别的一律当没回。
       */
      suggestions?: { label: string; patterns: string[] }[]
      /** 默认选中第几档 */
      preferred?: number
    }
  /** 一轮对话的开始/结束。结束时带上耗时和上下文占用。 */
  | {
      t: 'busy'
      busy: boolean
      durationMs?: number
      tokens?: number
      /** 累计真正发出去的提示词，只增不减。见 protocol 3.10。 */
      sentTokens?: number
      contextLimit?: number
    }
  | { t: 'reset_done' }
  /**
   * 这个工作区的会话历史，只回给问的那个连接。
   *
   * 不在 ready 帧里：一个工作区能攒几十段，而首帧每次连上来都要发。
   * 要列表的界面自己问一句（见 protocol.md 3.9）。
   */
  | {
      t: 'session_list'
      currentId: string
      sessions: {
        id: string
        title: string
        turns: number
        model: string
        /** 那段是在哪个分支上聊的。不在仓库里、或是老存档时是 null。 */
        branch?: string | null
        startedAt: number
        savedAt: number
        bytes: number
      }[]
    }
  | { t: 'error'; message: string }
  | { t: 'pong' }
  // ---- 先规划后执行（feature: 'plan'），时序见 docs/protocol.md 第 5 节 ----
  /** 规划产出。layers 是服务端算好的拓扑分层——界面直接照着画，别自己按 dependsOn 再推一遍。 */
  | { t: 'plan_created'; goal: string; tasks: PlanTaskWire[]; layers: string[][] }
  /** 等界面确认。必须回 plan_confirm_reply，900 秒不回按否决。 */
  | { t: 'plan_confirm_request'; id: number }
  | {
      t: 'plan_task_started'
      taskId: string
      taskType: string
      role?: string
      roleTitle?: string
      description: string
    }
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
  /**
   * 某一步失败后，后端补出了一批救场任务。这是**改道**，
   * 和 plan_tasks_added 的「计划照常长大」不是一回事，面板上该看得出区别。
   */
  | { t: 'plan_revised'; failedTaskId: string; tasks: PlanTaskWire[] }
  /**
   * 圆桌开场：名单定了，还没开跑。
   *
   * 这一帧是**花钱之前用户唯一一眼**——后面每一席都是一次完整的子循环，
   * `reason` 那句话要显眼地画出来，用户靠它判断值不值得。
   */
  | { t: 'team_started'; goal: string; reason: string; seats: TeamSeatWire[] }
  | { t: 'team_seat_started'; seatId: string; role: string; roleTitle: string }
  | {
      t: 'team_seat_finished'
      seatId: string
      role: string
      /** PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED */
      status: string
      durationMs: number
      /** 这一席的定稿。哑火时是 null——流式那份是混在一起的，这份是归到它名下的 */
      opinion: string | null
      error: string | null
    }
  | { t: 'team_synthesis_started' }
  | {
      t: 'team_finished'
      /** COMPLETED | PARTIAL | FAILED | CANCELLED */
      status: string
      spoke: number
      silent: number
      /** 合议正文。取消和失败时是 null——没有结论就别画一个出来 */
      verdict: string | null
    }
  /**
   * 一条 prompt 被自动改走规划了，reason 是后端给的判断依据。
   *
   * 不显示它的话，用户发的是普通一句话、屏幕上却冒出个计划面板——
   * 他会完全不知道发生了什么，也没机会说「不用，直接做」。
   */
  | { t: 'mode_switched'; mode: string; reason: string }
  /** 用户否决时只有 status（CANCELLED），没有那三个计数。 */
  | {
      t: 'plan_finished'
      status: string
      succeeded?: number
      failed?: number
      skipped?: number
      durationMs?: number
      /** 中途重规划了几次。COMPLETED 且大于 0，说明绕过弯但目标达成了。 */
      replans?: number
    }

// ------------------------------------------------------------ 客户端 → 服务端

export type ApprovalDecision = 'yes' | 'no' | 'always'

export type ClientCommand =
  | { t: 'prompt'; text: string }
  | { t: 'plan'; goal: string }
  /**
   * 开一场圆桌（feature: `team`）：几个角色各把同一件事看一遍，最后合议。
   *
   * `roles` 不传就让模型挑。会写文件的角色坐不上桌（几个人各改一遍同一处，
   * 后写的会盖掉先写的），传了会被服务端静默跳过；传个不存在的 id 会收到 error。
   */
  | { t: 'team'; goal: string; roles?: string[] }
  /**
   * 接着跑上次没跑完的计划。没有存档时后端回 error。
   *
   * 老名字是 `resume`（那会儿只有计划能续）。后端两个都认，界面发新的这个——
   * `/resume` 在终端里指的是「挑一段会话」，协议再叫 resume 就是两头对不上。
   */
  | { t: 'resume_plan' }
  /** `resume_plan` 的老名字。留着只是为了描述协议全貌，界面不再发它。 */
  | { t: 'resume' }
  /** 接着聊上次没聊完的对话。没有存档、或当前已经有对话时后端回 error。 */
  | { t: 'continue' }
  | { t: 'plan_confirm_reply'; id: number; approved: boolean }
  | { t: 'cancel' }
  /**
   * 回应 `approval_request`。
   *
   * `always` 时带上 `rules`（从 `suggestions[i].patterns` 里原样取），后端会存进
   * `~/.myagent/policy.json`；`no` 时可以带 `feedback`，那句话会原样交给模型。
   */
  | {
      t: 'approval_reply'
      id: number
      decision: ApprovalDecision
      rules?: string[]
      feedback?: string
    }
  | { t: 'reset' }
  /**
   * 现在就压一次上下文（feature: `compact`）。
   *
   * 后端有后台算好的现货就直接用（零调用），没有就现算。正忙时回 error。
   * 压完回一条 `compacted`。**记录不受影响**——原文一直留在存档里，
   * 压的只是发给模型的那份窗口。
   */
  | { t: 'compact' }
  | { t: 'memory'; action: 'list' | 'undo' | 'clear_auto' }
  /** 要会话历史列表，后端回 session_list。 */
  | { t: 'sessions' }
  /** 按 id 接一段历史会话。id 认不出、找不到、或当前已有对话时后端回 error。 */
  | { t: 'session_resume'; id: string }
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

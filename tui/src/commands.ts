/**
 * 斜杠命令：认哪些、现在能不能用、边打边筛。
 *
 * <h3>为什么要有这个模块</h3>
 * 在它之前，界面只认三条命令（`/plan`、`/resume`、`/continue`），
 * 判断散在 `App` 的 onSubmit 里，而**认不出来的一律当提示词发给模型**。
 * 后果不只是「少个功能」：打错一个命令就真的调一次模型（花钱），
 * 而且 agent 可能当场动手改仓库——`/resume` 打上去，它会一本正经地
 * 去读仓库、翻提交记录，试图搞懂你要它「恢复」什么。
 *
 * <h3>纯函数，不碰渲染</h3>
 * 和 `plan-layout`、`markdown-blocks` 同一个路子：命令能不能用取决于
 * 后端宣告了什么能力、有没有存档，这些组合用断言测比手动敲一遍可靠得多。
 */

/** 判断一条命令此刻能不能用，要看的那些外部条件。 */
export type CommandContext = {
  /** 后端 `ready.features` 宣告的能力。 */
  features: string[]
  /** 有没有可以接着聊的会话（`ready.continuable`）。 */
  hasContinuable: boolean
  /** 有没有没跑完的计划（`ready.resumable`）。 */
  hasResumablePlan: boolean
}

export type CommandId =
  | "help"
  | "plan"
  | "continue"
  | "resume"
  | "resume-plan"
  | "compact"
  | "clear"

type CommandSpec = {
  id: CommandId
  name: string
  /** 参数占位符，菜单里显示；有这个的命令不带参数提交会被拦住。 */
  args?: string
  description: string
  /**
   * 后端得宣告这里面任意一个能力。
   *
   * 空数组是两种情况：要么纯本地（`/help`），要么是协议第 1 版就有的基础命令
   * （`/clear` 发的 `reset`），那些不在 `features` 里，没什么可探测的。
   */
  anyFeature: string[]
  /** 能力有了，还得满足这个条件——比如「接着聊」总得先有一段存档。 */
  ready?: (ctx: CommandContext) => boolean
  /** 上一条不满足时怎么说。用户敲了才知道为什么不行，比命令凭空消失好懂。 */
  notReady?: string
  /**
   * 名字先占下来，功能还没做。这里写「为什么现在不行、该用什么替代」。
   *
   * <p>为什么要占：`/resume` 是 `/resume-plan` 的唯一前缀，不占的话
   * 敲 `/resume` 会被前缀规则<b>悄悄解析成「续跑计划」</b>——
   * 而这个名字在终端里指的是「挑一段会话」。用户以为在挑会话，
   * 实际上启动了一份旧计划，这种错比「没这个命令」严重得多。
   */
  pending?: string
}

const SPECS: CommandSpec[] = [
  {
    id: "help",
    name: "/help",
    description: "看全部命令",
    anyFeature: [],
  },
  {
    id: "plan",
    name: "/plan",
    args: "<目标>",
    description: "先规划后执行：拆成任务依赖图，确认后逐个跑",
    anyFeature: ["plan"],
  },
  {
    id: "continue",
    name: "/continue",
    description: "接最近那段会话，不用挑",
    anyFeature: ["session"],
    ready: (ctx) => ctx.hasContinuable,
    notReady: "这个工作区还没有存下来的会话",
  },
  {
    id: "resume",
    name: "/resume",
    description: "从会话历史里挑一段接着聊",
    anyFeature: ["sessions"],
    // 不要求「有存档」：没有存档时面板会自己说这个工作区还没有会话，
    // 那比让命令灰着更容易懂——用户想知道的是「我有没有历史」，而这正是答案
  },
  {
    id: "resume-plan",
    name: "/resume-plan",
    description: "接着跑上次没跑完的计划（做成的步骤不重来）",
    // resume 是老名字。后端两个都认，界面也就都接受——连的可能是个旧后端
    anyFeature: ["resume_plan", "resume"],
    ready: (ctx) => ctx.hasResumablePlan,
    notReady: "上次没有留下没跑完的计划",
  },
  {
    id: "compact",
    name: "/compact",
    description: "现在就把早先的对话压成摘要（原文留在存档里，不会丢）",
    anyFeature: ["compact"],
  },
  {
    id: "clear",
    name: "/clear",
    description: "清空对话、开一段新会话（旧的还留着）",
    anyFeature: [],
  },
]

export type CommandEntry = {
  id: CommandId
  name: string
  args?: string
  description: string
  /** 此刻能不能用。**不能用也要列出来**：让它凭空消失，用户只会以为界面坏了。 */
  available: boolean
  /** 不能用的原因，界面直接显示。 */
  reason?: string
}

/**
 * 什么样的首词才算「一条命令」。
 *
 * <p>只认 `/` 加字母数字连字符，<b>里面不能再有斜杠</b>。这一条不是洁癖：
 * 用户会把绝对路径贴进来当提示词——「/Users/me/proj/pom.xml 看看这个」。
 * 光看「以 / 开头」就当命令的话，这句话会被当成一条不存在的命令拦下来，
 * 而他要问的事根本没发出去。同理，`/繼續` 这种非 ASCII 的也不是命令。
 *
 * <p>斜杠后面允许是空的：光敲下一个 `/` 的那一刻菜单就该弹出来，
 * 那时候还没有任何字母可匹配。
 */
const COMMAND_SHAPE = /^\/[A-Za-z0-9-]*$/

/** 这条命令此刻可用吗，不可用的话理由是什么。 */
function assess(spec: CommandSpec, ctx: CommandContext): CommandEntry {
  const base = {
    id: spec.id,
    name: spec.name,
    ...(spec.args ? { args: spec.args } : {}),
    description: spec.description,
  }
  if (spec.pending) {
    return { ...base, available: false, reason: spec.pending }
  }
  const supported =
    spec.anyFeature.length === 0 || spec.anyFeature.some((f) => ctx.features.includes(f))
  if (!supported) {
    return { ...base, available: false, reason: "这个后端没宣告这个能力" }
  }
  if (spec.ready && !spec.ready(ctx)) {
    return { ...base, available: false, ...(spec.notReady ? { reason: spec.notReady } : {}) }
  }
  return { ...base, available: true }
}

/** 全部命令，带上「此刻能不能用」。给 `/help` 和菜单用。 */
export function listCommands(ctx: CommandContext): CommandEntry[] {
  return SPECS.map((spec) => assess(spec, ctx))
}

/** 输入的第一个词。 */
function firstWord(text: string): string {
  return text.trim().split(/\s+/, 1)[0] ?? ""
}

/**
 * 首词之后剩下的部分，也就是参数。
 *
 * <p>菜单开着时按回车要执行**高亮那条**，但参数得从用户实际敲的字里取——
 * 他可能打的是 `/pl 把 README 补全`，高亮的是 `/plan`，参数是后面那句。
 */
export function argsOf(text: string): string {
  const trimmed = text.trim()
  return trimmed.slice(firstWord(trimmed).length).trim()
}

/** 这行输入看起来是在敲命令吗——决定要不要弹菜单。 */
export function looksLikeCommand(text: string): boolean {
  return COMMAND_SHAPE.test(firstWord(text))
}

/**
 * 边打边筛：给当前这行输入该显示哪些候选。
 *
 * <p>一律按<b>前缀</b>筛，打到参数了也一样。第一版在「已经打到参数」时只留精确
 * 匹配，理由是「用户正在写 `/plan 把 README 补全`，摆五条候选是噪音」——
 * 但那样 `/pl 把 README 补全` 的菜单会<b>整个消失</b>，而回车执行的是「高亮那条」，
 * 菜单一没，这条路就断了。名字打全了自然只剩一条，不用特殊照顾。
 */
export function filterCommands(text: string, ctx: CommandContext): CommandEntry[] {
  if (!looksLikeCommand(text)) {
    return []
  }
  const head = firstWord(text).toLowerCase()
  return listCommands(ctx).filter((entry) => entry.name.startsWith(head))
}

/**
 * 这行输入对上了哪条命令。对不上返回 null。
 *
 * <p>精确匹配之外还认<b>唯一前缀</b>：`/cont` 只可能是 `/continue`，
 * 那就当它。这条规矩是从终端版的 Tab 补全搬过来的——两边对命令的理解要一样，
 * 不然「在终端里管用的敲法，到界面里就不认」。
 */
export function matchCommand(text: string, ctx: CommandContext): CommandEntry | null {
  if (!looksLikeCommand(text)) {
    return null
  }
  const head = firstWord(text).toLowerCase()
  const all = listCommands(ctx)
  const exact = all.find((entry) => entry.name === head)
  if (exact) {
    return exact
  }
  const prefixed = all.filter((entry) => entry.name.startsWith(head))
  return prefixed.length === 1 ? prefixed[0]! : null
}

export type Resolution =
  /** 就是一句普通的话，发给模型。 */
  | { kind: "prompt"; text: string }
  /** 认出来了，可以执行。 */
  | { kind: "command"; entry: CommandEntry; args: string }
  /** 形状像命令，但没有这一条。**不能发给模型**。 */
  | { kind: "unknown"; typed: string }
  /** 有这条命令，但此刻用不了。 */
  | { kind: "unavailable"; entry: CommandEntry }
  /** 这条命令要参数，而用户没给。 */
  | { kind: "needs-args"; entry: CommandEntry }

/**
 * 按回车时怎么处置这行输入。
 *
 * <p>五种结局各有各的下场，尤其是 {@code unknown}：那一条必须<b>就地拦住</b>。
 * 在它之前，认不出来的命令会被当提示词发出去，于是打错字的代价是一次真实的模型调用
 * 加上一个可能开始改文件的 agent。
 */
export function resolve(text: string, ctx: CommandContext): Resolution {
  const trimmed = text.trim()
  if (!looksLikeCommand(trimmed)) {
    return { kind: "prompt", text: trimmed }
  }
  const entry = matchCommand(trimmed, ctx)
  if (!entry) {
    return { kind: "unknown", typed: firstWord(trimmed) }
  }
  if (!entry.available) {
    return { kind: "unavailable", entry }
  }
  const args = trimmed.slice(firstWord(trimmed).length).trim()
  if (entry.args && !args) {
    return { kind: "needs-args", entry }
  }
  return { kind: "command", entry, args }
}

/** `/help` 打出来的那几行。 */
export function helpLines(ctx: CommandContext): string[] {
  return listCommands(ctx).map((entry) => {
    const head = entry.args ? `${entry.name} ${entry.args}` : entry.name
    const tail = entry.available ? entry.description : `${entry.description}（现在不行：${entry.reason ?? "用不了"}）`
    return `${head}  ${tail}`
  })
}

/** 菜单开着时按回车的四种结局。 */
export type Acceptance =
  /** 执行这条，参数是用户实际敲的那部分。 */
  | { kind: "run"; entry: CommandEntry; args: string }
  /** 这条要参数而用户还没打：把名字补进输入框，让他接着打。 */
  | { kind: "complete"; text: string }
  /** 这条此刻用不了。 */
  | { kind: "refuse"; entry: CommandEntry }
  /** 没有高亮项（列表空的）。 */
  | { kind: "none" }

/**
 * 菜单开着时按回车该干什么。
 *
 * <h3>为什么回车是「选中高亮那条」，而不是解析敲了一半的文字</h3>
 * 原来沿用的是「唯一前缀才认」那条规矩，于是 `/c` 回车得到「没有 /c 这个命令」——
 * 因为它同时匹配 `/continue` 和 `/clear`。但用户既然敲了斜杠、而且菜单就在眼前
 * 高亮着一条，他要的显然是那一条：<b>没人会故意去敲一个不存在的命令</b>。
 * 让他为了执行一条看得见的命令而先去按 Tab 补全，是白加的一步。
 *
 * <p>参数从<b>用户实际敲的字</b>里取，不是从高亮那条里取——他可能打的是
 * `/pl 把 README 补全`，高亮的是 `/plan`，参数得是后面那句。
 */
export function acceptSelected(
  entries: readonly CommandEntry[],
  selected: number,
  text: string,
): Acceptance {
  const entry = entries[selected]
  if (!entry) {
    return { kind: "none" }
  }
  if (!entry.available) {
    return { kind: "refuse", entry }
  }
  const args = argsOf(text)
  if (entry.args && !args) {
    // 补完留个空格，光标正好停在该打参数的地方
    return { kind: "complete", text: entry.name + " " }
  }
  return { kind: "run", entry, args }
}

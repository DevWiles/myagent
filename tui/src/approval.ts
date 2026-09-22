/**
 * 确认框里那些和渲染无关的判断。
 *
 * 放这儿是因为按键处理在 App 里、画框在 ApprovalDialog 里，两边都要用；
 * 更要紧的是**这些逻辑要能单独测**——确认框是整个界面里唯一一个
 * 「答错了会真删文件」的地方，而它在短终端上可能根本不渲染，
 * 靠肉眼验证是验不到的。
 */

/** 后端提议的一档松紧。`patterns` 是真要存下去的东西，`label` 是给人看的。 */
export type ApprovalSuggestion = { label: string; patterns: string[] }

/**
 * 确认框现在停在哪一步。
 *
 * - `ask` 等用户表态
 * - `rule` 按了 a，正在挑「以后都同意」到什么程度
 * - `feedback` 按了 n，正在补一句话给模型
 */
export type ApprovalStage =
  | { step: "ask" }
  | { step: "rule"; index: number }
  | { step: "feedback"; text: string }

export const ASK_STAGE: ApprovalStage = { step: "ask" }

/** 补充说明最多多少字。和后端的 MAX_FEEDBACK 对齐，超了后端也会截。 */
export const MAX_FEEDBACK = 500

/** diff 最多占几行。再多会把上面的对话顶出屏幕。 */
export const MAX_DIFF_LINES = 12

/**
 * 一组工具调用里，所有 diff 加起来最多占几行。
 *
 * <p>单个 diff 卡 12 行是够的，但**一次新建工程会写七八个文件**——
 * 七块 12 行就是九十行，把上面的对话整个顶出屏幕。
 * 所以按组再卡一道，文件多了每个就少露几行。
 */
export const MAX_DIFF_LINES_PER_GROUP = 28

/** 一个 diff 至少露几行。再少就只剩个 @@ 抬头，不如不画。 */
export const MIN_DIFF_LINES = 3

/**
 * 一组里有 n 个 diff 时，每个分到几行。
 *
 * <p>平分，但两头夹住：不超过单个的上限，也不低于「还看得出点东西」的下限。
 */
export function diffBudgetFor(count: number): number {
  if (count <= 1) return MAX_DIFF_LINES
  const share = Math.floor(MAX_DIFF_LINES_PER_GROUP / count)
  return Math.max(MIN_DIFF_LINES, Math.min(MAX_DIFF_LINES, share))
}

export type DiffLineKind = "add" | "del" | "hunk" | "context"

export function diffLineKind(line: string): DiffLineKind {
  if (line.startsWith("@@")) return "hunk"
  if (line.startsWith("+")) return "add"
  if (line.startsWith("-")) return "del"
  return "context"
}

/**
 * ←/→ 调松紧。**不循环**：撞到头就停在那儿。
 *
 * 循环的话，用户一路按 → 会从最松的那档绕回最紧的，
 * 而他此刻正盯着「以后都同意」几个字——转一圈回到原点是这个框最不该有的意外。
 */
export function nextRuleIndex(current: number, delta: number, count: number): number {
  if (count <= 0) return 0
  return Math.max(0, Math.min(count - 1, current + delta))
}

/** 补充说明的输入：可打印字符往后加，退格往回删。返回 null 表示这个键与我无关。 */
export function editFeedback(text: string, key: { name?: string; sequence?: string }): string | null {
  if (key.name === "backspace") return text.slice(0, -1)
  const typed = key.sequence ?? ""
  // 只收单个可打印字符。方向键之类的转义序列长度不是 1，正好被挡在外面
  if (typed.length !== 1 || typed < " " || typed === "\x7f") return null
  if (text.length >= MAX_FEEDBACK) return text
  return text + typed
}

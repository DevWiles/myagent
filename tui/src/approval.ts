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
 * - `ask` 等用户表态，`index` 是光标停在第几个选项上
 * - `rule` 选了「以后都同意」，正在挑到什么程度
 * - `feedback` 选了拒绝，正在补一句话给模型
 */
export type ApprovalStage =
  | { step: "ask"; index: number }
  | { step: "rule"; index: number }
  | { step: "feedback"; text: string }

export const ASK_STAGE: ApprovalStage = { step: "ask", index: 0 }

/** 表态那一步的一个选项。`key` 决定选中后干什么，`label` 是给人看的。 */
export type AskChoice = { key: "yes" | "always" | "no"; label: string }

/**
 * 表态那一步有哪几个选项。
 *
 * <h3>为什么不再是敲 y/n/a</h3>
 * 字母键要求用户<b>先记住映射再动手</b>，而这个框出现的时机是随机的——
 * 人正看着 diff 想事情，脑子里没挂着「a 是以后都同意」这张表。
 * 光标选择把映射画在屏幕上，代价只是多按一下回车。
 *
 * <p>两套入口不能并存：留着 y/n/a 当快捷键，就会出现「光标停在拒绝上、
 * 手按了 y」这种自相矛盾的一瞬间——而这正是这个框最不能有的东西。
 * 所以字母键整套撤掉，只留数字键直选（按下即生效）和 Esc 拒绝。
 *
 * <p>顺序是「同意 / 以后都同意 / 拒绝」，光标默认停在第一个：绝大多数确认
 * 的答案就是同意，默认停在拒绝上只会训练出闭眼按方向键的习惯。
 * 代价要认账——<b>回车是整个键盘上最反射性的键</b>，这里是唯一一处
 * 手滑要付真代价的地方，所以配了两道防线：选项列表<b>不循环</b>
 * （见 {@link moveSelection}），Esc 永远是一键拒绝的逃生口。
 *
 * @param hasSuggestions 后端有没有提议可存的规则。没有就不画这一项——
 *                       画了按下去也进不去挑规则那一步，是个死选项
 */
export function askChoices(hasSuggestions: boolean): AskChoice[] {
  const choices: AskChoice[] = [{ key: "yes", label: "同意，就这一次" }]
  if (hasSuggestions) {
    choices.push({ key: "always", label: "以后都同意（下一步挑范围）" })
  }
  // 拒绝排最后，而且带着「说说为什么」：拒绝本身只是把路堵上，
  // 那句话才是模型下一步的依据
  choices.push({ key: "no", label: "不要这么做，我说说为什么" })
  return choices
}

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
 * 计划确认框的两个选项。
 *
 * <p>刻意**没有「以后都同意」**：每份计划都不一样，一次性授权掉后面所有计划
 * 是危险的。审批框有这一项、这里没有，是有意的差别（见 docs/protocol.md）。
 */
export const PLAN_CHOICES: readonly string[] = ["执行这份计划", "放弃"]

/**
 * 光标在一列选项里挪一格。表态和挑松紧两步共用。
 *
 * <p><b>不循环</b>：撞到头就停在那儿。循环的话，用户一路按 ↓ 会从最松的那档
 * 绕回最紧的，而他此刻正盯着「以后都同意」几个字——转一圈回到原点
 * 是这个框最不该有的意外。表态那一列同理，而且更要命：
 * 那里绕一圈会从「拒绝」回到「同意」。
 */
export function moveSelection(current: number, delta: number, count: number): number {
  if (count <= 0) return 0
  return Math.max(0, Math.min(count - 1, current + delta))
}

/**
 * 数字键 → 选项下标（1 → 0）。不是 1-9 就返回 null。
 *
 * <p>认 `sequence` 不认 `name`：两者对数字键通常都是 "1"，但 `sequence` 是
 * 终端实际送来的字节，`name` 是库解析出来的名字——解析这一层将来换库会变，
 * 字节不会。0 不收：选项从 1 开始编号，按 0 什么也不该发生。
 */
export function digitIndex(key: { name?: string; sequence?: string }): number | null {
  const typed = key.sequence ?? key.name ?? ""
  if (typed.length !== 1 || typed < "1" || typed > "9") return null
  return Number(typed) - 1
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

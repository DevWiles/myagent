/**
 * 会话选择器的挑选逻辑：搜什么、过滤什么、选中第几条。
 *
 * <p>纯函数，和渲染分开——挑错会话的代价是**拿着过时的上下文继续往下做**，
 * 模型会拿三天前的结论去改今天的代码，语气还一样笃定。
 * 所以「筛出来的到底是哪几条」这件事得能用断言钉住，不能靠在界面上手动试。
 */

/** 后端 `session_list` 里的一行。 */
export type SessionRow = {
  id: string
  title: string
  turns: number
  model: string
  /** 这段是在哪个 git 分支上聊的。不在仓库里、或是格式 1 的老存档时没有。 */
  branch?: string | null
  startedAt: number
  savedAt: number
  bytes: number
}

export type PickerFilter = {
  /** 搜索框里的字。空的话不筛。 */
  query: string
  /** 只看当前分支（Ctrl-B 切）。 */
  branchOnly: boolean
  /** 当前在哪个分支。没有的话 branchOnly 无从谈起。 */
  currentBranch?: string | null
}

/**
 * 按搜索词和分支筛。
 *
 * <p>搜索<b>同时匹配标题和分支名</b>：想找「那段在 main 上聊的」时，
 * 直接打 main 比先按 Ctrl-B 再翻列表顺手。大小写不敏感——
 * 分支名常有大写（`Feature/X`），而没人会为了搜到它去按 shift。
 *
 * <p>`branchOnly` 在「当前根本不在 git 仓库里」时<b>不生效</b>：
 * 那种情况下按一下 Ctrl-B 会把列表清空，用户只会以为会话全没了。
 */
export function filterSessions(rows: readonly SessionRow[], filter: PickerFilter): SessionRow[] {
  const query = filter.query.trim().toLowerCase()
  const branch = filter.currentBranch
  return rows.filter((row) => {
    if (filter.branchOnly && branch && row.branch !== branch) return false
    if (!query) return true
    const haystack = `${row.title} ${row.branch ?? ""}`.toLowerCase()
    return haystack.includes(query)
  })
}

/**
 * 把选中项按回列表里。
 *
 * <p>搜索会让列表随时变短，选中项很容易落在外面。不夹的话按 Enter
 * 会接一段**屏幕上根本没有的会话**——而那正是「挑错会话」最糟的形式：
 * 用户以为自己挑的是看见的那条。
 */
export function clampSelection(index: number, count: number): number {
  if (count <= 0) return 0
  if (index < 0) return 0
  return index >= count ? count - 1 : index
}

/**
 * 面板标题右边那句话：一共几段、这会儿筛出几段。
 *
 * <p>筛掉了多少必须说出来。只显示「3 段」的话，用户没法区分
 * 「这个项目只有 3 段」和「有 20 段但搜索词把别的都滤掉了」。
 */
export function describeFilter(total: number, shown: number, filter: PickerFilter): string {
  if (shown === total) return `${total} 段`
  const why: string[] = []
  if (filter.query.trim()) why.push(`搜「${filter.query.trim()}」`)
  if (filter.branchOnly && filter.currentBranch) why.push(`只看 ${filter.currentBranch}`)
  const reason = why.length > 0 ? `${why.join(" · ")}` : "已筛选"
  return `${shown}/${total} 段 · ${reason}`
}

/**
 * 列表装不下时，显示哪一段。
 *
 * <p>面板高度有限，而会话可能有几十段。窗口得<b>跟着选中项走</b>——
 * 不跟的话，↓ 按到第 11 条时选中项已经在屏幕外面，用户看着一个不动的列表
 * 按方向键，然后按 Enter 接了一段他没看见的会话。
 *
 * @returns 左闭右开的下标区间
 */
export function windowAround(
  count: number,
  selected: number,
  maxRows: number,
): { start: number; end: number } {
  if (count <= 0 || maxRows <= 0) return { start: 0, end: 0 }
  if (count <= maxRows) return { start: 0, end: count }
  // 选中项尽量留在窗口里，越界时贴边
  const start = Math.min(Math.max(0, selected - maxRows + 1), count - maxRows)
  return { start: Math.max(0, Math.min(start, selected)), end: Math.max(0, Math.min(start, selected)) + maxRows }
}

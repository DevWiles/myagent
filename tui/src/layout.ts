/**
 * 底部固定区的高度账本。
 *
 * 为什么要单独一个纯模块:输入框「钉在屏幕最后一行」这件事,最终是靠 Yoga
 * (transcript `flexGrow`、底部 `flexShrink={0}`)保证的,**不是靠这里算**。
 * 这里只负责一件事:在终端太矮的时候,**按固定顺序决定砍掉谁**,好让底部这一摞
 * 的总高永远塞得进屏幕。
 *
 * 所以这些常量的真正价值是「被真正设 `height=` 的那个组件 import」——
 * 数字和几何长在一起,就不可能再各走各路。之前 `App` 里写死一个
 * `approvalRows = 8` 而确认框自己想长多高就长多高,结果窄屏下按钮行折行,
 * 把 `action: detail` 那一行整个挤没了。那不是隐患,是已经在线上的 bug。
 */

/** 输入框:上边框 + 1 行内容 + 下边框。 */
export const INPUT_BOXED_ROWS = 3
/** 降级后的输入框:去掉两根横线,只剩输入行。输入行本身永远不砍。 */
export const INPUT_BARE_ROWS = 1
export const STATUS_ROWS = 1
export const SPINNER_ROWS = 1
export const CLOSED_ROWS = 1
/** 确认框:边框 2 + 标题 1 + 空 1 + 动作 1 + 原因 1 + 空 1 + 按键 1。 */
export const APPROVAL_ROWS = 8
/** 紧凑确认框:动作 / 原因 / 按键,无边框无空行。 */
export const APPROVAL_COMPACT_ROWS = 3
/**
 * 再怎么挤,也要给对话留一行。
 *
 * 唯一的例外是 `rows <= 1`:那时预算是 0,而输入行按约定永不砍,所以
 * `planBottomStack` 会返回 `height = 1 > budget`。这不是 bug,是「1 行终端里
 * 只能二选一」的必然结果 —— 保输入框。不变式因此要写成
 * `height <= max(INPUT_BARE_ROWS, rows - MIN_VIEWPORT_ROWS)`。
 */
export const MIN_VIEWPORT_ROWS = 1

export type BottomState = {
  busy: boolean
  hasApproval: boolean
  hasClosed: boolean
}

export type BottomPlan = {
  spinner: boolean
  approval: 'full' | 'compact' | 'none'
  closed: boolean
  input: 'boxed' | 'bare'
  status: boolean
  /**
   * 这套方案下底部固定区的总高。
   *
   * **只用于两件事**:挑降级档位、给 TranscriptView 首帧一个 seed 高度。
   * 真正的钉底由 Yoga 完成,所以这个数算错了也只是首帧抖一下,不会让输入框漂。
   */
  height: number
}

function planHeight(plan: Omit<BottomPlan, 'height'>): number {
  return (
    (plan.spinner ? SPINNER_ROWS : 0) +
    (plan.approval === 'full'
      ? APPROVAL_ROWS
      : plan.approval === 'compact'
        ? APPROVAL_COMPACT_ROWS
        : 0) +
    (plan.closed ? CLOSED_ROWS : 0) +
    (plan.input === 'boxed' ? INPUT_BOXED_ROWS : INPUT_BARE_ROWS) +
    (plan.status ? STATUS_ROWS : 0)
  )
}

/**
 * 决定底部这一摞放什么,保证 `height <= rows - MIN_VIEWPORT_ROWS`。
 *
 * 降级顺序(先砍装饰,后砍功能):
 *   1. 状态栏      —— 纯装饰,输入框优先
 *   2. spinner     —— 它是「还活着」的信号,但输入框不能用更糟
 *   3. 关闭提示    —— 都要退出了,原因在对话里也有
 *   4. 输入框边框  —— 省 2 行,输入行保住
 *   5. 确认框转紧凑 —— 省 5 行
 *   6. 确认框整个去掉 —— **最后手段**
 *
 * 第 6 条要格外小心:确认框出现时后端的 agent 线程正卡在 `approve()` 上等回答。
 * 把框藏掉会让用户既看不到问题、也不知道按什么,会话就彻底挂死。所以宁可先砍到
 * 只剩输入行,也要把框留着;真到了放不下的地步,调用方必须保证按键处理还活着
 * (y/n/a 仍能答),这一点写在 App 里。
 */
export function planBottomStack(rows: number, state: BottomState): BottomPlan {
  const budget = Math.max(0, rows - MIN_VIEWPORT_ROWS)

  const plan: Omit<BottomPlan, 'height'> = {
    spinner: state.busy && !state.hasApproval,
    approval: state.hasApproval ? 'full' : 'none',
    closed: state.hasClosed,
    input: 'boxed',
    status: true,
  }

  const ladder: Array<() => boolean> = [
    () => (plan.status ? ((plan.status = false), true) : false),
    () => (plan.spinner ? ((plan.spinner = false), true) : false),
    () => (plan.closed ? ((plan.closed = false), true) : false),
    () => (plan.input === 'boxed' ? ((plan.input = 'bare'), true) : false),
    () => (plan.approval === 'full' ? ((plan.approval = 'compact'), true) : false),
    () => (plan.approval === 'compact' ? ((plan.approval = 'none'), true) : false),
  ]

  for (const drop of ladder) {
    if (planHeight(plan) <= budget) break
    drop()
  }

  // 降级梯是贪心的,会砍过头:rows=8 带确认框时它一路砍到只剩 4 行,而 7 行本来
  // 装得下。所以再做一次「有余量就还回来」的回补,顺序正好是降级顺序的反过来
  // (先还功能,后还装饰)。少了这一步,矮屏下会白丢掉关闭原因和输入框边框。
  const restore: Array<() => boolean> = [
    () => (plan.approval === 'none' && state.hasApproval ? ((plan.approval = 'compact'), true) : false),
    () => (plan.approval === 'compact' ? ((plan.approval = 'full'), true) : false),
    () => (plan.input === 'bare' ? ((plan.input = 'boxed'), true) : false),
    () => (!plan.closed && state.hasClosed ? ((plan.closed = true), true) : false),
    () => (!plan.spinner && state.busy && !state.hasApproval ? ((plan.spinner = true), true) : false),
    () => (!plan.status ? ((plan.status = true), true) : false),
  ]

  for (const undo of restore) {
    const before = { ...plan }
    if (!undo()) continue
    // 回补后超了就撤回这一步,但继续试后面更便宜的
    if (planHeight(plan) > budget) Object.assign(plan, before)
  }

  return { ...plan, height: planHeight(plan) }
}

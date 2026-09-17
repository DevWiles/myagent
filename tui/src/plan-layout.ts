import type { PlanTask } from './store.ts'

/**
 * 计划面板的高度收敛。
 *
 * 面板钉在输入框下边，占的每一行都是从对话区身上抢来的。一份十步的计划全展开
 * （每步两行）就是二十行——终端一共才三四十行，对话区会被挤到只剩个缝。
 * 所以这里要在「显示多少」和「留多少给对话」之间做取舍。
 *
 * 三档降级，逐级放弃信息量：
 * ```
 *   1. 每步两行（编号那行 + 描述）        —— 放得下就这样
 *   2. 每步一行（描述省掉）              —— 步骤多了先砍描述，因为编号行里
 *                                          有状态、类型、依赖，信息密度高得多
 *   3. 只显示当前这步附近的一个窗口       —— 还放不下就只保当下
 * ```
 *
 * 拆成纯函数是为了能单测：这里的取舍全是边界条件（正好放得下、差一行、
 * 焦点在头尾），在组件里靠肉眼看渲染结果是查不出来的。
 */

export type PlanPanelLayout = {
  /** 要画出来的任务，按原顺序。 */
  tasks: PlanTask[]
  /** 每个任务要不要带描述行。 */
  withDescription: boolean
  /** 窗口上方藏了几步。大于 0 时面板要提一句，不然用户以为计划就这么点。 */
  hiddenBefore: number
  hiddenAfter: number
}

/**
 * 当前该盯着哪一步：正在跑的那个；没有就是第一个还没了结的；再没有就是最后一个。
 *
 * <p>最后一档不是凑数——计划跑完之后用户最关心的是结尾（成没成、最后一步说了什么），
 * 这时候把窗口停在开头没有任何意义。
 */
export function focusIndex(tasks: readonly PlanTask[]): number {
  const running = tasks.findIndex(task => task.status === 'RUNNING')
  if (running >= 0) return running
  const pending = tasks.findIndex(task => task.status === 'PENDING')
  if (pending >= 0) return pending
  return Math.max(0, tasks.length - 1)
}

export function layoutPlanPanel(
  tasks: readonly PlanTask[],
  maxRows: number,
): PlanPanelLayout {
  const all = [...tasks]
  if (all.length === 0) {
    return { tasks: [], withDescription: false, hiddenBefore: 0, hiddenAfter: 0 }
  }

  const budget = Math.max(1, Math.floor(maxRows))
  if (all.length * 2 <= budget) {
    return { tasks: all, withDescription: true, hiddenBefore: 0, hiddenAfter: 0 }
  }
  if (all.length <= budget) {
    return { tasks: all, withDescription: false, hiddenBefore: 0, hiddenAfter: 0 }
  }

  // 开窗。留一行给「…前面还有 N 步」那句提示——**不留的话用户根本不知道有东西被藏了**，
  // 会以为计划就这么几步。两端都可能有省略，但同一时刻最多占一行提示，所以只扣一行。
  const slots = Math.max(1, budget - 1)
  const focus = focusIndex(all)
  let start = focus - Math.floor((slots - 1) / 2)
  start = Math.max(0, Math.min(start, all.length - slots))

  return {
    tasks: all.slice(start, start + slots),
    withDescription: false,
    hiddenBefore: start,
    hiddenAfter: all.length - start - slots,
  }
}

/** 已了结的步数 / 总步数，画进度条用。 */
export function planProgress(tasks: readonly PlanTask[]): number {
  if (tasks.length === 0) return 0
  const done = tasks.filter(
    task => task.status === 'SUCCEEDED' || task.status === 'FAILED' || task.status === 'SKIPPED',
  ).length
  return Math.round((done / tasks.length) * 100)
}

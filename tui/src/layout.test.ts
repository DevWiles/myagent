import { describe, expect, test } from 'bun:test'
import {
  APPROVAL_COMPACT_ROWS,
  APPROVAL_ROWS,
  CLOSED_ROWS,
  INPUT_BARE_ROWS,
  INPUT_BOXED_ROWS,
  MIN_VIEWPORT_ROWS,
  SPINNER_ROWS,
  STATUS_ROWS,
  planBottomStack,
  type BottomState,
} from './layout.ts'

const STATES: BottomState[] = [false, true].flatMap(busy =>
  [false, true].flatMap(hasApproval =>
    [false, true].map(hasClosed => ({ busy, hasApproval, hasClosed })),
  ),
)

function expectedHeight(plan: ReturnType<typeof planBottomStack>): number {
  return (
    (plan.spinner ? SPINNER_ROWS : 0) +
    (plan.approval === 'full' ? APPROVAL_ROWS : plan.approval === 'compact' ? APPROVAL_COMPACT_ROWS : 0) +
    (plan.closed ? CLOSED_ROWS : 0) +
    (plan.input === 'boxed' ? INPUT_BOXED_ROWS : INPUT_BARE_ROWS) +
    (plan.status ? STATUS_ROWS : 0)
  )
}

describe('planBottomStack', () => {
  test('底部固定区永远塞得进屏幕（rows 1..120 × 全部状态组合）', () => {
    for (let rows = 1; rows <= 120; rows++) {
      for (const state of STATES) {
        const plan = planBottomStack(rows, state)
        // rows<=1 是退化情形：预算为 0 而输入行永不砍，所以上限取 max(...)
        const cap = Math.max(INPUT_BARE_ROWS, rows - MIN_VIEWPORT_ROWS)
        expect(plan.height).toBeLessThanOrEqual(cap)
      }
    }
  })

  test('height 和方案本身是一致的（改了常量却忘了改组件就会在这里露出来）', () => {
    for (let rows = 1; rows <= 60; rows++) {
      for (const state of STATES) {
        const plan = planBottomStack(rows, state)
        expect(plan.height).toBe(expectedHeight(plan))
      }
    }
  })

  test('输入行永远不被砍掉', () => {
    for (let rows = 1; rows <= 120; rows++) {
      for (const state of STATES) {
        expect(['boxed', 'bare']).toContain(planBottomStack(rows, state).input)
      }
    }
  })

  test('宽松的终端上什么都不砍', () => {
    const plan = planBottomStack(40, { busy: false, hasApproval: true, hasClosed: true })
    expect(plan).toMatchObject({ approval: 'full', input: 'boxed', closed: true, status: true })
  })

  test('降级顺序：先砍装饰（状态栏/spinner），后砍功能', () => {
    // 刚好装不下完整底部时，第一个牺牲的是状态栏
    const busy = { busy: true, hasApproval: false, hasClosed: false }
    const full = SPINNER_ROWS + INPUT_BOXED_ROWS + STATUS_ROWS
    const plan = planBottomStack(full /* 预算 = full-1，少 1 行 */, busy)
    expect(plan.status).toBe(false)
    expect(plan.spinner).toBe(true)
    expect(plan.input).toBe('boxed')
  })

  test('spinner 和确认框不会同时出现（确认框优先）', () => {
    const plan = planBottomStack(40, { busy: true, hasApproval: true, hasClosed: false })
    expect(plan.spinner).toBe(false)
    expect(plan.approval).toBe('full')
  })

  test('没有对应状态时不会凭空造出块来', () => {
    const plan = planBottomStack(40, { busy: false, hasApproval: false, hasClosed: false })
    expect(plan).toMatchObject({ spinner: false, approval: 'none', closed: false })
  })

  test('矮屏不会砍过头——有余量就把功能还回来', () => {
    // 这是回补逻辑的回归点：贪心梯子曾经在 rows=8 一路砍到 4 行，
    // 而 7 行本来装得下（紧凑确认框 + 带边框输入框 + 关闭行）。
    const plan = planBottomStack(8, { busy: true, hasApproval: true, hasClosed: true })
    expect(plan.height).toBeGreaterThan(4)
    expect(plan.height).toBeLessThanOrEqual(7)
    expect(plan.approval).not.toBe('none')
  })

  test('极矮终端仍然给出可用方案，不抛异常', () => {
    for (const rows of [0, 1, 2, 3]) {
      const plan = planBottomStack(rows, { busy: true, hasApproval: true, hasClosed: true })
      expect(plan.input).toBe('bare')
      expect(plan.height).toBeGreaterThanOrEqual(INPUT_BARE_ROWS)
    }
  })
})

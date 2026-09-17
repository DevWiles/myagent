import { describe, expect, test } from 'bun:test'
import { focusIndex, layoutPlanPanel, planProgress } from './plan-layout.ts'
import type { PlanTask } from './store.ts'

function task(id: string, status = 'PENDING'): PlanTask {
  return { id, type: 'FILE_READ', description: `做 ${id}`, status, parentId: null, dependsOn: [] }
}

function tasks(n: number, status = 'PENDING'): PlanTask[] {
  return Array.from({ length: n }, (_, i) => task(`t${i + 1}`, status))
}

describe('计划面板的高度收敛', () => {
  test('放得下就每步两行，带上描述', () => {
    const layout = layoutPlanPanel(tasks(5), 10)

    expect(layout.withDescription).toBe(true)
    expect(layout.tasks).toHaveLength(5)
    expect(layout.hiddenBefore).toBe(0)
    expect(layout.hiddenAfter).toBe(0)
  })

  test('差一行放不下两行制，先砍描述——编号行信息密度高得多', () => {
    const layout = layoutPlanPanel(tasks(5), 9)

    expect(layout.withDescription).toBe(false)
    expect(layout.tasks).toHaveLength(5)
    expect(layout.hiddenAfter).toBe(0)
  })

  test('砍完描述还放不下才开窗', () => {
    const layout = layoutPlanPanel(tasks(20), 8)

    expect(layout.tasks.length).toBeLessThan(20)
    expect(layout.hiddenBefore + layout.tasks.length + layout.hiddenAfter).toBe(20)
  })

  test('开窗要留一行给提示——不留的话用户以为计划就这么几步', () => {
    const layout = layoutPlanPanel(tasks(20), 8)

    expect(layout.tasks).toHaveLength(7)
    expect(layout.hiddenBefore + layout.hiddenAfter).toBeGreaterThan(0)
  })

  test('窗口跟着正在跑的那一步走', () => {
    const list = tasks(20)
    list[14] = task('t15', 'RUNNING')

    const layout = layoutPlanPanel(list, 8)

    expect(layout.tasks.map(t => t.id)).toContain('t15')
  })

  test('焦点在开头时窗口不越界往左跑', () => {
    const list = tasks(20)
    list[0] = task('t1', 'RUNNING')

    const layout = layoutPlanPanel(list, 8)

    expect(layout.hiddenBefore).toBe(0)
    expect(layout.tasks[0]?.id).toBe('t1')
  })

  test('焦点在结尾时窗口贴到最后，不留空槽', () => {
    const list = tasks(20, 'SUCCEEDED')
    list[19] = task('t20', 'RUNNING')

    const layout = layoutPlanPanel(list, 8)

    expect(layout.hiddenAfter).toBe(0)
    expect(layout.tasks.at(-1)?.id).toBe('t20')
  })

  test('全跑完之后停在结尾——这时用户关心的是结果，不是开头', () => {
    const layout = layoutPlanPanel(tasks(20, 'SUCCEEDED'), 8)

    expect(layout.hiddenAfter).toBe(0)
    expect(layout.tasks.at(-1)?.id).toBe('t20')
  })

  test('预算小到离谱也得给出一步，不能画个空框', () => {
    const layout = layoutPlanPanel(tasks(20), 1)

    expect(layout.tasks).toHaveLength(1)
    expect(layout.tasks.length + layout.hiddenBefore + layout.hiddenAfter).toBe(20)
  })

  test('空计划不炸', () => {
    const layout = layoutPlanPanel([], 10)

    expect(layout.tasks).toHaveLength(0)
    expect(layout.hiddenBefore).toBe(0)
  })
})

describe('焦点', () => {
  test('优先正在跑的', () => {
    const list = [task('t1', 'SUCCEEDED'), task('t2', 'RUNNING'), task('t3')]
    expect(focusIndex(list)).toBe(1)
  })

  test('没在跑的就看第一个还没了结的', () => {
    const list = [task('t1', 'SUCCEEDED'), task('t2', 'FAILED'), task('t3')]
    expect(focusIndex(list)).toBe(2)
  })

  test('全了结了就看最后一个', () => {
    const list = [task('t1', 'SUCCEEDED'), task('t2', 'SKIPPED')]
    expect(focusIndex(list)).toBe(1)
  })
})

describe('进度', () => {
  test('跳过和失败都算「了结了」——它们不会再动了', () => {
    const list = [
      task('t1', 'SUCCEEDED'),
      task('t2', 'FAILED'),
      task('t3', 'SKIPPED'),
      task('t4', 'RUNNING'),
    ]
    expect(planProgress(list)).toBe(75)
  })

  test('一步没跑是 0，空计划也是 0 而不是 NaN', () => {
    expect(planProgress(tasks(3))).toBe(0)
    expect(planProgress([])).toBe(0)
  })
})

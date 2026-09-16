/**
 * store 是整个界面唯一有状态的地方，也是唯一值得单独测的地方：
 * 组件只是把 state 画出来，而「事件流怎么归并成消息」有很多容易搞错的边界。
 */

import { describe, expect, test } from 'bun:test'
import type { ServerEvent } from './protocol.ts'
import { SessionStore, type Entry } from './store.ts'

function ready(): ServerEvent {
  return {
    t: 'ready',
    protocol: 1,
    version: '0.2.0',
    workspace: '/tmp/x',
    model: 'deepseek-chat',
    planMode: false,
    hasApiKey: true,
    busy: false,
    tools: [{ name: 'read_file', description: '读文件' }],
  }
}

function feed(store: SessionStore, ...events: ServerEvent[]): void {
  for (const event of events) store.apply(event)
}

/** 取 entry 种类序列，用来断言归并结果。 */
function kinds(entries: readonly Entry[]): string[] {
  return entries.map(e => e.kind)
}

describe('流式文本', () => {
  test('连续的增量并进同一条消息，而不是每个字一条', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'text_delta', text: '这是' },
      { t: 'text_delta', text: '一句' },
      { t: 'text_delta', text: '话' })

    const { entries: current } = store.getSnapshot()
    expect(kinds(current)).toEqual(['assistant'])
    expect(current[0]).toMatchObject({ kind: 'assistant', text: '这是一句话' })
  })

  test('工具调用打断文本后，后面的文本是新的一条', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'text_delta', text: '我先看看' },
      { t: 'tool_started', id: 1, tool: 'read_file', arguments: '{}' },
      { t: 'tool_finished', id: 1, tool: 'read_file', ok: true, output: 'x' },
      { t: 'text_delta', text: '看完了' })

    expect(kinds(store.getSnapshot().entries)).toEqual(['assistant', 'tools', 'assistant'])
  })
})

describe('Thought 去重', () => {
  test('流式已经打过的内容不再重复渲染一遍', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'text_delta', text: '我先读一下 README。' },
      { t: 'thought', text: '我先读一下 README。' })

    expect(kinds(store.getSnapshot().entries)).toEqual(['assistant'])
  })

  test('非流式后端只发 Thought，此时它是唯一来源，必须显示', () => {
    const store = new SessionStore()
    feed(store, { t: 'thought', text: '我先读一下 README。' })

    expect(kinds(store.getSnapshot().entries)).toEqual(['thought'])
  })

  test('内容不同的 Thought 照常显示', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'text_delta', text: '好的。' },
      { t: 'thought', text: '我打算先跑一下测试。' })

    expect(kinds(store.getSnapshot().entries)).toEqual(['assistant', 'thought'])
  })
})

describe('工具分组', () => {
  test('连着调用的工具归进同一组，好收成一行摘要', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'tool_started', id: 1, tool: 'read_file', arguments: '{"path":"a"}' },
      { t: 'tool_finished', id: 1, tool: 'read_file', ok: true, output: 'a' },
      { t: 'tool_started', id: 2, tool: 'read_file', arguments: '{"path":"b"}' },
      { t: 'tool_finished', id: 2, tool: 'read_file', ok: true, output: 'b' })

    const entry = store.getSnapshot().entries[0]
    expect(entry?.kind).toBe('tools')
    expect(entry?.kind === 'tools' && entry.calls).toHaveLength(2)
  })

  test('结果按 id 回填到对应那一条，不会串行', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'tool_started', id: 1, tool: 'bash', arguments: '{"cmd":"ls"}' },
      { t: 'tool_started', id: 2, tool: 'bash', arguments: '{"cmd":"pwd"}' },
      { t: 'tool_finished', id: 2, tool: 'bash', ok: false, output: '炸了' })

    const entry = store.getSnapshot().entries[0]
    if (entry?.kind !== 'tools') throw new Error('应该是工具组')
    expect(entry.calls[0]).toMatchObject({ id: 1, running: true })
    expect(entry.calls[1]).toMatchObject({ id: 2, running: false, ok: false, output: '炸了' })
  })

  test('没有对应工具组时收到 tool_finished 不该崩', () => {
    const store = new SessionStore()
    expect(() =>
      feed(store, { t: 'tool_finished', id: 9, tool: 'bash', ok: true, output: '' }),
    ).not.toThrow()
  })
})

describe('done 兜底', () => {
  test('流式走通了就不追加重复的全文', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'text_delta', text: '答案是 42' },
      { t: 'done', text: '答案是 42', steps: 1 })

    expect(kinds(store.getSnapshot().entries)).toEqual(['assistant'])
  })

  test('流式没走通时用 done 带的全文，否则这一轮一个字都没有', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'tool_started', id: 1, tool: 'read_file', arguments: '{}' },
      { t: 'tool_finished', id: 1, tool: 'read_file', ok: true, output: 'x' },
      { t: 'done', text: '答案是 42', steps: 2 })

    const current = store.getSnapshot().entries
    expect(kinds(current)).toEqual(['tools', 'assistant'])
    expect(current[1]).toMatchObject({ text: '答案是 42' })
  })

  test('done 文本为空时不追加空消息', () => {
    const store = new SessionStore()
    feed(store, { t: 'done', text: '   ', steps: 1 })
    expect(store.getSnapshot().entries).toHaveLength(0)
  })
})

describe('多轮对话', () => {
  test('整个 transcript 是一个数组，新一轮只是追加，不冻结任何东西', () => {
    const store = new SessionStore()
    store.submitPrompt('第一个问题')
    feed(store, { t: 'text_delta', text: '第一个答案' })
    store.submitPrompt('第二个问题')

    const { entries } = store.getSnapshot()
    expect(kinds(entries)).toEqual(['user', 'assistant', 'user'])
    expect(entries[2]).toMatchObject({ text: '第二个问题' })
  })

  test('展开态跨轮保留——全屏下历史消息也活着，翻上去还能看展开的过程', () => {
    const store = new SessionStore()
    store.submitPrompt('问题')
    feed(store,
      { t: 'tool_started', id: 1, tool: 'bash', arguments: '{}' },
      { t: 'tool_finished', id: 1, tool: 'bash', ok: true, output: 'x' })
    store.toggleExpanded()
    expect(store.getSnapshot().expanded.size).toBe(1)

    store.submitPrompt('下一个问题')
    // 不清空：上一轮的工具组还活着、还能展开着看
    expect(store.getSnapshot().expanded.size).toBe(1)
  })

  test('Ctrl-O 覆盖全部轮次的工具组——全屏下都可展开', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'tool_started', id: 1, tool: 'bash', arguments: '{}' },
      { t: 'tool_finished', id: 1, tool: 'bash', ok: true, output: 'x' },
      { t: 'text_delta', text: '第一轮结束' })
    store.submitPrompt('再来')
    feed(store,
      { t: 'tool_started', id: 2, tool: 'read_file', arguments: '{}' },
      { t: 'tool_finished', id: 2, tool: 'read_file', ok: true, output: 'y' })

    store.toggleExpanded()
    expect(store.getSnapshot().expanded.size).toBe(2)
  })

  test('toggleEntry 精确控制单条（鼠标点击走这条），不影响其它', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'tool_started', id: 1, tool: 'bash', arguments: '{}' },
      { t: 'tool_finished', id: 1, tool: 'bash', ok: true, output: 'x' })
    const key = store.getSnapshot().entries[0]!.key

    store.toggleEntry(key)
    expect(store.getSnapshot().expanded.has(key)).toBe(true)
    store.toggleEntry(key)
    expect(store.getSnapshot().expanded.has(key)).toBe(false)
  })
})

describe('Ctrl-O 展开', () => {
  test('只对工具组生效，反复按是开关', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'text_delta', text: '文本' },
      { t: 'tool_started', id: 1, tool: 'bash', arguments: '{}' },
      { t: 'tool_finished', id: 1, tool: 'bash', ok: true, output: 'x' })

    store.toggleExpanded()
    expect(store.getSnapshot().expanded.size).toBe(1)
    store.toggleExpanded()
    expect(store.getSnapshot().expanded.size).toBe(0)
  })

  test('这一轮没有工具调用时按了不该有副作用', () => {
    const store = new SessionStore()
    feed(store, { t: 'text_delta', text: '纯聊天' })
    store.toggleExpanded()
    expect(store.getSnapshot().expanded.size).toBe(0)
  })
})

describe('状态栏数据', () => {
  test('一轮结束时回填耗时和上下文占用，并把步数归零', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'busy', busy: true },
      { t: 'thinking', step: 3 })
    expect(store.getSnapshot().step).toBe(3)

    feed(store, { t: 'busy', busy: false, durationMs: 4200, tokens: 1234, contextLimit: 64000 })
    const state = store.getSnapshot()
    expect(state).toMatchObject({
      busy: false,
      step: 0,
      lastDurationMs: 4200,
      tokens: 1234,
      contextLimit: 64000,
    })
    expect(state.startedAt).toBeNull()
  })

  test('busy 结束时要把悬着的确认框收掉，否则界面永远停在那个框上', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'approval_request', id: 1, action: '写入文件', detail: 'a.txt', reason: '会覆盖' })
    expect(store.getSnapshot().approval).not.toBeNull()

    feed(store, { t: 'busy', busy: false })
    expect(store.getSnapshot().approval).toBeNull()
  })
})

describe('其它', () => {
  test('没有 API key 时给出可操作的提示，而不是静默不能用', () => {
    const store = new SessionStore()
    feed(store, { ...ready(), hasApiKey: false } as ServerEvent)

    const notice = store.getSnapshot().entries[0]
    expect(notice?.kind).toBe('notice')
    expect(notice?.kind === 'notice' && notice.level).toBe('error')
    expect(notice?.kind === 'notice' && notice.text).toContain('DEEPSEEK_API_KEY')
  })

  test('ready 会把工具清单带进来，/tools 才有东西可列', () => {
    const store = new SessionStore()
    feed(store, ready())
    expect(store.getSnapshot().ready?.tools).toHaveLength(1)
  })

  test('reset_done 清空全部记录', () => {
    const store = new SessionStore()
    store.submitPrompt('问题')
    feed(store, { t: 'text_delta', text: '答案' })
    store.submitPrompt('再一个问题')

    feed(store, { t: 'reset_done' })
    expect(kinds(store.getSnapshot().entries)).toEqual(['notice'])
  })

  test('快照是不可变的：变更后拿到的是新对象，useSyncExternalStore 才能察觉', () => {
    const store = new SessionStore()
    const before = store.getSnapshot()
    feed(store, { t: 'text_delta', text: 'x' })
    expect(store.getSnapshot()).not.toBe(before)
  })

  test('没变更时快照引用保持不变，避免无意义重渲染', () => {
    const store = new SessionStore()
    feed(store, { t: 'pong' })
    const before = store.getSnapshot()
    feed(store, { t: 'pong' })
    expect(store.getSnapshot()).toBe(before)
  })
})

// ------------------------------------------------------------ 先规划后执行

import type { PlanTaskWire } from './protocol.ts'

function wireTask(id: string, deps: string[] = [], parentId: string | null = null): PlanTaskWire {
  return { id, type: 'FILE_READ', description: `任务 ${id}`, status: 'PENDING', parentId, dependsOn: deps }
}

function planCreated(): ServerEvent {
  return {
    t: 'plan_created',
    goal: '给 Task 加上重试能力',
    tasks: [wireTask('t1'), wireTask('t2'), wireTask('t3', ['t1', 't2'])],
    layers: [['t1', 't2'], ['t3']],
  }
}

function planOf(store: SessionStore) {
  const entry = store.getSnapshot().entries.find(e => e.kind === 'plan')
  if (!entry || entry.kind !== 'plan') throw new Error('没有计划面板')
  return entry.plan
}

describe('计划面板', () => {
  test('plan_created 落成一条 plan entry，goal 和分层原样保留', () => {
    const store = new SessionStore()
    feed(store, planCreated())

    const plan = planOf(store)
    expect(plan.goal).toBe('给 Task 加上重试能力')
    expect(plan.status).toBe('CREATED')
    expect(plan.tasks.map(t => t.id)).toEqual(['t1', 't2', 't3'])
    expect(plan.layers).toEqual([['t1', 't2'], ['t3']])
  })

  test('确认请求像审批一样置起标志，回答后由界面清掉', () => {
    const store = new SessionStore()
    feed(store, planCreated(), { t: 'plan_confirm_request', id: 1 })

    expect(store.getSnapshot().planConfirm).toEqual({ id: 1 })
    store.clearPlanConfirm()
    expect(store.getSnapshot().planConfirm).toBeNull()
  })

  test('任务开始/结束原地点亮，哪怕计划面板后面已经堆了流式输出', () => {
    const store = new SessionStore()
    feed(store,
      planCreated(),
      { t: 'plan_task_started', taskId: 't1', taskType: 'FILE_READ', description: '任务 t1' },
      // 执行期间的 ReAct 事件照常进 transcript，把 plan entry 挤离队尾——
      // 这正是 updatePlan 按 key 找而不用 replaceTail 的原因
      { t: 'text_delta', text: '我先读一下' },
      { t: 'plan_task_finished', taskId: 't1', status: 'SUCCEEDED', durationMs: 7000, result: '读完了', error: null })

    const plan = planOf(store)
    expect(plan.status).toBe('RUNNING')
    expect(plan.tasks[0]).toMatchObject({ id: 't1', status: 'SUCCEEDED', durationMs: 7000 })
    expect(plan.tasks[1]?.status).toBe('PENDING')
    expect(kinds(store.getSnapshot().entries)).toEqual(['plan', 'assistant'])
  })

  test('跳过的任务把被谁连累记在 error 里', () => {
    const store = new SessionStore()
    feed(store, planCreated(),
      { t: 'plan_task_finished', taskId: 't3', status: 'SKIPPED', durationMs: 0, result: null, error: '上游 t1 失败' })

    expect(planOf(store).tasks[2]).toMatchObject({ status: 'SKIPPED', error: '上游 t1 失败' })
  })

  test('动态拆出的子任务插在父任务后面，不是甩到末尾', () => {
    const store = new SessionStore()
    feed(store, planCreated(),
      { t: 'plan_tasks_added', parentId: 't1', tasks: [wireTask('t1.1', [], 't1')] },
      { t: 'plan_tasks_added', parentId: 't1', tasks: [wireTask('t1.2', ['t1.1'], 't1')] })

    expect(planOf(store).tasks.map(t => t.id)).toEqual(['t1', 't1.1', 't1.2', 't2', 't3'])
    expect(planOf(store).tasks[1]?.parentId).toBe('t1')
  })

  test('plan_finished 盖棺定论并带上收支，同时清掉可能悬着的确认框', () => {
    const store = new SessionStore()
    feed(store, planCreated(),
      { t: 'plan_confirm_request', id: 1 },
      { t: 'plan_finished', status: 'COMPLETED', succeeded: 3, failed: 0, skipped: 0, durationMs: 42000 })

    const plan = planOf(store)
    expect(plan.status).toBe('COMPLETED')
    expect(plan.summary).toEqual({ succeeded: 3, failed: 0, skipped: 0, durationMs: 42000 })
    expect(store.getSnapshot().planConfirm).toBeNull()
  })

  test('用户否决：只有 CANCELLED，没有收支——不能编造三个零', () => {
    const store = new SessionStore()
    feed(store, planCreated(), { t: 'plan_finished', status: 'CANCELLED' })

    const plan = planOf(store)
    expect(plan.status).toBe('CANCELLED')
    expect(plan.summary).toBeUndefined()
  })

  test('第二份计划各自独立：更新只落在最新那份上', () => {
    const store = new SessionStore()
    feed(store, planCreated(), { t: 'plan_finished', status: 'COMPLETED', succeeded: 3, failed: 0, skipped: 0, durationMs: 1000 })
    feed(store, planCreated(),
      { t: 'plan_task_finished', taskId: 't1', status: 'FAILED', durationMs: 5, result: null, error: '炸了' })

    const plans = store.getSnapshot().entries.filter(e => e.kind === 'plan')
    expect(plans).toHaveLength(2)
    expect(plans[0]).toMatchObject({ plan: { status: 'COMPLETED' } })
    expect(plans[1]).toMatchObject({ plan: { tasks: expect.arrayContaining([expect.objectContaining({ id: 't1', status: 'FAILED' })]) } })
  })

  test('ready 没带 features 的老后端：features 是空数组，不是 undefined', () => {
    const store = new SessionStore()
    feed(store, ready())

    expect(store.getSnapshot().ready?.features).toEqual([])
  })
})

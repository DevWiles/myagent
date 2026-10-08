/**
 * store 是整个界面唯一有状态的地方，也是唯一值得单独测的地方：
 * 组件只是把 state 画出来，而「事件流怎么归并成消息」有很多容易搞错的边界。
 */

import { describe, expect, test } from 'bun:test'
import type { ServerEvent } from './protocol.ts'
import { modeIsAvailable, nextMode, SessionStore, type Entry } from './store.ts'

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

  test('写文件带回来的 diff 要存进那一条——界面唯一能显示「改了什么」的来源', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'tool_started', id: 1, tool: 'write_file', arguments: '{"path":"a.java"}' },
      { t: 'tool_finished', id: 1, tool: 'write_file', ok: true, output: '已写入 a.java',
        diff: '@@ -1 +1 @@\n- 旧\n+ 新' })

    const entry = store.getSnapshot().entries[0]
    if (entry?.kind !== 'tools') throw new Error('应该是工具组')
    expect(entry.calls[0]?.diff).toContain('+ 新')
  })

  test('没带 diff 的工具就是没有——不该凭空造一个空串出来', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'tool_started', id: 1, tool: 'read_file', arguments: '{}' },
      { t: 'tool_finished', id: 1, tool: 'read_file', ok: true, output: '内容' })

    const entry = store.getSnapshot().entries[0]
    if (entry?.kind !== 'tools') throw new Error('应该是工具组')
    expect(entry.calls[0]?.diff).toBeUndefined()
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

  test('老后端不发新字段时给默认值，界面不该显示 undefined', () => {
    const store = new SessionStore()
    feed(store,
      { t: 'approval_request', id: 1, action: '执行命令', detail: 'rm x', reason: '会删' })

    const approval = store.getSnapshot().approval!
    expect(approval.kind).toBe('bash')
    expect(approval.preview).toBe('')
    expect(approval.origin).toBe('')
    expect(approval.suggestions).toEqual([])
  })

  test('新字段照原样收下', () => {
    const store = new SessionStore()
    feed(store, askWithRules())

    const approval = store.getSnapshot().approval!
    expect(approval.kind).toBe('bash')
    expect(approval.origin).toBe('编码者 · t3')
    expect(approval.suggestions.length).toBe(3)
    expect(approval.preferred).toBe(1)
  })
})

/** 一条带全套新字段的审批请求。 */
function askWithRules(): ServerEvent {
  return {
    t: 'approval_request',
    id: 7,
    kind: 'bash',
    action: '执行命令',
    detail: 'mvn -o test -Dtest=Foo',
    reason: '未知命令 mvn',
    preview: '',
    origin: '编码者 · t3',
    suggestions: [
      { label: 'mvn -o test -Dtest=Foo', patterns: ['mvn -o test -Dtest=Foo'] },
      { label: 'mvn -o test *', patterns: ['mvn -o test *'] },
      { label: 'mvn *', patterns: ['mvn *'] },
    ],
    preferred: 1,
  } as ServerEvent
}

describe('确认框的三个交互态', () => {
  test('新的确认请求，光标停在第一项「同意」上', () => {
    const store = new SessionStore()
    feed(store, askWithRules())

    expect(store.getSnapshot().approvalStage).toEqual({ step: 'ask', index: 0 })
  })

  test('↑↓ 在表态那一列挪光标，撞到两头停住', () => {
    // 绕回去的代价在这一列最大：一路按 ↓ 会从「拒绝」转回「同意」
    const store = new SessionStore()
    feed(store, askWithRules())        // 有提议 → 三项

    store.moveChoice(-1)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'ask', index: 0 })
    store.moveChoice(1)
    store.moveChoice(1)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'ask', index: 2 })
    store.moveChoice(1)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'ask', index: 2 })
  })

  test('后端没提议规则时只有两项，光标挪不到第三项去', () => {
    // 「以后都同意」那一项这时根本不画，光标却能停上去的话，
    // 回车下去会掉进一个进不去的步骤
    const store = new SessionStore()
    feed(store,
      { t: 'approval_request', id: 1, action: '执行命令', detail: 'x', reason: 'y' })

    store.moveChoice(1)
    store.moveChoice(1)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'ask', index: 1 })
  })

  test('数字键直选跳到对应项，越界的数字不动光标', () => {
    const store = new SessionStore()
    feed(store, askWithRules())

    store.pickChoice(2)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'ask', index: 2 })
    store.pickChoice(7)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'ask', index: 2 })
  })

  test('选「以后都同意」进挑规则那一步，默认停在后端推荐的那一档', () => {
    const store = new SessionStore()
    feed(store, askWithRules())

    store.beginRulePick()

    expect(store.getSnapshot().approvalStage).toEqual({ step: 'rule', index: 1 })
  })

  test('后端一条规则都没提议时，进挑规则那一步会留在原地', () => {
    // 跳进一个空的选择态，用户只会更糊涂——而且此刻后端还等着
    const store = new SessionStore()
    feed(store,
      { t: 'approval_request', id: 1, action: '执行命令', detail: 'x', reason: 'y' })

    store.beginRulePick()

    expect(store.getSnapshot().approvalStage.step).toBe('ask')
  })

  test('←/→ 调松紧，撞到两头就停住', () => {
    const store = new SessionStore()
    feed(store, askWithRules())
    store.beginRulePick()

    store.moveChoice(-1)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'rule', index: 0 })
    store.moveChoice(-1)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'rule', index: 0 })
    store.moveChoice(1)
    store.moveChoice(1)
    store.moveChoice(1)
    expect(store.getSnapshot().approvalStage).toEqual({ step: 'rule', index: 2 })
  })

  test('选「拒绝」进补话那一步，敲字进得去、退格删得掉', () => {
    const store = new SessionStore()
    feed(store, askWithRules())

    store.beginFeedback()
    expect(store.typeFeedback({ sequence: 'a' })).toBe(true)
    expect(store.typeFeedback({ sequence: 'b' })).toBe(true)
    expect(store.typeFeedback({ name: 'backspace' })).toBe(true)

    expect(store.getSnapshot().approvalStage).toEqual({ step: 'feedback', text: 'a' })
  })

  test('方向键在补话那一步退回 false，交给上层处理', () => {
    const store = new SessionStore()
    feed(store, askWithRules())
    store.beginFeedback()

    expect(store.typeFeedback({ name: 'left', sequence: '\x1b[D' })).toBe(false)
  })

  test('不在补话那一步时敲键什么也不发生', () => {
    const store = new SessionStore()
    feed(store, askWithRules())

    expect(store.typeFeedback({ sequence: 'a' })).toBe(false)
    expect(store.getSnapshot().approvalStage.step).toBe('ask')
  })

  test('答完之后交互态要跟着框一起清掉，不能带到下一个框上', () => {
    // 不清的话，下一次弹框会直接停在上一次的 rule 态，
    // 而那一档对应的是上一条命令
    const store = new SessionStore()
    feed(store, askWithRules())
    store.beginRulePick()

    store.clearApproval()
    expect(store.getSnapshot().approvalStage.step).toBe('ask')

    feed(store, askWithRules())
    expect(store.getSnapshot().approvalStage.step).toBe('ask')
  })

  test('busy 结束也要把交互态清掉', () => {
    const store = new SessionStore()
    feed(store, askWithRules())
    store.beginFeedback()

    feed(store, { t: 'busy', busy: false })
    expect(store.getSnapshot().approvalStage.step).toBe('ask')
  })

  test('esc 退回表态那一步', () => {
    const store = new SessionStore()
    feed(store, askWithRules())
    store.beginFeedback()

    store.resetApprovalStage()
    expect(store.getSnapshot().approvalStage.step).toBe('ask')
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
  test('角色跟着任务一起进来——面板要靠它区分「去查」和「去挑毛病」', () => {
    const store = new SessionStore()
    feed(store, {
      t: 'plan_created',
      goal: '审一遍再改',
      tasks: [
        { ...wireTask('t1'), role: 'reviewer', roleTitle: '审查' },
        { ...wireTask('t2', ['t1']), type: 'FILE_WRITE', role: 'coder', roleTitle: '编码' },
      ],
      layers: [['t1'], ['t2']],
    })

    const plan = planOf(store)
    expect(plan.tasks[0].role).toBe('reviewer')
    expect(plan.tasks[0].roleTitle).toBe('审查')
    expect(plan.tasks[1].roleTitle).toBe('编码')
  })

  test('老后端不发角色：字段就是 undefined，不能编一个出来', () => {
    const store = new SessionStore()
    feed(store, planCreated())

    expect(planOf(store).tasks[0].role).toBeUndefined()
    expect(planOf(store).tasks[0].roleTitle).toBeUndefined()
  })

  test('plan_created 落成一条 plan entry，goal 和分层原样保留', () => {
    const store = new SessionStore()
    feed(store, planCreated())

    const plan = planOf(store)
    expect(plan.goal).toBe('给 Task 加上重试能力')
    expect(plan.status).toBe('CREATED')
    expect(plan.tasks.map(t => t.id)).toEqual(['t1', 't2', 't3'])
    expect(plan.layers).toEqual([['t1', 't2'], ['t3']])
  })

  test('plan_created 指向底部面板，用户说下一句时让位', () => {
    const store = new SessionStore()
    feed(store, planCreated())

    const key = store.getSnapshot().activePlanKey
    expect(key).not.toBeNull()
    expect(store.getSnapshot().entries.find(e => e.key === key)?.kind).toBe('plan')

    // 跑完了也还留着——刚看完就消失，用户连结果都来不及看
    feed(store, { t: 'plan_finished', status: 'COMPLETED', succeeded: 3, failed: 0, skipped: 0, durationMs: 100 })
    expect(store.getSnapshot().activePlanKey).toBe(key)

    // 说下一句才让位
    store.submitPrompt('接着干别的')
    expect(store.getSnapshot().activePlanKey).toBeNull()
  })

  test('面板取的是 transcript 里那一份，不另存一份状态', () => {
    const store = new SessionStore()
    feed(store, planCreated(), { t: 'plan_task_started', taskId: 't1', taskType: 'FILE_READ', description: '任务 t1' })

    const key = store.getSnapshot().activePlanKey
    const entry = store.getSnapshot().entries.find(e => e.key === key)
    if (entry?.kind !== 'plan') throw new Error('没找到计划')

    // 面板要显示的东西，和滚上去看到的是同一份对象
    expect(entry.plan).toBe(planOf(store))
    expect(entry.plan.tasks.find(t => t.id === 't1')?.status).toBe('RUNNING')
  })

  test('改道：补救任务接在失败那一步后面，并标出来是救场的', () => {
    const store = new SessionStore()
    feed(store, planCreated(),
      { t: 'plan_task_finished', taskId: 't1', status: 'FAILED', durationMs: 5, result: null, error: '炸了' },
      { t: 'plan_revised', failedTaskId: 't1', tasks: [wireTask('r1'), wireTask('r2', ['r1'])] })

    const plan = planOf(store)
    // 甩到末尾的话，用户得自己在一堆步骤里找「这几个是来救谁的」
    expect(plan.tasks.map(t => t.id)).toEqual(['t1', 'r1', 'r2', 't2', 't3'])
    expect(plan.tasks.find(t => t.id === 'r1')?.recovery).toBe(true)
    expect(plan.tasks.find(t => t.id === 't2')?.recovery).toBeUndefined()
  })

  test('认不出 failedTaskId 时补在末尾，也不能把任务弄丢', () => {
    const store = new SessionStore()
    feed(store, planCreated(),
      { t: 'plan_revised', failedTaskId: '不存在', tasks: [wireTask('r1')] })

    expect(planOf(store).tasks.map(t => t.id)).toEqual(['t1', 't2', 't3', 'r1'])
  })

  test('acceptance 透传，没有就是 undefined', () => {
    const store = new SessionStore()
    feed(store, {
      t: 'plan_created',
      goal: '目标',
      tasks: [
        { ...wireTask('t1'), acceptance: '说清 Java 版本' },
        wireTask('t2'),
      ],
      layers: [['t1', 't2']],
    })

    const plan = planOf(store)
    expect(plan.tasks[0]?.acceptance).toBe('说清 Java 版本')
    expect(plan.tasks[1]?.acceptance).toBeUndefined()
  })

  test('自动改走规划时，把后端给的理由摆出来', () => {
    const store = new SessionStore()
    feed(store, { t: 'mode_switched', mode: 'plan', reason: '范围铺得比较开' })

    const entry = store.getSnapshot().entries.at(-1)
    if (entry?.kind !== 'notice') throw new Error('没落成提示')
    // 不说清楚的话，用户发的是一句话、屏幕上却冒出个计划，他会完全不知道为什么
    expect(entry.text).toContain('范围铺得比较开')
    expect(entry.level).toBe('info')
  })

  test('后端没给理由也不能空着一句', () => {
    const store = new SessionStore()
    feed(store, { t: 'mode_switched', mode: 'plan', reason: '' })

    const entry = store.getSnapshot().entries.at(-1)
    if (entry?.kind !== 'notice') throw new Error('没落成提示')
    expect(entry.text.length).toBeGreaterThan(0)
  })

  test('首帧带上次没跑完的计划，界面据此放出「接着跑」', () => {
    const store = new SessionStore()
    feed(store, {
      ...(ready() as Extract<ServerEvent, { t: 'ready' }>),
      features: ['plan', 'resume'],
      resumable: { goal: '把 README 补全', remaining: 3, total: 7, savedAt: 1 },
    })

    expect(store.getSnapshot().ready?.resumable?.goal).toBe('把 README 补全')
    expect(store.getSnapshot().ready?.resumable?.remaining).toBe(3)
  })

  test('新计划一出来，「接着跑」的提示就该收——存档已经被覆盖了', () => {
    const store = new SessionStore()
    feed(store, {
      ...(ready() as Extract<ServerEvent, { t: 'ready' }>),
      resumable: { goal: '旧的那份', remaining: 3, total: 7, savedAt: 1 },
    })
    expect(store.getSnapshot().ready?.resumable).toBeDefined()

    feed(store, planCreated())

    // 留着它会指向一份已经不存在的进度
    expect(store.getSnapshot().ready?.resumable).toBeUndefined()
    // 其余 ready 信息不能跟着一起丢
    expect(store.getSnapshot().ready?.model).toBe('deepseek-chat')
  })

  test('确认请求像审批一样置起标志，回答后由界面清掉', () => {
    const store = new SessionStore()
    feed(store, planCreated(), { t: 'plan_confirm_request', id: 1 })

    // 光标从「执行」起步，和审批框一样默认停在肯定的那一项
    expect(store.getSnapshot().planConfirm).toEqual({ id: 1, choice: 0 })
    store.clearPlanConfirm()
    expect(store.getSnapshot().planConfirm).toBeNull()
  })

  test('计划确认的光标只有两格，同样不绕回去', () => {
    const store = new SessionStore()
    feed(store, planCreated(), { t: 'plan_confirm_request', id: 1 })

    store.movePlanChoice(1)
    expect(store.getSnapshot().planConfirm?.choice).toBe(1)
    store.movePlanChoice(1)
    expect(store.getSnapshot().planConfirm?.choice).toBe(1)
    store.movePlanChoice(-1)
    expect(store.getSnapshot().planConfirm?.choice).toBe(0)
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

describe('会话恢复', () => {
  const readyWithSession = () =>
    ({
      ...(ready() as Extract<ServerEvent, { t: 'ready' }>),
      features: ['plan', 'resume', 'session'],
      continuable: {
        turns: 4,
        messages: 11,
        firstMessage: '这个项目怎么跑测试',
        savedAt: 1,
      },
    }) as ServerEvent

  test('首帧带上次没聊完的对话，界面据此放出「接着聊」', () => {
    const store = new SessionStore()
    feed(store, readyWithSession())

    expect(store.getSnapshot().ready?.continuable?.turns).toBe(4)
    expect(store.getSnapshot().ready?.continuable?.firstMessage).toBe('这个项目怎么跑测试')
  })

  test('续计划和续对话是两件独立的事，可以同时挂着', () => {
    const store = new SessionStore()
    feed(store, {
      ...(readyWithSession() as Extract<ServerEvent, { t: 'ready' }>),
      resumable: { goal: '把 README 补全', remaining: 3, total: 7, savedAt: 1 },
    })

    expect(store.getSnapshot().ready?.resumable).toBeDefined()
    expect(store.getSnapshot().ready?.continuable).toBeDefined()
  })

  test('恢复成功后「接着聊」的入口就该收——存档已经用掉了', () => {
    const store = new SessionStore()
    feed(store, readyWithSession())
    feed(store, {
      t: 'session_restored',
      messages: 11,
      turns: 4,
      firstMessage: '这个项目怎么跑测试',
      savedAt: 1,
    })

    expect(store.getSnapshot().ready?.continuable).toBeUndefined()
    // 只摘这一个字段，其余 ready 信息不能跟着丢
    expect(store.getSnapshot().ready?.model).toBe('deepseek-chat')
    expect(store.getSnapshot().ready?.tools).toHaveLength(1)
  })

  test('恢复成功会说清接回来多少——可能比存档里少，残缺的尾巴被修剪了', () => {
    const store = new SessionStore()
    feed(store, readyWithSession())
    feed(store, {
      t: 'session_restored',
      messages: 8,
      turns: 3,
      firstMessage: '这个项目怎么跑测试',
      savedAt: 1,
    })

    const last = store.getSnapshot().entries.at(-1)!
    expect(last.kind).toBe('notice')
    expect((last as Extract<Entry, { kind: 'notice' }>).text).toContain('3 轮')
    expect((last as Extract<Entry, { kind: 'notice' }>).text).toContain('8 条')
  })

  test('恢复会把 token 占用一起更新——接回来的历史本来就占上下文', () => {
    const store = new SessionStore()
    feed(store, readyWithSession())
    feed(store, {
      t: 'session_restored',
      messages: 11,
      turns: 4,
      firstMessage: 'x',
      savedAt: 1,
      tokens: 8240,
      contextLimit: 64000,
    })

    expect(store.getSnapshot().tokens).toBe(8240)
    expect(store.getSnapshot().contextLimit).toBe(64000)
  })

  test('清空对话后入口也要收——后端 reset 时会把存档一起删掉', () => {
    const store = new SessionStore()
    feed(store, readyWithSession())
    feed(store, { t: 'reset_done' })

    // 留着的话用户点了只会得到一条 error
    expect(store.getSnapshot().ready?.continuable).toBeUndefined()
    expect(store.getSnapshot().ready?.model).toBe('deepseek-chat')
  })
})

describe('会话选择器', () => {
  const list = (currentId = 'now') =>
    ({
      t: 'session_list',
      currentId,
      sessions: [
        {
          id: '20260918-090000-aaaa',
          title: '加上多会话功能',
          turns: 12,
          model: 'deepseek-chat',
          branch: 'dev',
          startedAt: 1,
          savedAt: 2,
          bytes: 34_211,
        },
        {
          id: '20260917-090000-bbbb',
          title: '调试 tmux',
          turns: 6,
          model: 'deepseek-chat',
          branch: null,
          startedAt: 1,
          savedAt: 1,
          bytes: 5_800,
        },
      ],
    }) as ServerEvent

  test('收到列表就把面板的数据准备好，currentId 一起带上', () => {
    const store = new SessionStore()
    feed(store, list('20260918-090000-aaaa'))

    const picker = store.getSnapshot().sessionPicker!
    expect(picker.sessions).toHaveLength(2)
    expect(picker.currentId).toBe('20260918-090000-aaaa')
    expect(picker.sessions[0]!.branch).toBe('dev')
    expect(picker.sessions[1]!.branch).toBeNull()
  })

  test('再问一次是整份覆盖，不做缓存——别的界面可能刚聊了一轮', () => {
    const store = new SessionStore()
    feed(store, list())
    feed(store, { t: 'session_list', currentId: 'x', sessions: [] } as ServerEvent)

    expect(store.getSnapshot().sessionPicker?.sessions).toEqual([])
  })

  test('挑完接上了，面板就收起——挂着的话下一次 ↑↓ 会落在一个已经接过的列表上', () => {
    const store = new SessionStore()
    feed(store, list())
    feed(store, {
      t: 'session_restored',
      messages: 11,
      turns: 4,
      firstMessage: '这个项目怎么跑测试',
      savedAt: 1,
    } as ServerEvent)

    expect(store.getSnapshot().sessionPicker).toBeNull()
  })

  test('放弃挑选也收起来', () => {
    const store = new SessionStore()
    feed(store, list())
    store.closeSessionPicker()

    expect(store.getSnapshot().sessionPicker).toBeNull()
  })

  test('本来就没开的时候收一下也不报错、不多推一次状态', () => {
    const store = new SessionStore()
    let pushes = 0
    store.subscribe(() => pushes++)
    store.closeSessionPicker()

    expect(pushes).toBe(0)
  })
})

describe("用量：占用和累计是两个数", () => {
  test("busy 结束时两个都更新，累计只增不减", () => {
    const store = new SessionStore()

    feed(store, { t: "busy", busy: false, tokens: 21_400, sentTokens: 156_800 })
    expect(store.getSnapshot().tokens).toBe(21_400)
    expect(store.getSnapshot().sentTokens).toBe(156_800)

    // 压缩之后窗口变小了，但已经发出去的不会退回来
    feed(store, { t: "busy", busy: false, tokens: 6_800, sentTokens: 191_000 })
    expect(store.getSnapshot().tokens).toBe(6_800)
    expect(store.getSnapshot().sentTokens).toBe(191_000)
  })

  test("后端没给 sentTokens 时保持原值，不要当成 0", () => {
    const store = new SessionStore()
    feed(store, { t: "busy", busy: false, tokens: 100, sentTokens: 5_000 })

    feed(store, { t: "busy", busy: false, tokens: 200 })

    expect(store.getSnapshot().sentTokens).toBe(5_000)
  })
})

describe("压缩：摘要和裁剪是两回事", () => {
  test("摘要落地和只裁工具输出都要进转录，而且分得开", () => {
    const store = new SessionStore()

    feed(store,
      { t: "compacted", tokensBefore: 48_210, tokensAfter: 19_044, summarized: true },
      { t: "compacted", tokensBefore: 9_100, tokensAfter: 7_300, summarized: false })

    const compacted = store.getSnapshot().entries.filter(e => e.kind === "compacted")
    expect(compacted.length).toBe(2)
    expect(compacted[0]).toMatchObject({ before: 48_210, after: 19_044, summarized: true })
    // summarized:false 是「工具输出被裁了」。这一条不显示的话，用户看到的是
    // 「它刚读过的文件怎么下一句就忘了」，而界面一声不吭（protocol.md §3.11）
    expect(compacted[1]).toMatchObject({ before: 9_100, after: 7_300, summarized: false })
  })

  test("进度只占一条，一路原地更新——不然几百根条会把转录刷爆", () => {
    const store = new SessionStore()

    feed(store,
      { t: "compacting", phase: "map", done: 0, total: 3, percent: 4 },
      { t: "compacting", phase: "map", done: 0, total: 3, percent: 19 },
      { t: "compacting", phase: "map", done: 1, total: 3, percent: 41 },
      { t: "compacting", phase: "reduce", done: 2, total: 3, percent: 72 })

    const bars = store.getSnapshot().entries.filter(e => e.kind === "compacting")
    expect(bars.length).toBe(1)
    expect(bars[0]).toMatchObject({ phase: "reduce", done: 2, total: 3, percent: 72 })
  })

  test("收尾就把进度条整条移走，不留一根走了一半的", () => {
    const store = new SessionStore()

    feed(store,
      { t: "compacting", phase: "map", done: 0, total: 2, percent: 30 },
      { t: "compacting", phase: "done", done: 2, total: 2, percent: 100 })

    // 留一根凝固在 30% 的条，用户会以为还在跑。压缩被取消或者失败时走的正是这条路
    expect(store.getSnapshot().entries.filter(e => e.kind === "compacting").length).toBe(0)
  })

  test("结论落地前先撤条，两样东西不能同框", () => {
    const store = new SessionStore()

    feed(store,
      { t: "compacting", phase: "reduce", done: 1, total: 2, percent: 88 },
      { t: "compacted", tokensBefore: 48_210, tokensAfter: 19_044, summarized: true })

    const entries = store.getSnapshot().entries
    expect(entries.filter(e => e.kind === "compacting").length).toBe(0)
    expect(entries.filter(e => e.kind === "compacted").length).toBe(1)
  })

  test("`compacting done` 丢了也不能让条永远挂着——busy 收尾时兜一道", () => {
    const store = new SessionStore()

    // 旧后端、断线重连，都可能让收尾那条事件到不了
    feed(store,
      { t: "compacting", phase: "map", done: 0, total: 1, percent: 12 },
      { t: "busy", busy: false })

    expect(store.getSnapshot().entries.filter(e => e.kind === "compacting").length).toBe(0)
  })

  test("压缩不改 token 数——那是 busy 的活，两条路各管各的", () => {
    const store = new SessionStore()
    feed(store, { t: "busy", busy: false, tokens: 48_210, sentTokens: 100_000 })

    feed(store, { t: "compacted", tokensBefore: 48_210, tokensAfter: 19_044, summarized: true })

    expect(store.getSnapshot().tokens).toBe(48_210)
    expect(store.getSnapshot().sentTokens).toBe(100_000)
  })
})

describe("长期记忆清单", () => {
  const row = (name: string, supersededBy: string | null = null) => ({
    name,
    description: `摘要 ${name}`,
    scope: "project" as const,
    source: supersededBy ? "observation" : "user_correction",
    sourceLabel: supersededBy ? "自动记录·可撤销" : "用户纠正过",
    created: "2026-09-19",
    topicKey: "包管理器",
    supersededBy,
    turnId: null,
  })

  test("被取代的条目也要进转录——隐藏掉就查不清它为什么改口", () => {
    const store = new SessionStore()

    feed(store, { t: "memory_list", entries: [row("uses-bun"), row("uses-npm", "uses-bun")] })

    const entry = store.getSnapshot().entries.find(e => e.kind === "memory")
    expect(entry).toBeDefined()
    if (entry?.kind !== "memory") throw new Error("kind 不对")
    expect(entry.rows).toHaveLength(2)
    expect(entry.rows[1]!.supersededBy).toBe("uses-bun")
  })

  test("一条记忆都没有时也要出条目，而不是静默", () => {
    const store = new SessionStore()
    feed(store, { t: "memory_list", entries: [] })
    expect(store.getSnapshot().entries.filter(e => e.kind === "memory")).toHaveLength(1)
  })

  test("undo 的结果说清楚旧事实复活了——不然用户以为两条都没了", () => {
    const store = new SessionStore()

    feed(store, { t: "memory_changed", action: "undo", name: "uses-bun", description: "用 bun" })

    const notice = store.getSnapshot().entries.at(-1)
    expect(notice?.kind).toBe("notice")
    if (notice?.kind !== "notice") throw new Error("kind 不对")
    expect(notice.text).toContain("uses-bun")
    expect(notice.text).toContain("重新生效")
  })

  test("clear-auto 清了 0 条时不要说「已清掉 0 条」", () => {
    const store = new SessionStore()
    feed(store, { t: "memory_changed", action: "clear_auto", removed: 0 })
    const notice = store.getSnapshot().entries.at(-1)
    if (notice?.kind !== "notice") throw new Error("kind 不对")
    expect(notice.text).toBe("没有自动记录可清。")
  })
})

// ------------------------------------------------------------ 流水线

function teamOf(store: SessionStore) {
  const entry = store.getSnapshot().entries.find(e => e.kind === 'team')
  if (!entry || entry.kind !== 'team') throw new Error('没有流水线面板')
  return entry.team
}

function teamStarted(): ServerEvent {
  return {
    t: 'team_started',
    goal: '给配置模块加环境变量覆盖',
    reason: '探路拟方案 → 编码施工 → 审查验收',
    seats: [
      { id: 'plan', role: 'scout', roleTitle: '探路', summary: '拟方案', status: 'PENDING' },
      { id: 'work', role: 'coder', roleTitle: '编码', summary: '施工', status: 'PENDING' },
      { id: 'review', role: 'reviewer', roleTitle: '审查', summary: '验收', status: 'PENDING' },
    ],
  }
}

describe('流水线面板', () => {
  test('开场帧落成一条 team entry，三道工序和分工理由都留着', () => {
    const store = new SessionStore()
    feed(store, teamStarted())

    const team = teamOf(store)
    expect(team.goal).toBe('给配置模块加环境变量覆盖')
    expect(team.reason).toBe('探路拟方案 → 编码施工 → 审查验收')
    expect(team.seats.map(s => s.roleTitle)).toEqual(['探路', '编码', '审查'])
    expect(team.status).toBe('CREATED')
  })

  test('座位表是长大的：seats_added 按执行顺序追加，随后照常被 started 点亮', () => {
    const store = new SessionStore()
    feed(store, {
      t: 'team_started', goal: '目标', reason: '拟方案 → 并行施工 → 验收',
      seats: [{ id: 'plan', role: 'scout', roleTitle: '探路', summary: '拟方案', status: 'PENDING' }],
    })
    feed(store, {
      t: 'team_seats_added',
      seats: [
        { id: 't1', role: 'coder', roleTitle: '编码', summary: '施工', status: 'PENDING' },
        { id: 't2', role: 'explorer', roleTitle: '调研', summary: '查', status: 'PENDING' },
      ],
    })
    feed(store, { t: 'team_seat_started', seatId: 't2', role: 'explorer', roleTitle: '调研' })

    const team = teamOf(store)
    expect(team.seats.map(s => s.id)).toEqual(['plan', 't1', 't2'])
    expect(team.seats[2].status).toBe('RUNNING')
  })

  test('工序状态就地更新，不会互相串', () => {
    const store = new SessionStore()
    feed(store, teamStarted())
    feed(store, { t: 'team_seat_started', seatId: 'plan', role: 'scout', roleTitle: '探路' })

    expect(teamOf(store).seats[0].status).toBe('RUNNING')
    expect(teamOf(store).seats[1].status).toBe('PENDING')
    expect(teamOf(store).status).toBe('RUNNING')
  })

  test('一道工序的定稿归到它名下——流式那份是混在一起的，回看要靠这个', () => {
    const store = new SessionStore()
    feed(store, teamStarted())
    feed(store, {
      t: 'team_seat_finished', seatId: 'plan', role: 'scout', status: 'SUCCEEDED',
      durationMs: 28000, opinion: '分三步改', error: null,
    })

    expect(teamOf(store).seats[0].opinion).toBe('分三步改')
    expect(teamOf(store).seats[0].durationMs).toBe(28000)
    expect(teamOf(store).seats[0].error).toBeUndefined()
  })

  test('倒了的工序记下原因，不能留在转圈状态', () => {
    const store = new SessionStore()
    feed(store, teamStarted())
    feed(store, {
      t: 'team_seat_finished', seatId: 'work', role: 'coder', status: 'FAILED',
      durationMs: 3000, opinion: null, error: '写文件被拒绝',
    })

    expect(teamOf(store).seats[1].status).toBe('FAILED')
    expect(teamOf(store).seats[1].error).toBe('写文件被拒绝')
  })

  test('打回的 PARTIAL 把问题清单留在面板上——那是最值钱的一份产出', () => {
    const store = new SessionStore()
    feed(store, teamStarted())
    feed(store, {
      t: 'team_finished', status: 'PARTIAL', spoke: 3, silent: 0, verdict: '漏了空值判断\n打回',
    })

    const team = teamOf(store)
    expect(team.status).toBe('PARTIAL')
    expect(team.verdict).toBe('漏了空值判断\n打回')
  })

  test('验收没跑成的 PARTIAL 没有 verdict——没有结论就别画一份出来', () => {
    const store = new SessionStore()
    feed(store, teamStarted())
    feed(store, { t: 'team_finished', status: 'PARTIAL', spoke: 2, silent: 1, verdict: null })

    expect(teamOf(store).status).toBe('PARTIAL')
    expect(teamOf(store).verdict).toBeUndefined()
  })

  test('取消之后没有验收结论，别画一份出来', () => {
    const store = new SessionStore()
    feed(store, teamStarted())
    feed(store, { t: 'team_finished', status: 'CANCELLED', spoke: 1, silent: 2, verdict: null })

    expect(teamOf(store).verdict).toBeUndefined()
  })

  test('流水线面板也在用户说下一句时让位', () => {
    const store = new SessionStore()
    feed(store, teamStarted())
    expect(store.getSnapshot().activeTeamKey).not.toBeNull()

    store.submitPrompt('再说点别的')

    expect(store.getSnapshot().activeTeamKey).toBeNull()
    expect(teamOf(store).goal).toBe('给配置模块加环境变量覆盖')  // 痕迹还在转录里
  })
})

// ------------------------------------------------------------ 模式（Shift+Tab）

describe('模式切换', () => {
  const withFeatures = (features: string[]) => {
    const store = new SessionStore()
    feed(store, {
      t: 'ready', protocol: 1, version: '0.2.0', workspace: '/tmp/x', model: 'm',
      planMode: false, hasApiKey: true, busy: false, features, tools: [],
    } as ServerEvent)
    return store
  }

  test('默认 normal——最常用的那个不该要用户先按一下', () => {
    expect(new SessionStore().getSnapshot().mode).toBe('normal')
  })

  test('normal → plan → team → normal，转一圈回到原地', () => {
    const store = withFeatures(['plan', 'team'])

    store.cycleMode()
    expect(store.getSnapshot().mode).toBe('plan')
    store.cycleMode()
    expect(store.getSnapshot().mode).toBe('team')
    store.cycleMode()
    expect(store.getSnapshot().mode).toBe('normal')
  })

  test('后端没宣告的模式直接跳过——进得去又用不了，比切不进去更让人困惑', () => {
    const store = withFeatures(['plan'])

    store.cycleMode()
    expect(store.getSnapshot().mode).toBe('plan')
    store.cycleMode()
    expect(store.getSnapshot().mode).toBe('normal')
  })

  test('两个都没宣告时，按了等于没按，不会卡在跑不了的模式上', () => {
    const store = withFeatures([])

    store.cycleMode()

    expect(store.getSnapshot().mode).toBe('normal')
  })

  test('模式跨轮保持——它是用户表过的态，不该跑完一轮就弹回去', () => {
    const store = withFeatures(['plan', 'team'])
    store.cycleMode()

    store.submitPrompt('干点活')
    feed(store, { t: 'done', text: '干完了', steps: 2 })

    expect(store.getSnapshot().mode).toBe('plan')
  })

  test('还没连上后端时不瞎切——能力清单是空的', () => {
    const store = new SessionStore()

    store.cycleMode()

    expect(store.getSnapshot().mode).toBe('normal')
  })
})

describe('模式可用性（纯函数）', () => {
  test('normal 永远可用，不挑后端', () => {
    expect(modeIsAvailable('normal', [])).toBe(true)
  })

  test('plan / team 各自看自己的 feature', () => {
    expect(modeIsAvailable('plan', ['team'])).toBe(false)
    expect(modeIsAvailable('team', ['team'])).toBe(true)
  })

  test('nextMode 是纯的，不碰 store', () => {
    expect(nextMode('normal', ['plan', 'team'])).toBe('plan')
    expect(nextMode('team', ['plan', 'team'])).toBe('normal')
    expect(nextMode('plan', [])).toBe('normal')
  })
})

describe('用户消息回显（协议 3.15）', () => {
  test('别的界面说的话要画出来——手机上说的，终端里不能只冒出一段回答', () => {
    const store = new SessionStore()
    store.apply({ t: 'user_message', text: '手机上说的', mode: 'normal', cid: 'phone-42' })

    const entries = store.getSnapshot().entries
    expect(entries).toHaveLength(1)
    expect(entries[0]).toMatchObject({ kind: 'user', text: '手机上说的' })
  })

  test('自己那条跳过——本地在按回车时已经即时回显过了', () => {
    const store = new SessionStore()
    store.submitPrompt('我自己说的')
    store.apply({ t: 'user_message', text: '我自己说的', mode: 'normal', cid: store.clientId })

    expect(store.getSnapshot().entries).toHaveLength(1)
  })

  test('不带 cid 的回显照常显示——老后端/残缺帧宁可重复也别丢', () => {
    const store = new SessionStore()
    store.submitPrompt('一句话')
    store.apply({ t: 'user_message', text: '一句话', mode: 'normal' })

    expect(store.getSnapshot().entries).toHaveLength(2)
  })
})

describe('审批被别的屏答掉（协议 3.3）', () => {
  test('approval_resolved 立刻收框——不收会挂到整轮结束', () => {
    const store = new SessionStore()
    store.apply({ t: 'approval_request', id: 7, action: 'x', detail: 'y', reason: 'z' })
    expect(store.getSnapshot().approval).not.toBeNull()

    store.apply({ t: 'approval_resolved', id: 7 })
    expect(store.getSnapshot().approval).toBeNull()
  })

  test('id 对不上不乱收——那可能是上一道题的迟到回执', () => {
    const store = new SessionStore()
    store.apply({ t: 'approval_request', id: 8, action: 'x', detail: 'y', reason: 'z' })
    store.apply({ t: 'approval_resolved', id: 7 })
    expect(store.getSnapshot().approval?.id).toBe(8)
  })

  test('计划确认同理', () => {
    const store = new SessionStore()
    store.apply({ t: 'plan_confirm_request', id: 3 })
    store.apply({ t: 'plan_confirm_resolved', id: 3 })
    expect(store.getSnapshot().planConfirm).toBeNull()
  })
})

test("rewound 回对话：按用户消息数截转录，第 userTurn 句及之后的都不留", () => {
  const store = new SessionStore()
  store.submitPrompt("第一句")
  store.apply({ t: "text", text: "答一" } as any)
  store.submitPrompt("第二句")
  store.apply({ t: "text", text: "答二" } as any)
  store.submitPrompt("/rewind 1 both")
  store.apply({ t: "rewound", id: 1, label: "第一句", scope: "both", restored: ["a.py"], userTurn: 0, remaining: 0 } as any)
  const entries = store.getSnapshot().entries
  const users = entries.filter((e) => e.kind === "user")
  expect(users).toHaveLength(0)
  const last = entries[entries.length - 1] as any
  expect(last.kind).toBe("notice")
  expect(last.text).toContain("文件和对话已回到检查点 #1")
  expect(last.text).toContain("a.py")
})

test("rewound 只回文件：转录不动", () => {
  const store = new SessionStore()
  store.submitPrompt("第一句")
  store.submitPrompt("第二句")
  store.apply({ t: "rewound", id: 1, label: "第一句", scope: "code", restored: [], userTurn: -1, remaining: 2 } as any)
  const entries = store.getSnapshot().entries
  expect(entries.filter((e) => e.kind === "user")).toHaveLength(2)
  expect((entries[entries.length - 1] as any).text).toContain("文件没有差别")
})

test("checkpoint_list 列成编号清单；不可用时说清楚", () => {
  const store = new SessionStore()
  store.apply({ t: "checkpoint_list", available: true, retentionDays: 30, items: [
    { id: 1, label: "加环境变量", time: 0, turn: 0 }, { id: 2, label: "补单测", time: 0, turn: 1 }] } as any)
  let last = store.getSnapshot().entries.at(-1) as any
  expect(last.text).toContain("#1")
  expect(last.text).toContain("#2")
  expect(last.text).toContain("补单测")
  store.apply({ t: "checkpoint_list", available: true, retentionDays: 30, items: [
    { id: 1, label: "加环境变量", time: 0, turn: 0, kind: "turn" },
    { id: 2, label: "write_file src/a.py", time: 0, turn: 1, kind: "tool" }] } as any)
  last = store.getSnapshot().entries.at(-1) as any
  expect(last.text).toContain("└ write_file src/a.py 之前")
  expect(last.text).not.toContain("第 2 句前  write_file")
  store.apply({ t: "checkpoint_list", available: false, retentionDays: 30, items: [] } as any)
  last = store.getSnapshot().entries.at(-1) as any
  expect(last.level).toBe("warn")
})

describe('断在半路之后提示「继续」', () => {
  const cut = { reason: '已取消', prompt: '把 Main 改了', steps: 3, at: 1758100000000 }

  test('failed 带断点：原因之后紧跟一行「继续」提示，并记下断点', () => {
    const store = new SessionStore()
    feed(store, ready(), { t: 'failed', reason: '已取消', interrupted: cut })

    const notices = store.getSnapshot().entries.filter(e => e.kind === 'notice')
    expect(notices.map(n => (n as { text: string }).text)).toEqual([
      '已取消',
      '现场还在（断在第 3 步）· 说「继续」接着做，或者直接交代新的要求',
    ])
    expect(store.getSnapshot().interrupted).toEqual(cut)
  })

  test('续不了的失败不提示——说「继续」只会原地再撞一次', () => {
    const store = new SessionStore()
    feed(store, ready(), { t: 'failed', reason: '上下文超限' })

    expect(store.getSnapshot().entries.filter(e => e.kind === 'notice')).toHaveLength(1)
    expect(store.getSnapshot().interrupted).toBeNull()
  })

  test('下一句话一跑起来断点就作废，不管说的是不是「继续」', () => {
    const store = new SessionStore()
    feed(store, ready(), { t: 'failed', reason: '已取消', interrupted: cut }, { t: 'busy', busy: true })

    expect(store.getSnapshot().interrupted).toBeNull()
  })

  test('晚连上来的界面从 ready 拿断点——手机扫码进来时终端早按过停了', () => {
    const store = new SessionStore()
    feed(store, { ...ready(), interrupted: cut } as ServerEvent)

    expect(store.getSnapshot().interrupted).toEqual(cut)
  })

  test('接回来的会话断在半路：「接上了」后面紧跟「继续」提示', () => {
    const store = new SessionStore()
    feed(store, ready(), {
      t: 'session_restored', id: '20260918-143210-3f9a', messages: 4, turns: 1,
      firstMessage: '把 Main 改了', savedAt: 1, interrupted: cut,
    } as ServerEvent)

    const texts = store.getSnapshot().entries.filter(e => e.kind === 'notice').map(e => (e as { text: string }).text)
    expect(texts[texts.length - 1]).toBe('现场还在（断在第 3 步）· 说「继续」接着做，或者直接交代新的要求')
    expect(store.getSnapshot().interrupted).toEqual(cut)
  })

  test('接回来的会话没断过：清掉上一段的断点', () => {
    const store = new SessionStore()
    feed(store, ready(), { t: 'failed', reason: '已取消', interrupted: cut }, {
      t: 'session_restored', id: '20260918-143210-3f9a', messages: 4, turns: 1,
      firstMessage: 'x', savedAt: 1,
    } as ServerEvent)

    expect(store.getSnapshot().interrupted).toBeNull()
  })

  test('清空对话后断点不作数', () => {
    const store = new SessionStore()
    feed(store, ready(), { t: 'failed', reason: '已取消', interrupted: cut }, { t: 'reset_done' })

    expect(store.getSnapshot().interrupted).toBeNull()
  })
})

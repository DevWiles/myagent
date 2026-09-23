/**
 * 断线重连在 store 这一层的表现：连接状态、重启后该收什么、缺口怎么标。
 * 重连的时序本身（退避、去重）在 link.test.ts；这里只管界面看到的状态对不对。
 */

import { describe, expect, test } from 'bun:test'
import type { ServerEvent } from './protocol.ts'
import { SessionStore } from './store.ts'

function ready(): ServerEvent {
  return {
    t: 'ready',
    protocol: 1,
    version: '1.0.0',
    workspace: '/tmp/x',
    model: 'deepseek-chat',
    planMode: false,
    hasApiKey: true,
    busy: false,
    tools: [],
  }
}

describe('连接状态', () => {
  test('一开始是首连中，不是断线', () => {
    expect(new SessionStore().getSnapshot().link).toEqual({ phase: 'connecting' })
  })

  test('重连中不算死：closed 仍为 null，界面不该退出', () => {
    const store = new SessionStore()
    store.apply(ready())
    store.setLink({ phase: 'reconnecting', attempt: 2, reason: '后端断开了连接', restarting: false })
    const state = store.getSnapshot()
    expect(state.closed).toBeNull()
    expect(state.link).toMatchObject({ phase: 'reconnecting', attempt: 2 })
    // 转录、ready 都还在——断线不是清屏
    expect(state.ready?.model).toBe('deepseek-chat')
  })
})

describe('后端换了进程', () => {
  test('转录留着，挂着的审批框收掉', () => {
    const store = new SessionStore()
    store.apply(ready())
    store.submitPrompt('删掉 tmp')
    store.apply({ t: 'text_delta', text: '好的，' })
    store.apply({
      t: 'approval_request',
      id: 7,
      kind: 'shell',
      action: 'rm -rf tmp',
      detail: 'rm -rf tmp',
      reason: '',
      preview: '',
      suggestions: [],
      preferred: 0,
    } as never)
    expect(store.getSnapshot().approval).not.toBeNull()

    store.markRestarted()

    const state = store.getSnapshot()
    expect(state.approval).toBeNull()
    expect(state.planConfirm).toBeNull()
    expect(state.entries.map(e => e.kind)).toEqual(['user', 'assistant'])
  })

  test('第二次 ready 不清转录', () => {
    const store = new SessionStore()
    store.apply(ready())
    store.submitPrompt('你好')
    store.apply({ t: 'text_delta', text: '你好！' })
    store.apply({ ...ready(), busy: false } as ServerEvent)
    expect(store.getSnapshot().entries).toHaveLength(2)
  })
})

describe('补发缺口', () => {
  test('如实标一条警告，不装完整', () => {
    const store = new SessionStore()
    store.apply(ready())
    store.apply({ t: 'replay_gap', from: 12, to: 20 })
    const tail = store.getSnapshot().entries.at(-1)
    expect(tail).toMatchObject({ kind: 'notice', level: 'warn' })
    expect((tail as { text: string }).text).toContain('9 条')
  })
})

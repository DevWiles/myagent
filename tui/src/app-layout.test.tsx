/**
 * 布局回归测试：**输入框必须钉死在屏幕最后一行**。
 *
 * 这是整轮改动的验收点。以前底部高度是手算的，六七个漂移源（状态栏没 ready
 * 时不渲染却照样记 1 行、spinner/确认框按钮窄屏折行、关闭原因太长折行……），
 * 而现有的 54 个测试全是纯逻辑、一个组件都不渲染，所以这些 bug 全都活到了线上。
 *
 * 注意一个陷阱：**断言「帧行数 == rows」是恒真的**，因为 App 根节点写了
 * `height={rows}`，Ink 的 `Output` 高度取的就是根节点的 computed height。
 * 有牙齿的断言是**行号**：输入框在第几行、最后一个非空行是不是 rows-1。
 *
 * ## 这些测试到底在保护什么（A/B 验过的）
 *
 * - 拿掉 `TranscriptView` 的 `flexGrow/flexBasis` → **6 条红**。这才是钉底的机制，
 *   保护到位。
 * - 把 `StatusLine` 改回 `if (!ready) return null` → **1 条红**（正是那条用例）。
 * - 把 `Spinner` 改回不定高、不截断 → **0 条红**，而且这是对的：flex 布局下
 *   spinner 折行只会从对话区多借一行，输入框离底部的距离不变，布局自己就吸收了。
 *   所以各组件的固定高度现在是**第二道防线**，不是机制本身；它们真正的作用是让
 *   `planBottomStack` 的账算得准，矮屏降级才不会选错档。
 *
 * 换句话说：别指望这些用例能替你守住每个组件的 `height=`。它们守的是
 * 「输入框在最后一行」这个用户看得见的性质。
 */

import { describe, expect, test } from 'bun:test'
import { render } from 'ink'
import { createElement } from 'react'
import type { Backend } from './backend.ts'
import { App } from './components/App.tsx'
import { SessionStore } from './store.ts'
import { resetForTest } from './terminal.ts'
import { FakeStdin, FakeStdout, lastFrame, lastNonEmptyRow, promptRow } from './testing/tty.ts'
import { THEMES, ThemeProvider } from './theme.ts'

function stubBackend(): Backend {
  return {
    send() {},
    onEvent() {
      return () => {}
    },
    onClose() {
      return () => {}
    },
    dispose() {},
  }
}

const READY = {
  t: 'ready' as const,
  protocol: 1,
  version: '0.2.0',
  model: 'deepseek-chat',
  workspace: '/tmp/ws',
  planMode: false,
  hasApiKey: true,
  busy: false,
  tools: [{ name: 'read_file', description: '读文件' }],
  contextLimit: 64000,
}

type Harness = {
  out: FakeStdout
  store: SessionStore
  flush: () => Promise<void>
  frame: () => string[]
  unmount: () => void
}

function mount(columns: number, rows: number, opts: { ready?: boolean; fullscreen?: boolean } = {}): Harness {
  // App 挂载时会开 Alternate Scroll。terminal.ts 默认往真的 process.stdout 写，
  // 那会把 `?1007h` 刷到跑测试的终端上（还可能留着模式没关）。改道到数组。
  resetForTest([])

  const out = new FakeStdout(columns, rows)
  const stdin = new FakeStdin()
  const store = new SessionStore()
  if (opts.ready !== false) store.apply(READY)

  const instance = render(
    createElement(
      ThemeProvider,
      { value: THEMES.dark },
      createElement(App, {
        store,
        backend: stubBackend(),
        fullscreen: opts.fullscreen ?? true,
      }),
    ),
    {
      stdout: out as unknown as NodeJS.WriteStream,
      stdin: stdin as unknown as NodeJS.ReadStream,
      exitOnCtrlC: false,
      patchConsole: false,
      interactive: true,
    },
  )

  return {
    out,
    store,
    flush: async () => {
      await instance.waitUntilRenderFlush()
    },
    frame: () => lastFrame(out),
    unmount: () => instance.unmount(),
  }
}

/**
 * 让 spinner 真的出现。
 *
 * 注意 `thinking` 事件**只设 step、不设 busy**（store.ts:192），单用它的话
 * `plan.spinner` 为 false、spinner 压根不渲染，断言就成了恒真的空气。
 * 第一版这两条测试就是这么写的，改坏 Spinner 也照样绿 —— 所以这里必须发 busy。
 */
function startTurn(store: SessionStore, step = 12): void {
  store.submitPrompt('干活')
  store.apply({ t: 'busy', busy: true })
  store.apply({ t: 'thinking', step })
}

/** 往 store 里灌 n 条助手消息，模拟对话变长。 */
function grow(store: SessionStore, n: number): void {
  for (let i = 0; i < n; i++) {
    store.apply({ t: 'text_delta', text: `第 ${i} 条回复，够长好让它折行 ${'x'.repeat(40)}\n` })
  }
}

describe('输入框钉在最后一行', () => {
  test('对话从 1 条长到 200 条，输入框行号一个字都不变', async () => {
    const h = mount(80, 24)
    await h.flush()
    const base = promptRow(h.frame())
    expect(base).toBeGreaterThan(0)

    for (const n of [1, 20, 200]) {
      grow(h.store, n)
      await h.flush()
      const lines = h.frame()
      expect(promptRow(lines)).toBe(base)
      expect(lastNonEmptyRow(lines)).toBe(23)
    }
    h.unmount()
  })

  test('spinner 出现时行号也不变（spinner 占的那一行是从对话区借的）', async () => {
    const h = mount(80, 24)
    await h.flush()
    const before = promptRow(h.frame())

    startTurn(h.store, 1)
    await h.flush()
    const during = promptRow(h.frame())
    // 先确认 spinner 真的画出来了，否则这条断言毫无意义
    expect(h.frame().some(l => l.includes('Esc 中断'))).toBe(true)

    // spinner 多占 1 行，所以输入框会下移？不会——底部整摞是贴底的，
    // spinner 插在上面，输入框仍然在同一个「距底部」的位置上。
    // （第一版这条测试用 `thinking` 事件就断言完了，而 thinking 不设 busy，
    // spinner 压根没渲染，断言成了空气。所以上面先 expect 它真的出现了。）
    expect(during).toBe(before)
    expect(lastNonEmptyRow(h.frame())).toBe(23)
    h.unmount()
  })

  test('ready 还没到的时候行号也一样（状态栏画空盒子而不是消失）', async () => {
    const withReady = mount(80, 24)
    await withReady.flush()
    const a = promptRow(withReady.frame())
    withReady.unmount()

    const withoutReady = mount(80, 24, { ready: false })
    await withoutReady.flush()
    const b = promptRow(withoutReady.frame())
    withoutReady.unmount()

    // 这条就是「StatusLine 在 !ready 时 return null，而 App 照样记了 1 行」
    // 那个 off-by-one 的回归点
    expect(b).toBe(a)
  })
})

describe('窄屏不再把底部挤变形', () => {
  // 每一条对应一个实测过的漂移源
  const cases: Array<[string, number, (s: SessionStore) => void]> = [
    ['状态栏折行（COLS=20 实测过）', 20, () => {}],
    ['spinner 折行', 20, s => startTurn(s)],
    [
      '确认框按钮折行（COLS=44 实测过，会把「动作: 详情」整行挤没）',
      44,
      s =>
        s.apply({
          t: 'approval_request',
          id: 1,
          action: '执行命令',
          detail: 'rm -rf /tmp/some/really/long/path/that/keeps/going',
          reason: '这条命令会删除文件，需要你确认一下才行',
        }),
    ],
    [
      '关闭原因很长',
      40,
      s => s.markClosed('后端连接断开：' + 'x'.repeat(200)),
    ],
  ]

  for (const [name, columns, setup] of cases) {
    test(`${name} —— 底部仍然贴底，行号和宽屏一致`, async () => {
      const narrow = mount(columns, 16)
      setup(narrow.store)
      await narrow.flush()
      const narrowLines = narrow.frame()
      expect(lastNonEmptyRow(narrowLines)).toBe(15)
      const narrowRow = promptRow(narrowLines)
      expect(narrowRow).toBeGreaterThanOrEqual(0)
      narrow.unmount()

      const wide = mount(120, 16)
      setup(wide.store)
      await wide.flush()
      expect(promptRow(wide.frame())).toBe(narrowRow)
      wide.unmount()
    })
  }
})

describe('矮终端与 resize', () => {
  for (const rows of [12, 10, 8, 6, 5, 4]) {
    test(`rows=${rows} 仍然有可用的输入框且贴底`, async () => {
      const h = mount(60, rows)
      grow(h.store, 30)
      await h.flush()
      const lines = h.frame()
      expect(promptRow(lines)).toBeGreaterThanOrEqual(0)
      expect(lastNonEmptyRow(lines)).toBe(rows - 1)
      h.unmount()
    })
  }

  test('resize 之后依然贴底（先放大再缩小）', async () => {
    const h = mount(80, 40)
    grow(h.store, 60)
    await h.flush()

    for (const [c, r] of [
      [40, 12],
      [120, 30],
      [60, 20],
    ] as const) {
      h.out.resize(c, r)
      await h.flush()
      const lines = h.frame()
      expect(promptRow(lines)).toBeGreaterThanOrEqual(0)
      expect(lastNonEmptyRow(lines)).toBe(r - 1)
    }
    h.unmount()
  })

  test('--no-fullscreen 按 rows-1 布局（留一行，免得卸载时 ESC[3J 清掉用户的 scrollback）', async () => {
    const h = mount(80, 24, { fullscreen: false })
    grow(h.store, 30)
    await h.flush()
    const lines = h.frame()
    expect(lastNonEmptyRow(lines)).toBe(22) // rows-1-1
    h.unmount()
  })
})

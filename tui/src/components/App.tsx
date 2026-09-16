/**
 * 全屏布局 + 鼠标。从上到下：
 *
 *   TranscriptView   自绘滚动的对话记录（占满剩余高度，所有消息都活着、可点）
 *   Spinner / 确认框  正在跑 / 需要确认时出现
 *   PromptInput      输入框（3 行）
 *   StatusLine       状态栏（1 行）
 *
 * 鼠标：我们自己开了鼠标追踪（main.tsx），这里从 raw stdin 里解析 SGR 事件。
 *   点击工具组折叠行 → 展开/收起那一条
 *   滚轮            → 滚动 transcript
 * 代价：终端原生滚轮和拖拽选中被接管，选中改按 option 拖拽走终端原生。
 *
 * 键盘同样能滚：↑/↓ 一行、PgUp/PgDn 一屏、Home/End 顶/底。输入历史走 Ctrl-P/N。
 */

import { Box, Text, useApp, useInput, useWindowSize } from 'ink'
import type { EventEmitter } from 'node:events'
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import type { Backend } from '../backend.ts'
import { writeClipboard } from '../clipboard.ts'
import type { MouseEvent } from '../mouse.ts'
import {
  EMPTY_SELECTION,
  clearSelection,
  endSelection,
  extendSelection,
  hasSelection,
  selectedText,
  startSelection,
  type SelectionState,
} from '../selection.ts'
import type { ApprovalDecision } from '../protocol.ts'
import type { SessionStore } from '../store.ts'
import { useTheme } from '../theme.ts'
import { ApprovalDialog } from './ApprovalDialog.tsx'
import { Banner } from './Banner.tsx'
import { PromptInput } from './PromptInput.tsx'
import { Spinner } from './Spinner.tsx'
import { StatusLine } from './StatusLine.tsx'
import { TranscriptView, type TranscriptHandle } from './TranscriptView.tsx'

const HELP = [
  '/help     显示本帮助',
  '/tools    列出可用工具',
  '/clear    清空对话（后端的上下文也会清）',
  '/exit     退出',
  '',
  'Enter 发送 · Esc 中断当前这轮',
  '展开过程详情：鼠标点工具行，或 Ctrl-O 整体展开/收起',
  '选中复制：直接拖拽选文本，松手自动进剪贴板 · Esc 清除选区',
  '滚动：滚轮 / ↑↓ / PgUp PgDn / Home End',
  'Ctrl-P/Ctrl-N 翻输入历史 · Ctrl-W 删词 · Ctrl-U 删到行首 · Ctrl-C 退出',
].join('\n')

type Props = {
  store: SessionStore
  backend: Backend
  /** main.tsx 过滤 stdin 后把鼠标事件从这里发过来。 */
  mouseEmitter: EventEmitter
}

export function App({ store, backend, mouseEmitter }: Props) {
  const theme = useTheme()
  const { exit } = useApp()
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const transcript = useRef<TranscriptHandle>(null)
  /** 文本选区。自研的——开了鼠标追踪后终端原生拖选就没了，只能自己做。 */
  const [selection, setSelection] = useState<SelectionState>(EMPTY_SELECTION)
  /** 复制成功后短暂提示，让用户知道「选中的已经进剪贴板了」。 */
  const [copied, setCopied] = useState<string | null>(null)

  // 关键：用 useWindowSize 而不是直接读 process.stdout——它订阅终端 resize 事件，
  // 窗口一变大变小就重渲染，整个布局（宽度撑满的 banner/输入框、视口高度、
  // 内容折行）跟着重算。直接读 process.stdout 是一次性快照，resize 后布局就错位了。
  const { columns, rows } = useWindowSize()

  // resize 后：光标归位 + 触发一次重测量，用「全量覆写」清掉残影。
  //
  // 背景：实测 Ink **完全不发擦除序列**（没有 EL `ESC[K`，也没有 ED `ESC[J`），
  // 它靠「用新字符覆盖旧字符」来更新画面。所以残影的成因是画面没被完整覆写：
  // 缩放后新帧的起始行位置漂了，旧帧还留在上面，就出现两个界面叠着。
  //
  // 这里刻意**不发 `ESC[2J` 清屏**。清屏会擦掉刚画好的那一帧，而 Ink 有 30fps
  // 节流、且认为「内容没变」时不重画——一旦最后那次重绘被丢掉，屏幕就永久空白
  // （这正是上一版 resize 后全黑的原因；Ink 的 instance.clear() 同理，它清完还会
  // sync 帧缓存）。只发光标归位 `ESC[H` 是安全的：它不擦任何东西，最坏情况是
  // 画面没变，绝不会变空。
  //
  // 除残影靠「全量覆写」：根节点 height={rows} + TranscriptView 用空格铺满
  // 空白区，每一行都会被真实写入，旧字符无处存活。
  //
  // 这里**不能**用 key 去 remount 整棵树。之前那样做的后患很实在：
  // TranscriptView 被换成新实例后，transcript.current 还指向已卸载的旧组件，
  // hitTest 拿的是过期行坐标 —— 表现就是「放大之后点击折叠行没反应」。
  // epoch 只用来强制 Ink 重算一帧（作为 prop 往下传，不当 key）。
  const [epoch, setEpoch] = useState(0)
  const firstLayout = useRef(true)
  useEffect(() => {
    if (firstLayout.current) {
      firstLayout.current = false
      return
    }
    process.stdout.write('\x1b[H')
    setEpoch(e => e + 1)
  }, [columns, rows])

  useEffect(() => {
    const offEvent = backend.onEvent(event => store.apply(event))
    const offClose = backend.onClose(reason => {
      if (reason) store.markClosed(reason)
      exit()
    })
    return () => {
      offEvent()
      offClose()
    }
  }, [backend, store, exit])

  // 鼠标事件（已由 main.tsx 从 stdin 过滤出来）：滚轮滚动、点击工具行展开。
  //
  useEffect(() => {
    const onMouse = (events: MouseEvent[]) => {
      for (const ev of events) {
        if (ev.type === 'wheel') {
          transcript.current?.scrollBy(ev.direction === 'up' ? 3 : -3)
          continue
        }
        // SGR 是 1-based；视口从屏幕第 0 行开始，所以减 1 即视口内行号。
        // 招牌在滚动内容里，它的高度已由 hitMap 算进内容坐标。
        const viewRow = ev.row - 1
        const viewCol = ev.col - 1

        if (ev.type === 'down') {
          // 按下先清掉旧高亮并记锚点。此时还不知道用户是想点击还是想拖选，
          // 等松手时用「有没有移动过」来区分。
          const at = transcript.current?.pointAt(viewCol, viewRow)
          if (at) setSelection(startSelection(at))
          continue
        }
        if (ev.type === 'drag') {
          const at = transcript.current?.pointAt(viewCol, viewRow)
          if (at) setSelection(cur => extendSelection(cur, at))
          continue
        }
        // up：拖过了 → 当作选中并复制；没拖 → 当作点击去展开折叠行
        setSelection(cur => {
          const done = endSelection(cur)
          if (hasSelection(done)) {
            const lines = transcript.current?.getLines() ?? []
            const text = selectedText(done, lines)
            if (text) {
              void writeClipboard(text).then(via => {
                if (via) setCopied(`已复制 ${text.length} 字`)
              })
            }
            return done
          }
          // 没有拖动 = 单击
          const key = transcript.current?.hitTest(viewRow)
          if (key) store.toggleEntry(key)
          return clearSelection()
        })
      }
    }
    mouseEmitter.on('mouse', onMouse)
    return () => {
      mouseEmitter.off('mouse', onMouse)
    }
  }, [mouseEmitter, store])

  // 「已复制」提示自动消失。依赖 copied 本身：每次新的复制都重开一个定时器。
  useEffect(() => {
    if (copied === null) return
    const timer = setTimeout(() => setCopied(null), 1500)
    return () => clearTimeout(timer)
  }, [copied])

  const inputActive = state.approval === null && state.closed === null

  useInput((input, key) => {
    if (key.upArrow) return transcript.current?.scrollBy(1)
    if (key.downArrow) return transcript.current?.scrollBy(-1)
    if (key.pageUp) return transcript.current?.scrollBy(Math.max(1, rows - 6))
    if (key.pageDown) return transcript.current?.scrollBy(-Math.max(1, rows - 6))
    if (key.home) return transcript.current?.scrollToTop()
    if (key.end) return transcript.current?.scrollToBottom()

    if (key.escape) {
      // 有选区时 Esc 先清选区：用户刚选完想取消高亮，不该顺手把 agent 中断了
      if (hasSelection(selection)) {
        setSelection(clearSelection())
        return
      }
      if (state.busy && state.approval === null) {
        backend.send({ t: 'cancel' })
        store.notice('warn', '已请求中断')
      }
      return
    }
    if (key.ctrl && input === 'o') {
      store.toggleExpanded()
      return
    }
    if (key.ctrl && input === 'c') {
      if (state.busy) {
        backend.send({ t: 'cancel' })
        store.notice('warn', '已请求中断（再按一次 Ctrl-C 退出）')
      } else {
        exit()
      }
    }
  })

  const handleSubmit = useMemo(
    () => (text: string) => {
      const command = text.trim()

      if (command === '/exit' || command === '/quit' || command === '/q') {
        exit()
        return
      }
      if (command === '/help') {
        store.submitPrompt(text)
        store.notice('info', HELP)
        transcript.current?.scrollToBottom()
        return
      }
      if (command === '/tools') {
        const tools = state.ready?.tools ?? []
        store.submitPrompt(text)
        store.notice(
          'info',
          tools.length === 0
            ? '（没有可用工具）'
            : tools.map(t => `${t.name}\n  ${t.description}`).join('\n'),
        )
        transcript.current?.scrollToBottom()
        return
      }
      if (command === '/clear') {
        backend.send({ t: 'reset' })
        return
      }
      if (state.busy) {
        store.notice('warn', '正在处理上一条，先等它结束或按 Esc 中断')
        return
      }

      store.submitPrompt(command)
      transcript.current?.scrollToBottom()
      backend.send({ t: 'prompt', text: command })
    },
    [backend, store, exit, state.busy, state.ready],
  )

  const handleDecision = useMemo(
    () => (decision: ApprovalDecision) => {
      if (!state.approval) return
      backend.send({ t: 'approval_reply', id: state.approval.id, decision })
    },
    [backend, state.approval],
  )

  // 底部固定区高度 → 视口高度。确认框：边框2+标题1+空1+动作1+原因1+空1+按键1=8
  const spinnerRows = state.busy && state.approval === null ? 1 : 0
  const approvalRows = state.approval ? 8 : 0
  const closedRows = state.closed ? 1 : 0
  const bottomRows = spinnerRows + approvalRows + closedRows + 3 /* input */ + 1 /* status */
  /** 招牌 + 对话可用的总高度。 */
  const availRows = Math.max(3, rows - bottomRows)

  // 招牌是**滚动内容的第一项**，不是固定头部。
  //
  // 曾经把它做成视口外的固定头部、内容变长就整体隐藏——结果是「往上滚也滚不回
  // 招牌」，它压根不在滚动轨道上。放回内容里，行为就自然了：对话短时它在最上面
  // 看得见，对话变长被顶出视口，往上滚又能滚回来（和终端里翻历史一个直觉）。
  //
  // 这样 viewportRows 就是整个可用高度，命中测试也不用再减招牌偏移——
  // 招牌的高度已经算在滚动内容坐标里，由 TranscriptView 的 hitMap 统一处理。
  const viewportRows = availRows

  return (
    <Box flexDirection="column" width={columns} height={rows}>
      <TranscriptView
        entries={state.entries}
        expanded={state.expanded}
        height={viewportRows}
        columns={columns}
        handleRef={transcript}
        repaintToken={epoch}
        selection={selection}
        header={state.ready ? <Banner ready={state.ready} columns={columns} /> : undefined}
      />

      {spinnerRows > 0 && <Spinner step={state.step} startedAt={state.startedAt} />}

      {state.approval && (
        <ApprovalDialog approval={state.approval} columns={columns} onDecide={handleDecision} />
      )}

      {state.closed && <Text color={theme.error}>✗ {state.closed}</Text>}

      <PromptInput
        busy={state.busy}
        isActive={inputActive}
        columns={columns}
        placeholder={state.busy ? '正在处理…（Esc 中断）' : '说点什么，或 /help'}
        onSubmit={handleSubmit}
      />
      <Box>
        <StatusLine state={state} columns={columns} />
        {copied && <Text color={theme.success}>{'  ✓ ' + copied}</Text>}
      </Box>
    </Box>
  )
}

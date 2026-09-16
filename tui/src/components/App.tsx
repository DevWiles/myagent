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

import { Box, Text, useApp, useInput } from 'ink'
import type { EventEmitter } from 'node:events'
import { useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import type { Backend } from '../backend.ts'
import type { MouseEvent } from '../mouse.ts'
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
  '滚动：滚轮 / ↑↓ / PgUp PgDn / Home End · 复制：按住 option 拖拽',
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

  // useStdout 的 columns/rows 会随 resize 更新；useWindowSize 也行，这里直接读。
  const columns = process.stdout.columns || 80
  const rows = process.stdout.rows || 24

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
  useEffect(() => {
    const onMouse = (events: MouseEvent[]) => {
      for (const ev of events) {
        if (ev.type === 'wheel') {
          transcript.current?.scrollBy(ev.direction === 'up' ? 3 : -3)
        } else {
          // SGR 是 1-based；视口顶 = 屏幕第 0 行，所以 row-1 就是视口行
          const key = transcript.current?.hitTest(ev.row - 1)
          if (key) store.toggleEntry(key)
        }
      }
    }
    mouseEmitter.on('mouse', onMouse)
    return () => {
      mouseEmitter.off('mouse', onMouse)
    }
  }, [mouseEmitter, store])

  const inputActive = state.approval === null && state.closed === null

  useInput((input, key) => {
    if (key.upArrow) return transcript.current?.scrollBy(1)
    if (key.downArrow) return transcript.current?.scrollBy(-1)
    if (key.pageUp) return transcript.current?.scrollBy(Math.max(1, rows - 6))
    if (key.pageDown) return transcript.current?.scrollBy(-Math.max(1, rows - 6))
    if (key.home) return transcript.current?.scrollToTop()
    if (key.end) return transcript.current?.scrollToBottom()

    if (key.escape && state.busy && state.approval === null) {
      backend.send({ t: 'cancel' })
      store.notice('warn', '已请求中断')
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
  const viewportRows = Math.max(3, rows - bottomRows)

  return (
    <Box flexDirection="column" width={columns} height={rows}>
      <TranscriptView
        entries={state.entries}
        expanded={state.expanded}
        height={viewportRows}
        columns={columns}
        handleRef={transcript}
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
      <StatusLine state={state} columns={columns} />
    </Box>
  )
}

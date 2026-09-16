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
import type { MouseEvent } from '../mouse.ts'
import type { ApprovalDecision } from '../protocol.ts'
import type { SessionStore } from '../store.ts'
import { useTheme } from '../theme.ts'
import { ApprovalDialog } from './ApprovalDialog.tsx'
import { BANNER_HEIGHT, Banner } from './Banner.tsx'
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
  /** 对话内容（不含招牌）的真实行数，由 TranscriptView 量好上报。 */
  const [entriesRows, setEntriesRows] = useState(0)
  /** 招牌是否还占着顶部。见下面的滞回判定。 */
  const bannerVisible = useRef(true)

  // 关键：用 useWindowSize 而不是直接读 process.stdout——它订阅终端 resize 事件，
  // 窗口一变大变小就重渲染，整个布局（宽度撑满的 banner/输入框、视口高度、
  // 内容折行）跟着重算。直接读 process.stdout 是一次性快照，resize 后布局就错位了。
  const { columns, rows } = useWindowSize()

  // resize 后必须自己清一次屏，否则会看到两个界面叠在一起。
  //
  // 原因：Ink 的增量渲染只重画「它认为变了的那些格子」。终端变窄/变矮时，
  // 新画面占的区域比旧画面小，旧画面超出新边界的那部分（比如原来更宽的招牌
  // 右半截、原来更靠下的行）不在 Ink 的重画范围内，就留在备用屏幕上不走了。
  // 主屏模式下终端自己会滚掉这些残渣，备用屏幕没有滚动、谁都不管，只能我们清。
  //
  // 跳过首次：进备用屏幕时 main.tsx 已经清过一次，这里再清会白闪一下。
  const firstLayout = useRef(true)
  useEffect(() => {
    if (firstLayout.current) {
      firstLayout.current = false
      return
    }
    process.stdout.write('\x1b[2J\x1b[H')
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
  /** 招牌 + 对话可用的总高度。 */
  const availRows = Math.max(3, rows - bottomRows)

  // 招牌固定在顶部，直到对话内容多到铺满屏幕才让位给它。
  //
  // 用 ref 存这个开关、在 render 里直接算，而不是 useState：它是从「内容行数」
  // 推出来的派生值，用 state 就得在 render 阶段 setState（React 不喜欢）或多跑
  // 一轮渲染。entriesRows 本身是 state，它一变就会重渲染，所以 ref 够了。
  //
  // 两个阈值（滞回）而不是一个：只用一个阈值时，内容正好卡在边界上会
  // 「隐藏→空间变大→该显示→空间变小→该隐藏」每帧翻转，屏幕会疯狂闪。
  // 隐藏后要等内容明显缩回去（留 2 行余量）才重新显示。
  const roomWithBanner = availRows - BANNER_HEIGHT
  if (bannerVisible.current && entriesRows > roomWithBanner) bannerVisible.current = false
  else if (!bannerVisible.current && entriesRows <= roomWithBanner - 2) bannerVisible.current = true

  // 终端太矮就不给招牌留位置了，对话优先
  const showBanner =
    state.ready !== null && bannerVisible.current && rows >= BANNER_HEIGHT + 6
  const viewportRows = Math.max(3, availRows - (showBanner ? BANNER_HEIGHT : 0))

  return (
    <Box flexDirection="column" width={columns} height={rows}>
      {showBanner && state.ready && <Banner ready={state.ready} columns={columns} />}

      <TranscriptView
        entries={state.entries}
        expanded={state.expanded}
        height={viewportRows}
        columns={columns}
        handleRef={transcript}
        onEntriesHeight={setEntriesRows}
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

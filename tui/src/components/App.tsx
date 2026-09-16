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
  /** 对话视口的第一行在屏幕上的行号（招牌占了多少行）。鼠标命中测试要减掉它。 */
  const viewportTopRef = useRef(0)

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
  // 坐标换算：SGR 报的是 1-based 的**整屏**行号，而 hitTest 要的是**视口内**
  // 行号。招牌画在视口外、占着屏幕最上面若干行，所以必须把它的高度减掉。
  // 少减这一项的后果很具体：招牌一出现（点全屏、屏幕够高时）点击就整体偏移
  // BANNER_HEIGHT 行，表现成「小窗能点开，全屏就点不动」。
  //
  // 用 ref 读偏移量而不是写进依赖数组：这个 effect 只该在挂载时注册一次监听，
  // 把布局量塞进依赖会让它随每次尺寸变化反复解绑重绑。
  useEffect(() => {
    const onMouse = (events: MouseEvent[]) => {
      for (const ev of events) {
        if (ev.type === 'wheel') {
          transcript.current?.scrollBy(ev.direction === 'up' ? 3 : -3)
        } else {
          const viewportRow = ev.row - 1 - viewportTopRef.current
          if (viewportRow < 0) continue // 点在招牌上，不是对话内容
          const key = transcript.current?.hitTest(viewportRow)
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
  // 招牌在视口之上，视口顶行号 = 招牌高度（不显示时为 0）
  viewportTopRef.current = showBanner ? BANNER_HEIGHT : 0

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
        repaintToken={epoch}
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

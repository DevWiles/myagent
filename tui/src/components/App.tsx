/**
 * 全屏布局。从上到下：
 *
 *   TranscriptView   自绘滚动的对话记录（占满剩余高度，所有消息都活着、可点）
 *   Spinner / 确认框  正在跑 / 需要确认时出现
 *   PromptInput      输入框（3 行）
 *   StatusLine       状态栏（1 行）
 *
 * 不接管鼠标：滚轮由终端的 Alternate Scroll 转成方向键（见 main.tsx），
 * 拖拽选中、复制全部交回终端原生。折叠详情用 Ctrl-O。
 * 滚动：↑/↓ 一行、PgUp/PgDn 一屏、Home/End 顶/底。输入历史走 Ctrl-P/N。
 */

import { Box, Text, useApp, useInput, useWindowSize } from 'ink'
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import type { Backend } from '../backend.ts'
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
  'Ctrl-O 展开/收起工具调用详情',
  '选中复制：直接拖拽选文本（终端原生），Cmd-C 复制',
  '滚动：滚轮 / ↑↓ / PgUp PgDn / Home End',
  'Ctrl-P/Ctrl-N 翻输入历史 · Ctrl-W 删词 · Ctrl-U 删到行首 · Ctrl-C 退出',
].join('\n')

type Props = {
  store: SessionStore
  backend: Backend
}

export function App({ store, backend }: Props) {
  const theme = useTheme()
  const { exit } = useApp()
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const transcript = useRef<TranscriptHandle>(null)

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

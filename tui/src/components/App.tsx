/**
 * 全屏布局。从上到下：
 *
 *   TranscriptView   自绘滚动的对话记录（吃掉所有剩余高度）
 *   ─ 底部固定区 ─
 *   Spinner / 确认框  正在跑 / 需要确认时出现
 *   PromptInput      输入框
 *   StatusLine       状态栏
 *
 * ## 输入框是怎么钉在最后一行的
 *
 * 靠 Yoga，不靠算术。transcript 拿 `flexGrow={1} flexBasis={0}`，底部每一块都
 * `flexShrink={0}` 且**高度写死**，于是「剩下多少给 transcript」是 Yoga 算的，
 * 输入框的行号只由「底部放了哪几块」决定，和对话内容、滚动位置、折行全都无关。
 *
 * 以前这里是一串手算（`rows - spinnerRows - approvalRows - ... - 3 - 1`），
 * 六七个漂移源：状态栏没 ready 时不渲染却照样记 1 行、spinner 和确认框按钮窄屏
 * 折行、关闭原因太长折行……算多了就被 Yoga 悄悄从别人身上挤回来（实测 COLS=20
 * 时输入框被挤成 4 行还多一个空行），算少了底部就空一行。
 *
 * `planBottomStack` 现在只干一件事：终端太矮时按固定顺序决定砍掉谁。它算出的
 * `height` 只用于给 transcript 一个首帧 seed —— **算错也不会让输入框漂**。
 *
 * ## 根节点的 height 不能丢
 *
 * `height={layoutRows}` 是挡住闪屏的东西：Ink 的 `Output` 高度取的是根节点的
 * computed height，一旦根节点没有显式高度，帧高就变成内容高，超过视口时
 * `shouldClearTerminalForFrame` 会**每帧全清**（实测：去掉高度后帧 31 行、
 * clearTerminal 出现；钉住高度则 10 行、不出现）。
 *
 * 不接管鼠标（这一期）：滚轮由终端的 Alternate Scroll 转成方向键（见 terminal.ts），
 * 拖拽选中、复制全部交回终端原生。折叠详情用 Ctrl-O。
 */

import { Box, useApp, useInput, useWindowSize } from 'ink'
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import type { Backend } from '../backend.ts'
import { planBottomStack } from '../layout.ts'
import type { ApprovalDecision } from '../protocol.ts'
import type { SessionStore } from '../store.ts'
import { MODE_ALT_SCROLL, disableModes, enableModes } from '../terminal.ts'
import { ApprovalDialog } from './ApprovalDialog.tsx'
import { PlanConfirmDialog } from './PlanConfirmDialog.tsx'
import { Banner } from './Banner.tsx'
import { ClosedLine } from './ClosedLine.tsx'
import { PromptInput } from './PromptInput.tsx'
import { Spinner } from './Spinner.tsx'
import { StatusLine } from './StatusLine.tsx'
import { TranscriptView, type TranscriptHandle } from './TranscriptView.tsx'

const HELP = [
  '/help     显示本帮助',
  '/plan 目标  先规划后执行：拆成任务依赖图，确认后逐个跑',
  '/tools    列出可用工具',
  '/clear    清空对话（后端的上下文也会清）',
  '/exit     退出',
  '',
  'Enter 发送 · Esc 中断当前这轮',
  'Ctrl-O 展开/收起工具调用详情',
  '选中复制：直接拖拽选文本（终端原生），Cmd-C 复制',
  '滚动：滚轮 / ↑↓ / PgUp PgDn / Home End',
  'Ctrl-P/Ctrl-N 翻输入历史 · Ctrl-W 删词 · Ctrl-U 删到行首',
  'Ctrl-C 退出（正在跑的时候按一下是中断）· Ctrl-D 空输入时退出',
].join('\n')

type Props = {
  store: SessionStore
  backend: Backend
  /**
   * 是否独占终端。关掉时按 `rows - 1` 布局：Ink 卸载时若末帧高度 >= 视口会发
   * `clearTerminal`，那里面含 `ESC[3J`，**会清掉用户真正的 scrollback**
   * （实测：根高 rows 且不在备用屏 → 3J 出现 1 次；根高 rows-1 → 0 次）。
   */
  fullscreen: boolean
}

export function App({ store, backend, fullscreen }: Props) {
  const { exit } = useApp()
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const transcript = useRef<TranscriptHandle>(null)

  // 关键：用 useWindowSize 而不是直接读 process.stdout——它订阅终端 resize 事件，
  // 窗口一变大变小就重渲染，整个布局跟着重算。直接读 process.stdout 是一次性快照。
  const { columns, rows } = useWindowSize()

  const layoutRows = fullscreen ? rows : Math.max(8, rows - 1)

  // resize 后触发一次重测量。
  //
  // 这里**不写任何控制序列**。以前有一句 `process.stdout.write('\x1b[H')`，
  // 那是 bug：Ink 的帧是「相对当前光标」增删的（`log-update` 用
  // `eraseLines(previousLineCount)`），外人插一句挪光标会让它算错要擦几行。
  // resize 本来就由 Ink 自己处理——它在宽度变化时会 `clearTerminal` + 全量重画。
  //
  // 也**不能**用 key 去 remount 整棵树：那样 transcript.current 会指向已卸载的
  // 旧组件，命中测试拿到过期坐标（就是当年「放大之后点折叠行没反应」那个 bug）。
  const [epoch, setEpoch] = useState(0)
  const firstLayout = useRef(true)
  useEffect(() => {
    if (firstLayout.current) {
      firstLayout.current = false
      return
    }
    setEpoch(e => e + 1)
  }, [columns, rows])

  // 滚轮：Alternate Scroll 让终端把滚轮翻译成方向键，而 ↑/↓ 我们绑到了滚动上。
  //
  // 放在 effect 里而不是 main.tsx 里，是为了**退出时的字节顺序**：React 的 cleanup
  // 跑在 Ink 写 `1049l` **之前**，所以能保证「先关模式、再还主屏」。放在 main.tsx
  // 就只能靠 process.on('exit') 兜，那已经是 Ink 卸载之后了 —— 实测顺序会变成
  // `1049l`(4817) 然后才 `1007r`(4837)。这一期是 Alternate Scroll，顺序反了也无害；
  // 但下一期接管鼠标时，`1002/1006` 留在主屏上会让点击直接吐乱码，所以现在就把
  // 这个接缝放对。崩溃路径跳过 React cleanup，那条由 terminal.ts 的兜底负责。
  //
  // 刻意不开鼠标追踪（1000/1002/1006）：那会夺走终端原生的拖拽选中。
  useEffect(() => {
    if (!fullscreen) return
    enableModes(MODE_ALT_SCROLL)
    return () => disableModes()
  }, [fullscreen])

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

  const inputActive =
    state.approval === null && state.planConfirm === null && state.closed === null

  // 计划确认框借用审批框的降级档位：两者永不同时出现
  // （确认发生在执行之前，审批发生在执行之中），共用一套高度约定即可
  const bottom = planBottomStack(layoutRows, {
    busy: state.busy,
    hasApproval: state.approval !== null || state.planConfirm !== null,
    hasClosed: state.closed !== null,
  })

  const handleDecision = useMemo(
    () => (decision: ApprovalDecision) => {
      if (!state.approval) return
      backend.send({ t: 'approval_reply', id: state.approval.id, decision })
    },
    [backend, state.approval],
  )

  const handlePlanConfirm = useMemo(
    () => (approved: boolean) => {
      const confirm = state.planConfirm
      if (!confirm) return
      backend.send({ t: 'plan_confirm_reply', id: confirm.id, approved })
      store.clearPlanConfirm()
      transcript.current?.scrollToBottom()
    },
    [backend, store, state.planConfirm],
  )

  useInput((input, key) => {
    // 确认框的按键在**这里**接，而不是在 ApprovalDialog 里。
    // 因为终端矮到放不下那个框时（bottom.approval === 'none'）组件根本不渲染，
    // 而后端此刻正卡在 approve() 上等回答——按键要是跟着组件消失，会话就挂死了。
    if (state.approval) {
      const answer = input.toLowerCase()
      if (answer === 'y') return handleDecision('yes')
      if (answer === 'n' || key.escape) return handleDecision('no')
      if (answer === 'a') return handleDecision('always')
      return
    }
    // 计划确认和审批同一套道理：矮终端下框可能不渲染，但后端正卡着等，
    // 按键必须无条件活着。刻意没有 [a]——每份计划都不一样，不给一次性授权。
    if (state.planConfirm) {
      const answer = input.toLowerCase()
      if (answer === 'y' || key.return) return handlePlanConfirm(true)
      if (answer === 'n' || key.escape) return handlePlanConfirm(false)
      return
    }

    if (key.upArrow) return transcript.current?.scrollBy(1)
    if (key.downArrow) return transcript.current?.scrollBy(-1)
    if (key.pageUp) return transcript.current?.scrollBy(Math.max(1, layoutRows - 6))
    if (key.pageDown) return transcript.current?.scrollBy(-Math.max(1, layoutRows - 6))
    if (key.home) return transcript.current?.scrollToTop()
    if (key.end) return transcript.current?.scrollToBottom()

    if (key.escape && state.busy) {
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
      if (command === '/plan' || command.startsWith('/plan ')) {
        const goal = command.slice('/plan'.length).trim()
        const features = state.ready?.features ?? []
        store.submitPrompt(command)
        transcript.current?.scrollToBottom()
        if (!features.includes('plan')) {
          // 老后端没有这个能力。这正是 features 存在的意义：界面知道差在哪儿，
          // 而不是把命令发出去换一句「未知命令」
          store.notice('error', '这个后端不支持先规划后执行——重新 build 一次 jar（mvn -DskipTests package）')
          return
        }
        if (goal.length === 0) {
          store.notice('info', '用法: /plan <要做的事>   例如  /plan 给 Task 加上重试能力')
          return
        }
        if (state.busy) {
          store.notice('warn', '正在处理上一条，先等它结束或按 Esc 中断')
          return
        }
        backend.send({ t: 'plan', goal })
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

  // 招牌是**滚动内容的第一项**，不是固定头部。
  //
  // 曾经把它做成视口外的固定头部、内容变长就整体隐藏——结果是「往上滚也滚不回
  // 招牌」，它压根不在滚动轨道上。放回内容里，行为就自然了：对话短时它在最上面
  // 看得见，对话变长被顶出视口，往上滚又能滚回来（和终端里翻历史一个直觉）。
  return (
    <Box flexDirection="column" width={columns} height={layoutRows}>
      <TranscriptView
        entries={state.entries}
        expanded={state.expanded}
        columns={columns}
        handleRef={transcript}
        repaintToken={epoch}
        seedHeight={Math.max(1, layoutRows - bottom.height)}
        header={state.ready ? <Banner ready={state.ready} columns={columns} /> : undefined}
      />

      <Box flexDirection="column" flexShrink={0}>
        {bottom.spinner && (
          <Spinner step={state.step} startedAt={state.startedAt} columns={columns} />
        )}

        {bottom.approval !== 'none' && state.approval && (
          <ApprovalDialog
            approval={state.approval}
            columns={columns}
            variant={bottom.approval}
          />
        )}

        {bottom.approval !== 'none' && !state.approval && state.planConfirm && (
          <PlanConfirmDialog columns={columns} variant={bottom.approval} />
        )}

        {bottom.closed && state.closed && <ClosedLine reason={state.closed} columns={columns} />}

        <PromptInput
          busy={state.busy}
          isActive={inputActive}
          columns={columns}
          bare={bottom.input === 'bare'}
          placeholder={state.busy ? '正在处理…（Esc 中断）' : '说点什么，或 /help'}
          onSubmit={handleSubmit}
          onRequestExit={exit}
        />

        {bottom.status && <StatusLine state={state} columns={columns} />}
      </Box>
    </Box>
  )
}

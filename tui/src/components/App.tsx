/**
 * inline 布局（不进备用屏、不清屏）。从上到下：
 *
 *   TranscriptView   自绘滚动的对话记录（占满剩余高度，所有消息都活着、可点）
 *   Spinner / 确认框  正在跑 / 需要确认时出现
 *   PromptInput      输入框（3 行）
 *   StatusLine       状态栏（1 行）
 *
 * 内容往主屏下方铺，输入框和状态栏由 Ink 钉在底部实时重画 —— 看着像独占，
 * 实际全部留在 scrollback：退出后能往上翻、能复制，多次启动的记录会叠在一起。
 *
 * 滚动、拖拽选中、复制全部交回终端原生（我们既不进备用屏也不接管鼠标）。
 * 所以方向键留给输入历史，折叠详情用 Ctrl-O。
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
import { Transcript } from './Transcript.tsx'

const HELP = [
  '/help     显示本帮助',
  '/tools    列出可用工具',
  '/clear    清空对话（后端的上下文也会清）',
  '/exit     退出',
  '',
  'Enter 发送 · Esc 中断当前这轮',
  'Ctrl-O 展开/收起工具调用详情',
  '选中复制：直接拖拽选文本，Cmd-C 复制（都是终端原生行为）',
  '往上翻历史：滚轮 / fn+↑↓ / Shift-PgUp（终端自己的 scrollback）',
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

  // 用 useWindowSize 而不是直接读 process.stdout：它订阅终端 resize 事件，
  // 窗口宽度一变就重渲染，撑满宽度的招牌/输入框和文本折行跟着重算。
  // 直接读 process.stdout 是一次性快照，resize 后宽度就错了。
  // inline 模式只关心宽度——高度由内容决定，不需要 rows。
  const { columns } = useWindowSize()

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
    // 不拦滚动键：inline 模式下往上翻是终端 scrollback 的事（滚轮 / fn+↑↓ /
    // Shift-PgUp），我们不该抢。方向键因此留给输入历史。
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


  return (
    <Box flexDirection="column" width={columns}>
      <Transcript
        entries={state.entries}
        expanded={state.expanded}
        columns={columns}
        header={state.ready ? <Banner ready={state.ready} columns={columns} /> : undefined}
      />

      {state.busy && state.approval === null && (
        <Spinner step={state.step} startedAt={state.startedAt} />
      )}

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

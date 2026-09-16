/**
 * 一条消息怎么画。
 *
 * 每种 entry 都有一个固定的行首标记，扫一眼就知道这行是谁说的：
 * `›` 用户 · `✻` 模型 · `·` 模型的内心戏 · `●` 工具 · `⚠`/`✗` 提示。
 * 标记比颜色可靠——颜色会被终端主题吃掉，字符不会。
 */

import { Box, Text } from 'ink'
import { firstLine, summarizeTools, truncate } from '../format.ts'
import { Markdown } from '../markdown.tsx'
import type { Entry, ToolCall } from '../store.ts'
import { useTheme, type Theme } from '../theme.ts'

type Props = {
  entry: Entry
  expanded: boolean
  columns: number
}

export function EntryView({ entry, expanded, columns }: Props) {
  const theme = useTheme()

  switch (entry.kind) {
    case 'user':
      return (
        <Box marginTop={1}>
          <Text color={theme.user} bold>
            {'› '}
          </Text>
          <Text color={theme.user}>{entry.text}</Text>
        </Box>
      )

    case 'assistant':
      return (
        <Box marginTop={1} flexDirection="row">
          <Text color={theme.brand}>{'✻ '}</Text>
          <Box flexGrow={1}>
            <Markdown markdown={entry.text} />
          </Box>
        </Box>
      )

    case 'thought':
      return (
        <Box marginTop={1}>
          <Text color={theme.thought}>{'· '}</Text>
          <Text color={theme.thought} italic>
            {entry.text}
          </Text>
        </Box>
      )

    case 'tools':
      return <ToolGroup calls={entry.calls} expanded={expanded} columns={columns} theme={theme} />

    case 'compacted':
      return (
        <Box marginTop={1}>
          <Text color={theme.warning}>
            {'⋯ 上下文已压缩 '}
            {entry.before} → {entry.after} tokens
            {entry.summarized ? '（早期细节已归纳为摘要）' : ''}
          </Text>
        </Box>
      )

    case 'notice': {
      const color =
        entry.level === 'error' ? theme.error : entry.level === 'warn' ? theme.warning : theme.subtle
      const mark = entry.level === 'error' ? '✗ ' : entry.level === 'warn' ? '⚠ ' : 'ℹ '
      return (
        <Box marginTop={1}>
          <Text color={color}>{mark}</Text>
          <Text color={color}>{entry.text}</Text>
        </Box>
      )
    }
  }
}

/**
 * 一组工具调用。
 *
 * 跑的时候逐条显示——用户需要知道它在干什么。
 * 跑完收成一行概括：细节留着只会把结论挤出屏幕。想看再 Ctrl-O 展开。
 */
function ToolGroup({
  calls,
  expanded,
  columns,
  theme,
}: {
  calls: readonly ToolCall[]
  expanded: boolean
  columns: number
  theme: Theme
}) {
  const running = calls.some(call => call.running)
  const failed = calls.filter(call => call.ok === false).length

  if (!running && !expanded) {
    // 失败的第一条原因即便在折叠态也要露出来：出错了还要按一下 Ctrl-O 才知道
    // 发生了什么，等于把问题藏起来。
    const firstFailure = calls.find(call => call.ok === false)
    return (
      <Box marginTop={1} flexDirection="column">
        <Box>
          <Text color={failed > 0 ? theme.error : theme.success}>{failed > 0 ? '✗ ' : '● '}</Text>
          <Text color={theme.subtle}>
            {summarizeTools(calls)}
            {failed > 0 ? ` · ${failed} 个失败` : ''}
          </Text>
        </Box>
        {firstFailure?.output && (
          <Box marginLeft={2}>
            <Text color={theme.error}>
              {firstLine(firstFailure.output, Math.max(10, columns - 4))}
            </Text>
          </Box>
        )}
      </Box>
    )
  }

  // 展开时整组**共享**一个行数预算，而不是每条各自截 20 行。
  // 之前是后者：一次读 4 个文件、每条 20 行，展开后一下吐出 80+ 行，
  // 把招牌和对话全冲出视口。预算按条数均摊，至少保证每条能露 3 行。
  const perCall = expanded ? perCallBudget(calls.length) : 1

  return (
    <Box marginTop={1} flexDirection="column">
      {calls.map(call => (
        <ToolLine
          key={call.id}
          call={call}
          expanded={expanded}
          maxOutputLines={perCall}
          columns={columns}
          theme={theme}
        />
      ))}
    </Box>
  )
}

function ToolLine({
  call,
  expanded,
  maxOutputLines,
  columns,
  theme,
}: {
  call: ToolCall
  expanded: boolean
  maxOutputLines: number
  columns: number
  theme: Theme
}) {
  const mark = call.running ? '◐ ' : call.ok ? '● ' : '✗ '
  const markColor = call.running ? theme.spinner : call.ok ? theme.success : theme.error
  // 预留行首标记、工具名和一点余量，剩下的给参数
  const argsWidth = Math.max(10, columns - call.tool.length - 8)

  return (
    <Box flexDirection="column">
      <Box>
        <Text color={markColor}>{mark}</Text>
        <Text color={theme.toolName}>{call.tool}</Text>
        <Text color={theme.toolArgs}> {truncate(compactArgs(call.args), argsWidth)}</Text>
      </Box>
      {expanded && call.output !== undefined && (
        <Box marginLeft={2} flexDirection="column">
          {outputPreview(call.output, maxOutputLines).map((line, i) => (
            <Text key={i} color={theme.inactive}>
              {truncate(line, Math.max(10, columns - 4))}
            </Text>
          ))}
        </Box>
      )}
      {!expanded && call.output !== undefined && !call.ok && (
        // 失败的原因无论如何都要露一行，否则用户只看到一个 ✗ 不知道发生了什么
        <Box marginLeft={2}>
          <Text color={theme.error}>{firstLine(call.output, Math.max(10, columns - 4))}</Text>
        </Box>
      )}
    </Box>
  )
}

/** 工具参数是 JSON 串，压成一行显示，太长的值也截掉。 */
function compactArgs(raw: string): string {
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      return Object.entries(parsed)
        .map(([key, value]) => `${key}=${truncate(String(value).replace(/\s+/g, ' '), 60)}`)
        .join(' ')
    }
  } catch {
    /* 不是 JSON 就原样显示 */
  }
  return raw.replace(/\s+/g, ' ')
}

/**
 * 一组工具调用展开后总共最多显示这么多行输出。
 * 是**整组的**预算，不是每条的——见 ToolGroup 里的均摊。
 * 完整内容始终原样发给了模型，这里只是给人看的。
 */
export const EXPANDED_OUTPUT_BUDGET = 24

/** 整组预算按条数均摊，至少保证每条能露 3 行。 */
export function perCallBudget(callCount: number): number {
  return Math.max(3, Math.floor(EXPANDED_OUTPUT_BUDGET / Math.max(1, callCount)))
}

export function outputPreview(output: string, limit: number): string[] {
  const lines = output.split('\n')
  if (lines.length <= limit) return lines
  return [...lines.slice(0, limit), `… 还有 ${lines.length - limit} 行`]
}

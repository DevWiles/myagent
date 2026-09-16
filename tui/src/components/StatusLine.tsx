/**
 * 底部状态栏。
 *
 * 只放那些「随时想知道、但不值得打断阅读」的东西：模型、上下文占用、上轮耗时。
 * 上下文占用尤其重要——它快满的时候会触发压缩，早期细节就丢了，
 * 用户有权提前看到这件事正在逼近，而不是等压缩发生了才被通知。
 */

import { Box, Text } from 'ink'
import { formatDuration, formatTokens } from '../format.ts'
import type { SessionState } from '../store.ts'
import { useTheme } from '../theme.ts'

/** 超过这个占用比例就变黄：提醒用户压缩快来了。 */
const WARN_RATIO = 0.75

type Props = {
  state: SessionState
  columns: number
}

export function StatusLine({ state, columns }: Props) {
  const theme = useTheme()
  const { ready, tokens, contextLimit, lastDurationMs } = state
  if (!ready) return null

  const ratio = contextLimit > 0 ? tokens / contextLimit : 0
  const contextColor = ratio >= WARN_RATIO ? theme.warning : theme.inactive

  return (
    <Box width={columns} paddingX={1}>
      <Text color={theme.inactive}>{ready.model}</Text>
      {ready.planMode && <Text color={theme.warning}> · 只读</Text>}
      {contextLimit > 0 && (
        <Text color={contextColor}>
          {' · '}
          {formatTokens(tokens)}/{formatTokens(contextLimit)}
          {' ctx'}
        </Text>
      )}
      {lastDurationMs !== null && (
        <Text color={theme.inactive}> · 上轮 {formatDuration(lastDurationMs)}</Text>
      )}
    </Box>
  )
}

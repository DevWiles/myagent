/**
 * 底部状态栏。
 *
 * 只放那些「随时想知道、但不值得打断阅读」的东西：模型、上下文占用、上轮耗时。
 * 上下文占用尤其重要——它快满的时候会触发压缩，早期细节就丢了，
 * 用户有权提前看到这件事正在逼近，而不是等压缩发生了才被通知。
 *
 * **高度恒定 1 行**，而且 `ready` 还没到的时候也照样占那一行（画个空盒子）。
 * 以前这里是 `if (!ready) return null`，而 App 那边无条件给它记了 1 行，
 * 于是后端慢一点、`primeReady` 3 秒超时的时候，整个底部就会往下错一行。
 * 现在输入框的行号在 ready 到达之前就已经是常数了。
 */

import { Box, Text } from 'ink'
import { STATUS_ROWS } from '../layout.ts'
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

  const ratio = contextLimit > 0 ? tokens / contextLimit : 0
  const contextColor = ratio >= WARN_RATIO ? theme.warning : theme.inactive

  // height + overflow:hidden 是硬保证：窄屏下内容再长也只占一行，
  // 不会折行去挤别人（这正是 COLS=20 时把输入框挤变形的那个 bug）。
  return (
    <Box width={columns} height={STATUS_ROWS} flexShrink={0} overflow="hidden" paddingX={1}>
      {ready && (
        <>
          <Text color={theme.inactive} wrap="truncate">
            {ready.model}
          </Text>
          {ready.planMode && (
            <Text color={theme.warning} wrap="truncate">
              {' · 只读'}
            </Text>
          )}
          {contextLimit > 0 && (
            <Text color={contextColor} wrap="truncate">
              {' · '}
              {formatTokens(tokens)}/{formatTokens(contextLimit)}
              {' ctx'}
            </Text>
          )}
          {lastDurationMs !== null && (
            <Text color={theme.inactive} wrap="truncate">
              {' · 上轮 '}
              {formatDuration(lastDurationMs)}
            </Text>
          )}
        </>
      )}
    </Box>
  )
}

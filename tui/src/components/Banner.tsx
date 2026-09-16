/** 启动招牌。窄终端下自动退化成一行文字。 */

import { Box, Text } from 'ink'
import { LOGO_LINES, LOGO_MIN_WIDTH } from '../format.ts'
import type { ReadyInfo } from '../store.ts'
import { useTheme } from '../theme.ts'

type Props = {
  ready: ReadyInfo
  columns: number
}

export function Banner({ ready, columns }: Props) {
  const theme = useTheme()
  const showLogo = columns >= LOGO_MIN_WIDTH

  return (
    <Box flexDirection="column" marginBottom={1}>
      {showLogo ? (
        LOGO_LINES.map((line, i) => (
          <Text key={i} color={theme.brand}>
            {line}
          </Text>
        ))
      ) : (
        <Text color={theme.brand} bold>
          myagent
        </Text>
      )}

      <Box marginTop={showLogo ? 1 : 0}>
        <Text color={theme.subtle}>
          v{ready.version} · {ready.model} · {ready.tools.length} 个工具
          {ready.planMode ? ' · 只读模式' : ''}
        </Text>
      </Box>
      <Text color={theme.inactive}>{ready.workspace}</Text>
      <Box marginTop={1}>
        <Text color={theme.inactive}>
          Enter 发送 · Esc 中断 · Ctrl-O 展开过程 · /help 查看命令
        </Text>
      </Box>
    </Box>
  )
}

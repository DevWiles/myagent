/**
 * 后端断开时的那一行。
 *
 * 单独成组件只为一件事：**高度恒定 1 行**。以前它是 App 里一句裸
 * `<Text>✗ {reason}</Text>`，而关闭原因可以很长（带异常消息），
 * 窄屏下折成两三行，把底部固定区顶破。
 */

import { Box, Text } from 'ink'
import { CLOSED_ROWS } from '../layout.ts'
import { useTheme } from '../theme.ts'

type Props = {
  reason: string
  columns: number
}

export function ClosedLine({ reason, columns }: Props) {
  const theme = useTheme()
  return (
    <Box width={columns} height={CLOSED_ROWS} flexShrink={0} overflow="hidden">
      <Text color={theme.error} wrap="truncate">
        ✗ {reason}
      </Text>
    </Box>
  )
}

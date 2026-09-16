/**
 * 启动招牌。
 *
 * 两个布局约束（对齐 Claude Code 的手感）：
 * - **宽度撑满屏幕**：`width={columns}`，终端拉宽拉窄边框跟着走
 * - **高度固定**：无论宽窄都是同样的行数，缩放时不会忽高忽低把下面的内容顶来顶去
 *
 * 宽度不够画大字招牌时退化成一行文字，但**高度保持不变**（用空行补齐），
 * 这样从宽屏拖窄的过程里 transcript 不会跳。
 */

import { Box, Text } from 'ink'
import { LOGO_LINES, LOGO_MIN_WIDTH } from '../format.ts'
import type { ReadyInfo } from '../store.ts'
import { useTheme } from '../theme.ts'

/**
 * 招牌固定占的行数：logo 6 + 信息 1 + 路径 1 + 提示 1 + 下横线 1 = 10。
 *
 * 必须是常数：App 拿它算视口高度，缩放时招牌忽高忽低会把下面的内容顶来顶去。
 */
export const BANNER_HEIGHT = 10

type Props = {
  ready: ReadyInfo
  columns: number
}

export function Banner({ ready, columns }: Props) {
  const theme = useTheme()
  const showLogo = columns >= LOGO_MIN_WIDTH

  return (
    // 简约风：只有一根下横线收口，和输入框呼应，不用方框把界面切碎
    <Box
      flexDirection="column"
      width={columns}
      height={BANNER_HEIGHT}
      borderStyle="single"
      borderColor={theme.inactive}
      borderTop={false}
      borderLeft={false}
      borderRight={false}
      flexShrink={0}
    >
      {showLogo ? (
        LOGO_LINES.map((line, i) => (
          <Text key={i} color={theme.brand} wrap="truncate">
            {line}
          </Text>
        ))
      ) : (
        <>
          <Text color={theme.brand} bold>
            myagent
          </Text>
          {/* 窄屏也占满 logo 的 6 行，保证招牌高度恒定、缩放时下方内容不跳 */}
          {[1, 2, 3, 4, 5].map(i => (
            <Text key={i}> </Text>
          ))}
        </>
      )}

      <Text color={theme.subtle} wrap="truncate">
        v{ready.version} · {ready.model} · {ready.tools.length} 个工具
        {ready.planMode ? ' · 只读模式' : ''}
      </Text>
      {/* 路径可能很长，截断而不是折行——折行会把招牌撑高，破坏固定高度 */}
      <Text color={theme.inactive} wrap="truncate-start">
        {ready.workspace}
      </Text>
      <Text color={theme.inactive} wrap="truncate">
        {columns >= 64
          ? 'Enter 发送 · 点工具行展开 · 拖拽选中即复制 · /help 查看命令'
          : 'Enter 发送 · /help'}
      </Text>
    </Box>
  )
}

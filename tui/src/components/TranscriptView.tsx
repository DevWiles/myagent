/**
 * 对话记录的滚动视口。
 *
 * 备用屏幕里全部内容都是活的 React 元素，滚动我们自己做：外层固定视口高度 +
 * overflow:hidden，内层整段用负 marginTop 往上平移。
 *
 * ## 高度从哪来
 *
 * 视口高度**不再由父组件算给我们**，而是自己量。以前是 App 那边
 * 「rows 减去底部各块的行数」手算出一个 `height` 传进来，那串算术有六七个
 * 漂移源（状态栏没 ready 时不渲染、spinner 窄屏折行、确认框按钮折行……），
 * 一旦算错，`height` 和真实盒子高度就对不上，滚动和提示行全跟着错位。
 *
 * 现在外层盒子拿 `flexGrow={1} flexBasis={0}`，由 Yoga 把「剩下多少」直接分给它，
 * 我们用 `useBoxMetrics` 读回来。算术只剩一个 `seedHeight` 用于首帧，算错也不影响。
 *
 * ## 为什么负 marginTop 要挂在**另一个**盒子上
 *
 * `useBoxMetrics` 对 width/height/left/top 四个字段都做等值判断。要是把
 * `marginTop={-hiddenAbove}` 直接写在被量的那个盒子上，每滚一格它的 `top` 就变，
 * 就会多触发一次无谓的 setState→重渲染。所以中间插一层 shifter 专门吃 margin，
 * 被量的节点 `top` 恒为 0，只有内容真的变了 `height` 才动。
 */

import { Box, Text, measureElement, useBoxMetrics } from 'ink'
import type { DOMElement } from 'ink'
import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import type { Entry } from '../store.ts'
import { useTheme } from '../theme.ts'
import { EntryView } from './EntryView.tsx'

export type TranscriptHandle = {
  scrollBy(lines: number): void
  scrollToTop(): void
  scrollToBottom(): void
  /** 视口在屏幕上的绝对起始行。接管鼠标后做命中测试要用。 */
  viewportTop(): number
}

type Props = {
  entries: readonly Entry[]
  expanded: ReadonlySet<string>
  columns: number
  header?: React.ReactNode
  handleRef?: Ref<TranscriptHandle>
  /**
   * 首帧用的高度估值。`useBoxMetrics` 量到真值之前顶一下，之后就不用了。
   * 算错只会让第一帧的滚动位置差一点，不会让布局漂。
   */
  seedHeight: number
  /**
   * 变一次就回到贴底跟随。resize 后折行结果全变了，旧锚点对应不到原来的位置。
   */
  repaintToken?: number
}

export function TranscriptView({
  entries,
  expanded,
  columns,
  header,
  handleRef,
  seedHeight,
  repaintToken,
}: Props) {
  const theme = useTheme()
  const viewportRef = useRef<DOMElement>(null)
  const contentRef = useRef<DOMElement>(null)

  /**
   * 滚动锚点。null = 贴底跟随（新内容来了自动滚到最新）；
   * 数字 = 用户主动滚上去了，值是「视口顶对应内容的第几行」。
   *
   * 为什么不用「距底部多少行」来存：流式输出时内容每秒长高几十行，
   * 同一个「距底部」值对应的内容位置一直在漂，视野就被不断拽回底部 ——
   * 表现就是「流式输出时没法往上滚，只能等它输完」。
   */
  const [anchor, setAnchor] = useState<number | null>(null)

  const viewport = useBoxMetrics(viewportRef)
  const content = useBoxMetrics(contentRef)

  const height = viewport.hasMeasured ? Math.max(1, viewport.height) : Math.max(1, seedHeight)
  const contentHeight = content.hasMeasured ? content.height : 0

  // 尺寸变了：回到贴底跟随。
  useEffect(() => {
    setAnchor(null)
  }, [repaintToken, columns, height])

  const maxHidden = Math.max(0, contentHeight - height)
  const hiddenAbove = anchor === null ? maxHidden : Math.max(0, Math.min(anchor, maxHidden))

  useImperativeHandle(
    handleRef,
    () => ({
      scrollBy(lines) {
        // lines > 0 是往上看。从当前实际位置出发，避免第一次上滚时跳一下。
        setAnchor(cur => {
          const from = cur === null ? maxHidden : Math.min(cur, maxHidden)
          const next = from - lines
          // 滚回底部就恢复跟随，否则新消息不会自动进入视野
          return next >= maxHidden ? null : Math.max(0, next)
        })
      },
      scrollToTop() {
        setAnchor(0)
      },
      scrollToBottom() {
        setAnchor(null)
      },
      viewportTop() {
        // useBoxMetrics 给的是**相对父节点**的 top，命中测试要的是绝对屏幕行，
        // 所以这里用 measureElement——它会一路往上累加每个祖先的 computedTop。
        const node = viewportRef.current
        return node ? measureElement(node).y : 0
      },
    }),
    [maxHidden],
  )

  return (
    <Box
      ref={viewportRef}
      flexDirection="column"
      flexGrow={1}
      flexShrink={0}
      flexBasis={0}
      minHeight={0}
      overflow="hidden"
    >
      {/* shifter：只吃 margin，不被测量。见文件头说明。 */}
      <Box flexDirection="column" flexShrink={0} marginTop={-hiddenAbove}>
        <Box flexDirection="column" flexShrink={0} ref={contentRef}>
          {header}
          {entries.map(entry => (
            <Box key={entry.key} flexShrink={0}>
              <EntryView entry={entry} expanded={expanded.has(entry.key)} columns={columns} />
            </Box>
          ))}
        </Box>
      </Box>

      {anchor !== null && hiddenAbove < maxHidden && (
        <Box position="absolute" bottom={0} left={0} right={0} justifyContent="flex-end">
          <Text color={theme.warning} wrap="truncate"> ↓ 还有新内容 · End 回到底部 </Text>
        </Box>
      )}
    </Box>
  )
}

/**
 * 对话记录的滚动视口 + 鼠标命中测试。
 *
 * 备用屏幕里全部内容都是活的 React 元素，滚动我们自己做：外层固定视口高度 +
 * overflow:hidden，内层整段用负 marginTop 往上平移。内层真实高度、以及每个
 * 工具组占据哪几行，都用 measureElement 在每次 commit 后量出来——文本折行后
 * 的行数只有排版引擎知道，自己数会数错。
 *
 */

import { Box, Text, measureElement } from 'ink'
import type { DOMElement } from 'ink'
import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import type { Entry } from '../store.ts'
import { useTheme } from '../theme.ts'
import { EntryView } from './EntryView.tsx'

export type TranscriptHandle = {
  scrollBy(lines: number): void
  scrollToTop(): void
  scrollToBottom(): void
}

type Props = {
  entries: readonly Entry[]
  expanded: ReadonlySet<string>
  height: number
  columns: number
  header?: React.ReactNode
  handleRef?: Ref<TranscriptHandle>
  /**
   * 变一次就重新测量一遍布局。resize 后 Yoga 的折行结果全变了，
   * 必须重测，否则 hitTest 用的还是旧行坐标（点击会点偏）。
   * 用 prop 而不是给本组件换 key：换 key 会让父组件持有的 ref 失效。
   */
  repaintToken?: number
}

export function TranscriptView({
  entries,
  expanded,
  height,
  columns,
  header,
  handleRef,
  repaintToken,
}: Props) {
  const theme = useTheme()
  const contentRef = useRef<DOMElement>(null)
  const headerRef = useRef<DOMElement>(null)
  /** key → 该工具组的 wrapper 元素，用于 measureElement。 */
  const rowRefs = useRef(new Map<string, DOMElement>())

  /**
   * 滚动锚点。null = 贴底跟随（新内容来了自动滚到最新）；
   * 数字 = 用户主动滚上去了，值是「视口顶对应内容的第几行」。
   *
   * 为什么不用「距底部多少行」来存：流式输出时内容每秒长高几十行，
   * 同一个「距底部」值对应的内容位置一直在漂，视野就被不断拽回底部 ——
   * 表现就是「流式输出时没法往上滚，只能等它输完」。
   */
  const [anchor, setAnchor] = useState<number | null>(null)
  const [contentHeight, setContentHeight] = useState(0)

  // 尺寸变了：回到贴底跟随。折行全变了，旧锚点对应不到原来的位置。
  useEffect(() => {
    setAnchor(null)
  }, [repaintToken, columns, height])

  // 每次 commit 后：量内容总高、重建 hitMap、重建选区取字用的文本行。
  //
  // 依赖数组留空是刻意的——折行随宽度变，内容没变宽度变了高度也变，每次都量。
  // 但因此**任何 setState 都必须先判等再调**，否则就是
  // 「渲染 → 测量 → setState → 渲染」的自激循环，流式输出时会撞穿
  // React 的嵌套更新上限（Maximum update depth exceeded）。
  useEffect(() => {
    if (contentRef.current) {
      const measured = measureElement(contentRef.current).height
      if (measured !== contentHeight) setContentHeight(measured)
    }

  })

  const maxHidden = Math.max(0, contentHeight - height)
  const hiddenAbove = anchor === null ? maxHidden : Math.max(0, Math.min(anchor, maxHidden))
  // 内容不够高时，剩下的行要用空格铺满：空 Box 不产生字符，Ink 就什么都不写，
  // 那些格子上一帧的字符会留着——这是缩放后出现残影的根源之一。
  const blankRows = Math.max(0, height - contentHeight)

  useImperativeHandle(handleRef, () => ({
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
  }), [maxHidden])

  return (
    <Box flexDirection="column" height={height} overflow="hidden">
      <Box flexDirection="column" flexShrink={0} marginTop={-hiddenAbove} ref={contentRef}>
        {header && (
          <Box flexShrink={0} ref={headerRef}>
            {header}
          </Box>
        )}
        {entries.map(entry => (
          <Box
            key={entry.key}
            flexShrink={0}
            ref={el => {
              if (el) rowRefs.current.set(entry.key, el)
              else rowRefs.current.delete(entry.key)
            }}
          >
            <EntryView entry={entry} expanded={expanded.has(entry.key)} columns={columns} />
          </Box>
        ))}
      </Box>

      {blankRows > 0 &&
        Array.from({ length: blankRows }, (_, i) => (
          <Text key={`blank-${i}`}>{' '.repeat(Math.max(1, columns))}</Text>
        ))}
      {anchor !== null && hiddenAbove < maxHidden && (
        <Box position="absolute" marginTop={height - 1} width={columns} justifyContent="flex-end">
          <Text color={theme.warning}> ↓ 还有新内容 · End 回到底部 </Text>
        </Box>
      )}
    </Box>
  )
}

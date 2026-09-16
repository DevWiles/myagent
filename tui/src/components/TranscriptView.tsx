/**
 * 对话记录的滚动视口 + 鼠标命中测试。
 *
 * 备用屏幕里全部内容都是活的 React 元素，滚动我们自己做：外层固定视口高度 +
 * overflow:hidden，内层整段用负 marginTop 往上平移。内层真实高度、以及每个
 * 工具组占据哪几行，都用 measureElement 在每次 commit 后量出来——文本折行后
 * 的行数只有排版引擎知道，自己数会数错。
 *
 * 命中测试（hitTest）是「鼠标点击展开」的关键：把点击的屏幕行号映射回是哪个
 * 工具组。每个工具组包一层带 ref 的 Box，量出它在视口里的行区间存进 hitMap，
 * 点击时二分查一下就知道点中了谁。只有工具组登记进 hitMap——只有它能展开。
 */

import { Box, Text, measureElement } from 'ink'
import type { DOMElement } from 'ink'
import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { entryToLines, rowRange, type Point, type SelectionState } from '../selection.ts'
import type { Entry } from '../store.ts'
import { useTheme } from '../theme.ts'
import { EntryView } from './EntryView.tsx'

const ANSI = /\x1b\[[0-9;]*m/g

export type TranscriptHandle = {
  scrollBy(lines: number): void
  scrollToTop(): void
  scrollToBottom(): void
  /** 屏幕行号（0 起，相对视口顶）→ 该行所在的工具组 key，没有则 null。 */
  hitTest(screenRow: number): string | null
  /** 屏幕坐标 →「内容坐标」。选区一律用内容坐标存，滚动后才不会错位。 */
  pointAt(screenCol: number, screenRow: number): Point
  /** 全部内容的纯文本，按内容行号索引。取字用。 */
  getLines(): string[]
}

type Props = {
  entries: readonly Entry[]
  expanded: ReadonlySet<string>
  height: number
  columns: number
  header?: React.ReactNode
  /** 当前选区（内容坐标）。用来画反色高亮。 */
  selection?: SelectionState
  handleRef?: Ref<TranscriptHandle>
  /**
   * 变一次就重新测量一遍布局。resize 后 Yoga 的折行结果全变了，
   * 必须重测，否则 hitTest 用的还是旧行坐标（点击会点偏）。
   * 用 prop 而不是给本组件换 key：换 key 会让父组件持有的 ref 失效。
   */
  repaintToken?: number
}

/** 一个工具组在视口里占据的行区间（含 y0，不含 y1）。 */
type HitSpan = { key: string; y0: number; y1: number }

export function TranscriptView({
  entries,
  expanded,
  height,
  columns,
  header,
  selection,
  handleRef,
  repaintToken,
}: Props) {
  // 选区取字的文本来源。
  //
  // 试过两条死路，都记在这儿免得再走：
  // 1. render 里调 renderToString —— React 报「渲染中触发嵌套渲染」直接退出。
  // 2. effect 里调 renderToString —— 它会另建一套 Yoga 布局树，和正在运行的
  //    Ink 实例共用同一个 WASM 实例、互相踩内存，进程崩在
  //    「Out of bounds call_indirect」。
  //
  // 所以改为**从数据重建文本**：每个 entry 自己知道它渲染成哪几行（entryLines），
  // 配合 measureElement 量到的真实行高对齐。代价是复制出来的文本没有 Markdown
  // 的装饰（比如列表的 • 前缀），拿到的是更接近原文的纯文本 —— 对「复制去粘贴」
  // 这个用途反而更合适。
  const linesRef = useRef<string[]>([])

  const theme = useTheme()
  const contentRef = useRef<DOMElement>(null)
  const headerRef = useRef<DOMElement>(null)
  /** key → 该工具组的 wrapper 元素，用于 measureElement。 */
  const rowRefs = useRef(new Map<string, DOMElement>())
  const hitMap = useRef<HitSpan[]>([])

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
    const spans: HitSpan[] = []
    const headerH = headerRef.current ? measureElement(headerRef.current).height : 0
    let y = headerH
    for (const entry of entries) {
      const el = rowRefs.current.get(entry.key)
      const h = el ? measureElement(el).height : 0
      if (entry.kind === 'tools') {
        spans.push({ key: entry.key, y0: y, y1: y + h })
      }
      y += h
    }
    hitMap.current = spans

    // 选区取字用的文本行。关键是**按量到的真实行高对齐**：一条消息在屏幕上
    // 可能因折行占 5 行而原文只有 2 行，直接拼接会让行号和屏幕错位、选出来的
    // 字对不上。不足补空串、超出截断，保证 linesRef[i] 对应内容行 i。
    const text: string[] = []
    for (let i = 0; i < headerH; i++) text.push('')
    for (const entry of entries) {
      const el = rowRefs.current.get(entry.key)
      const rowsUsed = el ? measureElement(el).height : 0
      const own = entryToLines(entry as never)
      // EntryView 给每条消息加了 marginTop=1（消息之间的空行），它算在 rowsUsed
      // 里但不属于正文。漏掉这一行，后面所有行号都会整体偏移 1，选出来的字
      // 就串到上一行去了。
      const LEAD_BLANK = 1
      for (let i = 0; i < rowsUsed; i++) {
        text.push(i < LEAD_BLANK ? '' : (own[i - LEAD_BLANK] ?? ''))
      }
    }
    linesRef.current = text
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
    hitTest(screenRow) {
      // 屏幕行 → 内容坐标：加回被平移隐藏的高度
      const contentY = screenRow + hiddenAbove
      for (const span of hitMap.current) {
        if (contentY >= span.y0 && contentY < span.y1) return span.key
      }
      return null
    },
    pointAt(screenCol, screenRow) {
      return { row: screenRow + hiddenAbove, col: Math.max(0, screenCol) }
    },
    getLines: () => linesRef.current,
  }), [maxHidden, hiddenAbove])

  // 选区高亮：逐行算出「这一行的哪一段被选中」，在对应屏幕行叠一条反色文本。
  // 只处理视口内的行——看不见的没必要画。
  const selectionOverlay = (() => {
    if (!selection?.anchor || !selection.focus) return null
    const lines = linesRef.current
    if (lines.length === 0) return null

    const overlays: React.ReactNode[] = []
    for (let screenRow = 0; screenRow < height; screenRow++) {
      const contentRow = screenRow + hiddenAbove
      const range = rowRange(selection, contentRow)
      if (!range) continue
      const line = lines[contentRow]
      if (line === undefined) continue

      const chars = [...line]
      const to = range.to === null ? chars.length : Math.min(range.to, chars.length)
      if (to <= range.from) continue
      // 整行都是空白就别画了，否则选到空行会出现一条突兀的反色条
      const piece = chars.slice(range.from, to).join('')
      if (piece.trim() === '') continue

      overlays.push(
        <Box key={`sel-${screenRow}`} position="absolute" marginTop={screenRow}>
          <Text>{' '.repeat(range.from)}</Text>
          <Text inverse>{piece}</Text>
        </Box>,
      )
    }
    return overlays.length > 0 ? overlays : null
  })()

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

      {/* 选区高亮：绝对定位的覆盖层，把选中的那几段用反色重画一遍。
          社区 ink 没有格子级屏幕缓冲（那份 Claude Code 源码能 setCellStyleId
          逐格换背景，我们做不到），所以走「覆盖层重绘」这条路：
          按行取出选中的列区间，在对应屏幕行上叠一条反色文本。 */}
      {selectionOverlay}
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

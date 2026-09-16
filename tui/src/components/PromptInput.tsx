/**
 * 输入框。
 *
 * 自己实现而不是用现成的 text-input 组件，因为要的东西它们都没有：
 * 输入历史、Ctrl-W 删词、正在跑的时候变色。
 *
 * 光标用「反色那一格」来画，而不是画一个 `|`：`|` 会占掉一列，
 * 让光标后面的文字整体右移一格，看着就像少了个字符。
 *
 * 高度是约定死的（`INPUT_BOXED_ROWS` / `INPUT_BARE_ROWS`，和 `layout.ts` 共用），
 * 所以无论多窄都不会折行去挤别人——输入框钉在最后一行靠的就是这个。
 */

import { Box, Text, useInput } from 'ink'
import { useCallback, useRef, useState } from 'react'
import { INPUT_BARE_ROWS, INPUT_BOXED_ROWS } from '../layout.ts'
import { useTheme } from '../theme.ts'

type Props = {
  busy: boolean
  isActive: boolean
  columns: number
  placeholder: string
  onSubmit: (text: string) => void
  /** true = 去掉上下两根横线,只占 1 行。终端太矮时由 planBottomStack 决定。 */
  bare?: boolean
  /** 空输入时按 Ctrl-D 要退出。判空要读 text,所以这个判断只能放在这里。 */
  onRequestExit?: () => void
}

export function PromptInput({
  busy,
  isActive,
  columns,
  placeholder,
  onSubmit,
  bare = false,
  onRequestExit,
}: Props) {
  const theme = useTheme()

  const [text, setText] = useState('')
  const [cursor, setCursor] = useState(0)
  const history = useRef<string[]>([])
  /** 在历史里翻到第几条。-1 表示「当前正在编辑的这条」。 */
  const [historyIndex, setHistoryIndex] = useState(-1)
  const draft = useRef('')

  const replace = useCallback((next: string, at = next.length) => {
    setText(next)
    setCursor(Math.max(0, Math.min(at, next.length)))
  }, [])

  useInput(
    (input, key) => {
      // --- 提交 ---
      if (key.return) {
        const value = text.trim()
        if (!value) return
        if (history.current[history.current.length - 1] !== value) {
          history.current.push(value)
        }
        setHistoryIndex(-1)
        draft.current = ''
        replace('')
        onSubmit(value)
        return
      }

      // --- Ctrl-D：空输入时退出（readline 的老规矩），有内容时忽略 ---
      if (key.ctrl && input === 'd') {
        if (text.length === 0) onRequestExit?.()
        return
      }

      // --- 光标移动 ---
      if (key.leftArrow || (key.ctrl && input === 'b')) return setCursor(c => Math.max(0, c - 1))
      if (key.rightArrow || (key.ctrl && input === 'f')) {
        return setCursor(c => Math.min(text.length, c + 1))
      }
      if (key.ctrl && input === 'a') return setCursor(0)
      if (key.ctrl && input === 'e') return setCursor(text.length)

      // --- 删除 ---
      if (key.backspace || key.delete) {
        if (cursor === 0) return
        return replace(text.slice(0, cursor - 1) + text.slice(cursor), cursor - 1)
      }
      if (key.ctrl && input === 'u') return replace(text.slice(cursor), 0)
      if (key.ctrl && input === 'k') return replace(text.slice(0, cursor), cursor)
      if (key.ctrl && input === 'w') {
        // 往左吃掉空白，再吃掉一个词
        let at = cursor
        while (at > 0 && text[at - 1] === ' ') at--
        while (at > 0 && text[at - 1] !== ' ') at--
        return replace(text.slice(0, at) + text.slice(cursor), at)
      }

      // --- 历史（Ctrl-P/N） ---
      //
      // 刻意**不绑** ↑/↓：那两个键 App 已经拿去滚 transcript 了，而 Ink 是把输入
      // **广播**给所有 useInput 监听者的，handler 里 return 拦不住别人。实测按一下 ↑
      // 会同时「把历史召回输入框」和「把对话滚走」两件事都干。
      // 帮助文本和 README 本来写的就是 Ctrl-P/N，所以这里以文档为准。
      // 这也是开 Alternate Scroll 的前置条件——滚轮会被终端翻译成方向键。
      if (key.ctrl && input === 'p') {
        if (history.current.length === 0) return
        if (historyIndex === -1) draft.current = text
        const next = Math.min(historyIndex + 1, history.current.length - 1)
        setHistoryIndex(next)
        return replace(history.current[history.current.length - 1 - next] ?? '')
      }
      if (key.ctrl && input === 'n') {
        if (historyIndex === -1) return
        const next = historyIndex - 1
        setHistoryIndex(next)
        return replace(next === -1 ? draft.current : (history.current[history.current.length - 1 - next] ?? ''))
      }

      // --- 普通字符（粘贴也走这里，会是一整块） ---
      if (input && !key.ctrl && !key.meta) {
        const clean = input.replace(/[\r\n]+/g, ' ')
        return replace(text.slice(0, cursor) + clean + text.slice(cursor), cursor + clean.length)
      }
    },
    { isActive },
  )

  // 这里只用反色块画逻辑光标，真实终端光标交给 Ink 默认处理。
  // 代价：中文输入法的候选框可能落在输入框下方一两行，而不是紧贴输入位置。
  //
  // 备用屏 + 输入框钉底之后，这个框已经在一个**确定的绝对行**上了，所以
  // `useCursor` 变得可行，能把真实光标摆准、顺带修掉候选框飘的问题。
  // 但那要连着「点击定位光标」一起做（都依赖同一套列↔字符索引换算），
  // 所以留到接管鼠标那一期，这一期不碰。

  const borderColor = busy ? theme.promptBorderBusy : theme.promptBorder
  const showPlaceholder = text.length === 0

  // 内容行显式定高 1 行 + overflow:hidden。placeholder 也必须 truncate——
  // 少了它，窄屏下提示文字折行会让整个框从 3 行变 4 行，把底部固定区撑破。
  const row = (
    <Box width={columns} height={1} flexShrink={0} overflow="hidden">
      <Text color={busy ? theme.promptBorderBusy : theme.brand}>{'› '}</Text>
      {showPlaceholder ? (
        <>
          <Cursor char=" " active={isActive} />
          <Text color={theme.inactive} wrap="truncate">
            {placeholder}
          </Text>
        </>
      ) : (
        <Text color={theme.text} wrap="truncate-start">
          {text.slice(0, cursor)}
          <Cursor char={text[cursor] ?? ' '} active={isActive} />
          {text.slice(cursor + 1)}
        </Text>
      )}
    </Box>
  )

  if (bare) {
    return (
      <Box width={columns} height={INPUT_BARE_ROWS} flexShrink={0} overflow="hidden">
        {row}
      </Box>
    )
  }

  // 简约风：只有上下两根横线，没有左右竖边。
  // 用 borderStyle + borderLeft/Right={false} 而不是自己打一串 '─'，
  // 是为了让宽度由 Yoga 算——自己打字符在 resize 时容易差一格。
  return (
    <Box
      flexDirection="column"
      width={columns}
      height={INPUT_BOXED_ROWS}
      borderStyle="single"
      borderColor={borderColor}
      borderTop
      borderBottom
      borderLeft={false}
      borderRight={false}
      flexShrink={0}
    >
      {row}
    </Box>
  )
}

/** 光标：把所在那一格反色。不活跃时（比如确认框抢走了焦点）不画，避免有两个光标。 */
function Cursor({ char, active }: { char: string; active: boolean }) {
  return active ? <Text inverse>{char}</Text> : <Text>{char}</Text>
}

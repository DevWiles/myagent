/**
 * 输入框。
 *
 * 自己实现而不是用现成的 text-input 组件，因为要的东西它们都没有：
 * 输入历史、Ctrl-W 删词、正在跑的时候变色、以及把真实光标摆到正确的格子上
 * （`useCursor`）——这最后一条决定了中文输入法的候选框会不会飘到别处去。
 *
 * 光标用「反色那一格」来画，而不是画一个 `|`：`|` 会占掉一列，
 * 让光标后面的文字整体右移一格，看着就像少了个字符。
 */

import { Box, Text, useInput } from 'ink'
import { useCallback, useRef, useState } from 'react'
import { useTheme } from '../theme.ts'

type Props = {
  busy: boolean
  isActive: boolean
  columns: number
  placeholder: string
  onSubmit: (text: string) => void
}

export function PromptInput({ busy, isActive, columns, placeholder, onSubmit }: Props) {
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

      // --- 历史（↑/↓ 或 Ctrl-P/N） ---
      if (key.upArrow || (key.ctrl && input === 'p')) {
        if (history.current.length === 0) return
        if (historyIndex === -1) draft.current = text
        const next = Math.min(historyIndex + 1, history.current.length - 1)
        setHistoryIndex(next)
        return replace(history.current[history.current.length - 1 - next] ?? '')
      }
      if (key.downArrow || (key.ctrl && input === 'n')) {
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

  // inline 模式下输入框在动态区、位置随内容浮动，没法给 useCursor 一个稳定的
  // 绝对坐标——硬定位反而会让真实光标乱跳。这里只用反色块显示逻辑光标（见下），
  // 真实终端光标交给 Ink 默认处理。代价：中文输入法候选框可能落在输入框下方
  // 一两行而不是紧贴输入位置，功能不受影响。

  const borderColor = busy ? theme.promptBorderBusy : theme.promptBorder
  const showPlaceholder = text.length === 0

  // 简约风：只有上下两根横线，没有左右竖边。
  // 用 borderStyle + borderLeft/Right={false} 而不是自己打一串 '─'，
  // 是为了让宽度由 Yoga 算——自己打字符在 resize 时容易差一格。
  return (
    <Box
      flexDirection="column"
      width={columns}
      borderStyle="single"
      borderColor={borderColor}
      borderLeft={false}
      borderRight={false}
      flexShrink={0}
    >
      <Box>
        <Text color={busy ? theme.promptBorderBusy : theme.brand}>{'› '}</Text>
        {showPlaceholder ? (
          <>
            <Cursor char=" " active={isActive} />
            <Text color={theme.inactive}>{placeholder}</Text>
          </>
        ) : (
          <Text color={theme.text} wrap="truncate-start">
            {text.slice(0, cursor)}
            <Cursor char={text[cursor] ?? ' '} active={isActive} />
            {text.slice(cursor + 1)}
          </Text>
        )}
      </Box>
    </Box>
  )
}

/** 光标：把所在那一格反色。不活跃时（比如确认框抢走了焦点）不画，避免有两个光标。 */
function Cursor({ char, active }: { char: string; active: boolean }) {
  return active ? <Text inverse>{char}</Text> : <Text>{char}</Text>
}

/**
 * 主题层：界面里不允许出现字面颜色，只能引用这里的语义 token。
 *
 * 好处不是「好看」，是**可换**：终端千奇百怪，浅色背景、只支持 16 色、
 * 甚至完全不支持颜色的都有。颜色一旦散落在各个组件里，这些情况就没法统一应对。
 *
 * token 命名刻意带用途（`toolName` 而不是 `cyan`），因为颜色的含义比色值重要得多——
 * 改配色时你要回答的是「工具名该用什么颜色」，而不是「这个青色是干嘛的」。
 */

import { createContext, useContext } from 'react'

export type Theme = {
  /** 正文 */
  text: string
  /** 次要信息：耗时、行数、提示 */
  subtle: string
  /** 更淡一档：分隔线、占位符 */
  inactive: string

  /** 品牌色，用在招牌和 agent 的身份标识上 */
  brand: string
  /** 用户输入的回显 */
  user: string
  /** 模型在调用工具前说的话（ReAct 的 Thought） */
  thought: string

  success: string
  error: string
  warning: string

  /** 工具名 */
  toolName: string
  /** 工具参数 */
  toolArgs: string

  /** 输入框边框：空闲 / 正在跑 */
  promptBorder: string
  promptBorderBusy: string
  /** 需要确认时的边框 */
  approvalBorder: string
  /** 转圈动画 */
  spinner: string

  /** Markdown：行内代码 */
  inlineCode: string
  /** Markdown：代码块正文 */
  codeBlock: string
  /** Markdown：标题 */
  heading: string
}

/** 深色背景（绝大多数终端的默认）。 */
const dark: Theme = {
  text: '#e6e6e6',
  subtle: '#8a8a8a',
  inactive: '#5c5c5c',
  brand: '#d77757',
  user: '#7f9fd8',
  thought: '#9b82c8',
  success: '#5cb85c',
  error: '#e06c75',
  warning: '#d9a441',
  toolName: '#56b6c2',
  toolArgs: '#8a8a8a',
  promptBorder: '#5c5c5c',
  promptBorderBusy: '#d77757',
  approvalBorder: '#d9a441',
  spinner: '#d77757',
  inlineCode: '#e5c07b',
  codeBlock: '#98c379',
  heading: '#61afef',
}

/** 浅色背景。不是把深色调亮，是各自独立配的——亮底上灰色要更深才看得清。 */
const light: Theme = {
  text: '#1a1a1a',
  subtle: '#666666',
  inactive: '#9a9a9a',
  brand: '#c2410c',
  user: '#2563eb',
  thought: '#7c3aed',
  success: '#15803d',
  error: '#b91c1c',
  warning: '#a16207',
  toolName: '#0e7490',
  toolArgs: '#666666',
  promptBorder: '#9a9a9a',
  promptBorderBusy: '#c2410c',
  approvalBorder: '#a16207',
  spinner: '#c2410c',
  inlineCode: '#92400e',
  codeBlock: '#166534',
  heading: '#1d4ed8',
}

/**
 * 只用 16 色 ANSI。给不支持真彩色的终端——注意这是一套独立映射，
 * 不是把 RGB 自动量化过去：自动量化经常把两个语义不同的颜色压成同一个。
 */
const ansi: Theme = {
  text: 'white',
  subtle: 'gray',
  inactive: 'gray',
  brand: 'yellow',
  user: 'blue',
  thought: 'magenta',
  success: 'green',
  error: 'red',
  warning: 'yellow',
  toolName: 'cyan',
  toolArgs: 'gray',
  promptBorder: 'gray',
  promptBorderBusy: 'yellow',
  approvalBorder: 'yellow',
  spinner: 'yellow',
  inlineCode: 'yellow',
  codeBlock: 'green',
  heading: 'blueBright',
}

export const THEMES = { dark, light, ansi } as const
export type ThemeName = keyof typeof THEMES

/**
 * 挑一套主题。优先级：显式指定 > 环境变量 > 终端能力推断。
 *
 * COLORFGBG 是 iTerm2/rxvt 一类终端导出的「前景;背景」色号，背景 >= 8 说明是亮底。
 * 探测不到就按深色走——深色主题在亮底上尚可辨认，反过来基本没法看。
 */
export function pickTheme(explicit?: string): ThemeName {
  if (explicit && explicit in THEMES) return explicit as ThemeName
  const fromEnv = process.env['MYAGENT_THEME']
  if (fromEnv && fromEnv in THEMES) return fromEnv as ThemeName

  const depth = process.stdout.isTTY ? (process.stdout.getColorDepth?.() ?? 8) : 4
  if (depth < 8) return 'ansi'

  const colorfgbg = process.env['COLORFGBG']
  if (colorfgbg) {
    const background = Number(colorfgbg.split(';').pop())
    if (Number.isFinite(background) && background >= 8) return 'light'
  }
  return 'dark'
}

const ThemeContext = createContext<Theme>(dark)

export const ThemeProvider = ThemeContext.Provider

export function useTheme(): Theme {
  return useContext(ThemeContext)
}

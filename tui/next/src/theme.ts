/**
 * 主题层（OpenTUI 版）。
 *
 * token 名**刻意和 Ink 版 `tui/src/theme.ts` 保持一致**——迁移期两套界面并存，
 * 同名 token 意味着搬组件时只改标签（<Text color=> → <text fg=>），不用重新想颜色叫什么。
 *
 * 和 Ink 版的两处差别：
 * 1. `brand` 从橙色改成蓝色（Ink 版沿用 Claude Code 的橙，这版要蓝色 MYAGENT）
 * 2. 多了 `brandDim` / `border` / `panel` 三个 token，前者给招牌描边用，后两个给欢迎框用
 */

import { createContext, useContext } from "solid-js"

export type Theme = {
  /** 正文 */
  text: string
  /** 次要信息：耗时、行数、提示 */
  subtle: string
  /** 更淡一档：分隔线、占位符 */
  inactive: string

  /** 品牌色，用在招牌和 agent 的身份标识上 */
  brand: string
  /**
   * 品牌色的暗调。
   *
   * 招牌字形是 `███╗` 这种「实心块 + 描边线」的组合，两者用同一个颜色会糊成一片。
   * 实心块用 brand、描边线用 brandDim，等于白捡一层立体感——
   * 这是 opencode 用 tint() 混阴影的同一个思路，只是我们的字形自带描边，不用另算混色。
   */
  brandDim: string

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

  /** 面板边框（欢迎框、分栏竖线） */
  border: string
  /** 面板底色，比正文背景略亮一档 */
  panel: string

  /** Markdown：行内代码 */
  inlineCode: string
  /** Markdown：代码块正文 */
  codeBlock: string
  /** Markdown：标题 */
  heading: string
}

/** 深色背景（绝大多数终端的默认）。 */
export const dark: Theme = {
  text: "#e6e6e6",
  subtle: "#8a8a8a",
  inactive: "#5c5c5c",
  brand: "#5b9dfb",
  brandDim: "#2f5d9e",
  user: "#7f9fd8",
  thought: "#9b82c8",
  success: "#5cb85c",
  error: "#e06c75",
  warning: "#d9a441",
  toolName: "#56b6c2",
  toolArgs: "#8a8a8a",
  promptBorder: "#5c5c5c",
  promptBorderBusy: "#5b9dfb",
  approvalBorder: "#d9a441",
  spinner: "#5b9dfb",
  border: "#3d4757",
  panel: "#1b1f27",
  inlineCode: "#e5c07b",
  codeBlock: "#98c379",
  heading: "#5b9dfb",
}

/** 浅色背景。不是把深色调亮，是各自独立配的——亮底上灰色要更深才看得清。 */
export const light: Theme = {
  text: "#1a1a1a",
  subtle: "#666666",
  inactive: "#9a9a9a",
  brand: "#1d4ed8",
  brandDim: "#93b4ec",
  user: "#2563eb",
  thought: "#7c3aed",
  success: "#15803d",
  error: "#b91c1c",
  warning: "#a16207",
  toolName: "#0e7490",
  toolArgs: "#666666",
  promptBorder: "#9a9a9a",
  promptBorderBusy: "#1d4ed8",
  approvalBorder: "#a16207",
  spinner: "#1d4ed8",
  border: "#c4ccd8",
  panel: "#f2f4f8",
  inlineCode: "#92400e",
  codeBlock: "#166534",
  heading: "#1d4ed8",
}

const ThemeContext = createContext<Theme>(dark)

export const ThemeProvider = ThemeContext.Provider

/**
 * 组件里取颜色的唯一入口。
 *
 * 和 Ink 版同名同签名，是为了让搬过来的组件这一行不用改。
 */
export function useTheme(): Theme {
  return useContext(ThemeContext)
}

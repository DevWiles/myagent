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

import { SyntaxStyle, type ThemeTokenStyle } from "@opentui/core"
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

/**
 * 把主题翻译成 OpenTUI 的语法配色表。
 *
 * `<markdown>` 和 `<code>` 两个原生组件都要这个东西：它们内部用 TreeSitter 把文本
 * 切成带作用域（scope）的片段，再来这张表里查该用什么颜色。
 *
 * 两类 scope：
 * - `markup.*` 是 Markdown 自身的结构（标题、加粗、列表、行内代码…）
 * - 其余是代码块内部的语法高亮（关键字、字符串、注释…）
 *
 * 不用把 scope 列全——查不到的会退回 `default`。这里只覆盖中文技术对话里
 * 真正常见的那些，剩下的交给默认色，免得维护一张永远对不齐的大表。
 */
function syntaxRules(theme: Theme): ThemeTokenStyle[] {
  return [
    { scope: ["default"], style: { foreground: theme.text } },

    // --- Markdown 结构 ---
    //
    // 标题要把 1~6 级**逐个列出**，不能只写 markup.heading。
    // 渲染器内部有两条路径：行内 chunk 用 "markup.heading"，而块级文本走 TreeSitter
    // 的 markdown 语法，那边给出的是带级别的 "markup.heading.2"。
    // 查不到时的兜底只会砍到**第一段**（"markup"），不会退到 "markup.heading"，
    // 所以少写一个后缀，整行标题就掉回默认色。
    { scope: ["markup"], style: { foreground: theme.text } },
    { scope: ["markup.heading"], style: { foreground: theme.heading, bold: true } },
    { scope: ["markup.heading.1"], style: { foreground: theme.heading, bold: true } },
    { scope: ["markup.heading.2"], style: { foreground: theme.heading, bold: true } },
    { scope: ["markup.heading.3"], style: { foreground: theme.heading, bold: true } },
    { scope: ["markup.heading.4"], style: { foreground: theme.heading, bold: true } },
    { scope: ["markup.heading.5"], style: { foreground: theme.heading, bold: true } },
    { scope: ["markup.heading.6"], style: { foreground: theme.heading, bold: true } },
    { scope: ["markup.bold", "markup.strong"], style: { foreground: theme.text, bold: true } },
    { scope: ["markup.italic"], style: { foreground: theme.text, italic: true } },
    { scope: ["markup.list"], style: { foreground: theme.brand } },
    { scope: ["markup.quote"], style: { foreground: theme.subtle, italic: true } },
    // 行内代码和围栏代码块都走 markup.raw——渲染器没有区分两者的 scope，
    // 想让它们不同色得自己改 renderNode，暂时不值得
    { scope: ["markup.raw"], style: { foreground: theme.inlineCode } },
    { scope: ["markup.strikethrough"], style: { foreground: theme.inactive } },
    { scope: ["markup.link", "markup.link.label"], style: { foreground: theme.user } },
    { scope: ["markup.link.url"], style: { foreground: theme.subtle, underline: true } },

    // --- 代码块内部 ---
    { scope: ["comment", "comment.documentation"], style: { foreground: theme.inactive, italic: true } },
    { scope: ["string", "string.escape", "symbol"], style: { foreground: theme.codeBlock } },
    { scope: ["number", "boolean", "float", "constant"], style: { foreground: theme.warning } },
    { scope: ["keyword", "keyword.return", "keyword.conditional", "keyword.repeat"], style: { foreground: theme.thought } },
    { scope: ["keyword.import", "keyword.type", "keyword.modifier"], style: { foreground: theme.thought } },
    { scope: ["function", "function.call", "function.method", "constructor"], style: { foreground: theme.user } },
    { scope: ["type", "class", "module", "namespace"], style: { foreground: theme.toolName } },
    { scope: ["variable", "variable.parameter", "property", "field"], style: { foreground: theme.text } },
    { scope: ["operator", "punctuation", "punctuation.delimiter", "punctuation.bracket"], style: { foreground: theme.subtle } },
  ]
}

/**
 * 主题对应的语法配色，**按主题缓存**。
 *
 * `SyntaxStyle.fromTheme` 会在原生层分配一份资源，每次渲染都新建一个就是稳定泄漏；
 * 而主题在一次运行里基本不变，缓存住最划算。
 */
const syntaxCache = new WeakMap<Theme, SyntaxStyle>()

export function syntaxStyleFor(theme: Theme): SyntaxStyle {
  let style = syntaxCache.get(theme)
  if (!style) {
    style = SyntaxStyle.fromTheme(syntaxRules(theme))
    syntaxCache.set(theme, style)
  }
  return style
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

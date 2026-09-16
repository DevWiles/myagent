/**
 * 首屏欢迎框（Claude Code 那种双栏方框）。
 *
 * 布局分三档，按终端宽度退化：
 *
 * - **宽（≥ 104）**：左栏招牌 + 环境信息，右栏提示 + 更新日志，中间一根竖线
 * - **中（≥ 68）**：单栏，只留左栏内容——右栏是锦上添花，挤着放不如不放
 * - **窄（< 68）**：大字换成一行小字，其余照旧
 *
 * 三档的**外框高度不强求一致**（框会自己贴合内容），但每一档内部高度是定的，
 * 所以拖动窗口时只在越过档位那一瞬跳一次，不会连续抖动。
 */

import { TextAttributes } from "@opentui/core"
import { createMemo, For, Show } from "solid-js"
import { truncateStart } from "../path.ts"
import { useTheme } from "../theme.ts"
import { Banner, BannerCompact, BANNER_MIN_WIDTH } from "./Banner.tsx"

/** 招牌字模的宽度，左栏按它对齐。 */
const LOGO_COLUMNS = 64

/** 招牌 64 + 左右内边距 4 + 竖线 1 + 右栏至少 35，再加外框 2。 */
const TWO_COLUMN_MIN_WIDTH = 104

export type WelcomeInfo = {
  version: string
  model: string
  toolCount: number
  workspace: string
  /** 用户称呼，用在 "Welcome back X!" 那行；给空就只写 "Welcome back!" */
  user?: string
}

export type WelcomeProps = {
  info: WelcomeInfo
  /** 终端列数。由上层传进来而不是自己取，方便测试时喂假值。 */
  columns: number
}

export function Welcome(props: WelcomeProps) {
  const theme = useTheme()
  const twoColumn = () => props.columns >= TWO_COLUMN_MIN_WIDTH
  const wideEnoughForLogo = () => props.columns >= BANNER_MIN_WIDTH

  // 左栏宽度：画得下大字就钉死 64（和字模同宽），否则吃掉整行减去外框和内边距
  const leftWidth = () => (wideEnoughForLogo() ? LOGO_COLUMNS : props.columns - 6)
  const workspace = createMemo(() => truncateStart(props.info.workspace, leftWidth()))

  return (
    <box
      flexDirection="row"
      border
      borderColor={theme.border}
      // 标题挂在上边框线上，和 Claude Code 一致：省一行，还顺手交代了版本
      title={` myagent v${props.info.version} `}
      paddingLeft={2}
      paddingRight={2}
      paddingTop={1}
      paddingBottom={1}
      flexShrink={0}
    >
      {/* 左栏：欢迎语 + 招牌 + 环境信息 */}
      <box flexDirection="column" flexGrow={twoColumn() ? 0 : 1} flexShrink={0}>
        <box alignItems="center" width={leftWidth()}>
          <text fg={theme.text} attributes={TextAttributes.BOLD}>
            {props.info.user ? `Welcome back ${props.info.user}!` : "Welcome back!"}
          </text>
        </box>
        <text> </text>

        <Show when={wideEnoughForLogo()} fallback={<BannerCompact />}>
          <Banner />
        </Show>

        <text> </text>
        <text fg={theme.subtle}>
          {props.info.model} · {props.info.toolCount} 个工具
        </text>
        {/* 路径可能很长：截断而不是折行，折行会把框撑高破坏固定高度。
            截头保尾，因为路径越靠后信息量越大。 */}
        <text fg={theme.inactive}>{workspace()}</text>
      </box>

      {/* 右栏：只在宽屏出现。竖线用 border=["left"] 画，比塞一列 │ 字符省事且不会错位 */}
      <Show when={twoColumn()}>
        <box
          flexDirection="column"
          flexGrow={1}
          minWidth={0}
          marginLeft={2}
          paddingLeft={2}
          border={["left"]}
          borderColor={theme.border}
        >
          <Section title="上手提示" lines={TIPS} />
          <text> </text>
          <Section title="最近更新" lines={WHATS_NEW} />
        </box>
      </Show>
    </box>
  )
}

function Section(props: { title: string; lines: readonly string[] }) {
  const theme = useTheme()
  return (
    <box flexDirection="column">
      <text fg={theme.brand} attributes={TextAttributes.BOLD}>
        {props.title}
      </text>
      <For each={props.lines}>{(line) => <text fg={theme.text}>{line}</text>}</For>
    </box>
  )
}

const TIPS = [
  "输入任务后按 Enter，agent 会自己决定调哪些工具",
  "/plan 先规划后执行，/help 看全部命令",
  "Ctrl-O 展开工具调用详情，Ctrl-C 退出",
] as const

const WHATS_NEW = [
  "界面渲染层迁到 OpenTUI + SolidJS（原生绘制、支持鼠标）",
  "先规划后执行：计划面板 + 执行前确认",
  "审计轨迹落盘，一行一条 JSON",
] as const

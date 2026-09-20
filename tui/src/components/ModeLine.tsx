import { useTheme } from "../theme.ts"
import type { Mode } from "../store.ts"

/**
 * 状态栏底下那一行：<b>下一句话会怎么跑</b>。
 *
 * <h3>为什么它必须常驻，哪怕在 normal 下</h3>
 * 这一行是 `/plan`、`/team` 两条命令删掉之后<b>唯一</b>还能告诉用户
 * 「还有别的模式」的地方。只在非 normal 时才显示的话，新用户永远不会知道
 * Shift+Tab 能干什么——而那两条命令已经不在菜单里了，他也无从发现。
 *
 * <p>所以 normal 那行画得很淡（`inactive`），存在感低到不碍事，但一直在。
 *
 * <h3>为什么颜色要跟着模式变</h3>
 * 「我以为我在 normal，结果它拆了一份计划」和「我以为在 plan，结果它直接动手改了文件」
 * 是这个交互最容易出的两种事故，而且<b>都要等到结果出来才发现</b>。
 * 颜色是眼睛在打字时唯一会顺带扫到的信号——比多一行字管用。
 */

type Descriptor = { label: string; hint: string; marker: string }

const MODES: Record<Mode, Descriptor> = {
  normal: { label: "normal", hint: "直接对话", marker: "·" },
  plan: { label: "plan", hint: "先拆计划，确认后逐个跑", marker: "⏵⏵" },
  team: { label: "team", hint: "几个角色各看一遍，最后合议", marker: "⏵⏵" },
}

export function ModeLine(props: { mode: Mode; columns: number }) {
  const theme = useTheme()

  const color = () => {
    switch (props.mode) {
      case "plan":
        return theme.brand
      case "team":
        return theme.thought
      default:
        return theme.inactive
    }
  }

  const descriptor = () => MODES[props.mode]

  return (
    <box flexDirection="row" flexShrink={0} paddingLeft={1} paddingRight={1}>
      <text fg={color()}>
        {descriptor().marker} {descriptor().label}
      </text>
      <text fg={theme.inactive}>  {descriptor().hint}</text>
      {/* 键位提示一直挂着。用户是在**打字的时候**想起「这句话该走规划」的，
          那一刻他不会去翻 /help——提示得就在眼皮底下 */}
      <text fg={theme.inactive}>  ·  shift+tab 切换</text>
    </box>
  )
}

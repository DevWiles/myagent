/**
 * 蓝色 MYAGENT 招牌。
 *
 * 字模**复用 Ink 版的** `tui/src/format.ts`，不在这里复制一份——
 * 招牌宽度（64）和高度（6）在 Ink 版里已经调好并被布局代码依赖，
 * 抄一份出来迟早两边对不上。那个文件是纯 TS、零依赖，跨包 import 是安全的。
 *
 * 上色规则：字形里 `█` 是实心笔画、`╗╝║═╔╚` 是描边线。
 * 两者分别用 brand / brandDim，深浅一拉就有了立体感——
 * 同一个颜色画完整张图会糊成一坨蓝斑，这是终端里画大字最常见的翻车点。
 */

import { TextAttributes } from "@opentui/core"
import { For } from "solid-js"
import { LOGO_LINES } from "../format.ts"
import { useTheme } from "../theme.ts"

/** 实心笔画的字符。除它以外的非空格字符都算描边。 */
const FILL = "█"

/**
 * 招牌固定占的行数（= LOGO_LINES.length）。
 *
 * 必须是常数：招牌是首屏第一项，缩放时它忽高忽低会让下面的内容跳。
 */
export const BANNER_HEIGHT = LOGO_LINES.length

/** 窄于这个宽度就别画大字了，画出来会折行，比不画还难看。 */
export const BANNER_MIN_WIDTH = 68

export function Banner() {
  const theme = useTheme()

  return (
    <box flexDirection="column" flexShrink={0}>
      <For each={LOGO_LINES}>
        {(line) => (
          // 一行内要两种颜色，只能拆成字符级的 <text>。
          // OpenTUI 底层是原生绘制，这点碎片化开销可以忽略——
          // 真正贵的是每帧重建节点树，而招牌是静态的，Solid 只会建一次。
          <box flexDirection="row">
            <For each={Array.from(line)}>
              {(char) => <text fg={char === FILL ? theme.brand : theme.brandDim}>{char}</text>}
            </For>
          </box>
        )}
      </For>
    </box>
  )
}

/**
 * 窄屏退化版：画不下大字时只写一行小字，但**行数补齐到和大字一样**。
 *
 * 为什么要补空行：从宽屏拖窄的过程中招牌高度若变化，下方内容会整体上下跳，
 * 观感上像是界面在抽搐。宁可留白也要把高度钉死。
 */
export function BannerCompact() {
  const theme = useTheme()

  return (
    <box flexDirection="column" flexShrink={0}>
      <text fg={theme.brand} attributes={TextAttributes.BOLD}>
        myagent
      </text>
      <For each={Array.from({ length: BANNER_HEIGHT - 1 })}>{() => <text> </text>}</For>
    </box>
  )
}

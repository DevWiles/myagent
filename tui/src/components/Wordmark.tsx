/**
 * 带投影的字标。排版算法在 `../wordmark.ts`，这里只负责上色。
 *
 * 一个格子可能上下两半不同层（半块字符 `▀` 的前景是上半格、背景是下半格），
 * 投影的半格精度就落在这上面——`fg` 和 `bg` 各查一次颜色。
 *
 * 投影用 `border` 而不是 `inactive`：`inactive` 只比正文淡一档，画出来像重影；
 * `border` 在深浅两套主题里都是最暗的一档，才压得住。
 */

import { TextAttributes } from "@opentui/core"
import { For, Show } from "solid-js"
import { useTheme } from "../theme.ts"
import { buildWordmark, toRuns, wordmarkWidth, type Layer } from "../wordmark.ts"

export type WordmarkProps = {
  text: string
  /** 可用列数。画不下点阵就退回一行普通文字。 */
  columns: number
}

export function Wordmark(props: WordmarkProps) {
  const theme = useTheme()
  const fits = () => props.columns >= wordmarkWidth(props.text)
  const colorOf = (layer: Layer | null): string | undefined =>
    layer === "main" ? theme.text : layer === "shadow" ? theme.border : undefined

  return (
    <Show
      when={fits()}
      fallback={
        <text fg={theme.text} attributes={TextAttributes.BOLD}>
          {props.text}
        </text>
      }
    >
      <box flexDirection="column" flexShrink={0}>
        <For each={buildWordmark(props.text)}>
          {(row) => (
            <box flexDirection="row">
              <For each={toRuns(row)}>
                {(run) => (
                  <text fg={colorOf(run.fg)} bg={colorOf(run.bg)}>
                    {run.text}
                  </text>
                )}
              </For>
            </box>
          )}
        </For>
      </box>
    </Show>
  )
}

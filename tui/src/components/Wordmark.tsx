/**
 * 斜体 + 投影的字标。排版算法在 `../wordmark.ts`，这里只负责上色。
 *
 * 两层用两个颜色：本体 `text`、投影 `border`。
 * 投影必须**比正文暗**才像影子——用 `inactive`（比正文淡一档的灰）会像重影，
 * 而 `border` 在深浅两套主题里都是最暗的一档，正好。
 */

import { TextAttributes } from "@opentui/core"
import { For, Show } from "solid-js"
import { useTheme } from "../theme.ts"
import { buildWordmark, toRuns, wordmarkWidth } from "../wordmark.ts"

export type WordmarkProps = {
  text: string
  /** 可用列数。画不下点阵就退回一行普通斜体字。 */
  columns: number
}

export function Wordmark(props: WordmarkProps) {
  const theme = useTheme()
  const fits = () => props.columns >= wordmarkWidth(props.text)

  return (
    <Show
      when={fits()}
      fallback={
        // 退化版才用得上 ITALIC：这里是真字符，终端的斜体对它有效
        <text fg={theme.text} attributes={TextAttributes.BOLD | TextAttributes.ITALIC}>
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
                  <text
                    fg={run.layer === "shadow" ? theme.border : theme.text}
                    attributes={run.layer === "main" ? TextAttributes.BOLD : undefined}
                  >
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

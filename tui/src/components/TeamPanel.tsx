import { Index, Show } from "solid-js"
import { TextAttributes } from "@opentui/core"
import { useTheme } from "../theme.ts"
import { truncate } from "../format.ts"
import { labelColumns, padToColumns } from "../plan-layout.ts"
import type { TeamData, TeamSeat } from "../store.ts"

/**
 * 钉在输入框下边的流水线面板。
 *
 * <p>和 {@link PlanPanel} 是两种形状，不能共用：计划画的是一张依赖图，
 * 流水线画的是**按执行顺序长大的座位表**——开场只有规划一席，
 * workers、验收、返工/重拟的新工序随 team_seats_added 逐批追加，
 * 数组顺序就是执行顺序，画一列就够了。
 *
 * <p>还没做行数降级：多轮返工能把座位表堆到十几行，真挤了再学计划面板那套。
 */

const MARKS: Record<string, string> = {
  PENDING: "⧗",
  RUNNING: "▸",
  SUCCEEDED: "✓",
  FAILED: "✗",
  SKIPPED: "⊘",
}

function headline(status: string): string {
  switch (status) {
    case "CREATED":
      return "流水线"
    case "RUNNING":
      return "流水线进行中"
    case "COMPLETED":
      return "验收通过"
    case "PARTIAL":
      return "没过验收"
    case "FAILED":
      return "流水线没跑成"
    case "CANCELLED":
      return "已中断"
    default:
      return "流水线"
  }
}

export function TeamPanel(props: { team: TeamData; columns: number }) {
  const theme = useTheme()

  const running = () => props.team.status === "RUNNING" || props.team.status === "CREATED"

  // 标签宽度按这场实际坐的角色算，不写死——名册是后端给的，会长
  const labelWidth = () =>
    labelColumns(props.team.seats.map(seat => ({
      id: seat.id,
      type: seat.role,
      roleTitle: seat.roleTitle,
      description: "",
      status: seat.status,
      parentId: null,
      dependsOn: [],
    })))

  const markColor = (status: string): string => {
    switch (status) {
      case "SUCCEEDED":
        return theme.success
      case "FAILED":
        return theme.error
      case "RUNNING":
        return theme.brand
      case "SKIPPED":
        return theme.inactive
      default:
        return theme.inactive
    }
  }

  /** 一道工序的右半句：干完了、正在干、还是倒了的原因。 */
  const note = (seat: TeamSeat): string => {
    switch (seat.status) {
      case "SUCCEEDED":
        return seat.durationMs ? `完成  ${Math.round(seat.durationMs / 1000)}s` : "完成"
      case "RUNNING":
        return "进行中…"
      case "SKIPPED":
        return seat.error ? seat.error : "没轮到"
      case "FAILED":
        return seat.error ? `没跑成：${seat.error}` : "没跑成"
      default:
        return seat.summary
    }
  }

  return (
    <box
      flexDirection="column"
      // 和计划面板一样不能省：对话区是 flexGrow={1}，会把可伸缩的兄弟整行整行压没
      flexShrink={0}
      border
      borderColor={running() ? theme.brand : theme.border}
      paddingLeft={1}
      paddingRight={1}
    >
      <box flexDirection="row">
        <text fg={theme.heading} attributes={TextAttributes.BOLD}>
          {headline(props.team.status)}
        </text>
        <text fg={theme.inactive}> {props.team.seats.length} 道工序 </text>
        <text fg={theme.text}>
          {truncate(props.team.goal, Math.max(10, props.columns - 24))}
        </text>
      </box>

      {/* 为什么是这几位——花钱之前用户唯一一眼，不能省 */}
      <text fg={theme.subtle}>
        {"  "}
        {truncate(props.team.reason, Math.max(10, props.columns - 4))}
      </text>

      {/* ⚠️ <Index> 不是 <For>：席位状态每次都是新对象，<For> 按元素身份复用会整块重画 */}
      <Index each={props.team.seats}>
        {(seat, i) => (
          <box flexDirection="row">
            <text fg={theme.inactive}>{`${i + 1}`.padStart(2)}. </text>
            <text fg={markColor(seat().status)}>{MARKS[seat().status] ?? "·"} </text>
            <text fg={theme.toolName}>
              [{padToColumns(seat().roleTitle, labelWidth())}]{" "}
            </text>
            <text fg={seat().status === "FAILED" ? theme.error : theme.subtle}>
              {truncate(note(seat()), Math.max(10, props.columns - 12 - labelWidth()))}
            </text>
          </box>
        )}
      </Index>

      {/* PARTIAL 必须说出来，而且分两种：打回（问题清单在 verdict 里）和验收没跑成
          （verdict 为空）。两种都意味着工作区里有一批没人担保的改动——
          混成一种，用户会把「发现了问题」误读成「审查没跑」 */}
      <Show when={props.team.status === "PARTIAL"}>
        <text fg={theme.warning}>
          {props.team.verdict
            ? "  ⚠ 验收打回——问题清单在上面验收那道工序的产出里，改动没回滚"
            : "  ⚠ 验收没跑成——施工的改动还在，但没人担保它对"}
        </text>
      </Show>

      <Show when={props.team.status === "CANCELLED"}>
        <text fg={theme.inactive}>{"  已中断。跑完的工序（和它们的改动）留着了。"}</text>
      </Show>
    </box>
  )
}

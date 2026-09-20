import { Index, Show } from "solid-js"
import { TextAttributes } from "@opentui/core"
import { useTheme } from "../theme.ts"
import { truncate } from "../format.ts"
import { labelColumns, padToColumns } from "../plan-layout.ts"
import type { TeamData, TeamSeat } from "../store.ts"

/**
 * 钉在输入框下边的圆桌面板。
 *
 * <p>和 {@link PlanPanel} 是两种形状，不能共用：计划要画的是一条链
 * （谁依赖谁、跑到第几步），圆桌要画的是<b>几个并列的视角加一份收口</b>——
 * 没有层、没有依赖、没有先后上的因果。硬套计划那套画法，会让人以为
 * 「审查」得等「调研」跑完才有意义，而它俩根本互不相干。
 *
 * <p>席位少（最多四位），所以不做计划面板那套三档降级——
 * 那套逻辑存在是因为十几步的计划能把对话区挤没，四行挤不没。
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
      return "圆桌"
    case "RUNNING":
      return "圆桌进行中"
    case "COMPLETED":
      return "圆桌结束"
    case "PARTIAL":
      return "圆桌结束"
    case "FAILED":
      return "圆桌没开成"
    case "CANCELLED":
      return "已中断"
    default:
      return "圆桌"
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

  /** 一席的右半句：说完了、正在看、还是哑火的原因。 */
  const note = (seat: TeamSeat): string => {
    switch (seat.status) {
      case "SUCCEEDED":
        return seat.durationMs ? `说完了  ${Math.round(seat.durationMs / 1000)}s` : "说完了"
      case "RUNNING":
        return "正在看…"
      case "SKIPPED":
        return "没轮到"
      case "FAILED":
        return seat.error ? `没说上话：${seat.error}` : "没说上话"
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
        <text fg={theme.inactive}> {props.team.seats.length} 位 </text>
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

      <Show when={props.team.synthesizing}>
        <text fg={theme.brand}>{"  ▸ 合议中…"}</text>
      </Show>

      {/* PARTIAL 必须说出来：少了一席的合议和全员到齐的长得一模一样，
          不标的话用户会把两个人的共识当成三个人的共识 */}
      <Show when={props.team.status === "PARTIAL"}>
        <text fg={theme.warning}>
          {"  ⚠ 缺席 "}
          {props.team.silent ?? 0}
          {" 席——下面这份合议少了那个视角"}
        </text>
      </Show>

      <Show when={props.team.status === "CANCELLED"}>
        <text fg={theme.inactive}>{"  已中断，没有合议。说过话的那几席留着了。"}</text>
      </Show>
    </box>
  )
}

import { Index, Show } from "solid-js"
import { TextAttributes } from "@opentui/core"
import { useTheme } from "../theme.ts"
import { truncate } from "../format.ts"
import { layoutPlanPanel, planProgress } from "../plan-layout.ts"
import type { PlanData, PlanTask } from "../store.ts"

/**
 * 钉在输入框下边的计划面板。
 *
 * <p>和 transcript 里那条 plan entry 是分工，不是重复：这块是**当前这份计划的活状态**，
 * 一直看得见、跟着执行实时点亮；entry 那条是历史痕迹，跑完往回滚还能找到。
 * 面板在用户说下一句话时让位（见 store 的 activePlanKey）。
 *
 * <p>高度会挤占对话区，所以要收敛——取舍逻辑在 {@link layoutPlanPanel}，
 * 那是纯函数，边界条件在 plan-layout.test.ts 里测。
 */

/** 最长的类型名是 VERIFICATION，按它对齐，标签才不会一行一个宽度。 */
const TYPE_WIDTH = 12

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
      return "待确认的计划"
    case "RUNNING":
      return "正在执行"
    case "COMPLETED":
      return "计划完成"
    case "FAILED":
      return "计划未完成"
    case "CANCELLED":
      return "已中断"
    default:
      return "计划"
  }
}

export function PlanPanel(props: { plan: PlanData; columns: number; maxRows: number }) {
  const theme = useTheme()

  const layout = () => layoutPlanPanel(props.plan.tasks, props.maxRows)
  const progress = () => planProgress(props.plan.tasks)
  const running = () => props.plan.status === "RUNNING"

  // id 宽度按实际最长的来，不写死：task_1 和 t1 这两种命名宽度差一倍，
  // 写死就会在其中一种下留出一大片空白
  const idWidth = () =>
    Math.min(16, props.plan.tasks.reduce((max, task) => Math.max(max, task.id.length), 0))

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

  /** 编号行右边那截：类型 + 依赖。留给描述的宽度靠它算出来。 */
  const headRow = (task: PlanTask, index: number) => {
    const number = `${index + 1}`.padStart(2)
    const id = task.id.padEnd(idWidth())
    const type = `[${task.type.padEnd(TYPE_WIDTH)}]`
    const deps = task.dependsOn.length === 0 ? "依赖 无" : `依赖 ${task.dependsOn.join(", ")}`
    return { number, id, type, deps }
  }

  return (
    <box
      flexDirection="column"
      // 不能省：对话区是 flexGrow={1}，会把可伸缩的兄弟压扁，
      // 而压扁的表现不是滚动条，是**整行整行地消失**（确认框上踩过一次）
      flexShrink={0}
      border
      borderColor={running() ? theme.brand : theme.border}
      paddingLeft={1}
      paddingRight={1}
    >
      <box flexDirection="row">
        <text fg={theme.heading} attributes={TextAttributes.BOLD}>
          {headline(props.plan.status)}
        </text>
        <text fg={theme.text}>
          {" "}
          {truncate(props.plan.goal, Math.max(10, props.columns - 20))}
        </text>
      </box>

      <Show when={layout().hiddenBefore > 0}>
        <text fg={theme.inactive}>  ⋮ 上面还有 {layout().hiddenBefore} 步</text>
      </Show>

      {/* ⚠️ 必须是 <Index> 不是 <For>：任务状态每次都是新对象（immutable 更新），
          <For> 按元素身份复用，会把整块重画一遍；<Index> 按位置复用，只更新变了的字段。
          这个坑在 App.tsx 的 transcript 上已经踩过一次。 */}
      <Index each={layout().tasks}>
        {(task, i) => {
          const head = () => headRow(task(), layout().hiddenBefore + i)
          return (
            <box flexDirection="column">
              <box flexDirection="row">
                <text fg={theme.inactive}>{head().number}. </text>
                <text fg={markColor(task().status)}>{MARKS[task().status] ?? "·"} </text>
                <text fg={task().status === "RUNNING" ? theme.brand : theme.text}>
                  {head().id}{" "}
                </text>
                <text fg={theme.toolName}>{head().type} </text>
                <text fg={theme.inactive}>{truncate(head().deps, 24)}</text>
                <Show when={task().recovery}>
                  <text fg={theme.warning}> ↻改道</text>
                </Show>
              </box>
              <Show when={layout().withDescription}>
                <text fg={theme.subtle}>
                  {"     "}
                  {truncate(task().description, Math.max(10, props.columns - 8))}
                </text>
              </Show>
              {/* 失败原因永远要露出来，哪怕描述被省了——用户就靠它知道该去看谁 */}
              <Show when={task().error}>
                <text fg={theme.error}>
                  {"     "}
                  {truncate(task().error!, Math.max(10, props.columns - 8))}
                </text>
              </Show>
            </box>
          )
        }}
      </Index>

      <Show when={layout().hiddenAfter > 0}>
        <text fg={theme.inactive}>  ⋮ 下面还有 {layout().hiddenAfter} 步</text>
      </Show>

      <box flexDirection="row" paddingTop={1}>
        <text fg={theme.subtle}>
          进度 {progress()}% · 状态 {props.plan.status}
        </text>
        <Show when={props.plan.summary}>
          {(summary) => (
            <text fg={theme.subtle}>
              {" "}
              · 成功 {summary().succeeded}
              {summary().failed > 0 ? ` · 失败 ${summary().failed}` : ""}
              {summary().skipped > 0 ? ` · 跳过 ${summary().skipped}` : ""}
            </text>
          )}
        </Show>
      </box>
    </box>
  )
}

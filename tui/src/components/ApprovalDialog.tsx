/**
 * 危险动作确认框。
 *
 * 这个框出现的时候，后端的 agent 线程正卡在 `decide()` 上等回答——
 * 所以它必须能被回答，也必须能被中断（Esc/Ctrl-C 按拒绝），不能有第三种出路。
 *
 * **按键不在这里处理**，在 App 里（键盘那一段）。理由是：
 * 终端矮到放不下这个框时组件可能根本不渲染，可后端还在等。
 * 按键要是跟着组件一起消失，会话就永久挂死了。这条纪律从 Ink 版原样继承。
 * 同理，**三个交互态存在 store 里**，不在组件里——道理一样。
 *
 * **三态**：
 * ```
 *   ask ──选「以后都同意」──▸ rule      挑到什么程度，enter 存，esc 只批这一次
 *    │
 *    └──选「拒绝」──────────▸ feedback  补一句话给模型，enter 发，esc 不带话拒绝
 * ```
 *
 * 表态那一步是**光标选**不是敲字母：字母键要求用户先记住映射再动手，
 * 而这个框出现的时机是随机的（理由写在 {@link askChoices} 上）。
 * 代价是按钮区从一行变成一列，框高了 3 行——所以 diff 预算同步让出 3 行，
 * 否则框会悄悄把上面的对话顶出屏幕。
 */

import { TextAttributes } from "@opentui/core"
import { createMemo, For, Show } from "solid-js"
import { askChoices, type ApprovalStage, type AskChoice } from "../approval.ts"
import { DiffView } from "./DiffView.tsx"
import type { Approval } from "../store.ts"
import { truncateEnd, width } from "../path.ts"
import { useTheme } from "../theme.ts"

export type ApprovalDialogProps = {
  approval: Approval
  stage: ApprovalStage
  columns: number
  /** 终端总高度。diff 能占几行由它决定——把上面的对话顶掉比看不见 diff 更糟 */
  rows: number
}

export function ApprovalDialog(props: ApprovalDialogProps) {
  const theme = useTheme()
  // 边框 2 列 + 左右内边距 2 列
  const inner = () => Math.max(12, props.columns - 4)
  const wide = () => inner() >= 40

  // 预算要按**显示宽度**减，不能用 .length：
  // 「执行命令」是 4 个字符但占 8 列，按字符数算会少扣 4 列，
  // 长命令就正好溢出一点点，把动作行折成两行，框被撑高、按钮行顶出可视区。
  const detail = createMemo(() =>
    truncateEnd(props.approval.detail, Math.max(4, inner() - width(props.approval.action) - 2)),
  )
  const reason = createMemo(() => truncateEnd(`原因: ${props.approval.reason}`, inner()))

  // 框里除 diff 之外固定占掉约 13 行（边框、标题、动作、原因、留白，
  // 加上选项列表那 3~4 行）。选项从一行按钮变成一列之后，这个数字跟着涨了 3——
  // **不涨的话框会悄悄变高，把上面的对话顶出屏幕**，而那正是选项行
  // 一直压在一行里要避免的事。留不下 3 行就干脆不画 diff
  const diffBudget = () => Math.max(0, props.rows - 15)
  const showDiff = () => props.approval.preview.length > 0 && props.rows >= 18

  const level = createMemo(() => {
    const stage = props.stage
    if (stage.step !== "rule") return null
    return props.approval.suggestions[stage.index] ?? null
  })

  const choices = createMemo(() => askChoices(props.approval.suggestions.length > 0))
  const askIndex = () => (props.stage.step === "ask" ? props.stage.index : 0)

  /** 选中项按后果上色：同意绿、以后都同意黄、拒绝红。颜色本身就是一句提醒。 */
  const choiceColor = (key: AskChoice["key"]) =>
    key === "yes" ? theme.success : key === "always" ? theme.warning : theme.error

  return (
    <box
      flexDirection="column"
      flexShrink={0}
      border
      borderColor={theme.approvalBorder}
      paddingLeft={1}
      paddingRight={1}
    >
      <box flexDirection="row">
        <text fg={theme.approvalBorder} attributes={TextAttributes.BOLD}>
          需要确认
        </text>
        {/* 谁在问。计划里七八个任务依次跑过时，没有出处的确认框只会被一路点同意 */}
        <Show when={props.approval.origin.length > 0}>
          <text fg={theme.subtle}>{`   ${truncateEnd(props.approval.origin, Math.max(4, inner() - 10))}`}</text>
        </Show>
      </box>

      <box flexDirection="row" paddingTop={1}>
        <text fg={theme.text} attributes={TextAttributes.BOLD}>
          {props.approval.action}:{" "}
        </text>
        <text fg={theme.text}>{detail()}</text>
      </box>
      <text fg={theme.subtle}>{reason()}</text>

      {/* 改动长什么样。只说「原 30 行 → 新 45 行」的话，用户能做的判断只有「变长了」。
          带行号和底色，和转录里事后回看的那份长得一模一样——用户正是拿这两屏做对照的 */}
      <Show when={showDiff()}>
        <box flexDirection="column" paddingTop={1}>
          <DiffView diff={props.approval.preview} columns={inner()} budget={diffBudget()} />
        </box>
      </Show>

      {/* ---- 第一步：表态。光标选，不再背 y/n/a 的映射 ---- */}
      <Show when={props.stage.step === "ask"}>
        <box flexDirection="column" paddingTop={1}>
          <For each={choices()}>
            {(choice, i) => {
              const active = () => i() === askIndex()
              return (
                <box flexDirection="row">
                  {/* 选中标记和选项文字都换色：只靠一个 ❯ 的话，
                      在没有真彩色的终端上等于没有标记 */}
                  <text fg={active() ? theme.approvalBorder : theme.inactive}>
                    {active() ? "❯ " : "  "}
                  </text>
                  <text fg={theme.inactive}>{`${i() + 1}. `}</text>
                  <text
                    fg={active() ? choiceColor(choice.key) : theme.subtle}
                    attributes={active() ? TextAttributes.BOLD : undefined}
                  >
                    {truncateEnd(choice.label, Math.max(6, inner() - 6))}
                  </text>
                </box>
              )
            }}
          </For>
          <text fg={theme.subtle}>
            {wide() ? "  ↑↓ 选   Enter 确认   数字键直选   Esc 拒绝" : "  ↑↓/数字选  Enter 定"}
          </text>
        </box>
      </Show>

      {/* ---- 第二步之一：挑授权范围 ---- */}
      <Show when={props.stage.step === "rule" && level()}>
        <box flexDirection="column" paddingTop={1}>
          <box flexDirection="row">
            <text fg={theme.warning} attributes={TextAttributes.BOLD}>
              以后都同意:{" "}
            </text>
            <text fg={theme.text}>
              {truncateEnd(`${props.approval.kind}  ${level()!.label}`, Math.max(8, inner() - 12))}
            </text>
          </box>
          <text fg={theme.subtle}>
            {wide()
              ? "←/→ 调松紧    [enter] 存进 ~/.myagent/policy.json    [esc] 只批这一次"
              : "←/→ 松紧  [enter] 存  [esc] 只批一次"}
          </text>
        </box>
      </Show>

      {/* ---- 第二步之二：拒绝并补一句话 ---- */}
      <Show when={props.stage.step === "feedback"}>
        <box flexDirection="column" paddingTop={1}>
          <box flexDirection="row">
            <text fg={theme.error} attributes={TextAttributes.BOLD}>
              不要这么做，因为:{" "}
            </text>
            <text fg={theme.text}>
              {truncateEnd(
                (props.stage as { step: "feedback"; text: string }).text,
                Math.max(6, inner() - 16),
              )}
            </text>
            <text fg={theme.subtle}>▏</text>
          </box>
          <text fg={theme.subtle}>
            {wide()
              ? "这句话会原样交给模型    [enter] 发出    [esc] 不带话直接拒绝"
              : "[enter] 发  [esc] 直接拒绝"}
          </text>
        </box>
      </Show>
    </box>
  )
}

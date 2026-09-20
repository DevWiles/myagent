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
 *   ask ──[a]──▸ rule      挑「以后都同意」到什么程度，enter 存，esc 只批这一次
 *    │
 *    └──[n]──▸ feedback    补一句话给模型，enter 发，esc 不带话拒绝
 * ```
 *
 * 按钮行压成**一行**：窄屏折行会把框撑高，把上面的对话内容顶掉。
 */

import { TextAttributes } from "@opentui/core"
import { createMemo, For, Show } from "solid-js"
import { diffLineKind, visibleDiff, type ApprovalStage } from "../approval.ts"
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

  // 框里除 diff 之外固定占掉约 10 行（边框、标题、动作、原因、按钮、留白）。
  // 留不下 3 行就干脆不画——画一两行 diff 什么也说明不了，还占着地方
  const diff = createMemo(() => visibleDiff(props.approval.preview, Math.max(0, props.rows - 12)))
  const showDiff = () => props.approval.preview.length > 0 && props.rows >= 15

  const level = createMemo(() => {
    const stage = props.stage
    if (stage.step !== "rule") return null
    return props.approval.suggestions[stage.index] ?? null
  })

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

      {/* 改动长什么样。只说「原 30 行 → 新 45 行」的话，用户能做的判断只有「变长了」 */}
      <Show when={showDiff()}>
        <box flexDirection="column" paddingTop={1}>
          <For each={diff().lines}>
            {(line) => {
              const kind = diffLineKind(line)
              return (
                <text
                  fg={
                    kind === "add"
                      ? theme.diffAdd
                      : kind === "del"
                        ? theme.diffDel
                        : theme.subtle
                  }
                >
                  {truncateEnd(line, inner())}
                </text>
              )
            }}
          </For>
          <Show when={diff().hidden > 0}>
            <text fg={theme.subtle}>{`… 还有 ${diff().hidden} 行没显示`}</text>
          </Show>
        </box>
      </Show>

      {/* ---- 第一步：表态 ---- */}
      <Show when={props.stage.step === "ask"}>
        <box flexDirection="row" paddingTop={1}>
          <text fg={theme.success}>[y]</text>
          <text fg={theme.subtle}>{wide() ? " 同意   " : "同意 "}</text>
          <text fg={theme.error}>[n]</text>
          <text fg={theme.subtle}>{wide() ? " 拒绝（Esc 同）   " : "拒绝 "}</text>
          <Show when={props.approval.suggestions.length > 0}>
            <text fg={theme.warning}>[a]</text>
            <text fg={theme.subtle}>{wide() ? " 以后都同意" : "以后都行"}</text>
          </Show>
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

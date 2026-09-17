/**
 * 顶层界面。
 *
 * 布局自上而下：欢迎框 → 对话区 → 输入框 → 计划面板 → 状态栏。
 * 伸缩交给 Yoga 算，不自己数行数——自己算视口高度是 Ink 版早期踩过的坑，
 * 终端一缩放就错位。
 *
 * 状态全部来自 `createSession()`：那是 Ink 版那套 SessionStore 的 Solid 封装，
 * 事件规约逻辑两版共用，这里只管画。
 */

import type { KeyEvent } from "@opentui/core"
import { useKeyboard, useTerminalDimensions } from "@opentui/solid"
import { createEffect, Index, Show, type Accessor } from "solid-js"
import type { BackendOptions } from "../backend.ts"
import type { SessionState } from "../store.ts"
import { tildify } from "../path.ts"
import { createSession } from "../session.ts"
import { dark, ThemeProvider } from "../theme.ts"
import { ApprovalDialog } from "./ApprovalDialog.tsx"
import { EntryView } from "./EntryView.tsx"
import { PlanConfirmDialog } from "./PlanConfirmDialog.tsx"
import { PlanPanel } from "./PlanPanel.tsx"
import { PromptInput } from "./PromptInput.tsx"
import { StatusLine } from "./StatusLine.tsx"
import { Welcome } from "./Welcome.tsx"

export type AppProps = {
  options: BackendOptions
  branch?: string
  /** 后端没了就调它，由入口决定怎么退出（要还原终端）。 */
  onClosed: (reason: string) => void
  /**
   * 挂载时把会话的两个出口交给入口：读状态的 accessor + 关后端的手。
   *
   * 为什么不学 opencode 用 context + Provider：那边有多个路由各自往上报收尾文案，
   * 需要一个任意深度都能写入的口子；我们只有一个 App，收尾信息又完全是 SessionState
   * 的派生量，直接把 accessor 递出去就够了，中间那层 context 是纯开销。
   *
   * 递 **accessor** 而不是快照：退出那一刻才求值，拿到的才是最新的数。
   */
  onMounted?: (handle: { state: Accessor<SessionState>; dispose: () => void }) => void
}

export function App(props: AppProps) {
  const dimensions = useTerminalDimensions()
  const session = createSession(props.options)
  const state = session.state

  // 在组件体里直接调，不放 onMount：越早把 dispose 交出去越好——
  // 在这之前按 Ctrl-C，退出路径就够不着已经 spawn 出来的 java 进程了
  props.onMounted?.({ state, dispose: session.dispose })

  // 后端挂了就退出。不能在这儿直接 process.exit：终端还在 altscreen 模式里，
  // 得让入口先把 renderer destroy 掉，否则用户的终端会留在没有回显的状态
  createEffect(() => {
    const closed = state().closed
    if (closed) props.onClosed(closed)
  })

  const workspace = () => tildify(state().ready?.workspace ?? props.options.workspace)

  /**
   * 底部面板要显示的那份计划。
   *
   * <p>从 transcript 里按 key 取，而不是另存一份——两份状态各更新各的，
   * 迟早出现「面板上还是 PENDING，滚上去那份已经打勾了」。
   */
  const activePlan = () => {
    const key = state().activePlanKey
    if (!key) return null
    const entry = state().entries.find(item => item.key === key)
    return entry && entry.kind === "plan" ? entry.plan : null
  }

  /**
   * 分给计划面板的行数。
   *
   * <p>面板占的每一行都是从对话区身上抢的，所以给它一个**硬预算**：屏幕的四成，
   * 再多也不超过 16 行。没有上限的话，一份十几步的计划能把对话区挤到只剩一条缝——
   * 而用户这时候想看的恰恰是正在跑的那一步的输出。
   */
  const planRows = () => Math.max(4, Math.min(16, Math.floor(dimensions().height * 0.4)))

  /** 有确认框挂着时为 true：此刻后端的 agent 线程正阻塞在等回答上。 */
  const pending = () => state().approval !== null || state().planConfirm !== null

  // 确认框的按键在**这里**接，而不是在对话框组件里。
  // 因为矮终端下那个框可能整个不渲染，而后端正卡着等回答——
  // 按键要是跟着组件消失，会话就永久挂死了。这条纪律从 Ink 版继承。
  useKeyboard((key: KeyEvent) => {
    /**
     * 「这个键我消费了」。
     *
     * 不调用它会怎样：OpenTUI 的派发顺序是**全局 handler 先跑、renderable 后跑**，
     * 而我们在全局 handler 里答完确认框就把 pending 清掉，输入框同一瞬间重新获得焦点，
     * 于是紧接着的 renderable 派发把同一个按键又交给了输入框——
     * 按 y 同意之后，输入框里凭空多出一个 y。
     * preventDefault() 正好卡在两段派发之间（InternalKeyHandler.emitWithPriority）。
     */
    const consume = () => key.preventDefault()

    const approval = state().approval
    if (approval) {
      if (key.name === "y") return consume(), session.approve(approval.id, "yes")
      if (key.name === "n" || key.name === "escape")
        return consume(), session.approve(approval.id, "no")
      if (key.name === "a") return consume(), session.approve(approval.id, "always")
      // 确认框挂着时，其余按键一律吞掉：此刻不该有任何别的操作
      return consume()
    }

    // 计划确认同一套道理。刻意没有 [a]——每份计划都不一样，不给一次性授权
    const confirm = state().planConfirm
    if (confirm) {
      if (key.name === "y" || key.name === "return")
        return consume(), session.confirmPlan(confirm.id, true)
      if (key.name === "n" || key.name === "escape")
        return consume(), session.confirmPlan(confirm.id, false)
      return consume()
    }

    // 没有确认框时，Esc 中断当前这一轮
    if (key.name === "escape" && state().busy) {
      consume()
      session.cancel()
    }
  })

  return (
    <ThemeProvider value={dark}>
      <box flexDirection="column" width="100%" height="100%" paddingTop={1}>
        {/* 对话区。flexGrow 吃掉剩余高度，把输入框压到底部。
            scrollbox 是 OpenTUI 的原生滚动容器——Ink 版这里要自己算视口切片，
            这套不用，滚动在原生层做。 */}
        <box flexGrow={1} minHeight={0} paddingLeft={1} paddingRight={1}>
          <scrollbox stickyScroll stickyStart="bottom">
            {/* 欢迎框在**滚动容器里面**，是对话流的第一项而不是固定页头。
                钉在顶上的话，聊到第十轮它还占着五分之一个屏幕，
                而那时它显示的信息（版本、工具数）早就没人看了。
                想回看开场信息往上滚就是了。 */}
            <Welcome
              info={{
                version: state().ready?.version ?? "…",
                model: state().ready?.model ?? "连接后端中…",
                toolCount: state().ready?.tools.length ?? 0,
                workspace: workspace(),
                user: "Chris",
              }}
              columns={dimensions().width - 2}
            />

            {/* streaming 只给最后一条、且会话还忙着的那条——
                只有它的文本还会继续长，其余都已定稿 */}
            {/* ⚠️ 必须用 <Index> 而不是 <For>。
                两者的区别是按什么复用：<For> 按**元素身份**，<Index> 按**位置**。
                而 store 每收到一个 text_delta 就 replaceTail 一个新的 entry 对象
                （immutable 更新，这本身是对的）——在 <For> 眼里最后一条每次都是
                「一个全新的元素」，于是把整条消息的组件树销毁重建一遍，
                里面所有已经渲染好的 markdown 块跟着全部重建。
                实测就是这一处让早期块被重写 61 次；换成 <Index> 后位置不变、
                组件实例保留，只有 props 更新，块级复用才真正生效。 */}
            <Index each={state().entries}>
              {(entry, index) => (
                <EntryView
                  entry={entry()}
                  streaming={state().busy && index === state().entries.length - 1}
                />
              )}
            </Index>
          </scrollbox>
        </box>

        {/* 没有 API key 时先说清楚，否则用户发一句话才看到报错 */}
        <Show when={state().ready && !state().ready!.hasApiKey}>
          <box paddingLeft={1}>
            <text fg={dark.warning}>没检测到 API key，发出去的请求会失败。</text>
          </box>
        </Show>

        {/* 确认框。审批和计划确认永不同时出现（一个在执行中、一个在执行前），
            但还是显式分优先级，免得协议将来变了两个一起来时叠在一起 */}
        {/* flexShrink={0} 不能省：对话区是 flexGrow={1}，它会把所有可伸缩的兄弟
            压扁。压扁的表现不是滚动条，而是**整行整行地消失**——
            实测过一次，确认框只剩标题和「原因」，动作行和按钮行被吃掉了，
            用户根本不知道能按什么键，而后端还卡在那儿等。 */}
        <Show when={state().approval}>
          {(approval) => (
            <box flexShrink={0} paddingLeft={1} paddingRight={1}>
              <ApprovalDialog approval={approval()} columns={dimensions().width - 2} />
            </box>
          )}
        </Show>
        <Show when={!state().approval && state().planConfirm}>
          <box flexShrink={0} paddingLeft={1} paddingRight={1}>
            <PlanConfirmDialog />
          </box>
        </Show>

        <PromptInput
          placeholder={
            pending()
              ? "上面等你回答…"
              : state().busy
                ? "正在跑…（Esc 中断）"
                : "交代一件事，比如「把 README 里的启动命令补全」"
          }
          busy={state().busy}
          suspended={pending()}
          onSubmit={(value) => {
            if (state().busy) return
            // /plan 开头走规划模式，但后端没宣告 plan 能力时退回普通对话
            const planGoal = value.startsWith("/plan ") ? value.slice(6).trim() : null
            if (planGoal && state().ready?.features.includes("plan")) session.plan(planGoal)
            else session.prompt(value)
          }}
        />

        {/* 计划面板钉在输入框下边：出现时把输入框往上顶，
            执行完不收走——用户说下一句话才让位（见 store 的 activePlanKey） */}
        <Show when={activePlan()}>
          {(plan) => (
            <box flexShrink={0} paddingLeft={1} paddingRight={1}>
              <PlanPanel
                plan={plan()}
                columns={dimensions().width - 2}
                maxRows={planRows()}
              />
            </box>
          )}
        </Show>

        <StatusLine
          model={state().ready?.model ?? "未连接"}
          workspace={workspace()}
          branch={props.branch}
          columns={dimensions().width}
          busy={state().busy}
          tokens={state().tokens}
          contextLimit={state().contextLimit}
        />
      </box>
    </ThemeProvider>
  )
}

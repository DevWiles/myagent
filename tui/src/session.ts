/**
 * 把 Ink 版的 `SessionStore` 接到 Solid 上。
 *
 * **不重写状态机**。`tui/src/store.ts` 是一个框架无关的类（`subscribe` + `getSnapshot`），
 * 当初为 React 的 useSyncExternalStore 设计，但那个形状对 Solid 一样好使——
 * 订阅它，每次变更把新快照塞进一个 signal 就行。
 *
 * 这么做的价值不只是省事：协议有 20 多种事件、计划图还会中途长大，
 * 那套规约逻辑已经被 Ink 版的测试覆盖过了。迁移期最不该干的事就是
 * 把它按新框架重写一遍，然后用两套实现去追同一个后端。
 */

import { createSignal, onCleanup, type Accessor } from "solid-js"
import { startBackend, type Backend, type BackendOptions } from "./backend.ts"
import { SessionStore, type SessionState } from "./store.ts"

export type Session = {
  /** 当前快照。Solid 的 signal，读它就会建立依赖。 */
  state: Accessor<SessionState>
  /** 发一轮普通对话。 */
  prompt: (text: string) => void
  /** 先规划后执行。后端没宣告 plan 能力时不要调。 */
  plan: (goal: string) => void
  /** 回应审批请求。 */
  approve: (id: number, decision: "yes" | "no" | "always") => void
  /** 回应计划确认。 */
  confirmPlan: (id: number, approved: boolean) => void
  /** 中断当前这一轮。 */
  cancel: () => void
}

/**
 * 拉起后端、连上、开始接事件。
 *
 * 返回的 signal 一开始是空状态（`ready` 为 null）——后端要 build、要启动，
 * 不可能同步就绪。界面据此画「连接中」，不要在这里 await 到天荒地老。
 */
export function createSession(options: BackendOptions): Session {
  const store = new SessionStore()
  const [state, setState] = createSignal<SessionState>(store.getSnapshot())

  // 这里必须传新对象而不是直接传快照：SessionStore 每次 set 都会重建 state 对象，
  // 但 Solid 的 signal 默认用 === 比较，同一个引用不会触发更新。
  // store 本身就是 immutable 更新，所以直接 setState(snapshot) 是安全的，
  // 包一层函数只是为了绕开 Solid 把函数参数当 updater 的行为。
  const unsubscribe = store.subscribe(() => setState(() => store.getSnapshot()))

  let backend: Backend | undefined

  startBackend(options)
    .then((b) => {
      backend = b
      b.onEvent((event) => store.apply(event))
      b.onClose((reason) => store.markClosed(reason ?? "后端已退出"))
    })
    .catch((error: unknown) => {
      // 起不来是常见情况（jar 没 build、没装 java），要把原因原样显示出来，
      // 而不是留一个空界面让人猜
      store.markClosed(error instanceof Error ? error.message : String(error))
    })

  onCleanup(() => {
    unsubscribe()
    backend?.dispose()
  })

  return {
    state,
    prompt(text) {
      // 先把用户这句话记进 transcript，再发出去——否则从按回车到后端回第一个
      // 事件之间，屏幕上什么都没有，像是按键丢了
      store.submitPrompt(text)
      backend?.send({ t: "prompt", text })
    },
    plan(goal) {
      store.submitPrompt(`/plan ${goal}`)
      backend?.send({ t: "plan", goal })
    },
    approve(id, decision) {
      // 先收框再发：后端收到回复后要等工具跑完才会有下一个事件，
      // 这期间框不收掉，用户会对着一个已经答过的框发呆
      store.clearApproval()
      backend?.send({ t: "approval_reply", id, decision })
    },
    confirmPlan(id, approved) {
      store.clearPlanConfirm()
      backend?.send({ t: "plan_confirm_reply", id, approved })
    },
    cancel() {
      backend?.send({ t: "cancel" })
    },
  }
}

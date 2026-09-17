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

import { readFileSync } from "node:fs"
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
  /** 接着跑上次没跑完的计划。后端没宣告 resume 能力、或没有存档时不要调。 */
  resume: () => void
  /** 接着聊上次没聊完的对话。后端没宣告 session 能力、或没有存档时不要调。 */
  continueSession: () => void
  /** 回应审批请求。 */
  approve: (id: number, decision: "yes" | "no" | "always") => void
  /** 回应计划确认。 */
  confirmPlan: (id: number, approved: boolean) => void
  /** 中断当前这一轮。 */
  cancel: () => void
  /**
   * 显式关掉后端。
   *
   * 正常卸载走 onCleanup，这里再暴露一份是给退出路径用的：入口的 shutdown()
   * 最后要 process.exit()，而 process.exit 不跑 Solid 的卸载链。
   * 实测不显式关的话，java 进程在 Ctrl-C 之后还要活 10 秒以上。
   */
  dispose: () => void
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
  /**
   * store 每变一次就推一次 signal 太密了。
   *
   * text_delta 是一个字一个字来的，一秒能有几十次；每一次都会走完整条
   * 「Solid 更新 → Yoga 重新布局 → 重绘」的链路，屏幕看着就是一直在抖。
   * 而人眼根本分不出 60 次/秒和 20 次/秒的文字增长有什么区别。
   *
   * 所以这里做**尾沿节流**：第一次变更立刻生效（打字的即时感要保住），
   * 之后 33ms 内的变更全部合并成一次。Claude Code 用的是 16ms（60fps）
   * 的 throttle，同一个道理。
   *
   * 注意必须是尾沿——只做头沿的话，一轮结束前最后那几十毫秒的内容会丢。
   */
  const FRAME_MS = 33
  let timer: ReturnType<typeof setTimeout> | undefined
  let lastPush = 0

  const push = (): void => {
    lastPush = performance.now()
    setState(() => store.getSnapshot())
  }

  const unsubscribe = store.subscribe(() => {
    if (timer) return // 已经排好了一次，合并进去
    const elapsed = performance.now() - lastPush
    if (elapsed >= FRAME_MS) {
      push()
      return
    }
    timer = setTimeout(() => {
      timer = undefined
      push()
    }, FRAME_MS - elapsed)
  })

  let backend: Backend | undefined

  /**
   * 用来在「后端还没起好」时也能把 java 子进程杀掉。
   *
   * backend 只在 startBackend 的 then 里才被赋值，而 java 要一两秒才起得来。
   * 这段时间里退出的话，backend 还是 undefined，子进程句柄只活在 startBackend
   * 内部的作用域里——**没有任何人够得着它**。abort 是唯一的手。
   */
  const aborter = new AbortController()

  // 回放模式：不连后端，改用一份固定文本按固定速率喂进来。
  //
  // 为什么需要它：渲染性能问题（闪烁、重排）只能靠对比实验定位，
  // 而每次问模型拿到的回答长度和速度都不一样，测出来的数全是噪声——
  // 我就在这上面误判过好几次。固定输入才能让「改之前 vs 改之后」可比。
  //
  //   MYAGENT_REPLAY=某个.md 文件  ./myagent-tui
  //   MYAGENT_REPLAY_CPS=200      每秒喂多少字符，默认 120
  const replayPath = process.env["MYAGENT_REPLAY"]
  let stopReplay: (() => void) | undefined

  if (replayPath) {
    stopReplay = startReplay(store, replayPath, Number(process.env["MYAGENT_REPLAY_CPS"] ?? 120))
  } else {
    startBackend(options, aborter.signal)
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
  }

  let disposed = false

  /** onCleanup 和入口的 shutdown() 都会调，所以必须幂等。 */
  const dispose = (): void => {
    if (disposed) return
    disposed = true
    // 先停节流定时器：不然退出后它还会醒来往一个已经销毁的渲染树里写
    if (timer) clearTimeout(timer)
    stopReplay?.()
    unsubscribe()
    aborter.abort()      // 后端还没起好时，这是唯一能杀到子进程的手
    backend?.dispose()
  }

  onCleanup(dispose)

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
    resume() {
      // 和 prompt 一样先记一条：从按回车到后端把计划捞回来有肉眼可见的停顿，
      // 屏幕上什么都不留的话像是按键丢了
      store.submitPrompt("/resume")
      backend?.send({ t: "resume" })
    },
    continueSession() {
      store.submitPrompt("/continue")
      backend?.send({ t: "continue" })
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
    dispose,
  }
}

/**
 * 回放模式：把一份固定文本当成模型的流式输出，按固定速率喂给 store。
 *
 * 存在的唯一理由是**让渲染性能可以被测量**。真问模型的话，每次回答的长度、
 * 速度、结构都不一样，测出来的重绘次数根本没法横向比较——
 * 我就靠不可比的数据误判过好几次。
 *
 * 返回一个停止函数。
 */
function startReplay(store: SessionStore, path: string, cps: number): () => void {
  const TICK_MS = 50
  const perTick = Math.max(1, Math.round((cps * TICK_MS) / 1000))

  let text = ""
  try {
    text = readFileSync(path, "utf8")
  } catch (error) {
    store.markClosed(`回放文件读不了：${path}（${String(error)}）`)
    return () => {}
  }

  store.apply({
    t: "ready",
    protocol: 1,
    version: "replay",
    workspace: process.cwd(),
    model: "（回放模式）",
    planMode: false,
    hasApiKey: true,
    busy: false,
    tokens: 0,
    contextLimit: 64000,
    features: [],
    tools: [],
  } as never)

  let index = 0
  let timer: ReturnType<typeof setInterval> | undefined

  const begin = (): void => {
    store.apply({ t: "busy", busy: true } as never)
    timer = setInterval(() => {
      if (index >= text.length) {
        clearInterval(timer)
        timer = undefined
        store.apply({ t: "done", text, steps: 1 } as never)
        store.apply({ t: "busy", busy: false } as never)
        return
      }
      const chunk = text.slice(index, index + perTick)
      index += perTick
      store.apply({ t: "text_delta", text: chunk } as never)
    }, TICK_MS)
  }

  // 等界面挂载完再开始，免得把启动阶段也混进测量里
  const kickoff = setTimeout(begin, 1500)

  return () => {
    clearTimeout(kickoff)
    if (timer) clearInterval(timer)
  }
}

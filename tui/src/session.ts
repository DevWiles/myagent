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
import type { ClientCommand } from "./protocol.ts"
import { SessionStore, type SessionState } from "./store.ts"

export type Session = {
  /** 当前快照。Solid 的 signal，读它就会建立依赖。 */
  state: Accessor<SessionState>
  /** 发一轮普通对话。 */
  prompt: (text: string) => void
  /** 先规划后执行。后端没宣告 plan 能力时不要调。 */
  plan: (goal: string) => void
  /**
   * 开一条流水线：拟方案 → 并行施工 → 验收，打回可重新执行或重新规划。
   * 后端没宣告 team 能力时不要调。`roles` 已废弃，一律传空数组。
   */
  team: (goal: string, roles: string[]) => void
  /** 切到下一个模式（Shift+Tab）。后端没宣告的模式会被跳过。 */
  cycleMode: () => void
  /** 接着跑上次没跑完的计划。后端没宣告 resume_plan / resume 能力、或没有存档时不要调。 */
  resumePlan: () => void
  /** 接着聊上次没聊完的对话。后端没宣告 session 能力、或没有存档时不要调。 */
  continueSession: () => void
  /** 要会话历史列表（打开选择器）。后端回 session_list。 */
  requestSessions: () => void
  /** 挑中了某一段，接进来。 */
  resumeSession: (id: string) => void
  /** 收起选择器面板。 */
  closeSessionPicker: () => void
  /** 回应审批请求。 */
  /**
   * 回答审批。
   *
   * `rules` 只在 `always` 时有意义——那是用户挑定的授权范围，后端会存进
   * `~/.myagent/policy.json`；`feedback` 只在 `no` 时有意义，那句话会原样交给模型。
   */
  approve: (
    id: number,
    decision: "yes" | "no" | "always",
    rules?: string[],
    feedback?: string,
  ) => void
  /** 回应计划确认。 */
  /** 选了「以后都同意」：进「挑授权范围」那一步。后端没提议规则时什么也不做 */
  pickRule: () => void
  /** ↑/↓ 挪光标。停在哪一步就挪哪一列，由 store 判断 */
  moveChoice: (delta: number) => void
  /** 光标跳到表态的第 n 个选项（数字键直选） */
  pickChoice: (index: number) => void
  /** 计划确认框里挪光标 */
  movePlanChoice: (delta: number) => void
  /** 选了拒绝：进「补一句话」那一步 */
  askFeedback: () => void
  /** 在补话那一步敲了个键。返回 true 表示这个键被吃掉了 */
  typeFeedback: (key: { name?: string; sequence?: string }) => boolean
  /** 退回表态那一步 */
  resetApprovalStage: () => void
  confirmPlan: (id: number, approved: boolean) => void
  /** 现在就压一次上下文。原文留在存档里，压的只是发给模型的那份窗口。 */
  compact: () => void
  /** 查看 / 清理长期记忆。后端没宣告 memory 能力时不要调。 */
  memory: (action: "list" | "undo" | "clear_auto") => void
  /** 清空对话、开一段新会话（旧存档留着，见 protocol 3.9）。 */
  reset: () => void
  /** 中断当前这一轮。 */
  cancel: () => void
  /**
   * 往对话流里插一条本地提示，<b>不发给后端</b>。
   *
   * 命令层要用它：敲了不存在的命令、或者命令此刻用不了，都得当场说一句。
   * 在它之前这种情况是把那行字当提示词发给模型——花钱，而且 agent 会真的动手。
   */
  notice: (level: "info" | "warn" | "error", text: string) => void
  /**
   * 手机配对 URL。null 的两种情况要区分给用户听：
   * 后端还没起来（等一下再试），或不是 --lan 启动（要重启才有）。
   * 这里只回答「有没有」，怎么措辞归命令层。
   */
  pairingUrl: () => string | null
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
   * 后端起来之前攒下的命令，连上那一刻按序补发。
   *
   * <p>为什么现在必须有：build 挪到了界面起来之后（见 build.ts），
   * 「界面活着、后端还没影」这扇窗从零点几秒变成了 mvn 的五秒起步，
   * 而输入框在这期间是活的。原来的 `backend?.send` 会把这五秒里敲的话
   * **静默扔掉**——transcript 里记了、模型永远收不到，界面就此干等到天荒地老。
   */
  const outbox: ClientCommand[] = []
  const send = (command: ClientCommand): void => {
    if (backend) backend.send(command)
    else outbox.push(command)
  }

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
    startBackend(options, aborter.signal, (text) => store.setBootStatus(text))
      .then((b) => {
        backend = b
        b.onEvent((event) => store.apply(event))
        b.onClose((reason) => store.markClosed(reason ?? "后端已退出"))
        // 事件先接好再补发：攒下的第一句话马上会引来回答，接晚了会漏帧
        for (const command of outbox.splice(0)) b.send(command)
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
    pairingUrl: () => backend?.pairingUrl ?? null,
    prompt(text) {
      // 先把用户这句话记进 transcript，再发出去——否则从按回车到后端回第一个
      // 事件之间，屏幕上什么都没有，像是按键丢了
      store.submitPrompt(text)
      send({ t: "prompt", text })
    },
    plan(goal) {
      // 记原话，不再伪造一个 `/plan ` 前缀：现在「走的是规划」这件事由左下角那行
      // 常驻模式标识回答，而转录里紧跟着就是计划面板。
      // 往用户说过的话里塞他没打过的字，是最容易让人怀疑「我刚才到底输了什么」的做法
      store.submitPrompt(goal)
      send({ t: "plan", goal })
    },
    team(goal, roles) {
      store.submitPrompt(roles.length === 0 ? goal : `${roles.join(" ")} ${goal}`)
      send({ t: "team", goal, ...(roles.length > 0 ? { roles } : {}) })
    },
    cycleMode() {
      store.cycleMode()
    },
    resumePlan() {
      // 和 prompt 一样先记一条：从按回车到后端把计划捞回来有肉眼可见的停顿，
      // 屏幕上什么都不留的话像是按键丢了
      store.submitPrompt("/resume-plan")
      // 发新名字。后端两个都认（老名字 resume 还留着），但界面该说新的那个
      send({ t: "resume_plan" })
    },
    continueSession() {
      store.submitPrompt("/continue")
      send({ t: "continue" })
    },
    requestSessions() {
      // 刻意**不往 transcript 里记一条**：打开一个面板不是「说了一句话」。
      // 记了的话，翻历史时会看到一串莫名其妙的 /resume
      send({ t: "sessions" })
    },
    resumeSession(id) {
      store.closeSessionPicker()      // 先收面板：后端要几十毫秒，面板挂着会像没反应
      send({ t: "session_resume", id })
    },
    closeSessionPicker() {
      store.closeSessionPicker()
    },
    approve(id, decision, rules, feedback) {
      // 先收框再发：后端收到回复后要等工具跑完才会有下一个事件，
      // 这期间框不收掉，用户会对着一个已经答过的框发呆
      store.clearApproval()
      send({ t: "approval_reply", id, decision, rules, feedback })
    },
    pickRule() {
      store.beginRulePick()
    },
    moveChoice(delta) {
      store.moveChoice(delta)
    },
    pickChoice(index) {
      store.pickChoice(index)
    },
    movePlanChoice(delta) {
      store.movePlanChoice(delta)
    },
    askFeedback() {
      store.beginFeedback()
    },
    typeFeedback(key) {
      return store.typeFeedback(key)
    },
    resetApprovalStage() {
      store.resetApprovalStage()
    },
    confirmPlan(id, approved) {
      store.clearPlanConfirm()
      send({ t: "plan_confirm_reply", id, approved })
    },
    compact() {
      // 记一条：压缩要发模型调用，有肉眼可见的停顿，屏幕上不留痕像是按键丢了
      store.submitPrompt("/compact")
      send({ t: "compact" })
    },
    memory(action) {
      // 记一条：三个动作都会在转录里留下结果，不记的话那段结果像是凭空冒出来的
      store.submitPrompt(
        action === "list" ? "/memory" : action === "undo" ? "/memory undo" : "/memory clear-auto",
      )
      send({ t: "memory", action })
    },
    reset() {
      store.submitPrompt("/clear")
      send({ t: "reset" })
    },
    cancel() {
      send({ t: "cancel" })
    },
    notice(level, text) {
      store.notice(level, text)
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

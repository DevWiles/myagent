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

import type { KeyEvent, ScrollBoxRenderable } from "@opentui/core"
import { useKeyboard, useSelectionHandler, useTerminalDimensions } from "@opentui/solid"
import { armedAfter, createArmState, decideCtrlC } from "../interrupt.ts"
import { createEffect, createSignal, Index, onCleanup, Show, type Accessor } from "solid-js"
import type { BackendOptions } from "../backend.ts"
import type { SessionState } from "../store.ts"
import { tildify } from "../path.ts"
import { createSession } from "../session.ts"
import { dark, ThemeProvider } from "../theme.ts"
import {
  acceptSelected,
  filterCommands,
  helpLines,
  matchCommand,
  resolve,
  type CommandContext,
  type CommandEntry,
} from "../commands.ts"
import { askChoices, digitIndex, PLAN_CHOICES, type AskChoice } from "../approval.ts"
import { qrText } from "../qr.ts"
import { copyToClipboard } from "../clipboard.ts"
import { clampSelection, filterSessions, type PickerFilter } from "../session-picker.ts"
import { ApprovalDialog } from "./ApprovalDialog.tsx"
import { CommandMenu } from "./CommandMenu.tsx"
import { EntryView } from "./EntryView.tsx"
import { PlanConfirmDialog } from "./PlanConfirmDialog.tsx"
import { PlanPanel } from "./PlanPanel.tsx"
import { TeamPanel } from "./TeamPanel.tsx"
import { ModeLine } from "./ModeLine.tsx"
import { isNewlineKey, PromptInput } from "./PromptInput.tsx"
import { SessionPicker } from "./SessionPicker.tsx"
import { StatusLine } from "./StatusLine.tsx"
import { Welcome } from "./Welcome.tsx"

export type AppProps = {
  options: BackendOptions
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
  /**
   * 用户确认要退了，由入口执行（要关后端、还终端、打收尾）。
   *
   * 「确认」这两个字是重点：Ctrl-C 什么时候算退出是 {@link decideCtrlC} 的事，
   * 入口只管退。原先入口自己接 Ctrl-C 直接退，而它看不见「正跑着一轮」
   * 也看不见「输入框里有字」——看不见的东西自然也就保护不了。
   */
  onExit: () => void
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
   * 状态栏最左边那句连接提示。连着时是空的。
   *
   * <p>断线不再退出（见 backend.ts 开头），所以得有个地方让人知道「现在没连着、
   * 正在等它回来」——否则敲了没反应，用户第一反应是界面卡死了。
   */
  const linkText = () => {
    const link = state().link
    if (link.phase !== "reconnecting") return undefined
    return `${link.restarting ? "后端重启中" : "重连中"}（第 ${link.attempt} 次）`
  }

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

  /** 当前那条流水线。和计划面板一样从 transcript 里按 key 取，不另存一份。 */
  const activeTeam = () => {
    const key = state().activeTeamKey
    if (!key) return null
    const entry = state().entries.find(item => item.key === key)
    return entry && entry.kind === "team" ? entry.team : null
  }

  /**
   * 分给计划面板的行数。
   *
   * <p>面板占的每一行都是从对话区身上抢的，所以给它一个**硬预算**：屏幕的四成，
   * 再多也不超过 16 行。没有上限的话，一份十几步的计划能把对话区挤到只剩一条缝——
   * 而用户这时候想看的恰恰是正在跑的那一步的输出。
   */
  const planRows = () => Math.max(4, Math.min(16, Math.floor(dimensions().height * 0.4)))

  /**
   * 后端说这个工作区有没跑完的计划，而且它确实支持续跑。
   *
   * <p>两个条件都要查：老后端没有 resume 能力，这时候放出入口只会让用户敲了没反应。
   */
  const canResume = () =>
    Boolean(state().ready?.resumable) &&
    // resume_plan 是新名字，resume 是老的。连的可能是个旧后端，两个都认
    Boolean(
      state().ready?.features.includes("resume_plan") ||
        state().ready?.features.includes("resume"),
    )

  /**
   * 后端说这个工作区有没聊完的对话，而且它确实支持恢复。
   *
   * <p>两个条件都要查，理由同 canResume。和它是两件独立的事——
   * 一个续计划、一个续对话，可能同时有，也可能只有一个。
   */
  const canContinue = () =>
    Boolean(state().ready?.continuable) && Boolean(state().ready?.features.includes("session"))

  /** 有确认框挂着时为 true：此刻后端的 agent 线程正阻塞在等回答上。 */
  const pending = () => state().approval !== null || state().planConfirm !== null

  // ---------------------------------------------------------------- 斜杠命令

  /** 输入框里此刻的内容。菜单靠它边打边收窄，和高亮共用。 */
  const [draft, setDraft] = createSignal("")
  /** 菜单里选中第几条（↑↓ 移动，Tab 补全）。 */
  const [picked, setPicked] = createSignal(0)
  /**
   * 「再按一次 Ctrl-C 就退出」此刻成立吗。
   *
   * 它和输入框下面那行提示是<b>同一件事</b>：提示亮着 ⟺ 再按一下真的会退。
   * 所以解除武装和收起提示必须是同一个动作——这正是超时敢加的前提
   * （理由写在 {@link createArmState} 那一侧）。三种解除：按了别的键、超时、退出。
   */
  const [exitArmed, setExitArmed] = createSignal(false)
  /** 定时器记账在 {@link createArmState} 里面，<b>所有</b>改 exitArmed 的地方都得走 arm.set。 */
  const arm = createArmState(setExitArmed)
  onCleanup(() => arm.dispose())

  /**
   * 刚复制完那一下的提示，几秒后自己消失。
   *
   * <p>和「再按一次 Ctrl-C」一样<b>不进转录</b>：它说的是刚刚发生的一个动作，
   * 不是会话的内容。进了转录，滚上去还能看见「已复制 42 个字符」，纯属噪音。
   *
   * <p>成功也要报一声。这件事本来就是「看起来是好的」那一类——
   * 选区一直画得好好的，只有粘贴的时候才发现没进剪贴板。一行确认，
   * 用户才知道这回是真的进去了。
   */
  const [copied, setCopied] = createSignal<{ ok: boolean; text: string } | null>(null)
  let copyHintTimer: ReturnType<typeof setTimeout> | undefined

  function flashCopyHint(ok: boolean, text: string): void {
    clearTimeout(copyHintTimer)
    setCopied({ ok, text })
    copyHintTimer = setTimeout(() => setCopied(null), ok ? 1600 : 4000)
  }

  /**
   * 拖选完把文字送进系统剪贴板。
   *
   * <p><b>为什么必须自己做</b>：界面开着鼠标上报（滚轮要用），终端因此把拖选交给了程序，
   * 原生的「选中 + Cmd-C」不再生效。OpenTUI 接手后自己画选区、自己算选中了什么，
   * 但它只画不 copy——屏幕上一块高亮，Cmd-C 却什么都拿不到。
   *
   * <p>只在<b>拖完</b>那一下复制（`isDragging` 为 false），不在拖的过程中：
   * 拖一次会经过几十个中间状态，每个都去起一次 pbcopy 是在自找卡顿。
   */
  useSelectionHandler((selection) => {
    if (selection.isDragging) return
    const text = selection.getSelectedText()
    if (!text) return
    void copyToClipboard(text).then((ok) => {
      flashCopyHint(ok, ok
        ? `已复制 ${[...text].length} 个字符`
        : "复制失败：没找到 pbcopy/xclip/wl-copy，终端也不支持 OSC 52")
    })
  })

  onCleanup(() => clearTimeout(copyHintTimer))
  /** Tab 补全要改输入框的内容，这只手由 PromptInput 挂载时交过来。 */
  let inputControls: { setValue: (value: string) => void } | undefined

  /** 对话区的滚动容器。展开/收起工具明细时要按住它的滚动位置（见 toggleToolEntry）。 */
  let transcriptScroll: ScrollBoxRenderable | undefined

  /**
   * 展开/收起一组工具明细，**不让屏幕跳**。
   *
   * <p>抖动的真凶不是哪一行被顶替，是 scrollbox 的 stickyStart="bottom"：
   * 只要当前贴着底，内容高度一变它就重新钉底——展开在转录中间加了一块高度，
   * 重新钉底等于把整屏往上顶那么多行；收起再弹回来。摘要行钉不钉住都没用，
   * 动的是滚动位置本身。
   *
   * <p>所以在切换前关掉 sticky、记下 scrollTop，布局重排后原样按回去——
   * 上方内容纹丝不动，明细块只朝下长。下一帧再把 sticky 打开：
   * 此刻已经不在底部，sticky 的「贴底才钉」判定不会再抢。
   */
  const toggleToolEntry = (key: string): void => {
    const sb = transcriptScroll
    if (!sb) {
      session.toggleEntry(key)
      return
    }
    const keep = sb.scrollTop
    sb.stickyScroll = false
    session.toggleEntry(key)
    // 布局在渲染循环的下一帧里重排，restore 必须排在它**后面**——
    // setTimeout(0) 会抢在渲染帧之前，sticky 恢复太早，重排时又被钉底。
    // 50ms ≈ 两三帧，窗口内 sticky 一直关着，重排怎么排都动不了 scrollTop
    setTimeout(() => {
      sb.scrollTop = keep
      sb.stickyScroll = true
    }, 50)
  }

  /**
   * 一条命令能不能用，取决于后端宣告了什么、有没有存档。
   *
   * 这三样都从 ready 帧来，所以这里只是把它们打个包——判断逻辑在 commands.ts，
   * 那是纯函数，各种组合用断言测比在界面上手动敲一遍可靠。
   */
  const commandContext = (): CommandContext => ({
    features: state().ready?.features ?? [],
    hasContinuable: Boolean(state().ready?.continuable),
    hasResumablePlan: Boolean(state().ready?.resumable),
  })

  /**
   * 该显示哪些候选。
   *
   * 忙着跑、或者有确认框挂着时一条都不显示：那会儿输入框根本不该被用，
   * 摆一列命令只会让人以为可以敲。
   */
  const candidates = () =>
    state().busy || pending() || picker() !== null
      ? []
      : filterCommands(draft(), commandContext())

  /** 当前输入对上了一条真实存在的命令吗——输入框整行换色靠它。 */
  const matched = () => matchCommand(draft(), commandContext()) !== null

  // 候选变少之后选中项可能落在列表外面，回到第一条。
  // 不做这件事的话，Tab 会补出一条屏幕上根本没有的命令
  createEffect(() => {
    if (picked() >= candidates().length) setPicked(0)
  })

  /**
   * 按回车时怎么处置这行输入。
   *
   * <p>五种结局里最要紧的是 unknown：**必须就地拦住**。
   * 在这之前，认不出来的命令会被当提示词发给模型——打错一个字的代价是
   * 一次真实的模型调用，加上一个可能开始改文件的 agent。
   */
  /**
   * 一句话按当前模式发出去。
   *
   * <p>模式在**提交这一刻**才起作用，不是打字时。所以用户可以先写完一长段，
   * 再想起来「这该走规划」，Shift+Tab 一下再回车——不用重打。
   */
  const sendByMode = (text: string): void => {
    switch (state().mode) {
      case "plan":
        session.plan(text)
        return
      case "team":
        // 不再从输入里拆角色点名：分工由规划工序决定（协议里 roles 已废弃），
        // 整句话原样就是目标——开头的「operator」现在是目标的一部分，不是指令
        session.team(text, [])
        return
      default:
        session.prompt(text)
    }
  }

  const submit = (value: string): void => {
    if (state().busy) return
    const decision = resolve(value, commandContext())
    switch (decision.kind) {
      case "prompt":
        sendByMode(decision.text)
        return
      case "unknown":
        session.notice("warn", `没有 ${decision.typed} 这个命令。敲一个 / 看有哪些，或者 /help。`)
        return
      case "unavailable":
        session.notice("info", `${decision.entry.name} 现在用不了：${decision.entry.reason ?? "条件不满足"}`)
        return
      case "needs-args":
        session.notice(
          "warn",
          `${decision.entry.name} 后面要跟${decision.entry.args ?? "参数"}，比如「${decision.entry.name} 把 README 补全」`,
        )
        return
      case "command":
        run(decision.entry.id, decision.args)
    }
  }

  /**
   * 菜单开着时按回车：执行高亮那条。四个岔口的判断在 {@link acceptSelected} 里，
   * 那是纯函数、有测试——这里只负责照办。
   */
  const acceptHighlighted = (): void => {
    const decision = acceptSelected(candidates(), picked(), draft())
    switch (decision.kind) {
      case "none":
        return
      case "refuse":
        session.notice(
          "info",
          `${decision.entry.name} 现在用不了：${decision.entry.reason ?? "条件不满足"}`,
        )
        inputControls?.setValue("")
        return
      case "complete":
        inputControls?.setValue(decision.text)
        return
      case "run":
        // 回车被我消费掉了，输入框不会自己清，这里得清
        inputControls?.setValue("")
        run(decision.entry.id, decision.args)
    }
  }

  /** 真正执行一条命令。 */
  const run = (id: string, args: string): void => {
    switch (id) {
      case "help":
        session.notice("info", ["可以用的命令：", ...helpLines(commandContext())].join("\n"))
        return
      case "continue":
        session.continueSession()
        return
      case "resume":
        // 每次都重新问一次后端，不缓存：另一个界面可能刚聊了一轮、刚清过一段
        session.requestSessions()
        return
      case "resume-plan":
        session.resumePlan()
        return
      case "compact":
        session.compact()
        return
      case "undo":
        session.undo()
        return
      case "memory": {
        // 子命令在这儿解析而不是各注册一条命令，理由见 commands.ts 里那段注释。
        // 认不出的参数当场说清楚，不发给后端——后端也会拒，但那要多一个往返，
        // 而且错在用户手上，本地就能答
        const sub = args.trim().toLowerCase()
        if (sub === "" || sub === "list") {
          session.memory("list")
        } else if (sub === "undo") {
          session.memory("undo")
        } else if (sub === "clear-auto" || sub === "clear_auto") {
          session.memory("clear_auto")
        } else {
          session.notice("warn", `/memory 不认识「${sub}」，能用的是 list / undo / clear-auto`)
        }
        return
      }
      case "clear":
        session.reset()
        return
      case "qr": {
        const url = session.pairingUrl()
        if (url === null) {
          session.notice(
            "warn",
            "没有配对地址。手机要连进来，后端得先挂到中继上、或者开局域网模式：\n" +
              "  ./myagent-tui --relay wss://relay.example.com/ws   # 任何网络都能连\n" +
              "  ./myagent-tui --lan                                        # 只在同一个局域网\n" +
              "（见 docs/protocol.md 1.1 / 1.3）",
          )
          return
        }
        // 说清这个码能用在什么网络下。中继那个到哪儿都行，局域网那个出门就废——
        // 不写明的话，用户会拿着一个在家扫的码到公司去扫，然后以为是 app 坏了
        const where =
          session.pairingKind() === "relay"
            ? "任何网络都能连（经中继，端到端加密）"
            : "只在同一个局域网内有效"
        session.notice("info", `手机扫码连入（或手填下面的地址）——${where}：\n\n${qrText(url)}\n\n${url}`)
        return
      }
    }
  }

  // ------------------------------------------------------------ 会话选择器

  /** 面板的数据由后端给（session_list），null = 没开。 */
  const picker = () => state().sessionPicker
  /** 只看当前分支（Ctrl-B 切）。 */
  const [branchOnly, setBranchOnly] = createSignal(false)
  /** 选中第几段。 */
  const [pickIndex, setPickIndex] = createSignal(0)
  /**
   * 面板打开那一刻的「现在」，用来算「4 分钟前」。
   *
   * <p>钉死在打开的时刻，不跟着时钟走：让它每秒刷新，整个列表就会每秒重绘一次，
   * 而「4 分钟前」变成「5 分钟前」对挑选毫无帮助。
   */
  const [pickerNow, setPickerNow] = createSignal(Date.now())

  /**
   * 搜索词就是**主输入框里的字**。
   *
   * <p>面板不自带搜索框：再放一个 input 就要处理焦点在两个控件之间交接，
   * 而那正是这套界面上最容易出「按键打到别处去」的地方（见下面 consume 那段）。
   */
  const pickerFilter = (): PickerFilter => ({
    query: draft(),
    branchOnly: branchOnly(),
    currentBranch: state().ready?.branch ?? null,
  })

  const pickerRows = () => filterSessions(picker()?.sessions ?? [], pickerFilter())

  /** 分给面板的行数。一段占两行，所以先算行数再折半。 */
  const pickerMaxRows = () =>
    Math.max(2, Math.min(8, Math.floor((dimensions().height * 0.4) / 2)))

  // 每次打开都从第一条开始、从全部分支开始、重新取一次「现在」。
  // 不重置的话，上一次翻到第 9 条的位置会留到下一次打开，而列表早就不一样了
  createEffect(() => {
    if (picker()) {
      setPickIndex(0)
      setBranchOnly(false)
      setPickerNow(Date.now())
    }
  })

  // 搜索会让列表随时变短，选中项很容易落在外面。不夹的话按 Enter 会接一段
  // **屏幕上根本没有的会话**——那正是「挑错会话」最糟的形式
  createEffect(() => {
    const count = pickerRows().length
    if (pickIndex() >= count) setPickIndex(clampSelection(pickIndex(), count))
  })

  /** 挑中了，或者放弃了。两种都要把搜索词清掉，否则它会变成下一句提示词。 */
  const leavePicker = (id?: string): void => {
    inputControls?.setValue("")
    if (id) session.resumeSession(id)
    else session.closeSessionPicker()
  }

  /**
   * 按下 Ctrl-C。判断在 {@link decideCtrlC} 里（纯函数，有测试），这里只负责执行。
   *
   * <p>原先这个键根本到不了 App——入口的 `renderer.keyInput` 接到就直接退进程了。
   * 于是一次跑了两分钟的任务，手一抖整个会话就没了，而 agent 那边还在跑。
   */
  const handleCtrlC = (): void => {
    const action = decideCtrlC({
      hasApproval: state().approval !== null,
      hasPlanConfirm: state().planConfirm !== null,
      pickerOpen: picker() !== null,
      busy: state().busy,
      draft: draft(),
      armed: exitArmed(),
    })
    arm.set(armedAfter(action))

    switch (action.kind) {
      case "decline-approval": {
        const approval = state().approval
        if (approval) session.approve(approval.id, "no")
        return
      }
      case "decline-plan": {
        const confirm = state().planConfirm
        if (confirm) session.confirmPlan(confirm.id, false)
        return
      }
      case "close-picker":
        return leavePicker()
      case "interrupt":
        return session.cancel()
      case "clear-input":
        return inputControls?.setValue("")
      case "arm":
        return          // 提示那一行由 exitArmed() 驱动，这里没有别的事要做
      case "exit":
        return props.onExit()
    }
  }

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

    // Ctrl-C 必须在所有分支**之前**接。确认框那一段会把其余按键全部吞掉，
    // 放到后面的话，用户会陷进一个既答不了、也退不出的框里
    if (key.ctrl && key.name === "c") {
      consume()
      handleCtrlC()
      return
    }
    // 按了别的键就解除武装。和终端版 consecutiveInterrupts 被下一次成功读行
    // 清零是同一套语义——「连按两次」说的是连按，中间插了别的就不算
    if (exitArmed()) arm.set(false)


    const approval = state().approval
    if (approval) {
      // 确认框挂着时，所有按键都归它——此刻不该有任何别的操作。
      // 先 consume 再分发，省得下面每条出路都得记着调一次
      consume()
      const stage = state().approvalStage

      // ---- 挑授权范围：←/→ 调松紧，enter 存下，esc 只批这一次 ----
      if (stage.step === "rule") {
        if (key.name === "left" || key.name === "up") return session.moveChoice(-1)
        if (key.name === "right" || key.name === "down") return session.moveChoice(1)
        if (key.name === "return") {
          const level = approval.suggestions[stage.index]
          return session.approve(approval.id, "always", level?.patterns ?? [])
        }
        if (key.name === "escape") return session.approve(approval.id, "yes")
        return
      }

      // ---- 补一句话：可打印字符进输入，enter 发出，esc 不带话直接拒绝 ----
      if (stage.step === "feedback") {
        if (key.name === "return") return session.approve(approval.id, "no", [], stage.text)
        if (key.name === "escape") return session.approve(approval.id, "no")
        // typeFeedback 把方向键之类的退回来，那些在这一步没有意义，吞掉就是
        session.typeFeedback(key)
        return
      }

      // ---- 表态：↑/↓ 挪光标，enter 选中，数字键直选，esc 一键拒绝 ----
      const choices = askChoices(approval.suggestions.length > 0)

      /**
       * 选中一项。只有「同意」是就地答完的，另外两项都是**进下一步**——
       * 授权范围要挑、拒绝的理由要写，这个框的三个出口本来就不对称。
       */
      const commit = (choice: AskChoice | undefined) => {
        if (!choice) return
        if (choice.key === "yes") return session.approve(approval.id, "yes")
        if (choice.key === "always") return session.pickRule()
        // 拒绝不直接发出去，先给一个补话的机会：拒绝本身只是把路堵上，
        // 「换个做法」那句话才是模型下一步的依据
        return session.askFeedback()
      }

      if (key.name === "up") return session.moveChoice(-1)
      if (key.name === "down") return session.moveChoice(1)
      if (key.name === "return") return commit(choices[stage.index])
      // Esc 仍然是一键拒绝，不经过补话那一步：
      // 在矮到画不出框的终端上，它是唯一还看得见的出路
      if (key.name === "escape") return session.approve(approval.id, "no")

      // 数字键直选：按下即生效，不用再补一下回车。
      // 光标跟着挪是为了让这一下**看得见**——终端不重绘完就换了画面的话，
      // 用户不确定自己到底按中了哪一项
      const picked = digitIndex(key)
      if (picked !== null && picked < choices.length) {
        session.pickChoice(picked)
        return commit(choices[picked])
      }
      return
    }

    // 计划确认同一套道理。刻意没有「以后都同意」——每份计划都不一样，不给一次性授权
    const confirm = state().planConfirm
    if (confirm) {
      consume()
      if (key.name === "up") return session.movePlanChoice(-1)
      if (key.name === "down") return session.movePlanChoice(1)
      if (key.name === "return") return session.confirmPlan(confirm.id, confirm.choice === 0)
      if (key.name === "escape") return session.confirmPlan(confirm.id, false)
      const picked = digitIndex(key)
      if (picked !== null && picked < PLAN_CHOICES.length) {
        return session.confirmPlan(confirm.id, picked === 0)
      }
      return
    }

    // 会话选择器开着时，它先接键。输入框照常收字符——那就是搜索框
    if (picker()) {
      const rows = pickerRows()
      if (key.name === "escape") return consume(), leavePicker()
      if (key.name === "up") {
        consume()
        setPickIndex((i) => clampSelection(i - 1, rows.length))
        return
      }
      if (key.name === "down") {
        consume()
        setPickIndex((i) => clampSelection(i + 1, rows.length))
        return
      }
      // Ctrl-B 只看当前分支。不在 git 仓库里时这个开关没有意义（见 filterSessions）
      if (key.name === "b" && key.ctrl) {
        consume()
        setBranchOnly((v) => !v)
        return
      }
      if (key.name === "return") {
        // 这里**必须**消费掉：不消费的话输入框会把搜索词当成一句话提交给模型
        consume()
        const row = rows[pickIndex()]
        if (row) leavePicker(row.id)
        else session.notice("info", "没有可挑的会话。改几个字，或者 Esc 取消。")
        return
      }
      return
    }

    // 命令菜单开着时：↑↓ 选、Tab 只补名字、Enter 执行高亮那条。
    //
    // 代价是得自己清输入框（回车被消费了，输入框不会自己清），
    // 换来的是「敲了斜杠、看着高亮、按回车」这条最自然的路真的通——
    // 理由见 acceptHighlighted 上面那段
    const menu = candidates()
    if (menu.length > 0) {
      if (key.name === "up") {
        consume()
        setPicked((i) => (i - 1 + menu.length) % menu.length)
        return
      }
      if (key.name === "down") {
        consume()
        setPicked((i) => (i + 1) % menu.length)
        return
      }
      if (key.name === "tab") {
        consume()
        const entry = menu[picked()]
        // 带参数的命令补完留个空格，光标正好停在该打参数的地方
        if (entry) inputControls?.setValue(entry.name + (entry.args ? " " : ""))
        return
      }
      // 回车 = 就选高亮这条。必须消费掉：不消费的话输入框会把敲了一半的
      // 「/c」当成一句话提交，而那句话对模型毫无意义
      //
      // 但**带修饰键的回车要放过去**：那是换行（见 PromptInput 的 BINDINGS）。
      // 不分开的话，菜单一开着就再也换不了行，而菜单是打一个 / 就弹出来的
      if (key.name === "return" && !isNewlineKey(key)) {
        consume()
        acceptHighlighted()
        return
      }
    }

    /**
     * Shift+Tab 切模式。
     *
     * <p>放在菜单那一段**后面**：命令菜单开着时 Tab 是补全，那是更近的上下文。
     * 两者不冲突——补全那条分支只认不带 shift 的 Tab。
     *
     * <p>OpenTUI 把 `ESC[Z`（CSI Z）解析成 `{name:"tab", shift:true}`，所以这里认得到。
     * 必须 consume：不消费的话输入框会收到同一个 Tab，在文本里插一个制表符。
     */
    if (key.name === "tab" && key.shift) {
      consume()
      session.cycleMode()
      return
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
          <scrollbox
            stickyScroll
            stickyStart="bottom"
            ref={(r: ScrollBoxRenderable) => {
              transcriptScroll = r
            }}
          >
            {/* 欢迎框在**滚动容器里面**，是对话流的第一项而不是固定页头。
                钉在顶上的话，聊到第十轮它还占着五分之一个屏幕，
                而那时它显示的信息（版本、工具数）早就没人看了。
                想回看开场信息往上滚就是了。 */}
            <Welcome
              info={{
                version: state().ready?.version ?? "…",
                // ready 之前显示启动进度（build 中/启动中）。最长的等待是 mvn，
                // 五秒起步，不写进度的话就是一个看起来卡死的空界面
                model: state().ready?.model ?? state().bootStatus,
                toolCount: state().ready?.tools.length ?? 0,
                workspace: workspace(),
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
                  columns={dimensions().width - 2}
                  expanded={state().expanded.has(entry().key)}
                  onToggle={() => toggleToolEntry(entry().key)}
                />
              )}
            </Index>
          </scrollbox>
        </box>

        {/* 上次没跑完的计划。摆在输入框上方而不是塞进欢迎框里——
            欢迎框会被对话顶上去滚走，而这条得一直看得见：
            后端在「被叫停」之后是留着存档的，用户随时可能回来接着跑 */}
        <Show when={canResume() && !state().busy}>
          <box flexShrink={0} paddingLeft={1} flexDirection="row">
            <text fg={dark.warning}>⏸ 上次有一份计划没跑完：</text>
            <text fg={dark.text}>{state().ready!.resumable!.goal}</text>
            <text fg={dark.subtle}>
              （还剩 {state().ready!.resumable!.remaining} /{" "}
              {state().ready!.resumable!.total} 步，输入 /resume-plan 接着跑）
            </text>
          </box>
        </Show>

        {/* 上次没聊完的对话，摆在计划那条下边。两条可能同时在——
            一个续计划、一个续对话，各说各的，不该互相顶掉 */}
        <Show when={canContinue() && !state().busy}>
          <box flexShrink={0} paddingLeft={1} flexDirection="row">
            {/* 报总数而不是只说「上次聊过」：存档已经是一次会话一份，
                「上次」这个说法的前提（只有一段）不成立了 */}
            <text fg={dark.warning}>⏸ 这个工作区有 </text>
            <text fg={dark.text}>{state().ready!.continuable!.count ?? 1} 段会话</text>
            <text fg={dark.subtle}>
              ，最近一段「{state().ready!.continuable!.firstMessage}」
              {state().ready!.continuable!.turns} 轮
              {state().ready!.continuable!.branch
                ? ` · ${state().ready!.continuable!.branch}`
                : ""}
              （/continue 接最近那段）
            </text>
          </box>
        </Show>

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
              <ApprovalDialog
                approval={approval()}
                stage={state().approvalStage}
                columns={dimensions().width - 2}
                rows={dimensions().height}
              />
            </box>
          )}
        </Show>
        <Show when={!state().approval && state().planConfirm}>
          {(confirm) => (
            <box flexShrink={0} paddingLeft={1} paddingRight={1}>
              <PlanConfirmDialog choice={confirm().choice} />
            </box>
          )}
        </Show>

        {/* 会话选择器。和命令菜单一样钉在输入框上方，两个不会同时出现 */}
        <Show when={picker()}>
          <SessionPicker
            rows={pickerRows()}
            total={picker()!.sessions.length}
            selected={pickIndex()}
            currentId={picker()!.currentId}
            filter={pickerFilter()}
            columns={dimensions().width}
            maxRows={pickerMaxRows()}
            now={pickerNow()}
          />
        </Show>

        {/* 命令菜单钉在输入框**上方**：往下长会把输入框挤下去，
            而输入框的位置是打字时眼睛的锚点，不能动 */}
        <CommandMenu entries={candidates()} selected={picked()} columns={dimensions().width - 2} />

        <PromptInput
          placeholder={
            pending()
              ? "上面等你回答…"
              : picker()
                ? "输入即搜索（标题或分支）· ↑↓ 选 · Enter 接着聊 · Esc 取消"
                : state().busy
                  ? "正在跑…（Esc 或 Ctrl-C 中断）"
                  : "交代一件事，比如「把 README 里的启动命令补全」"
          }
          busy={state().busy}
          suspended={pending()}
          onValueChange={setDraft}
          matched={matched()}
          controls={(api) => (inputControls = api)}
          onSubmit={submit}
        />

        {/* 「再按一次就退出」。贴着输入框下面，不进转录——
            它描述的是**此刻的键盘状态**，不是发生过的事；
            混进转录里就成了一条永远留在那儿的假消息 */}
        <Show when={exitArmed()}>
          <box flexShrink={0} paddingLeft={1} paddingRight={1}>
            <text fg={dark.warning}>再按一次 Ctrl-C 退出</text>
          </box>
        </Show>

        {/* 复制回执。和上面那行一样不进转录，理由见 copied 那个信号 */}
        <Show when={copied()}>
          {(hint) => (
            <box flexShrink={0} paddingLeft={1} paddingRight={1}>
              <text fg={hint().ok ? dark.inactive : dark.warning}>{hint().text}</text>
            </box>
          )}
        </Show>

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

        {/* 流水线面板，和计划面板同一个位置——两者不会同时出现（后端一次只跑一件事） */}
        <Show when={activeTeam()}>
          {(team) => (
            <box flexShrink={0} paddingLeft={1} paddingRight={1}>
              <TeamPanel team={team()} columns={dimensions().width - 2} />
            </box>
          )}
        </Show>

        <StatusLine
          model={state().ready?.model ?? "未连接"}
          workspace={workspace()}
          branch={state().ready?.branch ?? undefined}
          columns={dimensions().width}
          busy={state().busy}
          link={linkText()}
          tokens={state().tokens}
          contextLimit={state().contextLimit}
        />

        {/* 模式行贴在状态栏下面，和模型名同一列起头。
            在最下面而不是输入框上面：它描述的是「下一句话怎么跑」，
            眼睛从输入框往下扫的第一站就该是它 */}
        <ModeLine mode={state().mode} columns={dimensions().width} />
      </box>
    </ThemeProvider>
  )
}

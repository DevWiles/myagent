/**
 * 按下 Ctrl-C 该发生什么。
 *
 * ## 为什么是一个纯函数，而不是写在按键回调里
 *
 * 和 `commands.ts` 同一个理由：这里的判断有六个分支、要看五样状态，
 * 组合数远超「在界面上手动敲一遍」能覆盖的范围。抽成纯函数之后，
 * 每一条都能用断言钉住，而 App 那边只剩一行 switch。
 *
 * ## 规则是从终端版抄来的，不是新发明的
 *
 * `Repl` 那边早就是对的（`LineReader` 的 `CTRL_C` 分支 + `consecutiveInterrupts`）：
 * 跑着就中断、有字就清行、空行连按两次才退。两个界面在这种最基本的键位上
 * 行为不一致，是比任何一种行为本身都更糟的事——用户换个界面就得重新学一遍手感。
 *
 * ## 「连按两次」的有效期
 *
 * 武装状态有两种解除方式：**按了别的键**（和终端版 `consecutiveInterrupts`
 * 被下一次成功读行清零是同一套语义），以及**{@link ARM_TIMEOUT_MS} 毫秒没动静**。
 *
 * <p>超时这一条要成立，全靠 {@link CtrlCContext.armed} 和界面上那行提示是
 * **同一个信号**：时间一到，武装解除的同时那行字也消失了。
 * 两者要是各存各的，就会出现「提示还在屏幕上、按下去却只是重新武装一次」——
 * 那行字开始说谎，比没有超时糟得多。加超时的时候<b>先确认这一点还成立</b>。
 *
 * <p>为什么需要这条：只靠「按别的键」解除，武装状态能无限期挂着。
 * 中断一轮之后人走开了，回来想再中断点什么，按下去的却是退出——
 * 而他上一次按 Ctrl-C 可能是十分钟前的事。
 */

/**
 * 武装多久没动静就自动解除。
 *
 * <p>4 秒是「刻意连按两下」和「隔了一会儿又按一下」之间的分界：
 * 真想退出的人不会在两下之间停 4 秒，而停了 4 秒的人多半已经在想别的事了。
 */
export const ARM_TIMEOUT_MS = 4000

export type CtrlCContext = {
  /** 工具审批框开着——此刻后端的 agent 线程正阻塞在等回答上。 */
  hasApproval: boolean
  /** 计划确认框开着，同上。 */
  hasPlanConfirm: boolean
  /** 会话选择器开着。 */
  pickerOpen: boolean
  /** 正在跑一轮。 */
  busy: boolean
  /** 输入框里此刻的内容。 */
  draft: string
  /**
   * 上一次按的也是 Ctrl-C，当时就已经是「空行 + 闲着」，而且那一下还没过期。
   *
   * <p>过期和「按了别的键」由 App 负责清掉——这个函数只看此刻武装没武装。
   */
  armed: boolean
}

export type CtrlCAction =
  /** 当成「不同意」，把卡着的那一轮放行。 */
  | { kind: 'decline-approval' }
  /** 当成「不执行这份计划」。 */
  | { kind: 'decline-plan' }
  /** 收起会话选择器。 */
  | { kind: 'close-picker' }
  /** 中断当前这一轮，会话继续。 */
  | { kind: 'interrupt' }
  /** 清掉输入框这一行，和 shell 一致。 */
  | { kind: 'clear-input' }
  /** 亮出「再按一次 Ctrl-C 退出」。 */
  | { kind: 'arm' }
  /** 真的退。 */
  | { kind: 'exit' }

/**
 * 顺序就是优先级，从「最局部」到「最彻底」。
 *
 * 两个确认框排在最前面不是随手排的：它们挂着的时候后端<b>正阻塞在等回答上</b>，
 * 而那个分支在 App 里会把其余按键全部吞掉。Ctrl-C 要是不在这儿接，
 * 用户就会陷进一个既答不了、也退不出的框里。
 *
 * `busy` 排在 `draft` 前面：跑着的时候用户心里想停的是<b>那一轮</b>，
 * 不是他顺手打了一半的下一句话——把草稿清掉是白白毁掉他的输入。
 */
export function decideCtrlC(ctx: CtrlCContext): CtrlCAction {
  if (ctx.hasApproval) return { kind: 'decline-approval' }
  if (ctx.hasPlanConfirm) return { kind: 'decline-plan' }
  if (ctx.pickerOpen) return { kind: 'close-picker' }
  if (ctx.busy) return { kind: 'interrupt' }
  if (ctx.draft !== '') return { kind: 'clear-input' }
  return ctx.armed ? { kind: 'exit' } : { kind: 'arm' }
}

/**
 * 这个动作之后，「再按一次就退出」该不该继续亮着。
 *
 * 只有 `arm` 让它亮。其余每一种都意味着这次 Ctrl-C <b>干了别的事</b>——
 * 中断了一轮、清了一行、收了个框——那么紧接着的第二下就不该是退出，
 * 用户按第二下想的是「刚才那下没生效？」而不是「我要走」。
 */
export function armedAfter(action: CtrlCAction): boolean {
  return action.kind === 'arm'
}

/**
 * 武装状态那根定时器的记账。
 *
 * <p>抽出来是因为这十行里有一处<b>很难靠肉眼验的</b>错法：武装时忘了先把
 * 上一根定时器掐掉。漏了的话，上一次武装留下的定时器会在新的武装期间到点，
 * 把刚亮起来的提示掐灭——表现是「提示闪一下就没了」，而复现它要求
 * 两次 Ctrl-C 之间隔的时间不多不少正好落在窗口里。这种东西只能用断言钉。
 *
 * @param onChange 状态变了就调一次，界面那个信号挂在这上面
 * @param timeoutMs 多久没动静自动解除。留给测试压到几毫秒
 */
export function createArmState(
  onChange: (armed: boolean) => void,
  timeoutMs: number = ARM_TIMEOUT_MS,
) {
  let timer: ReturnType<typeof setTimeout> | undefined

  const clear = () => {
    if (timer !== undefined) {
      clearTimeout(timer)
      timer = undefined
    }
  }

  return {
    /** 武装或解除。武装会重新计时——每一次都是「从这一下开始算 4 秒」。 */
    set(armed: boolean): void {
      clear()
      onChange(armed)
      if (armed) {
        timer = setTimeout(() => {
          timer = undefined
          onChange(false)
        }, timeoutMs)
      }
    },
    /** 组件卸载时收摊，别让定时器拖着一个已经没了的界面。 */
    dispose(): void {
      clear()
    },
  }
}

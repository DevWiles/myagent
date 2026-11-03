/**
 * 终端标签页标题：状态图标 + 这段会话的摘要。
 *
 * 开着好几个标签页时，图标的颜色就是一句话：蓝色一切正常，橙色轮到你了，红色出事了。
 * 用 emoji 是要它的颜色：标签页标题只能放字符、没法指定颜色，emoji 自带颜色。
 * 蓝色和欢迎页的 MYAGENT 招牌同色。
 * 摘要由后端让模型概括（session_title），还没起好时先写 myagent。
 */

import type { SessionState } from "./store.ts"

export type TabStatus = "idle" | "busy" | "waiting" | "error"

/** 空闲：一颗蓝宝石。 */
export const IDLE_ICON = "💠"
/** 正在跑：宝石、大菱形、小菱形、大菱形，一明一暗地呼吸。 */
export const BUSY_FRAMES = ["💠", "🔷", "🔹", "🔷"]
/** 卡在审批或计划确认上，在等用户。橙色在一排蓝色标签里最显眼 */
export const WAITING_ICON = "🔶"
/** 断线、后端退了、上一轮没跑完就失败了 */
export const ERROR_ICON = "🔺"
/** 多久换一帧。标题栏刷太勤，有的终端会闪 */
export const FRAME_MS = 300

/**
 * 眼下该亮哪种。出错压过一切：断着线的时候，审批框也答不出去。
 * 用户自己按 Esc 叫停的不算出错，那是他要的结果。
 */
export function tabStatus(
  s: Pick<SessionState, "busy" | "approval" | "planConfirm" | "link" | "closed" | "interrupted">,
): TabStatus {
  const failed = !s.busy && s.interrupted !== null && s.interrupted.reason !== "已取消"
  if (s.closed !== null || s.link.phase === "reconnecting" || failed) return "error"
  if (s.approval !== null || s.planConfirm !== null) return "waiting"
  return s.busy ? "busy" : "idle"
}

/**
 * @param title  会话摘要；null 或空就用默认名
 * @param status 见 {@link tabStatus}
 * @param frame  第几帧，只在 busy 时有意义
 */
export function tabTitle(title: string | null | undefined, status: TabStatus, frame = 0): string {
  const icon =
    status === "busy" ? BUSY_FRAMES[frame % BUSY_FRAMES.length]
      : status === "waiting" ? WAITING_ICON
        : status === "error" ? ERROR_ICON
          : IDLE_ICON
  // 摘要是模型写的，控制字符（ESC、BEL）混进标题序列会把终端搞乱，一律去掉
  const clean = (title ?? "").replace(/[\u0000-\u001f\u007f]/g, " ").trim()
  return `${icon} ${clean || "myagent"}`
}

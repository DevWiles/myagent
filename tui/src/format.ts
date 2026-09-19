/**
 * 文案与格式化。
 *
 * 措辞刻意和 Java 版的 ToolSummary / Logo 保持一致——两套前端共用一个后端，
 * 说法不一样会让人以为是两个程序。
 */

import type { ToolCall } from './store.ts'

/** 工具名 → [动词, 量词]。没登记的退回通用说法。 */
const WORDING: Record<string, [string, string]> = {
  read_file: ['读取', '个文件'],
  write_file: ['写入', '个文件'],
  list_dir: ['列出', '个目录'],
  bash: ['执行', '条命令'],
  create_project: ['创建', '批文件'],
}

/**
 * 把一组工具调用概括成一句话：「读取 3 个文件 · 执行 1 条命令」。
 *
 * 顺序按首次调用出现的先后，不排序——用户脑子里的顺序就是发生顺序。
 */
export function summarizeTools(calls: readonly ToolCall[]): string {
  const counts = new Map<string, number>()
  for (const call of calls) {
    counts.set(call.tool, (counts.get(call.tool) ?? 0) + 1)
  }
  const parts: string[] = []
  for (const [tool, count] of counts) {
    const wording = WORDING[tool]
    parts.push(wording ? `${wording[0]} ${count} ${wording[1]}` : `调用 ${count} 次 ${tool}`)
  }
  return parts.join(' · ')
}

/** 毫秒 → 人话。超过一分钟就不再显示小数了，没人关心。 */
export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  const seconds = ms / 1000
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  const minutes = Math.floor(seconds / 60)
  return `${minutes}m${Math.round(seconds % 60)}s`
}

export function formatTokens(count: number): string {
  return count >= 1000 ? `${(count / 1000).toFixed(1)}k` : String(count)
}

/**
 * 一个时刻 → 人话：`刚刚`、`12 分钟前`、`昨天 14:32`、`9-16 09:15`。
 *
 * <p>近的说相对、远的说绝对。相对时间在近处精确、在远处失真——「237 小时前」
 * 谁也换算不出来是哪天；所以 6 小时内（「同一段工作」的尺度）说相对，
 * 跨过去就报日期时间，让人自己认那天在干什么。
 *
 * <p>`now` 由调用方传，否则这个函数没法测，而它每条分支都是差一点就错的边界。
 * 和 Java 那侧的 `Moment` 是同一套规矩——两边显示同一份存档，说法不该不一样。
 */
export function formatRelativeTime(when: number, now: number): string {
  if (!when || when <= 0) return "时间不明"
  const seconds = Math.floor((now - when) / 1000)
  // 负数也走这条：时钟回拨、存档从别的机器拷过来，都会让「现在」比存档还早。
  // 说「刚刚」不准，但「-3 小时前」是纯粹的乱码
  if (seconds < 60) return "刚刚"
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 6 * 3600) return `${Math.floor(seconds / 3600)} 小时前`

  const then = new Date(when)
  const today = new Date(now)
  const hhmm = `${String(then.getHours()).padStart(2, "0")}:${String(then.getMinutes()).padStart(2, "0")}`
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()

  if (sameDay(then, today)) return `今天 ${hhmm}`
  const yesterday = new Date(now - 24 * 3600 * 1000)
  if (sameDay(then, yesterday)) return `昨天 ${hhmm}`
  if (then.getFullYear() === today.getFullYear()) {
    return `${then.getMonth() + 1}-${then.getDate()} ${hhmm}`
  }
  // 跨年就不报时刻了：隔了这么久，具体几点几分帮不上任何决定
  return `${then.getFullYear()}-${then.getMonth() + 1}-${then.getDate()}`
}

/**
 * 字节数 → 人话。
 *
 * <p>存档大小是「这段聊得有多沉」的便宜代理：几十 KB 是随手问两句，
 * 几百 KB 是啃了半天。所以一位小数就够，没人需要知道是 33.4 还是 33.44KB。
 */
export function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return "0B"
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}

/**
 * 截到指定宽度，超出部分用 … 收尾。
 *
 * 按 code point 而不是 UTF-16 code unit 切，否则会把 emoji 劈成两半变成乱码。
 * 注意这里没做东亚字符的双宽处理——中文会被算成 1 列，实际占 2 列。
 * 对「一行摘要」这种场景够用，真要做对齐得引入字宽表。
 */
export function truncate(text: string, max: number): string {
  const chars = [...text]
  return chars.length <= max ? text : chars.slice(0, Math.max(0, max - 1)).join('') + '…'
}

/** 把多行内容压成一行，用于折叠显示。 */
export function firstLine(text: string, max: number): string {
  const line = text.split('\n').find(l => l.trim().length > 0) ?? ''
  return truncate(line.trim(), max)
}

/**
 * ASCII art 招牌。ANSI Shadow 风格，6 行，每行严格 64 列——
 * 这些都是 Block Elements / Box Drawing 字符，每个占一列宽，差一个就错位。
 */
export const LOGO_LINES = [
  '███╗   ███╗██╗   ██╗ █████╗  ██████╗ ███████╗███╗   ██╗████████╗',
  '████╗ ████║╚██╗ ██╔╝██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝',
  '██╔████╔██║ ╚████╔╝ ███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║   ',
  '██║╚██╔╝██║  ╚██╔╝  ██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║   ',
  '██║ ╚═╝ ██║   ██║   ██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║   ',
  '╚═╝     ╚═╝   ╚═╝   ╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝   ',
]

export const LOGO_WIDTH = 64
/** 窄于这个宽度就别画了，画出来会换行，比不画还难看。 */
export const LOGO_MIN_WIDTH = LOGO_WIDTH + 8

/** 转圈动词。等模型响应时轮换，让人知道程序还活着。 */
export const SPINNER_VERBS = [
  '思考中', '推敲中', '琢磨中', '盘算中', '梳理中',
  '翻找中', '推理中', '斟酌中', '演算中', '构思中',
]


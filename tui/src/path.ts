/**
 * 路径显示工具。
 *
 * 全部按**显示宽度**而不是字符串长度计算。这是终端里最容易踩的坑：
 * `"（未连接后端）".length === 7`，但它在终端里占 **14 列**——中日韩字符是双宽的。
 * 用 `.length` 算预算，界面就会在有中文时莫名其妙折行。
 */

import { homedir } from "node:os"
import stringWidth from "string-width"

/** 一段文字在终端里占几列。emoji、CJK、组合字符都算对。 */
export function width(text: string): number {
  return stringWidth(text)
}

/** `/Users/me/x` → `~/x`。纯显示用，别拿去 open 文件。 */
export function tildify(path: string): string {
  const home = homedir()
  if (path === home) return "~"
  if (path.startsWith(home + "/")) return "~" + path.slice(home.length)
  return path
}

/**
 * 截断到 max 列，**保留开头**并在尾巴加省略号。
 *
 * 和 truncateStart 相反，用在「前面是重点」的文字上：动作名、原因说明。
 * format.ts 里那个同名函数是按字符数算的，在有中文时会短一截，所以另写一个。
 */
export function truncateEnd(text: string, max: number): string {
  if (max <= 0) return ""
  if (width(text) <= max) return text
  if (max <= 1) return "…"

  const budget = max - 1
  const chars = Array.from(text)
  let used = 0
  let i = 0
  while (i < chars.length) {
    const w = width(chars[i]!)
    if (used + w > budget) break
    used += w
    i++
  }
  return chars.slice(0, i).join("") + "…"
}

/**
 * 截断到 max 列，**保留尾部**并在头上加省略号。
 *
 * 为什么保留尾部：路径越靠后信息量越大。
 * `/Users/me/workspace/projects/...` 这个前缀对用户毫无意义，
 * 真正要认的是最后那两段目录名。
 */
export function truncateStart(text: string, max: number): string {
  if (max <= 0) return ""
  if (width(text) <= max) return text
  if (max <= 1) return "…"

  // 从尾巴往前吃，吃到装不下为止。逐字符量宽度是因为双宽字符可能正好卡在边界上，
  // 按长度切会切出半个字，终端上表现为一个乱码方块。
  const budget = max - 1 // 省略号自己占 1 列
  const chars = Array.from(text)
  let used = 0
  let i = chars.length
  while (i > 0) {
    const w = width(chars[i - 1]!)
    if (used + w > budget) break
    used += w
    i--
  }
  return "…" + chars.slice(i).join("")
}

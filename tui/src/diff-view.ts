/**
 * 把后端的统一 diff 解析成**带行号的行**，供界面按行上色。
 *
 * <h3>为什么行号要在这儿算，而不是让后端发过来</h3>
 * 统一 diff 的格式里本来就带着这个信息：hunk 抬头 `@@ -14 +15 @@` 说的是
 * 「这一段从旧文件第 14 行、新文件第 15 行开始」，之后每一行是增是删决定了
 * 两个计数各自走不走。把它算出来是十几行代码，而让后端多发一份行号
 * 意味着协议里多一个和 diff 正文冗余的字段——两份数据描述同一件事，
 * 迟早会对不上。
 *
 * <h3>显示哪个行号</h3>
 * 删除行显示**旧**文件的行号，新增行显示**新**文件的行号，上下文行显示新文件的。
 * 这是 git 和各家 IDE 的通行做法：你要跳去改代码，跳的是新文件。
 *
 * <p>后端的格式见 {@code TextDiff.unified}：抬头是 `@@ -旧 +新 @@`，
 * 正文每行带两字符前缀（`  ` 上下文 / `- ` 删 / `+ ` 增）。
 */

import { diffLineKind, type DiffLineKind } from "./approval.ts"

export type DiffRow = {
  kind: DiffLineKind
  /** 旧文件行号；新增行没有 */
  oldNo: number | null
  /** 新文件行号；删除行没有 */
  newNo: number | null
  /** 去掉前缀之后的正文。hunk 抬头保留原样 */
  text: string
  /** 行首那个记号：`+` / `-` / 空格。hunk 抬头是空串 */
  sign: string
}

const HUNK = /^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@/

/**
 * 解析统一 diff。认不出的行按上下文处理——**宁可少上色，也不能少显示**。
 */
export function parseUnified(diff: string): DiffRow[] {
  const rows: DiffRow[] = []
  let oldNo = 0
  let newNo = 0

  for (const line of diff.split("\n")) {
    const hunk = HUNK.exec(line)
    if (hunk) {
      oldNo = Number(hunk[1])
      newNo = Number(hunk[2])
      rows.push({ kind: "hunk", oldNo: null, newNo: null, text: line, sign: "" })
      continue
    }
    // 末尾那个空串是 split 的产物，不是内容
    if (line === "") continue

    const kind = diffLineKind(line)
    const text = line.slice(2)        // 前缀恒定两字符，见 TextDiff.unified
    if (kind === "add") {
      rows.push({ kind, oldNo: null, newNo: newNo++, text, sign: "+" })
    } else if (kind === "del") {
      rows.push({ kind, oldNo: oldNo++, newNo: null, text, sign: "-" })
    } else {
      rows.push({ kind: "context", oldNo: oldNo++, newNo: newNo++, text, sign: " " })
    }
  }
  return rows
}

/**
 * 按预算裁行。**裁了多少要报出来**——悄悄少给的时候，人以为自己看到了全部。
 *
 * <p>只数真正的改动行（增/删）来判断要不要裁：一段 diff 里上下文行往往比改动还多，
 * 按总行数裁的话，用户看到的常常是几行没变的代码加一句「还有 20 行没显示」。
 */
export function visibleRows(rows: DiffRow[], budget: number): {
  rows: DiffRow[]
  hidden: number
} {
  const room = Math.max(1, budget)
  if (rows.length <= room) return { rows, hidden: 0 }
  // 留一行给「还有多少没显示」那句话，否则它自己会把内容再挤掉一行
  const shown = rows.slice(0, Math.max(1, room - 1))
  return { rows: shown, hidden: rows.length - shown.length }
}

/**
 * 行号那一栏占几个字符。
 *
 * <p>按最大行号算，最少三位——一位两位的话，文件一长列宽就会变，
 * 同一块 diff 里代码的起始列跟着抖，读起来很难受。
 */
export function gutterWidth(rows: DiffRow[]): number {
  let widest = 0
  for (const row of rows) {
    widest = Math.max(widest, row.oldNo ?? 0, row.newNo ?? 0)
  }
  return Math.max(3, String(widest).length)
}

/** 这一行左边该显示什么行号。hunk 抬头不显示。 */
export function lineNoOf(row: DiffRow): string {
  if (row.kind === "hunk") return ""
  // 删除行看旧文件，其余看新文件——要跳去改代码，跳的是新文件
  const no = row.kind === "del" ? row.oldNo : row.newNo
  return no === null ? "" : String(no)
}

/**
 * 粘贴大段文字时折成一个占位符，照 Claude Code 的 `[Pasted text #1 +20 lines]`。
 *
 * 不折会怎样：贴一屏终端输出进来，输入框长到顶，光标和刚打的那半句话被挤得看不见；
 * 发出去之后转录里又是一整屏灰底，往上翻找自己说过什么全被它淹掉。
 *
 * 规矩：
 * - 输入框和转录里显示占位符，发给模型的是展开后的全文
 * - 占位符被删掉一个字就对不上了，那段就不展开——和 Claude Code 一样，删了就是不要了
 * - 短的（两行以内且不长）照常插进去，折起来反而要多看一眼才知道贴了什么
 */

/** 超过这么多行就折。 */
export const FOLD_MIN_LINES = 3
/** 一行也很长的（压成一行的 JSON、日志）超过这么多字也折。 */
export const FOLD_MIN_CHARS = 800

/** 终端粘贴常带 CRLF，统一成 LF 再数行、再存。 */
export function normalizePaste(text: string): string {
  return text.replace(/\r\n?/g, "\n")
}

export function lineCount(text: string): number {
  return text.replace(/\n+$/, "").split("\n").length
}

export function shouldFold(text: string): boolean {
  return lineCount(text) >= FOLD_MIN_LINES || text.length > FOLD_MIN_CHARS
}

export function pastePlaceholder(id: number, text: string): string {
  const lines = lineCount(text)
  return lines > 1 ? `[粘贴的文本 #${id} +${lines} 行]` : `[粘贴的文本 #${id} ${text.length} 字]`
}

/** 一次输入里攒下的几段粘贴。提交或清空输入框时整个扔掉。 */
export class PasteStore {
  private seq = 0
  private readonly texts = new Map<string, string>()

  /** 记下一段，返回该插进输入框的占位符。 */
  add(text: string): string {
    const placeholder = pastePlaceholder(++this.seq, text)
    this.texts.set(placeholder, text)
    return placeholder
  }

  /** 把还完整留在输入里的占位符换回原文。 */
  expand(value: string): string {
    let out = value
    for (const [placeholder, text] of this.texts) {
      out = out.split(placeholder).join(text)
    }
    return out
  }

  clear(): void {
    this.seq = 0
    this.texts.clear()
  }
}

/**
 * 流式 Markdown 的分块器。
 *
 * ## 为什么需要它
 *
 * 最省事的写法是 `buffer += chunk; render(parse(buffer))`——每来一个 token
 * 就把整段文本从头解析一遍。它能跑，但长回答会越来越卡：
 *
 * ```
 * 第 1 次：parse 100 字
 * 第 2 次：parse 101 字
 * ...
 * 第 1000 次：parse 1000 字
 * ```
 *
 * 等于把已经解析过的内容反复重算。更要命的是在 TUI 里，重新解析意味着
 * **重建整棵渲染树**，屏幕上已经画好的内容会被整片重画——这就是闪烁的来源。
 *
 * 正确的做法是把文本切成块，**已经写完的块不再解析**，只有最后那个还在长的块
 * 持续更新：
 *
 * ```
 * ┌─────────┐ ┌───────────┐ ┌────────────┐
 * │ 标题 ✓  │ │ 段落 ✓    │ │ 代码块 ⏳  │ ← 只有它在动
 * └─────────┘ └───────────┘ └────────────┘
 * ```
 *
 * ## 切在哪
 *
 * 块边界是空行。但**不能简单地按空行切**——围栏代码块内部可以有空行：
 *
 * ````md
 * ```java
 * int a = 1;
 *                  ← 这个空行属于代码块，不是块边界
 * int b = 2;
 * ```
 * ````
 *
 * 所以要一边扫描一边记录「当前在不在围栏里」。
 */

/** 围栏行：``` 或 ~~~ 开头（前面允许有缩进）。 */
const FENCE_LINE = /^\s{0,3}(```|~~~)/

/**
 * 列表项的起始行：`- foo`、`* foo`、`+ foo`、`1. foo`、`2) foo`，允许缩进（嵌套）。
 *
 * **每个列表项各自是一个块**，这和 Notion 的 block 模型一致。
 * 不这么切的话，一个「**有几点：**」带十条的列表之间没有任何空行，
 * 整坨算一个块——要等最后一条写完才一次性落下来一大片，
 * 而且在那之前它一直以纯文本形式挂在屏幕上露着 `1.`、`**` 这些标记。
 */
const LIST_ITEM_LINE = /^\s*([-*+]|\d+[.)])\s/

/** 这一行是不是一个列表项的开头。给外部算块间距用。 */
export function isListItem(text: string): boolean {
  return LIST_ITEM_LINE.test(text)
}

export type MarkdownBlocks = {
  /** 已经写完、不会再变的块。每个块自己就是一段合法的 markdown。 */
  complete: string[]
  /** 还在写的那一块。空串表示刚好停在块边界上。 */
  current: string
}

/**
 * 把流式文本切成「已完成的块」和「正在写的块」。
 *
 * 判定「已完成」的依据是**后面已经出现了空行**——空行一到，
 * 前面那个块就不可能再长了，可以固化。围栏没闭合的代码块永远算「正在写」，
 * 因为收尾的围栏还没到，块的边界还没确定。
 */
export function splitBlocks(text: string): MarkdownBlocks {
  const lines = text.split("\n")
  const complete: string[] = []

  let block: string[] = []
  let inFence = false
  let fenceMarker = ""

  const flush = (): void => {
    // 去掉块尾的空行，但块本身的内容原样保留
    while (block.length > 0 && block[block.length - 1]!.trim() === "") block.pop()
    if (block.length > 0) complete.push(block.join("\n"))
    block = []
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!
    const isLast = i === lines.length - 1

    const fence = FENCE_LINE.exec(line)
    if (fence) {
      const marker = fence[1]!
      if (!inFence) {
        // 开围栏前先把前面攒着的内容收成一块
        if (block.some((l) => l.trim() !== "")) flush()
        inFence = true
        fenceMarker = marker
        block.push(line)
        continue
      }
      if (marker === fenceMarker) {
        // 收围栏：代码块到此完整
        block.push(line)
        inFence = false
        fenceMarker = ""
        // 但先别 flush——后面可能紧跟着空行，等空行来了再收，
        // 这样「代码块 + 紧随其后的空行」不会被拆成两次更新
        continue
      }
    }

    // 新的列表项开始 → 上一块（前面的段落，或上一个列表项）到此为止。
    // 列表项之间通常没有空行，光靠空行切的话整张列表会变成一个块。
    //
    // 这一步必须排在 isLast 前面：正在写的那个列表项本身就是最后一行，
    // 如果先走 isLast 分支直接 push，上一个列表项就没机会收尾，
    // 会和正在写的这条黏成一团留在 current 里。
    if (!inFence && LIST_ITEM_LINE.test(line) && block.some((l) => l.trim() !== "")) {
      flush()
    }

    if (isLast) {
      // `"abc\n".split("\n")` 的最后一个元素是空串，那是行尾的换行符，
      // **不是空行**。把它当空行会让「刚写完一行」被误判成「块已结束」，
      // 而段落是可以跨行的（软换行），下一行可能还要接上去。
      if (line === "") break
      // 最后一行没有换行符收尾，说明它还在写
      block.push(line)
      break
    }

    if (!inFence && line.trim() === "") {
      flush()
      continue
    }

    block.push(line)
  }

  // 剩下的就是还在写的那块。围栏没闭合的话，整块都还在写
  while (block.length > 0 && block[0]!.trim() === "") block.shift()
  return { complete, current: block.join("\n") }
}

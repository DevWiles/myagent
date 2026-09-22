/**
 * 围栏代码块的语法高亮。
 *
 * <h3>为什么不用 OpenTUI 自带的那条路</h3>
 * OpenTUI 的 `<markdown>` 把围栏块交给 `CodeRenderable`，后者靠 **tree-sitter**。
 * 那条路在这里走不通，两道坎叠在一起：
 * <ol>
 *   <li>它只捆了 5 个 parser（js / ts / markdown / markdown_inline / zig），
 *       **没有 java、python、go、rust**。每加一门语言要带一个 400KB~1.4MB 的 wasm</li>
 *   <li>更硬的一道：markdown parser 的 `infoStringMap` 只认 `js/jsx/ts/tsx/md` 这几种围栏标记，
 *       其它语言的 ``` 块**压根不会被注入高亮**，整块落回一个平色</li>
 * </ol>
 *
 * <h3>为什么是 highlight.js</h3>
 * 查了 Claude Code 的实现（2.1.226 的二进制里能读出来）：它也是 **highlight.js**，
 * 不是 tree-sitter；插件扩展语言走的也是 hljs grammar。理由不难理解——
 * 纯 JS、无二进制资源、天然离线可用，而终端要的精度本来就只是
 * 「一眼分得清注释、字符串和关键字」，不是 IDE 级的语义着色。
 *
 * <p>用 `lib/common`（36 种常用语言）而不是全量：全量约 190 种，
 * 多出来的那 150 种（1C:Enterprise、AVR 汇编…）这辈子也不会在这个界面里出现，
 * 而它们要一起被解析进内存。
 *
 * <h3>走 token 树，不解析 HTML</h3>
 * `hljs.highlight()` 的返回值里 `value` 是一段 HTML。拿它去正则抠 `<span>` 是能做，
 * 但要处理 HTML 实体转义（`&lt;` `&amp;`），而代码里恰恰全是这些字符——
 * 少转一个就会在屏幕上显示成 `&amp;&amp;`。`_emitter.rootNode` 直接给结构化的 token 树，
 * 一个字都不用解码。
 *
 * <p>代价是 `_emitter` 带下划线、属于半私有 API，升级 hljs 可能变形。
 * 所以 {@link highlightCode} **认不出结构就整段退回纯文本**，绝不抛异常——
 * 高亮没了只是难看一点，界面崩了是另一回事。{@link treeShapeIsIntact} 把这个形状
 * 单独钉了一条测试，依赖一升级就会红，而不是等用户发现代码块全变白。
 */

import hljs from "highlight.js/lib/common"

/** 一段同色的文字。`scope` 是 hljs 的作用域名，空串表示没有作用域（用默认色）。 */
export type CodeSpan = { text: string; scope: string }

/** 一行代码，切成若干段。空行是一个空数组。 */
export type CodeLine = CodeSpan[]

/**
 * 围栏标记（``` 后面那个词）→ hljs 语言 id。
 *
 * <p>只列 hljs 自己的 alias 表**没覆盖到**的那些。模型爱写 `sh`、`yml`、`jsonc`
 * 这类俗名，而 `getLanguage` 认不出来就整块不上色——那正是最常见的失效方式。
 */
const ALIASES: Record<string, string> = {
  sh: "bash",
  zsh: "bash",
  shell: "bash",
  console: "bash",
  yml: "yaml",
  jsonc: "json",
  json5: "json",
  golang: "go",
  rs: "rust",
  py: "python",
  kt: "kotlin",
  jsx: "javascript",
  tsx: "typescript",
  htm: "xml",
  html: "xml",
  vue: "xml",
  patch: "diff",
  text: "plaintext",
  txt: "plaintext",
}

/**
 * 从围栏的信息串里认出语言。认不出返回 null（调用方按纯文本处理）。
 *
 * <p>信息串可能带附加内容（```js title="a.js"），所以只取第一个词。
 */
export function languageOf(info: string): string | null {
  const first = info.trim().split(/\s+/)[0]?.toLowerCase() ?? ""
  if (!first) return null
  const mapped = ALIASES[first] ?? first
  return hljs.getLanguage(mapped) ? mapped : null
}

/** 把一个围栏代码块拆成「语言 + 正文」。不是围栏块就返回 null。 */
export function parseFence(block: string): { lang: string | null; code: string } | null {
  const lines = block.split("\n")
  const open = lines[0]
  if (open === undefined) return null
  const marker = /^\s{0,3}(```|~~~)(.*)$/.exec(open)
  if (!marker) return null
  const body = lines.slice(1)
  // 收尾围栏可能还没到（流式写到一半），那时候最后一行就是正文
  const last = body[body.length - 1]
  if (last !== undefined && new RegExp(`^\\s{0,3}${marker[1]}\\s*$`).test(last)) body.pop()
  return { lang: languageOf(marker[2] ?? ""), code: body.join("\n") }
}

/**
 * 高亮一段代码，按行返回。
 *
 * <p>三种情况一律退回「整行一段、无作用域」的纯文本，**都不抛异常**：
 * 语言认不出来、hljs 内部炸了、token 树不是预期的形状。
 *
 * @param lang {@link languageOf} 的结果；null 表示不认识这门语言
 */
export function highlightCode(code: string, lang: string | null): CodeLine[] {
  if (!lang) return plainLines(code)
  try {
    const result = hljs.highlight(code, { language: lang, ignoreIllegals: true })
    const root = (result as { _emitter?: { rootNode?: unknown } })._emitter?.rootNode
    const spans = flatten(root, "")
    // 一个 span 都没有说明树的形状变了（半私有 API 升级后的典型症状）。
    // 空代码本来就该是空的，那种情况 code 也是空的，分得开
    if (spans.length === 0 && code.length > 0) return plainLines(code)
    return splitLines(spans)
  } catch {
    return plainLines(code)
  }
}

/** token 树是不是我们认识的那个形状。给测试用——依赖一升级就该红。 */
export function treeShapeIsIntact(): boolean {
  const result = hljs.highlight("const a = 1", { language: "javascript", ignoreIllegals: true })
  const root = (result as { _emitter?: { rootNode?: unknown } })._emitter?.rootNode
  return flatten(root, "").some((s) => s.scope === "keyword" && s.text === "const")
}

/**
 * 把 hljs 的 token 树压平成一串带作用域的片段。
 *
 * <p>嵌套的作用域取**最里面**那个：`title.class` 套在 `title` 外面时，
 * 里层描述得更准，而配色表本来就按「从具体到笼统」回退（见 {@link scopeChain}）。
 */
function flatten(node: unknown, scope: string): CodeSpan[] {
  if (typeof node === "string") return node ? [{ text: node, scope }] : []
  if (!node || typeof node !== "object") return []
  const { children, scope: own } = node as { children?: unknown; scope?: unknown }
  if (!Array.isArray(children)) return []
  const here = typeof own === "string" && own ? own : scope
  return children.flatMap((child) => flatten(child, here))
}

/** 按换行把片段切成行。一个跨行的片段（块注释、多行字符串）会被切开，颜色不变。 */
function splitLines(spans: CodeSpan[]): CodeLine[] {
  const lines: CodeLine[] = [[]]
  for (const span of spans) {
    const parts = span.text.split("\n")
    parts.forEach((part, index) => {
      if (index > 0) lines.push([])
      if (part) lines[lines.length - 1]!.push({ text: part, scope: span.scope })
    })
  }
  return lines
}

function plainLines(code: string): CodeLine[] {
  return code.split("\n").map((line) => (line ? [{ text: line, scope: "" }] : []))
}

/**
 * 一个作用域的回退链，从具体到笼统：
 * {@code title.function.call} → {@code title.function} → {@code title}。
 *
 * <p>配色表只需要写它关心的那几层，剩下的自动落到最近的祖先上。
 * 不这么做的话，hljs 里那些带点的作用域（`variable.language`、`title.class`）
 * 会一个都匹配不上，而它们恰恰是出现频率最高的几个。
 */
export function scopeChain(scope: string): string[] {
  if (!scope) return []
  const parts = scope.split(".")
  return parts.map((_, i) => parts.slice(0, parts.length - i).join("."))
}

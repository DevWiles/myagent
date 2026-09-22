import { describe, expect, test } from "bun:test"
import { splitBlocks } from "./markdown-blocks.ts"
import { codeScopeColor, codeScopeItalic, dark } from "./theme.ts"
import {
  highlightCode,
  languageOf,
  parseFence,
  scopeChain,
  treeShapeIsIntact,
  type CodeLine,
} from "./code-highlight.ts"

/** 把一行压成「作用域:文字」，断言起来比嵌套对象好读。 */
const shape = (line: CodeLine | undefined): string[] =>
  (line ?? []).map((s) => `${s.scope || "-"}:${s.text}`)

/** 整段里出现过的作用域集合。 */
const scopesIn = (lines: CodeLine[]): Set<string> =>
  new Set(lines.flat().map((s) => s.scope).filter(Boolean))

describe("认语言", () => {
  test("hljs 自己认得的直接用", () => {
    expect(languageOf("java")).toBe("java")
    expect(languageOf("python")).toBe("python")
  })

  test("模型爱写的俗名也要认——认不出的代价是整块不上色", () => {
    expect(languageOf("sh")).toBe("bash")
    expect(languageOf("yml")).toBe("yaml")
    expect(languageOf("golang")).toBe("go")
    expect(languageOf("jsonc")).toBe("json")
  })

  test("大小写不敏感，信息串后面的附加内容忽略", () => {
    expect(languageOf("JAVA")).toBe("java")
    // 契约是「返回一个 hljs 认的 id」，不是「返回规范名」——
    // js 本来就是 hljs 的合法别名，没必要再折算一次
    expect(languageOf('js title="a.js"')).toBe("js")
    expect(highlightCode("const a = 1", languageOf("js"))[0]).toContainEqual({
      text: "const",
      scope: "keyword",
    })
  })

  test("不认识的和空的都返回 null，由调用方退回纯文本", () => {
    expect(languageOf("mermaid")).toBeNull()
    expect(languageOf("")).toBeNull()
    expect(languageOf("   ")).toBeNull()
  })
})

describe("拆围栏", () => {
  test("拆出语言和正文，收尾围栏不算正文", () => {
    expect(parseFence("```java\nint a = 1;\n```")).toEqual({ lang: "java", code: "int a = 1;" })
  })

  test("没写语言标记照样拆，只是 lang 为 null", () => {
    expect(parseFence("```\n随便什么\n```")).toEqual({ lang: null, code: "随便什么" })
  })

  test("还没收尾（流式写到一半）也要拆——不然写的过程中整块看不见", () => {
    expect(parseFence("```python\nx = 1")).toEqual({ lang: "python", code: "x = 1" })
  })

  test("~~~ 也是围栏", () => {
    expect(parseFence("~~~go\nvar x int\n~~~")?.lang).toBe("go")
  })

  test("不是围栏的块返回 null——普通段落不该被当成代码", () => {
    expect(parseFence("这是一段普通文字")).toBeNull()
    expect(parseFence("# 标题")).toBeNull()
  })
})

describe("高亮", () => {
  test("Java：关键字、类型、注释、字符串各自分开——截图里那一块就是它", () => {
    const lines = highlightCode(
      'public class TodoList {\n    // 生成 id\n    private String name = "x";\n}',
      "java",
    )

    expect(shape(lines[0])).toContain("keyword:public")
    expect(shape(lines[1]).join("")).toContain("comment:// 生成 id")
    const third = shape(lines[2]).join(" ")
    expect(third).toContain("keyword:private")
    expect(third).toContain('string:"x"')
  })

  test("行数和原文一致，空行要留着——少一行缩进就全错位了", () => {
    const lines = highlightCode("a = 1\n\nb = 2", "python")
    expect(lines).toHaveLength(3)
    expect(lines[1]).toEqual([])
  })

  test("跨行的块注释被切成多行，颜色不丢", () => {
    const lines = highlightCode("/* 第一行\n   第二行 */\nint x;", "java")
    expect(lines[0]?.every((s) => s.scope === "comment")).toBe(true)
    expect(lines[1]?.some((s) => s.scope === "comment")).toBe(true)
  })

  test("认不出语言就整段退回纯文本，但内容一个字不少", () => {
    const lines = highlightCode("graph TD\n  A-->B", null)
    expect(scopesIn(lines).size).toBe(0)
    expect(lines.map((l) => l.map((s) => s.text).join("")).join("\n")).toBe("graph TD\n  A-->B")
  })

  test("非法代码不抛异常——模型写半截代码是家常便饭", () => {
    expect(() => highlightCode("public class {{{ 没写完", "java")).not.toThrow()
    expect(highlightCode("public class {{{ 没写完", "java").length).toBeGreaterThan(0)
  })

  test("原文可以一字不差地拼回来——高亮只该上色，不该动内容", () => {
    const code = 'def f(x):\n    return "a\\tb"  # 注释\n'
    const back = highlightCode(code, "python")
      .map((line) => line.map((s) => s.text).join(""))
      .join("\n")
    expect(back).toBe(code)
  })
})

describe("作用域回退链", () => {
  test("从具体到笼统", () => {
    expect(scopeChain("title.function.call")).toEqual([
      "title.function.call",
      "title.function",
      "title",
    ])
  })

  test("单层就一个，空的就没有", () => {
    expect(scopeChain("keyword")).toEqual(["keyword"])
    expect(scopeChain("")).toEqual([])
  })
})

describe("依赖契约", () => {
  test("hljs 的 token 树还是我们认识的形状", () => {
    // 这条红了说明 highlight.js 升级后 _emitter.rootNode 变形了（它是半私有 API）。
    // 那时候高亮会整体退回纯文本——不会崩，但会悄悄失效，所以要在这儿拦住
    expect(treeShapeIsIntact()).toBe(true)
  })
})

describe("作用域上色", () => {
  test("常见作用域都有颜色，而且互相分得开——全一个色就等于没高亮", () => {
    const picked = ["keyword", "string", "comment", "number", "type", "built_in"].map((scope) =>
      codeScopeColor(scope, dark),
    )
    expect(picked.every((c) => c !== null)).toBe(true)
    expect(new Set(picked).size).toBeGreaterThanOrEqual(5)
  })

  test("带点的作用域回退到祖先——hljs 里这类最常见，漏了就大片变白", () => {
    expect(codeScopeColor("title.function", dark)).toBe(codeScopeColor("title", dark))
    expect(codeScopeColor("string.special", dark)).toBe(codeScopeColor("string", dark))
  })

  test("更具体的那层若单独配了色，优先用它", () => {
    expect(codeScopeColor("title.class", dark)).not.toBe(codeScopeColor("title", dark))
  })

  test("认不出的作用域返回 null，由调用方用代码块默认色", () => {
    expect(codeScopeColor("从没见过的作用域", dark)).toBeNull()
    expect(codeScopeColor("", dark)).toBeNull()
  })

  test("注释斜体，别的不斜——满屏斜体反而看不清", () => {
    expect(codeScopeItalic("comment")).toBe(true)
    expect(codeScopeItalic("keyword")).toBe(false)
  })
})

describe("和分块器对接", () => {
  // 这一段是最容易悄悄断掉的接缝：分块器切出来的块，parseFence 要认得。
  // 两边对不上的话高亮整个不生效，而单测各自都是绿的
  const REPLY = [
    "方法签名：",
    "",
    "```java",
    "public class TodoList {",
    "    // 生成 id",
    "}",
    "```",
    "",
    "id 生成：用 UUID。",
  ].join("\n")

  test("代码块被切成独立一块，并且认得出是 java", () => {
    const blocks = splitBlocks(REPLY + "\n\n").complete
    const fences = blocks.map((b) => parseFence(b))
    expect(fences.filter(Boolean)).toHaveLength(1)
    expect(fences.find((f) => f)?.lang).toBe("java")
  })

  test("前后的散文不会被当成代码块", () => {
    const blocks = splitBlocks(REPLY + "\n\n").complete
    expect(parseFence(blocks[0]!)).toBeNull()
    expect(parseFence(blocks[blocks.length - 1]!)).toBeNull()
  })

  test("走完整条链之后真的上了色（截图里那块代码）", () => {
    const fence = splitBlocks(REPLY + "\n\n").complete.map(parseFence).find((f) => f)!
    const lines = highlightCode(fence.code, fence.lang)
    expect(scopesIn(lines)).toContain("keyword")
    expect(scopesIn(lines)).toContain("comment")
  })
})

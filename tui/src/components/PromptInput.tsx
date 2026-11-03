/**
 * 底部输入框。
 *
 * <h3>为什么是 textarea 而不是 input</h3>
 * OpenTUI 的 `<input>` 是**单行**控件：文字超过宽度就横向滚动，前面的字滑出视野。
 * 那个行为放在一个「交代一件事」的框里是错的——用户正在写的那句话，
 * 写到一半开头就看不见了，而这类输入天生会长（粘一段报错、描述一个需求）。
 *
 * `<textarea>` 配 `wrapMode="word"` 按词折行，框跟着内容长高，写多少看得见多少。
 *
 * <h3>换来的三件麻烦，都在下面处理掉了</h3>
 * <ol>
 *   <li>回车默认是**换行**不是提交（见 {@link BINDINGS}）</li>
 *   <li>`onSubmit` 不带内容，得自己从 `plainText` 取</li>
 *   <li>不会自己长高，得按折行后的行数算（见 {@link rows}）</li>
 * </ol>
 *
 * <p>光标仍然不用自己画——那是 Zig 那层的事，和 `<input>` 时期一样。
 */

import { decodePasteBytes, stripAnsiSequences, type KeyEvent, type PasteEvent, type TextareaRenderable } from "@opentui/core"
import { normalizePaste, PasteStore, shouldFold } from "../paste-fold.ts"
import { ImageStore, imageFromPastedPath, readClipboardImage, type ClipImage } from "../clipboard-image.ts"
import { createSignal } from "solid-js"
import { useTheme } from "../theme.ts"

/**
 * 最多长到几行。
 *
 * <p>再长就内部滚动，而不是接着顶高——输入框吃掉半个屏幕的话，
 * 上面的对话就被挤没了，而用户写长文时恰恰需要对照着上文。
 * 八行足够放下一段粘进来的报错，也还给转录留着大半个屏幕。
 */
const MAX_ROWS = 8

/**
 * 键位。
 *
 * <p><b>和 textarea 的默认值相反</b>：默认是「回车换行、Meta+回车提交」，
 * 而这个框的既定语义是「回车就发出去」——欢迎页上白纸黑字写着，
 * 换掉它等于让所有老用户重新学一遍。所以这里把两者对调。
 *
 * <p>换行给了三个键，是因为**终端对 Shift+Enter 的支持很不一致**：
 * 支持 kitty 键盘协议的终端（kitty / ghostty / 新版 iTerm2）能报出 shift，
 * 老一些的终端里 Shift+Enter 和 Enter 是同一个字节，完全分不出来。
 * 所以再给 Option/Alt+Enter 和 Ctrl-J 两条路——后者是万能的，
 * 它本来就是一个货真价实的 LF 字节，任何终端都送得出来。
 */
const BINDINGS = [
  { name: "return", action: "submit" as const },
  { name: "kpenter", action: "submit" as const },
  { name: "return", shift: true, action: "newline" as const },
  { name: "kpenter", shift: true, action: "newline" as const },
  { name: "return", meta: true, action: "newline" as const },
  { name: "kpenter", meta: true, action: "newline" as const },
  { name: "j", ctrl: true, action: "newline" as const },
]

export type PromptInputProps = {
  placeholder: string
  /** 正在等后端时为 true：提示符换色，提示「它还活着」 */
  busy?: boolean
  /**
   * 有确认框挂着时为 true：必须**交出焦点**。
   *
   * 否则用户按 y/n 想回答确认框，字符会被输入框一并吃掉打在里面
   * （确认框照样会响应——按键是全局监听的——但输入框里会留下一个莫名其妙的 y）。
   */
  suspended?: boolean
  /**
   * 输入框内容变了。命令菜单靠它边打边收窄。
   *
   * 程序改内容（提交后清空、Tab 补全）时也会叫一次——OpenTUI 的内容事件
   * 只在用户按键时发，不叫的话菜单会挂在屏幕上不走。
   */
  onValueChange?: (value: string) => void
  /**
   * 当前内容对上了一条真实存在的命令。
   *
   * 整行换色。这是「这条命令确实存在」最便宜的反馈——按回车之前就看得见，
   * 而不是等一轮往返之后被告知「没这个命令」。
   */
  matched?: boolean
  /**
   * 挂载时把控件的手交出去：`setValue` 给 Tab 补全，`pasteImage` 给贴图快捷键
   * （按键在 App 的全局 handler 里接，见 clipboard-image.ts）。
   */
  controls?: (api: { setValue: (value: string) => void; pasteImage: () => void }) => void
  /** 这句话现在不能带图的原因（计划模式、后端太老）。有值时带图的提交会被拦下、输入框原样留着 */
  imagesBlocked?: string
  /** 贴图没成、带图被拦时说一声。不给就静默 */
  onNotice?: (level: "info" | "warn", text: string) => void
  /**
   * @param value   发给模型的全文：粘贴折成的占位符已经换回原文
   * @param display 给人看的那份，占位符原样留着；转录里画这个（见 paste-fold.ts）
   * @param images  贴进来、占位符还在的那几张图，按出现顺序
   */
  onSubmit: (value: string, display: string, images: ClipImage[]) => void
}

export function PromptInput(props: PromptInputProps) {
  const theme = useTheme()
  let input: TextareaRenderable | undefined
  /** 这一句里贴进来的大段文字。占位符在输入框里，原文在这儿 */
  const pastes = new PasteStore()
  /** 这一句里贴进来的图，同一个路子 */
  const images = new ImageStore()

  const attach = (result: ReturnType<typeof imageFromPastedPath>): void => {
    if (!input || !result) return
    if (result.ok) input.insertText(images.add(result.image))
    else props.onNotice?.("warn", result.reason)
  }

  /**
   * 粘贴进来的东西：拖进来的图片文件路径当图附上；大段文字折成占位符；
   * 短的放行，让 textarea 照常插进去。
   */
  const handlePaste = (event: PasteEvent): void => {
    if (!input) return
    const text = normalizePaste(stripAnsiSequences(decodePasteBytes(event.bytes)))
    const dropped = imageFromPastedPath(text)
    if (dropped) {
      event.preventDefault()
      attach(dropped)
      return
    }
    if (!shouldFold(text)) return
    event.preventDefault()
    input.insertText(pastes.add(text))
  }

  /** 贴图快捷键：去系统剪贴板里拿图。要起一个子进程，所以是异步的 */
  const pasteImage = (): void => {
    void readClipboardImage().then(attach)
  }

  /**
   * 现在要占几行。
   *
   * <p>用 `virtualLineCount`（折行**之后**的视觉行数）而不是 `lineCount`（逻辑行数）：
   * 后者只数换行符，一句写满三行的长句在它眼里仍然是 1，框就不会长高——
   * 那正是这次要修的毛病，只不过换了个地方重现。
   *
   * <p>存成信号而不是每帧去问控件：高度要驱动布局，而布局变化又会触发重排，
   * 直接在渲染里读控件状态就成了「渲染依赖渲染」。内容变了才更新，干净。
   */
  const [rows, setRows] = createSignal(1)

  const syncRows = (): void => {
    if (!input) return
    setRows(Math.max(1, Math.min(MAX_ROWS, input.virtualLineCount)))
  }

  /** 内容变了：更新高度，并把新内容报给上层（菜单靠它收窄）。 */
  const handleChange = (): void => {
    syncRows()
    props.onValueChange?.(input?.plainText ?? "")
  }

  const submit = (): void => {
    const display = (input?.plainText ?? "").trim()
    if (!display) return
    const attached = images.collect(display)
    if (attached.length > 0 && props.imagesBlocked) {
      // 拦在清空之前：条件满足了（切回 normal 之类）就能原样发出去，不用重贴
      props.onNotice?.("warn", props.imagesBlocked)
      return
    }
    props.onSubmit(pastes.expand(display).trim(), display, attached)
    // 提交后立刻清空，否则下一句会接在上一句后面
    pastes.clear()
    images.clear()
    input?.setText("")
    setRows(1)
    props.onValueChange?.("")   // 清空是程序干的，内容事件不会叫，菜单得自己收
  }

  // 上下两条横线把输入区从对话流和状态栏里切出来。
  // 正在跑的时候换成高亮色——这是除了状态栏那个点之外，第二个「它还活着」的信号，
  // 而且是全屏最宽的一处，眼睛不用去找。
  const rule = () => (props.busy ? theme.promptBorderBusy : theme.promptBorder)

  return (
    <box
      flexDirection="row"
      flexShrink={0}
      // 只要上下两条边：左右再封起来就成了一个方框，和下面的状态栏挤在一起显得很闷
      border={["top", "bottom"]}
      borderColor={rule()}
      paddingLeft={1}
      paddingRight={1}
    >
      <text fg={props.busy ? theme.promptBorderBusy : theme.brand}>{"› "}</text>
      {/*
        高度显式给到行数。不给的话 textarea 在 Yoga 里拿到的是一个由父容器决定的
        高度，内容长出去只能内部滚动——看着就和原来那个单行框一样，白改一场。
      */}
      <box flexGrow={1} minWidth={0} height={rows()}>
        <textarea
          ref={(r: TextareaRenderable) => {
            input = r
            props.controls?.({
              pasteImage,
              setValue: (value: string) => {
                // 程序改写整行（Tab 补全、清空）：原来那几段粘贴的占位符都不在了
                pastes.clear()
                images.clear()
                r.setText(value)
                syncRows()
                props.onValueChange?.(value)
              },
            })
          }}
          // 按词折行。这一行就是这次改动的全部目的
          wrapMode="word"
          // 用声明式的 focused 而不是 onMount 里调 focus()：
          // 焦点要跟着 suspended 来回切，写成命令式就得自己管两个方向的时序，
          // 而且初次挂载时 ref 还没赋值，focus() 会静默落空
          focused={!props.suspended}
          placeholder={props.placeholder}
          cursorColor={theme.brand}
          // 对上命令就整行换成品牌色，见 matched 那段注释
          focusedTextColor={props.matched ? theme.brand : theme.text}
          keyBindings={BINDINGS}
          onContentChange={handleChange}
          onPaste={handlePaste}
          // 和 `<input>` 不同，这个回调**不带内容**（签名是 `(event) => void`），
          // 内容得自己从 plainText 取。照着老写法接一个 value 参数的话，
          // 拿到的是一个 event 对象，typeof 判断直接把提交静默吞掉
          onSubmit={submit}
        />
      </box>
    </box>
  )
}

/** 给测试用：暴露键位表，好断言「回车是提交、Shift+回车是换行」这条没被改坏。 */
export const promptKeyBindings: readonly { name: string; action: string }[] = BINDINGS

/** 给测试用：折行后该占几行。纯函数，不碰控件。 */
export function rowsFor(virtualLineCount: number): number {
  return Math.max(1, Math.min(MAX_ROWS, virtualLineCount))
}

/**
 * 这个键会不会被输入框当成「换行」。App 判断要不要拦回车时用得上。
 *
 * <p>修饰键收成可选：{@link KeyEvent} 上它们是必填的 boolean，而调用方
 * （和测试）手里常常只有「名字 + 按下的那一个修饰键」。缺的按 false 算，
 * 和终端没报这个修饰键是一回事。
 */
type KeyShape = Pick<KeyEvent, "name"> & Partial<Pick<KeyEvent, "shift" | "meta" | "ctrl">>

export function isNewlineKey(key: KeyShape): boolean {
  return BINDINGS.some(
    (b) =>
      b.action === "newline" &&
      b.name === key.name &&
      Boolean((b as { shift?: boolean }).shift) === Boolean(key.shift) &&
      Boolean((b as { meta?: boolean }).meta) === Boolean(key.meta) &&
      Boolean((b as { ctrl?: boolean }).ctrl) === Boolean(key.ctrl),
  )
}

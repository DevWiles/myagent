#!/usr/bin/env bun
/**
 * 入口：解析参数 → 拉起后端 → 渲染界面。
 *
 * 这里刻意不做任何 agent 逻辑。界面进程只负责画和收键盘，
 * 模型调用、工具执行、上下文管理全在 Java 后端里——它才是这个项目的本体。
 */

import { render } from 'ink'
import { createElement } from 'react'
import { EventEmitter } from 'node:events'
import { resolve } from 'node:path'
import { PassThrough } from 'node:stream'
import { startBackend, type Backend } from './backend.ts'
import { App } from './components/App.tsx'
import { extractMouseEvents, type MouseEvent } from './mouse.ts'
import { SessionStore } from './store.ts'
import { transcriptToText } from './format.ts'
import { THEMES, ThemeProvider, pickTheme } from './theme.ts'

const USAGE = `myagent-tui —— myagent 的终端界面（React + Ink）

用法:
  myagent-tui [选项]

选项:
  -d, --dir <路径>     工作区目录（默认当前目录）
  -m, --model <名称>   覆盖配置里的模型
  -p, --plan           只读模式：不给它改文件和执行命令的工具
      --unsafe         跳过工作区安全检查（危险）
      --theme <名称>   dark | light | ansi（默认按终端能力自动选）
      --jar <路径>     指定后端 jar（默认 ../target/myagent.jar，也可用 MYAGENT_JAR）
  -h, --help           显示本帮助

界面自己不跑 agent：它会拉起 \`java -jar myagent.jar --serve\`，
通过本机 WebSocket 驱动它。后端逻辑一行都没有改。`

type Args = {
  workspace: string
  model?: string
  planMode: boolean
  unsafe: boolean
  theme?: string
  jar?: string
  help: boolean
}

function parseArgs(argv: string[]): Args {
  const args: Args = { workspace: process.cwd(), planMode: false, unsafe: false, help: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    const value = () => {
      const next = argv[++i]
      if (next === undefined) throw new Error(`${arg} 后面要跟一个值`)
      return next
    }
    switch (arg) {
      case '-h':
      case '--help':
        args.help = true
        break
      case '-p':
      case '--plan':
        args.planMode = true
        break
      case '--unsafe':
        args.unsafe = true
        break
      case '-d':
      case '--dir':
        args.workspace = resolve(value())
        break
      case '-m':
      case '--model':
        args.model = value()
        break
      case '--theme':
        args.theme = value()
        break
      case '--jar':
        args.jar = value()
        break
      default:
        throw new Error(`未知参数: ${arg}（用 --help 查看用法）`)
    }
  }
  return args
}

async function main(): Promise<void> {
  let args: Args
  try {
    args = parseArgs(process.argv.slice(2))
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exit(2)
  }

  if (args.help) {
    console.log(USAGE)
    return
  }

  // 全屏 TUI 在管道里毫无意义，而且会把控制序列当正文吐出去
  if (!process.stdout.isTTY) {
    console.error('myagent-tui 需要在真正的终端里运行。非交互场景请直接用 `myagent "任务描述"`。')
    process.exit(2)
  }

  const backend = await startBackend({
    workspace: args.workspace,
    model: args.model,
    planMode: args.planMode,
    unsafe: args.unsafe,
    jar: args.jar,
  })

  const store = new SessionStore()
  // 等首帧到了再开画。否则第一帧会画出一个没有招牌的空输入框，
  // 下一帧再被 ready 顶掉——用户看到的就是启动时闪一下。
  await primeReady(backend, store)

  const theme = THEMES[pickTheme(args.theme)]

  // 全屏（备用屏幕）+ 鼠标追踪，全部手动发——不用 Ink 的选项，那套有环境检测
  // 会在真实终端里静默失效。手动发和 Java 版 FullScreenUi 一样确定。
  //   1049h  切备用屏幕（退出 1049l 把主屏原样还回来）
  //   2J H   清屏 + 光标归左上，从 (0,0) 画
  //   1000h+1006h  鼠标追踪（点击/滚轮，SGR 格式）——点击展开、滚轮滚动的前提
  //                代价：终端原生滚轮/选中被接管，选中改按 option 拖拽走原生
  const ENTER = '\x1b[?1049h\x1b[2J\x1b[H\x1b[?1000h\x1b[?1006h'
  const EXIT = '\x1b[?1006l\x1b[?1000l\x1b[?1049l'
  process.stdout.write(ENTER)

  let restored = false
  const restore = () => {
    if (restored) return
    restored = true
    process.stdout.write(EXIT)
  }
  process.on('exit', restore)
  process.on('SIGTERM', () => {
    restore()
    process.exit(0)
  })

  // 在数据进 ink 之前把鼠标序列挑走，否则 ink 会把 `\x1b[<0;5;10m` 当成一串
  // 垃圾按键塞进输入框。做法：给 ink 传一个过滤后的 stdin——真实 stdin 的每块
  // 数据先过 extractMouseEvents，鼠标事件走 mouseEmitter 给 App，剩下的正常
  // 按键 write 进 PassThrough 交给 ink。PassThrough 不是 TTY，得把 ink 需要的
  // 几个 TTY 方法代理到真实 stdin。
  const mouseEmitter = new EventEmitter()
  const inkStdin = new PassThrough() as PassThrough & {
    isTTY: boolean
    setRawMode: (mode: boolean) => void
    ref: () => void
    unref: () => void
  }
  inkStdin.isTTY = true
  inkStdin.setRawMode = (mode: boolean) => {
    process.stdin.setRawMode?.(mode)
  }
  inkStdin.ref = () => {}
  inkStdin.unref = () => {}

  process.stdin.setRawMode?.(true)
  process.stdin.resume()
  const onStdin = (chunk: Buffer | string) => {
    const text = typeof chunk === 'string' ? chunk : chunk.toString('utf8')
    const { events, rest } = extractMouseEvents(text)
    if (events.length > 0) mouseEmitter.emit('mouse', events as MouseEvent[])
    if (rest) inkStdin.write(rest)
  }
  process.stdin.on('data', onStdin)

  const instance = render(
    createElement(ThemeProvider, { value: theme }, createElement(App, { store, backend, mouseEmitter })),
    {
      stdin: inkStdin as unknown as NodeJS.ReadStream,
      // Ctrl-C 自己处理：正在跑的时候它应该是「中断这一轮」而不是「退出程序」
      exitOnCtrlC: false,
    },
  )

  try {
    await instance.waitUntilExit()
  } finally {
    restore()
    backend.dispose()
    // 退出后把对话摘要打回主屏 scrollback——全屏内容随备用屏消失了，
    // 这一步让「退出后还能上拉回看这次聊了什么」成立（对齐 Claude Code 的手感）。
    const { entries } = store.getSnapshot()
    if (entries.length > 0) {
      process.stdout.write(transcriptToText(entries) + '\n')
    }
  }
}

/**
 * 把后端的首帧灌进 store，然后才返回。
 *
 * 超时不算失败：ready 收不到界面照样能起来（顶多少一块招牌），
 * 但连不上的错误此时早就在 startBackend 里抛过了。
 */
function primeReady(backend: Backend, store: SessionStore): Promise<void> {
  return new Promise(resolvePromise => {
    const timer = setTimeout(finish, 3000)
    const off = backend.onEvent(event => {
      store.apply(event)
      if (event.t === 'ready') finish()
    })
    function finish() {
      clearTimeout(timer)
      off()
      resolvePromise()
    }
  })
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error))
  process.exit(1)
})

#!/usr/bin/env bun
/**
 * 入口：解析参数 → 拉起后端 → 渲染界面。
 *
 * 这里刻意不做任何 agent 逻辑。界面进程只负责画和收键盘，
 * 模型调用、工具执行、上下文管理全在 Java 后端里——它才是这个项目的本体。
 */

import { render } from 'ink'
import { createElement } from 'react'
import { resolve } from 'node:path'
import { startBackend, type Backend } from './backend.ts'
import { App } from './components/App.tsx'
import { SessionStore } from './store.ts'
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

  // inline 渲染：**不进备用屏幕、不清屏**。
  //
  // 这是终端 agent 的正确形态（Claude Code 亦然）：内容直接打进主屏往下增长，
  // 输入框和状态栏由 Ink 钉在底部实时重画，看着像独占，实际全部留在 scrollback。
  // 由此得到三件事，都是备用屏方案做不到的：
  //   · 退出后整段对话留在终端历史里，可以往上翻、可以复制
  //   · 反复启动多次，每次的记录叠在一起，一条都不丢
  //   · fn+滚动（或滚轮）直接翻终端 scrollback，能翻回招牌上方
  //
  // 也因此不依赖任何终端设置 —— 备用屏那条路要求 Terminal.app 勾上
  // 「允许应用程序使用备用屏幕」，没勾就整个失效，而这条路没有这个前提。
  //
  // 鼠标同样不接管：拖拽选中、Cmd-C 复制全部是终端原生行为。


  const instance = render(
    createElement(ThemeProvider, { value: theme }, createElement(App, { store, backend })),
    {
      // Ctrl-C 自己处理：正在跑的时候它应该是「中断这一轮」而不是「退出程序」
      exitOnCtrlC: false,
    },
  )

  try {
    await instance.waitUntilExit()
  } finally {
    backend.dispose()
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

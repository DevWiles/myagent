/**
 * 入口。
 *
 * 三件事：解析参数、把终端交给 OpenTUI 的 renderer、挂上组件树。
 * 后端进程由 `createSession()` 在组件里拉起（见 session.ts）。
 *
 * `createCliRenderer` 会切到备用屏幕缓冲区（altscreen）并接管键盘，
 * **必须保证退出时还回去**，否则用户的终端会留在一个没有回显的诡异状态里。
 * opencode 用 Effect 的 acquireRelease 管这件事；我们这版还没引 Effect，
 * 就用一个统一的 shutdown() + 信号监听兜住所有退出路径。
 */

import { createCliRenderer } from "@opentui/core"
import { render } from "@opentui/solid"
import { writeSync } from "node:fs"
import { basename, resolve } from "node:path"
import { App } from "./components/App.tsx"

// --- 参数 -------------------------------------------------------------------

const argv = process.argv.slice(2)

function flag(...names: string[]): string | undefined {
  for (const name of names) {
    const i = argv.indexOf(name)
    if (i >= 0 && argv[i + 1]) return argv[i + 1]
  }
  return undefined
}

if (argv.includes("-h") || argv.includes("--help")) {
  console.log(`myagent（OpenTUI 版）

用法:
  bun run src/main.tsx [选项]

选项:
  -d, --dir <路径>    工作区，默认当前目录
  -m, --model <名字>  覆盖模型
      --readonly      只读模式，不允许改文件
      --unsafe        放行工作区外的路径
  -h, --help          这段文字
`)
  process.exit(0)
}

const workspace = resolve(flag("-d", "--dir") ?? process.cwd())

// --- 终端 -------------------------------------------------------------------

const renderer = await createCliRenderer({
  targetFps: 30,
  // 自己处理 Ctrl-C：要在退出前关掉后端进程，不能让它变成孤儿
  exitOnCtrlC: false,
  useMouse: true,
})

let shuttingDown = false

/**
 * 所有退出路径都走这里：先还终端，再打印原因，最后退。
 *
 * 原因用 `writeSync` 而不是 console.error：stderr 被重定向成管道或文件时
 * console.error 是异步的，紧跟着的 process.exit 会把还没刷出去的内容直接丢掉——
 * 表现就是「程序静悄悄退出，一个字的报错都没有」。
 */
function shutdown(reason?: string, code = 0): void {
  if (shuttingDown) return
  shuttingDown = true
  if (!renderer.isDestroyed) renderer.destroy()
  if (reason) writeSync(2, reason.endsWith("\n") ? reason : reason + "\n")
  process.exit(code)
}

renderer.keyInput.on("keypress", (key: { name?: string; ctrl?: boolean }) => {
  if (key.ctrl && key.name === "c") shutdown()
})
process.on("SIGINT", () => shutdown())
process.on("SIGTERM", () => shutdown())
process.on("SIGHUP", () => shutdown())

await render(
  () => (
    <App
      options={{
        workspace,
        model: flag("-m", "--model"),
        planMode: argv.includes("--readonly"),
        unsafe: argv.includes("--unsafe"),
      }}
      branch={basename(workspace)}
      // 后端没了就退出，并把原因留在终端上——altscreen 一还原，
      // 界面里印的任何错误都会跟着消失，只有这里打的才看得见
      onClosed={(reason) => shutdown(`myagent 已退出：${reason}`, 1)}
    />
  ),
  renderer,
)

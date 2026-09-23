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
import { queryCursorOrigin, restoreCursorOrigin, type CursorOrigin } from "./cursor-origin.ts"
import { resolve } from "node:path"
import type { Accessor } from "solid-js"
import { App } from "./components/App.tsx"
import { buildEpilogue } from "./epilogue.ts"
import type { SessionState } from "./store.ts"

/**
 * 整场会话的起点。
 *
 * store 里的 `startedAt` 是**每一轮**的（busy 时设、闲时清 null），算不出整场时长。
 * 起点取「用户敲下命令的那一刻」而不是「连上后端的那一刻」——等 java 起来那一两秒
 * 他也在等，那也是会话的一部分。
 *
 * mvn build 的时间**算在内**：build 现在发生在界面起来之后（见 build.ts），
 * 那几秒用户同样在等，收尾报的时长就该把它算进去。
 */
const sessionStartedAt = Date.now()

/** 组件树里的东西，退出路径要用。Solid 的作用域出不来，就搁在模块作用域。 */
const exit: { state?: Accessor<SessionState>; dispose?: () => void } = {}

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
      --rebuild       后端不管新鲜不新鲜都重 build 一遍
      --lan           局域网模式：手机可以扫码连进来（界面里敲 /qr 出码）。
                      明文 ws，只在可信网络用
      --relay <地址>  挂到中继上，手机在任何网络都能连（端到端加密）
      --relay-reset   换一套房间号和配对密钥，手机要重新扫码
      --connect <地址> 不在本机起后端，连一个已经在跑的（ws://host:port/?token=…）。
                      后端在云服务器上时用；那头要用 --lan 起。断了会一直重连
  -h, --help          这段文字
`)
  process.exit(0)
}

const workspace = resolve(flag("-d", "--dir") ?? process.cwd())

// --- 终端 -------------------------------------------------------------------

let epilogueWritten = false

/**
 * 把收尾信息留在主屏幕上。
 *
 * **时机是这里唯一的难点。** 还原主屏的 ANSI 是 renderer 在 destroy 的最后一步发的，
 * 早一步写就落在备用屏上，随 altscreen 一起蒸发——表现就是「退出后什么都没留下」，
 * 这正是修之前的样子。所以主写入点挂在 `onDestroy`（destroy 的最后一步），
 * shutdown() 里再补一次兜底。幂等，两边都调也只写一次。
 *
 * 用 writeSync 而不是 console.log：stdout 被重定向成管道时 console.log 是异步的，
 * 紧跟着的 process.exit 会把还没刷出去的内容直接丢掉。
 */
let cursorRestored = false

/**
 * 把光标放回启动时的位置。
 *
 * <p>和 {@link flushEpilogue} 分开、而且<b>不能合并</b>：没有收尾信息可打时
 * flushEpilogue 会提前 return，而那种情况下光标照样是错的——
 * 接下来 shell 画提示符，照样从第 1 行开始盖。
 */
function restoreCursor(): void {
  if (cursorRestored) return
  cursorRestored = true
  restoreCursorOrigin(cursorOrigin)
}

function flushEpilogue(): void {
  if (epilogueWritten) return
  epilogueWritten = true
  const state = exit.state
  if (!state) return
  const lines = buildEpilogue(state(), Date.now() - sessionStartedAt)
  if (lines.length === 0) return
  // 暗色只在真终端上加：输出被重定向到文件时别往里塞控制序列
  const body = lines.join("\n")
  writeSync(1, (process.stdout.isTTY ? `\x1b[2m${body}\x1b[0m` : body) + "\n")
}

/**
 * 启动那一刻光标在哪。**必须在 createCliRenderer 之前问**——
 * OpenTUI 一接管 stdin 就自己读键盘了，终端的回答会被它当成按键吃掉。
 *
 * 问不到（不是 TTY、终端不回话）就是 null，退出时跳过这一步，
 * 行为和修之前一模一样。
 */
const cursorOrigin: CursorOrigin | null = await queryCursorOrigin()

const renderer = await createCliRenderer({
  targetFps: 30,
  // 自己处理 Ctrl-C：要在退出前关掉后端进程，不能让它变成孤儿
  exitOnCtrlC: false,
  useMouse: true,
  // 顺序不能反：收尾信息要打在**还原之后的**光标位置上。
  // 反过来的话，那两行还是会盖掉用户第 1、2 行的内容
  onDestroy: () => {
    restoreCursor()
    flushEpilogue()
  },
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
  // 顺序：先关后端，再还终端，最后打字。
  //
  // 后端排第一是因为 process.exit() 不跑 Solid 的卸载链。renderer.destroy() 确实会
  // 顺带触发一次（@opentui/solid 把 root 的 dispose 挂在 renderer 的 destroy 事件上），
  // 但那是别人的实现细节，而且后端还没起好时那条链路根本够不着已经 spawn 出来的
  // java 进程。实测不显式关的话，java 在 Ctrl-C 之后还要活 10 秒以上。
  exit.dispose?.()
  if (!renderer.isDestroyed) renderer.destroy()
  // destroy 若被推迟（正好卡在渲染帧中间）就靠这两下兜底。两个都是幂等的
  restoreCursor()
  flushEpilogue()
  if (reason) writeSync(2, reason.endsWith("\n") ? reason : reason + "\n")
  process.exit(code)
}

/**
 * App 挂载之前，Ctrl-C 归这里管。
 *
 * 挂载之后就交给 App——只有它看得见「正跑着一轮」「输入框里有字」，
 * 而这两样恰恰是最不该被一下 Ctrl-C 抹掉的东西（见 `interrupt.ts`）。
 * 这条兜底不能删：后端起不来、界面还没画出来的那一两秒，
 * 用户唯一能按的就是 Ctrl-C，那时候 App 还接不了。
 */
let appOwnsCtrlC = false

renderer.keyInput.on("keypress", (key: { name?: string; ctrl?: boolean }) => {
  if (key.ctrl && key.name === "c" && !appOwnsCtrlC) shutdown()
})
// SIGINT 照旧立刻退：raw 模式下 Ctrl-C 根本不产生它（-isig），
// 能走到这儿的只有 `kill -INT` 那种外部指令——那是真的要它死，不该再问一遍
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
        forceRebuild: argv.includes("--rebuild"),
        lan: argv.includes("--lan"),
        relay: flag("--relay"),
        relayReset: argv.includes("--relay-reset"),
        connect: flag("--connect"),
      }}
      // 分支由后端从 .git/HEAD 读出来，随 ready 帧下来（见 store 的 branch）。
      // 这里原来传的是 basename(workspace)——目录名冒充分支，显示出来是错的

      // 后端**彻底**没了（协议不兼容、崩溃循环）才退出，并把原因留在终端上——
      // altscreen 一还原，界面里印的任何错误都会跟着消失，只有这里打的才看得见。
      // 普通断线不走这儿：backend.ts 会自己重连，界面照常活着
      onClosed={(reason) => shutdown(`myagent 已退出：${reason}`, 1)}
      onExit={() => shutdown()}
      onMounted={(handle) => {
        exit.state = handle.state
        exit.dispose = handle.dispose
        appOwnsCtrlC = true
      }}
    />
  ),
  renderer,
)

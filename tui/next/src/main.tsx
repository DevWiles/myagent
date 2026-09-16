/**
 * 入口。
 *
 * 两件事：把终端交给 OpenTUI 的 renderer，然后把 Solid 的组件树挂上去。
 *
 * `createCliRenderer` 会切到备用屏幕缓冲区（altscreen）并接管键盘，
 * **必须保证退出时还回去**，否则用户的终端会留在一个没有回显的诡异状态里。
 * opencode 用 Effect 的 acquireRelease 管这件事；我们这步还没引 Effect，
 * 就用最朴素的 try/finally + 信号监听兜住。
 */

import { createCliRenderer } from "@opentui/core"
import { render } from "@opentui/solid"
import { basename } from "node:path"
import { App } from "./components/App.tsx"
import { tildify } from "./path.ts"

const workspace = process.cwd()

const renderer = await createCliRenderer({
  targetFps: 30,
  // 自己处理 Ctrl-C：将来要在退出前关 WebSocket、落审计日志
  exitOnCtrlC: false,
  useMouse: true,
})

function shutdown() {
  if (!renderer.isDestroyed) renderer.destroy()
  process.exit(0)
}

renderer.keyInput.on("keypress", (key: { name?: string; ctrl?: boolean }) => {
  if (key.ctrl && key.name === "c") shutdown()
})
process.on("SIGINT", shutdown)
process.on("SIGTERM", shutdown)
process.on("SIGHUP", shutdown)

await render(
  () => (
    <App
      // 写死的占位值。接上后端后这些来自 ready 帧（见 docs/protocol.md）
      info={{
        version: "0.1.0",
        model: "（未连接后端）",
        toolCount: 0,
        workspace: tildify(workspace),
        user: "Chris",
      }}
      branch={basename(workspace)}
    />
  ),
  renderer,
)

# myagent-tui

myagent 的终端界面，用 **OpenTUI + SolidJS** 写的。

**它不跑 agent 逻辑。** 模型调用、工具执行、上下文管理全在 Java 后端里；
这个进程只负责画屏幕和收键盘，两边靠 WebSocket 通信（协议见 `docs/protocol.md`）。

## 跑起来

在你想让 agent 干活的目录里敲：

```bash
~/Workspace/projects/myagent/myagent-tui
```

仓库根目录的这个脚本会自己判断要不要 build 后端、要不要装依赖，然后起界面。
第一次跑会花点时间 build，之后两步都跳过，启动和裸跑界面一样快。

```bash
./myagent-tui                 # 在当前目录起界面
./myagent-tui -d ~/proj       # 指定工作区
./myagent-tui -m 模型名        # 换模型
./myagent-tui --readonly      # 只读，不让它改文件
./myagent-tui --rebuild       # 强制重新 build 后端
./myagent-tui --connect ws://host:port/?token=…   # 不起本地后端，连一个已在跑的（云上）
./myagent-tui --help          # 用法（不触发 build）
```

**断了不退出。** 后端连接断了（机器休眠、远端网络抖动）界面不再整个退出，
而是状态栏显示「重连中（第 n 次）」，1s→30s 退避一直重试，重连带 `since` 把漏掉的事件补回来；
本地 java 崩了会自动重新拉起并 `continue` 接回刚才的会话。期间敲的话先攒着，连上补发。
只有两种情况才真的退：协议版本对不上、一分钟内崩三次（这时把后端 stderr 打出来）。
规则部分在 `src/link.ts`，I/O 在 `src/backend.ts`。

想放进 PATH 直接叫 `myagent-tui`，做个软链接就行，脚本会自己解开链接找到仓库：

```bash
ln -s "$PWD/myagent-tui" ~/.local/bin/myagent-tui
```

**按键**：Enter 发送，Esc 中断当前这一轮，Ctrl-C 退出。
确认框弹出时 `y` 同意 / `n` 或 Esc 拒绝 / `a` 本会话都同意
（计划确认框只有 y/n，没有 a——每份计划都不一样，不给一次性授权）。

只调界面不想拉后端时可以直接跑，但**必须在本目录下**（原因见下）：

```bash
cd tui && bun run dev
```

## ⚠️ bunfig 必须在 cwd

Solid 的 JSX 靠 `bunfig.toml` 里的 `preload = ["@opentui/solid/preload"]` 转换，
而 **bun 是按 cwd 找 `bunfig.toml` 的**，不是按脚本位置。

所以在仓库根目录跑 `bun tui/src/main.tsx`，那份 bunfig 根本不会加载，
JSX 不转换，界面起来一片空白然后立刻退出，**而且没有任何报错**。
`myagent-tui` 脚本为此先存下 cwd、`cd` 进本目录、再用 `-d` 把工作区传回去。

## 为什么是 OpenTUI 而不是 Ink

早期这套界面是 React + Ink 写的（2026-09 迁走）。Ink 用 React 的协调器把界面
算成字符串再写给终端，绘制全在 JS 里做。OpenTUI 把绘制推到一个 Zig 写的原生库
（通过 Bun FFI 调用），布局仍然是 Yoga（和 Ink 同一个 flexbox 引擎），
但多出来几样 Ink 给不了的东西：

- **原生输入控件**——光标、选区、滚动在原生层实现，不用再往字符串里插反色字符冒充光标
- **原生滚动容器**（`<scrollbox>`）——不用自己算视口切片
- **鼠标**——原生层维护命中网格，点击和拖选是现成的
- **内置代码/Markdown 渲染**，带 TreeSitter 语法高亮

这套技术栈和 opencode 的 TUI 是同一套。

## 目录

```
src/
├── main.tsx        入口：解析参数、初始化 renderer、挂组件树
├── session.ts      把 SessionStore 接到 Solid 上
├── theme.ts        语义化颜色 token（界面里不许出现字面颜色）
├── path.ts         按显示宽度算的截断工具（中文是双宽字符！）
├── components/     界面组件
├── backend.ts      后端进程生命周期 + WebSocket（框架无关）
├── store.ts        会话状态机（框架无关，有测试）
├── protocol.ts     线上协议类型（框架无关）
└── format.ts       文案与格式化（框架无关，有测试）
```

`backend.ts` / `store.ts` / `protocol.ts` / `format.ts` 是从 Ink 时代**原样留下**的：
它们不依赖任何 UI 框架，协议规约逻辑也有测试覆盖，迁移时没必要重写。

## 还没做

- [ ] Markdown 渲染（可以直接用 OpenTUI 内置的 `<markdown>`，省掉自己解析）
- [ ] Spinner（等模型时的转圈）
- [ ] 工具调用详情的展开/折叠（Ctrl-O）
- [ ] PlanPanel：计划任务图
- [ ] 键位系统（可以引 `@opentui/keymap`，支持 leader 键）

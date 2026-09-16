# myagent-tui

myagent 的终端界面，用 React + Ink 写的。

**它不跑 agent 逻辑。** 模型调用、工具执行、上下文管理全在 Java 后端里；
这个进程只负责画屏幕和收键盘。

## 跑起来

```bash
./myagent-tui
```

仓库根目录的这个脚本会自己判断要不要 build 后端、要不要装依赖，然后起界面。
第一次跑会花点时间 build，之后两步都跳过，启动和裸跑界面一样快。

```bash
./myagent-tui                 # 在当前目录起界面
./myagent-tui -d ~/proj       # 指定工作区
./myagent-tui --theme light   # 浅色终端
./myagent-tui --rebuild       # 强制重新 build 后端
./myagent-tui --help          # 用法（不触发 build）
```

想放进 PATH 直接叫 `myagent-tui`，做个软链接就行，脚本会自己解开链接找到仓库：

```bash
ln -s "$PWD/myagent-tui" ~/.local/bin/myagent-tui
```

<details>
<summary>手动分步（调试用）</summary>

```bash
mvn -DskipTests package   # 仓库根目录
cd tui && bun install
bun run dev -- -d ~/some/project
```

</details>

常用参数（`--help` 看全部）：

| 参数 | 说明 |
|---|---|
| `-d, --dir <路径>` | 工作区目录，默认当前目录 |
| `-m, --model <名称>` | 覆盖配置里的模型 |
| `-p, --plan` | 只读模式：不给它改文件和执行命令的工具 |
| `--theme <名称>` | `dark` / `light` / `ansi`，默认按终端能力自动选 |
| `--jar <路径>` | 指定后端 jar（也可用 `MYAGENT_JAR` 环境变量） |
| `--no-fullscreen` | 不独占终端。对话留在终端 scrollback 里，`Cmd-F` 能搜 |
| `--fullscreen` | 强制独占（默认就是，一般用不到） |

独占终端默认开启，也可以用 `MYAGENT_FULLSCREEN=0` 关掉。非 TTY、`TERM=dumb`、
以及 CI 环境会自动关闭。

> `main` 分支上那套 **Java** TUI 的 `-f/--fullscreen` 是**可选项、默认关**；
> 这个 TS 界面**默认就是全屏**，要关用 `--no-fullscreen`。两边 `--help` 看起来
> 会不一致，是故意的。

按键：`Enter` 发送 · `Esc` 中断当前这轮 · `Ctrl-O` 展开/收起本轮过程详情 ·
`Ctrl-P/Ctrl-N` 翻输入历史 · `Ctrl-W` 删一个词 · `Ctrl-U` 删到行首 ·
`Ctrl-C` 退出（正在跑的时候按一下是中断）· `Ctrl-D` 空输入时退出。
斜杠命令：`/help` `/tools` `/clear` `/exit`。

滚动：滚轮 / `↑↓` / `PgUp` `PgDn` / `Home` `End`。**备用屏里没有终端原生
scrollback**，所以往回看要用上面这些键，而不是终端自己的滚动条。
输入历史刻意**不绑** `↑/↓`：那两个键给滚动了，而 Ink 会把按键广播给所有监听者，
两边都绑的话按一下 ↑ 会同时翻历史 + 滚屏（这是实测过的 bug）。

## 它和后端怎么说话

```
┌─────────────────────┐                         ┌──────────────────────────┐
│  myagent-tui (bun)  │                         │  java -jar myagent.jar   │
│                     │  ① spawn --serve        │        --serve           │
│  main.tsx ──────────┼────────────────────────>│                          │
│                     │                         │  AgentServer             │
│                     │  ② stdout 一行握手      │   ├─ 装配 Agent/Tools    │
│                     │<────────────────────────┤   ├─ AgentEvent → JSON   │
│                     │  {"t":"listening",      │   └─ agent.run() 在      │
│                     │   "port":…,"token":…}   │      工作线程上          │
│                     │                         │            │             │
│  backend.ts ────────┼─── ③ ws://127.0.0.1 ───>│            ▼             │
│      │              │      ?token=…           │      agent / llm /       │
│      ▼              │                         │      tool / context      │
│  store.ts           │<── 事件流 ──────────────┤      （一行都没改）      │
│      │              │    命令 ───────────────>│                          │
│      ▼              │                         └──────────────────────────┘
│  components/        │
└─────────────────────┘
```

端口用 `0` 让系统分配，所以同时开两个界面不会打架。
token 是一次性的，随后端进程生灭——监听在 127.0.0.1 不等于安全，
本机任何进程、甚至浏览器里的网页都能连本地端口（WebSocket 不受同源策略限制），
而这个后端能读写磁盘、能执行 shell 命令。服务端同时拒绝任何带 `Origin` 头的连接：
原生客户端不会发 `Origin`，发了的就是浏览器。

协议定义在两个地方，改的时候必须一起改：

- `tui/src/protocol.ts`
- `src/main/java/dev/wiles/myagent/server/Protocol.java`

两边各自硬编码 `PROTOCOL_VERSION`，对不上就拒绝连接，不带着半懂不懂往下跑。

## 代码结构

```
src/
  main.tsx              入口：解析参数 → 拉起后端 → 等首帧 → inline 渲染
  protocol.ts           线上协议类型（唯一知道后端长什么样的地方）
  backend.ts            后端进程生命周期 + WebSocket
  store.ts              会话状态：事件流怎么归并成消息
  theme.ts              语义化颜色 token + dark/light/ansi 三套主题
  format.ts             文案与格式化（措辞和 Java 版对齐）
  markdown.tsx          Markdown 子集解析 + 渲染（代码块/列表/粗体/行内代码…）
  terminal.ts           要不要独占终端 + 终端模式登记 + 各条退出路径的恢复
  layout.ts             底部固定区的高度常量 + 矮屏降级梯（纯函数）
  components/
    App.tsx             全屏布局（Yoga 钉底）+ 全局键盘 + 斜杠命令
    TranscriptView.tsx  滚动视口（自己量高 + 负 marginTop 平移）
    Banner.tsx          启动招牌（滚动内容的第一项，能往上滚回来）
    EntryView.tsx       一条消息怎么画（含工具组的折叠/展开）
    Spinner.tsx         等模型响应时的活着信号
    ApprovalDialog.tsx  危险动作确认框（完整 / 紧凑两种高度）
    PromptInput.tsx     输入框（历史、删词、光标）
    StatusLine.tsx      底部状态栏
    ClosedLine.tsx      后端断开提示
  testing/tty.ts        假终端：让测试跑真的 Ink 管线并逐行断言帧
```

## 关键设计

**1. 独占终端：备用屏幕（alternate screen），和 `vim` / `htop` 同一个机制。**
运行时占满整屏，退出时主屏**原样恢复** —— 所以退出后能看到启动前的命令历史
（`% ./myagent-tui` 那行还在），但和 agent 的对话不留在 scrollback 里，
随备用屏一起消失。

交给 Ink 的 `alternateScreen: true`，**不自己写 `1049h`**。实测它把顺序管得
比手写的对：`1049h` 落在第 0 字节、配 `?25l` 藏光标，卸载时先 `?25h` 再 `1049l`。
原来那版手写转义压根没还光标。

> 曾经这里写着「Terminal.app 默认不勾『允许应用程序使用备用屏幕缓冲区』，
> 所以要用 DECRQM 探测」。**那个开关不存在** —— `defaults read com.apple.Terminal`
> 里只有颜色/字体/bell 之类。备用屏在 Terminal.app 里本来就无条件可用（vim
> 就是这么跑的）。那段探测在测一个不存在的问题，代价是白等 300ms 并吞掉用户
> 开头敲的按键，已经删掉。

备用屏没有 scrollback，所以滚动自己做：`TranscriptView` 用
「视口 `flexGrow` + overflow:hidden + 负 marginTop 平移」实现，
视口高度用 `useBoxMetrics` 订阅、内容行高用它量（折行后的行数只有排版引擎知道）。

**2. 输入框钉在最后一行 —— 靠 Yoga，不靠算术。**
transcript 拿 `flexGrow={1} flexBasis={0}`，底部每块 `flexShrink={0}` 且高度写死，
于是「剩多少给对话」由 Yoga 算，输入框行号只跟「底部放了哪几块」有关，
和对话内容、滚动位置、折行全都无关。

以前这里是一串手算（`rows - spinnerRows - approvalRows - … - 3 - 1`），六七个
漂移源：状态栏没 `ready` 时不渲染却照样记 1 行、spinner 和确认框按钮窄屏折行、
关闭原因太长折行……算多了 Yoga 就悄悄从别人身上挤回来（实测 `COLS=20` 时输入框
被挤成 4 行还多一个空行），算少了底部就空一行。`layout.ts` 现在只负责一件事：
终端太矮时按固定顺序决定砍掉谁。

根节点的 `height={rows}` **不能丢**：Ink 的 `Output` 高度取的就是它，一旦没有
显式高度，帧高变成内容高，超过视口时 Ink 会**每帧全清**（闪屏）。

**3. 不接管鼠标：选中和复制交回终端。**
不开鼠标追踪（`1000/1002/1006`），拖拽选中、Cmd-C 都是终端原生行为。
滚轮靠 Alternate Scroll（`1007`）由终端翻译成方向键。
代价是没有「鼠标点击折叠行展开」，改用键盘 `Ctrl-O`。

**4. 外部 store 而不是组件内 state。**
事件来自 WebSocket 回调，不在 React 调度里；`text_delta` 一秒几十次。
`SessionStore` + `useSyncExternalStore` 让「谁写」和「谁读」彻底分开。

## 已知边界

- `format.ts` 的 `truncate` 按 code point 计数，没做东亚字符双宽处理，
  中文会被算成 1 列而实际占 2 列。对「一行摘要」够用，真要做列对齐得引入字宽表。
- Markdown 只做高频子集（围栏代码块/标题/列表/引用/分隔线/粗体/斜体/行内代码），
  代码块没有按语言做语法高亮，统一一个颜色。
- 每帧都重排整个 transcript。几百条消息没问题；会话长到几千条时
  需要再做视口外裁剪（只把可见范围附近的 entry 交给 Yoga）。Claude Code 的
  fullscreen 就是这么做的（官方文档：「only renders messages that are
  currently visible」），内存随会话长度保持恒定。
- **接管鼠标还没做**：所以没有「点击折叠行展开」、没有应用内选区和松手自动复制、
  没有双击选词。滚轮靠 Alternate Scroll 能滚，拖拽选中是终端原生的。
  Claude Code 的 fullscreen 是接管鼠标的（`Fn` 拖拽可临时拿回原生选中）。
- Windows 上 Ink 在全屏模式会每帧全清（`ink.js` 对 `win32` 的特殊处理），
  会闪。这个项目目前只有 bash 启动脚本，没测过 Windows。

## 测试

```bash
bun test          # 93 个：事件归并、格式化、终端决策、布局钉底
bunx tsc --noEmit # 类型检查
```

容易搞错的地方各有对应的测试：

| 文件 | 守什么 |
|---|---|
| `store.test.ts` | 事件流怎么归并成消息（含流式重复、工具分组） |
| `markdown.test.ts` | Markdown 解析的流式中间态（未闭合围栏等） |
| `terminal.test.ts` | 全屏优先级表 + 终端模式开关严格逆序 + 恢复串 |
| `layout.test.ts` | 底部固定区永远塞得进屏幕（rows 1..120 × 全部状态组合） |
| `app-layout.test.tsx` | **输入框钉在最后一行**：内容 1→200 条时行号不变 |

`app-layout.test.tsx` 用 `testing/tty.ts` 的假终端跑**真的** Ink 渲染管线
（Yoga 排版、折行、测量 effect 全都在），然后逐行断言画出来的帧。
之前的测试全是纯逻辑、一个组件都不渲染，所以整个布局是没人守的 ——
那几个窄屏漂移 bug 就是这么活到线上的。

两个写这类测试时踩过的坑，都写进注释了：
1. 断言「帧行数 == rows」是**恒真**的（根节点钉了 `height={rows}`），
   有牙齿的断言必须是**行号**。
2. `thinking` 事件只设 `step` 不设 `busy`，单用它 spinner 压根不渲染，
   断言会变成空气 —— 第一版就这么写错了，改坏 Spinner 照样全绿。

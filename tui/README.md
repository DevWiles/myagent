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

按键：`Enter` 发送 · `Esc` 中断当前这轮 · `Ctrl-O` 展开/收起本轮过程详情 ·
`↑/↓` 或 `Ctrl-P/Ctrl-N` 翻输入历史 · `Ctrl-W` 删一个词 · `Ctrl-U` 删到行首 ·
`Ctrl-C` 退出（正在跑的时候按一下是中断）。往回看更早的对话用终端自己的滚动。
斜杠命令：`/help` `/tools` `/clear` `/exit`。

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
  components/
    App.tsx             inline 布局（<Static> 历史 + 动态区）+ 全局键盘 + 斜杠命令
    Banner.tsx          启动招牌（<Static> 第一项，随历史落进 scrollback）
    EntryView.tsx       一条消息怎么画（含工具组的折叠/展开）
    Spinner.tsx         等模型响应时的活着信号
    ApprovalDialog.tsx  危险动作确认框
    PromptInput.tsx     输入框（历史、删词、光标）
    StatusLine.tsx      底部状态栏
```

## 关键设计

**1. 独占终端：备用屏幕（alternate screen）。**
运行时占满整屏，退出时主屏**原样恢复** —— 所以退出后能看到启动前的命令历史
（`% ./myagent-tui` 那行还在），但和 agent 的对话不留在 scrollback 里，
随备用屏一起消失。这是终端 agent 的标准形态（Claude Code 亦然）。

备用屏没有 scrollback，所以滚动必须自己做：`TranscriptView` 用
「固定视口高度 + overflow:hidden + 负 marginTop 平移」实现，
真实行高用 `measureElement` 每帧量（折行后的行数只有排版引擎知道）。

**终端设置是硬前提。** macOS Terminal.app 默认**不勾**「允许应用程序使用
备用屏幕缓冲区」，没勾时 `1049` 会被直接忽略、程序退化成在主屏往下打印。
所以启动时用 DECRQM（`ESC[?1049$p`）查询终端是否真的接受了，没接受就明确
打印开启方法并退出，而不是静默失败 —— 这个坑排查起来非常费时间。

**2. 不接管鼠标：选中和复制交回终端。**
不开鼠标追踪（`1000/1006`），拖拽选中、Cmd-C 都是终端原生行为。
代价是没有「鼠标点击折叠行展开」，改用键盘 `Ctrl-O`；
滚动用 ↑↓ / PgUp PgDn / Home End。

**3. 外部 store 而不是组件内 state。**
事件来自 WebSocket 回调，不在 React 调度里；`text_delta` 一秒几十次。
`SessionStore` + `useSyncExternalStore` 让「谁写」和「谁读」彻底分开。

## 已知边界

- `format.ts` 的 `truncate` 按 code point 计数，没做东亚字符双宽处理，
  中文会被算成 1 列而实际占 2 列。对「一行摘要」够用，真要做列对齐得引入字宽表。
- Markdown 只做高频子集（围栏代码块/标题/列表/引用/分隔线/粗体/斜体/行内代码），
  代码块没有按语言做语法高亮，统一一个颜色。
- 每帧都重排整个 transcript。几百条消息没问题；会话长到几千条时
  需要再做视口外裁剪（只把可见范围附近的 entry 交给 Yoga）。

## 测试

```bash
bun test          # store 的事件归并逻辑 + 格式化
bunx tsc --noEmit # 类型检查
```

组件只是把 state 画出来，没有单独测；容易搞错的是「事件流怎么归并成消息」
（`store.test.ts`）和「Markdown 解析的流式中间态」（`markdown.test.ts`）。

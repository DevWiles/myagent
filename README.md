<p align="center">
  <img src="docs/cover.svg" width="728" alt="myagent">
</p>

<p align="center">
  从零手写的 coding agent。Java 17 后端 + OpenTUI / SolidJS 终端界面，<b>不依赖任何 agent 框架</b>。
</p>

---

## 这是什么

一个能读写文件、跑命令、先规划后执行的命令行 coding agent。ReAct 循环、工具调用、
流式渲染、权限审批、审计落盘这些东西全部自己实现，为的是把 agent 到底是怎么转起来的搞清楚。

所以它有两条自我约束：

- **不用 LangChain4j / Spring AI 之类的框架。** 第三方库只有 Jackson（JSON）、OkHttp（HTTP）、
  Java-WebSocket、slf4j（日志），测试用 JUnit 5 + MockWebServer。
- **前后端是真的分开的。** 后端 `--serve` 起一个 WebSocket 服务，界面是个纯客户端。
  两边照 [`docs/protocol.md`](docs/protocol.md) 各自开工——那份文档是契约，改协议先改它。

## 快速开始

需要 JDK 17+、Maven、[Bun](https://bun.sh)，以及一个 DeepSeek API key。

```bash
export DEEPSEEK_API_KEY=sk-xxx        # 或写进 ~/.myagent/config.json
cd 你要让它干活的目录
/路径/到/myagent/myagent-tui
```

`myagent-tui` 一条命令把整套拉起来：按需 build 后端 jar、按需装界面依赖、再起界面。
两件事都带新鲜度判断，没改动就直接跳过，启动和裸跑界面一样快。
敲命令时所在的目录就是 agent 的工作区。

配置的读取优先级是 **环境变量 > `~/.myagent/config.json` > 内置默认值**：

```json
{ "apiKey": "sk-xxx", "baseUrl": "https://api.deepseek.com",
  "model": "deepseek-chat", "contextTokens": 64000 }
```

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `apiKey` | `DEEPSEEK_API_KEY` | 无 | 没有它只能裸跑界面 |
| `baseUrl` | `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 任何 OpenAI 兼容服务都行 |
| `model` | `DEEPSEEK_MODEL` | `deepseek-chat` | |
| `contextTokens` | `MYAGENT_CONTEXT_TOKENS` | `64000` | 上下文窗口，用到 75% 就触发压缩 |

`contextTokens` 除了换模型时要改，还有个用处是**把压缩逼出来**：默认值下得聊到
48,000 token 才会压缩一次，手工试不出来。调到 4,000（下限）几轮对话就能触发：

```bash
MYAGENT_CONTEXT_TOKENS=4000 ./myagent-tui
```

配错不会让程序起不来——不是整数就退回默认值，小于下限会抬上去，两种都打一行警告。

## 上手

直接说要干什么就行，agent 自己决定调哪些工具：

```
把 src/ 下所有的 TODO 注释找出来，整理成一张表
```

| 输入 | 作用 |
|---|---|
| `/plan <目标>` | 先出计划、确认之后再执行（后端宣告了 `plan` 能力才生效） |
| `/resume` | 接着上次没跑完的计划继续 |
| `Esc` | 中断当前这一轮 |
| `Ctrl-C` | 退出，并在终端留下一行会话摘要 |

常用启动参数：

```bash
./myagent-tui -d ~/proj          # 指定工作区
./myagent-tui -m deepseek-chat   # 换模型
./myagent-tui --readonly         # 只读：写文件和执行命令的工具压根不注册
./myagent-tui --rebuild          # 强制重新 build 后端
```

后端也能单独用。`java -jar target/myagent.jar` 进自带的命令行界面（不需要 Bun），
`--serve` 则是给外部界面用的无头模式。

## 安全边界

三道闸，从外到内：

1. **工作区隔离** —— 所有路径都要落在工作区内，`--unsafe` 才放行外面。
2. **只读模式** —— `--readonly` 下**不注册**写文件和执行命令的工具。这比「注册了再拦住」可靠：
   模型看不到菜单上没有的东西，就不会去试，也就无从绕过。
3. **逐次审批** —— 危险命令和写文件要用户点头。不给审批策略时默认一律拒绝。

每次运行都会在 `~/.myagent/logs/run-<时间>.jsonl` 落一份审计轨迹，一行一条 JSON，
记下工具调用、参数、结果和每一次审批。退出时界面会把这份文件的路径打在终端上。

## 项目结构

```
src/main/java/dev/wiles/myagent/
  agent/      ReAct 主循环、系统提示词
  llm/        DeepSeek 客户端、流式累积、工具协议
  tool/       工具注册表 + 内置工具（读/写/列目录/跑命令/建项目）
    safety/   工作区、命令、写文件三道守卫
  plan/       规划 → 任务图 → 执行 → 重规划
  context/    上下文窗口管理、token 估算
  audit/      审计日志
  server/     --serve 的 WebSocket 服务端
  cli/        自带的命令行界面（不走前端那套）
tui/src/
  main.tsx    入口：renderer、信号、退出收尾
  session.ts  Solid 适配层（33ms 尾沿节流）
  store.ts    与框架无关的会话状态机
  backend.ts  拉起 java 子进程、连 WebSocket
  components/ 界面组件
docs/protocol.md   前后端契约
```

## 开发

```bash
mvn test                       # 后端（796 个测试）
cd tui && bun test             # 界面（190 个测试）
cd tui && bun run typecheck
cd tui && bun run cover        # 重新生成上面那张封面图
```

封面图是**从界面代码里生成**的——招牌字模、字标排版、配色都直接 import
`tui/src/` 里的真东西，所以界面改了重跑一次就同步，不会和实际画面对不上。
图里没有一个 `<text>`，全是矩形和线段：那些 `█ ║ ═ ╔ ╗` 在不同机器上宽高比不一样，
用文字渲染等于把版式交给看图人装了什么字体。

界面这边有几个地方踩过坑，注释写在代码里，改之前值得看一眼：

- `components/App.tsx` —— 消息列表必须用 `<Index>` 而不是 `<For>`。流式输出时
  每个 token 都会换一个新的 entry 对象，`<For>` 按元素身份复用，于是整条消息的组件树
  每个 token 重建一次。实测早期块被重画了 61 次，换成 `<Index>` 之后是 2 次。
- `markdown-blocks.ts` —— 流式 markdown 的分块器。正在写的那一块**不画**，写完才画，
  所以用户看不到 `##` 这种源码，也不会因为块内重排而抖动。
- `wordmark.ts` —— 字标按**半格**排版。`tiny` 字模全由 `█▀▄` 组成，一个字符格装着上下
  两个像素，拆开算完再用「上像素当前景色、下像素当背景色」编码回去，投影才能只沉半格。

## 还没做的

Ctrl-O 展开工具调用详情、转圈动画、完整的任务图面板、可配置快捷键。
代码块的语法高亮目前只覆盖 OpenTUI 自带的几种语法（javascript / typescript / markdown / zig），
**Java 代码块不高亮**。

---

个人学习项目。分支只有 `main`（稳定）和 `dev`（开发），平时停在 `dev`，发版才合进 `main`。

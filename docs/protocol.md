# myagent 前后端协议

后端（`--serve`）和界面之间的全部约定。**这份文档是契约**：后端保证按这里写的发，
界面照这里写的收，两边可以各自独立开工。

- 协议版本：`1`
- 实现：服务端 `server/Protocol.java`、`server/AgentServer.java`
- 传输：WebSocket，**一行一个 JSON 对象**，用字段 `t` 区分类型

---

## 1. 连接

后端启动后把握手信息以**一行 JSON 打在 stdout**（日志一律走 stderr，不会混进来）：

```json
{"t":"listening","port":61772,"token":"0123456789abcdef0123456789abcdef","protocol":1}
```

界面据此连接：

```
ws://127.0.0.1:<port>/?token=<token>
```

三条规则：

| 规则 | 行为 |
|---|---|
| 只听 127.0.0.1 | 外部连不上 |
| token 对不上 | `close(1008, "bad token")` |
| 带 `Origin` 头 | `close(1008, "origin not allowed")`——浏览器一定会带，原生客户端不会 |

token 随进程生灭，每次启动都不一样。

连上后**服务端立即发一帧 `ready`**，把画第一屏需要的静态信息一次给全，省掉一轮往返。

### 1.1 局域网模式（给手机等远程客户端）

默认规则不变：不加开关就**只听 127.0.0.1**。加 `--lan` 后改绑 `0.0.0.0`，
`listening` 行多一个 `lan` 字段——服务端挑一个非环回的 IPv4 打出来，
省得客户端自己猜该连哪块网卡：

```json
{"t":"listening","port":61772,"token":"def8da...","protocol":1,"lan":"192.168.1.7"}
```

远程客户端据此连接（和本机连接**同一个 URL 格式**，只是 host 换了）：

```
ws://<lan>:<port>/?token=<token>
```

**配对方式约定为扫码**：二维码的内容就是上面这个 ws URL 本身，不发明新的
URI scheme——客户端拿到一个 `ws://` 开头的字符串就知道怎么用，手填和扫码
走同一条代码路径。谁来渲染二维码是实现细节，不属于协议。

规则有两条不变、一条升级：

| 规则 | 局域网模式下 |
|---|---|
| token 校验 | 不变。但它从「防本机其他进程」升级为**唯一门禁**——局域网里任何设备都能摸到端口，全靠 token 挡 |
| 拒绝 `Origin` 头 | 不变。远程客户端也必须是原生的，浏览器仍然进不来 |
| 传输 | **明文 ws，不加密**。局域网内可被嗅探，token 和代码内容都在里面——只在可信网络用这个模式；出门要走公网/中继是另一件事，到时候单独设计，不在这一节里凑合 |

**为什么不在这一步做 TLS**：自签证书在 Android 上要么装 CA 要么 pin，
配对复杂度全转嫁给手机端；而「家里/办公室局域网」这个威胁模型下，
明文 + 强 token 是诚实的取舍。协议在这里留的余地是——将来加中继时，
`listening` 行再多一个字段宣告，老客户端照旧连局域网，互不影响。

### 1.2 断线重连与补发（feature: `events_resume`）

终端和后端同生共死，连接断了进程也没了；手机不一样——切个后台、过个电梯，
连接必断而后端还活着。缺的那段事件不补，转录就永久豁一块口子。

**服务端**：每条<b>广播</b>事件都盖一个单调递增的 `seq`（从 1 起，随进程生灭），
最近若干条留底。定向回执（`error`、`session_list`、`memory_list`、`pong`、`ready`）
**不编号**——它们答的是某一次具体的问，问的人断线了，答案没有别人可给。

**客户端**：记住见过的最大 `seq`，重连时带上：

```
ws://<host>:<port>/?token=<token>&since=<最大seq>
```

服务端在 `ready` 之后把 `since` 以后的事件按原序补发。三条规则：

| 情况 | 服务端行为 | 客户端该怎么办 |
|---|---|---|
| `since=0` | 把留底全部补发（手机首连用，能接上当前会话的近况） | 照常按序应用 |
| 不带 `since` | 不补发（终端每次都是全新会话，补发只会搅乱它） | — |
| 留底装不下、有一段补不回来 | 先发 `{"t":"replay_gap","from":…,"to":…}` 如实报缺口，再补剩下的 | 在转录里标一段「这里缺了 n 条」，别装完整 |

`ready` 帧多一个 `lastSeq`：到此刻为止的最后一个事件号，客户端拿它对账。

**去重规则**：补发期间广播还在继续，同一条事件可能既在补发里、又被实时播了一遍。
客户端按 `seq` 去重——**小于等于已见最大 seq 的直接丢**，顺序和完整性就都有了。

`seq` 随进程生灭，和 token 一样：token 换了 = 后端换了 = 老 seq 作废，
这时候不该带 `since`（带了服务端也只当它是新号，不猜）。

---

### 1.3 中继模式：在任何网络上连

局域网模式出了门就废。要在 4G、公司网、咖啡馆都能连，问题不在加密，在**穿透**——
两端都在 NAT 后面，谁也连不到谁。

**解法和 Claude Code 的 Remote Control 同形：两端都往外拨，在一台公网机器上会合。**
官方文档那句话是这套设计的全部：本地会话「只发出站 HTTPS 请求，从不在你机器上开入站端口」，
注册之后由服务端在远程客户端和本地会话之间**路由消息**。出站连接没有任何 NAT 会拦，
所以「任何网络」是这个形状白送的。

和 Claude Code 的**唯一实质分歧是加密**：它把转录明文存在自己服务器上（换来多端同步和
断线恢复）；我们走 Happy 那条——**中继只看得见密文**，配对密钥从不上网。

```
  电脑（agent 在这儿跑）                     手机
        │                                    │
        │ 出站 wss                   出站 wss │
        ▼                                    ▼
   ┌──────────────────────────────────────────────┐
   │  relay：按房间号对接两条连接，只转发不解密    │
   └──────────────────────────────────────────────┘
```

#### 房间、密钥、配对 URL

| 名字 | 是什么 | 谁看得见 |
|---|---|---|
| 房间号 `room` | 16 字节随机数，base64url | relay 看得见（它靠这个对接） |
| 配对密钥 `k` | 32 字节随机数 | **只有两端**。relay 永远收不到 |

配对 URL 仍然是「一个 URL 本身」，不发明新 scheme（理由同 1.1）：

```
wss://relay.example.com/ws?room=<room>#<k>
```

**密钥放在 fragment（`#` 之后）不是美学选择**：URL 的 fragment 在协议层面
**永远不会被发到服务器**。把它放这儿，等于用 URL 的结构本身保证了
「密钥不上网」这件事，而不是靠实现者记得别发。

房间号和密钥**跨重启保持**，存在 `~/.myagent/relay.json`（0600）。
不这么做的话每次重启后端都要重新扫一次码，「任何时候都能连」就不成立。
要换一套：`--relay-reset`。

#### 加密封套

- **算法**：AES-256-GCM。密钥用 HKDF-SHA256 从配对密钥派生。
- **每个方向一把独立的密钥**，info 分别是 `myagent-relay v1 host->client` 和
  `myagent-relay v1 client->host`——同一把密钥两个方向各自计数，迟早撞上同一个 nonce。
- **每次会话换一个盐**：host 连上后先明文发一帧 `{"t":"hello","sid":"<16字节随机 base64url>"}`，
  两端用 `HKDF(ikm=k, salt=room||sid, info=方向)` 算这次会话的密钥。

  > 这一步不是多余的。密钥是跨重启保持的，如果计数器每次重启都从 0 开始，
  > **同一把密钥 + 同一个 nonce 会出现第二次**——GCM 在这种情况下会直接泄露
  > 认证密钥，不是「弱一点」而是彻底失效。每次会话换盐，计数器就可以放心从 0 起。

- **nonce** = 4 字节 0 + 8 字节大端计数器，每方向各自从 0 递增。
- **防重放**：收方记住见过的最大计数器，**不是严格递增的直接丢**。

数据帧（relay 原样转发，看不懂里面是什么）：

```json
{"t":"d","n":17,"c":"<base64 密文+tag>"}
```

明文就是本文档其余部分定义的那些 JSON——**中继层只是个信封，里面一个字都没变**。
所以手机端拆完信之后走的是同一套解析、同一个 reducer。

#### relay 的职责边界

relay 是这套设计里**权限最小的一环**，它只做三件事，多一件都不做：

| 做 | 不做 |
|---|---|
| 按 `room` 把两条连接对上 | 不解密（也没有密钥） |
| 转发 `d` 帧 | **不落盘、不记正文日志** |
| 踢掉超限的连接 | 不存转录、不做多端同步 |

约束：一个房间同一时刻**一个 host + 一个 client**（后到的 client 顶掉先到的）；
单帧上限 1 MB；房间空了就地回收。

房间号是 128 位随机数，猜不到；但即使有人猜中房间号挤进来，他也只能看到密文、
发不出能通过 AEAD 校验的帧——**relay 被攻破的后果被压到「拒绝服务」这一档**。

#### 断线

两端都自动重连：host 掉线重连后**重发 hello 换新会话盐**，client 据此重建密钥。
补发照 1.2 那套走——`since` 是**内层**协议的事，中继层不管。

#### 什么时候还用得上局域网模式

relay 要过一趟公网，同一屋檐下反而绕远。两种模式**同时可用**：
`--lan --relay <url>` 一起加，配对二维码优先给 relay 那个（它到哪儿都能用）。

---

## 2. 版本与兼容

`protocol` 字段在 `listening` 和 `ready` 两处都有。

**什么时候升版本**：只有破坏性改动才升——删字段、改字段含义、改一条消息的时序。
**新增消息类型、新增字段不升版本**，界面本来就必须忽略不认识的 `t` 和不认识的字段。

新能力靠 `ready.features` 宣告，不靠版本号：

```json
"features": ["plan", "replan", "resume", "resume_plan", "auto_mode", "session", "sessions",
             "compact", "memory", "roles", "team"]
```

理由是——新增能力如果靠升版本宣告，老界面会因为版本对不上直接拒连，
明明它只是用不了新功能，却连旧功能也没得用了。界面拿到 `features` 自己决定
「这个入口画不画」。

**界面必须做到**：收到未知 `t` 时静默忽略（可以记一条 debug），不能报错、不能断连。

---

## 3. 服务端 → 界面

### 3.1 会话与状态

| `t` | 字段 | 说明 |
|---|---|---|
| `listening` | `port` `token` `protocol` | 打在 stdout，不走 WebSocket |
| `ready` | 见下 | 连接建立后的首帧 |
| `busy` | `busy`(bool)、结束时另有 `durationMs` `tokens` `contextLimit` | 忙/闲切换。`busy:true` 时界面应禁用输入 |
| `error` | `message` | 命令被拒（未知命令、正忙、没 API key 等）。只发给发命令的那个连接 |
| `pong` | — | 对 `ping` 的回应 |
| `reset_done` | — | `/clear` 成功。**不删存档**，见 3.9 |
| `session_list` | `sessions[]` `currentId` | 会话历史，只回给问的那个连接，见 3.9 |

`ready` 的完整字段：

```json
{
  "t": "ready",
  "protocol": 1,
  "version": "0.3.0",
  "workspace": "/Users/x/proj",
  "branch": "dev",
  "model": "deepseek-chat",
  "planMode": false,
  "hasApiKey": true,
  "busy": false,
  "tokens": 1234,
  "sentTokens": 156800,
  "contextLimit": 1000000,
  "features": ["plan", "replan", "resume", "resume_plan", "auto_mode", "session", "sessions",
               "compact"],
  "tools": [{"name": "read_file", "description": "…"}],
  "workspaceWarning": "（可选）--unsafe 放行时的警告文案",
  "auditLog": "/Users/x/.myagent/logs/run-2026-09-17T08-09-44Z.jsonl",
  "resumable": {
    "goal": "上次那份没跑完的计划的目标",
    "remaining": 3, "total": 7, "savedAt": 1758067200000
  }
}
```

`branch` 是工作区**真正的 git 分支**（不在仓库里就是 `null`）。服务端直接读
`.git/HEAD`，不起 git 子进程：状态栏每次重绘都要这个值，几十毫秒的子进程开销
划不来，而要的东西就写在一个小文本文件里。detached HEAD 给短 sha，worktree 会
跟着 `gitdir:` 指过去。

**`resumable` 只在「这个工作区确实有没跑完的计划」时才出现**，见 3.7。
界面据此决定第一屏要不要摆出「接着跑」那个入口——放在首帧而不是让界面再问一次，
是因为画第一屏的时候就得知道。

> ⚠️ `planMode` 是**只读模式**的历史字段名（对应 CLI 的 `--readonly`），
> 和「先规划后执行」没有任何关系。名字保留是为了不破坏已有界面。

`auditLog` 是本次运行的审计轨迹落在哪。界面退出时会把它显示给用户——
界面活在备用屏里，退出后整段对话随屏幕消失，这个路径是唯一一条还能继续查的线索。
**新增字段不升协议版本**（见第 2 节），老界面忽略它即可。

### 3.2 ReAct 过程事件

一轮对话期间按顺序发出：

| `t` | 字段 | 说明 |
|---|---|---|
| `thinking` | `step` | 第几轮开始，正在等模型 |
| `text_delta` | `text` | 模型吐出的**增量**文本，不是全文 |
| `thought` | `text` | 调工具前说的那句话 |
| `tool_started` | `id` `tool` `arguments` | `arguments` 是 JSON 字符串 |
| `tool_finished` | `id` `tool` `ok` `output` `diff?` | `diff` 见下 |
| `compacting` | `phase` `done` `total` `percent` | 压缩正在进行，画进度条。`phase:"done"` 是收尾 |
| `compacted` | `tokensBefore` `tokensAfter` `summarized` | 上下文被压缩了 |
| `done` | `text` `steps` | 最终答复，本轮结束 |
| `failed` | `reason` | 跑不下去了（调用失败、被取消、超步数） |

**`tool_started` / `tool_finished` 的 `id` 是后端在发起调用那一刻编的流水号**（进程内
全局唯一），界面靠它把「开始」和「结束」配成同一行做原地更新。并行安全的调用（读文件、
搜索这类只读工具）会**凑批同时执行**，所以「开始」和「结束」在事件流里可能交错、
不再相邻——配对只能认 `id`，不能认「最近一条开始」。

**`tool_finished` 的 `diff` 是只给人看的那份改动**（统一 diff 格式，`- ` / `+ ` / `  ` 前缀，
带 `@@` 抬头）。只有写文件这类工具才带，没有改动（新建文件、内容没变）时**整个字段不发**。

它**不进对话历史**：模型刚把新内容整段写出来，再回灌一份 diff 给它，信息量是零、token 照付；
而用户没看过那段内容，「改了什么」恰恰是他唯一想知道的事。所以这条数据只走协议到界面。

> 以前 diff 只出现在 `approval_request` 的 `preview` 里。那条路**只在需要审批时才走**——
> 用惯之后绝大多数写入会被 `~/.myagent/policy.json` 的规则直接放行，
> 于是界面上从头到尾看不到任何改动内容。界面该把这个 diff 画在工具那一行下面。

计划/流水线执行期间，子任务发出的 `text_delta` / `thought` / `tool_started` /
`tool_finished` 会多带一个 **`task`** 字段（任务或工序的 id，如 `"t3"`、`"work"`）。
并行的任务各自在发事件，界面靠它把动静归到正确的那条线上；不认识这个字段的老界面
忽略即可，所以协议版本不升。主对话的事件没有这个字段。

### 3.3 审批

| `t` | 字段 |
|---|---|
| `approval_request` | `id` `kind` `action` `detail` `reason` `preview` `origin` `suggestions[]` `preferred` |

界面必须回 `approval_reply`。**不回就卡着**——后端的 agent 线程同步阻塞在这儿，
等待上限 600 秒，超时按拒绝。最后一个连接断开时后端会自行按拒绝放行。

```json
{
  "t": "approval_request",
  "id": 1,
  "kind": "bash",
  "action": "执行命令",
  "detail": "myfaketool --check",
  "reason": "未知命令 myfaketool，无法判断其影响",
  "preview": "",
  "origin": "",
  "suggestions": [
    { "label": "myfaketool --check", "patterns": ["myfaketool --check"] },
    { "label": "myfaketool *",       "patterns": ["myfaketool *"] }
  ],
  "preferred": 0
}
```

| 字段 | 说明 |
|---|---|
| `kind` | `bash` \| `write`。机器键，决定 `detail` 是命令还是路径，也决定 `preview` 怎么画 |
| `action` | 给人看的动作名。**会改措辞，别拿它当标识** |
| `detail` | 命令原文，或工作区相对路径 |
| `reason` | 为什么要问。同时给用户和模型看 |
| `preview` | 写文件时是 unified diff 正文，没有就是空串 |
| `origin` | 计划/流水线里是「编码者 · t3」，单轮对话里是空串 |
| `suggestions` | 「以后都同意」可以存成哪几条规则，**从紧到松**。空数组表示这次记不成规则 |
| `preferred` | 默认选中第几档 |

**`patterns` 才是回给后端的东西，`label` 只用来显示。** 多段命令（`a && b`）一档里会有
多条 pattern，一段一条——少回一条的话，用户按了「以后都同意」，下次还会被问。

**后端只接受它自己提议过的 pattern。** 回一条没在 `suggestions` 里出现过的
（比如 `*`），后端当它没回：这次照批，但不存规则。所以界面不必、也不该
自己拼规则字符串。

存下来的规则落在 `~/.myagent/policy.json`，跨会话有效，`reset` 不会清掉它——
那是用户挑定并存过盘的东西，不是这一场会话的临时授权。撤销靠手改那个文件，
改完立刻生效，不用重启。

> **没有「本会话都同意」这档了。** 老版本的 `always` 是一个全局开关：按一次，
> 这个会话里所有危险动作全部静默放行。它治的是「被问烦了」，代价是把整道防线
> 关掉——为了批一条 `mvn test`，顺手授权了 `curl` 和 `rm`。
> 现在 `always` 回的是一条**有范围的规则**，治的还是同一个病，但不靠少看东西来治。

### 3.4 先规划后执行（feature: `plan`）

| `t` | 字段 | 说明 |
|---|---|---|
| `plan_created` | `goal` `tasks[]` `layers[][]` | 规划产出，见下 |
| `plan_confirm_request` | `id` | 等界面确认，必须回 `plan_confirm_reply` |
| `plan_task_started` | `taskId` `taskType` `role` `roleTitle` `description` | `role` 见 3.13 |
| `plan_task_finished` | `taskId` `status` `durationMs` `result` `error` | `result`/`error` 可能是 `null` |
| `plan_tasks_added` | `parentId` `tasks[]` | 图长大了，见 3.5 |
| `plan_revised` | `failedTaskId` `tasks[]` | 某一步失败后补出了救场任务，见 3.6 |
| `mode_switched` | `mode` `reason` | 一条 `prompt` 被自动改走规划了，见 3.8 |
| `plan_finished` | `status` `succeeded` `failed` `skipped` `replans` `durationMs` | 用户否决时只有 `status` |

`plan_created`：

```json
{
  "t": "plan_created",
  "goal": "模型用自己的话复述的目标，界面要显眼地展示——用户靠它判断有没有理解偏",
  "tasks": [
    {"id": "t1", "type": "FILE_READ", "role": "explorer", "roleTitle": "调研",
     "description": "读 pom.xml",
     "acceptance": "说清 Java 版本是多少",
     "status": "PENDING", "parentId": null, "dependsOn": []},
    {"id": "t3", "type": "FILE_WRITE", "role": "coder", "roleTitle": "编码",
     "description": "按 {{t1}} 查到的版本改 X",
     "acceptance": "X 里的版本号和 pom 一致",
     "status": "PENDING", "parentId": null, "dependsOn": ["t1", "t2"]}
  ],
  "layers": [["t1", "t2"], ["t3"]]
}
```

**`layers` 是服务端算好的拓扑分层**，同一层内部互不依赖。界面直接照着画，
不要自己按 `dependsOn` 再推一遍——那等于把同一套图算法在两种语言里各实现一次，
迟早有一边算错。

**`acceptance` 是这一步的完成标准**，可能是 `null`（老计划、或模型没给）。
它回答的是「做到什么算完」，和 `description` 的「做什么」是两件事，值得单独显示——
用户看一眼就知道这一步的验收线划在哪儿。

**`description` 和 `acceptance` 里可能出现 `{{t1}}` 这样的占位符**，表示「这里要填上
t1 的产出」。**界面原样显示就行**，不要试图自己替换——真正的产出要等 t1 跑完才有，
替换发生在服务端执行那一步之前。占位符引用的任务一定在 `dependsOn` 里，这条由服务端保证。

**`role` 是派给这一步的角色**，`roleTitle` 是它的中文名（见 3.13）。
`type` 仍然照发，两者回答的不是同一个问题：`type` 说「这一步会对世界做什么」，
`role` 说「派谁去、用什么姿势」。同一个 `FILE_READ` 底下既可能是 `explorer` 也可能是
`reviewer`——**只认 type 的界面会把这两件事画成一样的**。

`taskType` 六种：`PLANNING` `FILE_READ` `FILE_WRITE` `COMMAND` `ANALYSIS` `VERIFICATION`
`status` 五种：`PENDING` `RUNNING` `SUCCEEDED` `FAILED` `SKIPPED`
计划 `status` 五种：`CREATED` `RUNNING` `COMPLETED` `FAILED` `CANCELLED`

### 3.5 执行期间图会长大

`PLANNING` 类型的任务跑完会**拆出一批子任务插进图里**，这时发 `plan_tasks_added`：

```json
{"t":"plan_tasks_added","parentId":"t1",
 "tasks":[{"id":"t1.1","type":"FILE_WRITE","role":"coder","roleTitle":"编码",
           "description":"改 A",
           "status":"PENDING","parentId":"t1","dependsOn":[]}]}
```

界面要把它们**就地插到父任务下面**，而不是重画整张图。
子任务最多再拆一层（不会有「孙子任务」），单批最多 5 个。

### 3.6 失败之后会重规划（feature: `replan`）

一步失败时，服务端**不一定就此收摊**。它会把「原目标 + 已经做成的产出 + 失败原因 +
被连累跳过的活」交回模型，让它补出接下来该走的几步，然后发 `plan_revised`：

```json
{"t":"plan_revised","failedTaskId":"t3",
 "tasks":[{"id":"r1","type":"FILE_READ","role":"explorer","roleTitle":"调研",
           "description":"查清 App.java 的真实位置",
           "acceptance":"给出实际路径","status":"PENDING","parentId":null,"dependsOn":[]}]}
```

三条规矩：

| 规矩 | 说明 |
|---|---|
| **一份计划只重规划一次** | 补救又失败就如实收场，不会没完没了 |
| **补救任务的 id 以 `r` 开头**，且只依赖这批新任务内部 | 不会连回原计划里已经废掉的节点 |
| **模型可以交白卷** | 判断这事儿做不成时不发 `plan_revised`，直接按原样收场 |

**和 `plan_tasks_added` 的区别要画出来**：后者是计划**按原样长大**（PLANNING 任务拆下一层），
前者是**原路走不通、换了条路**。混成一种显示，用户就看不出计划中途拐过弯。

`plan_finished` 里的 `replans` 是这一轮重规划了几次。**注意它和 `status` 的组合**：

| 组合 | 含义 | 界面该怎么说 |
|---|---|---|
| `COMPLETED` + `replans: 0` | 一次跑通 | 计划完成 |
| `COMPLETED` + `replans: 1` | **中途换过路，最后还是达成了** | 计划完成，但值得提一句绕过弯 |
| `FAILED` + `replans: 1` | 补救也没救回来 | 计划未完成 |

中间那种最容易报错：`failed` 计数大于 0（确实有一步红过），但目标达成了。
**别按「failed > 0 就是失败」去显示**——服务端已经在 `status` 里下好结论了，照着它说。

### 3.7 断点续跑（feature: `resume`）

计划**每了结一个任务就存一次盘**（`~/.myagent/plans/`，一个工作区一份）。
进程没了——崩溃、关终端、机器重启——下次连上来，`ready.resumable` 会带上那份存档的摘要。

界面发 `{"t":"resume"}`，服务端把计划立回来，**走和新计划完全一样的那条路**：
`plan_created` → `plan_confirm_request` → 确认后逐个任务跑。

| 规矩 | 说明 |
|---|---|
| **续跑也要确认** | 隔了一段时间用户未必还记得当初让它干什么，代码也可能变了 |
| **做成的步骤不重跑** | 它们在 `plan_created` 里就是 `SUCCEEDED`，产出也还在 |
| **上次跑到一半的那一步会重跑** | 它没跑完，状态退回 `PENDING` |
| **计划跑完（不管成没成）就删档** | 只有「被叫停」才留着 |

**`cancel` 之后存档是留着的**：用户按停止多半是想先去干别的，不是不要了。
所以一次 `cancel` 之后，下次 `ready` 里照样会有 `resumable`——
界面该把「接着跑」一直摆在那儿，而不是只在启动时看一眼。

存档认工作区：在别的目录连上来，哪怕文件名撞了也不会给你续——
一份计划里的任务描述全是针对某个仓库写的。

### 3.8 简单任务不规划（feature: `auto_mode`）

不是每句话都值得先拆计划。「PlanParser 为什么从第一个 `{` 抠起」是个查询，
为它多花一次规划调用纯属浪费。所以服务端收到 `prompt` 时会先做一次**字面判断**，
决定直接对话还是改走规划。判断分三档，只有前两档会改道：

| 档 | 服务端行为 |
|---|---|
| 直接干 | 照常走对话，不发 `mode_switched` |
| 拿不准 | **并到「规划」处理**（见下） |
| 该规划 | 发 `mode_switched`，然后走 3.4 那套完整流程 |

```json
{"t":"mode_switched","mode":"plan","reason":"是改动面比较大的活、范围铺得比较开"}
```

**为什么「拿不准」不单开一条协议消息去问用户**：规划本来就带一道确认
（`plan_confirm_request`），「先问一句」的效果它已经有了，代价只是多花一次规划调用。
终端版能直接读一行 y/n，所以那边是真的会问；无头模式为此新增一轮往返不划算。

**界面该做的事**：收到 `mode_switched` 就把 `reason` 显示出来。不显示的话，
用户发的是 `prompt`、回来的却是 `plan_created`，会完全不知道发生了什么。

**`/plan`（也就是 `plan` 命令）不受这套判断影响**——显式表态优先于启发式。

> ⚠️ 这是**字面**判断，没有理解。「把那个改一下」六个字背后可能是三天的活。
> 它认的是明显的两头，中间那片本来就该由人来定，所以 `plan_confirm_request`
> 那一步任何时候都不能省。

### 3.9 会话：接着聊，或者挑一段接着聊（feature: `session` / `sessions`）

对话**每说完一轮存一次盘**：`~/.myagent/sessions/<工作区>/<会话 id>.json`，
**一次会话一份**。会话 id 形如 `20260918-143210-3f9a`（本地时间 + 四位随机），
按名字排序就是按时间排序。

> 原来是「一个工作区一份」，覆盖式写入。那个设计只在没有选择器的前提下成立：
> 一旦要让用户回头翻三天前那段对话，覆盖式写入意味着那段对话从来就没被留下来过。
> 格式 1 的老存档会在第一次列表时被收成历史里的一条会话，不会丢。

**两条路，别混**：

| 界面发 | 干什么 | 什么时候用 |
|---|---|---|
| `{"t":"continue"}` | 接**最近**那段，不用挑 | 绝大多数时候用户要的就是这个 |
| `{"t":"sessions"}` → `{"t":"session_resume","id":"…"}` | 列出历史，挑一段接 | 用户要翻更早的 |

连上来时 `ready.continuable` 带最近那段的摘要：

```json
"continuable": {"id": "20260918-143210-3f9a", "branch": "dev",
                "turns": 4, "messages": 11,
                "firstMessage": "这个项目怎么跑测试", "savedAt": 1758100000000,
                "count": 3}
```

`count` 是这个工作区一共几段。**界面靠它决定要不要放出「挑一段」那个入口**——
只有一段时摆个选择器是纯噪音，直接 `continue` 就行。

会话列表<b>不放进 `ready`</b>：一个工作区能攒下几十段，而首帧每次连上来都要发。
要列表的界面自己问一句 `{"t":"sessions"}`，服务端回：

```json
{"t":"session_list","currentId":"20260918-150000-1a2b","sessions":[
  {"id":"20260918-143210-3f9a","title":"这个项目怎么跑测试","turns":4,
   "model":"deepseek-chat","branch":"dev","bytes":34211,
   "startedAt":1758099000000,"savedAt":1758100000000}]}
```

一行够画成「标题 + 时间 · 分支 · 大小」。三样各有各的用处：**时间**答「还新鲜吗」，
**分支**答「那段是在哪条线上聊的」（往往一眼就能排除掉一半），
**大小**是「这段聊得有多沉」的便宜代理——几十 KB 是随手问两句，几百 KB 是啃了半天。

`branch` 可能是 `null`：不在 git 仓库里，或者那是格式 1 的老存档
（老结构没记过分支，用今天的分支去填等于编一个出来）。

最近存过的在最前面。`currentId` 是此刻正待着的那一段——**标出来**，
否则用户会挑中自己现在就在的那段，然后发现「什么都没变」。
这条只回给问的那个连接：别的界面没在挑会话，不该收到一坨列表。

`continue` 和 `session_resume` 都回一条 `session_restored`（**广播**，
连着的每个界面都得知道当前会话被换掉了）：

```json
{"t":"session_restored","id":"20260918-143210-3f9a","messages":11,"turns":4,
 "firstMessage":"这个项目怎么跑测试","savedAt":1758100000000,
 "tokens":8240,"contextLimit":64000}
```

这条只是回执，**不会重播历史内容**。界面要显示上次聊了什么，
自己留一份就好——服务端不负责替界面记住已经画过的东西。

| 规矩 | 说明 |
|---|---|
| **不自动恢复** | 和计划续跑同理：隔了不知道多久，代码可能变了，用户也未必还记得当时在聊什么 |
| **当前已经有对话时拒绝** | 恢复会把眼前这段冲掉，这个决定该由用户做。先 `reset` 再 `continue` / `session_resume` |
| **`reset` 不删存档** | 清空的意思是「眼前这段我不要了」，不是「这段从来没发生过」。旧的留在历史里还能挑回来，`reset` 只是换一个新会话 id 从头记 |
| **`session_resume` 的 id 可能已经失效** | 列出来到挑中之间隔着用户的思考时间，那段会话可能被另一个界面清掉了。回 `error`，不要静默失败 |
| **id 认形状** | 服务端只认 `20260918-143210-3f9a` 这个形状。这个 id 要拿去拼文件名，`../../../.ssh/id_rsa` 一类的东西一律当没有 |
| **存档不含 system 消息** | 见下 |

**存档里为什么没有 system 消息**，这是最容易埋雷的地方：

system 是每轮请求的第 0 条，里面有工作区路径**以及长期记忆的索引**。
存档带着它、恢复时照搬的话，上次会话之后新记的所有东西在恢复出来的会话里
**永远看不见**——而现象是「remember 明明成功了，它却不知道」，极难查。
所以存档结构上就不存 system，恢复时由服务端现场生成一条新的。

**残缺的存档会被修剪**。进程可能死在「模型点了三个工具、只回来两个结果」的当口。
那种消息序列一旦留在历史里，之后**每一次**请求都会 400，而且重启也不会好——
存档还在，一恢复又坏。所以恢复前会退回到最后一个「模型把话说完」的位置，
`session_restored.messages` 给的是**真正恢复的条数**，可能少于存档里的。

存档认工作区：在别的目录连上来不给恢复——一段对话里全是针对某个仓库的上下文。

---

### 3.10 用量：`tokens` 和 `sentTokens` 不是一回事

`ready` 和 `busy:false` 上都带这两个数，**别把它们当成一个东西**：

| 字段 | 含义 | 特点 |
|---|---|---|
| `tokens` | **当前这一份窗口**有多大 | 瞬时快照，压缩之后会变小 |
| `sentTokens` | 这段会话**累计真正发出去**的提示词 | 只增不减 |

差别来自 ReAct：每走一步都要把整个窗口重发一遍。一轮跑 5 步、窗口 50k，
`tokens` 是 50k，而 `sentTokens` 涨了 250k。实测一段三轮对话（含一次读文件）
`sentTokens` 是 16.2k，而结束时 `tokens` 才 4k 出头。

**界面要把两个都显示出来**。只显示 `tokens` 的话，用户会拿它当花销，
而真实数字可能是它的十倍；只显示 `sentTokens` 的话，又看不出离窗口上限还有多远。

`sentTokens` 全部来自响应里的 `usage`，不是估算。网关不回 usage 时这个数会低报——
后端不会拿估算值去凑，宁可少报也不给假精确。

---

### 3.11 上下文压缩（feature: `compact`）

压缩只动**发给模型的那份窗口**，不动会话记录。记录是只追加的，原文永远留在存档里——
所以 `session_list` 里那一行显示的始终是用户真说的第一句，压过多少次都一样。

后端按占用率分三档动作，界面不需要参与决策，只需要把 `compacted` 画出来：

| 占用 | 后端干什么 | 界面看得到什么 |
|---|---|---|
| 80% | 后台**先把摘要算好**，不换上下文 | 什么都没有——这正是重点 |
| 93.5% | **有算好的就换上**，零调用、无卡顿 | 一条 `compacted` |
| 98.7% | 没算好也得压，此时才会卡一下 | 一串 `compacting` + 一条 `compacted` |
| 跌回 80% 以下 | 把没用上的那份丢掉 | 无 |

```
界面 → {"t":"compact"}
后端 → {"t":"busy","busy":true}
后端 → {"t":"compacting","phase":"map","done":0,"total":3,"percent":4}
后端 → {"t":"compacting","phase":"map","done":0,"total":3,"percent":19}
后端 → {"t":"compacting","phase":"reduce","done":2,"total":3,"percent":72}
后端 → {"t":"compacting","phase":"done","done":3,"total":3,"percent":100}
后端 → {"t":"compacted","tokensBefore":48210,"tokensAfter":19044,"summarized":true}
后端 → {"t":"busy","busy":false,"tokens":19044,...}
```

#### `compacting`：画进度条

压缩是一次（或几次）完整的模型调用，几十秒起步。没有这个事件的话，用户敲完
`/compact` 面对的是一块完全不动的屏幕——按键丢了、后端挂了、还是在干活，
三种情况长得一模一样。

| 字段 | 含义 |
|---|---|
| `phase` | `map` 逐块摘要 / `reduce` 合并成一份 / `done` 收尾 |
| `done` / `total` | 走完几步 / 一共几步。`total` = 要摘的块数 + （要 reduce ? 1 : 0） |
| `percent` | 0~100 的整体进度 |

四条规矩：

1. **只有用户等得到的压缩才发**。`/compact`，以及占用到 98.7% 时那次不得不卡的
   同步压缩。80% 那条线上的后台预压缩**刻意不发**——它的全部价值就是无感，
   弹一条「正在压缩」等于把它的意义抵消掉。有现货可用时（零调用、瞬间完成）
   同样一条都不发，免得界面上闪过一根来不及看清的条。
2. **`phase:"done"` 一定会来**。压成了、压失败了、用户按 Esc 取消了，三种都发。
   界面收到它就把进度条**整条移走**——留一根凝固在 62% 的条，比没有进度条更让人疑惑。
   稳妥起见，界面也该在 `busy:false` 时兜一道。
3. **`percent` 的内层是估的**。外层（第几块 / 共几块）是准的；内层是「摘要已经吐了
   多少字 ÷ 按输入规模估的预期长度」。之所以要这一层：1M 窗口下整段会话通常只切出
   **一块**，纯按块数报的话条会在 0% 上干等几十秒再直接跳 100%。代价是条可能提前
   走完或者顶在 99% 等收尾，所以**界面要把「估算」这件事写出来**——
   让人以为这是精确进度，比没有进度条更糟。
4. **进度期间 `busy` 为 true**，和一轮对话同一个闸。压缩会改写 agent 的派生物，
   和对话并发跑会互相踩；上了闸之后 Esc 也能真的把它掐断。

几条容易误会的地方：

1. **`compacted` 不一定意味着摘要**。`summarized` 为 false 时是「工具输出被裁了」——
   只有一轮对话时压不出摘要（最近几轮是原样保留的），但过长的工具输出照样会被裁。
   这个也要显示：不然用户看到的是「它刚读过的文件怎么下一句就忘了」，而界面一声不吭。
2. **`/compact` 救不了「单轮太大」**。最近几轮是硬约束，不为预算让路。
   粘了一大段文本撑破窗口时，后端回 `failed` 并说明该开新会话或调大 `contextTokens`——
   **这种时候别提示用户去按 `/compact`**，按了也没用。
3. **`tokens` 可能是实测的，也可能是估的**。后端会向模型要真实的 `prompt_tokens`；
   网关不支持时退回估算，并把三条线整体前移。界面不用区分，照显示就行。

---

### 3.12 长期记忆（feature: `memory`）

跨会话的事实存储，和上下文压缩是两回事：压缩管的是「这一次会话发出去多少」，
这里管的是「下次打开这个项目还记得什么」。

```
界面 → {"t":"memory","action":"list"}
后端 → {"t":"memory_list","entries":[
          {"name":"mvn-offline","description":"这台机器 maven 要加 -o",
           "scope":"project","source":"user_correction","sourceLabel":"用户纠正过",
           "created":"2026-09-12","topicKey":"构建命令",
           "supersededBy":null,"turnId":null},
          {"name":"uses-npm","description":"这个项目用 npm",
           "scope":"project","source":"observation","sourceLabel":"自动记录·可撤销",
           "created":"2026-09-18","topicKey":"包管理器",
           "supersededBy":"uses-bun","turnId":"seg[8,22)"}
       ]}

界面 → {"t":"memory","action":"undo"}
后端 → {"t":"memory_changed","action":"undo","name":"uses-bun","description":"…"}
       没有可撤销的自动记录时回 `error`

界面 → {"t":"memory","action":"clear_auto"}
后端 → {"t":"memory_changed","action":"clear_auto","removed":3}
```

几条容易误会的地方：

1. **`list` 给的是审计视角，含 `supersededBy` 非空的条目**。那些已经<b>不再注入提示词、
   也 recall 不到</b>，但仍留在盘上——用户要能看见系统曾经记过什么，
   否则「它为什么突然改口了」永远查不清。界面该把它们画得弱一些，而不是隐藏。
2. **`undo` / `clear_auto` 只认自动记录**（`source` 为 `observation`）。
   用户自己让记的一条不碰——他记的时候是清醒的。
3. **撤销会让被它取代过的旧事实重新生效**。少了这一步，撤销之后新旧都不生效，
   用户看到的是「两条都没了」，比不给撤销更糟。
4. **这三个动作都不拦 `busy`**。它们不碰会话记录，也不发模型调用，
   跑着任务时查一眼记忆是安全的——这一点和 `compact` 不同。

---

### 3.13 角色（feature: `roles`）

计划里的每一步都**派给一个角色**。角色决定三件事：执行这一步的子循环拿得到哪些工具、
系统提示词里那段行事规矩是什么、最多跑几轮。所以「派了谁」是执行层的事实，
不是显示用的标签。

首帧 `ready` 里带整份名册：

```json
"roles": [
  {"id": "explorer", "title": "调研", "summary": "查清某件事：读文件、列目录…",
   "type": "FILE_READ", "mutates": false,
   "tools": ["read_file", "list_dir", "grep", "glob"]},
  {"id": "reviewer", "title": "审查", "summary": "对着已经存在的代码挑毛病…",
   "type": "FILE_READ", "mutates": false,
   "tools": ["read_file", "list_dir", "grep", "glob"]},
  {"id": "coder", "title": "编码", "summary": "新建或修改文件",
   "type": "FILE_WRITE", "mutates": true,
   "tools": ["read_file", "list_dir", "grep", "glob", "write_file", "create_project"]}
]
```

**为什么把名册整份发过去，而不是让界面自己写一张表**：和 `layers` 由服务端算好是
同一个理由——同一份事实在两种语言里各写一遍，迟早有一边过期。这里过期的症状还特别隐蔽：
后端加了个角色，界面不认得它的 id，那一步就显示成一个空标签，而其它一切正常。

给界面实现者的三条：

1. **别按 id 硬编码文案和颜色**。要显示中文名就用 `title`，要分「会不会动手」就用
   `mutates`——名册是会长的（现在七个），硬编码的那张表只会停在你写它的那天。
2. **`role` 可能是你没见过的 id**。老界面连上新后端就是这个情况。
   退回显示 `type` 就行，别画空白，更别断连（第 2 节那条通则）。
3. **`type` 和 `role` 都要用**。同一个 `FILE_READ` 底下既有 `explorer` 也有 `reviewer`，
   只画 type 的界面会把「去查清楚」和「去挑毛病」显示成同一件事。

只读模式（`--readonly`）下，`mutates` 为 true 的角色一个都不会出现在计划里——
规划提示词里就没有它们，产出还要再校验一遍。

---

### 3.14 流水线（feature: `team`）

三种**固定分工**加一条反馈回路：

```
plan(scout 摸清现场、拆出任务) → workers 并行施工 → review(reviewer 验收)
        ↑___________重新规划___________|________重新执行________↑
```

规划工序拆出**几个 worker 任务**（角色、依赖都由它定），互不依赖的并行跑
（只读并行、会动手的独占，规矩和计划执行器同一条）；验收对着方案和全部施工结果查
真实文件，最后一行给三选一的结论：**「通过」「重新执行」（方案没错，按原方案重做）
「重新规划」（方案本身有问题，重拟）**。打回会被执行——最多 **3 轮验收**，
用完还过不了就如实报 `PARTIAL`。

> 2026-09-22 之前这里是「圆桌」。帧名里的 `seat` 是那个时期留下的，
> **名字和字段形状原样保留**（换名就是破坏性改动），语义是一道**工序**。

| `t` | 字段 | 说明 |
|---|---|---|
| `team_started` | `goal` `reason` `seats[]` | 开场。此刻只有规划那一道工序——worker 有几个、派谁，要等规划跑完才知道 |
| `team_seats_added` | `seats[]` | **座位表长大了**：规划拆出的 workers、每轮的验收工序、返工/重拟长出的新工序，全走这帧。按执行顺序追加到已有座位表末尾即可 |
| `team_seat_started` | `seatId` `role` `roleTitle` | 轮到这道工序。**几个只读 worker 会同时在跑**，靠 `seatId` 分线 |
| `team_seat_finished` | `seatId` `role` `status` `durationMs` `opinion` `error` | 倒了的、被跳过的也发 |
| `team_synthesis_started` | — | **不再发送**（圆桌时期的合议帧）。老界面收不到它没有影响：它只驱动一行「合议中…」 |
| `team_finished` | `status` `spoke` `silent` `verdict` `rounds` | 收工。`rounds` 是验收跑了几轮 |

```json
{"t":"team_started","goal":"给配置模块加环境变量覆盖",
 "reason":"拟方案 → 并行施工 → 验收；打回可重新执行或重新规划",
 "seats":[{"id":"plan","role":"scout","roleTitle":"探路","summary":"…","status":"PENDING"}]}

{"t":"team_seats_added",
 "seats":[{"id":"t1","role":"coder","roleTitle":"编码","summary":"…","status":"PENDING"},
          {"id":"t2","role":"verifier","roleTitle":"验证","summary":"…","status":"PENDING"}]}
```

工序 id 的规律（界面不用解析它，但日志里认得出）：规划是 `plan`、`plan2`…（每重拟一次加一）；
验收是 `review`、`review2`…（每轮一个）；worker 第一轮用规划给的原始 id，
之后每轮带 `r<轮次>.` 前缀（如 `r2.t1`），**全场唯一**。

`team_finished.status` 四种，界面要按**验收结论**而不是「跑没跑完」来画：

- `COMPLETED`——验收放行。`verdict` 是最后一轮审查正文。
- `PARTIAL`——活干了但**没有担保**：轮次用完仍被打回（`verdict` 是问题清单，
  最值钱的一份产出）、验收那道工序自己没跑成、或重拟方案没成（后两种 `verdict`
  可能是 `null` 或上一轮的打回意见）。都意味着**工作区里已经有改动，但没人担保它对**。
- `FAILED`——第一轮方案就没拟成，什么都没干。`verdict` 是 `null`。
- `CANCELLED`——用户叫停。已经落定的工序原样留着，`verdict` 是 `null`。

给界面实现者的四条：

1. **座位表是长大的，不是一次给齐的。** `team_started` 只有规划一席；
   后续每一批都是 `team_seats_added`。收到不认识的 `seatId` 的 started/finished 帧
   说明有一帧丢了，别崩，忽略即可。
2. **`opinion` 和流式文本是两份东西。** 执行期间各道工序的话混在一条 `text_delta` 流里
   （带 `task` 字段分线）；`team_seat_finished.opinion` 是归到这道工序名下的**定稿**。
3. **倒了的、被跳过的工序也有终态帧**，`status` 是 `FAILED`／`SKIPPED`。
   注意 worker 失败**不结束流水线**——验收会看到失败并决定重新执行还是重新规划，
   所以一个红了的 worker 后面还会有新工序长出来，这是正常剧情不是错乱。
4. **`PARTIAL` 一定要画出来，并且分清两种。** `verdict` 非空是「打回没修完」；
   `verdict` 为空是「没审成」。混成一种，用户会把「审查发现了问题」误读成「审查没跑」。

界面 → 服务端：

```json
{"t":"team","goal":"给配置模块加环境变量覆盖"}
```

`roles` 字段**已废弃**：分工由规划工序决定（它给每个任务派角色），不再点名。
老界面传了会被忽略，不报错。

---

## 4. 界面 → 服务端

| `t` | 字段 | 说明 |
|---|---|---|
| `prompt` | `text` | 普通一轮对话 |
| `plan` | `goal` | 先规划后执行 |
| `team` | `goal` | 开一条流水线（feature: `team`），见 3.14。`roles` 字段已废弃，传了会被忽略 |
| `plan_confirm_reply` | `id` `approved`(bool) | 回应 `plan_confirm_request` |
| `approval_reply` | `id` `decision`: `yes` \| `no` \| `always`，`rules[]`（可选），`feedback`（可选） | 回应 `approval_request`。见 3.3 |
| `cancel` | — | 中断当前这一轮 |
| `resume_plan` | — | 接着跑上次没跑完的计划，见 3.7。没有存档时回 `error` |
| `resume` | — | `resume_plan` 的老名字，仍然接受。那会儿只有计划能续，现在 `/resume` 在终端里指的是挑会话，所以协议这边改叫 `resume_plan`；改名不该让还没更新的界面连不上 |
| `continue` | — | 接**最近**那段会话，见 3.9。没有存档、或当前已有对话时回 `error` |
| `sessions` | — | 要会话历史列表，回 `session_list`。见 3.9 |
| `session_resume` | `id` | 按 id 接一段历史会话，见 3.9。id 认不出、找不到、或当前已有对话时回 `error` |
| `compact` | — | 现在就压一次上下文（feature: `compact`），见 3.11。忙的时候会被拒 |
| `memory` | `action`: `list` \| `undo` \| `clear_auto` | 查看 / 清理长期记忆（feature: `memory`），见 3.12。**不拦 busy** |
| `reset` | — | 清空对话历史、换一段新会话。**不删旧存档**。忙的时候会被拒 |
| `ping` | — | 保活 |

字段缺失或类型不对一律退回默认值，不会让服务端崩。未知 `t` 回一条 `error`。

---

## 5. 一轮完整的计划时序

```
界面 → {"t":"plan","goal":"给 Task 加上重试能力"}
后端 → {"t":"busy","busy":true}
       {"t":"plan_created","goal":"…","tasks":[…],"layers":[["t1","t2"],["t3"]]}
       {"t":"plan_confirm_request","id":1}

界面 → {"t":"plan_confirm_reply","id":1,"approved":true}

后端 → {"t":"plan_task_started","taskId":"t1",…}
       {"t":"thinking","step":1}                 ← 执行期间照常发 ReAct 事件
       {"t":"tool_started","id":7,"tool":"read_file",…}
       {"t":"tool_finished","id":7,…}
       {"t":"plan_task_finished","taskId":"t1","status":"SUCCEEDED",…}
       {"t":"plan_tasks_added","parentId":"t1","tasks":[…]}   ← 仅 PLANNING 任务
       …
       {"t":"plan_task_finished","taskId":"t3","status":"FAILED",…}
       {"t":"plan_revised","failedTaskId":"t3","tasks":[…]}   ← 某步失败且补得回来时才有
       …
       {"t":"plan_finished","status":"COMPLETED","succeeded":7,"failed":1,"skipped":0,"replans":1,…}
       {"t":"busy","busy":false,"durationMs":42627}
```

**执行期间的 ReAct 事件按 `task` 字段归属**（见 3.2）。互不依赖的只读任务会并行执行，
几条线的事件交错到达，「最近一条 `plan_task_started`」不再可靠；会动世界的任务
（`FILE_WRITE` / `COMMAND` / `VERIFICATION` 角色）独占运行，同一时刻只有它一个在动。

用户否决时：

```
界面 → {"t":"plan_confirm_reply","id":1,"approved":false}
后端 → {"t":"plan_finished","status":"CANCELLED"}      ← 注意：没有那三个计数字段
       {"t":"busy","busy":false,…}
```

---

## 6. 边界与错误

| 情况 | 后端行为 | 界面该怎么办 |
|---|---|---|
| 正忙时又发 `prompt`/`plan` | 回 `error`，不排队 | 提示用户，或先发 `cancel` |
| `plan_confirm_reply` 的 `id` 对不上 | 静默丢弃（过期回答） | 用最近一次 `plan_confirm_request` 的 id |
| 计划确认超过 900 秒没回 | 按否决处理，发 `plan_finished` | — |
| 没有任何连接时需要确认 | 直接否决，不执行 | — |
| `cancel` | 调度器不再开新任务、掐断正在跑的子循环和规划请求 | 正在跑的那个任务会变 `FAILED`，其余 `SKIPPED` |
| 某个任务超时（默认 10 分钟） | 按这一步失败处理，发 `plan_task_finished`（`status:FAILED`、`error` 里写明超时） | 和别的失败一样显示；下游会收到 `SKIPPED` |
| 某道工序超时（默认 15 分钟） | 按这道工序失败处理。worker 超时不结束流水线（验收会看到并决定怎么办）；第一轮拟方案超时→`FAILED`；验收超时→`PARTIAL` | 和失败一样显示 |
| 并行工具调用整批超时（默认 60 秒） | 超时那个发 `tool_finished`（`ok:false`），其余照常收 | 那一行标失败，别继续转圈 |
| 规划失败（模型连 3 次给不出可用计划） | 发 `failed`，带原因 | 照常显示错误 |
| 某步失败、且补得回来 | 发 `plan_revised`，补救任务接着跑 | 把新任务插进去，标明这是改道 |
| 某步失败、但模型判断补不了 | 不发 `plan_revised`，照原样收场 | 照常显示失败 |
| 重规划本身挂了（调用失败、给的图不合法） | 静默忽略，照原样收场 | 界面无感，和没配重规划一样 |
| `prompt` 被判定为该规划 | 发 `mode_switched`，转走计划流程 | 显示 `reason`，别让用户莫名其妙 |
| `resume_plan` 但没有存档 | 回 `error` | `ready.resumable` 不存在时就别放出这个入口 |
| `session_resume` 的 id 已经没了 | 回 `error` | 列表和挑中之间隔着用户的思考时间，别假设它还在 |
| 存档读不出来 / 格式过期 | 当作没有存档 | 同上 |
| 存不下（磁盘满、只读） | 计划照跑，只是断了续不上 | 界面无感 |
| 没配 API key | `plan`/`prompt` 回 `error` | `ready.hasApiKey` 为 false 时就该禁用输入 |

**取消的语义要分清**：计划整体是 `CANCELLED`（用户叫停），不是 `FAILED`（跑砸了）。
界面上这两种应该长得不一样——前者不是错误。

---

## 7. 给界面实现者的几条提醒

1. **`goal` 要显眼**。它是模型用自己的话复述的理解，用户看一眼就能判断方向对不对，
   在这儿毙掉只花一次规划的钱，跑完再发现代价是十倍。
2. **同层任务要能看出是并列的**。后端已经按层并行执行（只读任务同时跑，
   写任务独占），`layers` 表达的「这几步互不依赖」如今就是实际的执行形状。
3. **`plan_task_finished` 的 `error` 要露出来**。`SKIPPED` 的 error 写的是
   「上游 xx 失败」，用户靠它知道该去看谁。
4. **别缓存任务列表当不可变**。`plan_tasks_added` 会往里插。
5. **确认框不要做「本次都同意」**。每份计划都不一样，一次性授权掉后面所有计划
   是危险的——审批（`approval_request`）有 `always`，计划确认刻意没有。
6. **`acceptance` 值得单独显示**。用户在确认计划那一刻，看的不只是「要做什么」，
   更是「做到什么算完」——验收线划歪了，比步骤拆错更难在事后发现。
7. **`{{t1}}` 原样显示**，别自己替换，理由见 3.4。
8. **改道要和长大画得不一样**。`plan_revised` 说明原路走不通，
   这是用户最该注意的一刻；`plan_tasks_added` 只是计划照常展开。
9. **「接着跑」要常驻，不是只在启动时提一句**。`cancel` 之后存档还在，
   用户随时可能回来接着跑；只在首帧提示的话，他中断一次就再也找不到入口了。
10. **确认框的交互态别放在组件里**。矮终端上那个框可能压根不渲染，而后端还等着。
    状态跟着组件走的话，那种终端上按 `a` 会没有任何反应，用户不知道为什么。
    按键处理也是同一条纪律——这两件事从 Ink 版起就吃过亏。
11. **`preview` 有多长就画多长是不行的**，它会把上面的对话顶出屏幕。
    裁了要说「还有 N 行没显示」：悄悄少给的时候，人以为自己看到了全部，然后按了同意。
12. **`suggestions` 为空时别画「以后都同意」那个键**。空数组是后端在说
    「这次记不成规则」，画出来只会让用户按一个没有反应的键。

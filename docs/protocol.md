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

---

## 2. 版本与兼容

`protocol` 字段在 `listening` 和 `ready` 两处都有。

**什么时候升版本**：只有破坏性改动才升——删字段、改字段含义、改一条消息的时序。
**新增消息类型、新增字段不升版本**，界面本来就必须忽略不认识的 `t` 和不认识的字段。

新能力靠 `ready.features` 宣告，不靠版本号：

```json
"features": ["plan", "replan", "resume"]
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
| `reset_done` | — | `/clear` 成功 |

`ready` 的完整字段：

```json
{
  "t": "ready",
  "protocol": 1,
  "version": "0.2.0",
  "workspace": "/Users/x/proj",
  "model": "deepseek-chat",
  "planMode": false,
  "hasApiKey": true,
  "busy": false,
  "tokens": 1234,
  "contextLimit": 65536,
  "features": ["plan", "replan", "resume"],
  "tools": [{"name": "read_file", "description": "…"}],
  "workspaceWarning": "（可选）--unsafe 放行时的警告文案",
  "resumable": {
    "goal": "上次那份没跑完的计划的目标",
    "remaining": 3, "total": 7, "savedAt": 1758067200000
  }
}
```

**`resumable` 只在「这个工作区确实有没跑完的计划」时才出现**，见 3.7。
界面据此决定第一屏要不要摆出「接着跑」那个入口——放在首帧而不是让界面再问一次，
是因为画第一屏的时候就得知道。

> ⚠️ `planMode` 是**只读模式**的历史字段名（对应 CLI 的 `--readonly`），
> 和「先规划后执行」没有任何关系。名字保留是为了不破坏已有界面。

### 3.2 ReAct 过程事件

一轮对话期间按顺序发出：

| `t` | 字段 | 说明 |
|---|---|---|
| `thinking` | `step` | 第几轮开始，正在等模型 |
| `text_delta` | `text` | 模型吐出的**增量**文本，不是全文 |
| `thought` | `text` | 调工具前说的那句话 |
| `tool_started` | `id` `tool` `arguments` | `arguments` 是 JSON 字符串 |
| `tool_finished` | `id` `tool` `ok` `output` | |
| `compacted` | `tokensBefore` `tokensAfter` `summarized` | 上下文被压缩了 |
| `done` | `text` `steps` | 最终答复，本轮结束 |
| `failed` | `reason` | 跑不下去了（调用失败、被取消、超步数） |

**`tool_started` / `tool_finished` 的 `id` 是服务端编的流水号**，界面靠它把「开始」
和「结束」配成同一行做原地更新。工具调用严格串行，同一流水号必定对应同一次调用。

### 3.3 审批

| `t` | 字段 |
|---|---|
| `approval_request` | `id` `action` `detail` `reason` |

界面必须回 `approval_reply`。**不回就卡着**——后端的 agent 线程同步阻塞在这儿，
等待上限 600 秒，超时按拒绝。最后一个连接断开时后端会自行按拒绝放行。

### 3.4 先规划后执行（feature: `plan`）

| `t` | 字段 | 说明 |
|---|---|---|
| `plan_created` | `goal` `tasks[]` `layers[][]` | 规划产出，见下 |
| `plan_confirm_request` | `id` | 等界面确认，必须回 `plan_confirm_reply` |
| `plan_task_started` | `taskId` `taskType` `description` | |
| `plan_task_finished` | `taskId` `status` `durationMs` `result` `error` | `result`/`error` 可能是 `null` |
| `plan_tasks_added` | `parentId` `tasks[]` | 图长大了，见 3.5 |
| `plan_revised` | `failedTaskId` `tasks[]` | 某一步失败后补出了救场任务，见 3.6 |
| `plan_finished` | `status` `succeeded` `failed` `skipped` `replans` `durationMs` | 用户否决时只有 `status` |

`plan_created`：

```json
{
  "t": "plan_created",
  "goal": "模型用自己的话复述的目标，界面要显眼地展示——用户靠它判断有没有理解偏",
  "tasks": [
    {"id": "t1", "type": "FILE_READ", "description": "读 pom.xml",
     "acceptance": "说清 Java 版本是多少",
     "status": "PENDING", "parentId": null, "dependsOn": []},
    {"id": "t3", "type": "FILE_WRITE", "description": "按 {{t1}} 查到的版本改 X",
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

`taskType` 六种：`PLANNING` `FILE_READ` `FILE_WRITE` `COMMAND` `ANALYSIS` `VERIFICATION`
`status` 五种：`PENDING` `RUNNING` `SUCCEEDED` `FAILED` `SKIPPED`
计划 `status` 五种：`CREATED` `RUNNING` `COMPLETED` `FAILED` `CANCELLED`

### 3.5 执行期间图会长大

`PLANNING` 类型的任务跑完会**拆出一批子任务插进图里**，这时发 `plan_tasks_added`：

```json
{"t":"plan_tasks_added","parentId":"t1",
 "tasks":[{"id":"t1.1","type":"FILE_WRITE","description":"改 A",
           "status":"PENDING","parentId":"t1","dependsOn":[]}]}
```

界面要把它们**就地插到父任务下面**，而不是重画整张图。
子任务最多再拆一层（不会有「孙子任务」），单批最多 5 个。

### 3.6 失败之后会重规划（feature: `replan`）

一步失败时，服务端**不一定就此收摊**。它会把「原目标 + 已经做成的产出 + 失败原因 +
被连累跳过的活」交回模型，让它补出接下来该走的几步，然后发 `plan_revised`：

```json
{"t":"plan_revised","failedTaskId":"t3",
 "tasks":[{"id":"r1","type":"FILE_READ","description":"查清 App.java 的真实位置",
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

---

## 4. 界面 → 服务端

| `t` | 字段 | 说明 |
|---|---|---|
| `prompt` | `text` | 普通一轮对话 |
| `plan` | `goal` | 先规划后执行 |
| `plan_confirm_reply` | `id` `approved`(bool) | 回应 `plan_confirm_request` |
| `approval_reply` | `id` `decision`: `yes` \| `no` \| `always` | 回应 `approval_request` |
| `cancel` | — | 中断当前这一轮 |
| `resume` | — | 接着跑上次没跑完的计划，见 3.7。没有存档时回 `error` |
| `reset` | — | 清空对话历史。忙的时候会被拒 |
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

**执行期间的 ReAct 事件归属于「最近一条 `plan_task_started` 的那个任务」**——
任务是串行执行的，不会交错。界面把它们画进那个任务的展开区即可。

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
| 规划失败（模型连 3 次给不出可用计划） | 发 `failed`，带原因 | 照常显示错误 |
| 某步失败、且补得回来 | 发 `plan_revised`，补救任务接着跑 | 把新任务插进去，标明这是改道 |
| 某步失败、但模型判断补不了 | 不发 `plan_revised`，照原样收场 | 照常显示失败 |
| 重规划本身挂了（调用失败、给的图不合法） | 静默忽略，照原样收场 | 界面无感，和没配重规划一样 |
| `resume` 但没有存档 | 回 `error` | `ready.resumable` 不存在时就别放出这个入口 |
| 存档读不出来 / 格式过期 | 当作没有存档 | 同上 |
| 存不下（磁盘满、只读） | 计划照跑，只是断了续不上 | 界面无感 |
| 没配 API key | `plan`/`prompt` 回 `error` | `ready.hasApiKey` 为 false 时就该禁用输入 |

**取消的语义要分清**：计划整体是 `CANCELLED`（用户叫停），不是 `FAILED`（跑砸了）。
界面上这两种应该长得不一样——前者不是错误。

---

## 7. 给界面实现者的几条提醒

1. **`goal` 要显眼**。它是模型用自己的话复述的理解，用户看一眼就能判断方向对不对，
   在这儿毙掉只花一次规划的钱，跑完再发现代价是十倍。
2. **同层任务要能看出是并列的**。虽然后端目前串行执行，但 `layers` 表达的是
   「这几步本来互不依赖」，这是 DAG 相对于普通清单的全部价值。
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

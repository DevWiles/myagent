# AsynCodeBench 接入

把 myagent 接进 [AsynCodeBench](https://github.com/KaituoZhang/AsynCodeBench)（19 个带跨模块依赖的
Commit0/TVM 任务，5 种协作协议，指标 TPR / ADPR / DRS）。框架要 Linux x86_64 + Docker(+buildx)
+ Python 3.12 + uv，Mac 上跑不了；实际跑在一台 2C/3.6G 的 Linux 云主机上。

## 文件

- `myagent_adapter.py` / `__init__.py`：放到框架 `reproductions/async-swe-agents/my_agents/`。
  适配器通过 `workspace.file_upload` 把一个 linux-x64 JRE 和 `myagent.jar` 装进任务容器
  `/tmp/myagent-rt`，每个 assignment 用 `java -jar myagent.jar --once ... --once-mode react|team` 跑，
  跑完把 `writable_paths` 之外的改动**机械回滚**再 `git commit`（框架会整体拒收越界产物）。
- 服务器上的 helper：`~/run-acb.sh <asyncodebench args>`、`~/run-myagent.sh <task> <protocol> <react|team> <run-id>`，
  都要 `sg docker -c` 包起来。

## 跑法

```bash
# 框架侧一次性：bash scripts/setup_evaluation.sh（docker 权限那步会失败，runner 已装好即可）
# .env：LLM_BASE_URL / LLM_API_KEY / LLM_MODEL=openai/<model> / LLM_EXTRA_BODY_JSON='{"enable_thinking":false}'
sg docker -c "bash ~/run-myagent.sh cachetools single react myagent-react-v01"
sg docker -c "bash ~/run-myagent.sh cachetools single team  myagent-team-v01"
sg docker -c "bash ~/run-myagent.sh cachetools async_private react myagent-async-react-v01"
uv run --frozen asyncodebench validate-run outputs/asyncodebench/v0.3/<model>/<task>/<protocol>/<run-id>
```

结果与读数见 Obsidian「应聘记录/myagent 多 Agent 模式缺陷分析与题库调研」第六节。

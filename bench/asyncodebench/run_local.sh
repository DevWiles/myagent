#!/usr/bin/env bash
# 在本机（Apple Silicon）跑 AsynCodeBench 的一组配置，可重复多次。
#
#   bench/asyncodebench/run_local.sh <label> '<adapter-config-json>' [次数] [任务] [协议]
#   bench/asyncodebench/run_local.sh proactive '{"mode":"react","subagents":"proactive"}' 3
#
# 前提：~/workspace/AsynCodeBench 已经 setup_evaluation.sh 过、.env 填了 key；
# 适配器拷在 reproductions/async-swe-agents/my_agents/；target/myagent.jar 已打包。
# 框架固定用 --platform linux/amd64 起任务容器，在 Apple Silicon 上走 Rosetta 模拟，所以 JRE 用 linux-x64 那份。
# 镜像要用 docker-container 构建器（acb）构建：Docker Desktop 的 containerd 镜像存储里，
# 默认构建器构建出的镜像不带平台信息，docker run --platform 找不到它。只用环境变量指定，不动全局默认构建器：
#   docker buildx create --name acb --driver docker-container
set -u
LABEL=$1; CFG=$2; RUNS=${3:-1}; TASK=${4:-cookiecutter}; PROTO=${5:-single}
ACB=${ACB_ROOT:-$HOME/workspace/AsynCodeBench}
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
RUNNER="$ACB/reproductions/async-swe-agents"
LOG="$REPO/bench/results/acb-local"; mkdir -p "$LOG"
cp "$REPO/bench/asyncodebench/myagent_adapter.py" "$RUNNER/my_agents/myagent_adapter.py"
CFG=$(python3 -c 'import json,sys,os; c=json.loads(sys.argv[1]); c.setdefault("jar",sys.argv[2]); c.setdefault("jre",os.path.expanduser("~/jre/jre17-x64.tar.gz")); c.setdefault("timeout",3600); print(json.dumps(c))' "$CFG" "$REPO/target/myagent.jar")
cd "$RUNNER" || exit 1
export PATH="$RUNNER/.venv/bin:$PATH"
export BUILDX_BUILDER=acb
# 框架构建时不给 buildx 指定平台，构建阶段就按本机 arm64 跑，装进镜像的是 aarch64 的 Python，
# 而运行时是 amd64——容器一起来就 exec 失败。整个构建都钉在 amd64 上
export DOCKER_DEFAULT_PLATFORM=linux/amd64
export ENV_FILE="$RUNNER/.env"; source scripts/env.sh
for i in $(seq 1 "$RUNS"); do
  # 时间戳精确到秒：矩阵里同一分钟可能连着起好几次，按分钟会撞名
  RID="myagent-$LABEL-$PROTO-L$(date +%m%d%H%M%S)-$i"
  echo "$(date '+%F %T') START $TASK/$PROTO $LABEL #$i ($RID) cfg=$CFG" | tee -a "$LOG/chain.log"
  t0=$(date +%s)
  asyncodebench run --task "asyncodebench:$TASK" --protocol "$PROTO" --model "$LLM_MODEL" \
    --agent-import-path my_agents.myagent_adapter:MyAgentAdapter \
    --agent-config-json "$CFG" --run-id "$RID" > "$LOG/$RID.log" 2>&1
  rc=$?
  dir=$(find outputs/asyncodebench -type d -path "*/$TASK/$PROTO/$RID" | head -1)
  v=NO_OUTPUT_DIR
  if [ -n "$dir" ]; then asyncodebench validate-run "$dir" > "$LOG/$RID.validate" 2>&1; v="validate=$?"; fi
  for c in $(docker ps --format '{{.Names}}' | grep '^agent-server-'); do docker rm -f "$c" >/dev/null 2>&1; done
  echo "$(date '+%F %T') END   $TASK/$PROTO $LABEL #$i rc=$rc $(( $(date +%s) - t0 ))s $v dir=$dir" | tee -a "$LOG/chain.log"
  # 矩阵评测（run_matrix.sh）靠这份清单知道哪次运行属于哪个组、哪道题
  [ -n "${MANIFEST:-}" ] && echo "$LABEL $TASK $PROTO $RID $rc" >> "$MANIFEST"
done

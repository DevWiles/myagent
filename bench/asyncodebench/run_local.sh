#!/usr/bin/env bash
# 在本机（Apple Silicon）跑 AsynCodeBench 的一组配置，可重复多次。
#
#   bench/asyncodebench/run_local.sh <label> '<adapter-config-json>' [次数] [任务] [协议]
#   bench/asyncodebench/run_local.sh proactive '{"mode":"react","subagents":"proactive"}' 3
#
# 前提：~/workspace/AsynCodeBench 已经 setup_evaluation.sh 过、.env 填了 key；
# 适配器拷在 reproductions/async-swe-agents/my_agents/；target/myagent.jar 已打包。
# 任务容器在本机是 arm64，所以默认用 aarch64 的 JRE（服务器上是 linux-x64 那份）。
set -u
LABEL=$1; CFG=$2; RUNS=${3:-1}; TASK=${4:-cookiecutter}; PROTO=${5:-single}
ACB=${ACB_ROOT:-$HOME/workspace/AsynCodeBench}
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
RUNNER="$ACB/reproductions/async-swe-agents"
LOG="$REPO/bench/results/acb-local"; mkdir -p "$LOG"
cp "$REPO/bench/asyncodebench/myagent_adapter.py" "$RUNNER/my_agents/myagent_adapter.py"
CFG=$(python3 -c 'import json,sys,os; c=json.loads(sys.argv[1]); c.setdefault("jar",sys.argv[2]); c.setdefault("jre",os.path.expanduser("~/jre/jre17-aarch64.tar.gz")); c.setdefault("timeout",3600); print(json.dumps(c))' "$CFG" "$REPO/target/myagent.jar")
cd "$RUNNER" || exit 1
export PATH="$RUNNER/.venv/bin:$PATH"
export ENV_FILE="$RUNNER/.env"; source scripts/env.sh
STAMP=$(date +%m%d%H%M)
for i in $(seq 1 "$RUNS"); do
  RID="myagent-$LABEL-$PROTO-L$STAMP-$i"
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
done

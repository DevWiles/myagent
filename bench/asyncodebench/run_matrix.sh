#!/usr/bin/env bash
# 在本机按「组 × 题目 × 次数」跑 AsynCodeBench，组与组交替进行，结果记进一份清单，再用 summarize.py 汇总。
#
#   bench/asyncodebench/run_matrix.sh <组,组,...> <题目,题目,...> <每组每题次数> [协议]
#   bench/asyncodebench/run_matrix.sh A,D cookiecutter,flask,tinydb 3
#
# 组（对照方案见 docs，这里只是配置）：
#   A   原始单 agent                         subagents=off
#   B   单 agent + 探索子 agent（动态决策）    subagents=auto，只允许 explorer
#   C   单 agent + 收尾独立审查               mode=react+review（复用已有的 ReviewLoop）
#   D   动态委派 + 结果整合                   subagents=auto，三种类型都允许
#   Dm  D，但主 agent 和子 agent 共用 100 步  算力对齐的那一组
#   E   固定倾向委派                          subagents=proactive
#
# 为什么交替：同一组连着跑，碰上的是同一段网络和限流；交替让各组摊到的时段尽量一样。
# 顺序是 次数 → 题目 → 组，所以中途停下来，每组已经跑过的次数也差不多。
set -u
GROUPS_ARG=$1; TASKS_ARG=$2; RUNS=$3; PROTO=${4:-single}
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
LOG="$REPO/bench/results/acb-local"; mkdir -p "$LOG"
export MANIFEST="$LOG/matrix-$(date +%m%d%H%M%S).txt"

# jar 先复制一份固定下来：矩阵要跑几个小时，中途重新打包不该改变后半段测的东西
JAR="$LOG/jars/myagent-$(git -C "$REPO" rev-parse --short HEAD)$(git -C "$REPO" diff --quiet || echo -dirty).jar"
mkdir -p "$LOG/jars"; cp "$REPO/target/myagent.jar" "$JAR"

config() {
  case "$1" in
    A)  echo '{"mode":"react","subagents":"off"}' ;;
    B)  echo '{"mode":"react","subagents":"auto","subagent_roles":"explorer"}' ;;
    C)  echo '{"mode":"react+review","subagents":"off"}' ;;
    D)  echo '{"mode":"react","subagents":"auto"}' ;;
    Dm) echo '{"mode":"react","subagents":"auto","total_steps":100}' ;;
    E)  echo '{"mode":"react","subagents":"proactive"}' ;;
    *)  echo "未知的组: $1" >&2; return 1 ;;
  esac
}

IFS=, read -r -a GROUPS_LIST <<< "$GROUPS_ARG"
IFS=, read -r -a TASKS_LIST <<< "$TASKS_ARG"
for g in "${GROUPS_LIST[@]}"; do config "$g" >/dev/null || exit 2; done
echo "# groups=$GROUPS_ARG tasks=$TASKS_ARG runs=$RUNS proto=$PROTO jar=$JAR" > "$MANIFEST"
echo "清单: $MANIFEST"

for i in $(seq 1 "$RUNS"); do
  for t in "${TASKS_LIST[@]}"; do
    for g in "${GROUPS_LIST[@]}"; do
      cfg=$(python3 -c 'import json,sys; c=json.loads(sys.argv[1]); c["jar"]=sys.argv[2]; print(json.dumps(c))' \
            "$(config "$g")" "$JAR")
      "$REPO/bench/asyncodebench/run_local.sh" "$g" "$cfg" 1 "$t" "$PROTO"
    done
  done
done
echo "MATRIX_DONE $MANIFEST"

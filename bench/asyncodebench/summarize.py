#!/usr/bin/env python3
"""汇总 run_matrix.sh 的一份清单：每个「组 × 题目」一行，外加逐次明细。

    python3 bench/asyncodebench/summarize.py bench/results/acb-local/matrix-XXXX.txt [--json out.json]

指标（都取自 AsynCodeBench 自己的输出和 myagent 的运行摘要，不做二次判断）：
  tests        集成后测试通过数（每次的值、均值、总体标准差）
  ADPR         依赖契约解决数（final_integrated_ADPR）
  in/out       输入 / 输出 token（cost.json）
  time         agent 用时（秒）
  failed       没有出分的次数（崩溃、超时、打分挂住）——单独计数，不当成 0 分混进均值
  delegated    auto 模式下决策器决定委派的次数
  spawned      平均派出的子 agent 数
  conflicts    写入锁挡下的次数（主 agent 或子 agent 去写别人负责的文件）
  reminders    收尾验证提醒次数；unverified 是收尾时仍没验证的子 agent 改动文件数
  budgetHit    撞上步数上限或共用预算被强制收尾的次数

结论只能按「均值差是否超出波动」来读：每组每题 3 次时，标准差在 25 分上下，
小于这个量级的差别说明不了任何事。
"""
import argparse
import glob
import json
import os
import statistics as st
import sys

ACB = os.environ.get("ACB_ROOT", os.path.expanduser("~/workspace/AsynCodeBench"))
OUTPUTS = os.path.join(ACB, "reproductions/async-swe-agents/outputs/asyncodebench")


def load_run(task, proto, rid):
    hits = glob.glob(os.path.join(OUTPUTS, "*", "*", task, proto, rid))
    if not hits:
        return {"error": "没有输出目录"}
    d = hits[0]
    row = {"dir": d}
    try:
        p = json.load(open(os.path.join(d, "process_metrics_summary.json")))
        c = json.load(open(os.path.join(d, "cost.json")))
    except Exception as e:  # noqa: BLE001 —— 打分没跑完就是没有这两个文件
        row["error"] = f"没有出分: {e.__class__.__name__}"
        return row
    t = p["process_metrics"]["tests_passed_before_and_after_integration"]
    a = p["strict_dependency_metrics"]["final_integrated_ADPR"]
    row.update(tests=t["after_integration_passed"], total=t["after_integration_total"],
               adpr=a["resolved"], adpr_total=a["total"],
               tin=c["total"]["prompt_tokens"], tout=c["total"]["completion_tokens"],
               time=round(c["total"]["duration"]))
    summary = {}
    ev = os.path.join(d, "agent_events", f"{proto}_agent_events.jsonl")
    if os.path.exists(ev):
        for line in open(ev):
            r = json.loads(line)
            if r.get("event_type") == "myagent_once":
                summary = r["content"].get("summary") or {}
    rep = summary.get("subagentReport") or {}
    dec = summary.get("delegation") or {}
    row.update(status=summary.get("status"), steps=summary.get("steps"),
               budget_hit="步数" in (summary.get("failed") or "") or "预算" in (summary.get("failed") or ""),
               delegated=dec.get("delegate"), decision_reason=dec.get("reason", ""),
               spawned=rep.get("spawned", summary.get("subagents", 0)) or 0,
               conflicts=rep.get("writeConflicts", 0), reminders=rep.get("verifyReminders", 0),
               unverified=rep.get("unverifiedAtEnd", 0), sub_steps=summary.get("subagentSteps", 0),
               total_steps_used=summary.get("totalStepsUsed"))
    return row


def mean(xs):
    return round(st.mean(xs), 1) if xs else None


def sd(xs):
    return round(st.pstdev(xs), 1) if len(xs) > 1 else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    runs = []
    for line in open(args.manifest):
        if line.startswith("#") or not line.strip():
            continue
        group, task, proto, rid, rc = line.split()[:5]
        r = load_run(task, proto, rid)
        r.update(group=group, task=task, proto=proto, rid=rid, rc=int(rc))
        runs.append(r)

    cells = {}
    for r in runs:
        cells.setdefault((r["task"], r["group"]), []).append(r)

    print("| 题目 | 组 | n | 失败 | tests（每次） | 均值 | 标准差 | ADPR 均值 | 输入 token 均值 | 用时均值 | 委派 | 子agent均值 | 冲突 | 提醒 | 未验证 | 撞上限 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    table = []
    for (task, group), rows in sorted(cells.items()):
        ok = [r for r in rows if "error" not in r]
        tests = [r["tests"] for r in ok]
        cell = dict(task=task, group=group, n=len(rows), failed=len(rows) - len(ok), tests=tests,
                    mean=mean(tests), sd=sd(tests), adpr=mean([r["adpr"] for r in ok]),
                    tin=mean([r["tin"] / 1e6 for r in ok]), time=mean([r["time"] for r in ok]),
                    delegated=sum(1 for r in ok if r.get("delegated")),
                    spawned=mean([r["spawned"] for r in ok]),
                    conflicts=sum(r["conflicts"] for r in ok), reminders=sum(r["reminders"] for r in ok),
                    unverified=sum(r["unverified"] for r in ok), budget_hit=sum(1 for r in ok if r["budget_hit"]),
                    total=ok[0]["total"] if ok else None)
        table.append(cell)
        print(f"| {task} | {group} | {cell['n']} | {cell['failed']} | {tests} / {cell['total']} | {cell['mean']} | "
              f"{cell['sd']} | {cell['adpr']} | {cell['tin']}M | {cell['time']}s | {cell['delegated']} | "
              f"{cell['spawned']} | {cell['conflicts']} | {cell['reminders']} | {cell['unverified']} | {cell['budget_hit']} |")

    print("\n逐次明细：")
    for r in runs:
        if "error" in r:
            print(f"  {r['group']:3s} {r['task']:13s} {r['rid']}  ✗ {r['error']} (rc={r['rc']})")
        else:
            why = (r["decision_reason"] or "")[:60]
            print(f"  {r['group']:3s} {r['task']:13s} {r['rid']}  tests {r['tests']}/{r['total']} ADPR {r['adpr']} "
                  f"in {r['tin'] / 1e6:.1f}M steps {r['steps']} sub {r['spawned']}/{r['sub_steps']}步 "
                  f"冲突 {r['conflicts']} 提醒 {r['reminders']} {r['status']}" + (f"  决策: {why}" if why else ""))

    if args.json:
        json.dump({"cells": table, "runs": runs}, open(args.json, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    sys.exit(main())

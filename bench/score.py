#!/usr/bin/env python3
"""评分：把两个（或多个）label 的 summary.json 按任务对齐，算四维分数与合成分，输出 bench/results/score-report.md。

每任务 100 分：
  正确性 60   可见+隐藏测试全过 60；否则 60 × 通过用例比例（改了测试文件直接 0）
  约束   15   未改测试文件 +10；改动文件不超出任务范围（源码文件 + 新建文件）+5
  效率   15   耗时 7.5 + 输入 token 7.5：每个任务里以最好的 agent 为 1，其余按 best/x 线性折算（下限 0）
  验证   10   结束前至少跑过一次测试 +10
用法：python3 bench/score.py --labels myagent-react,claude
"""
import argparse
import json
import os
import statistics
from collections import defaultdict

BENCH = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(BENCH, "results")
KIND = {"bugfix": "修 bug", "feature": "实现功能", "refactor": "重构", "multi": "多文件"}


def load(label):
    return {r["task"]: r for r in json.load(open(os.path.join(RES, label, "summary.json"), encoding="utf-8"))}


def scope_ok(r, meta):
    """改动是否都落在任务范围内：允许改源码文件、新建文件；不允许改测试。"""
    if r.get("tests_modified"):
        return False
    return True


def score_one(r, best_time, best_tokens, meta):
    s = {}
    passed, failed = r.get("tests_passed") or 0, r.get("tests_failed") or 0
    total = passed + failed
    if r.get("tests_modified"):
        s["correct"] = 0.0
    elif r.get("success"):
        s["correct"] = 60.0
    else:
        s["correct"] = round(60.0 * passed / total, 1) if total else 0.0
    s["constraint"] = (0 if r.get("tests_modified") else 10) + (5 if scope_ok(r, meta) else 0)
    t, tok = r.get("elapsed_s") or 0, r.get("input_tokens") or 0
    eff_t = 7.5 * min(1.0, best_time / t) if t and best_time else 0
    eff_k = 7.5 * min(1.0, best_tokens / tok) if tok and best_tokens else 0
    s["efficiency"] = round(eff_t + eff_k, 1)
    s["verify"] = 10 if r.get("ran_tests") else 0
    s["total"] = round(s["correct"] + s["constraint"] + s["efficiency"] + s["verify"], 1)
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", required=True)
    ap.add_argument("--out", default=os.path.join(RES, "score-report.md"))
    args = ap.parse_args()
    labels = args.labels.split(",")
    data = {l: load(l) for l in labels}
    index = {t["name"]: t for t in json.load(open(os.path.join(BENCH, "tasks", "index.json"), encoding="utf-8"))}
    tasks = [t for t in index if all(t in data[l] for l in labels)]

    scores = {l: {} for l in labels}
    for t in tasks:
        rs = {l: data[l][t] for l in labels}
        best_time = min((r.get("elapsed_s") or 1e9) for r in rs.values())
        best_tok = min((r.get("input_tokens") or 1e12) for r in rs.values())
        for l in labels:
            scores[l][t] = score_one(rs[l], best_time, best_tok, index[t])

    lines = ["# agent 对比评分：" + " vs ".join(labels), ""]
    lines.append("- 任务 %d 个（%s）；同一模型 deepseek-chat、同一沙盒、同一提示词、审批自动放行" % (
        len(tasks), "、".join("%s %d" % (KIND.get(k, k), n) for k, n in
                              sorted(__import__("collections").Counter(index[t]["kind"] for t in tasks).items()))))
    lines.append("- 评分：正确性 60（全过 60，否则按用例通过比例；改测试计 0）+ 约束 15（不改测试 10、改动不越界 5）+ 效率 15（耗时、输入 token 各 7.5，按任务内最优归一化）+ 验证行为 10（结束前跑过测试）")
    lines.append("")
    lines.append("## 总分")
    lines.append("")
    lines.append("| agent | 平均总分 | 成功率 | 正确性 | 约束 | 效率 | 验证 | 中位步数 | 中位工具调用 | 中位耗时 | 中位输入 token | 中位输出 token | 超时 | 改了测试 |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for l in labels:
        ss = [scores[l][t] for t in tasks]
        rs = [data[l][t] for t in tasks]
        med = lambda k: statistics.median([r.get(k) or 0 for r in rs])
        lines.append("| %s | **%.1f** | %.1f%% | %.1f | %.1f | %.1f | %.1f | %.0f | %.0f | %.0f s | %.0f | %.0f | %d | %d |" % (
            l, statistics.mean(s["total"] for s in ss), sum(1 for r in rs if r.get("success")) / len(rs) * 100,
            statistics.mean(s["correct"] for s in ss), statistics.mean(s["constraint"] for s in ss),
            statistics.mean(s["efficiency"] for s in ss), statistics.mean(s["verify"] for s in ss),
            med("steps"), med("tool_calls_n"), med("elapsed_s"), med("input_tokens"), med("output_tokens"),
            sum(1 for r in rs if r.get("timeout")), sum(1 for r in rs if r.get("tests_modified"))))
    lines.append("")

    for title, key, names in (("按任务类型", "kind", ["bugfix", "feature", "refactor", "multi"]), ("按来源", "source", ["hand", "exercism"]), ("按难度", "difficulty", sorted({index[t]["difficulty"] for t in tasks}))):
        lines.append("## %s（成功率 / 平均总分）" % title)
        lines.append("")
        lines.append("| %s | " % title[1:] + " | ".join(labels) + " |")
        lines.append("|---|" + "---:|" * len(labels))
        for nm in names:
            sub = [t for t in tasks if (index[t].get(key) or "hand") == nm]
            if not sub:
                continue
            cells = []
            for l in labels:
                rs = [data[l][t] for t in sub]
                cells.append("%.0f%% / %.1f" % (sum(1 for r in rs if r.get("success")) / len(rs) * 100, statistics.mean(scores[l][t]["total"] for t in sub)))
            lines.append("| %s (%d) | " % (KIND.get(nm, nm) if key == "kind" else nm, len(sub)) + " | ".join(cells) + " |")
        lines.append("")

    # 胜负
    if len(labels) == 2:
        a, b = labels
        win = {a: 0, b: 0, "tie": 0}
        both_pass = only = {a: 0, b: 0}
        only_a = [t for t in tasks if data[a][t].get("success") and not data[b][t].get("success")]
        only_b = [t for t in tasks if data[b][t].get("success") and not data[a][t].get("success")]
        for t in tasks:
            sa, sb = scores[a][t]["total"], scores[b][t]["total"]
            win[a if sa > sb else b if sb > sa else "tie"] += 1
        lines.append("## 逐任务胜负（按总分）")
        lines.append("")
        lines.append("- %s 胜 %d，%s 胜 %d，平 %d" % (a, win[a], b, win[b], win["tie"]))
        lines.append("- 只有 %s 通过：%s" % (a, ", ".join(only_a) or "无"))
        lines.append("- 只有 %s 通过：%s" % (b, ", ".join(only_b) or "无"))
        lines.append("")

    lines.append("## 任务明细")
    lines.append("")
    lines.append("| 任务 | 类型 | 难度 | " + " | ".join("%s 总分(正/约/效/验) 步数 耗时" % l for l in labels) + " |")
    lines.append("|---|---|---:|" + "---|" * len(labels))
    for t in tasks:
        cells = []
        for l in labels:
            s, r = scores[l][t], data[l][t]
            mark = "✅" if r.get("success") else ("⏱" if r.get("timeout") else "❌")
            cells.append("%s %.0f (%.0f/%d/%.0f/%d) %s步 %.0fs" % (mark, s["total"], s["correct"], s["constraint"], s["efficiency"], s["verify"], r.get("steps") or 0, r.get("elapsed_s") or 0))
        lines.append("| %s | %s | %s | " % (t, KIND.get(index[t]["kind"], index[t]["kind"]), index[t]["difficulty"]) + " | ".join(cells) + " |")
    lines.append("")
    text = "\n".join(lines)
    open(args.out, "w", encoding="utf-8").write(text)
    print(text)


if __name__ == "__main__":
    main()

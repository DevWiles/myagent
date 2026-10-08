#!/usr/bin/env python3
"""汇总 bench/results/<label>/summary.json，输出 markdown 对比表到 bench/results/report.md。"""
import glob
import json
import os
import statistics

BENCH = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(BENCH, "results")
ORDER = ["react", "prompt", "plan", "team"]
TITLE = {"react": "单 agent（关自动规划）", "prompt": "产品默认（可自动改走规划）", "plan": "显式规划→执行", "team": "流水线（规划→并行→验收）"}
KIND = {"bugfix": "修 bug", "feature": "实现功能", "refactor": "重构", "multi": "多文件"}


def load():
    out = {}
    for p in glob.glob(os.path.join(RES, "*", "summary.json")):
        label = os.path.basename(os.path.dirname(p))
        if label.startswith("pilot"):
            continue
        out[label] = json.load(open(p, encoding="utf-8"))
    return out


def med(vals):
    vals = [v for v in vals if isinstance(v, (int, float))]
    return statistics.median(vals) if vals else 0


def rate(rs):
    return (sum(1 for r in rs if r.get("success")) / len(rs) * 100) if rs else 0


def main():
    data = load()
    labels = [l for l in ORDER if l in data] + [l for l in data if l not in ORDER]
    lines = ["# myagent 任务成功率评测", ""]
    n_tasks = len(next(iter(data.values()))) if data else 0
    lines.append("- 任务：%d 个 Python 小任务（修 bug / 实现功能 / 重构 / 多文件），每个带可见测试 + 隐藏测试，**全部通过才算成功**" % n_tasks)
    lines.append("- 沙盒：每个任务独立 Docker 容器（alpine + JRE17 + pytest），jar 只读挂载，审批由驱动脚本自动同意，单任务上限 15 分钟")
    lines.append("- 模型：deepseek-chat；同一任务同一提示词，只改运行模式")
    lines.append("")
    lines.append("## 总表")
    lines.append("")
    lines.append("| 模式 | 成功率 | 通过/总数 | 超时 | 中位步数 | 中位工具调用 | 中位审批 | 中位耗时 | 平均耗时 |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for l in labels:
        rs = data[l]
        lines.append("| %s | **%.1f%%** | %d/%d | %d | %.0f | %.0f | %.0f | %.0f s | %.0f s |" % (
            TITLE.get(l, l), rate(rs), sum(1 for r in rs if r.get("success")), len(rs),
            sum(1 for r in rs if r.get("timeout")), med([r.get("steps") for r in rs]),
            med([sum((r.get("tool_calls") or {}).values()) for r in rs]), med([r.get("approvals") for r in rs]),
            med([r.get("elapsed_s") for r in rs]), statistics.mean([r.get("elapsed_s") or 0 for r in rs])))
    lines.append("")

    for title, key, names in (("按任务类型", "kind", ["bugfix", "feature", "refactor", "multi"]), ("按难度", "difficulty", [1, 2, 3])):
        lines.append("## %s" % title)
        lines.append("")
        lines.append("| %s | " % title.replace("按", "") + " | ".join(TITLE.get(l, l) for l in labels) + " |")
        lines.append("|---|" + "---:|" * len(labels))
        for nm in names:
            row = []
            for l in labels:
                rs = [r for r in data[l] if r.get(key) == nm]
                row.append("%.0f%% (%d/%d)" % (rate(rs), sum(1 for r in rs if r.get("success")), len(rs)) if rs else "-")
            lines.append("| %s | " % (KIND.get(nm, nm) if key == "kind" else "难度 %s" % nm) + " | ".join(row) + " |")
        lines.append("")

    lines.append("## 任务 × 模式")
    lines.append("")
    lines.append("| 任务 | 类型 | 难度 | " + " | ".join(labels) + " |")
    lines.append("|---|---|---:|" + "---|" * len(labels))
    tasks = [(r["task"], r.get("kind"), r.get("difficulty")) for r in data[labels[0]]]
    for t, k, d in tasks:
        cells = []
        for l in labels:
            r = next((x for x in data[l] if x["task"] == t), None)
            if not r:
                cells.append("-")
            else:
                mark = "✅" if r.get("success") else ("⏱" if r.get("timeout") else "❌")
                cells.append("%s %d步/%.0fs" % (mark, r.get("steps") or 0, r.get("elapsed_s") or 0))
        lines.append("| %s | %s | %s | " % (t, KIND.get(k, k), d) + " | ".join(cells) + " |")
    lines.append("")

    lines.append("## 失败原因摘录")
    lines.append("")
    for l in labels:
        for r in data[l]:
            if not r.get("success"):
                why = "超时" if r.get("timeout") else (r.get("error") or "")
                tail = (r.get("pytest_tail") or "").strip().splitlines()
                tail = [x for x in tail if x.startswith("FAILED") or x.startswith("ERROR") or "failed" in x][-2:]
                term = [e for e in (r.get("terminal_events") or []) if e.get("t") in ("failed", "plan_finished", "team_finished")]
                lines.append("- **%s / %s**：%s %s %s" % (l, r["task"], why, " ".join(tail)[:200], json.dumps(term[-1], ensure_ascii=False) if term else ""))
    lines.append("")
    text = "\n".join(lines)
    open(os.path.join(RES, "report.md"), "w", encoding="utf-8").write(text)
    print(text)


if __name__ == "__main__":
    main()

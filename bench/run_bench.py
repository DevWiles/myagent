#!/usr/bin/env python3
"""agent 任务成功率评测驱动：每个任务在独立 Docker 容器里跑一个 agent，自动审批，跑隐藏测试判定，并记录评分所需的过程数据。

用法：
  python3 bench/run_bench.py --agent myagent --mode react [--tasks a,b] [--label L] [--timeout 3600]
  python3 bench/run_bench.py --agent claude                 # Claude Code 无头模式，接同一个 DeepSeek 模型
agent：myagent（WebSocket 协议驱动）/ claude（`claude -p` + stream-json）
mode（仅 myagent）：react / react+review（单写者 + 收尾独立审查）/ prompt / plan / team
结果：bench/results/<label>/<task>.json + summary.json；沙盒镜像 myagent-sandbox:v2（非 root 用户 bench）。
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import uuid

import websocket  # websocket-client

BENCH = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(BENCH)
JAR = os.path.join(PROJECT, "target", "myagent.jar")
TASKS = os.path.join(BENCH, "tasks")
IMAGE = "myagent-sandbox:v2"
SUBAGENTS = "explicit"   # --subagents：off / explicit / proactive，做「有无子 agent」的对照
IGNORE = ("__pycache__", ".pytest_cache", ".claude", "hidden_tests")


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def deepseek_key():
    return json.load(open(os.path.expanduser("~/.myagent/config.json")))["apiKey"]


def tree_hash(root):
    out = {}
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if d not in IGNORE]
        for fn in fns:
            p = os.path.join(dp, fn)
            rel = os.path.relpath(p, root)
            if any(part in IGNORE for part in rel.split(os.sep)):
                continue
            out[rel] = hashlib.md5(open(p, "rb").read()).hexdigest()
    return out


def prepare_home(run_dir):
    home = os.path.join(run_dir, "home")
    os.makedirs(os.path.join(home, ".myagent"), exist_ok=True)
    os.makedirs(os.path.join(home, ".claude"), exist_ok=True)
    user_cfg = json.load(open(os.path.expanduser("~/.myagent/config.json")))
    json.dump({"apiKey": user_cfg["apiKey"], "baseUrl": user_cfg.get("baseUrl", "https://api.deepseek.com"),
               "model": user_cfg.get("model", "deepseek-chat"), **({"thinking": user_cfg["thinking"]} if "thinking" in user_cfg else {})}, open(os.path.join(home, ".myagent", "config.json"), "w"))
    json.dump({"version": 1, "allow": [], "deny": []}, open(os.path.join(home, ".myagent", "policy.json"), "w"))
    # Claude Code：跳过首启引导
    json.dump({"hasCompletedOnboarding": True, "theme": "dark"}, open(os.path.join(home, ".claude.json"), "w"))
    return home


def docker_base(name, work, home, extra_env=()):
    cmd = ["docker", "run", "-d", "--rm", "--name", name,
           "-v", f"{JAR}:/opt/myagent.jar:ro",
           "-v", f"{work}:/work",
           "-v", f"{home}:/home/bench",
           "-e", "HOME=/home/bench", "-w", "/work"]
    for e in extra_env:
        cmd += ["-e", e]
    return cmd


def start_myagent(name, work, home, auto_plan_off, final_review=False, subagents="explicit"):
    cmd = docker_base(name, work, home, [f"MYAGENT_AUTO_PLAN={'0' if auto_plan_off else '1'}",
                                         f"MYAGENT_FINAL_REVIEW={'1' if final_review else '0'}",
                                         f"MYAGENT_SUBAGENTS={subagents}"])
    cmd += ["-p", "127.0.0.1::7777", IMAGE, "java", "-jar", "/opt/myagent.jar", "--serve", "--lan", "--port", "7777", "-d", "/work"]
    r = sh(cmd)
    if r.returncode != 0:
        raise RuntimeError("docker run failed: " + r.stderr)
    for _ in range(60):
        for line in sh(["docker", "logs", name]).stdout.splitlines():
            if line.startswith("{") and '"listening"' in line:
                port = sh(["docker", "port", name, "7777"]).stdout.strip().split(":")[-1]
                return json.loads(line)["token"], int(port)
        time.sleep(0.5)
    raise RuntimeError("backend did not start: " + sh(["docker", "logs", name]).stderr[-800:])


def is_test_cmd(cmd):
    return any(k in (cmd or "") for k in ("pytest", "unittest", "python -m test", "python3 -m test"))


# ---------------------------------------------------------------- myagent
def run_myagent(task, prompt, mode, work, home, name, timeout, rec, events):
    token, port = start_myagent(name, work, home, auto_plan_off=(mode in ("react", "react+review")),
                                final_review=(mode == "react+review"), subagents=SUBAGENTS)
    ws = websocket.create_connection(f"ws://127.0.0.1:{port}/?token={token}", timeout=60, suppress_origin=True)
    ready = json.loads(ws.recv())
    rec["model"] = ready.get("model")
    if not ready.get("hasApiKey"):
        raise RuntimeError("no api key in sandbox")
    ws.send(json.dumps({"t": "prompt", "text": prompt} if mode in ("prompt", "react", "react+review") else
                       {"t": "plan", "goal": prompt} if mode == "plan" else {"t": "team", "goal": prompt}))
    t0 = time.time()
    deadline = t0 + timeout
    approvals = tools_n = writes = steps = 0
    tools = {}
    ran_tests = False
    final = None
    text = []
    cancelled_at = None
    while True:
        now = time.time()
        if cancelled_at is None and now > deadline:
            ws.send(json.dumps({"t": "cancel"}))
            rec["timeout"] = True
            cancelled_at = now
        if cancelled_at is not None and now > cancelled_at + 60:
            break
        ws.settimeout(10)
        try:
            raw = ws.recv()
        except websocket.WebSocketTimeoutException:
            continue
        if not raw:
            break
        ev = json.loads(raw)
        t = ev.get("t")
        ev_rec = {k: (v if k != "output" else str(v)[:300]) for k, v in ev.items() if k != "preview"}
        ev_rec["ts"] = round(time.time() - t0, 1)
        events.append(ev_rec)
        if t == "approval_request":
            approvals += 1
            ws.send(json.dumps({"t": "approval_reply", "id": ev["id"], "decision": "yes"}))
        elif t == "plan_confirm_request":
            ws.send(json.dumps({"t": "plan_confirm_reply", "id": ev["id"], "approved": True}))
        elif t == "tool_started":
            tools_n += 1
            tools[ev.get("tool")] = tools.get(ev.get("tool"), 0) + 1
            if ev.get("tool") == "write_file":
                writes += 1
            if ev.get("tool") == "bash":
                try:
                    if is_test_cmd(json.loads(ev.get("arguments") or "{}").get("command")):
                        ran_tests = True
                except Exception:
                    pass
        elif t == "thinking":
            steps += 1
        elif t == "text_delta":
            text.append(ev.get("text", ""))
        elif t == "plan_created":
            rec["plan_tasks"] = len(ev.get("tasks") or [])
        elif t in ("done", "failed", "plan_finished", "team_finished", "mode_switched"):
            rec.setdefault("terminal_events", []).append({k: v for k, v in ev.items() if k in ("t", "status", "reason", "steps", "succeeded", "failed", "skipped", "replans", "rounds", "mode")})
        elif t == "busy" and ev.get("busy") is False:
            final = ev
            break
        elif t == "error":
            rec.setdefault("errors", []).append(ev.get("message"))
    rec.update(elapsed_s=round(time.time() - t0, 1), approvals=approvals, tool_calls=tools, tool_calls_n=tools_n,
               writes=writes, steps=steps, ran_tests=ran_tests, final_text="".join(text)[-600:])
    if final:
        rec.update(context_tokens=final.get("tokens"), input_tokens=final.get("sentTokens"),
                   output_tokens=final.get("completionTokens"), duration_ms=final.get("durationMs"))
    try:
        ws.close()
    except Exception:
        pass


# ---------------------------------------------------------------- Claude Code
def run_claude(task, prompt, work, home, name, timeout, rec, events):
    key = deepseek_key()
    env = ["ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic", f"ANTHROPIC_AUTH_TOKEN={key}",
           "ANTHROPIC_MODEL=deepseek-chat", "ANTHROPIC_SMALL_FAST_MODEL=deepseek-chat",
           "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1", "DISABLE_AUTOUPDATER=1", "DISABLE_TELEMETRY=1"]
    cmd = docker_base(name, work, home, env) + [IMAGE, "sleep", "infinity"]
    r = sh(cmd)
    if r.returncode != 0:
        raise RuntimeError("docker run failed: " + r.stderr)
    open(os.path.join(work, ".bench_prompt.txt"), "w", encoding="utf-8").write(prompt)
    t0 = time.time()
    proc = subprocess.Popen(["docker", "exec", "-w", "/work", name, "sh", "-c",
                             "claude -p \"$(cat .bench_prompt.txt)\" --dangerously-skip-permissions --disallowedTools WebFetch,WebSearch --output-format stream-json --verbose --max-turns 80"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    steps = tools_n = writes = 0
    tools = {}
    ran_tests = False
    final = None
    text = []
    import select
    while True:
        if time.time() - t0 > timeout:
            rec["timeout"] = True
            proc.kill()
            break
        rl, _, _ = select.select([proc.stdout], [], [], 5)
        if not rl:
            if proc.poll() is not None:
                break
            continue
        line = proc.stdout.readline()
        if not line:
            if proc.poll() is not None:
                break
            continue
        try:
            ev = json.loads(line)
        except Exception:
            continue
        ev_type = ev.get("type")
        if ev_type == "assistant":
            steps += 1
            for blk in (ev.get("message") or {}).get("content") or []:
                if blk.get("type") == "tool_use":
                    tools_n += 1
                    nm = blk.get("name")
                    tools[nm] = tools.get(nm, 0) + 1
                    inp = blk.get("input") or {}
                    if nm in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
                        writes += 1
                    if nm == "Bash" and is_test_cmd(inp.get("command")):
                        ran_tests = True
                    events.append({"t": "tool_use", "tool": nm, "arguments": json.dumps(inp, ensure_ascii=False)[:300], "ts": round(time.time() - t0, 1)})
                elif blk.get("type") == "text":
                    text.append(blk.get("text", ""))
        elif ev_type == "result":
            final = ev
            events.append({"t": "result", "subtype": ev.get("subtype"), "num_turns": ev.get("num_turns"), "ts": round(time.time() - t0, 1)})
    try:
        proc.wait(timeout=10)
    except Exception:
        proc.kill()
    err = proc.stderr.read() if proc.stderr else ""
    rec.update(elapsed_s=round(time.time() - t0, 1), approvals=0, tool_calls=tools, tool_calls_n=tools_n, writes=writes,
               steps=steps, ran_tests=ran_tests, final_text="".join(text)[-600:], stderr_tail=err[-400:])
    if final:
        u = final.get("usage") or {}
        rec.update(claude_subtype=final.get("subtype"), num_turns=final.get("num_turns"), duration_ms=final.get("duration_ms"),
                   input_tokens=(u.get("input_tokens") or 0) + (u.get("cache_read_input_tokens") or 0) + (u.get("cache_creation_input_tokens") or 0),
                   output_tokens=u.get("output_tokens"), model=list((final.get("modelUsage") or {}).keys()))
        if final.get("is_error"):
            rec.setdefault("errors", []).append(str(final.get("result"))[:300])
    try:
        os.remove(os.path.join(work, ".bench_prompt.txt"))
    except OSError:
        pass


# ---------------------------------------------------------------- 通用
def run_task(task, meta, agent, mode, label, timeout, results_dir):
    tdir = os.path.join(TASKS, task)
    prompt = open(os.path.join(tdir, "task.md"), encoding="utf-8").read().strip()
    run_dir = os.path.join(results_dir, "runs", task)
    shutil.rmtree(run_dir, ignore_errors=True)
    work = os.path.join(run_dir, "work")
    shutil.copytree(tdir, work, ignore=shutil.ignore_patterns("hidden", "task.md"))
    os.chmod(work, 0o777)
    home = prepare_home(run_dir)
    before = tree_hash(work)
    visible = meta.get("visible_tests") or ["tests"]
    name = f"agent-bench-{uuid.uuid4().hex[:8]}"
    rec = {"task": task, "agent": agent, "mode": mode if agent == "myagent" else "-", "label": label, "started": time.time()}
    events = []
    try:
        if agent == "myagent":
            run_myagent(task, prompt, mode, work, home, name, timeout, rec, events)
        else:
            run_claude(task, prompt, work, home, name, timeout, rec, events)
        after = tree_hash(work)
        changed = sorted(set(k for k in set(before) | set(after) if before.get(k) != after.get(k)))
        rec["changed_files"] = changed
        rec["tests_modified"] = any(any(c == v or c.startswith(v.rstrip("/") + "/") for v in visible) for c in changed)
        # 验收
        sh(["docker", "cp", os.path.join(tdir, "hidden"), f"{name}:/work/hidden_tests"])
        r = sh(["docker", "exec", "-w", "/work", name, "python3", "-m", "pytest", "-q", "--no-header", "-p", "no:cacheprovider", *visible, "hidden_tests"], timeout=300)
        rec["pytest_rc"] = r.returncode
        rec["pytest_tail"] = (r.stdout + r.stderr)[-1200:]
        summary_line = next((l for l in r.stdout.splitlines()[::-1] if "passed" in l or "failed" in l or "error" in l), "")
        rec["pytest_summary"] = summary_line.strip()
        import re
        p = re.search(r"(\d+) passed", summary_line)
        f = re.search(r"(\d+) failed", summary_line)
        e = re.search(r"(\d+) error", summary_line)
        rec["tests_passed"] = int(p.group(1)) if p else 0
        rec["tests_failed"] = (int(f.group(1)) if f else 0) + (int(e.group(1)) if e else 0)
        rec["success"] = r.returncode == 0 and not rec["tests_modified"]
    except Exception as ex:  # noqa: BLE001
        rec["error"] = str(ex)[:500]
        rec["success"] = False
    finally:
        sh(["docker", "rm", "-f", name])
    rec["events_count"] = len(events)
    json.dump({"record": rec, "events": events}, open(os.path.join(results_dir, f"{task}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--agent", choices=["myagent", "claude"], default="myagent")
    ap.add_argument("--mode", choices=["react", "react+review", "prompt", "plan", "team"], default="react")
    ap.add_argument("--label", default=None)
    ap.add_argument("--tasks", default="")
    ap.add_argument("--only-source", default="", help="hand / exercism")
    ap.add_argument("--timeout", type=int, default=3600, help="单题上限（秒）。默认一小时：规划/流水线一题要跑好几道工序，15 分钟偏紧")
    ap.add_argument("--jar", default=None, help="换一个后端 jar（默认 target/myagent.jar），用来做同模型的新旧版本对照")
    ap.add_argument("--subagents", choices=["off", "explicit", "proactive"], default="explicit",
                    help="子 agent（MYAGENT_SUBAGENTS）：off 关掉；explicit 用户没要求不派（Codex 默认）；proactive 能并行就派")
    args = ap.parse_args()
    global SUBAGENTS
    SUBAGENTS = args.subagents
    if args.jar:
        global JAR
        JAR = os.path.abspath(args.jar)
    label = args.label or (args.agent if args.agent == "claude" else f"myagent-{args.mode}")
    index = json.load(open(os.path.join(TASKS, "index.json")))
    meta = {t["name"]: t for t in index}
    names = [t["name"] for t in index]
    if args.only_source:
        names = [n for n in names if (meta[n].get("source") or "hand") == args.only_source]
    if args.tasks:
        names = [n for n in args.tasks.split(",") if n]
    results_dir = os.path.join(BENCH, "results", label)
    os.makedirs(results_dir, exist_ok=True)
    summary = []
    for i, task in enumerate(names, 1):
        print(f"[{i}/{len(names)}] {task} ({label}) ...", flush=True)
        rec = run_task(task, meta[task], args.agent, args.mode, label, args.timeout, results_dir)
        rec.update({"kind": meta[task]["kind"], "difficulty": meta[task]["difficulty"], "source": meta[task].get("source", "hand")})
        summary.append(rec)
        print(f"   -> {'PASS' if rec.get('success') else 'FAIL'}  steps={rec.get('steps')} tools={rec.get('tool_calls_n')} writes={rec.get('writes')} "
              f"tests={rec.get('tests_passed')}/{(rec.get('tests_passed') or 0) + (rec.get('tests_failed') or 0)} ran_tests={rec.get('ran_tests')} "
              f"in={rec.get('input_tokens')} out={rec.get('output_tokens')} {rec.get('elapsed_s')}s "
              f"{'TIMEOUT' if rec.get('timeout') else ''}{' TESTS_MODIFIED' if rec.get('tests_modified') else ''} {rec.get('error', '')[:80]}", flush=True)
        json.dump(summary, open(os.path.join(results_dir, "summary.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    ok = sum(1 for r in summary if r.get("success"))
    print(f"\n{label}: {ok}/{len(summary)} passed ({ok / max(1, len(summary)) * 100:.1f}%)")


if __name__ == "__main__":
    main()

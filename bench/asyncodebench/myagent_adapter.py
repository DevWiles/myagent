"""AsynCodeBench adapter that drives myagent (Java) inside the task workspace.

The harness owns the Docker workspace; we only get ``workspace.execute_command``
and ``workspace.file_upload``. So the whole runtime (a portable JRE + the jar)
is uploaded into the container once, and every assignment is executed with
``java -jar myagent.jar --once`` (headless single run, auto-approve).

Config keys (``--agent-config-json``):
  mode        "react" (default), "react+review" (single writer + final independent review)
              or "team"                         -> myagent --once-mode
  jar         host path of myagent.jar          (default ~/myagent.jar)
  jre         host path of a linux-x64 JRE tgz  (default ~/jre/jre17.tar.gz)
  max_steps   override the per-assignment step cap; 0 = unlimited (default: request.max_iterations,
              i.e. the benchmark's own response budget)
  delegate    false -> MYAGENT_DELEGATE=0, the main agent gets no `delegate` tool (A/B control)
  timeout     seconds per assignment (default 1500)
  model       model id for myagent (default: LLM_MODEL env without provider prefix)
Credentials come from the model environment (LLM_BASE_URL / LLM_API_KEY), never
from this config, so the persisted adapter config stays secret-free.
"""
from __future__ import annotations

import json
import os
import re
import shlex
import tempfile
import threading
import time
from pathlib import Path

from agents import AgentAdapter, AgentRunResponse

RT = "/tmp/myagent-rt"            # runtime inside the container: jre/ + myagent.jar
HOME = "/tmp/myagent-home"        # -Duser.home: ~/.myagent/config.json lives here
_LOCK = threading.Lock()


def _sh(workspace, cmd, timeout=120, cwd=None):
    if cwd is None:
        return workspace.execute_command(cmd, timeout=timeout)
    return workspace.execute_command(cmd, cwd=cwd, timeout=timeout)


def _model_id(explicit):
    if explicit:
        return explicit
    raw = os.environ.get("LLM_MODEL", "deepseek-chat")
    return raw.split("/", 1)[1] if "/" in raw else raw


class MyAgentAdapter(AgentAdapter):
    name = "myagent"

    def __init__(self, config=None):
        super().__init__(config)
        self.mode = self.config.get("mode", "react")
        self.jar = os.path.expanduser(self.config.get("jar", "~/myagent.jar"))
        self.jre = os.path.expanduser(self.config.get("jre", "~/jre/jre17.tar.gz"))
        self.timeout = float(self.config.get("timeout", 1500))
        self.delegate = bool(self.config.get("delegate", True))
        self.model = _model_id(self.config.get("model"))
        self._ready = set()          # workspace ids where the runtime is installed

    # ── runtime ──────────────────────────────────────────────────
    def _ensure_runtime(self, ws):
        key = id(ws)
        with _LOCK:
            if key in self._ready:
                return
            probe = _sh(ws, f"test -x {RT}/jre/bin/java && test -f {RT}/myagent.jar && echo READY")
            if "READY" not in (probe.stdout or ""):
                _sh(ws, f"rm -rf {RT} && mkdir -p {RT} {HOME}/.myagent")
                up = ws.file_upload(self.jre, f"{RT}/jre.tgz")
                if not getattr(up, "success", True):
                    raise RuntimeError(f"jre upload failed: {up}")
                up = ws.file_upload(self.jar, f"{RT}/myagent.jar")
                if not getattr(up, "success", True):
                    raise RuntimeError(f"jar upload failed: {up}")
                r = _sh(ws, f"cd {RT} && mkdir -p jre && tar xzf jre.tgz -C jre --strip-components=1 "
                            f"&& rm jre.tgz && {RT}/jre/bin/java -version", timeout=300)
                if r.exit_code != 0:
                    raise RuntimeError(f"jre unpack failed: {r.stderr or r.stdout}")
            # config.json: written from a local temp file so the key never appears in a command line
            cfg = {
                "apiKey": os.environ.get("LLM_API_KEY", ""),
                "baseUrl": os.environ.get("LLM_BASE_URL", "https://api.deepseek.com"),
                "model": self.model,
                "thinking": False,
            }
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
                json.dump(cfg, f)
                local = f.name
            try:
                _sh(ws, f"mkdir -p {HOME}/.myagent")
                ws.file_upload(local, f"{HOME}/.myagent/config.json")
                with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as g:
                    json.dump({"version": 1, "allow": [], "deny": []}, g)
                    pol = g.name
                ws.file_upload(pol, f"{HOME}/.myagent/policy.json")
                os.unlink(pol)
            finally:
                os.unlink(local)
            self._ready.add(key)

    # ── prompt ───────────────────────────────────────────────────
    @staticmethod
    def _prompt(request):
        parts = [request.instruction.strip(), "", "【执行约束——来自评测框架，必须遵守】"]
        if request.writable_paths:
            parts.append("- **只能新建或修改这些文件**（其它文件包括测试一律不动；改了会被评测框架整体拒收，"
                         "你在这些文件里做对的部分也一起作废）：" + ", ".join(request.writable_paths)
                         + "。别的模块由别的 agent 负责，你只按依赖约定调用它们的接口。")
        if request.primary_test_targets:
            parts.append("- 主要验收测试：" + ", ".join(request.primary_test_targets)
                         + "。改完用 `python -m pytest -q -p no:cacheprovider -o addopts= <目标>` 跑一遍，"
                           "以真实输出为准。")
        if request.dependency_annotations:
            parts.append("- 依赖关系（别的模块会按这些约定调用你写的接口，或你要按它们的约定调用别的模块）：")
            for d in request.dependency_annotations:
                parts.append(f"  · {d.get('producer_subproblem')} → {d.get('consumer_subproblem')}: "
                             f"{d.get('description', '')}")
        parts.append("- 不要 git commit，不要改 .git；不要写工作区以外的文件；不要联网查现成实现。")
        parts.append("- 做完把每个改过的文件和验证结果简要交代一下。")
        return "\n".join(parts)

    # ── scope ────────────────────────────────────────────────────
    @staticmethod
    def _enforce_scope(ws, path, writable_paths):
        if not writable_paths:
            return []
        allowed = [p.strip("/") for p in writable_paths]

        def ok(rel):
            rel = rel.strip("/")
            return any(rel == a or rel.startswith(a + "/") for a in allowed)

        st = _sh(ws, f"cd {shlex.quote(path)} && git status --porcelain --untracked-files=all", timeout=60)
        reverted = []
        for line in (st.stdout or "").splitlines():
            if len(line) < 4:
                continue
            code, rel = line[:2], line[3:]
            if " -> " in rel:
                rel = rel.split(" -> ", 1)[1]
            if ok(rel):
                continue
            q = shlex.quote(rel)
            if code.strip() == "??":
                _sh(ws, f"cd {shlex.quote(path)} && rm -rf -- {q}", timeout=30)
            else:
                _sh(ws, f"cd {shlex.quote(path)} && git checkout -q -- {q} 2>/dev/null || git rm -q --cached -- {q}", timeout=30)
            reverted.append(rel)
        return reverted

    # ── execute ──────────────────────────────────────────────────
    def execute(self, request):
        ws = request.workspace
        path = request.workspace_path
        t0 = time.time()
        events = []
        try:
            self._ensure_runtime(ws)
        except Exception as e:  # noqa: BLE001 - report as adapter error, harness decides validity
            return AgentRunResponse(error=f"runtime install failed: {e}", events=[
                {"event_type": "myagent_runtime_error", "content": {"error": str(e)}}])

        tag = re.sub(r"[^A-Za-z0-9_.-]", "_", f"{request.assignment_id}-r{request.round_num}")
        prompt_path = f"/tmp/myagent-prompt-{tag}.txt"
        out_path = f"/tmp/myagent-once-{tag}.json"
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write(self._prompt(request))
            local = f.name
        try:
            ws.file_upload(local, prompt_path)
        finally:
            os.unlink(local)

        # 显式配了就听配置（0 = 不限，照 Claude Code 默认）；没配就用框架的响应预算
        # （standard-100 档是 100——那是题库的统一规则，改了成绩就不和别人可比）
        if "max_steps" in self.config:
            max_steps = int(self.config["max_steps"])
        else:
            max_steps = int(request.max_iterations or 0)
        delegate_env = "" if self.delegate else "MYAGENT_DELEGATE=0 "
        cmd = (f"cd {shlex.quote(path)} && HOME={HOME} {delegate_env}{RT}/jre/bin/java -Duser.home={HOME} -Xmx512m "
               f"-jar {RT}/myagent.jar --once {prompt_path} --once-mode {self.mode} -d {shlex.quote(path)} "
               f"--unsafe --max-steps {max_steps} --once-out {out_path} 2>/tmp/myagent-stderr-{tag}.log")
        run = _sh(ws, cmd, timeout=self.timeout)
        summary = {}
        cat = _sh(ws, f"cat {out_path} 2>/dev/null")
        try:
            summary = json.loads((cat.stdout or "").strip().splitlines()[-1])
        except Exception:  # noqa: BLE001
            summary = {}
        stderr_tail = _sh(ws, f"tail -c 4000 /tmp/myagent-stderr-{tag}.log 2>/dev/null").stdout or ""

        # Scope is enforced mechanically, not by prompt: anything the agent touched outside
        # writable_paths is reverted before commit. The harness would reject the whole
        # artifact otherwise (that is how cachetools/async_private lost a 5/5 func.py because
        # the same worker also "helpfully" edited keys.py).
        reverted = self._enforce_scope(ws, path, request.writable_paths)
        if reverted:
            events.append({"event_type": "myagent_scope_revert", "content": {"reverted": reverted}})
        # commit whatever the agent produced; the harness validates scope and derives the patch itself
        commit = _sh(ws, f"cd {shlex.quote(path)} && git add -A && "
                         f"git -c user.name=myagent -c user.email=myagent@bench commit -q -m "
                         f"{shlex.quote('myagent ' + tag)} 2>&1 || true", timeout=120)

        error = None
        if getattr(run, "timeout_occurred", False):
            error = f"myagent timed out after {self.timeout}s"
        elif run.exit_code == 2:
            error = "myagent setup error: " + (stderr_tail[-500:] or run.stdout or "")[-500:]
        # exit 1 (hit step cap / verdict not passed) is a coding outcome, not an adapter error

        events.append({"event_type": "myagent_once", "content": {
            "exit_code": run.exit_code, "summary": summary,
            "final_text": (run.stdout or "")[-3000:], "stderr_tail": stderr_tail[-1500:],
            "commit": (commit.stdout or "")[-300:]}})
        return AgentRunResponse(
            error=error,
            prompt_tokens=int(summary.get("promptTokens", 0) or 0),
            completion_tokens=int(summary.get("completionTokens", 0) or 0),
            iterations=int(summary.get("steps", 0) or 0),
            events=events,
            metadata={"mode": self.mode, "model": self.model, "status": summary.get("status"),
                      "rounds": summary.get("rounds"), "exit_code": run.exit_code,
                      "reviews": summary.get("reviews"), "review_verdicts": summary.get("reviewVerdicts"),
                      "review_scores": summary.get("reviewScores"), "review_restored": summary.get("reviewRestored"),
                      "review_steps": summary.get("reviewSteps"),
                      "delegate": self.delegate, "delegations": summary.get("delegations"),
                      "delegate_steps": summary.get("delegateSteps"),
                      "duration_s": round(time.time() - t0, 1)},
        )

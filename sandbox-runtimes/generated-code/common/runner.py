#!/usr/bin/env python3
"""Fixed wrapper for Agent-generated Python or POSIX shell source; not a security boundary by itself."""
import contextlib
import io
import json
import os
import pathlib
import resource
import subprocess
import sys
import urllib.parse
import urllib.request

OUTPUT_PATH = pathlib.Path(os.environ.get("SANDBOX_OUTPUT_PATH", "/sandbox/output/result.json"))
MAX_OUTPUT = int(os.environ.get("SANDBOX_MAX_OUTPUT_BYTES", "1048576"))
MAX_PROCESSES = int(os.environ.get("SANDBOX_MAX_PROCESSES", "32"))


class BoundedText(io.StringIO):
    def write(self, value):
        remaining = MAX_OUTPUT - self.tell()
        if remaining > 0:
            super().write(value[:remaining])
        return len(value)


def fetch_input():
    uri = os.environ.get("SANDBOX_INPUT_ARTIFACT_URI", "")
    parsed = urllib.parse.urlparse(uri)
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("input must be an object-scoped HTTP(S) artifact URL")
    with urllib.request.urlopen(uri, timeout=15) as response:
        body = response.read(50 * 1024 * 1024 + 1)
    if len(body) > 50 * 1024 * 1024:
        raise ValueError("input artifact exceeds limit")
    return json.loads(body)


def apply_limits():
    resource.setrlimit(resource.RLIMIT_CPU, (120, 120))
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_OUTPUT, MAX_OUTPUT))
    resource.setrlimit(resource.RLIMIT_NPROC, (MAX_PROCESSES, MAX_PROCESSES))


def result(status, findings, evidence, summary):
    return {"schemaVersion": "v1", "status": status, "findings": findings, "evidenceReferences": evidence, "summary": summary[:16384]}


def execute_python(source, captured):
    namespace = {"__name__": "__sandbox__"}
    with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
        exec(compile(source, "/sandbox/input/generated.py", "exec"), namespace, namespace)
    value = namespace.get("result")
    if isinstance(value, dict):
        return result("SUCCEEDED", value.get("findings", []), value.get("evidenceReferences", []), str(value.get("summary", "")))
    return result("SUCCEEDED", [], [], captured.getvalue())


def execute_shell(source, captured):
    script = pathlib.Path("/sandbox/input/generated.sh")
    script.parent.mkdir(parents=True, exist_ok=True)
    script.write_text(source, encoding="utf-8")
    completed = subprocess.run(["/bin/sh", str(script)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, check=False, env={"PATH": "/usr/bin:/bin"})
    captured.write(completed.stdout or "")
    if completed.returncode:
        return result("FAILED", [], [], captured.getvalue())
    return result("SUCCEEDED", [], [], captured.getvalue())


def main(runtime):
    captured = BoundedText()
    try:
        payload = fetch_input()
        if payload.get("runtime") != runtime:
            raise ValueError("runtime contract mismatch")
        source = payload.get("source")
        if not isinstance(source, str) or len(source.encode("utf-8")) > 262144:
            raise ValueError("invalid source")
        apply_limits()
        output = execute_python(source, captured) if runtime == "python3" else execute_shell(source, captured)
    except subprocess.TimeoutExpired:
        output = result("TIMED_OUT", [], [], "runtime timeout")
    except BaseException as exc:
        output = result("FAILED", [], [], type(exc).__name__)
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(output, separators=(",", ":"), ensure_ascii=True)
    OUTPUT_PATH.write_text(encoded, encoding="utf-8")
    print("KOC_RESULT_JSON:" + encoded)
    return 0 if output["status"] == "SUCCEEDED" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))

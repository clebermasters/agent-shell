#!/usr/bin/env python3
"""
Smoke-test the Codex direct-session websocket flow against the local backend
source tree. This starts a second backend instance on temporary ports so it
does not collide with an already-installed AgentShell server.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Iterable

try:
    import websockets
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "websockets", "-q"])
    import websockets


ROOT = Path(__file__).resolve().parents[1]
BACKEND_DIR = ROOT / "backend-rust"


def reserve_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def tail_file(path: Path, lines: int = 80) -> str:
    if not path.exists():
        return "<no log file>"
    content = path.read_text(errors="replace").splitlines()
    return "\n".join(content[-lines:])


def fail(message: str, log_path: Path | None = None) -> "NoReturn":
    print(f"\nFAIL: {message}", file=sys.stderr)
    if log_path is not None:
        print("\nBackend log tail:", file=sys.stderr)
        print(tail_file(log_path), file=sys.stderr)
    raise SystemExit(1)


async def wait_for_backend(url: str, proc: subprocess.Popen[str], log_path: Path, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    last_error = None

    while time.monotonic() < deadline:
        if proc.poll() is not None:
            fail(f"backend exited before becoming ready (code {proc.returncode})", log_path)

        try:
            async with websockets.connect(url, open_timeout=1, close_timeout=1):
                return
        except Exception as exc:  # pragma: no cover - best-effort startup polling
            last_error = exc
            await asyncio.sleep(0.25)

    fail(f"backend did not become ready within {timeout:.1f}s: {last_error}", log_path)


async def recv_until(
    ws: websockets.WebSocketClientProtocol,
    wanted: Iterable[str],
    *,
    timeout: float,
    log_prefix: str,
) -> dict:
    wanted = set(wanted)
    deadline = time.monotonic() + timeout
    seen: list[str] = []

    while time.monotonic() < deadline:
        remaining = max(0.1, deadline - time.monotonic())
        raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        message = json.loads(raw)
        msg_type = message.get("type", "<missing>")
        seen.append(msg_type)
        print(f"{log_prefix} <- {msg_type}")

        if msg_type == "acp-error":
            raise RuntimeError(f"received acp-error: {message.get('message')}")

        if msg_type in wanted:
            return message

    raise RuntimeError(f"timed out waiting for {sorted(wanted)}; saw {seen}")


def assert_session_listed(message: dict, session_id: str, cwd: str, *, reconnect: bool) -> None:
    sessions = message.get("sessions") or []
    matches = [session for session in sessions if session.get("sessionId") == session_id]
    if not matches:
        suffix = "after reconnect" if reconnect else "after creation"
        raise RuntimeError(
            f"created session {session_id} was not present in acp-list-sessions {suffix}"
        )

    session = matches[0]
    if session.get("cwd") != cwd:
        raise RuntimeError(f"session cwd mismatch: expected {cwd}, got {session.get('cwd')}")
    provider = (session.get("provider") or "").lower()
    if provider != "codex":
        raise RuntimeError(
            f"session provider mismatch: expected codex, got {session.get('provider')}"
        )


async def exercise_flow(url: str, cwd: str, timeout: float) -> None:
    print(f"Testing websocket flow against {url}")

    async with websockets.connect(url) as ws:
        print("ws1 -> select-backend codex")
        await ws.send(json.dumps({"type": "select-backend", "backend": "codex"}))
        await recv_until(ws, {"backend-selected"}, timeout=timeout, log_prefix="ws1")

        print(f"ws1 -> acp-create-session cwd={cwd}")
        await ws.send(json.dumps({
            "type": "acp-create-session",
            "cwd": cwd,
            "backend": "codex",
        }))
        created = await recv_until(ws, {"acp-session-created"}, timeout=timeout, log_prefix="ws1")
        session_id = created.get("sessionId")
        created_cwd = created.get("cwd")

        if not session_id or not session_id.startswith("codex:"):
            raise RuntimeError(f"unexpected session id from create: {created}")
        if created_cwd != cwd:
            raise RuntimeError(f"create response cwd mismatch: expected {cwd}, got {created_cwd}")

        print(f"Created session: {session_id}")

        print("ws1 -> acp-list-sessions")
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        listed = await recv_until(ws, {"acp-sessions-listed"}, timeout=timeout, log_prefix="ws1")
        assert_session_listed(listed, session_id, cwd, reconnect=False)

    async with websockets.connect(url) as ws:
        print("ws2 -> select-backend codex")
        await ws.send(json.dumps({"type": "select-backend", "backend": "codex"}))
        await recv_until(ws, {"backend-selected"}, timeout=timeout, log_prefix="ws2")

        print("ws2 -> acp-list-sessions")
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        listed = await recv_until(ws, {"acp-sessions-listed"}, timeout=timeout, log_prefix="ws2")
        assert_session_listed(listed, session_id, cwd, reconnect=True)

        print("ws2 -> acp-resume-session")
        await ws.send(json.dumps({
            "type": "acp-resume-session",
            "sessionId": session_id,
            "cwd": cwd,
        }))
        resumed = await recv_until(ws, {"acp-session-created"}, timeout=timeout, log_prefix="ws2")
        if resumed.get("sessionId") != session_id:
            raise RuntimeError(
                f"resume returned wrong session id: expected {session_id}, got {resumed.get('sessionId')}"
            )

        print("ws2 -> acp-load-history")
        await ws.send(json.dumps({
            "type": "acp-load-history",
            "sessionId": session_id,
            "offset": 0,
            "limit": 20,
        }))
        history = await recv_until(ws, {"acp-history-loaded"}, timeout=timeout, log_prefix="ws2")
        if history.get("sessionId") != session_id:
            raise RuntimeError(
                f"history returned wrong session id: expected {session_id}, got {history.get('sessionId')}"
            )


async def main() -> None:
    parser = argparse.ArgumentParser(description="Smoke-test Codex direct-session create/list/resume flow")
    parser.add_argument("--timeout", type=float, default=60.0, help="Timeout in seconds for backend startup and each websocket step")
    parser.add_argument("--cwd", default=None, help="Working directory to use for the created Codex session")
    args = parser.parse_args()

    http_port = reserve_free_port()
    https_port = reserve_free_port()
    token = f"codex-test-{uuid.uuid4().hex}"
    log_dir = Path(tempfile.mkdtemp(prefix="webmux-codex-test-"))
    log_path = log_dir / "backend.log"

    cwd = os.path.abspath(args.cwd or str(ROOT))
    env = os.environ.copy()
    env["AUTH_TOKEN"] = token
    env["AGENTSHELL_HTTP_PORT"] = str(http_port)
    env["AGENTSHELL_HTTPS_PORT"] = str(https_port)
    env.setdefault("RUST_LOG", "info")

    print(f"Starting backend on port {http_port}")
    with log_path.open("w", encoding="utf-8") as log_file:
        proc = subprocess.Popen(
            ["cargo", "run", "--quiet"],
            cwd=BACKEND_DIR,
            env=env,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
        )

    url = f"ws://127.0.0.1:{http_port}/ws?token={token}"

    try:
        await wait_for_backend(url, proc, log_path, timeout=args.timeout)
        await exercise_flow(url, cwd, timeout=args.timeout)
    except SystemExit:
        raise
    except Exception as exc:
        fail(f"unexpected exception: {exc}", log_path)
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)

    print("\nPASS: Codex direct-session create/list/resume flow succeeded.")
    print(f"Backend log: {log_path}")


if __name__ == "__main__":
    asyncio.run(main())

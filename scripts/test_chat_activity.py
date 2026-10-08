#!/usr/bin/env python3
"""Read-only WebSocket smoke test against a temporary backend; never sends agent input.

Requires websockets and an existing tmux session. Builds are not deployed by this script.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time

import websockets


def tmux_snapshot():
    return subprocess.check_output([
        "tmux", "list-panes", "-a", "-F",
        "#{session_name}|#{window_index}|#{pane_id}|#{pane_width}|#{pane_height}",
    ], text=True).splitlines()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


async def watch(socket, session, window=0):
    await socket.send(json.dumps({
        "type": "watch-chat-log", "sessionName": session, "windowIndex": window, "limit": 1,
    }))


async def activity(socket, session, window=0, ready=True):
    deadline = asyncio.get_running_loop().time() + 8
    while True:
        remaining = deadline - asyncio.get_running_loop().time()
        msg = json.loads(await asyncio.wait_for(socket.recv(), timeout=max(remaining, 0.001)))
        if msg.get("type") != "chat-activity":
            continue
        if msg.get("sessionName") != session or msg.get("windowIndex") != window:
            continue
        if ready and msg["state"]["sequence"] == 0:
            continue
        return msg


async def history(socket, session, window=0):
    deadline = asyncio.get_running_loop().time() + 15
    while True:
        remaining = deadline - asyncio.get_running_loop().time()
        msg = json.loads(await asyncio.wait_for(socket.recv(), timeout=max(remaining, 0.001)))
        if msg.get("type") == "chat-log-error":
            raise AssertionError("Chat history initialization returned an error")
        if msg.get("type") == "chat-history" and msg.get("sessionName") == session and msg.get("windowIndex") == window:
            assert msg.get("messages"), "Expected nonempty chat history"
            return len(msg["messages"])


async def verify(url, args, before):
    async with websockets.connect(url, max_size=16 * 1024 * 1024) as first, websockets.connect(url, max_size=16 * 1024 * 1024) as second:
        await watch(first, args.session, args.window)
        history_count = await history(first, args.session, args.window) if args.require_history else None
        one = await activity(first, args.session, args.window)
        if one["state"]["status"] == "working":
            started = one["state"]["startedAt"]
            elapsed = one["state"]["observedAt"] - started if isinstance(started, int) else -1
            assert 0 <= elapsed < 365 * 24 * 60 * 60 * 1000, "Working timer has an invalid timestamp unit"
        if args.expected_source:
            assert one["state"]["source"] == args.expected_source, "Expected lifecycle adapter was not used"
        await watch(second, args.session, args.window)
        two = await activity(second, args.session, args.window)
        assert one["paneId"] == two["paneId"]
        assert one["state"]["observerId"] == two["state"]["observerId"], "Two clients did not share an observer"
        await first.send(json.dumps({"type": "unwatch-chat-log"}))
        while True:
            update = await activity(second, args.session, args.window)
            if update["state"]["sequence"] > two["state"]["sequence"]:
                break
        async with websockets.connect(url, max_size=16 * 1024 * 1024) as reconnected:
            await watch(reconnected, args.session, args.window)
            snapshot = await activity(reconnected, args.session, args.window)
            assert snapshot["state"]["observerId"] == two["state"]["observerId"], "Reconnect lost the shared observer"
        other = next((row.split("|") for row in before if row.split("|")[2] != one["paneId"]), None)
        if other:
            async with websockets.connect(url, max_size=16 * 1024 * 1024) as separate:
                await watch(separate, other[0], int(other[1]))
                separate_state = await activity(separate, other[0], int(other[1]))
                assert separate_state["paneId"] != one["paneId"]
                assert separate_state["state"]["observerId"] != one["state"]["observerId"], "Different panes shared activity state"
        missing = "activity-missing-" + secrets.token_hex(6)
        await watch(first, missing)
        unavailable = await activity(first, missing, ready=False)
        assert unavailable["state"]["status"] == "unknown", "An unavailable terminal was marked finished"
        print(json.dumps({
            "shared_observer": True, "peer_unsubscribe": True, "reconnect_snapshot": True,
            "separate_panes": bool(other), "missing_pane_fallback": True,
            "observed_status": one["state"]["status"], "observed_source": one["state"]["source"],
            "history_messages": history_count,
        }))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=Path(__file__).resolve().parents[1] / "backend-rust/target/release/agentshell-backend")
    parser.add_argument("--session", default="agentShell")
    parser.add_argument("--window", type=int, default=0)
    parser.add_argument("--expected-source")
    parser.add_argument("--require-history", action="store_true", help="Also require successful initial chat history delivery")
    args = parser.parse_args()
    before = tmux_snapshot()
    token = secrets.token_urlsafe(32)
    port = free_port()
    env = dict(os.environ, AUTH_TOKEN=token, AGENTSHELL_HTTP_PORT=str(port), AGENTSHELL_HTTPS_PORT=str(free_port()), RUST_LOG="error")
    with tempfile.TemporaryDirectory(prefix="agentshell-activity-") as temporary:
        directory = Path(temporary) / "instance"
        directory.mkdir()
        with (Path(temporary) / "backend.log").open("w") as log:
            process = subprocess.Popen([str(args.binary.resolve())], cwd=directory, env=env, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 10
                while True:
                    if process.poll() is not None:
                        raise RuntimeError("Temporary backend exited before the smoke test")
                    try:
                        with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                            break
                    except OSError:
                        if time.monotonic() > deadline:
                            raise RuntimeError("Temporary backend did not start")
                        time.sleep(0.1)
                asyncio.run(asyncio.wait_for(verify(f"ws://127.0.0.1:{port}/ws?token={token}", args, before), timeout=45))
            finally:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    assert tmux_snapshot() == before, "Pane inventory or dimensions changed during the read-only test"
    print("Pane inventory and dimensions preserved; temporary backend stopped.")


if __name__ == "__main__":
    main()

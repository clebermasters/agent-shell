#!/usr/bin/env python3
"""Exercise Android's shared-socket Chat → Terminal → Chat navigation.

Uses an isolated TMUX server, fake PTY agents, and temporary backend databases.
Existing agents, user settings, and production bindings are never changed.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import shlex
import socket
import subprocess
import tempfile
import time
import uuid

import websockets
from test_chat_bindings import FAKE_AGENT, free_port, receive, send, texts, watch


async def attach(connection, session):
    await connection.send(json.dumps({"type": "attach-session", "sessionName": session,
        "windowIndex": 0, "cols": 80, "rows": 24}))
    # Commands on one WebSocket are handled in order; the next watch waits for attach.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    args = parser.parse_args()
    inventory_args = ["tmux", "list-panes", "-a", "-F", "#{pane_id}|#{pane_pid}|#{pane_width}|#{pane_height}"]
    production = subprocess.check_output(inventory_args, text=True)
    socket_name = "agentshell-navigation-test-" + uuid.uuid4().hex
    identifiers = [str(uuid.uuid4()) for _ in range(4)]
    token = secrets.token_urlsafe(32)
    backend = None
    with tempfile.TemporaryDirectory(prefix="agentshell-navigation-test-") as temporary:
        root = Path(temporary)
        project = root / "same-folder"
        project.mkdir()
        script = root / "fake-agent.py"
        script.write_text(FAKE_AGENT)
        def tmux(*arguments):
            return subprocess.check_output(["tmux", "-L", socket_name, *arguments], text=True).strip()
        try:
            for label, identifier in zip(["A", "B"], identifiers):
                command = "python3 " + shlex.quote(str(script)) + " " + shlex.quote(str(project)) + " " + label + " " + identifier
                if label == "A":
                    command += " --subagent"
                tmux("new-session", "-d", "-s", "terminal-" + label, "-c", str(project), command)
            deadline = time.monotonic() + 5
            while not all((project / (label + ".ready")).exists() for label in ["A", "B"]):
                assert time.monotonic() < deadline, "fake agents did not start"
                time.sleep(.01)
            port = free_port()
            instance = root / "backend"
            instance.mkdir()
            context = tmux("display-message", "-p", "-t", "terminal-A", "#{socket_path},#{pid},0")
            environment = dict(os.environ, AUTH_TOKEN=token, AGENTSHELL_HTTP_PORT=str(port),
                AGENTSHELL_HTTPS_PORT=str(free_port()), TMUX=context, RUST_LOG="error", TOKIO_WORKER_THREADS="2")
            log = (root / "backend.log").open("w")
            backend = subprocess.Popen([str(args.binary.resolve())], cwd=instance, env=environment, stdout=log, stderr=log)
            deadline = time.monotonic() + 10
            while True:
                assert backend.poll() is None, "temporary backend exited"
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=.2):
                        break
                except OSError:
                    assert time.monotonic() < deadline, "temporary backend did not start"
                    time.sleep(.05)

            def transcript(identifier):
                return project / ".codex/sessions" / ("rollout-" + identifier + ".jsonl")

            async def verify():
                endpoint = f"ws://127.0.0.1:{port}/ws?token={token}"
                async with websockets.connect(endpoint, max_size=16*1024*1024) as connection:
                    # Both agents already exist when the backend starts: old sessions
                    # must resolve automatically without startup hooks or a picker.
                    first, _ = await watch(connection, "terminal-A")
                    await attach(connection, "terminal-B")
                    second, history = await watch(connection, "terminal-B")
                    assert second["conversationId"] == identifiers[1], "Chat B inherited Chat A's binding after Terminal B attach"
                    assert second["pane"]["paneId"] == tmux("display-message", "-p", "-t", "terminal-B", "#{pane_id}")
                    assert all(not text.startswith("A ") for text in texts(history)), "Chat B inherited A's history"
                    await send(connection, "terminal-B", second, "navigation to B")
                    assert "navigation to B" not in transcript(identifiers[0]).read_text(), "B's message reached A"
                    # Even after another terminal attachment, B's chat ownership
                    # stays independent. A forged A send must be rejected.
                    await attach(connection, "terminal-A")
                    await send(connection, "terminal-A", first, "stale A request", success=False)
                    await send(connection, "terminal-B", second, "B while terminal A is attached")
                    assert "stale A request" not in transcript(identifiers[0]).read_text()
                    assert "B while terminal A is attached" not in transcript(identifiers[0]).read_text()
                    # Alternate the exact navigation that failed on Android.
                    for index in range(6):
                        label = "A" if index % 2 == 0 else "B"
                        opposite = "B" if label == "A" else "A"
                        sid = identifiers[0] if label == "A" else identifiers[1]
                        other = identifiers[1] if label == "A" else identifiers[0]
                        await attach(connection, "terminal-" + label)
                        binding, history = await watch(connection, "terminal-" + label)
                        assert binding["conversationId"] == sid
                        assert all(not text.startswith(opposite + " ") for text in texts(history))
                        message = f"navigation round {index} to {label}"
                        await send(connection, "terminal-" + label, binding, message)
                        assert message not in transcript(other).read_text()
                    await attach(connection, "terminal-A")
                    old, _ = await watch(connection, "terminal-A")
                    (project / "A.control").write_text(identifiers[2])
                    deadline = time.monotonic() + 5
                    while (project / "A.active").read_text() != identifiers[2]:
                        assert time.monotonic() < deadline, "fake agent did not resume"
                        await asyncio.sleep(.01)
                    # Send as soon as the native process switched, before the
                    # once-per-second watcher has refreshed its cached binding.
                    observed = []
                    await send(connection, "terminal-A", old, "resume race must not send", success=False, observed=observed)
                    assert "resume race must not send" not in transcript(identifiers[2]).read_text()
                    matches_resume = lambda m: m.get("type") == "chat-binding" and (m["state"].get("binding") or {}).get("conversationId") == identifiers[2]
                    packet = next((m for m in observed if matches_resume(m)), None)
                    if packet is None:
                        packet = await receive(connection, matches_resume)
                    resumed = packet["state"]["binding"]
                    matches_history = lambda m: m.get("type") == "chat-history" and m.get("bindingId") == resumed["bindingId"]
                    if not any(matches_history(m) for m in observed):
                        await receive(connection, matches_history)
                    await send(connection, "terminal-A", old, "stale before resume", success=False)
                    await send(connection, "terminal-A", resumed, "after in-process resume")
                    assert "stale before resume" not in transcript(identifiers[2]).read_text()
                    tmux("rename-session", "-t", "terminal-A", "renamed-A")
                    # Refresh the old alias after a rename; retain only this chat's
                    # exact pane, never whatever terminal was attached most recently.
                    await attach(connection, "terminal-B")
                    renamed, _ = await watch(connection, "terminal-A")
                    assert renamed["bindingId"] == resumed["bindingId"]
                    await send(connection, "terminal-A", renamed, "after rename refresh")
                    await connection.send(json.dumps({"type": "unwatch-chat-log"}))
                    await send(connection, "terminal-A", renamed, "after unwatch", success=False)
                    assert "after unwatch" not in transcript(identifiers[2]).read_text()
                    # Recreating the selected terminal must replace its pane and
                    # process incarnation automatically, without reconnecting.
                    await attach(connection, "terminal-B")
                    retired, _ = await watch(connection, "terminal-B")
                    tmux("kill-session", "-t", "terminal-B")
                    (project / "B.ready").unlink(missing_ok=True)
                    command = "python3 " + shlex.quote(str(script)) + " " + shlex.quote(str(project)) + " B " + identifiers[3]
                    tmux("new-session", "-d", "-s", "terminal-B", "-c", str(project), command)
                    deadline = time.monotonic() + 5
                    while not (project / "B.ready").exists():
                        assert time.monotonic() < deadline
                        await asyncio.sleep(.01)
                    observed = []
                    await send(connection, "terminal-B", retired, "retired pane must not send", success=False, observed=observed)
                    matches_recreated = lambda m: m.get("type") == "chat-binding" and (m["state"].get("binding") or {}).get("conversationId") == identifiers[3]
                    packet = next((m for m in observed if matches_recreated(m)), None)
                    if packet is None:
                        packet = await receive(connection, matches_recreated)
                    recreated = packet["state"]["binding"]
                    matches_history = lambda m: m.get("type") == "chat-history" and m.get("bindingId") == recreated["bindingId"]
                    if not any(matches_history(m) for m in observed):
                        await receive(connection, matches_history)
                    await send(connection, "terminal-B", recreated, "after terminal recreation")
                    assert "retired pane must not send" not in transcript(identifiers[3]).read_text()
                async with websockets.connect(endpoint, max_size=16*1024*1024) as connection:
                    await attach(connection, "terminal-B")
                    rebound, _ = await watch(connection, "renamed-A")
                    assert rebound["bindingId"] == resumed["bindingId"]
                    await send(connection, "renamed-A", rebound, "after reconnect with other terminal attached")
                print(json.dumps({"shared_socket_navigation": True, "exact_send_destination": True,
                    "old_sessions_automatic": True, "stale_target_rejected": True, "repeated_switching": True,
                    "in_process_resume": True, "rename_refresh": True, "unwatch_invalidates_send": True,
                    "session_recreation": True,
                    "reconnect_after_other_terminal_attach": True}))
            asyncio.run(verify())
        finally:
            if backend is not None:
                backend.terminate()
                try:
                    backend.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    backend.kill()
                    backend.wait(timeout=5)
            subprocess.run(["tmux", "-L", socket_name, "kill-server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            assert subprocess.check_output(inventory_args, text=True) == production, "Production TMUX panes changed"
    print("Isolated test completed; production TMUX panes untouched.")


if __name__ == "__main__":
    main()

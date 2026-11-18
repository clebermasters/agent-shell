#!/usr/bin/env python3
"""Verify assistant file publication against private TMUX and backend instances.

No real agent receives input, production notifications are never sent, and the
existing TMUX sessions/configuration/databases are left untouched.
"""
import argparse
import asyncio
import base64
import json
import os
from pathlib import Path
import secrets
import shlex
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

import websockets
from test_chat_bindings import FAKE_AGENT, free_port, receive, texts


async def watch(connection, name, window):
    await connection.send(json.dumps({"type": "watch-chat-log", "sessionName": name,
        "windowIndex": window, "limit": 200}))
    packet = await receive(connection, lambda value: value.get("type") == "chat-binding" and
        value.get("sessionName") == name and value["state"].get("status") == "bound")
    binding = packet["state"]["binding"]
    history = await receive(connection, lambda value: value.get("type") == "chat-history" and
        value.get("bindingId") == binding["bindingId"])
    return binding, history


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path)
    args = parser.parse_args()
    inventory_args = ["tmux", "list-panes", "-a", "-F", "#{pane_id}|#{pane_pid}|#{pane_width}|#{pane_height}"]
    production = subprocess.check_output(inventory_args, text=True)
    socket_name = "agentshell-file-publication-" + uuid.uuid4().hex
    token = secrets.token_urlsafe(32)
    identifiers = [str(uuid.uuid4()) for _ in range(5)]
    backend = None
    with tempfile.TemporaryDirectory(prefix="agentshell-file-publication-") as temporary:
        root = Path(temporary)
        project = root / "same-project"
        project.mkdir()
        fixture = root / "fake-agent.py"
        fixture.write_text(FAKE_AGENT.replace("ready.write_text('ready')", "ready.write_text(str(os.getpid()))"))
        instance = root / "backend"
        instance.mkdir()
        log = (root / "backend.log").open("w")

        def tmux(*arguments):
            return subprocess.check_output(["tmux", "-L", socket_name, *arguments], text=True).strip()

        def agent_command(label, identifier):
            command = "python3 " + " ".join(shlex.quote(value) for value in (str(fixture), str(project), label, identifier))
            return command + " 2>" + shlex.quote(str(root / ("fixture-" + label + ".stderr")))

        def wait_ready(label):
            deadline = time.monotonic() + 5
            while not (project / (label + ".ready")).exists():
                if time.monotonic() >= deadline:
                    stderr = root / ("fixture-" + label + ".stderr")
                    detail = stderr.read_text(errors="replace")[-4096:] if stderr.exists() else "no fixture stderr file"
                    target = "arbitrary-files-A:3" if label == "A" else "unrelated-files-B:7" if label == "B" else "ambiguous-output"
                    pane = subprocess.run(["tmux", "-L", socket_name, "capture-pane", "-p", "-t", target], capture_output=True, text=True)
                    raise AssertionError(f"fixture agent {label} failed to start: {detail}\n{pane.stdout[-4096:]}{pane.stderr[-1024:]}")
                time.sleep(.01)

        port = free_port()
        try:
            tmux("new-session", "-d", "-s", "arbitrary-files-A", "-c", str(project), agent_command("A", identifiers[0]))
            tmux("move-window", "-s", "arbitrary-files-A:0", "-t", "arbitrary-files-A:3")
            tmux("new-session", "-d", "-s", "unrelated-files-B", "-c", str(project), agent_command("B", identifiers[1]))
            tmux("move-window", "-s", "unrelated-files-B:0", "-t", "unrelated-files-B:7")
            wait_ready("A")
            wait_ready("B")
            context = tmux("display-message", "-p", "-t", "arbitrary-files-A:3", "#{socket_path},#{pid},0")
            environment = dict(os.environ, AUTH_TOKEN=token, TMUX=context,
                AGENTSHELL_HTTP_PORT=str(port), AGENTSHELL_HTTPS_PORT=str(free_port()),
                RUST_LOG="error", TOKIO_WORKER_THREADS="2")

            def start():
                process = subprocess.Popen([str(args.binary.resolve())], cwd=instance, env=environment, stdout=log, stderr=log)
                deadline = time.monotonic() + 10
                while True:
                    assert process.poll() is None, "temporary backend exited"
                    try:
                        with socket.create_connection(("127.0.0.1", port), timeout=.2):
                            return process
                    except OSError:
                        assert time.monotonic() < deadline, "temporary backend did not start"
                        time.sleep(.05)

            backend = start()

            def post(payload, expected=200):
                request = Request(f"http://127.0.0.1:{port}/api/chat/publish",
                    data=json.dumps(payload).encode(), method="POST",
                    headers={"Content-Type": "application/json", "X-Auth-Token": token})
                try:
                    with urlopen(request, timeout=15) as response:
                        assert response.status == expected
                        return json.load(response)
                except HTTPError as error:
                    assert error.code == expected, (error.code, error.read(2048).decode(errors="replace"))
                    return None

            def files():
                return sorted(path.name for path in (instance / "chat_files").iterdir())

            def sender(binding):
                return {"paneId": binding["pane"]["paneId"], "agentPid": binding["pane"]["agentPid"],
                    "agentStart": binding["pane"]["agentStart"]}

            async def verify():
                endpoint = f"ws://127.0.0.1:{port}/ws?token={token}"
                async with websockets.connect(endpoint, max_size=16 * 1024 * 1024) as a, websockets.connect(endpoint, max_size=16 * 1024 * 1024) as b:
                    first, _ = await watch(a, "arbitrary-files-A", 3)
                    second, _ = await watch(b, "unrelated-files-B", 7)
                    native_before = {identifier: (project / ".codex/sessions" / ("rollout-" + identifier + ".jsonl")).read_bytes()
                        for identifier in identifiers[:2]}
                    image = b"private-fixture-image-bytes"
                    payload = dict(sender(first), text="An image for the current conversation",
                        files=[{"filename": "preview.png", "mimeType": "image/png", "data": base64.b64encode(image).decode()}])
                    publication = await asyncio.to_thread(post, payload)
                    assert publication["published"] is True
                    assert (publication["sessionName"], publication["windowIndex"]) == ("arbitrary-files-A", 3)
                    assert publication["conversationKey"] == first["conversationKey"]
                    packet = await receive(a, lambda value: value.get("type") == "chat-event" and value.get("source") == "agent-publication")
                    assert packet["bindingId"] == first["bindingId"]
                    assert packet["message"]["role"] == "assistant"
                    assert packet["message"]["blocks"][1]["id"] == publication["fileIds"][0]
                    request = Request(f"http://127.0.0.1:{port}/api/chat/files/{publication['fileIds'][0]}", headers={"X-Auth-Token": token})
                    with urlopen(request, timeout=5) as response:
                        assert response.read() == image
                    # Shared Codex metadata overrides a stale inherited pane ID.
                    shared = dict(sender(first), codexThreadId=identifiers[0], paneId=second["pane"]["paneId"], text="Per-call thread identity", files=[])
                    assert (await asyncio.to_thread(post, shared))["bindingId"] == first["bindingId"]
                    before_invalid = files()
                    await asyncio.to_thread(post, dict(payload, agentStart=payload["agentStart"] + 1), 409)
                    await asyncio.to_thread(post, dict(shared, agentPid=second["pane"]["agentPid"], agentStart=second["pane"]["agentStart"]), 409)
                    await asyncio.to_thread(post, dict(payload, expectedSessionName="unrelated-files-B"), 409)
                    await asyncio.to_thread(post, dict(payload, expectedWindowIndex=7), 409)
                    await asyncio.to_thread(post, dict(payload, paneId="arbitrary-files-A:3"), 409)
                    await asyncio.to_thread(post, dict(payload, files=payload["files"] + [{"filename": "invalid.txt", "mimeType": "text/plain", "data": "!"}]), 400)
                    assert files() == before_invalid, "a rejected request left attachment files behind"
                    _, isolated = await watch(b, "unrelated-files-B", 7)
                    assert "An image for the current conversation" not in texts(isolated)
                    _, intact = await watch(a, "arbitrary-files-A", 3)
                    assert "An image for the current conversation" in texts(intact)
                    # Names and window indexes are aliases, not stable sender identity.
                    tmux("rename-session", "-t", "arbitrary-files-A", "renamed-output")
                    tmux("move-window", "-s", "renamed-output:3", "-t", "renamed-output:9")
                    moved = await asyncio.to_thread(post, dict(sender(first), text="After rename and window move"))
                    assert (moved["sessionName"], moved["windowIndex"]) == ("renamed-output", 9)
                    # A publication is never typed into either native agent's PTY.
                    for identifier, previous in native_before.items():
                        assert (project / ".codex/sessions" / ("rollout-" + identifier + ".jsonl")).read_bytes() == previous
                    # In-process resume changes the binding even though PID/start stay identical.
                    (project / "A.control").write_text(identifiers[2])
                    deadline = time.monotonic() + 5
                    while (project / "A.active").read_text() != identifiers[2]:
                        assert time.monotonic() < deadline
                        await asyncio.sleep(.01)
                    await asyncio.to_thread(post, dict(sender(first), codexThreadId=identifiers[0], text="Old thread must be rejected"), 409)
                    resumed, history = await watch(a, "renamed-output", 9)
                    assert resumed["conversationId"] == identifiers[2]
                    assert "An image for the current conversation" not in texts(history)
                    assert (await asyncio.to_thread(post, dict(sender(resumed), text="New conversation only")))["conversationKey"] == resumed["conversationKey"]
                    # Two same-depth foreground agents in one pane are ambiguous.
                    # POSIX shells otherwise replace background jobs' stdin
                    # with /dev/null, so their PTY agents fail tty.setraw().
                    commands = agent_command("C", identifiers[3]) + " </dev/tty & " + agent_command("D", identifiers[4]) + " </dev/tty & wait"
                    tmux("new-session", "-d", "-s", "ambiguous-output", "-c", str(project), "sh -c " + shlex.quote(commands))
                    wait_ready("C")
                    wait_ready("D")
                    pid = int((project / "C.ready").read_text())
                    stat = Path(f"/proc/{pid}/stat").read_text()
                    start_time = int(stat[stat.rfind(')') + 2:].split()[19])
                    pane = tmux("display-message", "-p", "-t", "ambiguous-output", "#{pane_id}")
                    await asyncio.to_thread(post, {"paneId": pane, "agentPid": pid, "agentStart": start_time, "text": "Ambiguous must not publish"}, 409)
                    return resumed["bindingId"]

            resumed_id = asyncio.run(verify())
            backend.terminate()
            backend.wait(timeout=5)
            backend = start()

            async def verify_restart():
                async with websockets.connect(f"ws://127.0.0.1:{port}/ws?token={token}", max_size=16 * 1024 * 1024) as connection:
                    binding, history = await watch(connection, "renamed-output", 9)
                    assert binding["bindingId"] == resumed_id
                    assert "New conversation only" in texts(history)
                    answer = await asyncio.to_thread(post, dict(sender(binding), text="After backend restart"))
                    assert answer["bindingId"] == resumed_id

            asyncio.run(verify_restart())
            print(json.dumps({"arbitrary_sessions": True, "nonzero_windows": True, "same_directory_isolation": True,
                "assistant_file_display": True, "per_call_thread_identity": True, "rejections_leave_no_files": True,
                "rename_and_move": True, "resume_isolation": True, "ambiguous_rejected": True,
                "no_agent_input": True, "backend_restart": True}))
        finally:
            if backend is not None:
                backend.terminate()
                try:
                    backend.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    backend.kill()
                    backend.wait(timeout=5)
            log.close()
            subprocess.run(["tmux", "-L", socket_name, "kill-server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            assert subprocess.check_output(inventory_args, text=True) == production, "Production TMUX panes changed"
    print("Private publication test stopped; production TMUX panes untouched.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Regression against a disposable daemon and private tmux server; no user sessions touched."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

import websocket


def run(binary):
    with tempfile.TemporaryDirectory(prefix="agentshell-deleted-session-") as directory:
        root = Path(directory)
        tmux_directory = root / "tmux"
        tmux_directory.mkdir(mode=0o700)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        token = uuid.uuid4().hex
        environment = dict(os.environ, TMUX_TMPDIR=str(tmux_directory), AUTH_TOKEN=token,
                           AGENTSHELL_HTTP_PORT=str(port), AGENTSHELL_TLS_CERT_PATH=str(root / "no-cert"),
                           RUST_LOG="error")
        environment.pop("TMUX", None)
        sockets = []
        with (root / "backend.log").open("w") as log:
            process = subprocess.Popen([str(binary)], cwd=root, env=environment, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 15
                while True:
                    try:
                        request = urllib.request.Request(f"http://127.0.0.1:{port}/api/devices",
                                                         headers={"X-Auth-Token": token})
                        with urllib.request.urlopen(request, timeout=1):
                            break
                    except OSError:
                        if process.poll() is not None or time.monotonic() > deadline:
                            raise AssertionError("Disposable daemon did not start")
                        time.sleep(.05)

                def connect():
                    connection = websocket.create_connection(f"ws://127.0.0.1:{port}/ws",
                        header=["X-Auth-Token: " + token], timeout=5)
                    sockets.append(connection)
                    return connection

                def send(connection, kind, **fields):
                    connection.send(json.dumps(dict(type=kind, **fields)))

                def receive(connection, kind):
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        packet = json.loads(connection.recv())
                        if packet.get("type") == kind:
                            return packet
                    raise AssertionError("Missing response " + kind)

                def exists(name):
                    return subprocess.run(["tmux", "has-session", "-t", "=" + name], env=environment,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0

                client = connect()
                for name in ("kept-session", "deleted-session"):
                    send(client, "create-session", name=name)
                    assert receive(client, "session-created")["success"]
                send(client, "attach-session", sessionName="deleted-session", cols=80, rows=24)
                assert receive(client, "attached")["sessionName"] == "deleted-session"
                send(client, "kill-session", sessionName="deleted-session")
                assert receive(client, "session-killed")["success"]
                snapshot = receive(client, "sessions-list")
                assert "deleted-session" not in {entry["name"] for entry in snapshot["sessions"]}
                assert not exists("deleted-session")

                for connection in (client, connect()):
                    send(connection, "attach-session", sessionName="deleted-session", cols=80, rows=24)
                    assert "no longer exists" in receive(connection, "error")["message"]
                    snapshot = receive(connection, "sessions-list")
                    assert "deleted-session" not in {entry["name"] for entry in snapshot["sessions"]}
                    assert not exists("deleted-session"), "Stale navigation resurrected deleted session"

                send(client, "attach-session", sessionName="kept", cols=80, rows=24)
                assert "no longer exists" in receive(client, "error")["message"]
                assert exists("kept-session"), "Prefix check affected an unrelated live session"
                send(client, "attach-session", sessionName="kept-session", cols=80, rows=24)
                assert receive(client, "attached")["sessionName"] == "kept-session"
                print(json.dumps({"explicitCreateAndLiveAttach": True, "deletionRefreshesCatalog": True,
                    "staleAttachCannotRecreate": True, "reconnectCannotRecreate": True,
                    "prefixMatchRejected": True, "survivingSessionStillAttaches": True}))
            finally:
                for connection in sockets:
                    connection.close()
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                subprocess.run(["tmux", "kill-server"], env=environment,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=Path(__file__).resolve().parents[1] /
                        "backend-rust/target/release/agentshell-backend")
    run(parser.parse_args().binary.resolve())

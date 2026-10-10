#!/usr/bin/env python3
"""Quiet SessionStart hook: register metadata only, never prompt or transcript text."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request


def process_info(pid):
    raw = Path(f"/proc/{pid}/stat").read_text()
    fields = raw[raw.rfind(")") + 2:].split()
    return int(fields[1]), int(fields[19]), Path(f"/proc/{pid}/comm").read_text().strip()


def owner(tool):
    pid = os.getppid()
    for _ in range(32):
        parent, start, name = process_info(pid)
        if name == tool:
            return pid, start
        if parent <= 1:
            return None
        pid = parent
    return None


def metadata(event, tool, pane, pid, start):
    if event.get("hook_event_name") != "SessionStart" or not event.get("session_id"):
        return None
    return {
        "paneId": pane, "agentPid": pid, "agentStart": start,
        "tool": tool, "sessionId": event["session_id"],
        "cwd": event.get("cwd", ""), "transcriptPath": event.get("transcript_path"),
        "observedAt": time.time_ns() // 1_000_000,
    }


def save_record(payload, directory):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if directory.is_symlink():
        raise ValueError("Binding directory cannot be a symlink")
    pane = payload["paneId"].removeprefix("%")
    if not pane.isdigit():
        raise ValueError("Invalid pane identifier")
    name = str(payload["serverPid"]) + "-" + str(payload["serverStart"]) + "-" + pane
    fd, temporary = tempfile.mkstemp(prefix=".binding-", dir=directory)
    try:
        with os.fdopen(fd, "w") as output:
            json.dump(payload, output)
        os.replace(temporary, directory / (name + ".json"))
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tool", choices=["codex", "claude"], required=True)
    args = parser.parse_args()
    pane = os.environ.get("TMUX_PANE")
    if not pane:
        return
    event = json.loads(sys.stdin.read(512 * 1024))
    identity = owner(args.tool)
    if identity is None:
        return
    payload = metadata(event, args.tool, pane, *identity)
    if payload is None:
        return
    server = int(subprocess.check_output(["tmux", "display-message", "-p", "-t", pane, "#{pid}"], text=True, timeout=1).strip())
    payload["serverPid"] = server
    payload["serverStart"] = process_info(server)[1]
    # Persist before HTTP: startup/resume still identifies the conversation while backend is offline.
    save_record(payload, Path.home() / ".local/state/agentshell/bindings")
    config = Path.home() / ".config/agentshell/chat-binding-hook.json"
    if not config.exists():
        return
    settings = json.loads(config.read_text())
    request = urllib.request.Request(settings["url"].rstrip("/") + "/api/chat/bind",
        data=json.dumps(payload).encode(), headers={"Content-Type": "application/json", "X-Auth-Token": settings["token"]}, method="POST")
    with urllib.request.urlopen(request, timeout=1) as response:
        response.read(1024)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Optional UI integration must never block startup or inject error text into agent context.
        pass

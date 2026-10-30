#!/usr/bin/env python3
"""Read-only archive/memory integration check in an isolated backend instance.

Uses existing Codex transcripts without creating sessions or sending prompts.
The backend's databases, snapshots, attachments, logs, and auth token are isolated.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time

import websockets

ROOT = Path(__file__).resolve().parents[1]


def port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def memory(pid: int) -> dict[str, int]:
    try:
        fields = dict(line.split(":", 1) for line in Path(f"/proc/{pid}/status").read_text().splitlines() if ":" in line)
        return {name: int(fields.get(name, "0 kB").split()[0]) for name in ("VmRSS", "VmHWM", "VmSwap", "Threads")}
    except (FileNotFoundError, ProcessLookupError):
        return {}


async def request(ws, command: dict, expected: str) -> dict:
    request_id = secrets.token_hex(8)
    await ws.send(json.dumps({**command, "requestId": request_id}))
    while True:
        message = json.loads(await asyncio.wait_for(ws.recv(), timeout=180))
        if message.get("type") == expected and message.get("requestId") == request_id:
            return message
        if message.get("type") == "error":
            raise AssertionError(message)


async def exercise(url: str, token: str, rows: list[tuple], duration: float) -> dict:
    revisions: dict[str, str] = {}
    pages = messages = unchanged = changed = bodies = 0
    cached_keys: dict[str, set[str]] = {}
    started = time.monotonic()
    async with websockets.connect(url, additional_headers={"X-Auth-Token": token}, max_size=4 * 1024 * 1024) as ws:
        await ws.send(json.dumps({"type": "get-history-sync-capabilities"}))
        while True:
            capability = json.loads(await ws.recv())
            if capability.get("type") == "history-sync-capabilities":
                assert capability["version"] == 1
                break
        cycles = 0
        while True:
            for number, (session_id, source_size) in enumerate(rows):
                session = f"acp_codex:{session_id}"
                state = await request(ws, {"type": "get-history-state", "sessionName": session, "windowIndex": 0}, "history-state")
                assert state.get("available"), state
                revision = state["revision"]
                if revisions.get(session) == revision:
                    unchanged += 1
                    continue
                offset = 0
                next_keys: set[str] = set()
                base_revision = revisions.get(session)
                while True:
                    response = await request(ws, {"type": "get-history-page", "sessionName": session, "windowIndex": 0,
                                                  "revision": revision, "offset": offset, "limit": 30 if offset == 0 else 200, "baseRevision": base_revision}, "history-page")
                    if response.get("error"):
                        # A source changing before its first snapshot will be retried next cycle.
                        assert "changed" in response["error"].lower(), response
                        break
                    assert response["revision"] == revision
                    entries = response["entries"]
                    count = len(entries)
                    for entry in entries:
                        assert entry["message"] is not None or entry["key"] in cached_keys.get(session, set()), entry["key"]
                        bodies += entry["message"] is not None
                        next_keys.add(entry["key"])
                    assert count > 0 or not response["hasMore"]
                    offset += count
                    messages += count
                    pages += 1
                    if not response["hasMore"]:
                        assert offset == response["totalCount"], (offset, response["totalCount"])
                        revisions[session] = revision
                        cached_keys[session] = next_keys
                        changed += 1
                        break
                if number % 25 == 0:
                    print(json.dumps({"cycle": cycles, "conversation": number + 1, "of": len(rows), "pages": pages,
                                      "source_mib": round(source_size / 1024 ** 2, 1), "elapsed_seconds": round(time.monotonic() - started, 1)}), flush=True)
            cycles += 1
            print(json.dumps({"completed_cycles": cycles, "synced_conversations": len(revisions), "unchanged_checks": unchanged,
                              "pages": pages, "elapsed_seconds": round(time.monotonic() - started, 1)}), flush=True)
            if cycles >= 2 and time.monotonic() - started >= duration:
                return {"cycles": cycles, "conversations": len(revisions), "pages": pages, "messages": messages,
                        "unchanged_checks": unchanged, "changed_syncs": changed, "changed_message_bodies": bodies, "elapsed_seconds": time.monotonic() - started}
            await asyncio.sleep(min(30, max(0, duration - (time.monotonic() - started))))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=ROOT / "backend-rust/target/release/agentshell-backend")
    parser.add_argument("--largest", type=int, default=5, help="Number of largest transcripts; 0 selects all")
    parser.add_argument("--soak-seconds", type=float, default=0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    connection = sqlite3.connect(f"file:{Path.home() / '.codex/state_5.sqlite'}?mode=ro", uri=True)
    rows = []
    for session, path in connection.execute("SELECT id,rollout_path FROM threads WHERE archived=0"):
        try:
            rows.append((session, Path(path).stat().st_size))
        except OSError:
            continue
    connection.close()
    rows.sort(key=lambda row: row[1], reverse=True)
    if args.largest:
        rows = rows[:args.largest]
    assert rows, "No existing Codex transcripts available"
    token = secrets.token_urlsafe(32)
    with tempfile.TemporaryDirectory(prefix="agentshell-history-validation-") as temporary:
        directory = Path(temporary)
        http_port = port()
        environment = {**os.environ, "AUTH_TOKEN": token, "AGENTSHELL_HTTP_PORT": str(http_port),
                       "AGENTSHELL_HTTPS_PORT": str(port()), "AGENTSHELL_HISTORY_CACHE": str(directory / "transcripts.db"),
                       "RUST_LOG": "agentshell_backend=info"}
        with (directory / "backend.log").open("w") as log:
            process = subprocess.Popen([str(args.binary.resolve())], cwd=directory, env=environment, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            stopped = threading.Event()
            peak = {"rss_kib": 0, "rss_plus_swap_kib": 0}

            def sample():
                while not stopped.wait(0.1):
                    value = memory(process.pid)
                    peak["rss_kib"] = max(peak["rss_kib"], value.get("VmRSS", 0), value.get("VmHWM", 0))
                    peak["rss_plus_swap_kib"] = max(peak["rss_plus_swap_kib"], value.get("VmRSS", 0) + value.get("VmSwap", 0))

            worker = threading.Thread(target=sample, daemon=True)
            worker.start()
            try:
                for _ in range(120):
                    assert process.poll() is None, (directory / "backend.log").read_text()[-4000:]
                    try:
                        with socket.create_connection(("127.0.0.1", http_port), timeout=0.1):
                            break
                    except OSError:
                        time.sleep(0.1)
                report = asyncio.run(exercise(f"ws://127.0.0.1:{http_port}/ws", token, rows, args.soak_seconds))
                final_memory = memory(process.pid)
                peak["rss_kib"] = max(peak["rss_kib"], final_memory.get("VmHWM", 0))
                peak["rss_plus_swap_kib"] = max(peak["rss_plus_swap_kib"], final_memory.get("VmHWM", 0))
                report.update(peak)
                report["final_memory_kib"] = final_memory
                report["source_gib"] = sum(size for _, size in rows) / 1024 ** 3
                report["backend_only"] = True
                assert peak["rss_plus_swap_kib"] < 512 * 1024, report
                assert report["unchanged_checks"] > 0, report
                print(json.dumps(report, indent=2), flush=True)
                if args.output:
                    args.output.parent.mkdir(parents=True, exist_ok=True)
                    args.output.write_text(json.dumps(report, indent=2) + "\n")
            except BaseException:
                print((directory / "backend.log").read_text()[-6000:])
                raise
            finally:
                stopped.set()
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                worker.join(timeout=1)


if __name__ == "__main__":
    main()

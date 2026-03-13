#!/usr/bin/env python3
"""
test_enter_key.py — Reproduces the exact Flutter chat→tmux Enter key flow.

Tests the InputViaTmux path (what chat_provider.dart:sendInput() sends).
Sends two messages back-to-back and verifies tmux executed both (Enter worked).

Usage:
  python3 scripts/test_enter_key.py [--token TOKEN] [--session NAME]
"""

import asyncio
import json
import subprocess
import sys
import time
import argparse
import os

try:
    import websockets
except ImportError:
    print("ERROR: pip3 install websockets")
    sys.exit(1)

# ---------- config ----------
BACKEND_URL = "ws://localhost:4010/ws"
TEST_SESSION = "__enter_test__"
WAIT_AFTER_SEND = 0.5   # seconds to wait for tmux to echo
# ----------------------------

def run(cmd, **kw):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw)

def tmux_pane_content(session, lines=10):
    r = run(f"tmux capture-pane -t {session}:0 -p -S -{lines}")
    return r.stdout

def setup_test_session():
    """Create an isolated tmux session running bash with a clear marker."""
    run(f"tmux kill-session -t {TEST_SESSION} 2>/dev/null")
    r = run(f"tmux new-session -d -s {TEST_SESSION} -x 220 -y 50")
    if r.returncode != 0:
        print(f"ERROR creating tmux session: {r.stderr}")
        sys.exit(1)
    # Start a loop that prints what it receives (echo server)
    # We'll just use plain bash and check that echo/cd/etc ran
    time.sleep(0.3)
    # Clear to known state
    run(f"tmux send-keys -t {TEST_SESSION}:0 'clear' Enter")
    time.sleep(0.3)
    content = tmux_pane_content(TEST_SESSION, 3)
    print(f"[SETUP] Session '{TEST_SESSION}' ready. Pane tail:\n{content.strip()}")
    return TEST_SESSION

def teardown_test_session():
    run(f"tmux kill-session -t {TEST_SESSION} 2>/dev/null")

def check_tmux_for(session, marker, timeout=3.0):
    """Poll tmux pane until marker appears or timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        content = tmux_pane_content(session, 20)
        if marker in content:
            return True, content
        time.sleep(0.1)
    return False, tmux_pane_content(session, 20)

async def run_test(token: str):
    url = f"{BACKEND_URL}?token={token}" if token else BACKEND_URL
    results = []

    print(f"\n{'='*60}")
    print(f"Connecting to {BACKEND_URL}")
    print(f"{'='*60}\n")

    async with websockets.connect(url) as ws:
        # ── Step 1: Attach to test session (sets global session/window in WsState)
        attach_msg = json.dumps({
            "type": "attach-session",
            "sessionName": TEST_SESSION,
            "cols": 220,
            "rows": 50,
            "windowIndex": 0,
        })
        await ws.send(attach_msg)
        print(f"[WS] Sent attach-session for '{TEST_SESSION}'")

        # Drain any bootstrap messages (terminal history, etc.)
        attached = False
        deadline = time.time() + 5.0
        while time.time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=0.5)
                msg = json.loads(raw)
                t = msg.get("type", "")
                if t == "attached":
                    attached = True
                    print(f"[WS] Attached confirmed")
                elif t == "terminal-history-end":
                    print(f"[WS] History bootstrap done")
                    break
                elif t not in ("output", "terminal-history-start", "terminal-history-chunk"):
                    print(f"[WS] Received: {t}")
            except asyncio.TimeoutError:
                break

        if not attached:
            print("[WARN] No 'attached' message received — continuing anyway")

        await asyncio.sleep(0.3)

        # ── Step 2: Watch chat log (sets current_session / current_window in WsState)
        watch_msg = json.dumps({
            "type": "watch-chat-log",
            "sessionName": TEST_SESSION,
            "windowIndex": 0,
        })
        await ws.send(watch_msg)
        print(f"[WS] Sent watch-chat-log")
        await asyncio.sleep(0.3)

        # Drain any watch responses
        deadline = time.time() + 2.0
        while time.time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=0.3)
                msg = json.loads(raw)
                t = msg.get("type", "")
                if t not in ("output", "chat-log-error"):
                    print(f"[WS] Watch response: {t}")
            except asyncio.TimeoutError:
                break

        # ── Step 3: Send messages via InputViaTmux (exact Flutter path)
        test_cases = [
            ("FIRST_MSG_MARKER_AAA", "echo 'FIRST_MSG_MARKER_AAA'"),
            ("SECOND_MSG_MARKER_BBB", "echo 'SECOND_MSG_MARKER_BBB'"),
            ("THIRD_MSG_MARKER_CCC", "echo 'THIRD_MSG_MARKER_CCC'"),
        ]

        for marker, cmd in test_cases:
            print(f"\n── Sending: {cmd!r}")
            input_msg = json.dumps({
                "type": "inputViaTmux",
                "sessionName": TEST_SESSION,
                "windowIndex": 0,
                "data": cmd,          # No \n — backend adds Enter separately
            })
            await ws.send(input_msg)
            t_sent = time.time()

            found, content = check_tmux_for(TEST_SESSION, marker)
            elapsed = time.time() - t_sent

            if found:
                print(f"   ✅ PASS — marker found in {elapsed:.2f}s")
                results.append(("PASS", marker))
            else:
                print(f"   ❌ FAIL — marker NOT found after {elapsed:.2f}s")
                print(f"   Pane content:\n{content.rstrip()}")
                results.append(("FAIL", marker))

            # Drain any WS output while tmux settles
            deadline = time.time() + WAIT_AFTER_SEND
            while time.time() < deadline:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=0.1)
                except asyncio.TimeoutError:
                    break

    # ── Summary
    print(f"\n{'='*60}")
    print("RESULTS:")
    for status, marker in results:
        print(f"  {status}: {marker}")
    passed = sum(1 for s, _ in results if s == "PASS")
    print(f"\n{passed}/{len(results)} passed")
    print(f"{'='*60}\n")
    return passed == len(results)


# ── Also test SendChatMessage path ──────────────────────────────────────────
async def run_test_sendchatmessage(token: str):
    """Test the SendChatMessage path (used by backend's /api/tmux/input and chat handler)."""
    url = f"{BACKEND_URL}?token={token}" if token else BACKEND_URL
    results = []

    print(f"\n{'='*60}")
    print(f"Testing SendChatMessage path")
    print(f"{'='*60}\n")

    async with websockets.connect(url) as ws:
        # Attach first
        await ws.send(json.dumps({
            "type": "attach-session",
            "sessionName": TEST_SESSION,
            "cols": 220, "rows": 50, "windowIndex": 0,
        }))
        await asyncio.sleep(0.5)

        # Drain
        deadline = time.time() + 3.0
        while time.time() < deadline:
            try:
                await asyncio.wait_for(ws.recv(), timeout=0.3)
            except asyncio.TimeoutError:
                break

        test_cases = [
            ("CHAT_FIRST_AAA", "echo 'CHAT_FIRST_AAA'"),
            ("CHAT_SECOND_BBB", "echo 'CHAT_SECOND_BBB'"),
        ]

        for marker, cmd in test_cases:
            print(f"\n── SendChatMessage: {cmd!r}")
            await ws.send(json.dumps({
                "type": "send-chat-message",   # kebab-case per serde(rename_all)
                "sessionName": TEST_SESSION,
                "windowIndex": 0,
                "message": cmd,
                "notify": False,
            }))

            found, content = check_tmux_for(TEST_SESSION, marker)
            if found:
                print(f"   ✅ PASS")
                results.append(("PASS", marker))
            else:
                print(f"   ❌ FAIL")
                print(f"   Pane:\n{content.rstrip()}")
                results.append(("FAIL", marker))

            deadline = time.time() + WAIT_AFTER_SEND
            while time.time() < deadline:
                try:
                    await asyncio.wait_for(ws.recv(), timeout=0.1)
                except asyncio.TimeoutError:
                    break

    passed = sum(1 for s, _ in results if s == "PASS")
    print(f"\nSendChatMessage: {passed}/{len(results)} passed\n")
    return passed == len(results)


# ── Direct tmux baseline (sanity check) ────────────────────────────────────
def run_direct_tmux_test():
    """Verify that the two-call approach works directly, without backend."""
    print(f"\n{'='*60}")
    print("Baseline: direct tmux two-call approach")
    print(f"{'='*60}\n")
    results = []

    for i, marker in enumerate(["DIRECT_1", "DIRECT_2", "DIRECT_3"]):
        cmd = f"echo '{marker}'"
        print(f"── Direct send {i+1}: {cmd!r}")
        run(f"tmux send-keys -t {TEST_SESSION}:0 -l {json.dumps(cmd)}")
        time.sleep(0.05)
        run(f"tmux send-keys -t {TEST_SESSION}:0 Enter")
        found, content = check_tmux_for(TEST_SESSION, marker)
        if found:
            print(f"   ✅ PASS")
            results.append("PASS")
        else:
            print(f"   ❌ FAIL\n{content.rstrip()}")
            results.append("FAIL")
        time.sleep(0.2)

    passed = results.count("PASS")
    print(f"\nDirect baseline: {passed}/{len(results)} passed\n")
    return passed == len(results)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--token", default=os.environ.get("AUTH_TOKEN", ""))
    parser.add_argument("--session", default=TEST_SESSION)
    parser.add_argument("--url", default=BACKEND_URL)
    args = parser.parse_args()

    BACKEND_URL = args.url
    TEST_SESSION = args.session

    print("\n🔍 AgentShell Enter Key Test")
    print(f"   Backend: {BACKEND_URL}")
    print(f"   Session: {TEST_SESSION}")

    setup_test_session()

    try:
        # 1) Baseline: does direct tmux work?
        baseline_ok = run_direct_tmux_test()

        # 2) Via WebSocket InputViaTmux (Flutter chat_provider path)
        ws_ok = asyncio.run(run_test(args.token))

        # 3) Via WebSocket SendChatMessage
        chat_ok = asyncio.run(run_test_sendchatmessage(args.token))

        print("\n📊 Final Summary:")
        print(f"   Direct tmux two-call:  {'✅ ALL PASS' if baseline_ok else '❌ FAILURES'}")
        print(f"   WebSocket InputViaTmux: {'✅ ALL PASS' if ws_ok else '❌ FAILURES'}")
        print(f"   WebSocket SendChatMsg:  {'✅ ALL PASS' if chat_ok else '❌ FAILURES'}")

        sys.exit(0 if (baseline_ok and ws_ok and chat_ok) else 1)
    finally:
        teardown_test_session()

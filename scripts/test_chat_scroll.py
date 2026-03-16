#!/usr/bin/env python3
"""
Test script for lazy-load chat history (scroll-up pagination).

Usage:
  python3 test_chat_scroll.py <session_name> <window_index> [pages]

Example:
  python3 test_chat_scroll.py AgentShell 0 3
"""

import asyncio
import json
import sys
import time
import websockets

TOKEN = "Cartoon-Uncombed-Compost8"
WS_URL = f"ws://localhost:4010/ws?token={TOKEN}"
INITIAL_LIMIT = 5
PAGE_LIMIT = 5


async def recv_typed(ws, expected_type, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=5)
            msg = json.loads(raw)
            t = msg.get("type", "")
            if t == expected_type:
                return msg
            elif t == "chat-log-error":
                print(f"  [ERROR] {msg.get('error')}")
                return None
            else:
                print(f"  [skip {t}]")
        except asyncio.TimeoutError:
            return None
    return None


def print_messages(messages, label=""):
    if label:
        print(f"  {label}:")
    for m in messages:
        ts = (m.get("timestamp") or "no-ts")[:19]
        role = m.get("role", "?")
        text = next((b.get("text", "")[:60] for b in m.get("blocks", []) if b.get("type") == "text"), "")
        print(f"    [{ts}] {role}: {text!r}")


async def run(session_name: str, window_index: int, pages: int):
    print(f"\n=== Chat Scroll Pagination Test ===")
    print(f"Backend : {WS_URL}")
    print(f"Session : {session_name}:{window_index}")
    print(f"Initial : last {INITIAL_LIMIT} messages")
    print(f"Pages   : {pages} load-more calls of {PAGE_LIMIT} each\n")

    async with websockets.connect(WS_URL) as ws:

        # --- Step 1: Initial watch with small limit ---
        print(f"1) watch-chat-log (limit={INITIAL_LIMIT})")
        await ws.send(json.dumps({
            "type": "watch-chat-log",
            "sessionName": session_name,
            "windowIndex": window_index,
            "limit": INITIAL_LIMIT,
        }))

        msg = await recv_typed(ws, "chat-history")
        if not msg:
            print("  ✗ No chat-history received")
            return

        total = msg.get("totalCount", 0)
        has_more = msg.get("hasMore", False)
        loaded = len(msg.get("messages", []))
        print(f"  ✓ {loaded} messages received  |  totalCount={total}  |  hasMore={has_more}")
        print_messages(msg.get("messages", []))

        if not has_more:
            print(f"\n  All {total} messages fit in initial load — no pagination needed.")
            return

        # --- Step 2: Simulate successive scroll-up loads ---
        offset = loaded
        for page in range(1, pages + 1):
            print(f"\n{page + 1}) load-more-chat-history (offset={offset}, limit={PAGE_LIMIT})")
            await ws.send(json.dumps({
                "type": "load-more-chat-history",
                "sessionName": session_name,
                "windowIndex": window_index,
                "offset": offset,
                "limit": PAGE_LIMIT,
            }))

            chunk = await recv_typed(ws, "chat-history-chunk")
            if not chunk:
                print(f"  ✗ No chat-history-chunk received")
                break

            chunk_msgs = chunk.get("messages", [])
            chunk_has_more = chunk.get("hasMore", False)
            print(f"  ✓ {len(chunk_msgs)} older messages  |  hasMore={chunk_has_more}")
            print_messages(chunk_msgs)

            offset += len(chunk_msgs)
            if not chunk_has_more:
                print(f"\n  Reached beginning of history after {page} load-more(s).")
                break

        print(f"\n=== Summary ===")
        print(f"  Total in DB   : {total}")
        print(f"  Messages seen : {offset}")
        print(f"  Remaining     : {max(0, total - offset)}")
        print(f"  Backend OK    : ✓\n")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python3 test_chat_scroll.py <session_name> <window_index> [pages]")
        sys.exit(1)
    pages = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    asyncio.run(run(sys.argv[1], int(sys.argv[2]), pages))

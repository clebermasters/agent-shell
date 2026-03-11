#!/usr/bin/env python3
"""
Comprehensive test script simulating Flutter app behavior.
Tests: Initial load, refresh, ACP chat loading.
"""

import asyncio
import websockets
import json
import time


class TestRunner:
    def __init__(self):
        self.uri = "ws://localhost:4010/ws"
        self.results = []

    def log(self, msg):
        print(f"[{time.strftime('%H:%M:%S')}] {msg}")
        self.results.append(msg)

    async def test_session_list_initial(self):
        """Test: Initial session list load"""
        self.log("=== TEST 1: Initial Session List Load ===")
        async with websockets.connect(self.uri) as ws:
            await ws.send(json.dumps({"type": "list-sessions"}))
            resp = json.loads(await ws.recv())
            tmux_count = len(resp.get("sessions", []))
            self.log(f"  Tmux sessions: {tmux_count}")

            await ws.send(json.dumps({"type": "acp-list-sessions"}))
            resp = json.loads(await ws.recv())
            acp_count = len(resp.get("sessions", []))
            self.log(f"  ACP sessions: {acp_count}")

            if acp_count > 0:
                self.session_id = resp["sessions"][0]["sessionId"]
                self.cwd = resp["sessions"][0].get("cwd", "/home/cleber_rodrigues")
                self.log(f"  First ACP session: {self.session_id}")

        return tmux_count > 0 or acp_count > 0

    async def test_session_list_refresh(self):
        """Test: Refresh session list (second load)"""
        self.log("\n=== TEST 2: Session List Refresh (Second Load) ===")
        async with websockets.connect(self.uri) as ws:
            # Same flow as initial
            await ws.send(json.dumps({"type": "list-sessions"}))
            resp = json.loads(await ws.recv())
            tmux_count = len(resp.get("sessions", []))
            self.log(f"  Tmux sessions: {tmux_count}")

            await ws.send(json.dumps({"type": "acp-list-sessions"}))
            resp = json.loads(await ws.recv())
            acp_count = len(resp.get("sessions", []))
            self.log(f"  ACP sessions: {acp_count}")

        return tmux_count > 0 or acp_count > 0

    async def test_acp_chat_load(self):
        """Test: ACP chat loading"""
        if not hasattr(self, "session_id"):
            self.log("  SKIP: No ACP session available")
            return False

        self.log("\n=== TEST 3: ACP Chat Load ===")
        async with websockets.connect(self.uri) as ws:
            # Select backend
            await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
            resp = await ws.recv()
            self.log(f"  select-backend: {json.loads(resp).get('type')}")

            # Resume session
            await ws.send(
                json.dumps(
                    {
                        "type": "acp-resume-session",
                        "sessionId": self.session_id,
                        "cwd": self.cwd,
                    }
                )
            )
            resp = await ws.recv()
            self.log(f"  acp-resume-session: {json.loads(resp).get('type')}")

            # Watch chat log
            self.log("  Sending watch-acp-chat-log...")
            await ws.send(
                json.dumps(
                    {
                        "type": "watch-acp-chat-log",
                        "sessionId": self.session_id,
                        "limit": 500,
                    }
                )
            )

            # Wait for response with timeout
            start = time.time()
            got_history = False
            while time.time() - start < 10:
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=2)
                    resp = json.loads(msg)
                    msg_type = resp.get("type")

                    if msg_type == "chat-history":
                        msg_count = len(resp.get("messages", []))
                        self.log(f"  chat-history received: {msg_count} messages")
                        got_history = True
                        break
                    elif msg_type == "chat-log-error":
                        self.log(f"  ERROR: {resp.get('error')}")
                        break
                except asyncio.TimeoutError:
                    self.log("  Waiting for response...")

            if not got_history:
                self.log("  TIMEOUT - No chat history received!")
                return False

        return got_history

    async def test_full_refresh_cycle(self):
        """Test: Simulate full refresh cycle (what happens on pull-to-refresh)"""
        self.log("\n=== TEST 4: Full Refresh Cycle ===")

        # First connection - initial load
        self.log("  Connection 1: Initial load...")
        async with websockets.connect(self.uri) as ws:
            await ws.send(json.dumps({"type": "acp-list-sessions"}))
            resp = json.loads(await ws.recv())
            self.log(f"    ACP sessions: {len(resp.get('sessions', []))}")

        # Short delay (like refresh)
        await asyncio.sleep(0.5)

        # Second connection - refresh
        self.log("  Connection 2: Refresh...")
        async with websockets.connect(self.uri) as ws:
            await ws.send(json.dumps({"type": "acp-list-sessions"}))
            resp = json.loads(await ws.recv())
            self.log(f"    ACP sessions: {len(resp.get('sessions', []))}")

        # Third connection - chat load
        self.log("  Connection 3: Chat load after refresh...")
        async with websockets.connect(self.uri) as ws:
            await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
            await ws.recv()

            await ws.send(json.dumps({"type": "acp-list-sessions"}))
            resp = json.loads(await ws.recv())
            if resp.get("sessions"):
                session_id = resp["sessions"][0]["sessionId"]
                cwd = resp["sessions"][0].get("cwd", "/home/cleber_rodrigues")

                await ws.send(
                    json.dumps(
                        {
                            "type": "acp-resume-session",
                            "sessionId": session_id,
                            "cwd": cwd,
                        }
                    )
                )
                await ws.recv()

                await ws.send(
                    json.dumps(
                        {
                            "type": "watch-acp-chat-log",
                            "sessionId": session_id,
                            "limit": 500,
                        }
                    )
                )

                start = time.time()
                while time.time() - start < 10:
                    try:
                        msg = await asyncio.wait_for(ws.recv(), timeout=2)
                        resp = json.loads(msg)
                        if resp.get("type") == "chat-history":
                            self.log(
                                f"    chat-history: {len(resp.get('messages', []))} messages"
                            )
                            return True
                    except asyncio.TimeoutError:
                        pass

        return False

    async def run_all_tests(self):
        """Run all tests"""
        self.log("Starting comprehensive Flutter simulation tests\n")

        # Test 1: Initial session list
        result1 = await self.test_session_list_initial()

        # Test 2: Refresh (second load)
        result2 = await self.test_session_list_refresh()

        # Test 3: ACP chat load
        result3 = await self.test_acp_chat_load()

        # Test 4: Full refresh cycle
        result4 = await self.test_full_refresh_cycle()

        # Summary
        self.log("\n=== SUMMARY ===")
        self.log(f"  Initial session list: {'PASS' if result1 else 'FAIL'}")
        self.log(f"  Refresh session list: {'PASS' if result2 else 'FAIL'}")
        self.log(f"  ACP chat load: {'PASS' if result3 else 'FAIL'}")
        self.log(f"  Full refresh cycle: {'PASS' if result4 else 'FAIL'}")

        return all([result1, result2, result3, result4])


async def main():
    runner = TestRunner()
    success = await runner.run_all_tests()

    if not success:
        print("\n!!! TESTS FAILED - See details above !!!")
        exit(1)
    else:
        print("\n=== ALL TESTS PASSED ===")


if __name__ == "__main__":
    asyncio.run(main())

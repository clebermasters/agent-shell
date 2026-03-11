#!/usr/bin/env python3
"""
Troubleshooting script to test ACP chat functionality.
Tests the backend WebSocket directly to verify messages are being sent.
"""

import asyncio
import websockets
import json


async def test_acp_chat():
    uri = "ws://localhost:4010/ws"

    print("=== ACP Chat Troubleshooting Test ===\n")

    async with websockets.connect(uri) as ws:
        # Step 1: Select ACP backend
        print("1. Selecting ACP backend...")
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        resp = json.loads(await ws.recv())
        print(f"   Response: {resp.get('type')}")

        # Step 2: List sessions
        print("\n2. Listing ACP sessions...")
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        resp = json.loads(await ws.recv())

        if "sessions" not in resp or len(resp["sessions"]) == 0:
            print("   ERROR: No ACP sessions found!")
            return

        for session in resp["sessions"]:
            print(f"   - {session['sessionId']}: {session.get('displayName', 'N/A')}")

        # Step 3: Get session ID (use first one)
        session_id = resp["sessions"][0]["sessionId"]
        print(f"\n3. Using session: {session_id}")

        # Step 4: Test watch-acp-chat-log (the fix we need)
        print("\n4. Testing watch-acp-chat-log (the new endpoint)...")
        await ws.send(
            json.dumps({"type": "watch-acp-chat-log", "sessionId": session_id})
        )

        # Collect responses
        history_received = False
        events_received = 0

        try:
            while True:
                msg = await asyncio.wait_for(ws.recv(), timeout=5)
                resp = json.loads(msg)
                msg_type = resp.get("type")

                if msg_type == "chat-history":
                    history_received = True
                    msgs = resp.get("messages", [])
                    print(f"   ✓ chat-history received: {len(msgs)} messages")
                    # Show recent messages
                    if msgs:
                        print(
                            f"   Latest: {msgs[-1].get('role', '?')}: {str(msgs[-1].get('blocks', [{}])[0].get('text', ''))[:50]}..."
                        )

                elif msg_type == "chat-event":
                    events_received += 1
                    if events_received <= 3:
                        role = resp.get("message", {}).get("role", "?")
                        print(f"   ✓ chat-event #{events_received}: {role}")

                elif msg_type == "chat-log-error":
                    print(f"   ✗ Error: {resp.get('error')}")
                    break

        except asyncio.TimeoutError:
            print(f"\n   Timeout after receiving:")
            print(f"   - History: {'✓' if history_received else '✗'}")
            print(f"   - Real-time events: {events_received}")

        print("\n=== Test Complete ===")
        if history_received:
            print("✓ Backend is working correctly!")
            print(
                "The issue is in the Flutter app - watchAcpChatLog is not being called."
            )
        else:
            print("✗ Backend has issues - check server logs.")


if __name__ == "__main__":
    asyncio.run(test_acp_chat())

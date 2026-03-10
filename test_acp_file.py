#!/usr/bin/env python3
"""
Test script to send image to ACP chat.
This simulates what an OpenCode skill would do to send a file to ACP chat.
"""

import asyncio
import json
import websockets
import base64
import sys


async def main():
    if len(sys.argv) < 2:
        print("Usage: python3 test_acp_file.py <session_id>")
        print("\nAvailable ACP sessions:")
        async with websockets.connect("ws://localhost:5173/ws") as ws:
            await ws.send(json.dumps({"type": "acp-list-sessions"}))
            msg = await ws.recv()
            data = json.loads(msg)
            sessions = data.get("sessions", [])
            sorted_sessions = sorted(
                sessions, key=lambda x: x.get("updatedAt", ""), reverse=True
            )
            for s in sorted_sessions[:5]:
                print(f"  {s['sessionId']} - {s['title'][:40]} ({s['cwd']})")
        return

    session_id = sys.argv[1]
    image_path = "/home/cleber_rodrigues/Pictures/2024-03-11_09-17.png"

    # Read and encode image
    with open(image_path, "rb") as f:
        image_data = base64.b64encode(f.read()).decode("utf-8")

    # Get session info to find CWD
    async with websockets.connect("ws://localhost:5173/ws") as ws:
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        msg = await ws.recv()
        data = json.loads(msg)
        sessions = data.get("sessions", [])
        cwd = None
        title = None
        for s in sessions:
            if s["sessionId"] == session_id:
                cwd = s["cwd"]
                title = s["title"]
                break

        if not cwd:
            print(f"Session {session_id} not found!")
            return

        print(f"Sending to ACP session:")
        print(f"  Session ID: {session_id}")
        print(f"  Title: {title}")
        print(f"  CWD: {cwd}")
        print()

        # Build the message - this is what skills should send
        message = {
            "type": "send-file-to-acp-chat",
            "sessionId": session_id,
            "file": {
                "filename": "test-from-script.png",
                "mimeType": "image/png",
                "data": image_data,
            },
            "prompt": "This is a test image sent via script to ACP chat!",
            "cwd": cwd,
        }

        print(
            f"Sending message: {json.dumps({**message, 'file': {'filename': 'test-from-script.png', 'mimeType': 'image/png', 'data': '[BASE64...]'}}, indent=2)}"
        )
        print()

        await ws.send(json.dumps(message))
        print("Message sent!")
        print()
        print("Now open this ACP session in Flutter to see the image.")


if __name__ == "__main__":
    asyncio.run(main())

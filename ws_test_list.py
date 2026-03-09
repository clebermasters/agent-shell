import asyncio
import websockets
import json

async def test():
    async with websockets.connect('ws://127.0.0.1:4010/ws') as ws:
        print("Connected")
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        await ws.recv()
        
        await ws.send(json.dumps({"type": "acp-create-session", "cwd": "/home/cleber_rodrigues/project/webmux/test-new-123"}))
        
        msg = await ws.recv()
        d = json.loads(msg)
        print("Created:", d["type"])
        
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        
        while True:
            msg = await ws.recv()
            d = json.loads(msg)
            if d.get("type") == "acp-sessions-listed":
                sessions = d.get("sessions", [])
                print(f"Found {len(sessions)} sessions")
                for s in sessions:
                    print(f" - {s.get('sessionId')}: {s.get('cwd')}")
                break
        
asyncio.run(test())

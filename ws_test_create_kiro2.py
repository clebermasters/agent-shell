import asyncio
import websockets
import json

async def test():
    async with websockets.connect('ws://127.0.0.1:4010/ws') as ws:
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        await ws.recv()
        
        await ws.send(json.dumps({"type": "acp-create-session", "cwd": "/home/cleber_rodrigues/kiro-bot/test2"}))
        
        msg = await ws.recv()
        d = json.loads(msg)
        print("Created:", d.get("sessionId"))
        
        await asyncio.sleep(2)
        
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        
        while True:
            msg = await ws.recv()
            d = json.loads(msg)
            if d.get("type") == "acp-sessions-listed":
                sessions = d.get("sessions", [])
                for s in sessions:
                    if "test2" in s.get("cwd", ""):
                        print("Found in list:", s)
                break
        
asyncio.run(test())

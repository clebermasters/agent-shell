import asyncio
import websockets
import json

async def test():
    async with websockets.connect('ws://127.0.0.1:4010/ws') as ws:
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        await ws.recv()
        
        # Test my modified rust endpoint
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        
        while True:
            msg = await ws.recv()
            d = json.loads(msg)
            if d.get("type") == "acp-sessions-listed":
                sessions = d.get("sessions", [])
                print(f"Total sessions: {len(sessions)}")
                for s in sessions:
                    if "kiro-bot" in s.get("cwd", ""):
                        print("Found kiro-bot:", s)
                break
        
asyncio.run(test())

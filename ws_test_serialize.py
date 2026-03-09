import asyncio
import websockets
import json

async def test():
    async with websockets.connect('ws://127.0.0.1:4010/ws') as ws:
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        await ws.recv()
        await ws.send(json.dumps({"type": "acp-create-session", "cwd": "/tmp/test-acp"}))
        msg = await ws.recv()
        print("Received bytes:", len(msg))
        
asyncio.run(test())

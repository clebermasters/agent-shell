import asyncio
import websockets
import json

async def test():
    async with websockets.connect('ws://127.0.0.1:4010/ws') as ws:
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        await ws.recv()
        
        await ws.send(json.dumps({"type": "acp-list-sessions"})) # We will modify rust to pass cwd! wait, rust doesn't pass cwd right now.
        
asyncio.run(test())

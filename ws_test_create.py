import asyncio
import websockets
import json

async def test():
    async with websockets.connect('ws://127.0.0.1:4010/ws') as ws:
        print("Connected")
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        await ws.recv()
        
        await ws.send(json.dumps({"type": "acp-create-session", "cwd": "/home/cleber_rodrigues/project/webmux/foo-bar"}))
        
        print(await ws.recv())
        
asyncio.run(test())

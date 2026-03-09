import asyncio
import websockets
import json

async def test():
    async with websockets.connect('ws://127.0.0.1:4010/ws') as ws:
        await ws.send(json.dumps({"type": "select-backend", "backend": "acp"}))
        await ws.recv()
        
        await ws.send(json.dumps({"type": "acp-create-session", "cwd": "/home/cleber_rodrigues/kiro-bot/test3"}))
        msg = await ws.recv()
        d = json.loads(msg)
        session_id = d.get("sessionId")
        print("Created:", session_id)
        
        # Send a prompt to the session
        print("Sending prompt...")
        await ws.send(json.dumps({"type": "acp-send-prompt", "sessionId": session_id, "message": "hello"}))
        
        # Wait for the response to finish
        while True:
            msg = await ws.recv()
            d = json.loads(msg)
            if d.get("type") == "acp-tool-result" or d.get("type") == "acp-message-chunk":
                pass
            if d.get("type") == "acp-error":
                print("Error:", d)
                break
            
            # If the session gets an update, let's wait a bit and break out
            # Actually just wait 3 seconds and check list
            break
            
        await asyncio.sleep(5)
        
        await ws.send(json.dumps({"type": "acp-list-sessions"}))
        
        while True:
            msg = await ws.recv()
            d = json.loads(msg)
            if d.get("type") == "acp-sessions-listed":
                sessions = d.get("sessions", [])
                for s in sessions:
                    if "test3" in s.get("cwd", ""):
                        print("Found in list:", s)
                break
        
asyncio.run(test())

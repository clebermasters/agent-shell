#!/usr/bin/env python3
# Test WebSocket connection to the backend.
# Usage: ./scripts/test-ws.py [host]
# Default host is read from CF_DOMAIN in .env (falls back to localhost:4010)

import sys, asyncio, json, os

try:
    import websockets
except ImportError:
    print("Installing websockets...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "websockets", "-q"])
    import websockets

def load_env(path):
    values = {}
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#') or '=' not in line:
                    continue
                k, v = line.split('=', 1)
                values[k.strip()] = v.strip()
    except FileNotFoundError:
        pass
    return values

if len(sys.argv) > 1:
    HOST = sys.argv[1]
else:
    env_file = os.path.join(os.path.dirname(__file__), '..', '.env')
    env = load_env(env_file)
    HOST = env.get('CF_DOMAIN', 'localhost:4010')

scheme = 'ws' if HOST.startswith('localhost') or HOST.startswith('127.') else 'wss'
URL = f"{scheme}://{HOST}/ws"

async def test():
    print(f"Connecting to {URL}...")
    async with websockets.connect(URL) as ws:
        print("Connected ✓")
        await ws.send(json.dumps({"type": "list-sessions"}))
        response = await asyncio.wait_for(ws.recv(), timeout=5)
        data = json.loads(response)
        print(f"Response: {json.dumps(data, indent=2)}")

asyncio.run(test())

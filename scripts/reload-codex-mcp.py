#!/usr/bin/env python3
"""Refresh a live local Codex daemon after MCP registration, without a restart."""
import asyncio
import json
import os
from pathlib import Path
import sys


def daemon_socket(home):
    record = home / '.codex/app-server-daemon/daemon.pid'
    if not record.exists():
        return None
    pid = int(json.loads(record.read_text())['pid'])
    process = Path('/proc') / str(pid)
    if not process.exists():
        return None
    if process.stat().st_uid != os.getuid():
        raise ValueError('Codex daemon belongs to another user')
    command = (process / 'cmdline').read_bytes().split(b'\0')
    if b'--managed-daemon' not in command:
        raise ValueError('Recorded process is not a managed Codex daemon')
    inodes = set()
    for entry in (process / 'fd').iterdir():
        try:
            link = os.readlink(entry)
        except OSError:
            continue
        if link.startswith('socket:['):
            inodes.add(link[8:-1])
    paths = set()
    for line in Path('/proc/net/unix').read_text().splitlines():
        fields = line.split()
        if len(fields) == 8 and fields[6] in inodes and fields[7].startswith('/'):
            paths.add(fields[7])
    if len(paths) != 1:
        raise ValueError('Could not identify one current Codex daemon socket')
    return paths.pop()


async def reload(socket_path):
    import websockets
    async with websockets.unix_connect(socket_path, open_timeout=5) as connection:
        async def request(identifier, method, params=None):
            value = {'id': identifier, 'method': method}
            if params is not None:
                value['params'] = params
            await connection.send(json.dumps(value))
            while True:
                response = json.loads(await asyncio.wait_for(connection.recv(), 10))
                if response.get('id') == identifier:
                    if 'error' in response:
                        raise ValueError('Codex MCP reload RPC was rejected')
                    return
        await request(1, 'initialize', {'clientInfo': {'name': 'agentshell-installer', 'version': '1'}})
        await connection.send(json.dumps({'method': 'initialized'}))
        await request(2, 'config/mcpServer/reload')


def main():
    path = daemon_socket(Path.home())
    if path is None:
        print('No live Codex daemon; new agents will load the installed MCP configuration.')
        return
    asyncio.run(reload(path))
    print('Codex MCP configuration reloaded; loaded threads queued for refresh. No agent restarted.')


if __name__ == '__main__':
    runtime = Path(__file__).resolve().parents[1] / 'venv'
    if (runtime / 'bin/python').exists() and Path(sys.prefix).resolve() != runtime.resolve():
        os.execv(str(runtime / 'bin/python'), [str(runtime / 'bin/python'), __file__, *sys.argv[1:]])
    try:
        main()
    except Exception as error:
        # Reload failure must be visible; callers keep the installed files and
        # explicitly report that an existing client still needs a native reload.
        print('Codex MCP refresh pending: ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)

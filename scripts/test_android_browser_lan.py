#!/usr/bin/env python3
"""Verify an Android WebView renders a trusted secondary HTTPS browser origin.

Requires test_browser_streaming.py --keep-running and the owned test emulator.
All browser pages and credentials are synthetic.
"""
import argparse
import asyncio
import json
import subprocess
from pathlib import Path
import urllib.request
import zipfile
import websockets
from test_android_browser import Android, PROJECT, CDP


class LanAndroid(Android):
    current_origin='https://localhost:18998'
    async def target(self):
        pid=(await self.adb('shell','pidof','com.agentshell.debug')).decode().strip()
        await self.adb('forward','tcp:18995','localabstract:webview_devtools_remote_'+pid)
        targets=await asyncio.to_thread(lambda:json.load(urllib.request.urlopen('http://127.0.0.1:18995/json',timeout=2)))
        return next(t for t in targets if t['url'].startswith(self.current_origin+'/browser/'))

    async def launch(self,alias=True,tunnel=False,legacy=False):
        self.current_origin='https://localhost:18998' if alias and not legacy else 'https://127.0.0.1:18998'
        await self.adb('shell','am','force-stop','com.agentshell.debug')
        await self.adb('shell','am','start','-n','com.agentshell.debug/com.agentshell.BrowserFlowPocActivity','--ez','lanAlias',str(alias and not legacy).lower(),'--ez','lanTunnel',str(tunnel).lower(),'--ez','legacyHttp',str(legacy).lower())
        return await self.ready()

    async def run(self,apk):
        assert self.serial.startswith('emulator-')
        assert (await self.adb('emu','avd','name')).decode().splitlines()[0]=='agentshell-ui-poc'
        with zipfile.ZipFile(apk) as archive:
            assert any(b'isolated-ui-poc-only' in archive.read(n) for n in archive.namelist() if n.endswith('.dex'))
        self.output.mkdir(parents=True,exist_ok=True)
        try:
            await self.adb('install','-r',str(apk))
            await self.adb('reverse','tcp:18998','tcp:18998')
            target=await self.launch()
            async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
                c=CDP(socket)
                assert await c.evaluate('location.origin==="https://localhost:18998" && isSecureContext && document.cookie===""')
                assert await c.evaluate('document.getElementById("noVNC_fallback_errormsg").innerText===""')
                assert not await c.evaluate('Object.values(localStorage).some(x=>x.includes("isolated-ui-poc-only"))')
            self.results['secondary_https_origin_renders_real_frame']=True
            self.results['trusted_tls_secure_context_and_http_only_cookie']=True
            await self.screenshot('lan-alias-browser')
            await self.adb('shell','am','force-stop','com.agentshell.debug');await asyncio.sleep(.5)
            await self.launch(alias=False)
            self.results['primary_https_origin_still_connects']=True
            await self.adb('shell','am','force-stop','com.agentshell.debug');await asyncio.sleep(.5)
            await self.launch()
            self.results['return_to_lan_alias_connects']=True
            await self.adb('shell','am','force-stop','com.agentshell.debug');await asyncio.sleep(.5)
            target=await self.launch(tunnel=True)
            async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
                assert await CDP(socket).evaluate('location.origin==="https://localhost:18998" && isSecureContext && !!AgentShellUI.connected')
            self.results['encrypted_direct_ip_route_keeps_https_hostname_and_frame']=True
            await self.adb('shell','am','force-stop','com.agentshell.debug');await asyncio.sleep(.5)
            target=await self.launch(legacy=True)
            async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
                assert await CDP(socket).evaluate('location.origin==="https://127.0.0.1:18998" && isSecureContext && !!AgentShellUI.connected')
            nodes=list(await self.nodes())
            assert any(n.get('text')=='127.0.0.1' for n in nodes),'Original saved IP name was lost'
            assert not any(n.get('text')=='Choose a server with HTTPS enabled to open your browser securely.' for n in nodes)
            self.results['existing_custom_http_ip_entry_automatically_uses_verified_https']=True
            await self.screenshot('existing-http-ip-after')
            (self.output/'results.json').write_text(json.dumps(self.results,indent=2)+'\n')
            print(json.dumps(self.results,indent=2))
        finally:
            await self.adb('shell','am','force-stop','com.agentshell.debug')
            await self.adb('forward','--remove','tcp:18995')
            await self.adb('reverse','--remove','tcp:18998')


async def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5560')
    parser.add_argument('--apk',type=Path,default=PROJECT/'android-native/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--output',type=Path,default=Path('/tmp/as-lan-android'))
    args=parser.parse_args();await LanAndroid(args.serial,args.output).run(args.apk)


if __name__=='__main__':asyncio.run(main())

#!/usr/bin/env python3
"""Real Rust HTTPS gateway -> native loopback bridge -> owned fake Swift helper.

No Chrome, phone, user profile, agent prompt or production service is used.
Temporary HOME/databases, a named TMUX server and private test TLS isolate the run.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
import websockets

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed, InvalidStatus

from test_browser_runtime_macos import FAKE, runtime

PROJECT=Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',0));return probe.getsockname()[1]


class Gateway:
    def __init__(self,origin,context,token):self.origin=origin;self.context=context;self.token=token

    async def api(self,path,body=None,authenticated=True,cookie=None,raw=False):
        def fetch():
            headers={}
            if authenticated:headers['X-Auth-Token']=self.token
            if cookie:headers['Cookie']=cookie
            payload=body
            if isinstance(body,dict):headers['Content-Type']='application/json';payload=json.dumps(body).encode()
            elif isinstance(body,str):headers['Content-Type']='application/x-www-form-urlencoded';payload=body.encode()
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self,*args):return None
            opener=urllib.request.build_opener(NoRedirect,urllib.request.HTTPSHandler(context=self.context))
            request=urllib.request.Request(self.origin+path,data=payload,headers=headers)
            try:response=opener.open(request,timeout=15)
            except urllib.error.HTTPError as error:response=error
            with response:
                data=response.read(16*1024*1024)
                return response.status,response.headers,data if raw else json.loads(data) if data else {}
        return await asyncio.to_thread(fetch)

    def viewer(self,path,cookie,origin=None):
        return connect(self.origin.replace('https:','wss:')+path,ssl=self.context,origin=origin or self.origin,
            additional_headers={'Cookie':cookie},subprotocols=['binary'],open_timeout=5,close_timeout=2,max_size=runtime.MAX_FRAME)


async def packet(connection,predicate):
    async def receive():
        async for raw in connection:
            value=json.loads(raw)
            if predicate(value):return value
        raise AssertionError('Gateway closed before the expected native packet')
    return await asyncio.wait_for(receive(),8)


async def run(binary,output):
    checks={};backend=None;bridge=None;server=None
    socket_name='agentshell-native-gateway-'+uuid.uuid4().hex
    tmux_binary=shutil.which('tmux')
    if not tmux_binary:raise RuntimeError('TMUX is required for this owned gateway fixture')
    with tempfile.TemporaryDirectory(prefix='owned-native-gateway-',dir=output) as temporary:
        root=Path(temporary);home=root/'home';home.mkdir();instance=root/'backend';instance.mkdir();certs=root/'certs';certs.mkdir()
        key,certificate=certs/'key.pem',certs/'cert.pem'
        openssl=certs/'openssl.conf'
        openssl.write_text('[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=server\n'
            '[dn]\nCN=localhost\n[server]\nsubjectAltName=@names\nbasicConstraints=critical,CA:TRUE\n'
            'keyUsage=digitalSignature,keyEncipherment,keyCertSign\nextendedKeyUsage=serverAuth\n'
            '[names]\nDNS.1=localhost\nIP.1=127.0.0.1\n')
        subprocess.run(['openssl','req','-x509','-nodes','-newkey','rsa:2048','-days','1','-config',str(openssl),
            '-keyout',str(key),'-out',str(certificate)],check=True,capture_output=True)
        key.chmod(0o600);context=ssl.create_default_context(cafile=str(certificate))
        ports=[]
        while len(ports)<2:
            candidate=free_port()
            if candidate not in ports:ports.append(candidate)
        http_port,https_port=ports;origin='https://127.0.0.1:'+str(https_port)
        listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();listener.setblocking(False)
        helper=root/'fake-swift';helper.write_text('#!'+sys.executable+'\n'+FAKE);helper.chmod(0o700)
        config={'provider':'macos-native','sessionId':str(uuid.uuid4()),'port':listener.getsockname()[1],
            'password':secrets.token_urlsafe(48),'runtimeDir':str(root/'runtime'),'userDataDir':str(root/'owned-profile'),
            'nativeHelper':str(helper),'pythonPath':sys.executable,'runner':str(PROJECT/'scripts/browser-runtime-macos.py'),
            'publicOrigin':origin,'displayName':'Owned native gateway fixture'}
        config_path=root/'browser.json';config_path.write_text(json.dumps(config));config_path.chmod(0o600)
        os.environ['AGENTSHELL_BACKEND_BIN']=str(binary)
        value=runtime.config(config_path);bridge=runtime.Bridge(value)
        server=await serve(bridge.connection,sock=listener,process_request=bridge.authorized,subprotocols=['binary'],max_size=runtime.MAX_INPUT)
        runtime.write_json(Path(value['runtimeDir'])/'native-bridge.json',{'pid':os.getpid(),
            'processGeneration':runtime.process_info(os.getpid())['generation'],'serveNonce':bridge.serve_nonce,
            'configFingerprint':runtime.fingerprint(value)})
        log=(root/'backend.log').open('wb');token=secrets.token_urlsafe(32);gateway=Gateway(origin,context,token)
        try:
            subprocess.run([tmux_binary,'-f','/dev/null','-L',socket_name,'new-session','-d','-s','native-gateway','-c',str(instance),'sleep 300'],
                check=True,capture_output=True)
            tmux_context=subprocess.check_output([tmux_binary,'-L',socket_name,'display-message','-p','-t','native-gateway',
                '#{socket_path},#{pid},0'],text=True).strip()
            environment=dict(os.environ,AUTH_TOKEN=token,HOME=str(home),XDG_CONFIG_HOME=str(root/'config'),TMUX=tmux_context,
                AGENTSHELL_BROWSER_CONFIG=str(config_path),AGENTSHELL_HTTP_PORT=str(http_port),AGENTSHELL_HTTPS_PORT=str(https_port),
                AGENTSHELL_TLS_CERT_PATH=str(certificate),AGENTSHELL_TLS_KEY_PATH=str(key),RUST_LOG='warn',TOKIO_WORKER_THREADS='2',
                ALLOWED_ORIGIN=origin,AGENTSHELL_BACKEND_BIN=str(binary))
            # A managed venv is independent of HOME. Also support a local user-
            # site install without allowing the fixture HOME to hide websockets.
            environment['PYTHONPATH']=str(Path(websockets.__file__).resolve().parent.parent)+os.pathsep+os.environ.get('PYTHONPATH','')
            backend=await asyncio.create_subprocess_exec(str(binary),cwd=instance,env=environment,
                stdin=asyncio.subprocess.DEVNULL,stdout=log,stderr=log)
            for _ in range(100):
                if backend.returncode is not None:raise AssertionError('Owned Rust backend exited before HTTPS readiness')
                try:
                    if (await gateway.api('/api/clients'))[0]==200:break
                except OSError:pass
                await asyncio.sleep(.05)
            else:raise AssertionError('Owned HTTPS backend did not become ready')
            assert (await gateway.api('/api/clients',authenticated=False))[0]==401
            status,_,capabilities=await gateway.api('/api/browser/capabilities')
            assert status==200 and capabilities['available'] and capabilities['transport']=='native-macos',capabilities
            assert capabilities['session']['state']=='stopped' and bridge.helper.process is None
            checks['native_provider_https_capabilities_do_not_start_chrome']=True
            status,_,state=await gateway.api('/api/browser/sessions',{})
            assert status==200 and state['state']=='ready',state
            generation=state['runtimeGeneration'];chrome_pid=state['chromePid'];sid=config['sessionId'];path='/api/browser/sessions/'+sid
            viewer=str(uuid.uuid4())
            async def grant(viewer_id=viewer):
                return await gateway.api(path+'/viewer-grants',{'viewerId':viewer_id,'runtimeGeneration':generation})
            status,_,ticket=await grant();assert status==200,ticket
            bootstrap='/browser/'+sid+'/bootstrap';body='grant='+ticket['grant']
            status,headers,_=await gateway.api(bootstrap,body,authenticated=False,raw=True)
            assert status==303
            cookie=headers['Set-Cookie'].split(';',1)[0]
            assert all(flag in headers['Set-Cookie'] for flag in ('Secure','HttpOnly','SameSite=Strict'))
            assert (await gateway.api(bootstrap,body,authenticated=False,raw=True))[0]==401
            checks['single_use_origin_cookie_is_secure_httponly']=True
            asset='/browser/'+sid+'/view/'
            assert (await gateway.api(asset+'vnc.html',authenticated=False,raw=True))[0]==401
            status,headers,html=await gateway.api(asset+'vnc.html',authenticated=False,cookie=cookie,raw=True)
            assert status==200 and b'macos-viewer.js' in html and b'macos-viewer.css' in html
            assert 'blob:' in headers['Content-Security-Policy']
            for name in ('vnc.html','macos-viewer.js','macos-viewer.css'):
                status,_,data=await gateway.api(asset+name,authenticated=False,cookie=cookie,raw=True)
                assert status==200 and token.encode() not in data and config['password'].encode() not in data
            checks['native_assets_require_cookie_and_expose_no_backend_credentials']=True
            socket_path='/browser/'+sid+'/socket'
            try:
                async with gateway.viewer(socket_path,cookie,origin='https://wrong.example'):raise AssertionError('Wrong Origin was accepted')
            except InvalidStatus as rejected:assert rejected.response.status_code==401
            checks['wrong_websocket_origin_is_rejected']=True
            async with gateway.viewer(socket_path,cookie) as connection:
                frame=await packet(connection,lambda value:value.get('type')=='frame')
                assert frame['width']==640 and frame['height']==800
                # Admission to the real bridge also validates Rust's private generation header.
                checks['authorized_wss_relays_frame_with_native_generation_pin']=True
                status,_,failure=await grant(str(uuid.uuid4()));assert status==409,failure
                checks['second_controller_cannot_take_the_active_stream']=True
                identifier='gateway-unicode-'+uuid.uuid4().hex;text='Owned gateway café 😀'
                await connection.send(json.dumps({'type':'text','method':'unicode','id':identifier,'text':text}))
                reply=await packet(connection,lambda value:value.get('type')=='input-result' and value.get('id')==identifier)
                assert reply['ok'] is True,reply
                trace=[json.loads(line) for line in (root/'runtime/fake-native.jsonl').read_text().splitlines()]
                assert [(item['id'],item['text']) for item in trace if item['type']=='text']==[(identifier,text)]
                checks['input_acknowledgement_crosses_the_real_https_gateway']=True
                status,_,desktop=await gateway.api(path+'/native-handoff',{'viewerId':viewer,'runtimeGeneration':generation,'destination':'desktop'})
                assert status==200 and desktop['connected'] and desktop['session']['state']=='native',desktop
                assert desktop['session']['chromePid']==chrome_pid and desktop['session']['runtimeGeneration']==generation
                try:
                    while True:await asyncio.wait_for(connection.recv(),5)
                except ConnectionClosed:pass
            denied,_,body=await gateway.api(asset+'vnc.html',authenticated=False,cookie=cookie,raw=True)
            # While capture is on the desktop, readiness can reject before the
            # cookie lookup. After phone handoff below, the cookie must be gone.
            assert denied in (401,409),(denied,body[:160])
            status,_,phone=await gateway.api(path+'/native-handoff',{'viewerId':viewer,'runtimeGeneration':generation,'destination':'phone'})
            assert status==200 and phone['connected'] and phone['session']['state']=='ready',phone
            assert phone['session']['chromePid']==chrome_pid and phone['session']['runtimeGeneration']==generation
            assert (await gateway.api(asset+'vnc.html',authenticated=False,cookie=cookie,raw=True))[0]==401
            checks['native_handoffs_preserve_chrome_but_revoke_old_cookie_and_controller']=True
            status,_,fresh=await grant(str(uuid.uuid4()));assert status==200,fresh
            status,headers,_=await gateway.api(bootstrap,'grant='+fresh['grant'],authenticated=False,raw=True)
            assert status==303
            replacement=headers['Set-Cookie'].split(';',1)[0]
            async with gateway.viewer(socket_path,replacement) as connection:
                frame=await packet(connection,lambda value:value.get('type')=='frame')
                assert frame['width']>0 and frame['height']>0
            checks['new_controller_can_connect_after_handoff_revocation']=True
        finally:
            if backend is not None and backend.returncode is None:
                backend.terminate()
                try:await asyncio.wait_for(backend.wait(),8)
                except asyncio.TimeoutError:backend.kill();await backend.wait()
            if server is not None:server.close();await server.wait_closed()
            if bridge is not None:await bridge.helper.close()
            log.close();shutil.copyfile(root/'backend.log',output/'backend.log')
            for name in ('native-bridge.log','fake-native.jsonl'):
                source=root/'runtime'/name
                if source.exists():shutil.copyfile(source,output/name)
            subprocess.run([tmux_binary,'-L',socket_name,'kill-server'],capture_output=True)
    checks['owned_temporary_services_and_profile_fixture_cleaned']=True
    (output/'results.json').write_text(json.dumps(checks,indent=2)+'\n')
    print(json.dumps({'passed':len(checks),'checks':checks,'output':str(output)},indent=2))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--binary',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    binary=args.binary.resolve();assert binary.is_file() and os.access(binary,os.X_OK)
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True);output.chmod(0o700)
    asyncio.run(run(binary,output))


if __name__=='__main__':main()

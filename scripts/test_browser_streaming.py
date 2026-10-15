#!/usr/bin/env python3
"""Own a temporary Chrome profile/display and exercise the real Rust browser API.

No production browser, session or service is stopped. Leave --keep-running for
Android fixture tests, then use --cleanup against the same private output dir.
"""
import argparse
import asyncio
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import websockets

PROJECT=Path(__file__).resolve().parents[1]
TOKEN='isolated-ui-poc-only'
HTTP='http://127.0.0.1:18999'
HTTPS='https://127.0.0.1:18998'

class Fixture:
    def __init__(self, output, binary, shared_credentials=False, automatic_profile_check=False):
        self.output=output;self.binary=binary;self.backend=None;self.pages=None;self.cookie_seen=False
        self.config_path=output/'browser.json';self.config={}
        self.results={};self.ssl=None;self.shared_credentials=shared_credentials;self.mouse_events=[];self.pages_loaded=0
        self.automatic_profile_check=automatic_profile_check
    def api(self,path,body=None,token=TOKEN,cookie=None,https=False,raw=False):
        headers={}
        if token:headers['X-Auth-Token']=token
        if cookie:headers['Cookie']=cookie
        if isinstance(body,dict):headers['Content-Type']='application/json';body=json.dumps(body).encode()
        elif isinstance(body,str):headers['Content-Type']='application/x-www-form-urlencoded';body=body.encode()
        request=urllib.request.Request((HTTPS if https else HTTP)+path,data=body,headers=headers)
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args):return None
        opener=urllib.request.build_opener(NoRedirect,urllib.request.HTTPSHandler(context=self.ssl))
        try:response=opener.open(request,timeout=25)
        except urllib.error.HTTPError as error:response=error
        data=response.read()
        return response.status,response.headers,data if raw else json.loads(data) if data else {}
    def helper(self,command):
        q=subprocess.run(['python3',str(PROJECT/'scripts/browser-runtime.py'),'--config',str(self.config_path),command],capture_output=True,text=True,timeout=25)
        assert q.returncode==0;return json.loads(q.stdout)
    def start_backend(self):
        environment=dict(os.environ,AUTH_TOKEN=TOKEN,AGENTSHELL_BROWSER_CONFIG=str(self.config_path),
            AGENTSHELL_HTTP_PORT='18999',AGENTSHELL_HTTPS_PORT='18998',RUST_LOG='error',HOME=str(self.output/'home'))
        with (self.output/'backend.log').open('ab') as log:
            self.backend=subprocess.Popen([str(self.binary)],cwd=self.output/'backend',env=environment,stdout=log,stderr=log)
        for _ in range(50):
            try:
                if self.api('/api/clients')[0]==200:return
            except (OSError,urllib.error.URLError):pass
            time.sleep(.1)
        raise AssertionError('Fixture backend did not start')
    def stop_backend(self):
        if self.backend and self.backend.poll() is None:self.backend.terminate();self.backend.wait(timeout=10)
    def prepare(self):
        self.output.mkdir(parents=True,exist_ok=True);self.output.chmod(0o700)
        if self.config_path.exists(): self.helper('stop')
        for name in ['backend','home','certs']:(self.output/name).mkdir(exist_ok=True)
        source=Path('/tmp/agentshell-browser-implementation/tls')
        for name in ['cert.pem','key.pem','ca.pem']:shutil.copyfile(source/name,self.output/'certs'/name)
        self.ssl=ssl.create_default_context(cafile=str(self.output/'certs/ca.pem'))
        self.config={'sessionId':str(uuid.uuid4()),'userDataDir':str(self.output/'chrome-profile'),
            'profileDirectory':None,'runtimeDir':str(self.output/'runtime'),'display':94,'port':19002,
            'password':uuid.uuid4().hex+uuid.uuid4().hex,'runner':str(PROJECT/'scripts/browser-runtime.py'),
            'publicOrigin':HTTPS,'additionalOrigins':['https://localhost:18998'],'supervision':'process','privateBus':not self.shared_credentials,
            'requireSecretService':self.shared_credentials,'startUrl':'http://127.0.0.1:19003/'}
        self.config_path.write_text(json.dumps(self.config));self.config_path.chmod(0o600)
        owner=self
        class Page(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path=='/mouse-events':
                    data=json.dumps(owner.mouse_events).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(data);return
                owner.pages_loaded+=1
                if 'agentshell_fixture=persisted' in self.headers.get('Cookie',''):owner.cookie_seen=True
                data=b'<!doctype html><meta charset="utf-8"><h1>AgentShell secure browser fixture</h1><input id="text"><button onclick="window.open(\'/popup\')">Popup</button><input type="file">'
                data+=b'''<style>body{min-height:2400px}</style><script>for(const type of ['mousemove','mousedown','mouseup','wheel']) document.addEventListener(type,event=>{fetch('/mouse-event',{method:'POST',body:JSON.stringify({type,x:event.screenX,y:event.screenY,button:event.button,buttons:event.buttons,deltaY:event.deltaY,time:event.timeStamp})})},{passive:true});</script>'''
                self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Set-Cookie','agentshell_fixture=persisted; Max-Age=86400; SameSite=Lax');self.end_headers();self.wfile.write(data)
            def log_message(self,*args):pass
            def do_POST(self):
                if self.path=='/mouse-event':
                    length=int(self.headers.get('Content-Length','0'));assert length<4096
                    owner.mouse_events.append(json.loads(self.rfile.read(length)));owner.mouse_events=owner.mouse_events[-2000:]
                elif self.path=='/mouse-events/reset':owner.mouse_events=[]
                else:self.send_error(404);return
                self.send_response(204);self.end_headers()
        self.pages=ThreadingHTTPServer(('127.0.0.1',19003),Page);threading.Thread(target=self.pages.serve_forever,daemon=True).start()
        self.start_backend()
    async def tests(self):
        async def call(*args,**kwargs):return await asyncio.to_thread(self.api,*args,**kwargs)
        if self.automatic_profile_check:
            # First create a real, distinctly named fixture profile. Its on-disk
            # state is written by Chrome, rather than synthesized by this test.
            self.config['profileDirectory']='Profile 7'
            self.config_path.write_text(json.dumps(self.config));self.config_path.chmod(0o600)
            assert (await call('/api/browser/sessions',{}))[2]['state']=='ready'
            state_path=Path(self.config['userDataDir'])/'Local State'
            for _ in range(100):
                if self.pages_loaded:break
                await asyncio.sleep(.1)
            else:raise AssertionError('Fixture Chrome profile did not finish initializing')
            assert (await call('/api/browser/sessions/'+self.config['sessionId']+'/stop',{}))[2]['state']=='stopped'
            # Chrome batches Local State writes; graceful shutdown flushes the
            # completed profile initialization before the automatic-selection run.
            assert json.loads(state_path.read_text())['profile']['last_used']=='Profile 7'
            self.config['profileDirectory']=None
            self.config_path.write_text(json.dumps(self.config));self.config_path.chmod(0o600)
        assert (await call('/api/browser/capabilities',token='wrong'))[0]==401
        self.results['control_authentication']=True
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            futures=[pool.submit(self.api,'/api/browser/sessions',{}) for _ in range(2)]
            states=[future.result()[2] for future in futures]
        assert all(s['state']=='ready' for s in states),states
        assert states[0]['runtimeGeneration']==states[1]['runtimeGeneration']
        self.results['concurrent_start_one_owner']=True
        if self.shared_credentials:
            address='unix:path='+str(Path(self.config['runtimeDir'])/'credential-bus.sock')
            def owner(name):
                result=subprocess.run(['gdbus','call','--address',address,'--dest','org.freedesktop.DBus',
                    '--object-path','/org/freedesktop/DBus','--method','org.freedesktop.DBus.NameHasOwner',name],check=True,capture_output=True,text=True)
                return result.stdout.strip()=='(true,)'
            assert owner('org.freedesktop.secrets') and not owner('org.freedesktop.portal.Desktop')
            self.results['keyring_visible_desktop_portal_isolated']=True
            environment=dict(os.environ,DISPLAY=':'+str(self.config['display']),
                XAUTHORITY=str(Path(self.config['runtimeDir'])/'Xauthority'))
            def dialogs(env):
                result=subprocess.run(['xdotool','search','--onlyvisible','--name','^Open File$'],env=env,capture_output=True,text=True)
                return set(result.stdout.splitlines())
            desktop_before=dialogs(os.environ)
            for _ in range(50):
                window=subprocess.run(['xdotool','search','--onlyvisible','--class','Google-chrome'],env=environment,capture_output=True,text=True).stdout.splitlines()
                if window:break
                await asyncio.sleep(.1)
            assert window,'Fixture Chrome window did not appear'
            subprocess.run(['xdotool','windowactivate','--sync',window[0],'key','ctrl+o'],env=environment,check=True)
            for _ in range(50):
                managed=dialogs(environment)
                if managed:break
                await asyncio.sleep(.1)
            assert managed,'File dialog did not stay inside the managed display'
            assert dialogs(os.environ)==desktop_before,'File dialog leaked into the original desktop'
            subprocess.run(['xdotool','windowactivate','--sync',next(iter(managed)),'key','Escape'],env=environment,check=True)
            self.results['file_dialog_stays_in_managed_display']=True
        sid=self.config['sessionId'];path='/api/browser/sessions/'+sid
        viewer=str(uuid.uuid4());generation=states[0]['runtimeGeneration']
        async def grant(gen=generation,v=viewer):
            status,_,data=await call(path+'/viewer-grants',{'viewerId':v,'runtimeGeneration':gen})
            return status,data
        assert (await grant(gen=str(uuid.uuid4())))[0]==409
        status,data=await grant();assert status==200
        body='grant='+data['grant']
        status,headers,_=await call('/browser/'+sid+'/bootstrap',body,token=None,https=True,raw=True)
        assert status==303
        cookie=headers['Set-Cookie'].split(';',1)[0]
        assert 'HttpOnly' in headers['Set-Cookie'] and 'Secure' in headers['Set-Cookie'] and 'SameSite=Strict' in headers['Set-Cookie']
        assert (await call('/browser/'+sid+'/bootstrap',body,token=None,https=True,raw=True))[0]==401
        self.results['single_use_scoped_secure_cookie']=True
        assert (await call('/browser/'+sid+'/view/vnc.html',token=None,https=True,raw=True))[0]==401
        status,_,html=await call('/browser/'+sid+'/view/vnc.html',token=None,cookie=cookie,https=True,raw=True)
        assert status==200 and b'viewer-bridge.js' in html and TOKEN.encode() not in html
        self.results['viewer_assets_require_cookie']=True
        url=HTTPS.replace('https:','wss:')+'/browser/'+sid+'/socket'
        try:
            async with websockets.connect(url,ssl=self.ssl,origin='https://wrong.test',additional_headers={'Cookie':cookie}):raise AssertionError('Wrong Origin accepted')
        except websockets.exceptions.InvalidStatus as rejected:assert rejected.response.status_code==401
        try:
            async with websockets.connect(url,ssl=self.ssl,origin='https://localhost:18998',additional_headers={'Cookie':cookie}):raise AssertionError('A primary-origin cookie crossed into the LAN alias')
        except websockets.exceptions.InvalidStatus as rejected:assert rejected.response.status_code==401
        async with websockets.connect(url,ssl=self.ssl,origin=HTTPS,additional_headers={'Cookie':cookie},subprotocols=['binary']) as socket:
            assert await asyncio.wait_for(socket.recv(),5)==b'RFB 003.008\n'
            assert (await grant(v=str(uuid.uuid4())))[0]==409
            self.results['origin_and_single_controller']=True
        await asyncio.sleep(.2)
        request={'viewerId':viewer,'runtimeGeneration':generation,'viewerOrigin':'https://wrong.test'}
        assert (await call(path+'/viewer-grants',request))[0]==401
        request['viewerOrigin']='https://localhost:18998'
        status,_,data=await call(path+'/viewer-grants',request);assert status==200
        status,headers,_=await call('/browser/'+sid+'/bootstrap','grant='+data['grant'],token=None,https=True,raw=True);assert status==303
        lan_cookie=headers['Set-Cookie'].split(';',1)[0]
        status,headers,_=await call('/browser/'+sid+'/view/vnc.html',token=None,cookie=lan_cookie,https=True,raw=True)
        assert status==200 and 'wss://localhost:18998' in headers['Content-Security-Policy']
        try:
            async with websockets.connect(url,ssl=self.ssl,origin=HTTPS,additional_headers={'Cookie':lan_cookie}):raise AssertionError('A LAN cookie crossed into the primary origin')
        except websockets.exceptions.InvalidStatus as rejected:assert rejected.response.status_code==401
        async with websockets.connect(url.replace('127.0.0.1','localhost'),ssl=self.ssl,origin='https://localhost:18998',additional_headers={'Cookie':lan_cookie},subprotocols=['binary']) as socket:
            assert await asyncio.wait_for(socket.recv(),5)==b'RFB 003.008\n'
        self.results['lan_https_alias_and_origin_bound_tickets']=True
        await asyncio.sleep(.2)
        self.stop_backend();self.start_backend()
        assert (await call(path))[2]['runtimeGeneration']==generation
        assert (await call('/browser/'+sid+'/view/vnc.html',token=None,cookie=cookie,https=True,raw=True))[0]==401
        self.results['daemon_restart_keeps_chrome_revokes_cookie']=True
        assert (await call(path+'/stop',{}))[2]['state']=='stopped'
        self.cookie_seen=False
        restarted=(await call('/api/browser/sessions',{}))[2]
        assert restarted['state']=='ready' and restarted['sessionId']==sid and restarted['runtimeGeneration']!=generation
        for _ in range(50):
            if self.cookie_seen:break
            await asyncio.sleep(.1)
        assert self.cookie_seen,'Synthetic browser cookie did not survive Chrome restart'
        self.results['profile_persists_runtime_generation_changes']=True
        if self.automatic_profile_check:
            state=json.loads((Path(self.config['userDataDir'])/'Local State').read_text())
            assert state['profile']['last_used']=='Profile 7'
            assert (Path(self.config['userDataDir'])/'Profile 7'/'Preferences').is_file()
            record=json.loads((Path(self.config['runtimeDir'])/'runtime.json').read_text())
            arguments=(Path('/proc')/str(record['chromePid'])/'cmdline').read_bytes().split(b'\0')
            assert not any(arg.startswith(b'--profile-directory=') for arg in arguments)
            self.results['chrome_chooses_existing_default_without_forced_profile']=True
        # The Android production-flow fixture requests its own grant. Reserving
        # an unused POC grant here blocks that viewer for the controller lease.
        (self.output/'results.json').write_text(json.dumps(self.results,indent=2)+'\n')
        print(json.dumps(self.results,indent=2),flush=True)
    def cleanup(self):
        self.stop_backend()
        if self.config_path.exists():self.helper('stop')
        if self.pages:self.pages.shutdown();self.pages.server_close()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=Path('/tmp/agentshell-browser-implementation/integration'))
    parser.add_argument('--binary',type=Path,default=PROJECT/'backend-rust/target/release/agentshell-backend')
    parser.add_argument('--keep-running',action='store_true');parser.add_argument('--cleanup',action='store_true')
    parser.add_argument('--shared-credential-store',action='store_true',help='Verify existing keyring access and desktop-portal isolation without reading credentials')
    parser.add_argument('--automatic-profile-check',action='store_true',help='Verify Chrome selects its own existing fixture profile without a forced profile argument')
    args=parser.parse_args()
    fixture=Fixture(args.output,args.binary.resolve(),args.shared_credential_store,args.automatic_profile_check)
    if args.cleanup:
        fixture.cleanup();return
    try:
        fixture.prepare();asyncio.run(fixture.tests())
        if args.keep_running:
            print('Owned fixture remains running for Android verification.',flush=True)
            while True:time.sleep(1)
    finally:fixture.cleanup()

if __name__=='__main__':main()

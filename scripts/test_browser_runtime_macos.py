#!/usr/bin/env python3
"""Native bridge protocol tests with an owned fake Swift process, never Chrome."""
import asyncio
import base64
from http import HTTPStatus
import importlib.util
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed, InvalidStatus

spec=importlib.util.spec_from_file_location('native_runtime',Path(__file__).with_name('browser-runtime-macos.py'))
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)

FAKE = r'''
import argparse,base64,json,pathlib,sys
p=argparse.ArgumentParser();p.add_argument('--config');p.add_argument('--stdio',action='store_true');args=p.parse_args()
config=json.loads(pathlib.Path(args.config).read_text());state='stopped';sequence=0;chrome=4242;window=73
permissions={'screenCapture':config.get('testPermissions',True),'accessibility':config.get('testPermissions',True)}
trace=pathlib.Path(config['runtimeDir'])/'fake-native.jsonl';width=640;height=800
def status():
 result={'state':state,'permissions':permissions,'chromePid':chrome,'chromeStart':9000001,'windowId':window,'width':width,'height':height}
 if not all(permissions.values()):result.update(state='permission-required',code='SCREEN_CAPTURE_PERMISSION_REQUIRED')
 return result
def output(item):print(json.dumps(item),flush=True)
def frame():
 global sequence
 sequence+=1;output({'type':'frame','width':width,'height':height,'data':base64.b64encode(b'\xff\xd8fixture-jpeg\xff\xd9').decode(),'sequence':sequence})
for line in sys.stdin:
 command=json.loads(line)
 with trace.open('a') as log:log.write(json.dumps(command,ensure_ascii=False)+'\n')
 kind=command['type'];emit=False
 if kind=='start':state='ready';emit=True
 elif kind=='stop':state='stopped'
 elif kind=='handoff':state='native' if command['destination']=='desktop' else 'ready';emit=state=='ready'
 elif kind=='resize':width=command['width'];height=command['height'];emit=True
 elif kind=='test-replace':chrome+=1;window+=1
 elif kind=='test-permission-event':permissions['screenCapture']=False;state='permission-required'
 elif kind not in ('status','release','pointer','key','text'):
  output({'id':command['id'],'ok':False,'error':{'code':'UNSUPPORTED_INPUT'}});continue
 output({'id':command['id'],'ok':True,'result':status() if kind in ('status','start','stop','handoff') else {'accepted':True}})
 if kind=='test-permission-event':output(dict(status(),type='state'))
 if emit:frame()
'''


class NativeRuntimeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.root=Path(self.temporary.name)
        self.helper=self.root/'native-helper';self.helper.write_text('#!'+sys.executable+'\n'+FAKE);self.helper.chmod(0o700)
        self.socket=socket.socket();self.socket.bind(('127.0.0.1',0));self.socket.listen();self.socket.setblocking(False)
        self.value={'provider':'macos-native','sessionId':str(uuid.uuid4()),'port':self.socket.getsockname()[1],
            'password':'synthetic-password-only-'+uuid.uuid4().hex,'userDataDir':str(self.root/'owned-chrome'),
            'runtimeDir':str(self.root/'runtime'),'nativeHelper':str(self.helper),'pythonPath':sys.executable}
        self.path=self.root/'browser.json';self.write_config()
        self.value=runtime.config(self.path);self.bridge=runtime.Bridge(self.value)
        self.server=await serve(self.bridge.connection,sock=self.socket,process_request=self.bridge.authorized,
            subprotocols=['binary'],max_size=runtime.MAX_INPUT,max_queue=16)
        runtime.write_json(Path(self.value['runtimeDir'])/'native-bridge.json',{'pid':os.getpid(),
            'processGeneration':runtime.process_info(os.getpid())['generation'],'runtimeGeneration':self.bridge.generation,
            'configFingerprint':runtime.fingerprint(self.value)})
        self.origin='http://127.0.0.1:'+str(self.value['port'])
        self.authorization='Basic '+base64.b64encode(('viewer:'+self.value['password']).encode()).decode()

    def write_config(self):
        self.path.write_text(json.dumps(self.value));self.path.chmod(0o600)

    async def asyncTearDown(self):
        self.server.close();await self.server.wait_closed();await self.bridge.helper.close();self.temporary.cleanup()

    def client(self,path='/websockify',origin=None,authorization=None,protocols=None):
        return connect('ws://127.0.0.1:'+str(self.value['port'])+path,origin=self.origin if origin is None else origin,
            additional_headers={'Authorization':self.authorization if authorization is None else authorization,
                                'X-AgentShell-Runtime-Generation':self.bridge.generation},
            subprotocols=['binary'] if protocols is None else protocols,max_size=runtime.MAX_FRAME)

    async def start(self):
        result=await self.bridge.control({'type':'runtime-start'})
        for _ in range(100):
            if self.bridge.latest is not None:return result
            await asyncio.sleep(.01)
        raise AssertionError('Fake native helper did not produce a frame')

    def trace(self):
        path=Path(self.value['runtimeDir'])/'fake-native.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    async def reply(self,connection,identifier):
        for _ in range(30):
            value=json.loads(await asyncio.wait_for(connection.recv(),3))
            if value.get('id')==identifier:return value
        raise AssertionError('Input acknowledgement missing')

    async def test_status_and_profiles_never_start_the_helper_or_chrome(self):
        result=await runtime.run(SimpleNamespace(command='status'),self.value)
        self.assertEqual(result['state'],'stopped');self.assertIsNone(self.bridge.helper.process)
        catalog=await runtime.run(SimpleNamespace(command='profiles'),self.value)
        self.assertEqual(len(catalog['profiles']),1);self.assertFalse(catalog['canSelect'])
        selection=await runtime.run(SimpleNamespace(command='select-profile'),self.value)
        self.assertFalse(selection['connected']);self.assertFalse(Path(self.value['userDataDir']).exists())

    async def test_permissions_are_truthful_and_no_start_command_is_sent_without_them(self):
        self.value['testPermissions']=False;self.write_config()
        result=await self.bridge.control({'type':'runtime-start'})
        self.assertEqual(result['state'],'permission-required');self.assertFalse(result['permissions']['screenCapture'])
        self.assertEqual([item['type'] for item in self.trace()],['status'])
        self.assertFalse(Path(self.value['userDataDir']).exists())

    async def test_authentication_origin_and_binary_subprotocol_are_required(self):
        for origin,authorization,protocols,expected in [(self.origin,'Basic wrong',['binary'],401),('https://wrong.test',self.authorization,['binary'],401),(self.origin,self.authorization,[],400)]:
            with self.subTest(expected=expected),self.assertRaises(InvalidStatus) as caught:
                async with self.client(origin=origin,authorization=authorization,protocols=protocols):pass
            self.assertEqual(caught.exception.response.status_code,expected)
        self.assertIsNone(self.bridge.helper.process)

    async def test_handoffs_and_capture_stop_preserve_native_browser_identity(self):
        initial=await self.start();generation=initial['runtimeGeneration']
        desktop=await self.bridge.control({'type':'runtime-handoff','destination':'desktop','generation':generation})
        self.assertEqual(desktop['state'],'native');self.assertTrue(desktop['connected'])
        phone=await self.bridge.control({'type':'runtime-handoff','destination':'phone','generation':generation})
        self.assertEqual(phone['state'],'ready');self.assertEqual(phone['chromePid'],initial['chromePid'])
        self.assertEqual(phone['chromeStart'],initial['chromeStart']);self.assertEqual(phone['runtimeGeneration'],generation)
        with self.assertRaisesRegex(runtime.RuntimeErrorCode,'RUNTIME_CHANGED'):
            await self.bridge.control({'type':'runtime-handoff','destination':'desktop','generation':str(uuid.uuid4())})
        stopped=await self.bridge.control({'type':'runtime-stop'})
        self.assertEqual(stopped['state'],'stopped');self.assertEqual(stopped['chromePid'],initial['chromePid'])
        self.assertIsNone(self.bridge.helper.process.returncode)
        restarted=await self.start();self.assertEqual(restarted['chromePid'],initial['chromePid'])

    async def test_one_viewer_and_disconnect_release_all_native_input(self):
        await self.start()
        async with self.client() as first:
            self.assertEqual(json.loads(await first.recv())['type'],'state')
            async with self.client() as second:
                with self.assertRaises(ConnectionClosed) as rejected:await second.recv()
                self.assertEqual(rejected.exception.rcvd.code,1008)
        for _ in range(100):
            if any(item['type']=='release' for item in self.trace()):break
            await asyncio.sleep(.01)
        self.assertTrue(any(item['type']=='release' for item in self.trace()))

    async def test_unicode_input_acknowledgements_keep_the_original_client_id(self):
        await self.start()
        async with self.client() as connection:
            text='Café 😀\nsecond line'
            await connection.send(json.dumps({'type':'text','id':'dictation-job-1','text':text}))
            result=await self.reply(connection,'dictation-job-1')
            self.assertEqual(result['type'],'input-result');self.assertTrue(result['ok'])
            typed=[item for item in self.trace() if item['type']=='text']
            self.assertEqual([(item['id'],item['text']) for item in typed],[('dictation-job-1',text)])

    async def test_viewer_cannot_invoke_runtime_controls_or_out_of_frame_input(self):
        await self.start()
        async with self.client() as connection:
            for identifier,command in [('stop',{'type':'stop'}),('bad-point',{'type':'pointer','kind':'down','x':99999,'y':0}),('bad-size',{'type':'resize','width':2560,'height':3840})]:
                await connection.send(json.dumps(dict(command,id=identifier)))
                self.assertFalse((await self.reply(connection,identifier))['ok'])
            await connection.send(json.dumps({'type':'resize','id':'resize','width':800,'height':1200,'contentDensity':150}))
            self.assertTrue((await self.reply(connection,'resize'))['ok'])
        self.assertFalse(any(item['type']=='stop' for item in self.trace()))
        self.assertEqual([item['contentDensity'] for item in self.trace() if item['type']=='resize'],[150])

    async def test_latest_frame_replaces_previous_and_invalid_frames_are_ignored(self):
        self.bridge.state['state']='ready'
        data=base64.b64encode(b'\xff\xd8fake-jpeg\xff\xd9').decode()
        for _ in range(100):await self.bridge.accept_frame({'width':640,'height':800,'data':data})
        self.assertEqual(self.bridge.sequence,100);self.assertEqual(json.loads(self.bridge.latest)['sequence'],100)
        before=self.bridge.latest
        for frame in [{'width':9000,'height':9000,'data':data},{'width':640,'height':800,'data':'bad64!'}]:await self.bridge.accept_frame(frame)
        self.assertEqual(self.bridge.latest,before)

    async def test_permission_loss_and_closed_window_retire_cached_frames_and_generation(self):
        state=await self.start();generation=state['runtimeGeneration']
        self.bridge.update(dict(state,state='native-closed'))
        self.assertNotEqual(self.bridge.generation,generation);self.assertIsNone(self.bridge.latest)
        data=base64.b64encode(b'\xff\xd8fake-jpeg\xff\xd9').decode()
        await self.bridge.accept_frame({'width':640,'height':800,'data':data})
        self.assertIsNone(self.bridge.latest)
        self.bridge.update(dict(state,state='permission-required',permissions={'screenCapture':False,'accessibility':True}))
        await self.bridge.accept_frame({'width':640,'height':800,'data':data})
        self.assertIsNone(self.bridge.latest);self.assertEqual(self.bridge.dimensions,(0,0))

    async def test_a_ready_label_without_both_permissions_cannot_publish_frames(self):
        self.bridge.update({'state':'ready','permissions':{'screenCapture':True,'accessibility':False}})
        self.assertEqual(self.bridge.state['state'],'permission-required')
        self.bridge.update({'state':'ready'})
        self.assertEqual(self.bridge.state['state'],'permission-required')

    async def test_existing_personal_profile_is_never_adopted_or_started(self):
        profile=Path(self.value['userDataDir']);profile.mkdir();(profile/'Bookmarks').write_text('personal fixture')
        with self.assertRaisesRegex(runtime.RuntimeErrorCode,'PROFILE_NOT_OWNED'):await self.bridge.control({'type':'runtime-start'})
        self.assertEqual([item['type'] for item in self.trace()],['status'])
        self.assertEqual((profile/'Bookmarks').read_text(),'personal fixture')

    async def test_stale_pid_metadata_is_not_a_signal_target(self):
        with patch.object(runtime,'process_info',return_value={'generation':-1}),patch.object(runtime.os,'kill',side_effect=AssertionError('No PID signals')):
            result=await runtime.run(SimpleNamespace(command='stop'),self.value)
        self.assertEqual(result['state'],'stopped');self.assertIsNone(self.bridge.helper.process)

    async def test_replaced_chrome_rotates_generation_and_rejects_old_viewer_before_input(self):
        initial=await self.start();generation=initial['runtimeGeneration']
        async with self.client() as connection:
            await connection.recv()
            await self.bridge.helper.request({'type':'test-replace'})
            await connection.send(json.dumps({'type':'key','keysym':65293,'down':True,'id':'stale-enter'}))
            try:await self.reply(connection,'stale-enter')
            except ConnectionClosed:pass
        self.assertNotEqual(self.bridge.generation,generation)
        self.assertFalse(any(item['type']=='key' for item in self.trace()))

    async def test_configuration_permissions_password_port_and_executables_are_validated(self):
        self.path.chmod(0o644)
        with self.assertRaisesRegex(runtime.RuntimeErrorCode,'UNSAFE_RUNTIME_FILE'):runtime.config(self.path)
        self.path.chmod(0o600)
        for fields in [{'port':1023},{'password':'short'}]:
            original=dict(self.value);self.value.update(fields);self.write_config()
            with self.assertRaises(runtime.RuntimeErrorCode):runtime.config(self.path)
            self.value=original
        self.write_config();self.helper.chmod(0o777)
        with self.assertRaisesRegex(runtime.RuntimeErrorCode,'UNSAFE_RUNTIME_EXECUTABLE'):runtime.config(self.path)

    async def test_swift_replacement_retires_generation_even_when_chrome_pin_is_the_same(self):
        original=await self.start();child=self.bridge.helper.process;nonce=self.bridge.serve_nonce
        child.terminate();await child.wait()
        for _ in range(100):
            if self.bridge.state['state']=='stopped':break
            await asyncio.sleep(.01)
        self.assertNotEqual(self.bridge.generation,original['runtimeGeneration']);self.assertIsNone(self.bridge.latest)
        restored=await self.start()
        self.assertEqual(restored['chromePid'],original['chromePid']);self.assertEqual(restored['windowId'],original['windowId'])
        self.assertNotEqual(restored['runtimeGeneration'],original['runtimeGeneration']);self.assertEqual(self.bridge.serve_nonce,nonce)

    async def test_async_permission_event_stops_frame_delivery_without_waiting_for_a_status_poll(self):
        await self.start();self.assertIsNotNone(self.bridge.latest)
        await self.bridge.helper.request({'type':'test-permission-event'})
        for _ in range(100):
            if self.bridge.state['state']=='permission-required':break
            await asyncio.sleep(.01)
        self.assertEqual(self.bridge.state['state'],'permission-required');self.assertIsNone(self.bridge.latest)

    async def test_old_authorized_generation_cannot_admit_a_replacement_window(self):
        initial=await self.start();old=initial['runtimeGeneration']
        await self.bridge.helper.request({'type':'test-replace'})
        with self.assertRaises(InvalidStatus) as rejected:
            async with connect('ws://127.0.0.1:'+str(self.value['port'])+'/websockify',origin=self.origin,
                    additional_headers={'Authorization':self.authorization,'X-AgentShell-Runtime-Generation':old},subprotocols=['binary']):pass
        self.assertEqual(rejected.exception.response.status_code,409)
        self.assertNotEqual(self.bridge.generation,old)


class NativeRuntimeCliTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_supervisor_cli_keeps_one_generation_and_browser_across_phone_desktop(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);helper=root/'helper';helper.write_text('#!'+sys.executable+'\n'+FAKE);helper.chmod(0o700)
            with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
            value={'provider':'macos-native','sessionId':str(uuid.uuid4()),'port':port,'password':'private-fixture-'+uuid.uuid4().hex,
                'runtimeDir':str(root/'runtime'),'userDataDir':str(root/'owned-profile'),'nativeHelper':str(helper),'pythonPath':sys.executable}
            path=root/'config.json';path.write_text(json.dumps(value));path.chmod(0o600)
            script=str(Path(__file__).with_name('browser-runtime-macos.py'))
            process=await asyncio.create_subprocess_exec(sys.executable,script,'--config',str(path),'serve',
                stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
            async def cli(command,*args):
                child=await asyncio.create_subprocess_exec(sys.executable,script,'--config',str(path),command,*args,
                    stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
                output,error=await asyncio.wait_for(child.communicate(),20)
                self.assertEqual(child.returncode,0,error.decode(errors='replace'))
                return json.loads(output)
            try:
                for _ in range(100):
                    if (root/'runtime/native-bridge.json').exists():break
                    if process.returncode is not None:raise AssertionError('Owned supervisor failed to start')
                    await asyncio.sleep(.02)
                before=await cli('status');self.assertEqual(before['state'],'stopped')
                self.assertFalse((root/'runtime/fake-native.jsonl').exists())
                phone=await cli('start');self.assertEqual(phone['state'],'ready');generation=phone['runtimeGeneration']
                desktop=await cli('native-desktop','--generation',generation);self.assertEqual(desktop['state'],'native')
                restored=await cli('native-phone','--generation',generation);self.assertEqual(restored['state'],'ready')
                self.assertEqual(restored['runtimeGeneration'],generation);self.assertEqual(restored['chromePid'],phone['chromePid'])
                self.assertEqual(restored['windowId'],phone['windowId'])
            finally:
                if process.returncode is None:process.terminate()
                await asyncio.wait_for(process.wait(),8)
            self.assertFalse((root/'runtime/native-bridge.json').exists())


if __name__=='__main__':unittest.main()

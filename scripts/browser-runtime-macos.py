#!/usr/bin/env python3
"""Private native macOS Chrome capture bridge. No personal profile is opened.

The HTTP daemon remains the public authentication boundary. This supervisor
speaks only authenticated loopback WebSockets and owns one Swift stdio child.
It never signals a PID read from disk and never quits Chrome.
"""
import argparse
import asyncio
import base64
import contextlib
import fcntl
import hashlib
import hmac
from http import HTTPStatus
import json
import math
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid

from process_identity import process_info

MAX_FRAME = 8 * 1024 * 1024
MAX_INPUT = 65536
MAX_PIXELS = 6_000_000
PROFILE_ID = 'managed:macos-native'


class RuntimeErrorCode(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def private_json(path, maximum=65536):
    metadata = path.lstat()
    if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid()
            or metadata.st_mode & 0o077 or metadata.st_size > maximum):
        raise RuntimeErrorCode('UNSAFE_RUNTIME_FILE')
    return json.loads(path.read_text())


def write_json(path, value):
    fd, temporary = tempfile.mkstemp(prefix='.native-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream);stream.write('\n');stream.flush();os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def executable(value):
    path = Path(value).expanduser()
    if not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
        raise RuntimeErrorCode('NATIVE_HELPER_UNAVAILABLE')
    if path.stat().st_uid not in (0, os.getuid()) or path.stat().st_mode & 0o022:
        raise RuntimeErrorCode('UNSAFE_RUNTIME_EXECUTABLE')
    return str(path.resolve())


def config(path):
    path = path.expanduser()
    if path.is_symlink():raise RuntimeErrorCode('UNSAFE_RUNTIME_FILE')
    path = path.resolve()
    value = private_json(path)
    if not isinstance(value, dict) or value.get('provider') != 'macos-native':
        raise RuntimeErrorCode('INVALID_RUNTIME_CONFIG')
    try:uuid.UUID(value['sessionId'])
    except (KeyError, ValueError, TypeError):raise RuntimeErrorCode('INVALID_RUNTIME_CONFIG') from None
    if type(value.get('port')) is not int or not 1024 <= value['port'] <= 65535:
        raise RuntimeErrorCode('INVALID_RUNTIME_PORT')
    password = value.get('password')
    if not isinstance(password, str) or not 32 <= len(password) <= 256 or not password.isascii() or any(ord(c) < 33 for c in password):
        raise RuntimeErrorCode('INVALID_RUNTIME_PASSWORD')
    for name in ('runtimeDir', 'userDataDir'):
        raw = value.get(name)
        if not isinstance(raw, str) or not Path(raw).expanduser().is_absolute():
            raise RuntimeErrorCode('INVALID_RUNTIME_CONFIG')
        original = Path(raw).expanduser()
        if original.is_symlink():raise RuntimeErrorCode('UNSAFE_RUNTIME_DIRECTORY')
        value[name] = str(original.resolve())
    directory = Path(value['runtimeDir'])
    if directory.exists() and (not directory.is_dir() or directory.stat().st_uid != os.getuid()):
        raise RuntimeErrorCode('UNSAFE_RUNTIME_DIRECTORY')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if directory.stat().st_mode & 0o077:raise RuntimeErrorCode('UNSAFE_RUNTIME_DIRECTORY')
    value['nativeHelper'] = executable(value.get('nativeHelper', ''))
    value['pythonPath'] = executable(value.get('pythonPath', sys.executable))
    value['_path'] = str(path)
    return value


def fingerprint(value):
    keys = ('sessionId','runtimeDir','userDataDir','port','password','nativeHelper','pythonPath')
    return hashlib.sha256(json.dumps({key:value[key] for key in keys}, sort_keys=True).encode()).hexdigest()


def own_profile(value):
    directory = Path(value['userDataDir']);marker = directory/'.agentshell-native-profile.json'
    if directory.exists():
        if not directory.is_dir() or directory.stat().st_uid != os.getuid():raise RuntimeErrorCode('UNSAFE_BROWSER_PROFILE')
        if marker.exists():
            record = private_json(marker)
            if record != {'sessionId':value['sessionId'],'provider':'macos-native'}:raise RuntimeErrorCode('PROFILE_NOT_OWNED')
            if directory.stat().st_mode & 0o077:raise RuntimeErrorCode('UNSAFE_BROWSER_PROFILE')
            return
        if any(directory.iterdir()):raise RuntimeErrorCode('PROFILE_NOT_OWNED')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    write_json(marker, {'sessionId':value['sessionId'],'provider':'macos-native'})


def record(value):
    try:
        item = private_json(Path(value['runtimeDir'])/'native-bridge.json')
        if item.get('configFingerprint') != fingerprint(value):return None
        pid = item.get('pid')
        if type(pid) is not int or process_info(pid)['generation'] != item.get('processGeneration'):return None
        uuid.UUID(item.get('serveNonce',item.get('runtimeGeneration','')))
        return item
    except (OSError, ValueError, KeyError, TypeError, RuntimeErrorCode):return None


def stopped(value, code=None):
    result = {'sessionId':value['sessionId'],'state':'stopped','displayName':value.get('displayName','AgentShell Chrome'),
              'permissions':{},'provider':'macos-native'}
    if code:result['code'] = code
    return result


def finite(value, maximum):
    return type(value) in (int,float) and math.isfinite(value) and abs(value) <= maximum


def viewer_command(message, dimensions):
    if not isinstance(message, dict):raise RuntimeErrorCode('INVALID_INPUT')
    kind = message.get('type');result = {'type':kind}
    if kind == 'release':return result
    if kind == 'resize':
        width,height = message.get('width'),message.get('height')
        if (type(width) is not int or type(height) is not int or not 100 <= width <= 2560
                or not 100 <= height <= 3840 or width*height > MAX_PIXELS):raise RuntimeErrorCode('INVALID_RESOLUTION')
        density = message.get('contentDensity',100)
        if type(density) is not int or density not in (75,100,125,150,200):raise RuntimeErrorCode('INVALID_RESOLUTION')
        return dict(result,width=width,height=height,contentDensity=density)
    if kind == 'text':
        text = message.get('text')
        if not isinstance(text,str) or not text or len(text)>8192 or '\0' in text:raise RuntimeErrorCode('INVALID_TEXT')
        method=message.get('method','paste')
        if method not in ('paste','unicode'):raise RuntimeErrorCode('INVALID_TEXT_METHOD')
        return dict(result,text=text,method=method)
    if kind == 'key':
        key = message.get('keysym')
        if type(key) is not int or not 1 <= key <= 0x10FFFFFF or type(message.get('down')) is not bool:raise RuntimeErrorCode('INVALID_KEY')
        return dict(result,keysym=key,down=message['down'])
    if kind == 'pointer':
        action = message.get('kind');x,y = message.get('x'),message.get('y')
        width,height = dimensions
        frame_width,frame_height=message.get('frameWidth'),message.get('frameHeight')
        if frame_width is not None or frame_height is not None:
            if (type(frame_width) is not int or type(frame_height) is not int or not 1<=frame_width<=8192
                    or not 1<=frame_height<=8192 or frame_width*frame_height>MAX_PIXELS):raise RuntimeErrorCode('INVALID_POINTER')
            # Coordinates belong to the painted frame. Swift rejects the input
            # if a newer capture changed its mapping before this event arrives.
            width,height=frame_width,frame_height
            result.update(frameWidth=frame_width,frameHeight=frame_height)
        if (action not in ('move','down','up','scroll') or not width or not height
                or not finite(x,width) or not finite(y,height) or x<0 or y<0 or x>=width or y>=height):raise RuntimeErrorCode('INVALID_POINTER')
        button = message.get('button',0)
        if type(button) is not int or button not in (0,1,2):raise RuntimeErrorCode('INVALID_POINTER')
        result.update(kind=action,x=x,y=y,button=button)
        if action == 'scroll':
            dx,dy = message.get('deltaX',0),message.get('deltaY',0)
            if not finite(dx,4096) or not finite(dy,4096):raise RuntimeErrorCode('INVALID_POINTER')
            result.update(deltaX=dx,deltaY=dy)
        return result
    raise RuntimeErrorCode('UNSUPPORTED_INPUT')


class NativeHelper:
    def __init__(self,value,frame,state,failed,started):
        self.value=value;self.frame=frame;self.state=state;self.failed=failed;self.started=started;self.closing=False;self.process=None;self.reader=None;self.pending={};self.serial=0
        self.lock=asyncio.Lock();self.write_lock=asyncio.Lock()

    async def ensure(self):
        if self.process and self.process.returncode is None and self.reader and not self.reader.done():return
        async with self.lock:
            if self.process and self.process.returncode is None and self.reader and not self.reader.done():return
            if self.reader:
                with contextlib.suppress(asyncio.CancelledError,Exception):await self.reader
            if self.process and self.process.returncode is None:
                self.process.terminate()
                try:await asyncio.wait_for(self.process.wait(),2)
                except asyncio.TimeoutError:
                    self.process.kill();await self.process.wait()
            self.closing=False
            self.process=await asyncio.create_subprocess_exec(self.value['nativeHelper'],'--config',self.value['_path'],'--stdio',
                stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL,limit=MAX_FRAME+1)
            self.started()
            self.reader=asyncio.create_task(self.read())

    async def read(self):
        process=self.process
        try:
            while True:
                line=await process.stdout.readline()
                if not line:break
                if len(line)>MAX_FRAME:raise RuntimeErrorCode('NATIVE_FRAME_TOO_LARGE')
                try:message=json.loads(line)
                except (ValueError,UnicodeError):continue
                if not isinstance(message,dict):continue
                if message.get('type')=='frame':await self.frame(message)
                elif message.get('type')=='state':self.state(message)
                elif isinstance(message.get('id'),str):
                    result=message.get('result')
                    if message.get('ok') is True and isinstance(result,dict) and 'state' in result:self.state(result)
                    future=self.pending.pop(message['id'],None)
                    if future and not future.done():
                        future.set_result(message)
                        # Let command state transitions retire old frames before the next line.
                        await asyncio.sleep(0)
        except (OSError,ValueError,RuntimeErrorCode):pass
        finally:
            for future in list(self.pending.values()):
                if not future.done():future.set_exception(RuntimeErrorCode('NATIVE_HELPER_DISCONNECTED'))
            self.pending.clear()
            if not self.closing and self.process is process:
                self.failed()
                if process.returncode is None:
                    with contextlib.suppress(ProcessLookupError):process.terminate()

    async def request(self,command,timeout=15):
        await self.ensure();self.serial+=1;identifier=command.get('id') or 'runtime-'+str(uuid.uuid4())
        if identifier in self.pending:
            response=await asyncio.wait_for(asyncio.shield(self.pending[identifier]),timeout)
            if response.get('ok') is not True:raise RuntimeErrorCode('NATIVE_COMMAND_FAILED')
            return response.get('result')
        future=asyncio.get_running_loop().create_future();self.pending[identifier]=future
        try:
            async with self.write_lock:
                self.process.stdin.write((json.dumps(dict(command,id=identifier),ensure_ascii=False)+'\n').encode())
                await asyncio.wait_for(self.process.stdin.drain(),2)
            response=await asyncio.wait_for(future,timeout)
            if response.get('ok') is not True:
                error=response.get('error');code=error.get('code') if isinstance(error,dict) else error
                raise RuntimeErrorCode(code if isinstance(code,str) and len(code)<80 and code.replace('_','').isalnum() else 'NATIVE_COMMAND_FAILED')
            return response.get('result')
        except asyncio.TimeoutError:raise RuntimeErrorCode('NATIVE_HELPER_TIMEOUT') from None
        except (OSError,ConnectionError):raise RuntimeErrorCode('NATIVE_HELPER_DISCONNECTED') from None
        finally:self.pending.pop(identifier,None)

    async def close(self):
        self.closing=True
        if self.process and self.process.returncode is None:
            with contextlib.suppress(Exception):await self.request({'type':'release'},2)
            with contextlib.suppress(Exception):await self.request({'type':'stop'},2)
            self.process.stdin.close()
            try:await asyncio.wait_for(self.process.wait(),2)
            except asyncio.TimeoutError:
                # Only signal the child handle this supervisor created; never a PID from disk.
                self.process.terminate()
                try:await asyncio.wait_for(self.process.wait(),2)
                except asyncio.TimeoutError:
                    self.process.kill();await self.process.wait()
        if self.reader:self.reader.cancel()


class Bridge:
    def __init__(self,value):
        self.value=value;self.serve_nonce=str(uuid.uuid4());self.generation=self.serve_nonce;self.pin=None;self.helper_epoch=0
        self.helper=NativeHelper(value,self.accept_frame,self.update,self.helper_failed,self.helper_started);self.capture_epoch=0;self.latest_epoch=0
        self.state=stopped(value);self.latest=None;self.sequence=0;self.dimensions=(0,0);self.condition=asyncio.Condition()
        self.viewer=None;self.viewer_lock=asyncio.Lock();self.runtime_lock=asyncio.Lock()

    def public_state(self):
        return dict(self.state,sessionId=self.value['sessionId'],runtimeGeneration=self.generation,
                    displayName=self.value.get('displayName','AgentShell Chrome'),provider='macos-native')

    def rotate_generation(self):
        identity='none' if self.pin is None else ':'.join(map(str,self.pin))
        self.generation=str(uuid.uuid5(uuid.UUID(self.serve_nonce),str(self.helper_epoch)+':'+identity))
        self.capture_epoch+=1;self.latest=None;self.dimensions=(0,0)
        if self.viewer:asyncio.create_task(self.viewer.close(1008,'Browser identity changed'))

    def helper_started(self):
        self.helper_epoch+=1;self.rotate_generation()

    def update(self,result):
        if not isinstance(result,dict):raise RuntimeErrorCode('INVALID_NATIVE_STATE')
        if result.get('state') not in ('ready','native','native-closed','permission-required','stopped'):raise RuntimeErrorCode('INVALID_NATIVE_STATE')
        permissions=result.get('permissions',{})
        if result['state']=='ready' and (not isinstance(permissions,dict) or permissions.get('screenCapture') is not True or permissions.get('accessibility') is not True):
            result=dict(result,state='permission-required',code='NATIVE_PERMISSIONS_REQUIRED')
        previous=self.state.get('state')
        identity=tuple(result.get(key) for key in ('chromePid','chromeStart','windowId'))
        if result['state']=='native-closed' and self.pin is not None:
            identity=(self.pin[0],self.pin[1],0)
            result=dict(result,windowId=0)
        if all(type(value) is int and value>=0 for value in identity) and identity[0]>0 and identity[1]>0:
            if identity!=self.pin:
                self.pin=identity
                self.rotate_generation()
        self.state={key:result[key] for key in ('state','permissions','code','chromePid','chromeStart','windowId','width','height') if key in result}
        if previous!=result['state']:self.capture_epoch+=1
        if result['state']!='ready':
            self.latest=None;self.dimensions=(0,0)
            if self.viewer:asyncio.create_task(self.viewer.close(1008,'Browser capture unavailable'))
        return self.public_state()

    def helper_failed(self):
        self.helper_epoch+=1;self.rotate_generation()
        self.update({'state':'stopped','code':'NATIVE_HELPER_DISCONNECTED','permissions':{}})

    async def accept_frame(self,message):
        if self.state.get('state')!='ready':return
        width,height=message.get('width'),message.get('height');data=message.get('data')
        if (type(width) is not int or type(height) is not int or not 1<=width<=8192 or not 1<=height<=8192
                or width*height>MAX_PIXELS or not isinstance(data,str) or len(data)>MAX_FRAME-1024):return
        try:
            raw=base64.b64decode(data,validate=True)
            if len(raw)<4 or not raw.startswith(b'\xff\xd8') or not raw.endswith(b'\xff\xd9'):return
        except (ValueError,TypeError):return
        # There is one shared latest frame, not a queue per viewer or a replay history.
        self.sequence+=1;self.dimensions=(width,height)
        self.latest_epoch=self.capture_epoch
        self.latest=json.dumps({'type':'frame','width':width,'height':height,'data':data,'sequence':self.sequence},separators=(',',':'))
        async with self.condition:self.condition.notify_all()

    async def control(self,message):
        command=message.get('type')
        if command=='runtime-status':
            if self.helper.process and self.helper.process.returncode is None:
                return self.update(await self.helper.request({'type':'status'},3))
            return self.public_state()
        async with self.runtime_lock:
            if message.get('generation') not in (None,self.generation):raise RuntimeErrorCode('RUNTIME_CHANGED')
            if command=='runtime-start':
                before=self.update(await self.helper.request({'type':'status'},3))
                permissions=before.get('permissions',{})
                if before['state']=='permission-required' or not isinstance(permissions,dict) or permissions.get('screenCapture') is not True or permissions.get('accessibility') is not True:
                    return dict(before,state='permission-required',code=before.get('code','NATIVE_PERMISSIONS_REQUIRED'))
                own_profile(self.value)
                return self.update(await self.helper.request({'type':'start'}))
            if command=='runtime-stop':
                if self.helper.process and self.helper.process.returncode is None:
                    await self.helper.request({'type':'release'},3)
                    result=await self.helper.request({'type':'stop'},5)
                    self.update(result)
                self.state['state']='stopped';self.latest=None;self.dimensions=(0,0)
                if self.viewer:await self.viewer.close(1000,'Capture stopped')
                return self.public_state()
            if command=='runtime-handoff':
                destination=message.get('destination')
                if destination not in ('desktop','phone'):raise RuntimeErrorCode('INVALID_DESTINATION')
                before=self.update(await self.helper.request({'type':'status'},3))
                if message.get('generation')!=self.generation:raise RuntimeErrorCode('RUNTIME_CHANGED')
                if destination=='phone':
                    permissions=before.get('permissions',{})
                    if not isinstance(permissions,dict) or permissions.get('screenCapture') is not True or permissions.get('accessibility') is not True:
                        return dict(before,state='permission-required',connected=False,code=before.get('code','NATIVE_PERMISSIONS_REQUIRED'))
                state=self.update(await self.helper.request({'type':'handoff','destination':destination}))
                connected=state['state']==('native' if destination=='desktop' else 'ready')
                return dict(state,connected=connected,code='CONNECTED' if connected else state.get('code','NATIVE_HANDOFF_FAILED'),session=state)
        raise RuntimeErrorCode('UNSUPPORTED_CONTROL')

    async def authorized(self,connection,request):
        expected='Basic '+base64.b64encode(('viewer:'+self.value['password']).encode()).decode()
        auth=request.headers.get('Authorization','')
        origin='http://127.0.0.1:'+str(self.value['port'])
        if request.path not in ('/control','/websockify') or request.headers.get('Origin')!=origin or not hmac.compare_digest(auth,expected):
            return connection.respond(HTTPStatus.UNAUTHORIZED,'Unauthorized\n')
        if 'binary' not in [part.strip() for part in request.headers.get('Sec-WebSocket-Protocol','').split(',')]:
            return connection.respond(HTTPStatus.BAD_REQUEST,'Binary subprotocol required\n')
        if request.path=='/websockify':
            try:
                if self.helper.process and self.helper.process.returncode is None:
                    self.update(await self.helper.request({'type':'status'},3))
                expected=request.headers.get('X-AgentShell-Runtime-Generation','')
                if self.state.get('state')!='ready' or expected!=self.generation:
                    return connection.respond(HTTPStatus.CONFLICT,'Browser generation changed\n')
            except RuntimeErrorCode:
                return connection.respond(HTTPStatus.SERVICE_UNAVAILABLE,'Browser unavailable\n')

    async def write_frames(self,connection):
        try:
            last=0
            while True:
                async with self.condition:
                    await self.condition.wait_for(lambda:self.sequence!=last and self.latest is not None)
                    frame=self.latest;last=self.sequence;epoch=self.latest_epoch
                if self.state.get('state')!='ready' or epoch!=self.capture_epoch:continue
                await asyncio.wait_for(connection.send(frame),3)
        except asyncio.TimeoutError:
            await connection.close(1013,'Viewer too slow')

    async def connection(self,connection):
        from websockets.exceptions import ConnectionClosed
        if connection.request.path=='/control':
            async for raw in connection:
                identifier=None
                try:
                    message=json.loads(raw)
                    if not isinstance(message,dict):raise RuntimeErrorCode('INVALID_CONTROL')
                    identifier=message.get('id')
                    result=await self.control(message)
                    await connection.send(json.dumps({'id':identifier,'ok':True,'result':result}))
                except (ValueError,TypeError,RuntimeErrorCode) as error:
                    await connection.send(json.dumps({'id':identifier,'ok':False,'error':getattr(error,'code','INVALID_CONTROL')}))
            return
        async with self.viewer_lock:
            if self.viewer is not None:
                await connection.close(1008,'Viewer already connected');return
            if self.state.get('state')!='ready':
                await connection.close(1008,'Browser is not ready');return
            self.viewer=connection
            admitted_generation=self.generation
        frames=asyncio.create_task(self.write_frames(connection))
        try:
            await connection.send(json.dumps({'type':'state','state':'connected','runtimeGeneration':self.generation}))
            async for raw in connection:
                identifier=None
                try:
                    message=json.loads(raw)
                    if not isinstance(message,dict):raise RuntimeErrorCode('INVALID_INPUT')
                    identifier=message.get('id')
                    if not isinstance(identifier,str) or not 1<=len(identifier)<=128:raise RuntimeErrorCode('INVALID_INPUT')
                    self.update(await self.helper.request({'type':'status'},3))
                    if admitted_generation!=self.generation:raise RuntimeErrorCode('RUNTIME_CHANGED')
                    if self.state.get('state')!='ready':raise RuntimeErrorCode('BROWSER_NOT_READY')
                    command=viewer_command(message,self.dimensions)
                    command['id']=identifier
                    result=await self.helper.request(command,5)
                    await connection.send(json.dumps({'type':'input-result','id':identifier,'ok':True,'result':result}))
                except (ValueError,TypeError,RuntimeErrorCode) as error:
                    await connection.send(json.dumps({'type':'input-result','id':identifier,'ok':False,'error':getattr(error,'code','INVALID_INPUT')}))
        except ConnectionClosed:pass
        finally:
            frames.cancel()
            with contextlib.suppress(asyncio.CancelledError,Exception):await frames
            with contextlib.suppress(Exception):
                if self.helper.process and self.helper.process.returncode is None:await self.helper.request({'type':'release'},2)
            async with self.viewer_lock:
                if self.viewer is connection:self.viewer=None


async def control(value,command,**fields):
    from websockets.asyncio.client import connect
    item=record(value)
    if item is None:return None
    header='Basic '+base64.b64encode(('viewer:'+value['password']).encode()).decode()
    async with connect('ws://127.0.0.1:'+str(value['port'])+'/control',origin='http://127.0.0.1:'+str(value['port']),
            additional_headers={'Authorization':header},subprotocols=['binary'],open_timeout=2,max_size=65536) as connection:
        identifier=str(uuid.uuid4());await connection.send(json.dumps(dict(fields,type=command,id=identifier)))
        reply=json.loads(await asyncio.wait_for(connection.recv(),18))
        if reply.get('id')!=identifier or reply.get('ok') is not True:raise RuntimeErrorCode(reply.get('error','NATIVE_CONTROL_FAILED'))
        return reply['result']


async def ensure_server(value):
    try:
        if await control(value,'runtime-status') is not None:return
    except Exception:pass
    path=Path(value['runtimeDir'])/'native-bridge.log'
    descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_APPEND|getattr(os,'O_NOFOLLOW',0),0o600)
    metadata=os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid!=os.getuid() or metadata.st_mode & 0o077:
        os.close(descriptor);raise RuntimeErrorCode('UNSAFE_RUNTIME_FILE')
    with os.fdopen(descriptor,'ab') as log:
        child=subprocess.Popen([value['pythonPath'],str(Path(__file__).resolve()),'--config',value['_path'],'serve'],
            stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    for _ in range(50):
        await asyncio.sleep(.1)
        try:
            if await control(value,'runtime-status') is not None:return
        except Exception:pass
        if child.poll() is not None:break
    raise RuntimeErrorCode('NATIVE_BRIDGE_START_FAILED')


async def serve(value):
    from websockets.asyncio.server import serve as websocket_server
    directory=Path(value['runtimeDir']);lock_path=directory/'native-bridge.lock'
    descriptor=os.open(lock_path,os.O_WRONLY|os.O_CREAT|getattr(os,'O_NOFOLLOW',0),0o600)
    metadata=os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid!=os.getuid() or metadata.st_mode & 0o077:
        os.close(descriptor);raise RuntimeErrorCode('UNSAFE_RUNTIME_FILE')
    with os.fdopen(descriptor,'a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeErrorCode('NATIVE_BRIDGE_ALREADY_OWNED') from None
        bridge=Bridge(value);ended=asyncio.Event();loop=asyncio.get_running_loop()
        for event in (signal.SIGINT,signal.SIGTERM):loop.add_signal_handler(event,ended.set)
        try:
            async with websocket_server(bridge.connection,'127.0.0.1',value['port'],process_request=bridge.authorized,
                    subprotocols=['binary'],max_size=MAX_INPUT,max_queue=16,write_limit=32768,ping_interval=15,ping_timeout=10):
                write_json(directory/'native-bridge.json',{'pid':os.getpid(),'processGeneration':process_info(os.getpid())['generation'],
                    'serveNonce':bridge.serve_nonce,'configFingerprint':fingerprint(value)})
                await ended.wait()
        finally:
            await bridge.helper.close()
            path=directory/'native-bridge.json'
            try:
                item=private_json(path)
                if item.get('serveNonce',item.get('runtimeGeneration'))==bridge.serve_nonce:path.unlink()
            except (OSError,ValueError,RuntimeErrorCode):pass


async def run(args,value):
    command=args.command
    if command=='serve':await serve(value);return None
    if command=='profiles':
        state=await control(value,'runtime-status') or stopped(value)
        return {'profiles':[{'id':PROFILE_ID,'name':'AgentShell Chrome','location':'managed','status':state['state'],'selected':True,'canSelect':False}],
                'selectedProfileId':PROFILE_ID,'canSelect':False,'profileSelection':False}
    if command=='select-profile':
        return {'connected':False,'code':'PROFILE_SELECTION_UNSUPPORTED','selectedProfileId':PROFILE_ID}
    if command=='status':return await control(value,'runtime-status') or stopped(value)
    if command=='start':
        await ensure_server(value)
        return await control(value,'runtime-start')
    if command=='stop':return await control(value,'runtime-stop') or stopped(value)
    if command in ('native-desktop','native-phone'):
        if not args.generation:raise RuntimeErrorCode('RUNTIME_CHANGED')
        result=await control(value,'runtime-handoff',generation=args.generation,destination='desktop' if command=='native-desktop' else 'phone')
        if result is None:raise RuntimeErrorCode('RUNTIME_CHANGED')
        return result
    raise RuntimeErrorCode('UNSUPPORTED_COMMAND')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--generation');parser.add_argument('--profile-id');parser.add_argument('--confirmation-token')
    parser.add_argument('command',choices=['status','start','stop','profiles','select-profile','native-desktop','native-phone','serve'])
    args=parser.parse_args()
    try:
        value=config(args.config);result=asyncio.run(run(args,value))
        if result is not None:print(json.dumps(result,separators=(',',':')))
    except Exception as error:
        print(json.dumps({'state':'stopped','connected':False,'code':getattr(error,'code','INVALID_RUNTIME_CONFIG')}))


if __name__=='__main__':main()

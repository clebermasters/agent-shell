#!/usr/bin/python3
"""Restart the SAME managed Chrome profile between VNC and the real desktop.

Only a generation-verified, recorded owner can be closed. No live profile copies,
singleton deletion, DevTools exposure, sandbox changes or forced handoff kills.
"""
import argparse
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid


def load_runtime(path):
    spec=importlib.util.spec_from_file_location('runtime',path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def secret_preflight(value, runtime):
    if not value.get('requireSecretService'): return
    environment=dict(os.environ, XDG_RUNTIME_DIR=f'/run/user/{os.getuid()}',
        DBUS_SESSION_BUS_ADDRESS=f'unix:path=/run/user/{os.getuid()}/bus')
    result=subprocess.run(['busctl','--user','get-property','org.freedesktop.secrets',
        '/org/freedesktop/secrets/aliases/default','org.freedesktop.Secret.Collection','Locked'],
        env=environment,capture_output=True,text=True,timeout=5)
    if result.returncode or result.stdout.strip()!='b false':
        raise runtime.RuntimeErrorCode('CREDENTIAL_STORE_LOCKED')


def quit_chrome(value, record, runtime, timeout=15):
    pid=record.get('chromePid');expected=record.get('chromeGeneration')
    if not pid or not expected or runtime.generation(pid)!=expected or runtime.profile_owner(value['userDataDir'])!=pid:
        raise runtime.RuntimeErrorCode('PROFILE_OWNER_MISMATCH')
    marker=Path(value['runtimeDir'])/'close-request.json'
    if runtime.read_json(marker).get('chromeGeneration')==expected:
        # Chromium's shutdown signal handler is one-shot. Never send a second
        # signal after an unload/download prompt prevented the first exit.
        raise runtime.RuntimeErrorCode('CHROME_CLOSE_BLOCKED')
    # Bind the signal to this process lifetime, not a recyclable numeric PID.
    descriptor=os.pidfd_open(pid)
    try:
        if runtime.generation(pid)!=expected or runtime.profile_owner(value['userDataDir'])!=pid:
            raise runtime.RuntimeErrorCode('PROFILE_OWNER_MISMATCH')
        runtime.write_json(marker,{'chromeGeneration':expected})
        signal.pidfd_send_signal(descriptor,signal.SIGINT)
    finally:os.close(descriptor)
    expires=time.monotonic()+timeout
    while time.monotonic()<expires:
        if runtime.generation(pid)!=expected and runtime.profile_owner(value['userDataDir']) is None:
            marker.unlink(missing_ok=True)
            return
        time.sleep(.1)
    raise runtime.RuntimeErrorCode('CHROME_CLOSE_BLOCKED')


def wait_supervisor(record, runtime):
    expires=time.monotonic()+6
    while record.get('pid') and runtime.generation(record['pid'])==record.get('processGeneration'):
        if time.monotonic()>=expires: raise runtime.RuntimeErrorCode('RUNTIME_STOP_TIMEOUT')
        time.sleep(.1)


def clean_exit(value,runtime,profiles=None):
    metadata=runtime.profile_metadata(value['userDataDir'])
    selected=value.get('profileDirectory') or metadata.get('last_used')
    if profiles is None: profiles=[selected] if selected else metadata.get('last_active_profiles',[])
    if not profiles and len(metadata.get('info_cache',{}))==1:profiles=list(metadata['info_cache'])
    if not profiles:return False
    for profile in profiles:
        if not isinstance(profile,str) or '/' in profile or '\\' in profile or profile in ('','.','..'):return False
        preferences=runtime.read_json(Path(value['userDataDir'])/profile/'Preferences').get('profile',{})
        if not isinstance(preferences,dict) or preferences.get('exit_type')!='Normal':return False
    return True


def start_native(value, path, runtime):
    directory=Path(value['runtimeDir'])
    previous_generation=runtime.read_json(directory/'native.json').get('runtimeGeneration')
    if value.get('supervision','systemd-user')=='systemd-user':
        if runtime.user_systemctl('start','agentshell-browser-native.service').returncode:
            raise runtime.RuntimeErrorCode('USER_SERVICE_UNAVAILABLE')
    else:
        with (directory/'native.log').open('ab') as log:
            subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'--config',str(path),'run'],
                stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    for _ in range(100):
        state=runtime.native_status(value)
        if state and state.get('state')=='native':return state
        record=runtime.read_json(directory/'native.json')
        if record.get('runtimeGeneration')!=previous_generation and record.get('code') and not runtime.generation(record.get('pid')):
            raise runtime.RuntimeErrorCode(record['code'])
        time.sleep(.1)
    raise runtime.RuntimeErrorCode('NATIVE_START_FAILED')


def start_virtual(value, path, runtime):
    runtime.write_json(Path(value['runtimeDir'])/'restore-next.json',{'restore':True})
    state=runtime.start(value,path,ignore_switch=True)
    if state.get('state')!='ready':raise runtime.RuntimeErrorCode(state.get('code','VIRTUAL_START_FAILED'))
    return state


def move(value, path, destination, expected, runtime):
    directory=Path(value['runtimeDir']);directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    if not (os.environ.get('DISPLAY') or os.environ.get('WAYLAND_DISPLAY')):
        raise runtime.RuntimeErrorCode('DESKTOP_UNAVAILABLE')
    import gi
    gi.require_version('Gtk','3.0')
    from gi.repository import Gtk
    if not Gtk.init_check()[0]:raise runtime.RuntimeErrorCode('DESKTOP_UNAVAILABLE')
    secret_preflight(value,runtime)
    with (directory/'native-switch.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise runtime.RuntimeErrorCode('HANDOFF_IN_PROGRESS')
        current=runtime.status(value,ignore_switch=True)
        if current.get('runtimeGeneration')!=expected:
            raise runtime.RuntimeErrorCode('RUNTIME_CHANGED')
        source_native=current.get('state') in ('native','native-closed')
        already_closed=current.get('state')=='native-closed'
        if (destination=='desktop' and source_native) or (destination=='phone' and current.get('state')=='ready'):
            return {'code':'CONNECTED','connected':True,'destination':'native' if source_native else 'phone','session':current}
        if current.get('state') not in ('ready','native','native-closed'):
            raise runtime.RuntimeErrorCode('BROWSER_NOT_READY')
        record=runtime.read_json(directory/('native.json' if source_native else 'runtime.json'))
        switch={'pid':os.getpid(),'processGeneration':runtime.generation(os.getpid()),'destination':destination}
        runtime.write_json(directory/'native-switch.json',switch)
        closed=False
        try:
            if not already_closed:quit_chrome(value,record,runtime)
            closed=True
            wait_supervisor(record,runtime)
            if not clean_exit(value,runtime):raise runtime.RuntimeErrorCode('UNCLEAN_CHROME_EXIT')
            # systemd is stopped only AFTER Chrome has exited normally. It must
            # never signal/kill the browser to bypass a close confirmation.
            if value.get('supervision','systemd-user')=='systemd-user':
                unit='agentshell-browser-native.service' if source_native else 'agentshell-browser.service'
                if runtime.user_systemctl('stop',unit).returncode:raise runtime.RuntimeErrorCode('USER_SERVICE_UNAVAILABLE')
            state=start_native(value,path,runtime) if destination=='desktop' else start_virtual(value,path,runtime)
            return {'code':'CONNECTED','connected':True,'normalExit':True,'destination':'native' if destination=='desktop' else 'phone','session':state}
        except Exception as error:
            code=error.code if isinstance(error,runtime.RuntimeErrorCode) else 'NATIVE_HANDOFF_FAILED'
            if closed and runtime.profile_owner(value['userDataDir']) is None:
                try:
                    state=start_native(value,path,runtime) if source_native else start_virtual(value,path,runtime)
                except Exception:
                    state=runtime.status(value,ignore_switch=True)
            else:state=runtime.status(value,ignore_switch=True)
            return {'code':code,'connected':False,'session':state,'phonePreserved':not closed and not source_native}
        finally:
            (directory/'native-switch.json').unlink(missing_ok=True)


def run(value, runtime):
    directory=Path(value['runtimeDir']);directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    stopped=False;chrome=None
    def terminate(*_):
        nonlocal stopped
        stopped=True
    signal.signal(signal.SIGTERM,terminate);signal.signal(signal.SIGINT,terminate)
    with (directory/'native-owner.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return
        record={'pid':os.getpid(),'processGeneration':runtime.generation(os.getpid()),'state':'starting',
            'runtimeGeneration':str(uuid.uuid4()),'configFingerprint':runtime.fingerprint(value)}
        runtime.write_json(directory/'native.json',record)
        try:
            if os.getuid()==0:raise runtime.RuntimeErrorCode('CHROME_MUST_RUN_AS_USER')
            if runtime.profile_owner(value['userDataDir']) is not None:raise runtime.RuntimeErrorCode('PROFILE_IN_USE')
            secret_preflight(value,runtime)
            with (directory/'native-chrome.log').open('ab') as log:
                chrome=subprocess.Popen(runtime.chrome_command(value,restore=True),env=dict(os.environ),stdout=log,stderr=log)
                for _ in range(100):
                    if chrome.poll() is not None:raise runtime.RuntimeErrorCode('NATIVE_START_FAILED')
                    if runtime.profile_owner(value['userDataDir'])==chrome.pid:
                        record.update(state='ready',chromePid=chrome.pid,chromeGeneration=runtime.generation(chrome.pid))
                        runtime.write_json(directory/'native.json',record)
                        runtime.write_json(directory/'native-location.json',{'location':'native'})
                        break
                    time.sleep(.1)
                else:raise runtime.RuntimeErrorCode('PROFILE_OWNER_MISMATCH')
                while not stopped and chrome.poll() is None:time.sleep(.25)
                if stopped and chrome.poll() is None:
                    quit_chrome(value,record,runtime)
                    chrome.wait(timeout=5)
        except runtime.RuntimeErrorCode as error:record['code']=error.code
        except Exception:record['code']='NATIVE_START_FAILED'
        finally:
            if chrome and chrome.poll() is None:
                # A refused close is kept owned and visible; never orphan its
                # identity and accidentally launch a second profile owner.
                while chrome.poll() is None:time.sleep(.5)
            record['state']='stopped';runtime.write_json(directory/'native.json',record)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--generation')
    parser.add_argument('--desktop-config',type=Path)
    parser.add_argument('--from-app',action='store_true')
    parser.add_argument('command',choices=['run','desktop','phone'])
    args=parser.parse_args()
    try:
        raw=json.loads(args.config.read_text());runtime=load_runtime(raw.get('runner',str(Path(__file__).with_name('browser-runtime.py'))))
        value=runtime.config(args.config)
        if args.command=='run':run(value,runtime);return
        if args.from_app:
            import socket
            spec=importlib.util.spec_from_file_location('desktop',Path(__file__).with_name('browser-desktop.py'))
            desktop=importlib.util.module_from_spec(spec);spec.loader.exec_module(desktop)
            cfg=desktop.Config(args.desktop_config)
            with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as client:
                client.settimeout(70)
                try:client.connect(str(cfg.state/'desktop-agent.sock'))
                except (FileNotFoundError,ConnectionRefusedError):raise runtime.RuntimeErrorCode('DESKTOP_UNAVAILABLE')
                client.sendall(json.dumps({'command':'native-'+args.command,'generation':args.generation}).encode()+b'\n')
                result=desktop.receive(client)
        else:result=move(value,args.config,args.command,args.generation,runtime)
    except Exception as error:
        result={'code':getattr(error,'code','NATIVE_HANDOFF_FAILED'),'connected':False}
    print(json.dumps(result))


if __name__=='__main__':main()

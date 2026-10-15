#!/usr/bin/env python3
"""Supervise one Chrome-only KasmVNC display; never seize an existing profile."""
import argparse
import contextlib
import fcntl
import json
import hashlib
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid


class RuntimeErrorCode(Exception):
    def __init__(self, code): self.code = code


def read_json(path):
    try: return json.loads(Path(path).read_text())
    except (OSError, ValueError): return {}


def write_json(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name('.'+path.name+'.'+secrets.token_hex(8))
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
            json.dump(value, stream); stream.write('\n')
            stream.flush(); os.fsync(stream.fileno())
        temporary.replace(path)
        descriptor = os.open(path.parent, os.O_DIRECTORY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
    finally: temporary.unlink(missing_ok=True)


def generation(pid):
    try:
        path = Path('/proc')/str(pid)
        if path.stat().st_uid != os.getuid(): return None
        fields = (path/'stat').read_text().rsplit(')', 1)[1].split()
        if fields[0] == 'Z': return None
        return Path('/proc/sys/kernel/random/boot_id').read_text().strip()+':'+fields[19]
    except (OSError, ValueError): return None


def profile_owner(directory):
    lock = Path(directory)/'SingletonLock'
    if not lock.is_symlink():
        return 'unknown' if lock.exists() else None
    try:
        host, pid = os.readlink(lock).rsplit('-', 1)
        if host != socket.gethostname() or not pid.isdigit(): return 'unknown'
        if not Path('/proc', pid).exists(): return None
        return int(pid)  # A different UID or inaccessible live owner still blocks startup.
    except (OSError, ValueError): return 'unknown'


def config(path):
    value = read_json(path)
    required = ['sessionId', 'userDataDir', 'runtimeDir', 'display', 'port', 'password']
    if any(k not in value for k in required): raise RuntimeErrorCode('BROWSER_NOT_CONFIGURED')
    uuid.UUID(value['sessionId'])
    if not 20 <= value['display'] <= 200 or not 1024 <= value['port'] <= 65535: raise RuntimeErrorCode('INVALID_RUNTIME_CONFIG')
    profile = value.get('profileDirectory')
    if profile is not None and (not isinstance(profile, str) or not re.fullmatch(r'[A-Za-z0-9 _-]{1,64}', profile)):
        raise RuntimeErrorCode('INVALID_PROFILE')
    value['userDataDir'] = str(Path(value['userDataDir']).resolve())
    value['runtimeDir'] = str(Path(value['runtimeDir']).resolve())
    return value


def native_status(value):
    record = read_json(Path(value['runtimeDir'])/'native.json')
    if not record.get('pid') or generation(record['pid']) != record.get('processGeneration'):
        location=read_json(Path(value['runtimeDir'])/'native-location.json')
        virtual=read_json(Path(value['runtimeDir'])/'runtime.json')
        virtual_live=virtual.get('pid') and generation(virtual['pid'])==virtual.get('processGeneration')
        if location.get('location')=='native' and record.get('configFingerprint')==fingerprint(value) and record.get('runtimeGeneration') and not virtual_live and profile_owner(value['userDataDir']) is None:
            return {'state':'native-closed','sessionId':value['sessionId'],'runtimeGeneration':record['runtimeGeneration']}
        return None
    if record.get('configFingerprint') != fingerprint(value):
        return {'state':'configuration-changed','code':'RUNTIME_CONFIGURATION_CHANGED','sessionId':value['sessionId']}
    if record.get('state') == 'ready' and record.get('chromeGeneration') and generation(record.get('chromePid')) == record.get('chromeGeneration'):
        return {'state':'native','sessionId':value['sessionId'],'runtimeGeneration':record['runtimeGeneration']}
    return {'state':'native-starting','sessionId':value['sessionId']}


def status(value, ignore_switch=False):
    if not ignore_switch:
        switch = read_json(Path(value['runtimeDir'])/'native-switch.json')
        if switch.get('pid') and generation(switch['pid']) == switch.get('processGeneration'):
            return {'state':'switching-native','sessionId':value['sessionId']}
    native = native_status(value)
    if native: return native
    record = read_json(Path(value['runtimeDir'])/'runtime.json')
    live = bool(record.get('pid') and generation(record['pid']) == record.get('processGeneration'))
    if live and record.get('configFingerprint') != fingerprint(value):
        return {'state':'configuration-changed', 'code':'RUNTIME_CONFIGURATION_CHANGED', 'sessionId':value['sessionId']}
    if live and record.get('state') == 'ready':
        if not record.get('chromeGeneration') or generation(record.get('chromePid')) != record.get('chromeGeneration'):
            return {'state':'failed', 'code':'CHROME_EXITED', 'sessionId':value['sessionId']}
        return {'state':'ready', 'sessionId':value['sessionId'], 'runtimeGeneration':record['runtimeGeneration']}
    if live: return {'state':'starting', 'sessionId':value['sessionId']}
    if profile_owner(value['userDataDir']) is not None:
        return {'state':'profile-in-use', 'code':'PROFILE_IN_USE', 'sessionId':value['sessionId']}
    return {'state':'stopped', 'sessionId':value['sessionId'], **({'code':record['code']} if record.get('code') else {})}


def fingerprint(value):
    identity = {name:value.get(name) for name in ['sessionId','userDataDir','profileDirectory','display','port',
                'password','privateBus','requireSecretService']}
    return hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()


def user_systemctl(*args):
    environment = dict(os.environ, XDG_RUNTIME_DIR=f'/run/user/{os.getuid()}',
                       DBUS_SESSION_BUS_ADDRESS=f'unix:path=/run/user/{os.getuid()}/bus')
    return subprocess.run(['systemctl','--user',*args], env=environment, capture_output=True, text=True)


def start(value, path, ignore_switch=False):
    current = status(value, True) if ignore_switch else status(value)
    if current['state']=='native-closed':
        write_json(Path(value['runtimeDir'])/'restore-next.json',{'restore':True})
        current={'state':'stopped','sessionId':value['sessionId']}
    if current['state'] == 'ready': return current
    if current['state'] in ['profile-in-use','configuration-changed','native','native-starting','switching-native']: return current
    directory = Path(value['runtimeDir']); directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (directory/'start.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = status(value, True) if ignore_switch else status(value)
        if current['state']=='native-closed':current={'state':'stopped','sessionId':value['sessionId']}
        if current['state'] == 'ready': return current
        previous_generation = read_json(directory/'runtime.json').get('runtimeGeneration')
        already_starting = current['state'] == 'starting'
        if value.get('supervision','systemd-user') == 'systemd-user':
            if user_systemctl('start','agentshell-browser.service').returncode:
                raise RuntimeErrorCode('USER_SERVICE_UNAVAILABLE')
        elif current['state'] != 'starting':
            with (directory/'runtime.log').open('ab') as log:
                subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'--config',str(path),'run'],
                                 stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
        for _ in range(100):
            current = status(value, True) if ignore_switch else status(value)
            if current['state'] == 'ready': return current
            record = read_json(directory/'runtime.json')
            # systemctl start may return before the new Python process writes its
            # record. A previous launch's failure must not abort this attempt.
            if (already_starting or record.get('runtimeGeneration') != previous_generation) and record.get('code') and not generation(record.get('pid')): break
            time.sleep(.1)
        return current


def stop(value):
    if value.get('supervision','systemd-user') == 'systemd-user':
        if user_systemctl('stop','agentshell-browser.service').returncode: raise RuntimeErrorCode('USER_SERVICE_UNAVAILABLE')
    else:
        record = read_json(Path(value['runtimeDir'])/'runtime.json')
        if record.get('pid') and generation(record['pid']) == record.get('processGeneration'):
            os.kill(record['pid'],signal.SIGTERM)
            for _ in range(150):
                if generation(record['pid']) != record.get('processGeneration'): break
                time.sleep(.1)
            else: raise RuntimeErrorCode('STOP_TIMEOUT')
    return status(value)


OPENBOX = '''<openbox_config xmlns="http://openbox.org/3.4/rc"><focus><focusNew>yes</focusNew><followMouse>no</followMouse></focus>
<desktops><number>1</number></desktops><keyboard/><mouse/><menu/>
<applications><application class="Google-chrome"><decor>no</decor></application>
<application class="Google-chrome" role="browser"><maximized>yes</maximized></application></applications></openbox_config>'''


def credential_bus(env, directory, children, log):
    """Share the original keyring, without sharing desktop file-dialog portals."""
    original = env.get('DBUS_SESSION_BUS_ADDRESS', f'unix:path=/run/user/{os.getuid()}/bus')
    address = directory/'credential-bus.sock'
    address.unlink(missing_ok=True)  # This runtime holds owner.lock in its private directory.
    if not shutil.which('xdg-dbus-proxy'): raise RuntimeErrorCode('RUNTIME_DEPENDENCY_MISSING')
    proxy = subprocess.Popen(['xdg-dbus-proxy', original, str(address), '--filter',
                              '--talk=org.freedesktop.secrets'], stdout=log, stderr=log)
    children.append(proxy)
    for _ in range(50):
        if proxy.poll() is not None: raise RuntimeErrorCode('CREDENTIAL_BUS_START_FAILED')
        if address.exists():
            os.chmod(address, 0o600)
            env['DBUS_SESSION_BUS_ADDRESS'] = 'unix:path='+str(address)
            env['GTK_USE_PORTAL'] = '0'
            return
        time.sleep(.1)
    raise RuntimeErrorCode('CREDENTIAL_BUS_START_FAILED')


def check_port(port):
    with socket.socket() as probe:
        # A recent viewer leaves TIME_WAIT sockets after a graceful restart.
        # Match the listener's reuse policy, while still rejecting live listeners.
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try: probe.bind(('127.0.0.1', port))
        except OSError: raise RuntimeErrorCode('PORT_IN_USE')

def chrome_command(value, restore=False):
    command = ['google-chrome', '--user-data-dir='+value['userDataDir']]
    # Omitting the switch lets Chrome select its own default/last-used profile.
    # Only an explicitly configured selection pins a profile directory.
    if value.get('profileDirectory') is not None:
        command.append('--profile-directory='+value['profileDirectory'])
    if restore:
        return [*command, '--no-first-run', '--no-default-browser-check', '--restore-last-session']
    return [*command, '--no-first-run', '--no-default-browser-check', value.get('startUrl','about:blank')]


def run(value):
    directory = Path(value['runtimeDir']); directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory,0o700)
    children = []; stopped = False
    def terminate(*_):
        nonlocal stopped
        stopped = True
    signal.signal(signal.SIGTERM,terminate); signal.signal(signal.SIGINT,terminate)
    with (directory/'owner.lock').open('a') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise RuntimeErrorCode('RUNTIME_ALREADY_OWNED')
        record = {'pid':os.getpid(),'processGeneration':generation(os.getpid()),
                  'runtimeGeneration':str(uuid.uuid4()),'state':'starting','configFingerprint':fingerprint(value)}
        write_json(directory/'runtime.json',record)
        try:
            if profile_owner(value['userDataDir']) is not None: raise RuntimeErrorCode('PROFILE_IN_USE')
            for binary in ['Xvnc','openbox','google-chrome','xauth','kasmvncpasswd']:
                if not shutil.which(binary): raise RuntimeErrorCode('RUNTIME_DEPENDENCY_MISSING')
            if os.getuid() == 0: raise RuntimeErrorCode('CHROME_MUST_RUN_AS_USER')
            if Path(f'/tmp/.X11-unix/X{value["display"]}').exists(): raise RuntimeErrorCode('DISPLAY_IN_USE')
            # Check binding without inheriting a listening socket into Xvnc.
            check_port(value['port'])
            authority = directory/'Xauthority'; authority.touch(mode=0o600,exist_ok=True)
            subprocess.run(['xauth','-f',str(authority),'add',f':{value["display"]}','.',secrets.token_hex(16)],check=True,capture_output=True)
            passwd = directory/'kasmpasswd'
            subprocess.run(['kasmvncpasswd','-u','viewer','-w',str(passwd)],
                           input=value['password']+'\n'+value['password']+'\n',text=True,check=True,capture_output=True)
            os.chmod(passwd,0o600)
            env = dict(os.environ,DISPLAY=f':{value["display"]}',XAUTHORITY=str(authority))
            if value.get('privateBus',False): env.pop('DBUS_SESSION_BUS_ADDRESS',None)
            elif value.get('requireSecretService',False):
                env['DBUS_SESSION_BUS_ADDRESS']=f'unix:path=/run/user/{os.getuid()}/bus'
                result=subprocess.run(['busctl','--user','--list'],env=env,capture_output=True,text=True)
                if result.returncode or not any(line.split() and line.split()[0]=='org.freedesktop.secrets' for line in result.stdout.splitlines()):
                    raise RuntimeErrorCode('CREDENTIAL_STORE_UNAVAILABLE')
            (directory/'openbox.xml').write_text(OPENBOX)
            with (directory/'display.log').open('ab') as log:
                if not value.get('privateBus', False): credential_bus(env, directory, children, log)
                x = subprocess.Popen(['Xvnc',env['DISPLAY'],'-auth',str(authority),'-nolisten','tcp','-geometry','960x1200','-depth','24',
                    '-interface','127.0.0.1','-websocketPort',str(value['port']),'-httpd','/usr/share/kasmvnc/www',
                    '-KasmPasswordFile',str(passwd),'-SecurityTypes','None','-FrameRate','30','-MaxDisconnectionTime','0'],env=env,stdout=log,stderr=log)
                children.append(x)
                for _ in range(50):
                    if stopped or x.poll() is not None: raise RuntimeErrorCode('DISPLAY_START_FAILED')
                    if Path(f'/tmp/.X11-unix/X{value["display"]}').exists(): break
                    time.sleep(.1)
                else: raise RuntimeErrorCode('DISPLAY_START_TIMEOUT')
                wm=subprocess.Popen(['openbox','--config-file',str(directory/'openbox.xml')],env=env,stdout=log,stderr=log);children.append(wm)
                # Preserve the installed Chrome version, sandbox and credential-store behavior.
                restore = read_json(directory/'restore-next.json').get('restore') is True
                chrome=subprocess.Popen(chrome_command(value, restore=restore),env=env,stdout=log,stderr=log);children.append(chrome)
                for _ in range(100):
                    if stopped or chrome.poll() is not None: raise RuntimeErrorCode('CHROME_START_FAILED')
                    owner=profile_owner(value['userDataDir'])
                    if owner==chrome.pid:
                        record.update(state='ready',chromePid=chrome.pid,chromeGeneration=generation(chrome.pid))
                        write_json(directory/'runtime.json',record)
                        write_json(directory/'native-location.json',{'location':'virtual'})
                        (directory/'restore-next.json').unlink(missing_ok=True)
                        break
                    time.sleep(.1)
                else: raise RuntimeErrorCode('PROFILE_OWNER_MISMATCH')
                while not stopped:
                    if any(child.poll() is not None for child in children): raise RuntimeErrorCode('RUNTIME_EXITED')
                    time.sleep(.25)
        except RuntimeErrorCode as error:
            record['code']=error.code
        except Exception:
            record['code']='RUNTIME_START_FAILED'  # No credentials, browsing data or argv in error output.
        finally:
            for child in reversed(children):
                if child.poll() is None:
                    child.terminate()
                    try: child.wait(timeout=10)
                    except subprocess.TimeoutExpired: child.kill();child.wait(timeout=5)
            record['state']='stopped';write_json(directory/'runtime.json',record)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',type=Path,required=True);parser.add_argument('command',choices=['status','start','stop','run'])
    args=parser.parse_args()
    try:
        value=config(args.config)
        if args.command=='run': run(value);return
        result=status(value) if args.command=='status' else start(value,args.config) if args.command=='start' else stop(value)
    except (RuntimeErrorCode,ValueError) as error:
        result={'state':'failed','code':error.code if isinstance(error,RuntimeErrorCode) else 'INVALID_RUNTIME_CONFIG'}
    print(json.dumps(result))

if __name__=='__main__':main()

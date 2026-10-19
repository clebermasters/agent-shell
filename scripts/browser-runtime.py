#!/usr/bin/env python3
"""Supervise Chrome-only KasmVNC with explicit, verified profile handoffs."""
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
    try:
        value = json.loads(Path(path).read_text())
        return value if isinstance(value, dict) else {}
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
    value['_configuredUserDataDir'] = value['userDataDir']
    selection = read_json(Path(value['runtimeDir'])/'selected-profile.json')
    if (selection.get('sessionId') == value['sessionId'] and
            selection.get('configuredUserDataDir') == value['_configuredUserDataDir']):
        root = selection.get('userDataDir')
        profile = selection.get('profileDirectory')
        if isinstance(root, str) and root in {item['path'] for item in profile_roots(value)} and safe_profile(root, profile):
            value['userDataDir'] = root
            value['profileDirectory'] = profile
    return value



def profile_roots(value):
    """Paths are trusted local configuration, never an API-supplied directory."""
    configured = value.get('_configuredUserDataDir', value['userDataDir'])
    default = str((Path(os.environ.get('XDG_CONFIG_HOME', str(Path.home()/'.config')))/'google-chrome').resolve())
    roots = [{'path': configured, 'name': 'Computer Chrome' if configured == default else 'AgentShell'}]
    if value.get('includeDefaultProfiles', True): roots.append({'path': default, 'name': 'Computer Chrome'})
    extra = value.get('profileRoots', [])
    if not isinstance(extra, list): raise RuntimeErrorCode('INVALID_RUNTIME_CONFIG')
    for item in extra:
        if not isinstance(item, dict) or not isinstance(item.get('path'), str) or not Path(item['path']).is_absolute():
            raise RuntimeErrorCode('INVALID_RUNTIME_CONFIG')
        roots.append({'path': str(Path(item['path']).resolve()), 'name': display_name(item.get('name'), 'Chrome')})
    return list({item['path']: item for item in reversed(roots)}.values())[::-1]


def display_name(value, fallback):
    if not isinstance(value, str): return fallback
    clean = ''.join(character for character in value if ord(character) >= 32 and ord(character) != 127).strip()
    return clean[:128] or fallback


def safe_profile(root, directory):
    if not isinstance(directory, str) or not re.fullmatch(r'[A-Za-z0-9 _-]{1,64}', directory): return False
    try:
        path = Path(root)/directory
        return (not path.is_symlink() and path.is_dir() and path.resolve().parent == Path(root).resolve()
                and path.stat().st_uid == os.getuid())
    except OSError: return False


def profile_id(root, directory):
    return hashlib.sha256((str(root)+'\0'+directory).encode()).hexdigest()[:32]


def verified_chrome_owner(value):
    """Prove root, executable, UID and process lifetime before offering a close."""
    pid = profile_owner(value['userDataDir'])
    if not isinstance(pid, int): return None
    lifetime = generation(pid)
    if not lifetime: return None
    try:
        process = Path('/proc')/str(pid)
        executable = (process/'exe').resolve(strict=True)
        # Do not derive trusted executables from PATH: a user launcher may be
        # an arbitrary shim, unrelated to the Chrome process holding this root.
        installations = {Path('/opt/google/chrome/chrome'), Path('/usr/lib/chromium/chromium'),
                         Path('/usr/lib/chromium-browser/chromium-browser'),
                         Path('/usr/lib64/chromium-browser/chromium-browser')}
        trusted = {item.resolve() for item in installations if item.is_file()
                   and item.stat().st_uid == 0 and not item.stat().st_mode & 0o022}
        if executable not in trusted: return None
        argv = (process/'cmdline').read_bytes().decode().split('\0')
        if any(arg == '--type' or arg.startswith('--type=') for arg in argv): return None
        # Chrome may rewrite its command line after launch. A private, live
        # supervisor record can prove the exact managed root independently of
        # argv; this exception never authorizes an unrecorded desktop owner.
        for filename in ('runtime.json', 'native.json'):
            record = read_json(Path(value['runtimeDir'])/filename)
            if (record.get('state') == 'ready' and record.get('chromePid') == pid
                    and record.get('chromeGeneration') == lifetime
                    and record.get('configFingerprint') == fingerprint(value)
                    and record.get('pid') and generation(record['pid']) == record.get('processGeneration')
                    and generation(pid) == lifetime and profile_owner(value['userDataDir']) == pid):
                return {'chromePid': pid, 'chromeGeneration': lifetime}
        root = None
        for index, arg in enumerate(argv):
            if arg.startswith('--user-data-dir='): root = arg.split('=', 1)[1]
            elif arg == '--user-data-dir' and index+1 < len(argv): root = argv[index+1]
        if root is None:
            env = dict(item.split('=', 1) for item in (process/'environ').read_bytes().decode().split('\0') if '=' in item)
            home = env.get('HOME', str(Path.home()))
            root = str(Path(env.get('XDG_CONFIG_HOME', str(Path(home)/'.config')))/'google-chrome')
        if not Path(root).is_absolute() or str(Path(root).resolve()) != value['userDataDir'] or generation(pid) != lifetime: return None
        return {'chromePid': pid, 'chromeGeneration': lifetime}
    except (OSError, ValueError, UnicodeError): return None


def profile_metadata(root):
    value = read_json(Path(root)/'Local State').get('profile', {})
    return value if isinstance(value, dict) else {}


def catalog(value):
    current = status(value)
    profiles = []
    selected = value.get('profileDirectory')
    if selected is None:
        selected = profile_metadata(value['userDataDir']).get('last_used')
    selected_id = profile_id(value['userDataDir'], selected) if safe_profile(value['userDataDir'], selected) else None
    for root in profile_roots(value):
        metadata = profile_metadata(root['path'])
        cache = metadata.get('info_cache', {}) if isinstance(metadata, dict) else {}
        if not isinstance(cache, dict): continue
        owner = profile_owner(root['path'])
        owned = verified_chrome_owner(dict(value, userDataDir=root['path'])) if owner is not None else None
        phone = root['path'] == value['userDataDir'] and current.get('state') in ('ready', 'starting')
        location = ('phone' if phone else 'computer') if owned else ('closed' if owner is None else 'unavailable')
        for directory, info in cache.items():
            if isinstance(info, dict) and (info.get('is_omitted_from_profile_list') is True or info.get('is_ephemeral') is True): continue
            if not safe_profile(root['path'], directory): continue
            identifier = profile_id(root['path'], directory)
            profiles.append({'id': identifier, 'name': display_name(info.get('name') if isinstance(info, dict) else None, directory),
                'source': root['name'], 'selected': identifier == selected_id, 'location': location,
                'status': 'open' if owned else 'available' if owner is None else 'unavailable',
                '_root': root['path'], '_directory': directory})
    profiles.sort(key=lambda item: (item['source'].casefold(), item['name'].casefold(), item['id']))
    return {'code': 'PROFILES', 'selectedProfileId': selected_id,
            'profiles': [{key: val for key, val in item.items() if not key.startswith('_')} for item in profiles],
            'session': current, 'canSelect': bool(profiles)}, profiles


def selection_snapshot(value, target):
    owners = {}
    for root in sorted({value['userDataDir'], target['_root']}):
        pid = profile_owner(root)
        owners[root] = {'pid': pid, 'generation': generation(pid) if isinstance(pid, int) else None}
    return {'config': fingerprint(value), 'roots': owners, 'session': status(value, ignore_switch=True)}


def _runtime_module():
    # Tests may load this module with importlib without registering sys.modules.
    import types
    return types.SimpleNamespace(**globals())


def select_profile(value, path, identifier, confirmation_token=None):
    directory = Path(value['runtimeDir']); directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (directory/'native-switch.lock').open('a') as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise RuntimeErrorCode('HANDOFF_IN_PROGRESS')
        # A waiting request must not operate on a selection changed by another caller.
        value = config(path)
        public, rows = catalog(value)
        target = next((item for item in rows if item['id'] == identifier), None)
        if target is None: raise RuntimeErrorCode('PROFILE_NOT_FOUND')
        current = status(value)
        if current.get('state') in ('starting', 'native-starting', 'switching-native', 'configuration-changed'):
            raise RuntimeErrorCode('BROWSER_BUSY')
        if (target['_root'] == value['userDataDir'] and identifier == public['selectedProfileId']
                and current.get('state') == 'ready'):
            return {'code': 'CONNECTED', 'connected': True, 'selectedProfileId': identifier, 'session': current}
        snapshot = selection_snapshot(value, target)
        owners = {}
        for root, owner in snapshot['roots'].items():
            if owner['pid'] is None: continue
            verified = verified_chrome_owner(dict(value, userDataDir=root))
            if verified is None: raise RuntimeErrorCode('PROFILE_OWNER_UNVERIFIED')
            owners[root] = verified
        challenge_path = directory/'profile-selection-confirmation.json'
        if owners and confirmation_token is None:
            confirmation = {'token': secrets.token_hex(32), 'profileId': identifier,
                'location': 'computer' if target['location'] == 'computer' or current.get('state') in ('native', 'native-closed') else 'phone',
                'closesAllWindows': True, 'expiresAt': int(time.time())+120}
            write_json(challenge_path, {'confirmation': confirmation, 'snapshot': snapshot})
            return {'code': 'PROFILE_CONFIRMATION_REQUIRED', 'connected': False,
                    'confirmation': confirmation, 'session': current}
        if confirmation_token is not None:
            challenge = read_json(challenge_path)
            confirmation = challenge.get('confirmation', {})
            if not isinstance(confirmation, dict): confirmation = {}
            token = confirmation.get('token'); expires = confirmation.get('expiresAt')
            if (not isinstance(confirmation_token, str) or not isinstance(token, str)
                    or not secrets.compare_digest(token, confirmation_token)
                    or confirmation.get('profileId') != identifier or not isinstance(expires, (int, float)) or expires < time.time()
                    or challenge.get('snapshot') != snapshot):
                raise RuntimeErrorCode('PROFILE_CONFIRMATION_EXPIRED')
            challenge_path.unlink(missing_ok=True)  # A retry requires a fresh, state-bound warning.
        # Import only here to avoid a cycle; native's close primitive also pins a pidfd.
        import importlib.util
        spec = importlib.util.spec_from_file_location('native_profile_switch', Path(__file__).with_name('browser-native.py'))
        native = importlib.util.module_from_spec(spec); spec.loader.exec_module(native)
        native.secret_preflight(value, _runtime_module())
        switch = {'pid': os.getpid(), 'processGeneration': generation(os.getpid()), 'destination': 'phone'}
        write_json(directory/'native-switch.json', switch)
        previous_selection = read_json(directory/'selected-profile.json')
        closed_roots = []
        source_record = read_json(directory/('native.json' if current.get('state') == 'native' else 'runtime.json'))
        try:
            # The destination computer root closes first; a refused dialog preserves the phone.
            for root in sorted(owners, key=lambda item: item == value['userDataDir']):
                owner_value = dict(value, userDataDir=root)
                metadata = profile_metadata(root)
                active = metadata.get('last_active_profiles', [])
                if not isinstance(active, list): active = []
                if not active and metadata.get('last_used'): active = [metadata['last_used']]
                native.quit_chrome(owner_value, owners[root], _runtime_module())
                closed_roots.append(root)
                if root == value['userDataDir']: native.wait_supervisor(source_record, _runtime_module())
                if not native.clean_exit(owner_value, _runtime_module(), active):
                    raise RuntimeErrorCode('UNCLEAN_CHROME_EXIT')
            if value.get('supervision', 'systemd-user') == 'systemd-user':
                for unit in ('agentshell-browser.service', 'agentshell-browser-native.service'):
                    if user_systemctl('stop', unit).returncode: raise RuntimeErrorCode('USER_SERVICE_UNAVAILABLE')
            if not safe_profile(target['_root'], target['_directory']): raise RuntimeErrorCode('PROFILE_NOT_FOUND')
            selected = {'sessionId': value['sessionId'], 'configuredUserDataDir': value['_configuredUserDataDir'],
                        'userDataDir': target['_root'], 'profileDirectory': target['_directory']}
            write_json(directory/'selected-profile.json', selected)
            chosen = config(path)
            state = native.start_virtual(chosen, path, _runtime_module())
            return {'code': 'CONNECTED', 'connected': True, 'selectedProfileId': identifier,
                    'normalExit': bool(owners), 'session': state}
        except Exception as error:
            if previous_selection: write_json(directory/'selected-profile.json', previous_selection)
            else: (directory/'selected-profile.json').unlink(missing_ok=True)
            restored = status(value, ignore_switch=True)
            if value['userDataDir'] in closed_roots and profile_owner(value['userDataDir']) is None:
                try:
                    restored = (native.start_native(value, path, _runtime_module()) if current.get('state') == 'native'
                                else native.start_virtual(value, path, _runtime_module()))
                except Exception: restored = status(value, ignore_switch=True)
            return {'code': getattr(error, 'code', 'PROFILE_OPEN_FAILED'), 'connected': False,
                    'computerClosed': any(root != value['userDataDir'] or current.get('state') == 'native' for root in closed_roots),
                    'phonePreserved': current.get('state') == 'ready' and restored.get('state') == 'ready',
                    'session': restored}
        finally: (directory/'native-switch.json').unlink(missing_ok=True)

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
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--profile-id'); parser.add_argument('--confirmation-token')
    parser.add_argument('command',choices=['status','start','stop','run','profiles','select-profile'])
    args=parser.parse_args()
    try:
        value=config(args.config)
        if args.command=='run': run(value);return
        if args.command == 'profiles': result = catalog(value)[0]
        elif args.command == 'select-profile': result = select_profile(value, args.config, args.profile_id, args.confirmation_token)
        else: result=status(value) if args.command=='status' else start(value,args.config) if args.command=='start' else stop(value)
    except (RuntimeErrorCode,ValueError) as error:
        result={'state':'failed','code':error.code if isinstance(error,RuntimeErrorCode) else 'INVALID_RUNTIME_CONFIG'}
    print(json.dumps(result))

if __name__=='__main__':main()

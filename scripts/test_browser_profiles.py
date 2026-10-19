#!/usr/bin/env python3
"""Exercise profile selection with disposable Chrome roots and a real backend.

Owns its X displays, localhost ports, generated TLS key and fake Chrome profiles.
Never reads, closes, copies or changes the user's Chrome or physical phone.
Run after building the backend: python3 scripts/test_browser_profiles.py
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import uuid

import websockets

from test_android_browser import CDP
from test_browser_voice_paste import PROJECT, VoiceFixture, free_port


class ProfilesFixture(VoiceFixture):
    def __init__(self, output, binary, tls_directory=None):
        super().__init__(output, binary, tls_directory)
        self.desktop_root = output/'computer-chrome'
        self.desktop_debug = free_port()
        self.processes = []
        self.viewer_id = str(uuid.uuid4())
        self.remote_marker = None

    @staticmethod
    def seed(root, names):
        root.mkdir(mode=0o700)
        info = {}
        for directory, name in names.items():
            profile = root/directory
            profile.mkdir(mode=0o700)
            (profile/'Preferences').write_text(json.dumps({'profile': {
                'name': name, 'exit_type': 'Normal', 'exited_cleanly': True}}))
            info[directory] = {'name': name}
        (root/'Local State').write_text(json.dumps({'profile': {
            'last_used': 'Default', 'last_active_profiles': ['Default'], 'info_cache': info}}))

    def prepare(self):
        super().prepare()
        self.stop_backend()
        self.managed_root = Path(self.config['userDataDir'])
        self.seed(self.managed_root, {'Default': 'Managed default', 'Profile 2': 'Managed work'})
        self.seed(self.desktop_root, {'Default': 'Computer default', 'Profile 1': 'Computer work'})
        self.config.update(includeDefaultProfiles=False,
            profileRoots=[{'path': str(self.desktop_root), 'name': 'Computer Chrome'}])
        self.config_path.write_text(json.dumps(self.config))
        for name in ('browser-native.py', 'browser-desktop.py'):
            (self.output/name).write_bytes((PROJECT/'scripts'/name).read_bytes())
        display = next(n for n in range(100, 201)
            if n != self.config['display'] and not Path(f'/tmp/.X11-unix/X{n}').exists()
            and not Path(f'/tmp/.X{n}-lock').exists())
        authority = self.output/'desktop-Xauthority'
        subprocess.run(['xauth', '-f', str(authority), 'add', f':{display}', '.', uuid.uuid4().hex],
            capture_output=True, check=True)
        password = self.output/'desktop-password'
        password.write_text('fixture:'+uuid.uuid4().hex+'\n')
        password.chmod(0o600)
        self.desktop_env = dict(os.environ, DISPLAY=f':{display}', XAUTHORITY=str(authority),
            GTK_USE_PORTAL='0', HOME=str(self.output/'home'))
        self.desktop_env.pop('DBUS_SESSION_BUS_ADDRESS', None)
        with (self.output/'desktop.log').open('ab') as log:
            self.processes.append(subprocess.Popen(['Xvnc', f':{display}', '-auth', str(authority),
                '-nolisten', 'tcp', '-geometry', '1280x900', '-depth', '24', '-interface', '127.0.0.1',
                '-websocketPort', str(free_port()), '-KasmPasswordFile', str(password),
                '-SecurityTypes', 'None'], env=self.desktop_env, stdout=log, stderr=log))
        for _ in range(80):
            if Path(f'/tmp/.X11-unix/X{display}').exists():
                break
            time.sleep(.1)
        else:
            raise AssertionError('Private computer display did not start')
        with (self.output/'desktop-window-manager.log').open('ab') as log:
            self.processes.append(subprocess.Popen(['openbox'], env=self.desktop_env,
                stdout=log, stderr=log))
        self.start_backend()

    async def call(self, path, body=None, **kwargs):
        return await asyncio.to_thread(super().api, path, body, **kwargs)

    async def catalog(self):
        status, _, value = await self.call('/api/browser/profiles')
        assert status == 200 and value.get('canSelect'), value
        return value

    async def select(self, profile, token=None):
        current = (await self.call('/api/browser/capabilities'))[2]['session']
        request = {'profileId': profile, 'viewerId': self.viewer_id}
        if current.get('runtimeGeneration'):
            request['runtimeGeneration'] = current['runtimeGeneration']
        if token is not None:
            request['confirmationToken'] = token
        return await self.call('/api/browser/profiles/open', request)

    async def remote_target(self):
        for _ in range(100):
            try:
                targets = await self.targets(self.remote_debug)
                return next(t for t in targets if t['type'] == 'page'
                    and '/voice-fixture' in t.get('url', '')
                    and (not self.remote_marker or self.remote_marker in t['url']))
            except (OSError, StopIteration):
                await asyncio.sleep(.1)
        raise AssertionError('Selected profile fixture tab did not restore')

    async def remote(self, expression):
        return await self.evaluate(await self.remote_target(), expression)

    async def move_native(self, destination):
        state = (await self.call('/api/browser/capabilities'))[2]['session']
        result = await asyncio.to_thread(subprocess.run, ['/usr/bin/python3',
            str(self.output/'browser-native.py'), '--config', str(self.config_path),
            '--generation', state['runtimeGeneration'], destination], env=self.desktop_env,
            capture_output=True, text=True, timeout=60)
        value = json.loads(result.stdout)
        assert value.get('connected'), value
        return value

    def owner(self, root):
        lock = root/'SingletonLock'
        if not lock.is_symlink():
            return None
        try:
            return int(os.readlink(lock).rsplit('-', 1)[1])
        except (ValueError, OSError):
            return None

    @staticmethod
    def process_generation(pid):
        try:
            fields = Path('/proc', str(pid), 'stat').read_text().rsplit(')', 1)[1].split()
            return fields[19] if fields[0] != 'Z' else None
        except (OSError, ValueError):
            return None

    async def native_targets(self, count=1):
        for _ in range(100):
            try:
                pages = [p for p in await self.targets(self.desktop_debug)
                    if p['type'] == 'page' and '/voice-fixture' in p.get('url', '')]
                if len(pages) >= count:
                    return pages
            except OSError:
                pass
            await asyncio.sleep(.1)
        raise AssertionError('Disposable computer Chrome did not open its fixture tabs')

    async def launch_desktop(self):
        command = ['google-chrome', '--password-store=basic', '--no-first-run',
            '--no-default-browser-check', f'--remote-debugging-port={self.desktop_debug}',
            '--user-data-dir='+str(self.desktop_root)]
        with (self.output/'computer-chrome.log').open('ab') as log:
            process = subprocess.Popen([*command, '--profile-directory=Default',
                f'http://127.0.0.1:{self.page_port}/voice-fixture?profile=default'],
                env=self.desktop_env, stdout=log, stderr=log)
            self.processes.append(process)
        await self.native_targets()
        with (self.output/'computer-chrome.log').open('ab') as log:
            launched = await asyncio.to_thread(subprocess.run, [*command,
                '--profile-directory=Profile 1',
                f'http://127.0.0.1:{self.page_port}/voice-fixture?profile=work'],
                env=self.desktop_env, stdout=log, stderr=log, timeout=15)
        assert launched.returncode == 0
        pages = await self.native_targets(2)
        target = next(p for p in pages if 'profile=work' in p['url'])
        for _ in range(80):
            if await self.evaluate(target, "!!document.getElementById('draft')"):
                break
            await asyncio.sleep(.1)
        else:
            raise AssertionError('Disposable computer fixture document did not load')
        await self.evaluate(target, "window.fixtureMarker='unchanged';document.getElementById('draft').value='unsaved fixture'")
        return self.owner(self.desktop_root), target

    async def close_desktop(self):
        pages = await self.native_targets()
        try:
            async with websockets.connect(pages[0]['webSocketDebuggerUrl']) as connection:
                await CDP(connection).call('Browser.close')
        except websockets.ConnectionClosed:
            pass
        for _ in range(100):
            if self.owner(self.desktop_root) is None:
                return
            await asyncio.sleep(.1)
        raise AssertionError('Owned computer Chrome did not close normally')

    async def challenge(self, profile, location):
        status, _, result = await self.select(profile)
        assert status == 200 and result.get('code') == 'PROFILE_CONFIRMATION_REQUIRED', result
        confirmation = result['confirmation']
        assert confirmation['profileId'] == profile and confirmation['location'] == location
        assert confirmation['closesAllWindows'] is True and confirmation['token']
        return confirmation['token']

    def assert_remote(self, root, profile):
        record = json.loads((Path(self.config['runtimeDir'])/'runtime.json').read_text())
        arguments = Path('/proc', str(record['chromePid']), 'cmdline').read_bytes().split(b'\0')
        assert ('--user-data-dir='+str(root)).encode() in arguments, arguments
        assert ('--profile-directory='+profile).encode() in arguments, arguments
        assert self.owner(root) == record['chromePid']
        return record

    async def tests(self):
        assert (await self.call('/api/browser/profiles', token='invalid'))[0] == 401
        assert (await self.call('/api/browser/capabilities'))[2]['profileSelection'] is True
        catalog = await self.catalog()
        rows = catalog['profiles']
        assert len(rows) == 4, catalog
        assert {p['source'] for p in rows} == {'AgentShell', 'Computer Chrome'}
        assert all(len(p['id']) == 32 and '/' not in p['id'] for p in rows)
        assert str(self.managed_root) not in json.dumps(catalog)
        assert str(self.desktop_root) not in json.dumps(catalog)
        by_name = {p['name']: p['id'] for p in rows}
        computer = by_name['Computer work']
        managed = by_name['Managed default']
        work = by_name['Managed work']
        assert (await self.select('../../outside'))[0] in (400, 409)
        self.results['authenticated_opaque_two_root_profile_catalog'] = True

        status, _, initial = await self.select(managed)
        assert status == 200 and initial.get('connected'), initial
        original = self.assert_remote(self.managed_root, 'Default')
        sid = self.config['sessionId']
        status, _, grant = await self.call(f'/api/browser/sessions/{sid}/viewer-grants', {
            'viewerId': self.viewer_id, 'runtimeGeneration': original['runtimeGeneration']})
        assert status == 200, grant
        status, headers, _ = await self.call(f'/browser/{sid}/bootstrap', 'grant='+grant['grant'],
            token=None, https=True, raw=True)
        assert status == 303
        old_cookie = headers['Set-Cookie'].split(';', 1)[0]

        pid, target = await self.launch_desktop()
        generation = self.process_generation(pid)
        token = await self.challenge(computer, 'computer')
        await asyncio.sleep(.3)
        assert self.owner(self.desktop_root) == pid and self.process_generation(pid) == generation
        preserved = await self.evaluate(target, "({marker:fixtureMarker,draft:document.getElementById('draft').value})")
        assert preserved == {'marker': 'unchanged', 'draft': 'unsaved fixture'}
        assert self.assert_remote(self.managed_root, 'Default')['chromePid'] == original['chromePid']
        self.results['warning_and_cancel_leave_computer_windows_and_memory_untouched'] = True
        assert (await self.select(managed, token))[0] == 409
        assert self.owner(self.desktop_root) == pid
        self.results['confirmation_cannot_be_reused_for_another_profile'] = True

        token = await self.challenge(computer, 'computer')
        request = {'profileId': computer, 'viewerId': str(uuid.uuid4()),
            'runtimeGeneration': original['runtimeGeneration'], 'confirmationToken': token}
        assert (await self.call('/api/browser/profiles/open', request))[0] == 409
        assert self.owner(self.desktop_root) == pid
        self.results['confirmation_is_bound_to_requesting_viewer'] = True

        expired = await self.challenge(computer, 'computer')
        challenge_path = Path(self.config['runtimeDir'])/'profile-selection-confirmation.json'
        challenge = json.loads(challenge_path.read_text())
        challenge['confirmation']['expiresAt'] = 0
        challenge_path.write_text(json.dumps(challenge))
        assert (await self.select(computer, expired))[0] == 409
        assert self.owner(self.desktop_root) == pid
        self.results['expired_confirmation_leaves_both_roots_open'] = True

        stale = await self.challenge(computer, 'computer')
        await self.close_desktop()
        new_pid, _ = await self.launch_desktop()
        assert new_pid != pid
        assert (await self.select(computer, stale))[0] == 409
        assert self.owner(self.desktop_root) == new_pid
        self.results['stale_confirmation_cannot_close_new_computer_process'] = True

        token = await self.challenge(computer, 'computer')
        status, _, result = await self.select(computer, token)
        assert status == 200 and result.get('connected') and result['session']['state'] == 'ready', result
        assert self.process_generation(new_pid) is None
        remote = self.assert_remote(self.desktop_root, 'Profile 1')
        assert self.owner(self.managed_root) is None
        assert (await self.call(f'/browser/{sid}/view/vnc.html', token=None,
            cookie=old_cookie, https=True, raw=True))[0] == 401
        self.results['profile_switch_revokes_previous_viewer_cookie'] = True
        self.results['confirmed_whole_root_close_opens_exact_selected_computer_profile_on_phone'] = True
        self.remote_marker = 'profile=work'
        for _ in range(80):
            if await self.remote('document.hasFocus() && document.visibilityState === "visible"'):
                break
            await asyncio.sleep(.1)
        else:
            raise AssertionError('Chosen profile was restored without bringing its window to the foreground')
        self.results['chosen_profile_window_is_foreground_on_phone'] = True
        assert (await self.select(computer, token))[0] == 409
        self.results['confirmed_token_is_single_use'] = True
        selected = (await self.catalog())['selectedProfileId']
        assert selected == computer
        status, _, repeated = await self.select(computer)
        assert status == 200 and repeated.get('connected'), repeated
        assert self.assert_remote(self.desktop_root, 'Profile 1')['chromePid'] == remote['chromePid']
        self.results['selected_phone_profile_reconnects_without_restart'] = True
        self.stop_backend()
        self.start_backend()
        assert (await self.catalog())['selectedProfileId'] == computer
        assert self.assert_remote(self.desktop_root, 'Profile 1')['runtimeGeneration'] == remote['runtimeGeneration']
        self.results['selected_root_and_profile_survive_backend_restart'] = True

        # The existing native handoff must read the persisted root/profile,
        # rather than silently reverting to the original configured directory.
        await self.remote("localStorage.setItem('selected-profile-fixture','retained')")
        moved = await self.move_native('desktop')
        assert moved['session']['state'] == 'native'
        assert await self.remote("localStorage.getItem('selected-profile-fixture')") == 'retained'
        record = json.loads((Path(self.config['runtimeDir'])/'native.json').read_text())
        arguments = Path('/proc', str(record['chromePid']), 'cmdline').read_bytes().split(b'\0')
        assert ('--user-data-dir='+str(self.desktop_root)).encode() in arguments
        assert b'--profile-directory=Profile 1' in arguments
        assert (await self.move_native('phone'))['session']['state'] == 'ready'
        assert await self.remote("localStorage.getItem('selected-profile-fixture')") == 'retained'
        self.assert_remote(self.desktop_root, 'Profile 1')
        self.results['native_round_trip_retains_selected_root_profile_and_storage'] = True

        # Leave only the chosen fixture document for the existing cursor-paste
        # regression; it activates a single window before sending real RFB keys.
        target = await self.remote_target()
        async with websockets.connect(target['webSocketDebuggerUrl']) as connection:
            cdp = CDP(connection)
            for page in await self.targets(self.remote_debug):
                if page['type'] == 'page' and page['id'] != target['id']:
                    await cdp.call('Target.closeTarget', targetId=page['id'])
        await VoiceFixture.tests(self)
        self.results['existing_viewer_dictation_cursor_suite_on_selected_profile'] = True

        current_remote = self.assert_remote(self.desktop_root, 'Profile 1')
        token = await self.challenge(managed, 'phone')
        assert self.assert_remote(self.desktop_root, 'Profile 1')['chromePid'] == current_remote['chromePid']
        status, _, switched = await self.select(managed, token)
        assert status == 200 and switched.get('connected'), switched
        self.assert_remote(self.managed_root, 'Default')
        assert self.owner(self.desktop_root) is None
        assert {p['source'] for p in (await self.catalog())['profiles']} == {'AgentShell', 'Computer Chrome'}
        self.results['confirmed_phone_profile_switch_preserves_original_catalog_roots'] = True
        assert (await self.call(f'/api/browser/sessions/{sid}/stop', {}))[2]['state'] == 'stopped'
        status, _, stopped = await self.select(work)
        assert status == 200 and stopped.get('connected'), stopped
        self.assert_remote(self.managed_root, 'Profile 2')
        self.results['closed_profile_starts_directly_and_persists_selection'] = True

        # A caller who does not own the warning must not invalidate the
        # legitimate viewer's still-valid confirmation by presenting its token.
        await self.launch_desktop()
        token = await self.challenge(computer, 'computer')
        current = (await self.call('/api/browser/capabilities'))[2]['session']
        request = {'profileId': computer, 'viewerId': str(uuid.uuid4()),
            'runtimeGeneration': current['runtimeGeneration'], 'confirmationToken': token}
        assert (await self.call('/api/browser/profiles/open', request))[0] == 409
        status, _, legitimate = await self.select(computer, token)
        assert status == 200 and legitimate.get('connected'), legitimate
        self.assert_remote(self.desktop_root, 'Profile 1')
        self.results['invalid_caller_cannot_consume_another_viewers_confirmation'] = True
        token = await self.challenge(work, 'phone')
        assert (await self.select(work, token))[2].get('connected')
        previous = self.assert_remote(self.managed_root, 'Profile 2')
        assert (await self.call(f'/api/browser/sessions/{sid}/stop', {}))[2]['state'] == 'stopped'
        started = (await self.call('/api/browser/sessions', {}))[2]
        assert started['state'] == 'ready' and started['runtimeGeneration'] != previous['runtimeGeneration']
        self.assert_remote(self.managed_root, 'Profile 2')
        assert (await self.catalog())['selectedProfileId'] == work
        self.results['ordinary_start_always_reopens_remembered_profile'] = True
        (self.output/'profile-results.json').write_text(json.dumps(self.results, indent=2)+'\n')
        print(json.dumps(self.results, indent=2), flush=True)

    def cleanup(self):
        # Every process in this list was launched here with disposable profiles.
        for process in reversed(self.processes):
            if process.poll() is None:
                process.send_signal(signal.SIGINT)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
        super().cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, default=PROJECT/'backend-rust/target/debug/agentshell-backend')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--tls-directory', type=Path, help='Owned fixture cert.pem/key.pem for isolated Android tests')
    parser.add_argument('--keep-running', action='store_true', help='Keep only this disposable fixture for emulator UI checks')
    args = parser.parse_args()
    output = args.output or Path(tempfile.mkdtemp(prefix='agentshell-browser-profiles-'))
    fixture = ProfilesFixture(output.resolve(), args.binary.resolve(), args.tls_directory)
    print('Private profile fixture: '+str(fixture.output), flush=True)
    try:
        fixture.prepare()
        asyncio.run(fixture.tests())
        if args.keep_running:
            asyncio.run(fixture.launch_desktop())
            print(json.dumps({'httpsPort': fixture.https_port, 'cert': str(fixture.output/'certs/cert.pem'),
                'catalog': 'http://127.0.0.1:'+str(fixture.http_port)+'/api/browser/profiles'}), flush=True)
            while True:
                time.sleep(1)
    finally:
        fixture.cleanup()


if __name__ == '__main__':
    main()

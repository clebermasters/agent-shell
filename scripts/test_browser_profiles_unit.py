#!/usr/bin/env python3
"""Profile catalog and takeover safety checks; no browsers are started or closed."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

spec = importlib.util.spec_from_file_location('profile_runtime', Path(__file__).with_name('browser-runtime.py'))
runtime = importlib.util.module_from_spec(spec); spec.loader.exec_module(runtime)


class ProfileSelectionTests(unittest.TestCase):
    def fixture(self, root):
        root = Path(root)
        value = {'sessionId': str(uuid.uuid4()), 'userDataDir': str(root/'managed'),
                 'runtimeDir': str(root/'runtime'), 'display': 94, 'port': 19002,
                 'password': 'synthetic-only', 'supervision': 'process', 'includeDefaultProfiles': False,
                 'profileRoots': [{'path': str(root/'computer'), 'name': 'Computer Chrome'}]}
        self.add_profile(root/'managed', 'Default', 'Personal')
        self.add_profile(root/'computer', 'Profile 2', 'Work')
        path = root/'config.json'; runtime.write_json(path, value)
        return runtime.config(path), path

    def add_profile(self, root, directory, name):
        root = Path(root); (root/directory).mkdir(parents=True)
        metadata = runtime.read_json(root/'Local State')
        profile = metadata.setdefault('profile', {}); profile.setdefault('info_cache', {})[directory] = {
            'name': name, 'user_name': 'private-account@example.invalid', 'gaia_id': 'private-account-id'}
        profile['last_used'] = directory; profile['last_active_profiles'] = [directory]
        runtime.write_json(root/'Local State', metadata)
        runtime.write_json(root/directory/'Preferences', {'profile': {'exit_type': 'Normal'}})

    def target(self, value, name='Work'):
        return next(item for item in runtime.catalog(value)[0]['profiles'] if item['name'] == name)

    def test_catalog_opaque_ids_sources_and_no_private_paths_or_accounts(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, _ = self.fixture(temporary)
            public, _ = runtime.catalog(value)
            self.assertEqual({item['source'] for item in public['profiles']}, {'AgentShell', 'Computer Chrome'})
            self.assertEqual({item['location'] for item in public['profiles']}, {'closed'})
            self.assertTrue(all(len(item['id']) == 32 for item in public['profiles']))
            output = json.dumps(public)
            self.assertNotIn(temporary, output)
            self.assertNotIn('private-account', output)

    def test_default_os_root_is_allowlisted_without_removing_original_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, _ = self.fixture(temporary)
            value['includeDefaultProfiles'] = True
            with patch.dict(os.environ, {'XDG_CONFIG_HOME': str(Path(temporary)/'config-home')}):
                roots = runtime.profile_roots(value)
            self.assertIn(str(Path(temporary)/'config-home/google-chrome'), [item['path'] for item in roots])
            self.assertIn(value['userDataDir'], [item['path'] for item in roots])

    def test_catalog_rejects_traversal_symlink_and_missing_profile_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, _ = self.fixture(temporary); root = Path(value['userDataDir'])
            (Path(temporary)/'outside').mkdir(); (root/'Linked').symlink_to(Path(temporary)/'outside')
            data = runtime.read_json(root/'Local State')
            for invalid in ('../outside', 'Linked', 'Missing', 'a/b', ''):
                data['profile']['info_cache'][invalid] = {'name': 'Never selectable'}
            runtime.write_json(root/'Local State', data)
            self.assertEqual(len(runtime.catalog(value)[0]['profiles']), 2)

    def test_names_do_not_change_stable_profile_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, _ = self.fixture(temporary); original = self.target(value)['id']
            root = Path(temporary)/'computer'; metadata = runtime.read_json(root/'Local State')
            metadata['profile']['info_cache']['Profile 2']['name'] = 'Renamed account'
            runtime.write_json(root/'Local State', metadata)
            self.assertEqual(self.target(value, 'Renamed account')['id'], original)

    def test_stopped_selection_persists_both_root_and_profile_without_editing_local_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, path = self.fixture(temporary); target = self.target(value)
            original_config = path.read_bytes(); local_state = (Path(temporary)/'computer/Local State').read_bytes()
            with patch.object(runtime, 'start', return_value={'state': 'ready', 'runtimeGeneration': 'test-generation'}):
                result = runtime.select_profile(value, path, target['id'])
            self.assertTrue(result['connected']); selected = runtime.config(path)
            self.assertEqual(selected['userDataDir'], str(Path(temporary)/'computer'))
            self.assertEqual(selected['profileDirectory'], 'Profile 2')
            self.assertEqual(path.read_bytes(), original_config)
            self.assertEqual((Path(temporary)/'computer/Local State').read_bytes(), local_state)
            self.assertEqual({item['source'] for item in runtime.catalog(selected)[0]['profiles']}, {'AgentShell', 'Computer Chrome'})
            self.assertEqual((Path(value['runtimeDir'])/'selected-profile.json').stat().st_mode & 0o777, 0o600)

    def test_sidecar_cannot_select_an_unconfigured_root_or_escape_profile(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, path = self.fixture(temporary)
            for root, profile in [(str(Path(temporary)/'outside'), 'Default'), (str(Path(temporary)/'computer'), '../managed')]:
                runtime.write_json(Path(value['runtimeDir'])/'selected-profile.json', {
                    'sessionId': value['sessionId'], 'configuredUserDataDir': value['userDataDir'],
                    'userDataDir': root, 'profileDirectory': profile})
                self.assertEqual(runtime.config(path)['userDataDir'], value['userDataDir'])

    def test_non_chrome_root_owner_is_never_offered_takeover(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, path = self.fixture(temporary); target = self.target(value)
            lock = Path(temporary)/'computer/SingletonLock'
            lock.symlink_to(socket.gethostname()+'-'+str(os.getpid()))
            self.assertIsNone(runtime.verified_chrome_owner(dict(value, userDataDir=str(lock.parent))))
            with self.assertRaises(runtime.RuntimeErrorCode) as caught:
                runtime.select_profile(value, path, target['id'])
            self.assertEqual(caught.exception.code, 'PROFILE_OWNER_UNVERIFIED')
            self.assertTrue(lock.is_symlink())

    def test_fake_path_launcher_cannot_qualify_non_chrome_executable(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, _ = self.fixture(temporary)
            launcher = Path(temporary)/'google-chrome'
            executable = (Path('/proc')/str(os.getpid())/'exe').resolve()
            launcher.symlink_to(executable)
            original_read = Path.read_bytes
            def read_bytes(path):
                if path == Path('/proc')/str(os.getpid())/'cmdline':
                    return (str(executable)+'\0--user-data-dir='+value['userDataDir']+'\0').encode()
                return original_read(path)
            with patch.dict(os.environ, {'PATH': temporary}), patch.object(runtime, 'profile_owner', return_value=os.getpid()), patch.object(Path, 'read_bytes', read_bytes):
                self.assertIsNone(runtime.verified_chrome_owner(value))

    def test_recorded_live_owner_survives_rewritten_cmdline_but_not_stale_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, _ = self.fixture(temporary); pid = os.getpid(); lifetime = runtime.generation(pid)
            executable = Path('/opt/google/chrome/chrome'); process_exe = Path('/proc')/str(pid)/'exe'
            process_cmdline = process_exe.with_name('cmdline')
            original_resolve = Path.resolve; original_read = Path.read_bytes
            original_is_file = Path.is_file; original_stat = Path.stat
            def resolve(path, *args, **kwargs):
                if path == process_exe: return executable
                return original_resolve(path, *args, **kwargs)
            def read_bytes(path):
                if path == process_cmdline: return (str(executable)+'\0--rewritten-process-title\0').encode()
                return original_read(path)
            def is_file(path): return True if path == executable else original_is_file(path)
            def stat(path, *args, **kwargs):
                if path == executable: return SimpleNamespace(st_uid=0, st_mode=0o100755)
                return original_stat(path, *args, **kwargs)
            record = {'pid': pid, 'processGeneration': lifetime, 'state': 'ready',
                'chromePid': pid, 'chromeGeneration': lifetime, 'configFingerprint': runtime.fingerprint(value)}
            record_path = Path(value['runtimeDir'])/'runtime.json'; runtime.write_json(record_path, record)
            with patch.object(runtime, 'profile_owner', return_value=pid), patch.object(Path, 'resolve', resolve), patch.object(Path, 'read_bytes', read_bytes), patch.object(Path, 'is_file', is_file), patch.object(Path, 'stat', stat):
                self.assertEqual(runtime.verified_chrome_owner(value), {'chromePid': pid, 'chromeGeneration': lifetime})
                for field, wrong in [('configFingerprint', 'another-config'), ('chromeGeneration', 'another-lifetime'), ('processGeneration', 'stale-supervisor')]:
                    runtime.write_json(record_path, dict(record, **{field: wrong}))
                    self.assertIsNone(runtime.verified_chrome_owner(value))
                record_path.unlink()
                self.assertIsNone(runtime.verified_chrome_owner(value))

    def prepare_live_mock(self, value, path):
        target = self.target(value)
        root = str(Path(path).parent/'computer')
        owner = lambda directory: 123 if directory == root else None
        verified = lambda item: {'chromePid': 123, 'chromeGeneration': 'boot:123'} if item['userDataDir'] == root else None
        return target, patch.object(runtime, 'profile_owner', side_effect=owner), patch.object(runtime, 'generation', return_value='boot:123'), patch.object(runtime, 'verified_chrome_owner', side_effect=verified)

    def test_prepare_requires_explicit_confirmation_and_never_signals(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, path = self.fixture(temporary); target, owner, generation, verified = self.prepare_live_mock(value, path)
            with owner, generation, verified, patch.object(runtime.os, 'pidfd_open', side_effect=AssertionError('No signal before confirmation')):
                result = runtime.select_profile(value, path, target['id'])
            self.assertEqual(result['code'], 'PROFILE_CONFIRMATION_REQUIRED')
            self.assertEqual(result['confirmation']['location'], 'computer')
            self.assertTrue(result['confirmation']['closesAllWindows'])
            self.assertFalse((Path(value['runtimeDir'])/'selected-profile.json').exists())

    def test_wrong_confirmation_and_changed_owner_never_signal(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, path = self.fixture(temporary); target, owner, generation, verified = self.prepare_live_mock(value, path)
            with owner, generation, verified:
                result = runtime.select_profile(value, path, target['id'])
                with patch.object(runtime.os, 'pidfd_open', side_effect=AssertionError('No stale confirmation signal')):
                    with self.assertRaises(runtime.RuntimeErrorCode) as caught:
                        runtime.select_profile(value, path, target['id'], 'wrong-token')
                    self.assertEqual(caught.exception.code, 'PROFILE_CONFIRMATION_EXPIRED')
                    with patch.object(runtime, 'generation', return_value='boot:456'):
                        with self.assertRaises(runtime.RuntimeErrorCode) as caught:
                            runtime.select_profile(value, path, target['id'], result['confirmation']['token'])
                        self.assertEqual(caught.exception.code, 'PROFILE_CONFIRMATION_EXPIRED')

    def test_challenge_binds_both_live_root_lifetimes(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, path = self.fixture(temporary); target = self.target(value)
            source = value['userDataDir']; destination = str(Path(temporary)/'computer')
            pids = {source: 123, destination: 456}
            lifetimes = {123: 'boot:123', 456: 'boot:456'}
            verified = lambda item: {'chromePid': pids[item['userDataDir']], 'chromeGeneration': lifetimes[pids[item['userDataDir']]]}
            with patch.object(runtime, 'profile_owner', side_effect=lambda root: pids.get(root)), patch.object(runtime, 'generation', side_effect=lambda pid: lifetimes.get(pid)), patch.object(runtime, 'verified_chrome_owner', side_effect=verified):
                result = runtime.select_profile(value, path, target['id'])
                challenge = runtime.read_json(Path(value['runtimeDir'])/'profile-selection-confirmation.json')
                self.assertEqual(set(challenge['snapshot']['roots']), {source, destination})
                self.assertEqual(challenge['snapshot']['roots'][source]['generation'], 'boot:123')
                self.assertEqual(challenge['snapshot']['roots'][destination]['generation'], 'boot:456')
                lifetimes[123] = 'boot:changed-source'
                with self.assertRaises(runtime.RuntimeErrorCode) as caught:
                    runtime.select_profile(value, path, target['id'], result['confirmation']['token'])
                self.assertEqual(caught.exception.code, 'PROFILE_CONFIRMATION_EXPIRED')

    def test_partial_target_close_reports_computer_closed_and_phone_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, path = self.fixture(temporary); target = self.target(value)
            source = value['userDataDir']; destination = str(Path(temporary)/'computer')
            pids = {source: 123, destination: 456}
            verified = lambda item: {'chromePid': pids[item['userDataDir']], 'chromeGeneration': 'boot:'+str(pids[item['userDataDir']])}
            ready = {'state': 'ready', 'runtimeGeneration': 'phone-generation', 'sessionId': value['sessionId']}
            fake_native = Mock()
            fake_native.clean_exit.return_value = True
            closed = []
            def close(owner_value, *_):
                if owner_value['userDataDir'] == source: raise runtime.RuntimeErrorCode('CHROME_CLOSE_BLOCKED')
                closed.append(owner_value['userDataDir']); pids[destination] = None
            fake_native.quit_chrome.side_effect = close
            fake_spec = Mock()
            with patch.object(runtime, 'profile_owner', side_effect=lambda root: pids.get(root)), patch.object(runtime, 'generation', side_effect=lambda pid: 'boot:'+str(pid) if pid else None), patch.object(runtime, 'verified_chrome_owner', side_effect=verified), patch.object(runtime, 'status', return_value=ready):
                prepared = runtime.select_profile(value, path, target['id'])
                with patch('importlib.util.spec_from_file_location', return_value=fake_spec), patch('importlib.util.module_from_spec', return_value=fake_native):
                    result = runtime.select_profile(value, path, target['id'], prepared['confirmation']['token'])
            self.assertEqual(result['code'], 'CHROME_CLOSE_BLOCKED')
            self.assertTrue(result['computerClosed']); self.assertTrue(result['phonePreserved'])
            self.assertEqual(closed, [destination])
            fake_native.start_virtual.assert_not_called()
            self.assertEqual(runtime.config(path)['userDataDir'], source)

    def test_failed_launch_restores_previous_selection(self):
        with tempfile.TemporaryDirectory() as temporary:
            value, path = self.fixture(temporary); target = self.target(value)
            with patch.object(runtime, 'start', return_value={'state': 'failed', 'code': 'DISPLAY_START_FAILED'}):
                result = runtime.select_profile(value, path, target['id'])
            self.assertEqual(result['code'], 'DISPLAY_START_FAILED'); self.assertFalse(result['connected'])
            self.assertEqual(runtime.config(path)['userDataDir'], value['userDataDir'])
            self.assertFalse((Path(value['runtimeDir'])/'native-switch.json').exists())


if __name__ == '__main__': unittest.main()

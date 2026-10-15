#!/usr/bin/env python3
"""Desktop viewer credential, redirect and installation regressions (no GUI)."""
import importlib.util
from pathlib import Path
import json
import os
import stat
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
import unittest
from unittest.mock import patch
import test_backend_install

spec = importlib.util.spec_from_file_location('browser_desktop', Path(__file__).with_name('browser-desktop.py'))
desktop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(desktop)


class DesktopTests(unittest.TestCase):
    def fixture(self, root, origin='https://browser.example'):
        env = root / 'runtime.env'
        env.write_text('AUTH_TOKEN="synthetic-$(never-run)-`never-run`"\n')
        env.chmod(0o600)
        path = root / 'desktop.json'
        path.write_text(json.dumps({'origin': origin, 'runtimeEnv': str(env), 'stateDir': str(root / 'viewer')}))
        path.chmod(0o600)
        return desktop.Config(path)

    def test_credentials_are_literal_private_and_not_in_status(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.fixture(Path(directory))
            self.assertEqual(config.token(), 'synthetic-$(never-run)-`never-run`')
            self.assertEqual(stat.S_IMODE(config.state.stat().st_mode), 0o700)
            self.assertEqual(desktop.viewer_id(config), desktop.viewer_id(config))
            config.runtime_env.chmod(0o644)
            with self.assertRaises(desktop.Failure):
                config.token()

    def test_config_requires_https_without_credentials_path_or_query(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for origin in ['http://127.0.0.1', 'https://user:password@browser.example',
                           'https://browser.example/path', 'https://browser.example?token=x',
                           'https://browser.example/', 'https://browser.example#fragment']:
                with self.subTest(origin=origin), self.assertRaises(desktop.Failure):
                    self.fixture(root, origin)

    def test_symlink_or_shared_configuration_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = self.fixture(root)
            linked = root / 'link'
            linked.symlink_to(config.path)
            with self.assertRaises(desktop.Failure):
                desktop.Config(linked)
            config.path.chmod(0o644)
            with self.assertRaises(desktop.Failure):
                desktop.Config(config.path)

    def test_bootstrap_rejects_external_redirect_and_wrong_cookie_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            api = desktop.Api(self.fixture(Path(directory)))
            sid = '550e8400-e29b-41d4-a716-446655440000'
            valid_cookie = 'agentshell_browser=' + 'a' * 64 + '; Path=/browser/' + sid + '/; HttpOnly; Secure; SameSite=Strict'
            valid_location = '/browser/' + sid + '/view/vnc.html?autoconnect=true'
            for location, cookie in [('https://evil.example/', valid_cookie),
                    ('//evil.example/', valid_cookie), (valid_location, valid_cookie.replace('; Secure', '')),
                    (valid_location, valid_cookie.replace('/browser/' + sid + '/', '/')),
                    (valid_location, valid_cookie + '; Domain=browser.example')]:
                with self.subTest(location=location, cookie=cookie), patch.object(api, 'request',
                    side_effect=[{'grant': 'synthetic'}, {'Location': location, 'Set-Cookie': cookie}]), self.assertRaises(desktop.Failure):
                    api.launch('viewer', sid, 'generation', False)
            with patch.object(api, 'request', side_effect=[{'grant': 'synthetic'},
                    {'Location': valid_location, 'Set-Cookie': valid_cookie}]):
                url, _, _ = api.launch('viewer', sid, 'generation', False)
                self.assertEqual(url, api.config.origin + valid_location)

    def test_api_does_not_follow_redirects(self):
        self.assertIsNone(desktop.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.example'))

    def test_network_operations_have_one_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            api = desktop.Api(self.fixture(Path(directory)))
            api.deadline = time.monotonic() + 2
            self.assertLessEqual(api.timeout(), 2)
            api.deadline = time.monotonic() - 1
            with self.assertRaises(desktop.Failure):
                api.timeout()

    def test_concurrent_first_launches_share_one_viewer_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.fixture(Path(directory))
            with ThreadPoolExecutor(max_workers=4) as executor:
                values = list(executor.map(lambda _: desktop.viewer_id(config), range(8)))
            self.assertEqual(len(set(values)), 1)

    def test_installer_deploys_private_config_commands_and_menu(self):
        with tempfile.TemporaryDirectory(prefix='as-viewer-') as directory:
            root = Path(directory)
            value = test_backend_install.InstallerTests().fixture(root)
            value.user_home = root / 'home'
            value.install_dir = root / 'installation'
            value.runtime = value.install_dir / 'backend/runtime.env'
            value.configure_desktop_browser('https://browser.example')
            path = value.user_home / '.config/agentshell/browser-desktop.json'
            config = json.loads(path.read_text())
            self.assertEqual(config['origin'], 'https://browser.example')
            self.assertNotIn('token', config)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            for name in ['agentshell-browser', 'agentshell-send-to-phone', 'agentshell-return-to-computer']:
                command = value.user_home / '.local/bin' / name
                self.assertEqual(stat.S_IMODE(command.stat().st_mode), 0o755)
                self.assertNotIn('AUTH_TOKEN', command.read_text())
            menu = value.user_home / '.local/share/applications/agentshell-browser.desktop'
            self.assertIn('Actions=Phone;Desktop;', menu.read_text())
            agent_unit = value.user_home / '.config/systemd/user/agentshell-browser-desktop-agent.service'
            self.assertIn('PartOf=graphical-session.target', agent_unit.read_text())
            autostart = value.user_home / '.config/autostart/agentshell-browser-desktop-agent.desktop'
            self.assertIn('--register-session', autostart.read_text())
            config['caFile'] = '/custom/trusted-ca.pem'
            path.write_text(json.dumps(config))
            value.configure_desktop_browser('https://browser.example')
            self.assertEqual(json.loads(path.read_text())['caFile'], '/custom/trusted-ca.pem')
            value.configure_desktop_browser('https://browser.example')
            self.assertEqual(len(list(path.parent.glob('*.agentshell-backup-*'))), 1)

    def test_session_registration_imports_only_graphical_metadata(self):
        spec = importlib.util.spec_from_file_location('desktop_agent', Path(__file__).with_name('browser-desktop-agent.py'))
        agent = importlib.util.module_from_spec(spec);spec.loader.exec_module(agent)
        with patch.dict(os.environ, {'DISPLAY':':95','AUTH_TOKEN':'do-not-import'}, clear=True), patch.object(agent.subprocess, 'run') as run:
            agent.register()
            command = run.call_args_list[0].args[0]
            self.assertIn('DISPLAY', command)
            self.assertNotIn('AUTH_TOKEN', command)
            self.assertNotIn('do-not-import', str(run.call_args_list))
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(agent.desktop.Failure):
            agent.register()


if __name__ == '__main__':
    unittest.main()

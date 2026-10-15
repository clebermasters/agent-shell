#!/usr/bin/env python3
"""Check installer safety and comment-preserving MCP configuration updates."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from jsonc_config import merge_mcp, parse

spec = importlib.util.spec_from_file_location('backend_installer', Path(__file__).with_name('install-backend.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class JsoncTests(unittest.TestCase):
    def setUp(self):
        self.entry = {'type': 'local', 'command': ['/usr/bin/python3', '/opt/agentshell/ui/agent-ui-mcp.py'], 'enabled': True}

    def test_add_root_mcp_preserves_comments_urls_and_trailing_commas(self):
        text = '{\n// keep this comment\n"url":"https://example.test/a//b", "literal":",}",\n"nested":[1,2,],\n}'
        updated = merge_mcp(text, self.entry)
        value = parse(updated)
        self.assertEqual(value['url'], 'https://example.test/a//b')
        self.assertEqual(value['literal'], ',}')
        self.assertEqual(value['nested'], [1, 2])
        self.assertIn('// keep this comment', updated)
        self.assertEqual(value['mcp']['agentshell-ui'], self.entry)
        self.assertEqual(merge_mcp(updated, self.entry), updated)

    def test_existing_entry_changes_without_replacing_other_mcp(self):
        text = '{"model":"custom-model",/* retain */"mcp":{"other":{"url":"https://example.test"},"agentshell-ui":{"enabled":false},},}'
        updated = merge_mcp(text, self.entry)
        self.assertIn('/* retain */', updated)
        self.assertIn('"other":{"url":"https://example.test"}', updated)
        value = parse(updated)
        self.assertEqual(value['model'], 'custom-model')
        self.assertEqual(value['mcp']['agentshell-ui'], self.entry)

    def test_empty_mcp_and_escaped_braces(self):
        text = '{"note":"a \\\"quoted\\\" brace }", "mcp":{/* comment */}}'
        updated = merge_mcp(text, self.entry)
        self.assertEqual(parse(updated)['note'], 'a "quoted" brace }')
        self.assertIn('/* comment */', updated)

    def test_invalid_config_is_rejected(self):
        for text in ['{"mcp":[]}', '{/* unclosed', '[]', '{"mcp":']:
            with self.assertRaises((ValueError, json.JSONDecodeError)):
                merge_mcp(text, self.entry)


class InstallerTests(unittest.TestCase):
    def test_fresh_lan_host_bootstraps_dns_and_certificate_dependencies(self):
        value=installer.Installer.__new__(installer.Installer)
        value.args=SimpleNamespace(skip_dependencies=False,lan_hostname='lan.example.test',with_browser='none')
        value.manifest=json.loads((installer.PROJECT/'deployment/backend-dependencies.json').read_text())
        value.agents=[]
        value.usable=lambda name:True
        value.ensure_python_runtime=lambda:None
        requested=[];calls=[]
        value.missing_packages=lambda packages:requested.extend(packages) or list(packages)
        value.run=lambda command,**kwargs:calls.append(command) or subprocess.CompletedProcess(command,0,stdout='rustc 1.95.0')
        value.dependencies()
        for name in ['certbot','python3-certbot-dns-cloudflare','iproute2']:
            self.assertIn(name,requested)
            self.assertTrue(any(c[0]=='apt-get' and 'install' in c and name in c for c in calls))

    def test_reinstall_preserves_managed_tls_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);value=self.fixture(root)
            value.runtime=root/'runtime.env';value.runtime.write_text('AUTH_TOKEN="retained-token"\nAGENTSHELL_TLS_CERT_PATH="/etc/agentshell/lan-tls/lan.example.test/cert.pem"\nAGENTSHELL_TLS_KEY_PATH="/etc/agentshell/lan-tls/lan.example.test/key.pem"\n')
            value.user_home=root/'home';value.args=SimpleNamespace(http_port=4010,https_port=4443)
            with patch.object(installer,'PROJECT',root),patch.dict(os.environ,{},clear=True):
                settings=value.settings()
            self.assertEqual(settings['AGENTSHELL_TLS_CERT_PATH'],'/etc/agentshell/lan-tls/lan.example.test/cert.pem')
            self.assertEqual(settings['AUTH_TOKEN'],'retained-token')

    def test_missing_installer_helper_is_rejected_before_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            value=installer.Installer.__new__(installer.Installer)
            value.manifest={'files':[], 'installerFiles':['scripts/jsonc_config.py']}
            with patch.object(installer,'PROJECT',root), self.assertRaisesRegex(ValueError,'jsonc_config.py'):
                value.validate_sources()

    def test_browser_report_rejects_missing_keyring_and_damaged_client(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);asset=root/'vnc.html';asset.write_bytes(b'pinned client')
            specification={'version':'1.5.0','aptPackages':['gnome-keyring'],
                           'executables':['Xvnc','google-chrome'], 'packages':{'jammy-amd64':{}},
                           'clientHashes':{'vnc.html':hashlib.sha256(asset.read_bytes()).hexdigest()}}
            value=installer.Installer.__new__(installer.Installer)
            value.user_env={'PATH':os.defpath}
            value.browser_specification=lambda:specification
            value.browser_platform=lambda:'jammy-amd64'
            value.run=lambda *args,**kwargs:subprocess.CompletedProcess([],0,stdout='1.5.0-1')
            value.usable=lambda name:True
            with patch.object(installer,'BROWSER_ASSETS',root), patch.object(installer.shutil,'which',return_value='/fixture/bin'), patch.object(value,'missing_packages',return_value=['gnome-keyring']):
                report=value.browser_dependency_report()
                self.assertFalse(report['softwareReady'])
                self.assertEqual(report['missingSystemPackages'],['gnome-keyring'])
            value.missing_packages=lambda packages=None:[]
            with patch.object(installer,'BROWSER_ASSETS',root), patch.object(installer.shutil,'which',return_value='/fixture/bin'):
                self.assertTrue(value.browser_dependency_report()['softwareReady'])
                asset.write_bytes(b'damaged client')
                report=value.browser_dependency_report()
                self.assertFalse(report['softwareReady'])
                self.assertFalse(report['clientAssets']['vnc.html'])

    def test_node_prefix_and_agent_versions(self):
        self.assertEqual(installer.version('v24.15.0\n'), (24, 15, 0))
        self.assertEqual(installer.version('codex-cli 0.162.1'), (0, 162, 1))
        self.assertEqual(installer.version('2.1.295 (Claude Code)'), (2, 1, 295))

    def test_dotenv_is_literal_and_never_executes_shell_content(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'runtime.env'
            token = 'literal-$(touch /never-run)-`never-run`-$value'
            path.write_text('AUTH_TOKEN=' + json.dumps(token) + '\nALLOWED_ORIGIN="https://example.test"\n')
            self.assertEqual(installer.dotenv(path)['AUTH_TOKEN'], token)
            self.assertEqual(installer.dotenv(path)['ALLOWED_ORIGIN'], 'https://example.test')

    def fixture(self, root):
        value = installer.Installer.__new__(installer.Installer)
        value.account = pwd.getpwuid(os.getuid())
        value.unit = root / 'system-units/agentshell.service'
        value.stamp = 'test'
        value.changed = {}
        value.calls = []

        def run(command, **kwargs):
            value.calls.append(command)
            if command[0] == 'install':
                if '-d' in command:
                    Path(command[-1]).mkdir(parents=True, exist_ok=True)
                else:
                    shutil.copyfile(command[-2], command[-1])
                    os.chmod(command[-1], int(command[command.index('-m') + 1], 8))
                return subprocess.CompletedProcess(command, 0)
            if command[0] == 'chown':
                return subprocess.CompletedProcess(command, 0)
            return subprocess.run(command, check=kwargs.get('check', True))

        value.run = run
        return value

    def test_existing_system_directory_is_not_chowned_and_unit_is_root_owned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            units = root / 'system-units'
            units.mkdir()
            source = root / 'source'
            source.write_text('[Unit]\nDescription=fixture\n')
            value = self.fixture(root)
            value.copy(source, value.unit)
            self.assertFalse(any(c[0] == 'install' and '-d' in c and c[-1] == str(units) for c in value.calls))
            command = next(c for c in value.calls if c[0] == 'install' and '-d' not in c)
            self.assertEqual(command[command.index('-o') + 1], '0')
            self.assertEqual(command[command.index('-g') + 1], '0')

    def test_update_backs_up_old_file_and_identical_rerun_does_not_duplicate_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, target = root / 'source', root / 'managed/config'
            source.write_text('new')
            target.parent.mkdir()
            target.write_text('old')
            value = self.fixture(root)
            value.copy(source, target, '600')
            self.assertEqual(value.changed[target].read_text(), 'old')
            self.assertEqual(target.read_text(), 'new')
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            value.copy(source, target, '600')
            self.assertEqual(len(list(target.parent.glob('*.agentshell-backup-*'))), 1)

    def test_legacy_service_token_is_preserved_without_repo_env_or_mcp_config(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = self.fixture(root)
            value.unit.parent.mkdir()
            value.unit.write_text('Environment="AUTH_TOKEN=legacy-$literal-token"\nEnvironment="ALLOWED_ORIGIN=https://example.test"\n')
            value.runtime = root / 'runtime.env'
            value.user_home = root / 'user-home'
            value.args = SimpleNamespace(http_port=4010, https_port=4443)
            # Capture the unit into memory, never print it during inspection.
            value.run = lambda command, **kwargs: subprocess.run(command, text=True, capture_output=True, check=True)
            with patch.object(installer, 'PROJECT', root), patch.dict(os.environ, {}, clear=True):
                settings = value.settings()
            self.assertEqual(settings['AUTH_TOKEN'], 'legacy-$literal-token')
            self.assertEqual(settings['ALLOWED_ORIGIN'], 'https://example.test')


if __name__ == '__main__':
    unittest.main()

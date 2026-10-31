#!/usr/bin/env python3
"""Portable macOS installer/launchd safety tests; no packages or services change."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('macos_installer', Path(__file__).with_name('install-macos.py'))
installer = importlib.util.module_from_spec(spec); spec.loader.exec_module(installer)
runtime = installer.runtime_module


class MacInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='agentshell-mac-install-test-')
        self.root = Path(self.temporary.name)
        _, self.args = installer.arguments(['--install-dir', str(self.root / 'Application Support/AgentShell'),
            '--launch-agent-dir', str(self.root / 'LaunchAgents'), '--log-dir', str(self.root / 'Logs'), '--agents', 'none'])
        self.value = installer.MacInstaller(self.args, home=self.root / 'home', environment={'PATH': '/usr/bin:/bin'})
        self.commands = []
        def run(command, **kwargs):
            self.commands.append(command)
            return subprocess.CompletedProcess(command, 0, stdout='', stderr='')
        self.value.run = run

    def tearDown(self): self.temporary.cleanup()

    def test_dry_run_and_check_do_not_create_files_or_run_install_commands(self):
        before = list(self.root.rglob('*'))
        self.args.dry_run = True
        with contextlib.redirect_stdout(io.StringIO()) as output: self.value.execute()
        self.assertEqual(str(self.value.prefix), json.loads(output.getvalue())['installDirectory'])
        self.args.dry_run = False; self.args.check = True
        with contextlib.redirect_stdout(io.StringIO()): self.value.execute()
        self.assertEqual(before, list(self.root.rglob('*')))
        self.assertFalse(any(command[0] in ('brew', 'launchctl', 'sudo') for command in self.commands))

    def test_existing_private_token_wins_over_checkout_defaults_and_is_never_printed(self):
        self.value.runtime.parent.mkdir(parents=True)
        self.value.runtime.write_text('AUTH_TOKEN="keep-$literal-token"\nAGENTSHELL_HTTP_PORT="4199"\n')
        project = self.root / 'project'; project.mkdir(); (project / '.env').write_text('AUTH_TOKEN="different-checkout-default"\n')
        with patch.object(installer, 'PROJECT', project): settings = self.value.settings()
        self.assertEqual('keep-$literal-token', settings['AUTH_TOKEN'])
        self.assertEqual('4199', settings['AGENTSHELL_HTTP_PORT'])
        self.assertNotIn('keep-$literal-token', json.dumps(self.value.plan()))
        self.value.environment['AUTH_TOKEN'] = 'explicit-override'
        self.assertEqual('explicit-override', self.value.settings()['AUTH_TOKEN'])

    def test_plist_preserves_tmux_and_keeps_credentials_in_a_private_runtime_file(self):
        settings = {'AUTH_TOKEN': 'synthetic-private-token', 'AGENTSHELL_HTTP_PORT': '4010'}
        self.value.configure(settings, {})
        value = plistlib.loads(self.value.plist.read_bytes())
        self.assertTrue(value['AbandonProcessGroup'])
        self.assertTrue(value['KeepAlive'])
        self.assertEqual('Aqua', value['LimitLoadToSessionType'])
        self.assertEqual(str(self.value.binary.parent), value['WorkingDirectory'])
        self.assertIn(str(self.value.runtime), value['ProgramArguments'])
        self.assertNotIn('synthetic-private-token', self.value.plist.read_text())
        self.assertEqual({'HOME', 'PATH'}, set(value['EnvironmentVariables']))
        self.assertEqual(0, self.value.runtime.stat().st_mode & 0o077)
        for path in ['/opt/homebrew/bin', '/usr/local/bin', str(self.value.home / '.cargo/bin')]:
            self.assertIn(path, value['EnvironmentVariables']['PATH'].split(':'))

    def test_headless_user_domain_selects_background_instead_of_implicit_aqua(self):
        self.value.domain = f'user/{os.getuid()}'
        self.value.configure({'AUTH_TOKEN': 'synthetic-token'}, {})
        self.assertEqual('Background', plistlib.loads(self.value.plist.read_bytes())['LimitLoadToSessionType'])

    def test_failed_launchctl_reports_operation_and_bounded_redacted_detail_only(self):
        self.value.environment['AUTH_TOKEN'] = 'synthetic-sensitive-token'
        command = ['launchctl', 'bootstrap', self.value.domain, str(self.value.plist)]
        failed = subprocess.CalledProcessError(5, command, stderr=b'Bootstrap failed: synthetic-sensitive-token\n' + b'x'*800)
        with patch.object(installer.subprocess, 'run', side_effect=failed):
            with self.assertRaises(ValueError) as error: installer.MacInstaller.run(self.value, command)
        self.assertIn('launchctl bootstrap failed with exit 5', str(error.exception))
        self.assertIn('<redacted>', str(error.exception))
        self.assertNotIn('synthetic-sensitive-token', str(error.exception))
        self.assertLess(len(str(error.exception)), 600)

    def test_payload_installs_both_identity_helpers_without_touching_chrome_profile(self):
        cookie = self.value.prefix / 'chrome/Default/Cookies'; cookie.parent.mkdir(parents=True); cookie.write_bytes(b'personal-fixture')
        binary = self.root / 'binary'; binary.write_bytes(b'synthetic-executable')
        self.value.install_files(binary)
        expected = (installer.PROJECT / 'scripts/process_identity.py').read_bytes()
        for name in ['hooks/process_identity.py', 'ui/process_identity.py']:
            self.assertEqual(expected, (self.value.prefix / name).read_bytes())
        self.assertEqual(b'personal-fixture', cookie.read_bytes())
        self.assertNotIn('sudo', (self.value.prefix / 'bin/agentshell-service').read_text())

    def test_failed_service_upgrade_restores_binary_settings_plist_and_existing_profile(self):
        old = {self.value.binary: b'old-binary', self.value.runtime: b'AUTH_TOKEN="old-token"\n', self.value.plist: b'old-plist'}
        for path, data in old.items(): path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        profile = self.value.prefix / 'chrome/Default/Cookies'; profile.parent.mkdir(parents=True); profile.write_bytes(b'keep-cookie')
        for path in old: self.value.write(path, b'replacement')
        self.value.service_changed = True; self.value.was_loaded = True
        self.value.rollback()
        for path, data in old.items(): self.assertEqual(data, path.read_bytes())
        self.assertEqual(b'keep-cookie', profile.read_bytes())
        self.assertTrue(any(command[:2] == ['launchctl', 'bootstrap'] for command in self.commands))

    def test_failure_before_service_changes_never_stops_the_existing_daemon(self):
        self.value.write(self.value.binary, b'replacement')
        self.value.rollback()
        self.assertFalse(self.commands)
        self.assertFalse(self.value.binary.exists())

    def test_unsupported_provider_and_invalid_origin_fail_before_any_mutation(self):
        for provider in ['kasmvnc', 'vm', 'native-macos']:
            self.args.with_browser = provider
            with self.assertRaises(ValueError): self.value.execute()
        self.args.with_browser = 'native-macos'
        self.args.browser_origin = 'https://user:password@example.test/path'
        with self.assertRaises(ValueError): self.value.execute()
        self.assertEqual([], list(self.root.iterdir()))
        self.assertFalse(self.commands)

    def test_native_provider_upgrade_keeps_profile_identity_and_private_password(self):
        self.args.with_browser = 'native-macos'; self.args.browser_origin = 'https://mac.example.test:4443'
        config = self.value.prefix / 'browser/browser.json'; config.parent.mkdir(parents=True)
        original = {'provider': 'macos-native', 'sessionId': 'existing-session', 'password': 'private-fixture-password',
                    'userDataDir': str(self.value.prefix / 'chrome')}
        config.write_text(json.dumps(original))
        project = self.root / 'source'; (project / 'scripts').mkdir(parents=True)
        for name in ['browser-runtime-macos.py', 'process_identity.py']: (project / 'scripts' / name).write_text('# fixture\n')
        bundle = self.root / 'built.app'; (bundle / 'Contents/MacOS').mkdir(parents=True)
        (bundle / 'Contents/MacOS/AgentShellBrowser').write_bytes(b'fixture-helper')
        settings = {}
        with patch.object(installer, 'PROJECT', project): self.value.configure_browser(settings, bundle)
        installed = json.loads(config.read_text())
        for key in ['sessionId', 'password', 'userDataDir']: self.assertEqual(original[key], installed[key])
        self.assertEqual(str(config), settings['AGENTSHELL_BROWSER_CONFIG'])
        self.assertEqual(self.value.python, installed['pythonPath'])
        self.assertTrue(Path(installed['nativeHelper']).is_file())
        self.assertEqual(0, config.stat().st_mode & 0o077)

    def test_symlink_settings_are_rejected_without_modifying_the_referent(self):
        outside = self.root / 'outside'; outside.write_text('keep')
        self.value.runtime.parent.mkdir(parents=True); self.value.runtime.symlink_to(outside)
        with self.assertRaises(ValueError): self.value.write(self.value.runtime, 'replacement')
        self.assertEqual('keep', outside.read_text())

    def test_failed_bundle_rename_restores_the_previous_signed_app_and_profile(self):
        destination = self.value.prefix / 'browser/AgentShellBrowser.app'
        old_helper = destination / 'Contents/MacOS/AgentShellBrowser'
        old_helper.parent.mkdir(parents=True); old_helper.write_bytes(b'old-helper'); old_helper.chmod(0o755)
        signature = destination / 'Contents/_CodeSignature/CodeResources'
        signature.parent.mkdir(); signature.write_bytes(b'old-signature')
        profile = self.value.prefix / 'chrome/Default/Cookies'
        profile.parent.mkdir(parents=True); profile.write_bytes(b'keep-cookie')
        source = self.root / 'replacement.app'; (source / 'Contents/MacOS').mkdir(parents=True)
        (source / 'Contents/MacOS/AgentShellBrowser').write_bytes(b'new-helper')
        replace = os.replace
        def fail_publish(old, new):
            if Path(old).name.startswith('.AgentShellBrowser-') and Path(new) == destination:
                raise OSError('injected bundle publication failure')
            return replace(old, new)
        with patch.object(installer.os, 'replace', side_effect=fail_publish):
            with self.assertRaises(OSError): self.value.install_bundle(source, destination)
            self.value.rollback()
        self.assertEqual(b'old-helper', old_helper.read_bytes())
        self.assertEqual(0o755, old_helper.stat().st_mode & 0o777)
        self.assertEqual(b'old-signature', signature.read_bytes())
        self.assertEqual(b'keep-cookie', profile.read_bytes())
        self.assertEqual(['AgentShellBrowser.app'], [path.name for path in destination.parent.iterdir()])
        self.assertFalse(self.commands)


class RuntimeTests(unittest.TestCase):
    def test_literal_settings_are_not_shell_code_and_explicit_environment_wins(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); settings = root / 'runtime.env'; executable = root / 'backend'
            executable.write_text('#!/bin/sh\n'); executable.chmod(0o755)
            payload = '$(touch should-never-exist)'
            settings.write_text('AUTH_TOKEN="stored"\nLITERAL=' + json.dumps(payload) + '\n')
            with patch.dict(os.environ, {'AUTH_TOKEN': 'explicit'}, clear=True), patch.object(runtime.os, 'chdir'), \
                 patch.object(runtime.os, 'umask'), patch.object(runtime.os, 'execve') as execute:
                runtime.run(executable, root, settings, ['--help'])
            command, argv, environment = execute.call_args.args
            self.assertEqual(str(executable), command); self.assertEqual([str(executable), '--help'], argv)
            self.assertEqual('explicit', environment['AUTH_TOKEN']); self.assertEqual(payload, environment['LITERAL'])
            self.assertFalse((root / 'should-never-exist').exists())

    def test_service_start_only_bootstraps_its_user_label_and_restart_does_not_kill_ports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); plist = root / 'agent.plist'; plist.write_bytes(plistlib.dumps({}))
            commands = []
            def command(arguments, **kwargs):
                commands.append(arguments)
                return subprocess.CompletedProcess(arguments, 1 if arguments[1] == 'print' else 0)
            with patch.object(runtime, 'service_paths', return_value=(plist, root, f'gui/{os.getuid()}')), \
                 patch.object(runtime.sys, 'platform', 'darwin'), patch.object(runtime.subprocess, 'run', side_effect=command):
                runtime.service_action('start', root)
            self.assertEqual(['launchctl', 'bootstrap', f'gui/{os.getuid()}', str(plist)], commands[1])
            self.assertFalse(any(value in ('sudo', 'kill', 'lsof') for command in commands for value in command))


if __name__ == '__main__': unittest.main()

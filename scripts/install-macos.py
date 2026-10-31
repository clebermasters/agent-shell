#!/usr/bin/env python3
"""Install the native macOS daemon for the current user without sudo."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.parse
import uuid

PROJECT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
specification = importlib.util.spec_from_file_location('agentshell_runtime', Path(__file__).with_name('backend-runtime.py'))
runtime_module = importlib.util.module_from_spec(specification)
specification.loader.exec_module(runtime_module)

FILES = [
    ('scripts/backend-runtime.py', 'runtime/backend-runtime.py'),
    ('scripts/chat-binding-hook.py', 'hooks/chat-binding-hook.py'),
    ('scripts/process_identity.py', 'hooks/process_identity.py'),
    ('scripts/agent-ui-mcp.py', 'ui/agent-ui-mcp.py'),
    ('scripts/process_identity.py', 'ui/process_identity.py'),
    ('scripts/reload-codex-mcp.py', 'hooks/reload-codex-mcp.py'),
]


def service_path(prefix, home, inherited):
    paths = [prefix / 'bin', '/opt/homebrew/bin', '/opt/homebrew/sbin', '/usr/local/bin', '/usr/local/sbin',
             '/opt/homebrew/opt/openssl@3/bin', '/usr/local/opt/openssl@3/bin', home / '.cargo/bin',
             home / '.local/bin', home / '.npm-global/bin', home / '.opencode/bin', home / '.bun/bin',
             *inherited.split(':'), '/usr/bin', '/bin', '/usr/sbin', '/sbin']
    return ':'.join(dict.fromkeys(str(path) for path in paths if str(path)))


def valid_port(value):
    port = int(value)
    if not 1 <= port <= 65535: raise ValueError('Ports must be between 1 and 65535')
    return port


class MacInstaller:
    def __init__(self, args, home=None, environment=None):
        self.args = args
        self.home = home or Path.home()
        self.prefix = args.install_dir.expanduser().absolute()
        self.plist = args.launch_agent_dir.expanduser().absolute() / (runtime_module.LABEL + '.plist')
        self.logs = args.log_dir.expanduser().absolute()
        self.domain = args.domain or f'gui/{os.getuid()}'
        if self.domain not in (f'gui/{os.getuid()}', f'user/{os.getuid()}'):
            raise ValueError('LaunchAgent domain must belong to the current user')
        self.runtime = self.prefix / 'backend/runtime.env'
        self.binary = self.prefix / 'backend/agentshell-backend'
        self.environment = dict(os.environ if environment is None else environment)
        self.environment.update(HOME=str(self.home), PATH=service_path(self.prefix, self.home, self.environment.get('PATH', '')))
        self.python = shutil.which('python3', path=self.environment['PATH']) or sys.executable
        self.host_python = self.python
        self.changed = {}
        self.bundles = []
        self.browser_build = None
        self.was_loaded = False
        self.service_changed = False
        self.stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')

    def run(self, command, **kwargs):
        # Never print commands/environment: downstream configuration uses private tokens.
        return subprocess.run(command, env=kwargs.pop('env', self.environment), check=kwargs.pop('check', True), **kwargs)

    def validate_options(self):
        if self.args.with_browser in ('kasmvnc', 'vm'):
            raise ValueError('Use --with-browser native-macos for native Chrome. KasmVNC requires Linux. No files changed')
        if self.args.with_browser in ('native', 'native-macos'):
            self.browser_origin()
        if self.args.lan_hostname:
            raise ValueError('Automatic LAN DNS/certbot provisioning is currently Linux-specific; supply an existing TLS pair on macOS. No files changed')
        if self.args.agents not in ('installed', 'none'):
            names = self.args.agents.split(',')
            if any(name not in ('codex', 'claude', 'opencode') for name in names):
                raise ValueError('--agents must be installed, none, or a comma-separated subset of codex,claude,opencode')

    def settings(self):
        previous = runtime_module.dotenv(self.runtime)
        source = runtime_module.dotenv(PROJECT / '.env')
        settings = {key: value for key, value in previous.items()
                    if key.startswith('AGENTSHELL_') or key in ('AUTH_TOKEN', 'ALLOWED_ORIGIN', 'RUST_LOG')}
        token = self.environment.get('AUTH_TOKEN') or previous.get('AUTH_TOKEN') or source.get('AUTH_TOKEN')
        if not token or token.startswith('<') or token in ('your-secret-token', 'replace-with-a-long-random-token'):
            token = secrets.token_urlsafe(48)
        if any(character in token for character in ('\0', '\r', '\n')): raise ValueError('AUTH_TOKEN cannot contain line breaks or NUL')
        settings.update(AUTH_TOKEN=token, AGENTSHELL_BACKEND_BIN=str(self.binary),
                        ALLOWED_ORIGIN=self.environment.get('ALLOWED_ORIGIN', previous.get('ALLOWED_ORIGIN', source.get('ALLOWED_ORIGIN', ''))),
                        RUST_LOG=self.environment.get('RUST_LOG', previous.get('RUST_LOG', 'info')))
        for name in ('AGENTSHELL_TLS_CERT_PATH', 'AGENTSHELL_TLS_KEY_PATH'):
            value = self.environment.get(name) or previous.get(name) or source.get(name)
            if value: settings[name] = value
        if bool(settings.get('AGENTSHELL_TLS_CERT_PATH')) != bool(settings.get('AGENTSHELL_TLS_KEY_PATH')):
            raise ValueError('Supply both AGENTSHELL_TLS_CERT_PATH and AGENTSHELL_TLS_KEY_PATH')
        for argument, name, fallback in [('http_port', 'AGENTSHELL_HTTP_PORT', 4010), ('https_port', 'AGENTSHELL_HTTPS_PORT', 4443)]:
            value = getattr(self.args, argument) or self.environment.get(name) or previous.get(name) or source.get(name) or fallback
            settings[name] = str(valid_port(value))
        return settings

    def plan(self):
        self.validate_options()
        return {'platform': sys.platform, 'nativeMacOS': sys.platform == 'darwin', 'installDirectory': str(self.prefix),
                'launchAgent': str(self.plist), 'launchDomain': self.domain, 'logDirectory': str(self.logs),
                'privateRuntimeExists': self.runtime.is_file(), 'python': self.python,
                'dependencies': ['tmux', 'python', 'rust', 'openssl@3'],
                'build': None if self.args.binary else ['cargo', 'build', '--release', '--locked', '--manifest-path', str(PROJECT / 'backend-rust/Cargo.toml')],
                'agents': self.args.agents, 'start': not self.args.no_start,
                'browserProvider': 'macos-native' if self.args.with_browser in ('native', 'native-macos') else None,
                'credentials': 'Preserve the existing private runtime token; otherwise use AUTH_TOKEN or source .env, then generate one',
                'serviceCommands': [['launchctl', 'bootstrap', self.domain, str(self.plist)],
                                    ['launchctl', 'kickstart', self.domain + '/' + runtime_module.LABEL]]}

    def check(self):
        report = self.plan()
        commands = ['brew', 'tmux', 'python3', 'cargo', 'openssl', 'codex', 'claude', 'opencode']
        report['commands'] = {name: shutil.which(name, path=self.environment['PATH']) is not None for name in commands}
        report['commandLineTools'] = self.run(['xcode-select', '-p'], check=False, capture_output=True).returncode == 0 if sys.platform == 'darwin' else False
        return report

    def remember(self, path):
        if path in self.changed: return
        if path.is_symlink(): raise ValueError('Refusing to replace a symlink: ' + str(path))
        self.changed[path] = (path.read_bytes(), path.stat().st_mode & 0o777) if path.is_file() else None

    def write(self, path, data, mode=0o600):
        self.remember(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, str): data = data.encode()
        if path.is_file() and path.read_bytes() == data:
            path.chmod(mode); return
        if path.is_file(): shutil.copy2(path, path.with_name(path.name + '.agentshell-backup-' + self.stamp))
        fd, temporary = tempfile.mkstemp(prefix='.agentshell-', dir=path.parent)
        try:
            with os.fdopen(fd, 'wb') as output: output.write(data)
            os.chmod(temporary, mode); os.replace(temporary, path)
        finally: Path(temporary).unlink(missing_ok=True)

    def dependencies(self):
        if self.args.skip_dependencies: return
        if (not self.args.binary or self.args.with_browser != 'none') and self.run(['xcode-select', '-p'], check=False, capture_output=True).returncode:
            raise ValueError('Install Xcode Command Line Tools with xcode-select --install, finish installation, then rerun')
        requested = [('tmux', 'tmux'), ('python3', 'python'), ('openssl', 'openssl@3')]
        if not self.args.binary: requested.append(('cargo', 'rust'))
        needed = [formula for command, formula in requested
                  if not shutil.which(command, path=self.environment['PATH'])]
        # Apple’s LibreSSL executable can lack the -addext option used for TLS.
        if not self.args.no_tls and not any(Path(path, 'openssl').is_file() for path in
                ['/opt/homebrew/opt/openssl@3/bin', '/usr/local/opt/openssl@3/bin']):
            if 'openssl@3' not in needed: needed.append('openssl@3')
        if needed:
            brew = shutil.which('brew', path=self.environment['PATH'])
            if not brew: raise ValueError('Install Homebrew from https://brew.sh, then rerun; its installer is not downloaded or executed automatically')
            self.run([brew, 'install', *needed])
        self.python = shutil.which('python3', path=self.environment['PATH']) or self.python
        self.host_python = self.python
        if self.args.with_browser in ('native', 'native-macos') and not Path('/Applications/Google Chrome.app').is_dir():
            brew = shutil.which('brew', path=self.environment['PATH'])
            if not brew: raise ValueError('Install Google Chrome or install Homebrew to bootstrap the requested browser')
            self.run([brew, 'install', '--cask', 'google-chrome'])

    def python_runtime(self):
        python = self.prefix / 'venv/bin/python'
        if not python.is_file(): self.run([self.host_python, '-m', 'venv', str(self.prefix / 'venv')])
        self.python = str(python)
        probe = self.run([self.python, '-c', 'import importlib.metadata;v=int(importlib.metadata.version("websockets").split(".")[0]);assert 13<=v<16'], check=False, capture_output=True)
        if probe.returncode:
            if self.args.skip_dependencies:
                if self.args.with_browser in ('native', 'native-macos'):
                    raise ValueError('Managed Python needs websockets>=13,<16; rerun without --skip-dependencies')
                return
            self.run([self.python, '-m', 'pip', 'install', '--disable-pip-version-check', 'websockets>=13,<16'])

    def browser_origin(self):
        config = self.prefix / 'browser/browser.json'
        previous = json.loads(config.read_text()) if config.is_file() else {}
        origin = self.args.browser_origin or previous.get('publicOrigin', '')
        value = urllib.parse.urlsplit(origin)
        if value.scheme != 'https' or not value.hostname or value.username or value.password or value.path or value.query or value.fragment:
            raise ValueError('Native browser requires --browser-origin https://trusted-host[:port] without a path')
        return origin.rstrip('/')

    def build_browser(self):
        if self.args.with_browser not in ('native', 'native-macos'): return None
        build = PROJECT / 'scripts/build-macos-browser.sh'
        if not build.is_file(): raise ValueError('Native browser build script is missing')
        self.browser_build = tempfile.TemporaryDirectory(prefix='agentshell-native-browser-build-')
        bundle = Path(self.browser_build.name) / 'AgentShellBrowser.app'
        self.run(['bash', str(build), '--output', str(bundle)])
        if not (bundle / 'Contents/MacOS/AgentShellBrowser').is_file(): raise ValueError('Native browser helper build did not produce its app bundle')
        return bundle

    def install_bundle(self, source, destination):
        if destination.is_symlink(): raise ValueError('Browser helper bundle cannot be a symlink')
        destination.parent.mkdir(parents=True, exist_ok=True)
        staging = destination.with_name('.AgentShellBrowser-' + secrets.token_hex(6) + '.app')
        backup = destination.with_name(destination.name + '.agentshell-backup-' + self.stamp) if destination.exists() else None
        try:
            shutil.copytree(source, staging, symlinks=True)
            if backup: os.replace(destination, backup)
            # Once the old bundle moves, rollback must know how to restore it
            # even if publishing the replacement fails.
            self.bundles.append((destination, backup))
            os.replace(staging, destination)
        finally:
            if staging.exists(): shutil.rmtree(staging)

    def configure_browser(self, settings, bundle):
        if bundle is None: return
        config = self.prefix / 'browser/browser.json'
        previous = json.loads(config.read_text()) if config.is_file() else {}
        if previous and previous.get('provider', 'macos-native') != 'macos-native':
            raise ValueError('Existing browser uses a different provider; explicitly reconfigure it before replacing it')
        destination = self.prefix / 'browser/AgentShellBrowser.app'
        self.install_bundle(bundle, destination)
        for name in ['browser-runtime-macos.py', 'process_identity.py']:
            self.write(self.prefix / 'browser' / name, (PROJECT / 'scripts' / name).read_bytes(), 0o755 if name.startswith('browser-') else 0o644)
        runtime_dir = self.prefix / 'state/browser'; runtime_dir.mkdir(parents=True, exist_ok=True); runtime_dir.chmod(0o700)
        value = dict(previous, provider='macos-native', sessionId=previous.get('sessionId', str(uuid.uuid4())),
                     userDataDir=previous.get('userDataDir', str(self.prefix / 'chrome')),
                     runtimeDir=str(runtime_dir), port=previous.get('port', 18996), password=previous.get('password', secrets.token_urlsafe(48)),
                     publicOrigin=self.browser_origin(), runner=str(self.prefix / 'browser/browser-runtime-macos.py'),
                     pythonPath=self.python, nativeHelper=str(destination / 'Contents/MacOS/AgentShellBrowser'),
                     displayName=previous.get('displayName', 'Chrome'), supervision='process')
        self.write(config, json.dumps(value, indent=2) + '\n')
        settings['AGENTSHELL_BROWSER_CONFIG'] = str(config)

    def build(self):
        if self.args.binary:
            candidate = self.args.binary.expanduser().absolute()
            if not candidate.is_file(): raise ValueError('Supplied backend binary does not exist')
            return candidate
        if not shutil.which('cargo', path=self.environment['PATH']): raise ValueError('Cargo is missing; install Rust or rerun without --skip-dependencies')
        self.run(['cargo', 'build', '--release', '--locked', '--manifest-path', str(PROJECT / 'backend-rust/Cargo.toml')])
        return PROJECT / 'backend-rust/target/release/agentshell-backend'

    def install_files(self, binary):
        self.write(self.binary, binary.read_bytes(), 0o755)
        hashes = {'backend/agentshell-backend': hashlib.sha256(binary.read_bytes()).hexdigest()}
        for source, destination in FILES:
            data = (PROJECT / source).read_bytes()
            self.write(self.prefix / destination, data, 0o755 if destination.endswith('.py') else 0o644)
            hashes[destination] = hashlib.sha256(data).hexdigest()
        assets = PROJECT / 'android-native/app/src/main/assets/ui'
        for source in assets.rglob('*'):
            if source.is_file(): self.write(self.prefix / 'ui' / source.relative_to(assets), source.read_bytes(), 0o644)
        binary_launcher = '#!/bin/sh\nexec ' + shlex.quote(str(self.binary)) + ' "$@"\n'
        service_launcher = '#!/bin/sh\nexec ' + shlex.quote(self.python) + ' ' + shlex.quote(str(self.prefix / 'runtime/backend-runtime.py')) + ' service --install-dir ' + shlex.quote(str(self.prefix)) + ' "$@"\n'
        self.write(self.prefix / 'bin/agentshell-backend', binary_launcher, 0o755)
        self.write(self.prefix / 'bin/agentshell-service', service_launcher, 0o755)
        return hashes

    def certificates(self, settings):
        if self.args.no_tls or settings.get('AGENTSHELL_TLS_CERT_PATH'): return
        cert, key = self.prefix / 'certs/cert.pem', self.prefix / 'certs/key.pem'
        if cert.exists() != key.exists(): raise ValueError('Incomplete TLS certificate pair; supply both files or use --no-tls')
        if not cert.exists():
            source_cert, source_key = PROJECT / 'certs/cert.pem', PROJECT / 'certs/key.pem'
            if source_cert.is_file() and source_key.is_file():
                self.write(cert, source_cert.read_bytes()); self.write(key, source_key.read_bytes())
            else:
                with tempfile.TemporaryDirectory(prefix='agentshell-macos-tls-') as temporary:
                    temporary = Path(temporary)
                    self.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-sha256', '-nodes', '-days', '365',
                              '-subj', '/CN=AgentShell', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
                              '-keyout', str(temporary / 'key.pem'), '-out', str(temporary / 'cert.pem')], capture_output=True)
                    self.write(cert, (temporary / 'cert.pem').read_bytes()); self.write(key, (temporary / 'key.pem').read_bytes())
        settings.update(AGENTSHELL_TLS_CERT_PATH=str(cert), AGENTSHELL_TLS_KEY_PATH=str(key))

    def configure(self, settings, hashes):
        self.write(self.runtime, ''.join(key + '=' + json.dumps(value) + '\n' for key, value in settings.items()))
        self.logs.mkdir(parents=True, exist_ok=True)
        plist = {'Label': runtime_module.LABEL, 'ProgramArguments': [self.python, str(self.prefix / 'runtime/backend-runtime.py'), 'run',
                 '--executable', str(self.binary), '--working-directory', str(self.binary.parent), '--env-file', str(self.runtime)],
                 'WorkingDirectory': str(self.binary.parent), 'EnvironmentVariables': {'HOME': str(self.home), 'PATH': self.environment['PATH']},
                 'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 5, 'AbandonProcessGroup': True, 'Umask': 63,
                 'StandardOutPath': str(self.logs / 'stdout.log'), 'StandardErrorPath': str(self.logs / 'stderr.log')}
        self.write(self.plist, plistlib.dumps(plist))
        self.run(['plutil', '-lint', str(self.plist)], capture_output=True)
        self.write(self.prefix / 'installation.json', json.dumps({'installedAt': self.stamp, 'platform': 'macos', 'files': hashes,
                   'launchAgent': str(self.plist), 'logDirectory': str(self.logs), 'launchDomain': self.domain}, indent=2) + '\n')

    def health(self, settings):
        request = urllib.request.Request('http://127.0.0.1:' + settings['AGENTSHELL_HTTP_PORT'] + '/api/clients',
                                         headers={'X-Auth-Token': settings['AUTH_TOKEN']})
        with urllib.request.urlopen(request, timeout=2) as response:
            return response.status == 200 and isinstance(json.load(response).get('clients'), int)

    def start(self, settings):
        if self.args.no_start: return
        target = self.domain + '/' + runtime_module.LABEL
        self.was_loaded = self.run(['launchctl', 'print', target], check=False, capture_output=True).returncode == 0
        self.service_changed = True
        if self.was_loaded: self.run(['launchctl', 'bootout', target], capture_output=True)
        self.run(['launchctl', 'enable', target], capture_output=True)
        self.run(['launchctl', 'bootstrap', self.domain, str(self.plist)], capture_output=True)
        self.run(['launchctl', 'kickstart', target], capture_output=True)
        deadline = time.monotonic() + 15
        while True:
            try:
                if self.health(settings): return
            except (OSError, ValueError): pass
            if time.monotonic() >= deadline: raise ValueError('Installed backend did not pass authenticated startup health check')
            time.sleep(.2)

    def configure_agents(self, settings):
        if self.args.agents == 'none': return
        selected = [name for name in ('codex', 'claude', 'opencode') if shutil.which(name, path=self.environment['PATH'])]
        if self.args.agents != 'installed':
            requested = self.args.agents.split(',')
            missing = set(requested) - set(selected)
            if missing: raise ValueError('Install the requested agent CLIs first: ' + ', '.join(sorted(missing)))
            selected = requested
        if not selected: return
        for name in ['.codex/hooks.json', '.codex/config.toml', '.claude/settings.json', '.claude.json',
                     '.config/opencode/opencode.json', '.config/opencode/opencode.jsonc',
                     '.config/agentshell/chat-binding-hook.json', '.config/agentshell/interactive-ui.json']:
            self.remember(self.home / name)
        environment = dict(self.environment, **settings)
        url = 'http://127.0.0.1:' + settings['AGENTSHELL_HTTP_PORT']
        if set(selected) & {'codex', 'claude'}:
            self.run([self.python, str(PROJECT / 'scripts/install-chat-binding-hooks.py'), '--user-home', str(self.home),
                      '--script', str(self.prefix / 'hooks/chat-binding-hook.py'), '--url', url, '--python', self.python], env=environment)
        self.run([self.python, str(PROJECT / 'scripts/install-ui-mcp.py'), '--user-home', str(self.home),
                  '--script', str(self.prefix / 'ui/agent-ui-mcp.py'), '--url', url, '--python', self.python,
                  '--tools', ','.join(selected)], env=environment)
        # Existing agent processes remain running; fresh processes read the merged settings.

    def rollback(self):
        target = self.domain + '/' + runtime_module.LABEL
        if self.service_changed: self.run(['launchctl', 'bootout', target], check=False, capture_output=True)
        changed, self.changed = self.changed, {}
        for path, previous in reversed(list(changed.items())):
            if previous is None: path.unlink(missing_ok=True)
            else:
                data, mode = previous
                fd, temporary = tempfile.mkstemp(prefix='.agentshell-rollback-', dir=path.parent)
                try:
                    with os.fdopen(fd, 'wb') as output: output.write(data)
                    os.chmod(temporary, mode); os.replace(temporary, path)
                finally: Path(temporary).unlink(missing_ok=True)
        for destination, backup in reversed(self.bundles):
            if destination.exists(): shutil.rmtree(destination)
            if backup: os.replace(backup, destination)
        if self.service_changed and self.was_loaded:
            self.run(['launchctl', 'bootstrap', self.domain, str(self.plist)], check=False, capture_output=True)
            self.run(['launchctl', 'kickstart', target], check=False, capture_output=True)

    def execute(self):
        self.validate_options()
        if self.args.dry_run: print(json.dumps(self.plan(), indent=2)); return
        if self.args.check: print(json.dumps(self.check(), indent=2)); return
        if sys.platform != 'darwin': raise ValueError('Native installation requires macOS; --check and --dry-run are read-only on other platforms')
        if os.geteuid() == 0: raise ValueError('Run the macOS installer as your normal user without sudo')
        self.dependencies()
        self.python_runtime()
        settings = self.settings()
        binary = self.build()
        bundle = self.build_browser()
        try:
            hashes = self.install_files(binary)
            self.configure_browser(settings, bundle)
            self.certificates(settings)
            self.configure(settings, hashes)
            self.start(settings)
            self.configure_agents(settings)
        except Exception:
            self.rollback(); raise
        finally:
            if self.browser_build is not None: self.browser_build.cleanup()
        print('Native macOS daemon installed' + ('; startup was skipped.' if self.args.no_start else ' and authenticated startup verified.'))
        print('Private settings: ' + str(self.runtime))
        print('Service control: ' + str(self.prefix / 'bin/agentshell-service'))


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install-dir', type=Path, default=Path.home() / 'Library/Application Support/AgentShell')
    parser.add_argument('--launch-agent-dir', type=Path, default=Path.home() / 'Library/LaunchAgents')
    parser.add_argument('--log-dir', type=Path, default=Path.home() / 'Library/Logs/AgentShell')
    parser.add_argument('--domain', help='Current-user launchd domain; default gui/UID, or user/UID for a headless user session')
    parser.add_argument('--http-port', type=valid_port); parser.add_argument('--https-port', type=valid_port)
    parser.add_argument('--check', action='store_true'); parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--skip-dependencies', action='store_true'); parser.add_argument('--no-start', action='store_true')
    parser.add_argument('--no-tls', action='store_true'); parser.add_argument('--binary', type=Path)
    parser.add_argument('--agents', default='installed', help='Reuse/configure installed CLIs; installed, none, or codex,claude,opencode')
    parser.add_argument('--with-browser', choices=['none', 'native-macos', 'native', 'kasmvnc', 'vm'], default='none')
    parser.add_argument('--browser-origin', help='Trusted HTTPS origin for the native Chrome viewer')
    parser.add_argument('--lan-hostname', help='Linux provisioning option; rejected before changes on macOS')
    return parser, parser.parse_args(argv)


def main():
    parser, args = arguments()
    try:
        if sys.version_info < (3, 9): raise ValueError('Python 3.9+ is required')
        MacInstaller(args).execute()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        # Do not include captured subprocess output, which could contain private configuration.
        if isinstance(error, subprocess.CalledProcessError): message = 'Installation command failed with exit ' + str(error.returncode)
        else: message = str(error)
        parser.exit(1, 'macOS installation failed: ' + message + '\n')


if __name__ == '__main__': main()

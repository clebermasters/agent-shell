#!/usr/bin/env python3
"""One entry point for backend, native-agent dependencies and Smart UI MCP."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import pwd
import re
import secrets
import shlex
import shutil
from string import Template
import subprocess
import sys
import tempfile
import time
import urllib.request

PROJECT = Path(__file__).resolve().parents[1]


def dotenv(path):
    """Read literal settings without executing a shell or printing secrets."""
    if not path.exists():
        return {}
    result = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        name, value = line.removeprefix('export ').split('=', 1)
        value = value.strip()
        if value.startswith('"') and value.endswith('"'):
            try:
                value = json.loads(value)
            except ValueError:
                value = value[1:-1]
        elif value.startswith("'") and value.endswith("'"):
            value = value[1:-1]
        result[name.strip()] = value
    return result


def version(text):
    found = re.search(r'(?<!\d)(\d+)\.(\d+)\.(\d+)', text)
    return tuple(map(int, found.groups())) if found else (0, 0, 0)


class Installer:
    def __init__(self, args):
        self.args = args
        self.manifest = json.loads((PROJECT / 'deployment/backend-dependencies.json').read_text())
        self.account = pwd.getpwnam(args.service_user)
        self.user_home = Path(self.account.pw_dir)
        self.install_dir = args.install_dir.resolve()
        self.tools = self.install_dir / 'tools'
        self.node = self.install_dir / 'node'
        self.venv = self.install_dir / 'venv'
        self.unit = args.unit_dir.resolve() / 'agentshell.service'
        self.runtime = self.install_dir / 'backend/runtime.env'
        self.changed = {}
        self.stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        self.user_env = dict(os.environ, HOME=str(self.user_home), USER=args.service_user, LOGNAME=args.service_user)
        login = self.run(['bash', '-lc', 'printf "%s" "$PATH"'], user=True, capture=True)
        paths = [self.tools / 'bin', self.node / 'bin', self.user_home / '.local/bin',
                 self.user_home / '.npm-global/bin', self.user_home / '.opencode/bin',
                 self.user_home / '.bun/bin', self.user_home / '.cargo/bin',
                 *login.stdout.strip().split(':'), '/usr/local/bin', '/usr/bin', '/bin', '/usr/sbin', '/sbin']
        self.service_path = ':'.join(dict.fromkeys(str(path) for path in paths if str(path)))
        self.user_env['PATH'] = self.service_path
        if args.agents == 'installed':
            self.agents = [name for name in self.manifest['agents'] if self.usable(name)]
        elif args.agents == 'none':
            self.agents = []
        else:
            self.agents = list(dict.fromkeys(args.agents.split(',')))
            if any(name not in self.manifest['agents'] for name in self.agents):
                raise ValueError('--agents must be codex,claude,opencode, installed, or none')

    def run(self, command, *, root=False, user=False, capture=False, env=None, check=True):
        environment = dict(self.user_env if user else os.environ)
        if env:
            environment.update(env)
        options = {}
        if root and os.geteuid() != 0:
            command = ['sudo', *(['--preserve-env=DEBIAN_FRONTEND'] if env and 'DEBIAN_FRONTEND' in env else []), *command]
        elif user and os.geteuid() == 0 and self.account.pw_uid != 0:
            options = dict(user=self.account.pw_uid, group=self.account.pw_gid,
                           extra_groups=os.getgrouplist(self.args.service_user, self.account.pw_gid))
        elif user and os.geteuid() != self.account.pw_uid:
            raise ValueError('Run with sudo to install for another service user')
        # Never print command/env objects: they may contain runtime credentials.
        return subprocess.run(command, env=environment, text=True, capture_output=capture,
                              check=check, **options)

    def usable(self, name):
        if not shutil.which(name, path=self.user_env.get('PATH', os.defpath)):
            return False
        try:
            result = self.run([name, '--version'], user=True, capture=True, check=False)
            return result.returncode == 0 and version(result.stdout) != (0, 0, 0)
        except OSError:
            return False

    def missing_packages(self):
        result = []
        for package in self.manifest['aptPackages']:
            found = subprocess.run(['dpkg-query', '-W', '-f=${Status}', package], text=True, capture_output=True)
            if found.returncode or found.stdout != 'install ok installed':
                result.append(package)
        return result

    def validate_sources(self):
        for entry in self.manifest['files']:
            path = PROJECT / entry['source']
            if not path.exists():
                raise ValueError('Required repository file is missing: ' + entry['source'])
        for path in ['deployment/templates/agentshell.service', 'deployment/templates/agentshell-path.sh', 'backend-rust/Cargo.lock']:
            if not (PROJECT / path).is_file():
                raise ValueError('Required repository file is missing: ' + path)

    def dependencies(self):
        if self.args.skip_dependencies:
            print('Dependency bootstrap skipped explicitly.', flush=True)
            return
        missing = self.missing_packages()
        if missing:
            print('Installing missing system packages: ' + ', '.join(missing), flush=True)
            refreshed = self.run(['apt-get', 'update'], root=True, check=False)
            if refreshed.returncode:
                print('APT index refresh reported an error; trying required packages from the available indexes with normal signature checks.', flush=True)
            self.run(['apt-get', '-o', 'DPkg::Lock::Timeout=120', 'install', '-y', '--no-install-recommends', *missing], root=True,
                     env={'DEBIAN_FRONTEND': 'noninteractive'})
        self.ensure_python_runtime()
        rust = self.manifest['rust']
        current = self.run(['rustc', '--version'], user=True, capture=True, check=False) if self.usable('rustc') else None
        if current is None or version(current.stdout) < version(rust['minimum']):
            print('Installing the pinned Rust toolchain for the service user.', flush=True)
            with tempfile.TemporaryDirectory(prefix='agentshell-rustup-') as directory:
                path = Path(directory) / 'rustup.sh'
                self.download(rust['installer'], path)
                # The user must be able to read a root-created staging directory.
                os.chmod(directory, 0o755)
                os.chmod(path, 0o644)
                self.run(['sh', str(path), '-y', '--profile', 'minimal', '--no-modify-path',
                          '--default-toolchain', rust['version']], user=True)
        if self.agents:
            self.ensure_node()
        for name in self.agents:
            if self.usable(name):
                print('Keeping working ' + name + ' installation.', flush=True)
                continue
            specification = self.manifest['agents'][name]
            print('Installing missing/unusable ' + name + ' in the managed user directory.', flush=True)
            self.mkdir(self.tools)
            self.mkdir(self.install_dir / 'cache/npm')
            self.run(['npm', 'install', '--global', '--prefix', str(self.tools), '--registry',
                      'https://registry.npmjs.org', specification['package'] + '@' + specification['version']], user=True,
                     env={'NPM_CONFIG_CACHE': str(self.install_dir / 'cache/npm')})
            if not self.usable(name):
                raise ValueError(name + ' failed its installed version check')
            local_bin = self.user_home / '.local/bin'
            self.run(['mkdir', '-p', str(local_bin)], user=True)
            link = local_bin / name
            if not link.exists() and not link.is_symlink():
                self.run(['ln', '-s', str(self.tools / 'bin' / name), str(link)], user=True)

    @staticmethod
    def download(url, target):
        with urllib.request.urlopen(url, timeout=60) as response, target.open('wb') as output:
            shutil.copyfileobj(response, output)

    def ensure_python_runtime(self):
        python = self.venv / 'bin/python'
        if not python.exists():
            self.mkdir(self.venv)
            self.run(['/usr/bin/python3', '-m', 'venv', str(self.venv)], user=True)
        expected = self.manifest['pythonPackages']
        code = 'import importlib.metadata,json;print(json.dumps({name:importlib.metadata.version(name) for name in ' + repr(list(expected)) + '}))'
        result = self.run([str(python), '-c', code], user=True, capture=True, check=False)
        if result.returncode == 0 and json.loads(result.stdout) == expected:
            return
        self.mkdir(self.install_dir / 'cache/pip')
        self.run([str(python), '-m', 'pip', 'install', '--disable-pip-version-check', '--index-url', 'https://pypi.org/simple',
                  *[name + '==' + release for name, release in expected.items()]], user=True,
                 env={'PIP_CACHE_DIR': str(self.install_dir / 'cache/pip')})

    def ensure_node(self):
        spec = self.manifest['node']
        if self.usable('node') and self.usable('npm'):
            existing = self.run(['node', '--version'], user=True, capture=True)
            if version(existing.stdout)[0] >= spec['minimumMajor']:
                return
        architecture = {'x86_64': 'x64', 'aarch64': 'arm64'}.get(platform.machine())
        if architecture is None:
            raise ValueError('Managed Node binaries support Linux x64 and ARM64')
        filename = 'node-v' + spec['version'] + '-linux-' + architecture + '.tar.xz'
        base = spec['distribution'] + '/v' + spec['version'] + '/'
        print('Installing pinned Node.js and npm without replacing system Node.js.', flush=True)
        with tempfile.TemporaryDirectory(prefix='agentshell-node-') as directory:
            archive = Path(directory) / filename
            checksums = Path(directory) / 'SHASUMS256.txt'
            self.download(base + filename, archive)
            self.download(base + 'SHASUMS256.txt', checksums)
            expected = next((line.split()[0] for line in checksums.read_text().splitlines()
                             if len(line.split()) == 2 and line.split()[1] == filename), None)
            if expected != hashlib.sha256(archive.read_bytes()).hexdigest():
                raise ValueError('Node.js archive checksum mismatch')
            os.chmod(directory, 0o755)
            os.chmod(archive, 0o644)
            self.mkdir(self.node)
            self.run(['tar', '-xJf', str(archive), '--strip-components=1', '-C', str(self.node)], user=True)

    def mkdir(self, path, system=False):
        if path.exists():
            if not path.is_dir():
                raise ValueError('Installation directory is not a directory: ' + str(path))
            return
        self.run(['install', '-d', '-m', '755', '-o', '0' if system else str(self.account.pw_uid),
                  '-g', '0' if system else str(self.account.pw_gid), str(path)], root=True)

    def copy(self, source, target, mode='644'):
        system = target == self.unit
        owner, group = ('0', '0') if system else (str(self.account.pw_uid), str(self.account.pw_gid))
        self.mkdir(target.parent, system=system)
        if target.exists():
            equal = self.run(['cmp', '-s', str(source), str(target)], root=True, check=False)
            if equal.returncode == 0:
                self.run(['chmod', mode, str(target)], root=True)
                self.run(['chown', owner + ':' + group, str(target)], root=True)
                return
            backup = target.with_name(target.name + '.agentshell-backup-' + self.stamp)
            self.run(['cp', '-a', str(target), str(backup)], root=True)
        else:
            backup = None
        self.changed[target] = backup
        temporary = target.with_name('.' + target.name + '.agentshell-' + secrets.token_hex(6))
        try:
            self.run(['install', '-m', mode, '-o', owner, '-g', group,
                      str(source), str(temporary)], root=True)
            self.run(['mv', '-f', str(temporary), str(target)], root=True)
        finally:
            self.run(['rm', '-f', str(temporary)], root=True)

    def write(self, target, text, mode='644'):
        with tempfile.TemporaryDirectory(prefix='agentshell-file-') as directory:
            source = Path(directory) / 'content'
            source.write_text(text)
            self.copy(source, target, mode)

    def settings(self):
        previous = dotenv(self.runtime)
        # Older installations kept credentials directly in the service unit.
        # Retain those on migration even when .env/MCP configuration is absent.
        if self.unit.exists():
            text = self.run(['cat', str(self.unit)], root=True, capture=True).stdout
            for line in text.splitlines():
                if line.startswith('Environment='):
                    for value in shlex.split(line.split('=', 1)[1]):
                        if '=' in value:
                            name, setting = value.split('=', 1)
                            if name in ['AUTH_TOKEN', 'ALLOWED_ORIGIN']:
                                previous.setdefault(name, setting)
        private = self.user_home / '.config/agentshell/interactive-ui.json'
        if not previous.get('AUTH_TOKEN') and private.exists():
            previous['AUTH_TOKEN'] = json.loads(private.read_text()).get('token', '')
        combined = dict(previous, **dotenv(PROJECT / '.env'))
        for name in ['AUTH_TOKEN', 'ALLOWED_ORIGIN']:
            if name in os.environ:
                combined[name] = os.environ[name]
        token = combined.get('AUTH_TOKEN')
        if not token or token.startswith('<') or token in ['replace-with-a-long-random-token', 'your-secret-token']:
            token = secrets.token_urlsafe(48)
            print('Generated a new backend token; it will be saved in the private runtime configuration.', flush=True)
        return {'AUTH_TOKEN': token, 'ALLOWED_ORIGIN': combined.get('ALLOWED_ORIGIN', ''), 'RUST_LOG': 'info',
                'AGENTSHELL_HTTP_PORT': str(self.args.http_port), 'AGENTSHELL_HTTPS_PORT': str(self.args.https_port)}

    def build(self):
        if self.args.binary:
            if not self.args.binary.is_file():
                raise ValueError('Supplied backend binary does not exist')
            return self.args.binary.resolve()
        source = PROJECT / 'backend-rust'
        writable = self.run(['test', '-w', str(source)], user=True, check=False).returncode == 0
        target = source / 'target' if source.stat().st_uid == self.account.pw_uid and writable else self.install_dir / 'cache/backend-target'
        self.mkdir(target)
        print('Building the backend with Cargo.lock.', flush=True)
        self.run(['cargo', 'build', '--release', '--locked', '--manifest-path', str(source / 'Cargo.toml'),
                  '--target-dir', str(target)], user=True)
        return target / 'release/agentshell-backend'

    def install_files(self, binary):
        self.copy(binary, self.install_dir / 'backend/agentshell-backend', '755')
        hashes = {'backend/agentshell-backend': hashlib.sha256(binary.read_bytes()).hexdigest()}
        for entry in self.manifest['files']:
            source = PROJECT / entry['source']
            files = [path for path in source.rglob('*') if path.is_file()] if entry.get('directory') else [source]
            for path in files:
                relative = path.relative_to(source) if entry.get('directory') else Path()
                destination = self.install_dir / entry['destination'] / relative
                self.copy(path, destination, entry['mode'])
                hashes[str(destination.relative_to(self.install_dir))] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write(self.install_dir / 'installation.json', json.dumps({'installedAt': self.stamp, 'files': hashes,
                   'dependencyManifest': self.manifest}, indent=2) + '\n')

    def certificates(self):
        directory = self.install_dir / 'certs'
        self.mkdir(directory)
        if self.args.no_tls:
            return
        cert, key = directory / 'cert.pem', directory / 'key.pem'
        if cert.exists() and key.exists():
            return
        source_cert, source_key = PROJECT / 'certs/cert.pem', PROJECT / 'certs/key.pem'
        if not cert.exists() and not key.exists() and source_cert.exists() and source_key.exists():
            self.copy(source_cert, cert)
            self.copy(source_key, key, '600')
        elif cert.exists() or key.exists():
            raise ValueError('Incomplete TLS certificate pair; supply matching files or use --no-tls')
        else:
            print('Creating this machine\'s local TLS certificate.', flush=True)
            self.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-sha256', '-nodes', '-days', '365',
                      '-subj', '/CN=AgentShell', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
                      '-keyout', str(key), '-out', str(cert)], user=True, capture=True)
            self.run(['chmod', '600', str(key)], user=True)

    def configure(self, runtime):
        self.write(self.runtime, ''.join(name + '=' + json.dumps(value) + '\n' for name, value in runtime.items()), '600')
        text = Template((PROJECT / 'deployment/templates/agentshell.service').read_text()).substitute(
            service_user=self.args.service_user, working_directory=str(self.install_dir / 'backend'),
            executable=json.dumps(str(self.install_dir / 'backend/agentshell-backend')),
            environment_file=str(self.runtime), service_path=self.service_path)
        self.write(self.unit, text)
        self.configure_shell_path()
        url = 'http://127.0.0.1:' + str(self.args.http_port)
        print('Configuring conversation hooks and Smart UI MCP.', flush=True)
        self.run(['/usr/bin/python3', str(PROJECT / 'scripts/install-chat-binding-hooks.py'), '--user-home',
                  str(self.user_home), '--script', str(self.install_dir / 'hooks/chat-binding-hook.py'), '--url', url], user=True, env=runtime)
        self.run(['/usr/bin/python3', str(PROJECT / 'scripts/install-ui-mcp.py'), '--user-home', str(self.user_home),
                  '--script', str(self.install_dir / 'ui/agent-ui-mcp.py'), '--url', url, '--tools', ','.join(self.agents)], user=True, env=runtime)
        # A stdio handshake checks the installed files without publishing into a user chat.
        requests = [{'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2024-11-05'}},
                    {'id': 2, 'method': 'tools/list'}, {'id': 3, 'method': 'tools/call',
                     'params': {'name': 'get_ui_design_system', 'arguments': {}}}]
        command = ['/usr/bin/python3', str(self.install_dir / 'ui/agent-ui-mcp.py')]
        environment = dict(self.user_env)
        options = dict(user=self.account.pw_uid, group=self.account.pw_gid) if os.geteuid() == 0 and self.account.pw_uid else {}
        checked = subprocess.run(command, input=''.join(json.dumps(value) + '\n' for value in requests),
                                 capture_output=True, text=True, env=environment, check=True, **options)
        results = [json.loads(line) for line in checked.stdout.splitlines()]
        if len(results) != 3 or any('error' in value for value in results):
            raise ValueError('Installed MCP stdio/design-system verification failed')
        names = {tool['name'] for tool in results[1]['result']['tools']}
        if names != {'get_ui_design_system', 'render_interactive_ui'}:
            raise ValueError('Installed MCP tool inventory is incomplete')
        print('Installed MCP handshake, both tools and design-system files verified.', flush=True)

    def configure_shell_path(self):
        path = self.user_home / '.config/agentshell/path.sh'
        text = Template((PROJECT / 'deployment/templates/agentshell-path.sh').read_text()).substitute(
            managed_prefix=shlex.quote(str(self.tools / 'bin') + ':' + str(self.node / 'bin')))
        # Run file changes as the service account, retaining its existing profile
        # text and permissions. Live shells/agents are not interrupted.
        helper = '''import os,sys,tempfile,shutil
from pathlib import Path
from datetime import datetime,timezone
path=Path(sys.argv[1]);text=sys.argv[2];append=sys.argv[3]=='append'
if path.is_symlink():raise ValueError('Shell configuration cannot be a symlink')
old=path.read_text() if path.exists() else ''
if append:
 if '# AgentShell managed tools' in old:sys.exit(0)
 text=old+'\\n\\n'+text
if text==old:sys.exit(0)
path.parent.mkdir(parents=True,exist_ok=True)
mode=path.stat().st_mode&0o777 if path.exists() else 0o644
if path.exists():shutil.copy2(path,path.with_name(path.name+'.agentshell-backup-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')))
fd,temp=tempfile.mkstemp(prefix='.agentshell-',dir=path.parent)
try:
 with os.fdopen(fd,'w') as stream:stream.write(text)
 os.chmod(temp,mode);os.replace(temp,path)
finally:Path(temp).unlink(missing_ok=True)
'''
        self.run(['/usr/bin/python3', '-c', helper, str(path), text, 'replace'], user=True)
        block = '# AgentShell managed tools\nif [ -f "$HOME/.config/agentshell/path.sh" ]; then\n    . "$HOME/.config/agentshell/path.sh"\nfi\n'
        for name in ['.profile', '.bashrc']:
            self.run(['/usr/bin/python3', '-c', helper, str(self.user_home / name), block, 'append'], user=True)

    def start(self):
        if self.args.no_start:
            print('Service files prepared; start skipped with --no-start.', flush=True)
            return
        if not Path('/run/systemd/system').exists():
            raise ValueError('A running systemd host is required; use --no-start to prepare an image')
        verified = self.run(['systemd-analyze', 'verify', str(self.unit)], root=True, capture=True, check=False)
        if verified.returncode or 'ignoring:' in verified.stderr or 'not absolute' in verified.stderr:
            raise ValueError('Service unit validation failed before restart; run systemd-analyze verify on ' + str(self.unit))
        self.run(['systemctl', 'daemon-reload'], root=True)
        self.run(['systemctl', 'enable', 'agentshell'], root=True)
        try:
            self.run(['systemctl', 'restart', 'agentshell'], root=True)
            deadline = time.monotonic() + 15
            while True:
                active = self.run(['systemctl', 'is-active', '--quiet', 'agentshell'], root=True, check=False)
                try:
                    request = urllib.request.Request('http://127.0.0.1:' + str(self.args.http_port) + '/api/clients',
                                                     headers={'X-Auth-Token': dotenv(self.runtime)['AUTH_TOKEN']})
                    with urllib.request.urlopen(request, timeout=2) as response:
                        value = json.load(response)
                        if active.returncode == 0 and response.status == 200 and isinstance(value.get('clients'), int):
                            break
                except OSError:
                    pass
                if time.monotonic() >= deadline:
                    raise ValueError('Backend did not pass its startup health check')
                time.sleep(.2)
        except Exception:
            for target in [self.install_dir / 'backend/agentshell-backend', self.unit, self.runtime]:
                backup = self.changed.get(target)
                if backup:
                    self.run(['cp', '-a', str(backup), str(target)], root=True)
            self.run(['systemctl', 'daemon-reload'], root=True)
            self.run(['systemctl', 'restart', 'agentshell'], root=True, check=False)
            raise
        if 'codex' in self.agents:
            refreshed = self.run([str(self.venv / 'bin/python'), str(self.install_dir / 'hooks/reload-codex-mcp.py')], user=True, check=False)
            if refreshed.returncode:
                print('Installation is ready; an existing Codex client still needs a native MCP reload.', flush=True)
        print('Backend active and healthy on http://localhost:' + str(self.args.http_port), flush=True)

    def execute(self):
        if sys.version_info < (3, 9):
            raise ValueError('Python 3.9+ is required; use Ubuntu 22.04+/Debian 12+ or their derivatives')
        self.validate_sources()
        if not shutil.which('apt-get') or not shutil.which('dpkg-query'):
            raise ValueError('This installer supports Debian/Ubuntu and derivatives with apt-get')
        if self.args.check:
            python = self.venv / 'bin/python'
            python_versions = None
            if python.exists():
                code = 'import importlib.metadata,json;print(json.dumps({name:importlib.metadata.version(name) for name in ' + repr(list(self.manifest['pythonPackages'])) + '}))'
                checked = self.run([str(python), '-c', code], user=True, capture=True, check=False)
                if checked.returncode == 0:
                    python_versions = json.loads(checked.stdout)
            print(json.dumps({'missingSystemPackages': self.missing_packages(),
                  'rustAvailable': self.usable('rustc'), 'nodeAvailable': self.usable('node'),
                  'agents': {name: self.usable(name) for name in self.agents},
                  'managedPythonPackages': python_versions,
                  'repositoryFilesPresent': True}, indent=2))
            return
        self.dependencies()
        if self.args.deps_only:
            print('Backend and selected native-agent dependencies installed.', flush=True)
            return
        runtime = self.settings()
        binary = self.build()
        self.install_files(binary)
        self.certificates()
        self.configure(runtime)
        self.start()
        print('Installation complete. Live credentials and chat databases stay outside the repository.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service-user', default=os.environ.get('SERVICE_USER', os.environ.get('SUDO_USER', pwd.getpwuid(os.getuid()).pw_name)))
    parser.add_argument('--install-dir', type=Path, default='/opt/agentshell')
    parser.add_argument('--unit-dir', type=Path, default='/etc/systemd/system')
    parser.add_argument('--http-port', type=int, default=int(os.environ.get('AGENTSHELL_HTTP_PORT', '4010')))
    parser.add_argument('--https-port', type=int, default=int(os.environ.get('AGENTSHELL_HTTPS_PORT', '4443')))
    parser.add_argument('--agents', default='codex,claude,opencode')
    parser.add_argument('--check', action='store_true', help='Read-only dependency/source check')
    parser.add_argument('--deps-only', action='store_true', help='Bootstrap dependencies without deployment')
    parser.add_argument('--skip-dependencies', action='store_true', help='Use an already provisioned host')
    parser.add_argument('--no-start', action='store_true', help='Prepare files/configuration without starting systemd')
    parser.add_argument('--no-tls', action='store_true', help='Skip creation of a missing TLS pair')
    parser.add_argument('--binary', type=Path, help='Install an already built backend binary')
    args = parser.parse_args()
    if not (1 <= args.http_port <= 65535 and 1 <= args.https_port <= 65535):
        parser.error('Ports must be between 1 and 65535')
    try:
        Installer(args).execute()
    except subprocess.CalledProcessError as error:
        parser.exit(1, 'Installation step failed (exit ' + str(error.returncode) + '). No credential values were printed by the installer.\n')
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, 'Installation failed: ' + str(error) + '\n')


if __name__ == '__main__':
    main()

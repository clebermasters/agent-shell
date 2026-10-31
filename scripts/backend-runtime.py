#!/usr/bin/env python3
"""Portable foreground runner and per-user macOS launchd controls."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

LABEL = 'com.agentshell.backend'


def wait_for_unloaded(target, run=None, timeout=15):
    """bootout may return while the old job is still exiting; wait before reuse."""
    execute = run or subprocess.run
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0: raise ValueError(f'launchd service did not unload within {timeout:g} seconds: {target}')
        try:
            result = execute(['launchctl', 'print', target], capture_output=True, check=False,
                             timeout=min(2, remaining))
            if result.returncode != 0: return
        except subprocess.TimeoutExpired: pass
        time.sleep(min(.1, max(0, deadline - time.monotonic())))


def dotenv(path):
    """Read literal values; never execute shell expressions in private settings."""
    values = {}
    if not path.is_file(): return values
    for number, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith('#'): continue
        line = line.removeprefix('export ')
        if '=' not in line: raise ValueError(f'Invalid setting on line {number}')
        key, value = line.split('=', 1); key, value = key.strip(), value.strip()
        if not re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', key): raise ValueError(f'Invalid setting name on line {number}')
        if value.startswith('"') and value.endswith('"'): value = json.loads(value)
        elif value.startswith("'") and value.endswith("'"): value = value[1:-1]
        if not isinstance(value, str) or '\0' in value: raise ValueError(f'Invalid setting on line {number}')
        values[key] = value
    return values


def service_paths(prefix, home=None):
    home = home or Path.home()
    metadata = prefix / 'installation.json'
    saved = json.loads(metadata.read_text()) if metadata.is_file() else {}
    return (Path(saved.get('launchAgent', home / 'Library/LaunchAgents' / (LABEL + '.plist'))),
            Path(saved.get('logDirectory', home / 'Library/Logs/AgentShell')),
            saved.get('launchDomain', f'gui/{os.getuid()}'))


def service_action(action, prefix, dry_run=False, tail_args=()):
    plist, logs, domain = service_paths(prefix)
    if not re.fullmatch(r'(gui|user)/' + str(os.getuid()), domain): raise ValueError('LaunchAgent must belong to the current user')
    target = domain + '/' + LABEL
    if action in ('logs', 'logs-full'):
        command = ['tail', '-F' if action == 'logs' else '-n', *([] if action == 'logs' else ['1000']),
                   *tail_args, str(logs / 'stdout.log'), str(logs / 'stderr.log')]
        if dry_run: return [command]
        return subprocess.run(command, check=True).returncode
    if dry_run:
        return {'start': [['launchctl', 'bootstrap', domain, str(plist)], ['launchctl', 'kickstart', target]],
                'restart': [['launchctl', 'kickstart', '-k', target]],
                'stop': [['launchctl', 'bootout', target]],
                'status': [['launchctl', 'print', target]],
                'enable': [['launchctl', 'enable', target]],
                'disable': [['launchctl', 'disable', target], ['launchctl', 'bootout', target]]}[action]
    if sys.platform != 'darwin': raise ValueError('launchd service controls require macOS')
    loaded = subprocess.run(['launchctl', 'print', target], capture_output=True).returncode == 0
    if action == 'status': return subprocess.run(['launchctl', 'print', target]).returncode
    if action in ('stop', 'disable'):
        if action == 'disable': subprocess.run(['launchctl', 'disable', target], check=True)
        if loaded:
            subprocess.run(['launchctl', 'bootout', target], check=True)
            wait_for_unloaded(target)
        return 0
    if not plist.is_file(): raise ValueError('LaunchAgent is not installed; run ./install.sh first')
    if action == 'enable': subprocess.run(['launchctl', 'enable', target], check=True)
    if not loaded: subprocess.run(['launchctl', 'bootstrap', domain, str(plist)], check=True)
    command = ['launchctl', 'kickstart'] + (['-k'] if action == 'restart' and loaded else []) + [target]
    subprocess.run(command, check=True)
    return 0


def run(executable, directory, env_file, arguments):
    executable, directory = executable.absolute(), directory.absolute()
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise ValueError('Backend executable is missing; run scripts/build-backend.sh first or select --executable')
    if not directory.is_dir(): raise ValueError('Backend working directory does not exist')
    environment = dict(dotenv(env_file), **os.environ)
    environment['AGENTSHELL_BACKEND_BIN'] = str(executable)
    os.umask(0o077)
    os.chdir(directory)
    os.execve(str(executable), [str(executable), *arguments], environment)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    runner = commands.add_parser('run', help='Run a backend in the foreground using literal private settings')
    runner.add_argument('--executable', type=Path, required=True)
    runner.add_argument('--working-directory', type=Path, required=True)
    runner.add_argument('--env-file', type=Path, required=True)
    runner.add_argument('arguments', nargs=argparse.REMAINDER)
    service = commands.add_parser('service', help='Control the current user’s macOS LaunchAgent')
    service.add_argument('action', choices=['start', 'stop', 'restart', 'status', 'enable', 'disable', 'logs', 'logs-full'])
    service.add_argument('--install-dir', type=Path, default=os.environ.get('AGENTSHELL_INSTALL_DIR', str(Path.home() / 'Library/Application Support/AgentShell')))
    service.add_argument('--dry-run', action='store_true')
    service.add_argument('tail_args', nargs='*')
    args = parser.parse_args()
    try:
        if args.command == 'run':
            arguments = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
            run(args.executable, args.working_directory, args.env_file, arguments)
        else:
            result = service_action(args.action, args.install_dir.expanduser().absolute(), args.dry_run, args.tail_args)
            if args.dry_run: print(json.dumps(result, indent=2))
            else: return result
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, 'Backend runtime failed: ' + str(error) + '\n')
    return 0


if __name__ == '__main__': sys.exit(main())

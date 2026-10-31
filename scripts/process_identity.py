"""Native process identity shared by AgentShell hooks and MCP publishers.

Linux retains procfs start ticks. macOS delegates to the daemon's canonical
libproc implementation so Python and Rust never invent different generations.
This helper reads metadata only and never starts or contacts a running daemon.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def backend_binary():
    override = os.environ.get('AGENTSHELL_BACKEND_BIN')
    if override:
        candidate = Path(override).expanduser()
        if not candidate.is_absolute() or not candidate.is_file() or not os.access(candidate, os.X_OK):
            raise ValueError('AGENTSHELL_BACKEND_BIN must name an executable absolute path')
        return str(candidate)
    root = Path(__file__).resolve().parent.parent
    for candidate in (root/'bin/agentshell-backend', root/'backend/agentshell-backend'):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    found = shutil.which('agentshell-backend')
    if found:
        return found
    raise ValueError('AgentShell process identity helper is not installed')


def _native_info(pid, include_open_files):
    command = [backend_binary(), 'process-info', '--pid', str(pid)]
    if include_open_files:
        command.append('--open-files')
    try:
        completed = subprocess.run(command, capture_output=True, timeout=2, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError('Unable to inspect the owning native process') from None
    if completed.returncode or len(completed.stdout) > 1024*1024:
        raise ValueError('Unable to inspect the owning native process')
    try:
        value = json.loads(completed.stdout)
    except (ValueError, UnicodeError):
        raise ValueError('Invalid native process identity response') from None
    if (not isinstance(value, dict) or type(value.get('pid')) is not int or value['pid'] != pid
            or type(value.get('parentPid')) is not int or not 0 <= value['parentPid'] <= 2**31-1
            or type(value.get('generation')) is not int or not 0 < value['generation'] <= 2**64-1
            or not isinstance(value.get('name'), str) or not value['name'] or len(value['name']) > 256):
        raise ValueError('Invalid native process identity response')
    if include_open_files:
        paths = value.get('openFiles')
        if not isinstance(paths, list) or not all(isinstance(path, str) and path.startswith('/') for path in paths):
            raise ValueError('Invalid native open-file response')
    tool = value.get('agentTool')
    if tool is not None and tool not in ('codex', 'claude', 'opencode', 'kiro'):
        raise ValueError('Invalid native agent identity response')
    # The daemon verifies macOS package aliases against the physical executable,
    # rather than trusting a version-shaped process name or an arbitrary argv[0].
    if tool in ('codex', 'claude', 'opencode'):
        value['name'] = tool
    return value


def process_info(pid, include_open_files=False):
    if type(pid) is not int or not 0 < pid <= 2**31-1:
        raise ValueError('Invalid process identifier')
    if sys.platform == 'linux':
        process = Path('/proc')/str(pid)
        raw = (process/'stat').read_text()
        fields = raw[raw.rfind(')')+2:].split()
        if len(fields) < 20 or fields[0] == 'Z':
            raise ValueError('The native process is no longer running')
        value = {'pid': pid, 'parentPid': int(fields[1]), 'generation': int(fields[19]),
                 'name': (process/'comm').read_text().strip()}
        if include_open_files:
            paths = []
            for fd in (process/'fd').iterdir():
                try:
                    target = os.readlink(fd)
                except OSError:
                    continue
                if target.startswith('/'):
                    paths.append(target)
            value['openFiles'] = sorted(set(paths))
        return value
    if sys.platform == 'darwin':
        return _native_info(pid, include_open_files)
    raise ValueError('Native process identity is unsupported on this platform')


def process_name(pid):
    return process_info(pid)['name']


def python_command(override=None):
    """Keep registered Linux paths stable; use the selected interpreter on Mac."""
    return override or ('/usr/bin/python3' if sys.platform == 'linux' else sys.executable)

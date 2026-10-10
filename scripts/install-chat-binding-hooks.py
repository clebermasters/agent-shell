#!/usr/bin/env python3
"""Merge AgentShell's identity hook into existing user hooks, preserving all other settings."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shlex
import shutil
import tempfile


def merge_hook(value, command):
    result = dict(value)
    hooks = dict(result.get("hooks", {}))
    starts = list(hooks.get("SessionStart", []))
    starts = [dict(group, hooks=[entry for entry in group.get("hooks", []) if "chat-binding-hook.py" not in entry.get("command", "")]) for group in starts]
    starts = [group for group in starts if group.get("hooks")]
    starts.append({"matcher": "startup|resume|clear|compact|fork", "hooks": [{"type": "command", "command": command, "timeout": 3}]})
    hooks["SessionStart"] = starts
    result["hooks"] = hooks
    return result


def atomic_write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("Configuration file cannot be a symlink")
    if path.exists():
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        shutil.copy2(path, path.with_name(path.name + ".agentshell-backup-" + stamp))
    fd, temporary = tempfile.mkstemp(prefix=".agentshell-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as output:
            json.dump(value, output, indent=2)
            output.write("\n")
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--script", type=Path, required=True)
    parser.add_argument("--url", default="http://127.0.0.1:4010")
    parser.add_argument("--user-home", type=Path, default=Path.home())
    args = parser.parse_args()
    token = os.environ.get("AUTH_TOKEN")
    if not token:
        raise ValueError("AUTH_TOKEN is required to configure the local binding hook")
    for tool, relative in [("codex", ".codex/hooks.json"), ("claude", ".claude/settings.json")]:
        path = args.user_home / relative
        previous = json.loads(path.read_text()) if path.exists() else {}
        command = "/usr/bin/python3 " + shlex.quote(str(args.script.resolve())) + " --tool " + tool
        atomic_write(path, merge_hook(previous, command))
    atomic_write(args.user_home / ".config/agentshell/chat-binding-hook.json", {"url": args.url, "token": token})
    print("Installed conversation identity hooks; existing hooks preserved and backed up.")
    print("Codex requires one-time review of the new hook in /hooks. No hook-trust bypass was configured.")


if __name__ == "__main__":
    main()

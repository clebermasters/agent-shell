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



def configure_codex_footer(path):
    """Expose the native UUID without replacing the user's other footer items."""
    import ast
    import re
    text = path.read_text() if path.exists() else ""
    section = re.search(r"(?m)^\[tui\]\s*(?:#.*)?$", text)
    default = ["model-with-reasoning", "current-dir", "context-remaining"]
    if section:
        begin = section.end()
        next_section = re.search(r"(?m)^\[", text[begin:])
        end = begin + next_section.start() if next_section else len(text)
        body = text[begin:end]
        value = re.search(r"(?m)^status_line\s*=\s*(\[[\s\S]*?\])", body)
        items = ast.literal_eval("\n".join(line.split("#",1)[0] for line in value[1].splitlines())) if value else default
        if not isinstance(items,list) or not all(isinstance(item,str) for item in items):
            raise ValueError("Invalid Codex footer configuration")
        identity_item = "thread-id" if "thread-id" in items else "session-id"
        items = [identity_item, *[item for item in items if item not in ("session-id", "thread-id")]]
        replacement = "status_line = " + json.dumps(items)
        if value: body = body[:value.start()] + replacement + body[value.end():]
        else: body = "\n" + replacement + "\n" + body
        updated = text[:begin] + body + text[end:]
    else:
        updated = text.rstrip() + "\n\n[tui]\nstatus_line = " + json.dumps(["session-id", *default]) + "\n"
    if updated == text:return
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.is_symlink():raise ValueError("Codex configuration cannot be a symlink")
    if path.exists():
        stamp=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        shutil.copy2(path,path.with_name(path.name+".agentshell-backup-"+stamp))
    fd,temp=tempfile.mkstemp(prefix=".agentshell-",dir=path.parent)
    try:
        with os.fdopen(fd,"w") as stream:stream.write(updated)
        os.chmod(temp,path.stat().st_mode & 0o777 if path.exists() else 0o600)
        os.replace(temp,path)
    finally:Path(temp).unlink(missing_ok=True)


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
    configure_codex_footer(args.user_home / ".codex/config.toml")
    atomic_write(args.user_home / ".config/agentshell/chat-binding-hook.json", {"url": args.url, "token": token})
    print("Codex Session ID is first in the terminal footer for shared-daemon identity.")
    print("Installed conversation identity hooks; existing hooks preserved and backed up.")
    print("Codex requires one-time review of the new hook in /hooks. No hook-trust bypass was configured.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Install the local renderer's MCP entry, preserving unrelated agent settings."""
import argparse
import hashlib
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from jsonc_config import merge_mcp

def backup(path):
    if path.exists():
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        shutil.copy2(path,path.with_name(path.name+'.agentshell-ui-backup-'+stamp))

def write_json(path,value,private=False):
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.is_symlink():raise ValueError('Configuration cannot be a symlink')
    backup(path)
    fd,temp=tempfile.mkstemp(prefix='.agentshell-ui-',dir=path.parent)
    try:
        with os.fdopen(fd,'w') as stream:json.dump(value,stream,indent=2);stream.write('\n')
        os.chmod(temp,0o600 if private else (path.stat().st_mode & 0o777 if path.exists() else 0o600))
        os.replace(temp,path)
    finally:Path(temp).unlink(missing_ok=True)

def write_text(path,value):
    if path.is_symlink():raise ValueError('Configuration cannot be a symlink')
    if path.exists() and path.read_text()==value:return
    path.parent.mkdir(parents=True,exist_ok=True);backup(path)
    fd,temp=tempfile.mkstemp(prefix='.agentshell-ui-',dir=path.parent)
    try:
        with os.fdopen(fd,'w') as stream:stream.write(value)
        os.chmod(temp,path.stat().st_mode & 0o777 if path.exists() else 0o600)
        os.replace(temp,path)
    finally:Path(temp).unlink(missing_ok=True)

def merge_entry(value,key,entry):
    result=dict(value);servers=dict(result.get(key,{}));servers['agentshell-ui']=entry;result[key]=servers;return result

def server_environment(script,config):
    # Codex compares MCP configuration on reload, not the running Python file.
    # A changed revision makes an installed adapter update visible to that check.
    return {'AGENTSHELL_UI_CONFIG':str(config),'AGENTSHELL_UI_REVISION':hashlib.sha256(Path(script).read_bytes()).hexdigest()}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--script',type=Path,required=True);parser.add_argument('--url',default='http://127.0.0.1:4010');parser.add_argument('--user-home',type=Path,default=Path.home());parser.add_argument('--tools',default='codex,claude,opencode');args=parser.parse_args()
    token=os.environ.get('AUTH_TOKEN')
    if not token:raise ValueError('AUTH_TOKEN is required')
    script=str(args.script.resolve());config=args.user_home/'.config/agentshell/interactive-ui.json'
    write_json(config,{'url':args.url,'token':token},private=True)
    command=['/usr/bin/python3',script];installed=[];server_env=server_environment(script,config)
    for tool in args.tools.split(','):
        if not shutil.which(tool):continue
        if tool=='codex':
            path=args.user_home/'.codex/config.toml'
            if path.is_symlink():raise ValueError('Codex configuration cannot be a symlink')
            backup(path)
            environment=dict(os.environ,CODEX_HOME=str(path.parent))
            env_args=[arg for key,value in server_env.items() for arg in ['--env',key+'='+value]]
            subprocess.run(['codex','mcp','add','agentshell-ui',*env_args,'--',*command],env=environment,check=True,capture_output=True,text=True)
        elif tool=='claude':
            path=args.user_home/'.claude.json';old=json.loads(path.read_text()) if path.exists() else {}
            write_json(path,merge_entry(old,'mcpServers',{'type':'stdio','command':command[0],'args':command[1:],'env':server_env}))
        elif tool=='opencode':
            path=args.user_home/'.config/opencode/opencode.json'
            entry={'type':'local','command':command,'enabled':True,'environment':server_env}
            if path.with_suffix('.jsonc').exists():
                path=path.with_suffix('.jsonc')
                write_text(path,merge_mcp(path.read_text(),entry))
            else:
                old=json.loads(path.read_text()) if path.exists() else {}
                write_json(path,merge_entry(old,'mcp',entry))
        else:raise ValueError('Unknown agent: '+tool)
        installed.append(tool)
    print('Registered AgentShell UI MCP for: '+', '.join(installed))
    print('New agent processes load the server. Existing running agents remain undisturbed.')

if __name__=='__main__':main()

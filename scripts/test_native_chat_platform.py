#!/usr/bin/env python3
"""Verify native process identity and bound chat on Linux and macOS.

Compiles two tiny owned terminal processes named codex/claude, with real open
transcripts and a synthetic input loop. No model, production socket, profile,
service, phone or user terminal is modified.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid

import websockets
from process_identity import process_info
from test_chat_bindings import watch, send

C_WRAPPER = r'''#include <stdio.h>
#include <unistd.h>
#include <sys/wait.h>
int main(int argc, char **argv) {
    if (argc != 8) return 2;
    FILE *transcript = fopen(argv[1], "r");
    if (!transcript) return 3;
    pid_t child = fork();
    if (child < 0) return 4;
    if (child == 0) {
        char *args[] = {argv[2], argv[3], argv[1], argv[4], argv[5], argv[6], argv[7], NULL};
        execv(argv[2], args); _exit(5);
    }
    int status = 0;
    if (waitpid(child, &status, 0) < 0) return 6;
    fclose(transcript);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 7;
}
'''

WORKER = r'''import json,os,sys,tty,uuid
from pathlib import Path
from datetime import datetime,timezone
transcript,ready,tool,identifier,cwd=sys.argv[1:]
file=open(transcript,'a',buffering=1)
def append(role,text):
    stamp=datetime.now(timezone.utc).isoformat()
    if tool=='codex':
        value={'timestamp':stamp,'type':'event_msg','payload':{'type':'user_message' if role=='user' else 'agent_message','message':text}}
    else:
        value={'timestamp':stamp,'type':role,'uuid':str(uuid.uuid4()),'sessionId':identifier,
            'message':{'role':role,'content':[{'type':'text','text':text}]}}
    file.write(json.dumps(value)+'\n');file.flush()
tty.setraw(0);sys.stdout.write('\x1b[?2004h');sys.stdout.flush()
Path(ready).write_text('ready')
draft=b'';escape=b'';paste=False
while True:
    char=os.read(0,1)
    if not char:break
    if escape or char==b'\x1b':
        escape+=char
        if escape==b'\x1b[200~':paste=True;escape=b'';continue
        if escape==b'\x1b[201~':paste=False;escape=b'';continue
        if len(escape)<6:continue
        draft+=escape;escape=b'';continue
    if char==b'\r' and not paste:
        text=draft.decode();draft=b''
        append('user',text);append('assistant',tool+' reply: '+text)
    else:draft+=char
'''


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0));return listener.getsockname()[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();binary=args.binary.resolve()
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True);output.chmod(0o700)
    assert sys.platform in ('linux','darwin') and binary.is_file()
    checks={};backend=None
    with tempfile.TemporaryDirectory(prefix='as-native-',dir='/tmp') as temporary:
        root=Path(temporary).resolve();home=root/'native home Ω';home.mkdir()
        project=home/'project with spaces';project.mkdir();instance=root/'backend';instance.mkdir()
        socket_name='fixture';tmux_env=dict(os.environ,TMUX_TMPDIR=str(root))
        token=secrets.token_urlsafe(32)
        wrapper=root/'wrapper.c';wrapper.write_text(C_WRAPPER)
        worker=root/'worker.py';worker.write_text(WORKER)
        log=(output/'backend.log').open('wb')
        def tmux(*arguments):
            return subprocess.check_output(['tmux','-L',socket_name,*arguments],env=tmux_env,text=True).strip()
        try:
            identities={}
            for tool in ('codex','claude'):
                executable=root/tool
                subprocess.run(['cc','-O2',str(wrapper),'-o',str(executable)],check=True,capture_output=True)
                identifier=str(uuid.uuid4())
                folder=home/('.codex/sessions' if tool=='codex' else '.claude/projects/native-fixture')
                folder.mkdir(parents=True,exist_ok=True)
                transcript=folder/(('rollout-' if tool=='codex' else '')+identifier+'.jsonl')
                if tool=='codex':
                    initial={'type':'session_meta','payload':{'id':identifier,'cwd':str(project),'source':'cli'}}
                else:
                    initial={'type':'assistant','timestamp':datetime.now(timezone.utc).isoformat(),
                        'uuid':str(uuid.uuid4()),'sessionId':identifier,
                        'message':{'role':'assistant','content':[{'type':'text','text':'Native Claude fixture ready'}]}}
                transcript.write_text(json.dumps(initial)+'\n')
                ready=root/(tool+'.ready')
                command='exec '+' '.join(shlex.quote(str(value)) for value in
                    [executable,transcript,sys.executable,worker,ready,tool,identifier,project])
                tmux('new-session','-d','-s',tool,'-c',str(project),command)
                deadline=time.monotonic()+10
                while not ready.exists():
                    assert time.monotonic()<deadline,'Owned native fixture did not start';time.sleep(.02)
                pid=int(tmux('display-message','-p','-t',tool,'#{pane_pid}'))
                native=json.loads(subprocess.check_output([str(binary),'process-info','--pid',str(pid),'--open-files']))
                assert native['name']==tool and native['hasTty']
                assert native['processGroup']==native['foregroundGroup']
                assert transcript in [Path(p) for p in native['openFiles']],native['openFiles']
                previous=os.environ.get('AGENTSHELL_BACKEND_BIN');os.environ['AGENTSHELL_BACKEND_BIN']=str(binary)
                try: python_identity=process_info(pid,include_open_files=True)
                finally:
                    if previous is None:os.environ.pop('AGENTSHELL_BACKEND_BIN',None)
                    else:os.environ['AGENTSHELL_BACKEND_BIN']=previous
                assert python_identity['generation']==native['generation'] and python_identity['name']==tool
                identities[tool]=(identifier,pid,transcript)
                checks[tool+'_native_foreground_identity_and_python_agreement']=True
            context=tmux('display-message','-p','-t','codex','#{socket_path},#{pid},0')
            port=free_port();tls=free_port()
            while tls==port:tls=free_port()
            environment=dict(tmux_env,AUTH_TOKEN=token,HOME=str(home),XDG_CONFIG_HOME=str(root/'config'),
                TMUX=context,AGENTSHELL_HTTP_PORT=str(port),AGENTSHELL_HTTPS_PORT=str(tls),
                AGENTSHELL_BROWSER_CONFIG=str(root/'absent-browser.json'),RUST_LOG='warn',
                AGENTSHELL_TLS_CERT_PATH=str(root/'absent.pem'),AGENTSHELL_TLS_KEY_PATH=str(root/'absent.key'))
            backend=subprocess.Popen([str(binary)],cwd=instance,env=environment,stdout=log,stderr=log)
            deadline=time.monotonic()+15
            while True:
                assert backend.poll() is None,'Owned backend exited'
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=.2):break
                except OSError:
                    assert time.monotonic()<deadline,'Owned backend failed to start';time.sleep(.05)
            async def verify():
                bindings={}
                async with websockets.connect(f'ws://127.0.0.1:{port}/ws',additional_headers={'X-Auth-Token':token},max_size=16*1024*1024) as connection:
                    for tool in ('codex','claude'):
                        binding,_=await watch(connection,tool)
                        assert binding['conversationId']==identities[tool][0]
                        bindings[tool]=binding
                        text='native-platform-'+tool+'-'+uuid.uuid4().hex[:8]
                        await send(connection,tool,binding,text)
                        lines=[json.loads(line) for line in identities[tool][2].read_text().splitlines()]
                        if tool=='codex':
                            count=sum(event.get('payload',{}).get('type')=='user_message' and event['payload'].get('message')==text for event in lines)
                        else:
                            count=sum(event.get('type')=='user' and any(block.get('text')==text for block in event.get('message',{}).get('content',[])) for event in lines)
                        assert count==1,'Input must reach exactly one native conversation once'
                        checks[tool+'_bound_history_and_terminal_send']=True
                    assert bindings['codex']['bindingId']!=bindings['claude']['bindingId']
                    checks['shared_working_directory_does_not_mix_conversations']=True
            asyncio.run(verify())
        finally:
            if backend is not None:
                backend.terminate()
                try:backend.wait(timeout=8)
                except subprocess.TimeoutExpired:backend.kill();backend.wait(timeout=5)
            subprocess.run(['tmux','-L',socket_name,'kill-server'],env=tmux_env,capture_output=True)
            log.close()
    checks['owned_fixture_cleaned_without_production_mutations']=True
    result={'platform':sys.platform,'checks':checks,'passed':len(checks)}
    (output/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))


if __name__=='__main__':main()

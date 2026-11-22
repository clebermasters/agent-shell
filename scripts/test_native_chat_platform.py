#!/usr/bin/env python3
"""Verify native process identity and bound chat on Linux and macOS.

Compiles owned terminal processes named codex, claude and agy, with native
conversation file descriptors and a synthetic input loop. No model, production socket, profile,
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
from test_chat_bindings import watch, send, receive, texts

C_WRAPPER = r'''#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/wait.h>
int main(int argc, char **argv) {
    if (argc != 8 && argc != 10) return 2;
    int proof = open(argc == 10 ? argv[8] : argv[1], O_RDONLY | O_CLOEXEC);
    int control[2];
    if (proof < 0 || pipe(control) != 0) return 3;
    pid_t child = fork();
    if (child < 0) return 4;
    if (child == 0) {
        close(control[0]);
        char channel[32];
        snprintf(channel, sizeof(channel), "%d", control[1]);
        char *args[] = {argv[2], argv[3], argv[1], argv[4], argv[5], argv[6], argv[7], channel, NULL};
        execv(argv[2], args); _exit(5);
    }
    close(control[1]);
    char command;
    while (read(control[0], &command, 1) == 1) {
        if (argc != 10 || command != 'R') return 8;
        close(proof);
        proof = open(argv[9], O_RDONLY | O_CLOEXEC);
        if (proof < 0) return 9;
    }
    close(control[0]);
    int status = 0;
    if (waitpid(child, &status, 0) < 0) return 6;
    close(proof);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 7;
}
'''

WORKER = r'''import json,os,sys,tty,uuid
from pathlib import Path
from datetime import datetime,timezone
transcript,ready,tool,identifier,cwd,channel=sys.argv[1:]
transcript=Path(transcript)
step=1
def append(role,text):
    global step
    stamp=datetime.now(timezone.utc).isoformat()
    if tool=='codex':
        value={'timestamp':stamp,'type':'event_msg','payload':{'type':'user_message' if role=='user' else 'agent_message','message':text}}
    elif tool=='agy':
        value={'step_index':step,'created_at':stamp,'status':'DONE',
            'type':'USER_INPUT' if role=='user' else 'PLANNER_RESPONSE',
            'source':'USER_EXPLICIT' if role=='user' else 'MODEL',
            'content':'<USER_REQUEST>\n'+text+'\n</USER_REQUEST>\n<ADDITIONAL_METADATA>test workspace</ADDITIONAL_METADATA>' if role=='user' else text}
        step+=1
    else:
        value={'timestamp':stamp,'type':role,'uuid':str(uuid.uuid4()),'sessionId':identifier,
            'message':{'role':role,'content':[{'type':'text','text':text}]}}
    with transcript.open('a') as file:
        file.write(json.dumps(value)+'\n')
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
        if tool=='agy' and text.startswith('/resume '):
            identifier=str(uuid.UUID(text.split()[1]))
            transcript=transcript.parents[3]/identifier/'.system_generated/logs/transcript_full.jsonl'
            assert transcript.exists()
            step=1
            os.write(int(channel),b'R')
            continue
        append('user',text);append('assistant',tool+' reply: '+text)
    else:draft+=char
'''


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0));return listener.getsockname()[1]


def agy_fixture(home, identifier):
    cli=home/'.gemini/antigravity-cli'
    proof=cli/'presence'/(identifier+'.lock')
    proof.parent.mkdir(parents=True,exist_ok=True)
    proof.touch()
    transcript=cli/'brain'/identifier/'.system_generated/logs/transcript_full.jsonl'
    transcript.parent.mkdir(parents=True,exist_ok=True)
    initial={'step_index':0,'type':'PLANNER_RESPONSE','source':'MODEL','status':'DONE',
        'created_at':datetime.now(timezone.utc).isoformat(),'content':'Fixture '+identifier}
    transcript.write_text(json.dumps(initial)+'\n')
    return proof,transcript


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();binary=args.binary.resolve()
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True);output.chmod(0o700)
    assert sys.platform in ('linux','darwin') and binary.is_file()
    checks={};backend=None;trace=[]
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
            cases=[('codex','codex',False),('claude','claude',False),('agy','agy',False)]
            if sys.platform=='darwin':cases.append(('claude-versioned','claude',True))
            for label,tool,versioned in cases:
                executable=home/'.local/share/claude/versions/2.1.284' if versioned else root/tool
                executable.parent.mkdir(parents=True,exist_ok=True)
                subprocess.run(['cc','-O2',str(wrapper),'-o',str(executable)],check=True,capture_output=True)
                identifier=str(uuid.uuid4())
                presence_args=[]
                if tool=='agy':
                    proof,transcript=agy_fixture(home,identifier)
                    resumed_id=str(uuid.uuid4())
                    resumed_proof,resumed_log=agy_fixture(home,resumed_id)
                    presence_args=[proof,resumed_proof]
                else:
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
                    proof=transcript
                ready=root/(label+'.ready')
                command='exec '+' '.join(shlex.quote(str(value)) for value in
                    [executable,transcript,sys.executable,worker,ready,tool,identifier,project,*presence_args])
                tmux('new-session','-d','-s',label,'-c',str(project),command)
                deadline=time.monotonic()+10
                while not ready.exists():
                    assert time.monotonic()<deadline,'Owned native fixture did not start';time.sleep(.02)
                pid=int(tmux('display-message','-p','-t',label,'#{pane_pid}'))
                native=json.loads(subprocess.check_output([str(binary),'process-info','--pid',str(pid),'--open-files'],env=dict(os.environ,HOME=str(home))))
                assert native.get('agentTool',native['name'])==tool and native['hasTty']
                assert native['processGroup']==native['foregroundGroup']
                open_files=[Path(p) for p in native['openFiles']]
                assert proof in open_files,open_files
                if tool=='agy':
                    assert transcript not in open_files and resumed_proof not in open_files
                from unittest.mock import patch
                with patch.dict(os.environ,AGENTSHELL_BACKEND_BIN=str(binary),HOME=str(home)):
                    python_identity=process_info(pid,include_open_files=True)
                assert python_identity['generation']==native['generation'] and python_identity['name']==tool
                identities[label]=(identifier,pid,transcript)
                checks[label+'_native_foreground_identity_and_python_agreement']=True
                print(label+' native identity verified',flush=True)
            context=tmux('display-message','-p','-t','codex','#{socket_path},#{pid},0')
            port=free_port();tls=free_port()
            while tls==port:tls=free_port()
            environment=dict(tmux_env,AUTH_TOKEN=token,HOME=str(home),XDG_CONFIG_HOME=str(root/'config'),
                TMUX=context,AGENTSHELL_HTTP_PORT=str(port),AGENTSHELL_HTTPS_PORT=str(tls),
                AGENTSHELL_BROWSER_CONFIG=str(root/'absent-browser.json'),RUST_LOG='agentshell_backend=info',
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
                    for label,tool,_ in cases:
                        binding,_=await watch(connection,label)
                        assert binding['conversationId']==identities[label][0]
                        bindings[label]=binding
                        print(label+' native conversation bound',flush=True)
                        text='native-platform-'+label+'-'+uuid.uuid4().hex[:8]
                        observed=[]
                        try:
                            await send(connection,label,binding,text,observed=observed)
                        finally:
                            trace.append({'tool':label,'packets':observed,
                                'transcript':identities[label][2].read_text().splitlines()[-8:]})
                            (output/'trace.json').write_text(json.dumps(trace,indent=2)+'\n')
                        lines=[json.loads(line) for line in identities[label][2].read_text().splitlines()]
                        if tool=='codex':
                            count=sum(event.get('payload',{}).get('type')=='user_message' and event['payload'].get('message')==text for event in lines)
                        elif tool=='agy':
                            request='<USER_REQUEST>\n'+text+'\n</USER_REQUEST>'
                            count=sum(event.get('type')=='USER_INPUT' and event['content'].startswith(request) for event in lines)
                        else:
                            count=sum(event.get('type')=='user' and any(block.get('text')==text for block in event.get('message',{}).get('content',[])) for event in lines)
                        assert count==1,'Input must reach exactly one native conversation once'
                        checks[label+'_bound_history_and_terminal_send']=True
                        print(label+' native chat submitted once and reply received',flush=True)
                        def matching_users(messages):
                            return [message for message in messages if message.get('role')=='user'
                                and message.get('blocks')==[{'type':'text','text':text}]]
                        _,history=await watch(connection,label)
                        assert len(matching_users(history['messages']))==1,'History reload duplicated the native user echo'
                        # Identical prompts intentionally sent twice must remain
                        # two turns after both history reload and offline sync.
                        await send(connection,label,binding,text)
                        _,history=await watch(connection,label)
                        assert len(matching_users(history['messages']))==2,'Repeated prompts were lost or duplicated'
                        request=uuid.uuid4().hex
                        await connection.send(json.dumps({'type':'get-history-state','requestId':request,
                            'sessionName':label,'windowIndex':0}))
                        state=await receive(connection,lambda m:m.get('type')=='history-state' and m.get('requestId')==request)
                        assert state['available'] and state['revision'].startswith('sync-v2:')
                        if tool == 'agy':
                            assert history['historyRevision'] == state['revision']
                        entries=[]
                        limit=2 if tool=='agy' else 200
                        while True:
                            request=uuid.uuid4().hex
                            await connection.send(json.dumps({'type':'get-history-page','requestId':request,
                                'sessionName':label,'windowIndex':0,'revision':state['revision'],
                                'offset':len(entries),'limit':limit}))
                            page=await receive(connection,lambda m:m.get('type')=='history-page' and m.get('requestId')==request)
                            assert 'error' not in page,page.get('error')
                            assert 0<len(page['entries'])<=limit
                            entries[:0]=page['entries']
                            assert page['hasMore']==(len(entries)<page['totalCount'])
                            if not page['hasMore']:
                                break
                        assert len(entries)==page['totalCount']
                        assert len(matching_users([entry['message'] for entry in entries]))==2
                        assert [entry['position'] for entry in entries]==list(range(len(entries)))
                        checks[label+'_user_echo_reconciled_and_intentional_repeats_preserved']=True
                        print(label+' reload and offline sync retain exactly two intentional sends',flush=True)
                        if tool=='agy':
                            assert len(entries)>limit
                            checks['agy_pagination']=True
                            # A regenerated first step can change even as the file grows.
                            transcript=identities[label][2]
                            rewritten=[json.loads(line) for line in transcript.read_text().splitlines()]
                            old=rewritten[0]['content']
                            rewritten[0]['content']=old+' with the updated first step'
                            transcript.write_text(''.join(json.dumps(step)+'\n' for step in rewritten))
                            refresh=await receive(connection,lambda m:m.get('type')=='chat-history'
                                and m.get('bindingId')==binding['bindingId'] and m.get('historyRevision')!=state['revision'])
                            assert rewritten[0]['content'] in texts(refresh) and old not in texts(refresh)
                            checks['agy_rewritten_step_replaces_previous_text']=True
                    assert len({value['bindingId'] for value in bindings.values()})==len(cases)
                    checks['shared_working_directory_does_not_mix_conversations']=True
                    await watch(connection,'agy')
                    tmux('send-keys','-t','agy','-l','/resume '+resumed_id)
                    tmux('send-keys','-t','agy','Enter')
                    changed=await receive(connection,lambda m:m.get('type')=='chat-binding' and
                        (m['state'].get('binding') or {}).get('conversationId')==resumed_id)
                    resumed=changed['state']['binding']
                    assert resumed['bindingId']!=bindings['agy']['bindingId']
                    history=await receive(connection,lambda m:m.get('type')=='chat-history' and m.get('bindingId')==resumed['bindingId'])
                    assert texts(history)==['Fixture '+resumed_id]
                    await send(connection,'agy',bindings['agy'],'stale message',success=False)
                    assert 'stale message' not in resumed_log.read_text()
                    checks['agy_presence_switch_invalidates_previous_binding']=True
                async with websockets.connect(f'ws://127.0.0.1:{port}/ws',additional_headers={'X-Auth-Token':token}) as connection:
                    binding,history=await watch(connection,'agy')
                    assert binding['bindingId']==resumed['bindingId'] and texts(history)==['Fixture '+resumed_id]
                    await send(connection,'agy',binding,'after reconnect')
                    checks['agy_resumed_conversation_survives_reconnect']=True
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

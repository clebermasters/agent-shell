#!/usr/bin/env python3
import argparse,json,os,socket,subprocess,sys,tempfile,time
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'scripts'))
from test_chat_bindings import free_port

def request(url,token,payload):
 try:
  with urlopen(Request(url,data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','X-Auth-Token':token},method='POST'),timeout=5) as response:return response.status
 except HTTPError as error:return error.code

def main():
 parser=argparse.ArgumentParser();parser.add_argument('runtime',type=Path);parser.add_argument('--binary',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();cfg=json.loads(args.runtime.read_text())
 endpoint=f"http://127.0.0.1:{cfg['port']}/api/chat/ui-poc";payload=cfg['agents']['A']|{'title':'Negative publication check','html':'<p>test</p>'};report={}
 assert request(endpoint,'invalid-poc-token',payload)==401;report['authentication_required']=True
 assert request(endpoint,cfg['token'],payload|{'agentStart':payload['agentStart']+1})==409;report['publisher_incarnation_checked']=True
 assert request(endpoint,cfg['token'],payload|{'html':'x'*262145})==409;report['document_size_limited']=True
 assert request(endpoint,cfg['token'],payload|{'title':''})==409;report['empty_title_rejected']=True
 assert request(endpoint,cfg['token'],payload|{'conversationId':'forged-folder-selection'})==422;report['caller_cannot_choose_conversation']=True
 with tempfile.TemporaryDirectory(prefix='agentshell-ui-poc-disabled-') as temporary:
  port=free_port();environment=dict(os.environ,AUTH_TOKEN='disabled-poc-test',AGENTSHELL_HTTP_PORT=str(port),AGENTSHELL_HTTPS_PORT=str(free_port()),RUST_LOG='error');environment.pop('AGENTSHELL_UI_POC',None)
  log=open(Path(temporary)/'backend.log','w');process=subprocess.Popen([str(args.binary.resolve())],cwd=temporary,env=environment,stdout=log,stderr=log)
  try:
   deadline=time.monotonic()+10
   while True:
    try:
     with socket.create_connection(('127.0.0.1',port),timeout=.2):break
    except OSError:
     assert time.monotonic()<deadline;time.sleep(.05)
   assert request(f'http://127.0.0.1:{port}/api/chat/ui-poc','disabled-poc-test',payload)==404;report['disabled_without_explicit_flag']=True
  finally:
   process.terminate()
   try:process.wait(timeout=5)
   except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
 args.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()

#!/usr/bin/env python3
"""Isolated fixture runtime. It never installs hooks or touches the real daemon."""
import argparse,json,os,secrets,shlex,socket,subprocess,sys,tempfile,threading,time,uuid
from pathlib import Path
from http.server import ThreadingHTTPServer,SimpleHTTPRequestHandler
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from test_chat_bindings import FAKE_AGENT,free_port
from fixtures import widgets

def process_start(pid):
 raw=Path(f'/proc/{pid}/stat').read_text();return int(raw[raw.rfind(')')+2:].split()[19])
def native_pid(root):
 descendants={root}
 for _ in range(20):
  for item in Path('/proc').iterdir():
   if not item.name.isdigit():continue
   try:
    raw=(item/'stat').read_text();parent=int(raw[raw.rfind(')')+2:].split()[1])
    if parent in descendants:descendants.add(int(item.name))
   except (OSError,ValueError):continue
  for pid in descendants:
   try:
    if Path(f'/proc/{pid}/comm').read_text().strip()=='codex':return pid
   except OSError:pass
 raise ValueError('Fake native agent not found')
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--binary',type=Path,required=True);parser.add_argument('--directory',type=Path);args=parser.parse_args()
 root=args.directory or Path(tempfile.mkdtemp(prefix='agentshell-ui-poc-runtime-'));root.mkdir(parents=True,exist_ok=True)
 socket_name='agentshell-ui-poc-'+uuid.uuid4().hex;token=secrets.token_urlsafe(24);port=free_port()
 def tmux(*args):return subprocess.check_output(['tmux','-L',socket_name,*args],text=True).strip()
 inventory=subprocess.check_output(['tmux','list-panes','-a','-F','#{pane_id}|#{pane_pid}'],text=True)
 project=root/'shared-folder';project.mkdir();script=root/'fake-agent.py';script.write_text(FAKE_AGENT)
 backend=None;http=None
 try:
  ids=[str(uuid.uuid4()) for _ in range(3)];agents={}
  for label,sid in zip(['A','B'],ids):
   command='python3 '+shlex.quote(str(script))+' '+shlex.quote(str(project))+' '+label+' '+sid
   tmux('new-session','-d','-s','ui-'+label,'-c',str(project),command)
  deadline=time.monotonic()+8
  while not all((project/(label+'.ready')).exists() for label in ['A','B']):
   assert time.monotonic()<deadline;time.sleep(.02)
  for label in ['A','B']:
   fields=tmux('display-message','-p','-t','ui-'+label,'#{pane_id}|#{pane_pid}').split('|');pid=native_pid(int(fields[1]))
   agents[label]={'paneId':fields[0],'agentPid':pid,'agentStart':process_start(pid)}
  instance=root/'backend';instance.mkdir()
  context=tmux('display-message','-p','-t','ui-A','#{socket_path},#{pid},0')
  log=(root/'backend.log').open('w')
  backend=subprocess.Popen([str(args.binary.resolve())],cwd=instance,env=dict(os.environ,AUTH_TOKEN=token,AGENTSHELL_HTTP_PORT=str(port),AGENTSHELL_HTTPS_PORT=str(free_port()),AGENTSHELL_UI_POC='1',TMUX=context,TOKIO_WORKER_THREADS='2',RUST_LOG='error'),stdout=log,stderr=log)
  deadline=time.monotonic()+10
  while True:
   assert backend.poll() is None
   try:
    with socket.create_connection(('127.0.0.1',port),timeout=.2):break
   except OSError:
    assert time.monotonic()<deadline;time.sleep(.05)
  class Assets(SimpleHTTPRequestHandler):
   def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(ROOT/'android-native/app/src/debug/assets'),**kwargs)
   def log_message(self,*args):pass
  asset_port=free_port();http=ThreadingHTTPServer(('127.0.0.1',asset_port),Assets);threading.Thread(target=http.serve_forever,daemon=True).start()
  documents=widgets('https://appassets.androidplatform.net/assets/ui-poc');published=[]
  for label,entries in [('A',documents),('B',[documents[1]])]:
   env=dict(os.environ,AGENTSHELL_UI_PANE=agents[label]['paneId'],AGENTSHELL_UI_AGENT_PID=str(agents[label]['agentPid']),AGENTSHELL_UI_URL=f'http://127.0.0.1:{port}',AGENTSHELL_UI_TOKEN=token)
   requests=[{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2024-11-05'}},{'jsonrpc':'2.0','id':2,'method':'tools/list'}]
   requests += [{'jsonrpc':'2.0','id':index+3,'method':'tools/call','params':{'name':'render_interactive_ui','arguments':doc}} for index,doc in enumerate(entries)]
   result=subprocess.run([sys.executable,str(Path(__file__).with_name('mcp_server.py'))],input=''.join(json.dumps(x)+'\n' for x in requests),env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
   responses=[json.loads(line) for line in result.stdout.splitlines()]
   for doc,response in zip(entries,responses[2:]):
    assert 'result' in response,response
    published.append({'session':label,'document':doc,**response['result']['structuredContent']})
  config={'port':port,'token':token,'assetsPort':asset_port,'socket':socket_name,'project':str(project),'ids':ids,'agents':agents,'widgets':published,'backendPid':backend.pid}
  (root/'runtime.json').write_text(json.dumps(config,indent=2));(root/'runtime.json').chmod(0o600)
  print('Isolated runtime ready: '+str(root/'runtime.json'),flush=True)
  while backend.poll() is None:time.sleep(.5)
 except KeyboardInterrupt:pass
 finally:
  if http:http.shutdown()
  if backend:
   backend.terminate()
   try:backend.wait(timeout=5)
   except subprocess.TimeoutExpired:backend.kill();backend.wait(timeout=5)
  subprocess.run(['tmux','-L',socket_name,'kill-server'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  assert subprocess.check_output(['tmux','list-panes','-a','-F','#{pane_id}|#{pane_pid}'],text=True)==inventory,'Real terminal inventory changed'
if __name__=='__main__':main()

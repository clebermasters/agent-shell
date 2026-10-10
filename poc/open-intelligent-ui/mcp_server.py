#!/usr/bin/env python3
"""Minimal stdio MCP publishing adapter, for the isolated POC only."""
import json,os,sys
from pathlib import Path
from urllib.request import Request,urlopen

def owner():
 pid=int(os.environ.get('AGENTSHELL_UI_AGENT_PID',os.getppid()))
 for _ in range(40):
  path=Path('/proc')/str(pid);raw=(path/'stat').read_text();fields=raw[raw.rfind(')')+2:].split()
  if (path/'comm').read_text().strip() in ['codex','claude','opencode']:
   return pid,int(fields[19])
  pid=int(fields[1])
  if pid<=1:raise ValueError('No owning native agent')
 raise ValueError('No owning native agent')

def publish(title,html):
 pid,start=owner()
 payload={'paneId':os.environ.get('AGENTSHELL_UI_PANE',os.environ.get('TMUX_PANE')),'agentPid':pid,'agentStart':start,'title':title,'html':html}
 request=Request(os.environ['AGENTSHELL_UI_URL'].rstrip('/')+'/api/chat/ui-poc',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','X-Auth-Token':os.environ['AGENTSHELL_UI_TOKEN']},method='POST')
 with urlopen(request,timeout=10) as response:return json.load(response)

def run():
 for line in sys.stdin:
  request={}
  try:
   request=json.loads(line)
   if not isinstance(request,dict):raise ValueError("MCP request must be an object")
   if 'id' not in request:continue
   method=request.get('method')
   if method=='initialize':result={'protocolVersion':request.get('params',{}).get('protocolVersion','2024-11-05'),'capabilities':{'tools':{}},'serverInfo':{'name':'agentshell-open-ui-poc','version':'0.1.0'}}
   elif method=='ping':result={}
   elif method=='tools/list':result={'tools':[{'name':'render_interactive_ui','description':
    'Publish a complete interactive HTML document in this agent conversation. Use for useful charts, diagrams, calculators, 3D previews or maps. '
    'Design mobile first at 280 CSS pixels: no page overflow, labelled inputs, 44px touch targets, system fonts, light/dark themes and reduced motion. '
    'Use self-contained inline CSS/JS and accurate labelled data. No secrets or private network calls. '
    'For an agent follow-up use parent.postMessage({type:"send-prompt",text:"contextual question"},"*"). '
    'The host handles sizing, fullscreen and image sharing. Tool result confirms publication to the exact current native conversation. '
    'Optional control persistence: post {type:"widget-state",state:{inputs,custom}}; listen for parent widget-context messages containing state/theme. '
    'Available local libraries: https://appassets.androidplatform.net/assets/ui-poc/vendor/three.js and leaflet.js (plus leaflet.css). '
    'Preview branch only.',
    'inputSchema':{'type':'object','properties':{'title':{'type':'string','minLength':1,'maxLength':160},'html':{'type':'string','minLength':1,'maxLength':262144}},'required':['title','html'],'additionalProperties':False}}]}
   elif method=='tools/call':
    if request['params']['name']!='render_interactive_ui':raise ValueError('Unknown tool')
    args=request['params']['arguments'];artifact=publish(args['title'],args['html'])
    result={'content':[{'type':'text','text':'Interactive widget published: '+artifact['widgetId']}],'structuredContent':artifact}
   else:raise ValueError('Unsupported method')
   output={'jsonrpc':'2.0','id':request['id'],'result':result}
  except Exception as error:output={'jsonrpc':'2.0','id':(request.get('id') if isinstance(request,dict) else None),'error':{'code':-32000,'message':type(error).__name__+': '+str(error)}}
  print(json.dumps(output),flush=True)
if __name__=='__main__':run()

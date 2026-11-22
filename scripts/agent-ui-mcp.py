#!/usr/bin/env python3
"""AgentShell conversation-scoped interactive UI MCP server (stdio)."""
import base64,json,mimetypes,os,sys
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from urllib.parse import urlsplit
from process_identity import process_info, process_name

class PublicationError(RuntimeError):
 """A backend rejection with its bounded, credential-free explanation."""

def owner():
 pid=int(os.environ.get('AGENTSHELL_UI_AGENT_PID',os.getppid()))
 for _ in range(40):
  info=process_info(pid)
  if info['name'] in ['codex','claude','agy','opencode']:
   return pid,info['generation']
  pid=info['parentPid']
  if pid<=1:raise ValueError('No owning native agent')
 raise ValueError('No owning native agent')

def runtime_config():
 path=Path(os.environ.get('AGENTSHELL_UI_CONFIG',Path.home()/'.config/agentshell/interactive-ui.json'))
 value=json.loads(path.read_text()) if path.exists() else {}
 return (os.environ.get('AGENTSHELL_UI_URL') or os.environ.get('AGENTSHELL_URL') or value.get('url') or 'http://127.0.0.1:4010',
         os.environ.get('AGENTSHELL_UI_TOKEN') or os.environ.get('AGENTSHELL_AUTH_TOKEN') or os.environ.get('AUTH_TOKEN') or value.get('token'))

def design_system():
 paths=[Path(__file__).with_name('mobile.css'),Path(__file__).resolve().parents[1]/'android-native/app/src/main/assets/ui/mobile.css']
 css=next((p.read_text() for p in paths if p.exists()),None)
 if not css:raise ValueError('Design system is not installed')
 return css+'\n\nUse the provided classes: eyebrow, subtitle, panel, metric, chips, row, stack, primary, visual. Theme variables --bg, --ink, --muted, --panel, --accent and --line automatically follow Android. Complete HTML/CSS/JS is supported. For actions call parent.postMessage({type:"send-prompt",text:"contextual question"},"*").'

def publisher_identity(metadata=None):
 """The per-call native thread wins over a shared daemon's inherited pane."""
 if metadata is not None and not isinstance(metadata,dict):raise ValueError('Invalid MCP metadata')
 pid,start=owner()
 payload={'agentPid':pid,'agentStart':start}
 thread_id=(metadata or {}).get('threadId')
 if process_name(pid)=='codex' and isinstance(thread_id,str) and thread_id.strip():
  payload['codexThreadId']=thread_id
 else:
  payload['paneId']=os.environ.get('AGENTSHELL_UI_PANE',os.environ.get('TMUX_PANE'))
 return payload

def post_publication(endpoint,payload,timeout=10):
 url,token=runtime_config()
 if not url or not token:raise ValueError('AgentShell publisher runtime is not configured')
 parsed=urlsplit(url)
 if parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
  raise ValueError('AgentShell URL must be an HTTP(S) base URL without credentials or query parameters')
 request=Request(url.rstrip('/')+endpoint,data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','X-Auth-Token':token},method='POST')
 try:
  with urlopen(request,timeout=timeout) as response:
   data=response.read(65537)
   if len(data)>65536:raise PublicationError('AgentShell publication response is too large')
   return json.loads(data)
 except HTTPError as error:
  with error:
   body=error.read(8193)
  detail=body[:8192].decode('utf-8',errors='replace').strip()
  if len(body)>8192:detail+=' [truncated]'
  # Neither the request URL nor authentication headers belong in tool output.
  detail=detail.replace(token,'[redacted]')
  raise PublicationError(f'AgentShell publication rejected (HTTP {error.code}): {detail or error.reason}') from None

def publish(title,html,metadata=None):
 if not isinstance(title,str) or not title.strip() or len(title.encode())>160:raise ValueError('Title must contain 1–160 bytes')
 if not isinstance(html,str) or not html.strip() or len(html.encode())>262144:raise ValueError('HTML must contain 1–262144 bytes')
 return post_publication('/api/chat/ui',dict(publisher_identity(metadata),title=title,html=html))

def publish_chat_file(path,prompt='',metadata=None,expected_session_name=None,expected_window_index=None):
 """Publish assistant content in this conversation; never type it back into an agent."""
 if not isinstance(path,(str,os.PathLike)) or not os.fspath(path):raise ValueError('A file path is required')
 if not isinstance(prompt,str) or len(prompt.encode())>65536:raise ValueError('Description must be at most 65536 bytes')
 attachment=Path(path)
 if not attachment.is_file():raise ValueError('Attachment must be an existing regular file')
 # Bound the read as well as the stat: files can grow between these operations.
 maximum=10*1024*1024
 if attachment.stat().st_size>maximum:raise ValueError('Attachment exceeds the 10 MiB upload limit')
 with attachment.open('rb') as stream:data=stream.read(maximum+1)
 if len(data)>maximum:raise ValueError('Attachment exceeds the 10 MiB upload limit')
 payload=dict(publisher_identity(metadata),text=prompt,files=[{
  'filename':attachment.name,'mimeType':mimetypes.guess_type(attachment.name)[0] or 'application/octet-stream',
  'data':base64.b64encode(data).decode('ascii')}])
 if expected_session_name is not None:
  if not isinstance(expected_session_name,str) or not expected_session_name.strip():raise ValueError('Expected session name must not be empty')
  payload['expectedSessionName']=expected_session_name
 if expected_window_index is not None:
  if type(expected_window_index) is not int or expected_window_index<0:raise ValueError('Expected window index must be a nonnegative integer')
  payload['expectedWindowIndex']=expected_window_index
 result=post_publication('/api/chat/publish',payload,timeout=30)
 valid=(isinstance(result,dict) and result.get('published') is True and
        all(isinstance(result.get(key),str) and result[key].strip() for key in ('bindingId','conversationKey','sessionName')) and
        type(result.get('windowIndex')) is int and result['windowIndex']>=0 and
        isinstance(result.get('fileIds'),list) and len(result['fileIds'])==1 and
        isinstance(result['fileIds'][0],str) and bool(result['fileIds'][0].strip()))
 if not valid:raise PublicationError('AgentShell did not confirm a saved conversation-bound file publication')
 if expected_session_name is not None and result['sessionName']!=expected_session_name:raise PublicationError('AgentShell returned a different session than the requested guard')
 if expected_window_index is not None and result['windowIndex']!=expected_window_index:raise PublicationError('AgentShell returned a different window than the requested guard')
 return result

def run():
 for line in sys.stdin:
  request={}
  try:
   request=json.loads(line)
   if not isinstance(request,dict):raise ValueError("MCP request must be an object")
   if 'id' not in request:continue
   method=request.get('method')
   if method=='initialize':result={'protocolVersion':request.get('params',{}).get('protocolVersion','2024-11-05'),'capabilities':{'tools':{}},'serverInfo':{'name':'agentshell-ui','version':'1.1.0'}}
   elif method=='ping':result={}
   elif method=='tools/list':result={'tools':[{'name':'render_interactive_ui','description':
    'Publish a complete interactive HTML document in this agent conversation. Use for useful charts, diagrams, calculators, 3D previews or maps. '
    'Design mobile first at 280 CSS pixels: no page overflow, labelled inputs, 44px touch targets, system fonts, light/dark themes and reduced motion. '
    'Use self-contained inline CSS/JS and accurate labelled data. No secrets or private network calls. '
    'For an agent follow-up use parent.postMessage({type:"send-prompt",text:"contextual question"},"*"). '
    'The host handles sizing, fullscreen and image sharing. Tool result confirms publication to the exact current native conversation. '
    'Optional control persistence: post {type:"widget-state",state:{inputs,custom}}; listen for parent widget-context messages containing state/theme. '
    'Available local libraries: https://appassets.androidplatform.net/assets/ui/vendor/three.js and leaflet.js (plus leaflet.css). '
    'Uses the app theme automatically. Call get_ui_design_system for reusable classes and controls.',
    'inputSchema':{'type':'object','properties':{'title':{'type':'string','minLength':1,'maxLength':160},'html':{'type':'string','minLength':1,'maxLength':262144}},'required':['title','html'],'additionalProperties':False}},{'name':'get_ui_design_system','description':'Get AgentShell mobile-first light/dark CSS, reusable controls and the follow-up bridge contract before designing an interactive view.','inputSchema':{'type':'object','properties':{},'additionalProperties':False}},
    {'name':'send_file_to_chat','description':
     'Send a requested local image, audio, document or other file to this exact AgentShell chat. '
     'The backend identifies the calling native conversation and its current TMUX pane/session/window automatically. '
     'No fixed session name or manual routing is needed. The file is saved as an assistant attachment, with normal chat reply notifications; '
     'it is never submitted back to the agent as input. Use only when the user requests file delivery. '
     'The path is on the agent host, with a 10 MiB upload limit. Success confirms saved history and the actual destination, not that an offline phone has downloaded it.',
     'inputSchema':{'type':'object','properties':{'path':{'type':'string','minLength':1},'description':{'type':'string','maxLength':65536}},'required':['path'],'additionalProperties':False}}]}
   elif method=='tools/call':
    if request['params']['name']=='get_ui_design_system':
     result={'content':[{'type':'text','text':design_system()}]}
     print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
     continue
    if request['params']['name']=='send_file_to_chat':
     args=request['params']['arguments']
     artifact=publish_chat_file(args['path'],args.get('description',''),request['params'].get('_meta'))
     result={'content':[{'type':'text','text':'File saved in '+artifact['sessionName']+':'+str(artifact['windowIndex'])}], 'structuredContent':artifact}
     print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
     continue
    if request['params']['name']!='render_interactive_ui':raise ValueError('Unknown tool')
    args=request['params']['arguments'];artifact=publish(args['title'],args['html'],request['params'].get('_meta'))
    result={'content':[{'type':'text','text':'Interactive widget published: '+artifact['widgetId']}],'structuredContent':artifact}
   else:raise ValueError('Unsupported method')
   output={'jsonrpc':'2.0','id':request['id'],'result':result}
  except Exception as error:output={'jsonrpc':'2.0','id':(request.get('id') if isinstance(request,dict) else None),'error':{'code':-32000,'message':type(error).__name__+': '+str(error)}}
  print(json.dumps(output),flush=True)
if __name__=='__main__':run()

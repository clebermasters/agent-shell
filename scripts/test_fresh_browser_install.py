#!/usr/bin/env python3
"""Run the unified browser installer in an owned, disposable Ubuntu 22.04 container.

Copies only deployment sources/assets and a prebuilt binary; never mounts .env,
Chrome data, MCP credentials or the user's home directory into the container.
"""
from pathlib import Path
import argparse
import json
import shutil
import subprocess
import uuid

PROJECT=Path(__file__).resolve().parents[1]

CHECK = '''import json,hashlib,os,subprocess,urllib.request,time,pwd
from pathlib import Path
import importlib.util
if not Path('/.dockerenv').exists() or os.environ.get('AGENTSHELL_INSTALL_TEST')!='1':
 raise SystemExit('Run only inside the owned installation test container')
spec=importlib.util.spec_from_file_location('installer','/repo/scripts/install-backend.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
base=Path('/opt/agentshell');record=json.loads((base/'installation.json').read_text())
for name,digest in record['files'].items():assert hashlib.sha256((base/name).read_bytes()).hexdigest()==digest,name
config=Path('/home/fixture/.config/agentshell/browser.json');before=config.read_bytes();value=json.loads(before)
assert config.stat().st_mode&0o777==0o600
assert value['publicOrigin']=='https://browser.example' and value.get('profileDirectory') is None
assert value['userDataDir']=='/home/fixture/.local/share/agentshell/chrome'
desktop_config=Path('/home/fixture/.config/agentshell/browser-desktop.json')
desktop_before=desktop_config.read_bytes();desktop=json.loads(desktop_before)
assert desktop_config.stat().st_mode&0o777==0o600
assert desktop['origin']=='https://browser.example' and desktop['runtimeEnv']==str(base/'backend/runtime.env')
assert Path(desktop['stateDir']).stat().st_mode&0o777==0o700
for name in ['agentshell-browser','agentshell-send-to-phone','agentshell-return-to-computer']:
 command=Path('/home/fixture/.local/bin')/name
 assert command.stat().st_mode&0o777==0o755
 subprocess.run(['sh','-n',str(command)],check=True)
assert Path('/home/fixture/.local/share/applications/agentshell-browser.desktop').is_file()
assert Path('/home/fixture/.config/autostart/agentshell-browser-desktop-agent.desktop').is_file()
agent_unit='/home/fixture/.config/systemd/user/agentshell-browser-desktop-agent.service'
subprocess.run(['systemd-analyze','verify',agent_unit],check=True,capture_output=True)
assert (base/'browser/browser-desktop-agent.py').is_file()
assert (base/'browser/browser-native.py').is_file()
subprocess.run(['systemd-analyze','verify','/home/fixture/.config/systemd/user/agentshell-browser-native.service'],check=True,capture_output=True)
subprocess.run(['/usr/bin/python3','-c','import gi;gi.require_version("Gtk","3.0");gi.require_version("WebKit2","4.0");gi.require_version("Soup","2.4");from gi.repository import Gtk,WebKit2,Soup'],check=True)
runtime=module.dotenv(base/'backend/runtime.env');original_token=runtime['AUTH_TOKEN']
assert Path('/home/fixture/.config/systemd/user/agentshell-browser.service').is_file()
unit='/home/fixture/.config/systemd/user/agentshell-browser.service'
subprocess.run(['systemd-analyze','verify',unit],check=True,capture_output=True)
home=Path('/home/fixture');codex=home/'.codex/config.toml';claude=home/'.claude.json';opencode=home/'.config/opencode/opencode.json'
assert '[mcp_servers.agentshell-ui]' in codex.read_text()
assert 'agentshell-ui' in json.loads(claude.read_text())['mcpServers']
assert 'agentshell-ui' in json.loads(opencode.read_text())['mcp']
codex.write_text(codex.read_text()+'\\n[mcp_servers.fixture-other]\\ncommand = "/bin/true"\\nenabled = false\\n')
for path,key in [(claude,'mcpServers'),(opencode,'mcp')]:
 data=json.loads(path.read_text())
 data[key]['fixture-other']=({'type':'local','command':['/bin/true'],'enabled':False} if key=='mcp' else {'type':'stdio','command':'/bin/true','args':[]})
 path.write_text(json.dumps(data))
# Prove the same installer repairs a missing pinned client file rather than
# accepting the package database's installed status as sufficient.
Path('/usr/share/kasmvnc/www/vnc.html').unlink()
check=json.loads(subprocess.check_output(['/repo/install.sh','--check','--with-browser','kasmvnc']))
assert not check['browser']['softwareReady'] and not check['browser']['clientAssets']['vnc.html']
subprocess.run(['/repo/install.sh','--no-start','--with-browser','kasmvnc','--binary','/repo/backend'],check=True,stdout=subprocess.DEVNULL)
assert config.read_bytes()==before,'Repeat installation changed browser identity/credentials'
assert desktop_config.read_bytes()==desktop_before,'Repeat installation changed desktop viewer configuration'
assert module.dotenv(base/'backend/runtime.env')['AUTH_TOKEN']==original_token
assert 'fixture-other' in codex.read_text()
assert 'fixture-other' in json.loads(claude.read_text())['mcpServers']
assert 'fixture-other' in json.loads(opencode.read_text())['mcp']
check=json.loads(subprocess.check_output(['/repo/install.sh','--check','--with-browser','kasmvnc']))
assert not check['missingSystemPackages'] and all(check['agents'].values()) and check['browser']['softwareReady']
specification=json.loads((base/'browser/dependencies.json').read_text())
for package in specification['aptPackages']:
 assert subprocess.check_output(['dpkg-query','-W','-f=${db:Status-Status}',package]).decode()=='installed',package
for name,digest in specification['clientHashes'].items():assert hashlib.sha256((Path('/usr/share/kasmvnc/www')/name).read_bytes()).hexdigest()==digest
account=pwd.getpwnam('fixture')
environment=dict(os.environ,HOME=str(home),USER='fixture',LOGNAME='fixture',PATH=str(base/'tools/bin')+':'+str(base/'node/bin')+':'+str(home/'.cargo/bin')+':'+os.environ['PATH'])
versions={}
for name in ['rustc','node','npm','codex','claude','opencode','google-chrome']:
 versions[name]=subprocess.check_output([name,'--version'],env=environment,user=account.pw_uid,group=account.pw_gid,text=True).splitlines()[0]
for name in [base/'backend/agentshell-backend',Path('/usr/bin/Xvnc'),Path('/opt/google/chrome/chrome')]:
 assert 'not found' not in subprocess.check_output(['ldd',str(name)],text=True),str(name)
subprocess.run(['dbus-run-session','--','busctl','--user','call','org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','StartServiceByName','su','org.freedesktop.secrets','0'],env=environment,user=account.pw_uid,group=account.pw_gid,check=True,capture_output=True,timeout=20)
requests=[{'id':1,'method':'initialize','params':{'protocolVersion':'2024-11-05'}},{'id':2,'method':'tools/list'},{'id':3,'method':'tools/call','params':{'name':'get_ui_design_system','arguments':{}}}]
result=subprocess.run(['/usr/bin/python3',str(base/'ui/agent-ui-mcp.py')],input=''.join(json.dumps(item)+'\\n' for item in requests),env=environment,user=account.pw_uid,group=account.pw_gid,capture_output=True,text=True,check=True)
messages=[json.loads(line) for line in result.stdout.splitlines()]
assert len(messages)==3 and all('result' in item for item in messages)
assert {tool['name'] for tool in messages[1]['result']['tools']}=={'get_ui_design_system','render_interactive_ui'}
assert '--accent' in messages[2]['result']['content'][0]['text']
server=subprocess.Popen([str(base/'backend/agentshell-backend')],cwd=base/'backend',env=dict(os.environ,**runtime,AGENTSHELL_BROWSER_CONFIG=str(config)),user=account.pw_uid,group=account.pw_gid,stdout=open('/tmp/backend.log','w'),stderr=subprocess.STDOUT)
try:
 for i in range(50):
  try:
   request=urllib.request.Request('http://127.0.0.1:4010/api/browser/capabilities',headers={'X-Auth-Token':runtime['AUTH_TOKEN']})
   with urllib.request.urlopen(request,timeout=2) as response:capability=json.load(response)
   break
  except OSError:time.sleep(.1)
 else:raise AssertionError('Installed backend did not start')
 assert capability['available'] and capability['session']['state']=='stopped',capability
finally:server.terminate();server.wait(timeout=10)
print(json.dumps({'freshBrowserDependencies':True,'desktopGtkWebKitAndCommands':True,'independentProfileDefault':True,'keyringServiceActivation':True,'allAgentClisAndMcpRegistration':True,'mcpToolsAndDesignSystem':True,'filteredCredentialBusInstalled':True,'pinnedClientRepair':True,'privateConfig':True,'automaticChromeProfile':True,'repeatInstallPreservesIdentityAndToken':True,'unrelatedMcpPreserved':True,'browserUnitValid':True,'installedBackendCapabilities':True,'missingNativeLibraries':False,'versions':versions}))
'''

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=Path('/tmp/agentshell-browser-implementation/fresh-install'))
    args=parser.parse_args();output=args.output
    if output.exists() and any(output.iterdir()):parser.error('--output must be a new or empty directory for a genuinely fresh install')
    output.mkdir(parents=True,exist_ok=True);output.chmod(0o700)
    package=output/'package';package.mkdir()
    manifest=json.loads((PROJECT/'deployment/backend-dependencies.json').read_text())
    for source in dict.fromkeys([*manifest['installerFiles'],'deployment/backend-dependencies.json',*[entry['source'] for entry in manifest['files']]]):
        destination=package/source;destination.parent.mkdir(parents=True,exist_ok=True)
        if (PROJECT/source).is_dir():shutil.copytree(PROJECT/source,destination,dirs_exist_ok=True)
        else:shutil.copyfile(PROJECT/source,destination)
    (package/'install.sh').chmod(0o755)
    shutil.copyfile(PROJECT/'backend-rust/target/release/agentshell-backend',package/'backend');(package/'backend').chmod(0o755)
    (package/'check.py').write_text(CHECK)
    (package/'bootstrap.sh').write_text('''#!/bin/bash
set -eu
useradd -m -u 1100 fixture
for fixture_command in python3 node codex claude opencode google-chrome Xvnc; do
    if command -v "$fixture_command" >/dev/null 2>&1; then
        echo "Fixture is already provisioned: $fixture_command" >&2
        exit 1
    fi
done
/repo/install.sh --service-user fixture --no-start --with-browser kasmvnc --browser-origin https://browser.example --binary /repo/backend
export SERVICE_USER=fixture AGENTSHELL_INSTALL_TEST=1
python3 /repo/check.py
''')
    assert not (package/'.env').exists()
    mounts=[]
    # Each run starts with empty dependency/home directories. Never reuse the
    # previous host fixture's installed tools or configuration.
    for name,destination in [('opt','/opt'),('home','/home'),('apt-lists','/var/lib/apt/lists'),('apt-cache','/var/cache/apt')]:
        directory=output/('container-'+name);directory.mkdir(exist_ok=True)
        mounts.extend(['-v',str(directory.resolve())+':'+destination])
    with (output/'install.log').open('w') as log:
        completed=subprocess.run(['docker','run','--rm','--name','agentshell-browser-install-test-'+uuid.uuid4().hex[:8],'--label','agentshell-test=remote-browser',
            '-v',str(package.resolve())+':/repo:ro',*mounts,'ubuntu:22.04','bash','/repo/bootstrap.sh'],stdout=log,stderr=subprocess.STDOUT)
    if completed.returncode:raise SystemExit('Fresh browser install failed; inspect '+str(output/'install.log'))
    result=next(json.loads(line) for line in reversed((output/'install.log').read_text().splitlines()) if line.startswith('{'))
    (output/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))

if __name__=='__main__':main()

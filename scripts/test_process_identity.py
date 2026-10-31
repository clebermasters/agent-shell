#!/usr/bin/env python3
"""Cross-platform process identity fixtures; never inspect or signal an agent."""
import importlib.util
import json
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import process_identity as identity


def module(name, filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


class ProcessIdentityTests(unittest.TestCase):
    def native(self, **changes):
        return dict({'pid':123,'parentPid':12,'generation':1_791_234_567_123_456,'name':'codex'},**changes)

    def query(self,value=None,returncode=0,files=False):
        response=subprocess.CompletedProcess([],returncode,stdout=json.dumps(value or self.native()).encode(),stderr=b'private diagnostic')
        with patch.object(identity.sys,'platform','darwin'),patch.object(identity,'backend_binary',return_value='/owned/backend'),patch.object(identity.subprocess,'run',return_value=response) as run:
            actual=identity.process_info(123,files)
            self.assertEqual(run.call_args.args[0],['/owned/backend','process-info','--pid','123']+(['--open-files'] if files else []))
            self.assertEqual(run.call_args.kwargs['timeout'],2)
            self.assertNotIn('shell',run.call_args.kwargs)
            return actual

    def test_mac_generation_is_exactly_the_canonical_unsigned_integer(self):
        self.assertEqual(self.query()['generation'],1_791_234_567_123_456)

    def test_mac_open_files_are_explicit_and_validated(self):
        value=self.native(openFiles=['/Users/test/.codex/sessions/owned.jsonl'])
        self.assertEqual(self.query(value,files=True)['openFiles'],value['openFiles'])
        with self.assertRaisesRegex(ValueError,'open-file'):
            self.query(self.native(openFiles=['socket:[123]']),files=True)

    def test_invalid_identity_cannot_bind_a_reused_or_unknown_process(self):
        for value in [self.native(pid=124),self.native(generation=True),self.native(generation=0),self.native(parentPid=-1),self.native(name='')]:
            with self.subTest(value=value),self.assertRaises(ValueError):self.query(value)

    def test_permission_or_process_errors_do_not_leak_helper_output(self):
        with self.assertRaises(ValueError) as caught:self.query(returncode=1)
        self.assertNotIn('private',str(caught.exception))
        with patch.object(identity.sys,'platform','darwin'),patch.object(identity,'backend_binary',return_value='/owned/backend'),patch.object(identity.subprocess,'run',side_effect=subprocess.TimeoutExpired('private',2)):
            with self.assertRaisesRegex(ValueError,'Unable to inspect'):identity.process_info(123)

    def test_malformed_and_oversized_helper_output_fail_closed(self):
        for stdout in [b'not json',b'x'*(1024*1024+1),b'[]']:
            response=subprocess.CompletedProcess([],0,stdout=stdout,stderr=b'')
            with patch.object(identity.sys,'platform','darwin'),patch.object(identity,'backend_binary',return_value='/owned/backend'),patch.object(identity.subprocess,'run',return_value=response):
                with self.assertRaises(ValueError):identity.process_info(123)

    def test_linux_retains_start_ticks_and_handles_parentheses_in_comm(self):
        fields=['S','12']+['0']*17+['123456789']
        def read(path,*args,**kwargs):
            if str(path)=='/proc/123/stat':return '123 (name with ) parentheses) '+' '.join(fields)
            if str(path)=='/proc/123/comm':return 'codex\n'
            raise AssertionError('Unexpected path '+str(path))
        with patch.object(identity.sys,'platform','linux'),patch.object(identity.Path,'read_text',read):
            info=identity.process_info(123)
        self.assertEqual((info['parentPid'],info['generation'],info['name']),(12,123456789,'codex'))

    def test_binary_resolution_uses_installed_sibling_and_explicit_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);binary=root/'bin/agentshell-backend';binary.parent.mkdir();binary.write_text('fixture');binary.chmod(0o700)
            with patch.object(identity,'__file__',str(root/'ui/process_identity.py')),patch.dict(identity.os.environ,{},clear=True):
                self.assertEqual(identity.backend_binary(),str(binary))
            with patch.dict(identity.os.environ,{'AGENTSHELL_BACKEND_BIN':'relative/backend'}):
                with self.assertRaises(ValueError):identity.backend_binary()
            with patch.dict(identity.os.environ,{'AGENTSHELL_BACKEND_BIN':str(binary)}):
                self.assertEqual(identity.backend_binary(),str(binary))

    def test_interpreter_defaults_preserve_linux_and_follow_mac_override(self):
        with patch.object(identity.sys,'platform','linux'):self.assertEqual(identity.python_command(),'/usr/bin/python3')
        with patch.object(identity.sys,'platform','darwin'):
            self.assertEqual(identity.python_command(),identity.sys.executable)
            self.assertEqual(identity.python_command('/chosen/python3'),'/chosen/python3')

    def test_hook_and_mcp_parent_walks_share_the_same_generation(self):
        hook=module('native_hook','chat-binding-hook.py');mcp=module('native_mcp','agent-ui-mcp.py')
        nodes={123:self.native(),12:self.native(pid=12,parentPid=1,name='launchd',generation=900)}
        with patch.object(hook,'native_process_info',side_effect=lambda pid:nodes[pid]),patch.object(hook.os,'getppid',return_value=123):
            self.assertEqual(hook.owner('codex'),(123,self.native()['generation']))
        with patch.object(mcp,'process_info',side_effect=lambda pid:nodes[pid]),patch.dict(mcp.os.environ,{'AGENTSHELL_UI_AGENT_PID':'123'}):
            self.assertEqual(mcp.owner(),(123,self.native()['generation']))

    def test_nonlinux_live_reload_is_reported_as_pending_not_no_daemon(self):
        reload=module('native_reload','reload-codex-mcp.py')
        with patch.object(reload.sys,'platform','darwin'):
            with self.assertRaisesRegex(ValueError,'reload MCP servers'):reload.daemon_socket(Path('/owned/home'))

    def test_identity_helper_updates_change_mcp_revision_without_changing_adapter(self):
        installer=module('identity_mcp_installer','install-ui-mcp.py')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);adapter=root/'agent-ui-mcp.py';helper=root/'process_identity.py'
            adapter.write_text('unchanged adapter');helper.write_text('identity version one')
            first=installer.server_environment(adapter,root/'runtime.json')
            helper.write_text('identity version two')
            second=installer.server_environment(adapter,root/'runtime.json')
            self.assertNotEqual(first['AGENTSHELL_UI_REVISION'],second['AGENTSHELL_UI_REVISION'])
            self.assertEqual(first['AGENTSHELL_UI_CONFIG'],second['AGENTSHELL_UI_CONFIG'])

    def test_hook_installer_quotes_an_explicit_interpreter_and_preserves_linux_default(self):
        installer=module('identity_hook_installer','install-chat-binding-hooks.py')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);script=root/'hook with spaces.py';script.write_text('fixture')
            argv=['install-chat-binding-hooks.py','--script',str(script),'--user-home',directory,'--python','/chosen Python/python3']
            with patch.object(installer.os,'environ',{'AUTH_TOKEN':'synthetic-token'}),patch('sys.argv',argv),patch('sys.stdout',io.StringIO()):
                installer.main()
            command=json.loads((root/'.claude/settings.json').read_text())['hooks']['SessionStart'][-1]['hooks'][0]['command']
            import shlex
            self.assertEqual(shlex.split(command),['/chosen Python/python3',str(script),'--tool','claude'])


if __name__=='__main__':unittest.main()

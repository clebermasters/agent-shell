#!/usr/bin/env python3
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

spec=importlib.util.spec_from_file_location('native',Path(__file__).with_name('browser-native.py'))
native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native)
runtime=native.load_runtime(Path(__file__).with_name('browser-runtime.py'))

class NativeSafetyTests(unittest.TestCase):
    def test_stale_or_foreign_owner_is_never_signalled(self):
        with tempfile.TemporaryDirectory() as directory:
            value={'runtimeDir':directory,'userDataDir':directory}
            record={'chromePid':os.getpid(),'chromeGeneration':'stale:1'}
            with patch.object(native.os,'kill',side_effect=AssertionError('No signal permitted')):
                with self.assertRaises(runtime.RuntimeErrorCode):native.quit_chrome(value,record,runtime)

    def test_second_close_request_never_escalates(self):
        with tempfile.TemporaryDirectory() as directory:
            value={'runtimeDir':directory,'userDataDir':directory}
            record={'chromePid':123,'chromeGeneration':'boot:123'}
            runtime.write_json(Path(directory)/'close-request.json',{'chromeGeneration':'boot:123'})
            with patch.object(runtime,'generation',return_value='boot:123'),patch.object(runtime,'profile_owner',return_value=123),patch.object(native.os,'kill',side_effect=AssertionError('No second signal')):
                with self.assertRaises(runtime.RuntimeErrorCode) as caught:native.quit_chrome(value,record,runtime)
                self.assertEqual(caught.exception.code,'CHROME_CLOSE_BLOCKED')

    def test_automatic_profile_metadata_without_last_used(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            runtime.write_json(root/'Local State',{'profile':{'last_active_profiles':['Profile 7'],'info_cache':{'Profile 7':{}}}})
            runtime.write_json(root/'Profile 7/Preferences',{'profile':{'exit_type':'Normal'}})
            self.assertTrue(native.clean_exit({'userDataDir':directory},runtime))
            runtime.write_json(root/'Profile 7/Preferences',{'profile':{'exit_type':'Crashed'}})
            self.assertFalse(native.clean_exit({'userDataDir':directory},runtime))

    def test_restore_launch_does_not_open_an_extra_blank_tab(self):
        command=runtime.chrome_command({'userDataDir':'/synthetic/data','profileDirectory':None},restore=True)
        self.assertIn('--restore-last-session',command)
        self.assertNotIn('about:blank',command)
        self.assertFalse(any(arg.startswith('--profile-directory') for arg in command))

    def test_switch_marker_blocks_unrelated_start(self):
        with tempfile.TemporaryDirectory() as directory:
            value={'sessionId':'test','runtimeDir':directory,'userDataDir':directory}
            runtime.write_json(Path(directory)/'native-switch.json',{'pid':os.getpid(),'processGeneration':runtime.generation(os.getpid())})
            with patch.object(runtime.subprocess,'Popen',side_effect=AssertionError('Must not start')):
                self.assertEqual(runtime.start(value,Path(directory)/'config.json')['state'],'switching-native')


class NativeFocusTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.value={'runtimeDir':self.directory.name,'userDataDir':self.directory.name}
        self.record={'pid':101,'processGeneration':'boot:101','chromePid':202,
            'chromeGeneration':'boot:202','runtimeGeneration':'native-generation',
            'configFingerprint':runtime.fingerprint(self.value),'state':'ready'}
        runtime.write_json(Path(self.directory.name)/'native.json',self.record)
        self.client={'pid':202,'address':'0x1234abcd','mapped':True,'hidden':False}
        self.calls=[]
        self.clock=0
        for item in [patch.dict(native.os.environ,{'XDG_CURRENT_DESKTOP':'Hyprland'},clear=True),
                patch.object(runtime,'generation',side_effect=lambda pid:{101:'boot:101',202:'boot:202'}.get(pid)),
                patch.object(runtime,'profile_owner',return_value=202),
                patch.object(native.time,'monotonic',side_effect=lambda:self.clock),
                patch.object(native.time,'sleep',side_effect=self.advance)]:
            item.start();self.addCleanup(item.stop)

    def advance(self,seconds):self.clock+=seconds

    def control(self,arguments,**kwargs):
        self.calls.append(arguments)
        self.assertGreater(kwargs['timeout'],0);self.assertLessEqual(kwargs['timeout'],1)
        if arguments==['hyprctl','--help']:output='    eval <code> Execute Lua\n    dispatch <dispatcher>\n'
        elif arguments==['hyprctl','-j','clients']:output=json.dumps([self.client])
        elif arguments==['hyprctl','-j','activewindow']:output=json.dumps(self.client)
        else:output='ok'
        return subprocess.CompletedProcess(arguments,0,stdout=output,stderr='')

    def test_owned_mapped_chrome_is_focused_with_current_lua_api(self):
        with patch.object(native.subprocess,'run',side_effect=self.control):native.focus_native(self.value,runtime,'native-generation')
        self.assertIn(['hyprctl','eval','hl.dispatch(hl.dsp.focus({window="address:0x1234abcd"}))'],self.calls)
        self.assertEqual(self.calls[-1],['hyprctl','-j','activewindow'])

    def test_explicit_legacy_capability_uses_legacy_dispatch(self):
        def legacy(arguments,**kwargs):
            if arguments==['hyprctl','--help']:
                self.calls.append(arguments)
                return subprocess.CompletedProcess(arguments,0,stdout='    dispatch <dispatcher>\n',stderr='')
            return self.control(arguments,**kwargs)
        with patch.object(native.subprocess,'run',side_effect=legacy):native.focus_native(self.value,runtime,'native-generation')
        self.assertIn(['hyprctl','dispatch','focuswindow','address:0x1234abcd'],self.calls)
        self.assertFalse(any(call[1]=='eval' for call in self.calls))

    def test_current_help_exit_one_with_recognized_usage_still_selects_lua(self):
        def control(arguments,**kwargs):
            if arguments==['hyprctl','--help']:
                return subprocess.CompletedProcess(arguments,1,
                    stdout='usage: hyprctl [flags] <command>\ncommands:\n    eval <code> Execute Lua\n    dispatch <dispatcher>\n',stderr='')
            return self.control(arguments,**kwargs)
        with patch.object(native.subprocess,'run',side_effect=control):native.focus_native(self.value,runtime,'native-generation')
        self.assertTrue(any(call[1]=='eval' for call in self.calls))

    def test_unrecognized_nonzero_help_response_is_rejected(self):
        for output in ['eval unavailable','    eval <code> Execute Lua\n','usage: hyprctl [flags]\nerror: compositor unavailable']:
            with self.subTest(output=output),patch.object(native.subprocess,'run',return_value=
                    subprocess.CompletedProcess(['hyprctl'],1,stdout=output,stderr='')):
                with self.assertRaises(runtime.RuntimeErrorCode) as caught:native.focus_native(self.value,runtime,'native-generation')
                self.assertEqual(caught.exception.code,'NATIVE_FOCUS_FAILED')

    def test_foreign_owner_or_stale_generation_never_issues_compositor_command(self):
        for attribute in ['profile_owner','generation']:
            with self.subTest(attribute=attribute),patch.object(runtime,attribute,return_value='unrelated'),\
                    patch.object(native.subprocess,'run',side_effect=AssertionError('Must not focus')):
                with self.assertRaises(runtime.RuntimeErrorCode) as caught:native.focus_native(self.value,runtime,'native-generation')
                self.assertEqual(caught.exception.code,'PROFILE_OWNER_MISMATCH')

    def test_changed_native_runtime_cannot_be_focused_by_stale_handoff(self):
        with patch.object(native.subprocess,'run',side_effect=AssertionError('Do not focus replacement')):
            with self.assertRaises(runtime.RuntimeErrorCode) as caught:
                native.focus_native(self.value,runtime,'previous-native-generation')
        self.assertEqual(caught.exception.code,'RUNTIME_CHANGED')

    def test_wrong_pid_unmapped_hidden_or_malformed_address_is_never_focused(self):
        for change in [{'pid':303},{'mapped':False},{'hidden':True},
                {'address':'0x1234"}); malicious()'},{'address':'0x'},{'address':'0x'+'a'*17}]:
            with self.subTest(change=change):
                self.clock=0;self.calls=[]
                self.client=dict(pid=202,address='0x1234abcd',mapped=True,hidden=False)
                self.client.update(change)
                with patch.object(native.subprocess,'run',side_effect=self.control):
                    with self.assertRaises(runtime.RuntimeErrorCode) as caught:native.focus_native(self.value,runtime,'native-generation',timeout=.3)
                self.assertEqual(caught.exception.code,'NATIVE_FOCUS_FAILED')
                self.assertFalse(any(call[1] in ('eval','dispatch') for call in self.calls))

    def test_missing_window_and_active_window_mismatch_expire_without_success(self):
        for mode in ['no-window','wrong-active-pid','wrong-active-address']:
            with self.subTest(mode=mode):
                self.clock=0
                def control(arguments,**kwargs):
                    if arguments==['hyprctl','-j','clients'] and mode=='no-window':
                        return subprocess.CompletedProcess(arguments,0,stdout='[]',stderr='')
                    if arguments==['hyprctl','-j','activewindow']:
                        active=dict(self.client)
                        active.update({'pid':303} if mode=='wrong-active-pid' else {'address':'0x9999'})
                        return subprocess.CompletedProcess(arguments,0,stdout=json.dumps(active),stderr='')
                    return self.control(arguments,**kwargs)
                with patch.object(native.subprocess,'run',side_effect=control):
                    with self.assertRaises(runtime.RuntimeErrorCode) as caught:native.focus_native(self.value,runtime,'native-generation',timeout=.3)
                self.assertEqual(caught.exception.code,'NATIVE_FOCUS_FAILED')
                self.assertGreaterEqual(self.clock,.3)

    def test_compositor_timeout_or_error_is_bounded_and_does_not_claim_success(self):
        for result in [subprocess.TimeoutExpired('hyprctl',1),FileNotFoundError(),
                subprocess.CompletedProcess(['hyprctl'],1,stdout='',stderr='error')]:
            with self.subTest(result=type(result).__name__),patch.object(native.subprocess,'run',side_effect=result if isinstance(result,Exception) else None,return_value=result):
                with self.assertRaises(runtime.RuntimeErrorCode) as caught:native.focus_native(self.value,runtime,'native-generation')
                self.assertEqual(caught.exception.code,'NATIVE_FOCUS_FAILED')

    def test_failed_lua_focus_does_not_fall_back_to_legacy_dispatch(self):
        def control(arguments,**kwargs):
            result=self.control(arguments,**kwargs)
            if arguments[1]=='eval':result.returncode=7
            return result
        with patch.object(native.subprocess,'run',side_effect=control):
            with self.assertRaises(runtime.RuntimeErrorCode) as caught:native.focus_native(self.value,runtime,'native-generation')
        self.assertEqual(caught.exception.code,'NATIVE_FOCUS_FAILED')
        self.assertFalse(any(call[1]=='dispatch' for call in self.calls))

    def test_lost_owner_before_focus_cannot_target_a_reused_pid(self):
        def control(arguments,**kwargs):
            result=self.control(arguments,**kwargs)
            if arguments==['hyprctl','-j','clients']:self.record['chromeGeneration']='replaced';runtime.write_json(Path(self.directory.name)/'native.json',self.record)
            return result
        with patch.object(native.subprocess,'run',side_effect=control):
            with self.assertRaises(runtime.RuntimeErrorCode) as caught:native.focus_native(self.value,runtime,'native-generation')
        self.assertEqual(caught.exception.code,'PROFILE_OWNER_MISMATCH')
        self.assertFalse(any(call[1] in ('eval','dispatch') for call in self.calls))

    def test_non_hyprland_desktop_uses_existing_activation_behavior(self):
        with patch.dict(native.os.environ,{'XDG_CURRENT_DESKTOP':'GNOME','DISPLAY':':95'},clear=True),\
                patch.object(native.subprocess,'run',side_effect=AssertionError('No Hyprland commands')):
            native.focus_native(self.value,runtime,'native-generation')

    def move_context(self,current):
        gtk=types.SimpleNamespace(init_check=lambda:(True,[]))
        gi=types.SimpleNamespace(require_version=lambda *_:None)
        stack=__import__('contextlib').ExitStack();self.addCleanup(stack.close)
        stack.enter_context(patch.dict('sys.modules',{'gi':gi,'gi.repository':types.SimpleNamespace(Gtk=gtk)}))
        stack.enter_context(patch.dict(native.os.environ,{'DISPLAY':':95'}))
        stack.enter_context(patch.object(runtime,'status',return_value=current))
        stack.enter_context(patch.object(native,'secret_preflight'))
        return stack

    def test_idempotent_native_handoff_focuses_before_acknowledgement(self):
        current={'state':'native','runtimeGeneration':'native-generation'}
        self.move_context(current)
        with patch.object(native,'focus_native') as focus,patch.object(native,'quit_chrome',side_effect=AssertionError('Do not close')):
            result=native.move(self.value,Path(self.directory.name)/'config.json','desktop','native-generation',runtime)
        focus.assert_called_once_with(self.value,runtime,'native-generation')
        self.assertTrue(result['connected']);self.assertEqual(result['session'],current)

    def test_failed_idempotent_focus_preserves_live_native_session(self):
        current={'state':'native','runtimeGeneration':'native-generation'}
        self.move_context(current)
        with patch.object(native,'focus_native',side_effect=runtime.RuntimeErrorCode('NATIVE_FOCUS_FAILED')),\
                patch.object(native,'quit_chrome',side_effect=AssertionError('Do not close')):
            result=native.move(self.value,Path(self.directory.name)/'config.json','desktop','native-generation',runtime)
        self.assertFalse(result['connected']);self.assertEqual(result['session'],current)

    def test_closed_native_browser_cannot_acknowledge_desktop_connection(self):
        current={'state':'native-closed','runtimeGeneration':'native-generation'}
        self.move_context(current)
        with patch.object(native,'focus_native',side_effect=AssertionError('No live native browser')),\
                patch.object(native,'quit_chrome',side_effect=AssertionError('Do not close')),\
                patch.object(native,'start_native',side_effect=AssertionError('Do not launch')):
            result=native.move(self.value,Path(self.directory.name)/'config.json','desktop','native-generation',runtime)
        self.assertFalse(result['connected']);self.assertEqual(result['code'],'BROWSER_NOT_READY')
        self.assertEqual(result['session'],current)

    def test_closed_native_browser_can_still_return_to_phone(self):
        current={'state':'native-closed','runtimeGeneration':'native-generation'}
        self.move_context(current)
        with patch.object(native,'quit_chrome',side_effect=AssertionError('Already closed')),\
                patch.object(native,'wait_supervisor'),patch.object(native,'clean_exit',return_value=True),\
                patch.object(runtime,'user_systemctl',return_value=Mock(returncode=0)),\
                patch.object(native,'start_virtual',return_value={'state':'ready','runtimeGeneration':'phone-generation'}) as start,\
                patch.object(native,'focus_native',side_effect=AssertionError('Phone does not need focus')):
            result=native.move(self.value,Path(self.directory.name)/'config.json','phone','native-generation',runtime)
        self.assertTrue(result['connected']);self.assertEqual(result['destination'],'phone')
        start.assert_called_once()

    def test_failed_new_native_focus_does_not_rollback_or_close_live_chrome(self):
        current={'state':'ready','runtimeGeneration':'phone-generation'}
        self.move_context(current)
        live={'state':'native','runtimeGeneration':'native-generation'}
        runtime.status.side_effect=[current,live]
        with patch.object(native,'quit_chrome') as close,patch.object(native,'wait_supervisor'),\
                patch.object(native,'clean_exit',return_value=True),patch.object(runtime,'user_systemctl',return_value=Mock(returncode=0)),\
                patch.object(native,'start_native',return_value={'state':'native','runtimeGeneration':'native-generation'}) as start,\
                patch.object(native,'focus_native',side_effect=runtime.RuntimeErrorCode('NATIVE_FOCUS_FAILED')),\
                patch.object(native,'start_virtual',side_effect=AssertionError('Do not rollback live Chrome')):
            result=native.move(self.value,Path(self.directory.name)/'config.json','desktop','phone-generation',runtime)
        self.assertFalse(result['connected']);self.assertEqual(result['code'],'NATIVE_FOCUS_FAILED')
        self.assertEqual(result['session'],live)
        close.assert_called_once();start.assert_called_once()

    def test_new_native_handoff_focuses_before_success(self):
        current={'state':'ready','runtimeGeneration':'phone-generation'}
        self.move_context(current);events=[]
        def start(*_):events.append('start');return {'state':'native','runtimeGeneration':'native-generation'}
        def focus(*_):events.append('focus')
        with patch.object(native,'quit_chrome'),patch.object(native,'wait_supervisor'),\
                patch.object(native,'clean_exit',return_value=True),patch.object(runtime,'user_systemctl',return_value=Mock(returncode=0)),\
                patch.object(native,'start_native',side_effect=start),patch.object(native,'focus_native',side_effect=focus):
            result=native.move(self.value,Path(self.directory.name)/'config.json','desktop','phone-generation',runtime)
        self.assertTrue(result['connected']);self.assertEqual(events,['start','focus'])

if __name__=='__main__':unittest.main()

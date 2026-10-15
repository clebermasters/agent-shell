#!/usr/bin/env python3
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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

if __name__=='__main__':unittest.main()

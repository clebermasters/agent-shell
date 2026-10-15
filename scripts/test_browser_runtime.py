#!/usr/bin/env python3
"""Regression checks for profile locks and PID generations; never starts Chrome."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

spec=importlib.util.spec_from_file_location('browser_runtime',Path(__file__).with_name('browser-runtime.py'))
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)

class BrowserRuntimeTests(unittest.TestCase):
    def test_default_profile_is_delegated_to_chrome_without_changing_local_state(self):
        with tempfile.TemporaryDirectory() as d:
            value=self.fixture(d);value.pop('profileDirectory')
            profile=Path(value['userDataDir']);profile.mkdir()
            state=profile/'Local State';state.write_text(json.dumps({'profile':{'last_used':'Profile 7'}}))
            before=state.read_bytes();path=Path(d)/'config.json';runtime.write_json(path,value)
            loaded=runtime.config(path)
            self.assertFalse(any(arg.startswith('--profile-directory=') for arg in runtime.chrome_command(loaded)))
            self.assertEqual(state.read_bytes(),before)
            value['profileDirectory']=None;runtime.write_json(path,value)
            self.assertEqual(runtime.fingerprint(runtime.config(path)),runtime.fingerprint(loaded))

    def test_explicit_profile_remains_supported_and_invalid_values_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            value=self.fixture(d);value['profileDirectory']='Profile 7'
            path=Path(d)/'config.json';runtime.write_json(path,value)
            self.assertIn('--profile-directory=Profile 7',runtime.chrome_command(runtime.config(path)))
            for invalid in ['', '../elsewhere', 7, {}]:
                value['profileDirectory']=invalid;runtime.write_json(path,value)
                with self.assertRaises(runtime.RuntimeErrorCode):runtime.config(path)

    def fixture(self,directory):
        root=Path(directory)
        return {'sessionId':str(uuid.uuid4()),'userDataDir':str(root/'profile'),'profileDirectory':None,
            'runtimeDir':str(root/'runtime'),'display':94,'port':19002,'password':'fixture-password-not-live','supervision':'process'}
    def test_live_profile_owner_blocks_start_without_launching_a_process(self):
        with tempfile.TemporaryDirectory() as d:
            value=self.fixture(d);profile=Path(value['userDataDir']);profile.mkdir()
            (profile/'SingletonLock').symlink_to(socket.gethostname()+'-'+str(os.getpid()))
            with patch.object(runtime.subprocess,'Popen',side_effect=AssertionError('Must not launch')):
                self.assertEqual(runtime.start(value,Path(d)/'config.json')['code'],'PROFILE_IN_USE')
    def test_unknown_host_lock_is_not_deleted_or_claimed(self):
        with tempfile.TemporaryDirectory() as d:
            value=self.fixture(d);profile=Path(value['userDataDir']);profile.mkdir()
            lock=profile/'SingletonLock';lock.symlink_to('another-computer-123')
            self.assertEqual(runtime.status(value)['state'],'profile-in-use')
            self.assertTrue(lock.is_symlink())
    def test_stale_pid_generation_does_not_stop_an_unrelated_process(self):
        with tempfile.TemporaryDirectory() as d:
            value=self.fixture(d);p=Path(value['runtimeDir'])/'runtime.json'
            runtime.write_json(p,{'pid':os.getpid(),'processGeneration':'previous-boot:1','state':'ready'})
            with patch.object(runtime.os,'kill',side_effect=AssertionError('Must not signal')):
                self.assertEqual(runtime.stop(value)['state'],'stopped')
    def test_missing_chrome_identity_cannot_report_ready(self):
        with tempfile.TemporaryDirectory() as d:
            value=self.fixture(d)
            runtime.write_json(Path(value['runtimeDir'])/'runtime.json',{'pid':os.getpid(),
                'processGeneration':runtime.generation(os.getpid()),'state':'ready','runtimeGeneration':str(uuid.uuid4()),'configFingerprint':runtime.fingerprint(value)})
            self.assertEqual(runtime.status(value)['code'],'CHROME_EXITED')
    def test_configuration_change_cannot_reuse_an_old_runtime(self):
        with tempfile.TemporaryDirectory() as d:
            value=self.fixture(d)
            runtime.write_json(Path(value['runtimeDir'])/'runtime.json',{'pid':os.getpid(),
                'processGeneration':runtime.generation(os.getpid()),'state':'ready','configFingerprint':runtime.fingerprint(value)})
            value['password']='a-new-private-password'
            with patch.object(runtime.subprocess,'Popen',side_effect=AssertionError('Must not launch')):
                self.assertEqual(runtime.start(value,Path(d)/'config.json')['code'],'RUNTIME_CONFIGURATION_CHANGED')
    def test_durable_record_is_private_and_replaced_atomically(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'runtime.json';runtime.write_json(p,{'generation':'one'});runtime.write_json(p,{'generation':'two'})
            self.assertEqual(json.loads(p.read_text()),{'generation':'two'})
            self.assertEqual(p.stat().st_mode&0o777,0o600)
            self.assertEqual(list(Path(d).glob('.runtime.json.*')),[])
    def test_credential_bus_only_exposes_original_secret_service(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);env={'DBUS_SESSION_BUS_ADDRESS':'unix:path=/fixture-original-bus'};children=[]
            proxy=Mock();proxy.poll.return_value=None
            def launch(arguments, **_):
                (root/'credential-bus.sock').touch()
                self.assertEqual(arguments,['xdg-dbus-proxy','unix:path=/fixture-original-bus',
                    str(root/'credential-bus.sock'),'--filter','--talk=org.freedesktop.secrets'])
                return proxy
            with patch.object(runtime.shutil,'which',return_value='/bin/xdg-dbus-proxy'), patch.object(runtime.subprocess,'Popen',side_effect=launch):
                runtime.credential_bus(env,root,children,None)
            self.assertEqual(env['DBUS_SESSION_BUS_ADDRESS'],'unix:path='+str(root/'credential-bus.sock'))
            self.assertEqual(env['GTK_USE_PORTAL'],'0')
            self.assertEqual(children,[proxy])
            self.assertEqual((root/'credential-bus.sock').stat().st_mode&0o777,0o600)
    def test_failed_credential_bus_cannot_launch_chrome(self):
        with tempfile.TemporaryDirectory() as d:
            proxy=Mock();proxy.poll.return_value=1;children=[]
            with patch.object(runtime.shutil,'which',return_value='/bin/xdg-dbus-proxy'), patch.object(runtime.subprocess,'Popen',return_value=proxy):
                with self.assertRaises(runtime.RuntimeErrorCode) as caught:
                    runtime.credential_bus({},Path(d),children,None)
            self.assertEqual(caught.exception.code,'CREDENTIAL_BUS_START_FAILED')
            self.assertEqual(children,[proxy])
    def test_port_probe_rejects_live_listener_but_allows_recently_closed_connection(self):
        with socket.socket() as listener, socket.socket() as client:
            listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            listener.bind(('127.0.0.1',0));port=listener.getsockname()[1];listener.listen()
            client.connect(('127.0.0.1',port));peer,_=listener.accept()
            with self.assertRaises(runtime.RuntimeErrorCode) as caught: runtime.check_port(port)
            self.assertEqual(caught.exception.code,'PORT_IN_USE')
            peer.close();client.recv(1);client.close();listener.close()
            runtime.check_port(port)
    def test_previous_failure_does_not_abort_a_new_systemd_launch(self):
        with tempfile.TemporaryDirectory() as d:
            value=self.fixture(d);value['supervision']='systemd-user'
            old={'state':'stopped','code':'PORT_IN_USE'};ready={'state':'ready'}
            record={'state':'stopped','code':'PORT_IN_USE','runtimeGeneration':'old'}
            with patch.object(runtime,'status',side_effect=[old,old,old,ready]), patch.object(runtime,'read_json',return_value=record), patch.object(runtime,'user_systemctl',return_value=Mock(returncode=0)), patch.object(runtime.time,'sleep'):
                self.assertEqual(runtime.start(value,Path(d)/'config.json'),ready)

if __name__=='__main__':unittest.main()

#!/usr/bin/env python3
"""Validate LAN DNS isolation, private addresses, and renewal deployment boundaries."""
import importlib.util
import json
import os
from pathlib import Path
import pwd
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('lan_https', Path(__file__).with_name('lan-https.py'))
lan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lan)


class LanHttpsTest(unittest.TestCase):
    def value(self):
        return dict(hostname='lan.example.test',address='192.168.0.76',interface=None,zoneId='a'*32,
                    credentials='/private/credentials',serviceUser=pwd.getpwuid(os.getuid()).pw_name,httpsPort=4443)

    def test_private_ip_and_explicit_hostname_validation(self):
        self.assertEqual(lan.validate(self.value())['hostname'],'lan.example.test')
        for address in ['127.0.0.1','169.254.1.1','8.8.8.8','::1']:
            with self.assertRaises(ValueError):lan.private_address(address)
        for hostname in ['https://lan.example.test','../other','localhost','a.example.test;anything']:
            with self.assertRaises(ValueError):lan.validate(dict(self.value(),hostname=hostname))

    def test_sync_creates_only_the_exact_dns_only_record_and_unchanged_check_is_local(self):
        calls=[]
        def api(value,method,path,body=None):
            calls.append((method,path,body));return [] if method=='GET' else {'id':'fixture'}
        with tempfile.TemporaryDirectory() as directory,patch.object(lan,'cloudflare',api),patch.object(lan,'atomic',lambda p,v,*a,**k:Path(p).write_text(v)):
            state=Path(directory)/'state.json'
            lan.sync_dns(self.value(),state)
            self.assertEqual([c[0] for c in calls],['GET','POST'])
            self.assertEqual(calls[1][2]['content'],'192.168.0.76')
            self.assertFalse(calls[1][2]['proxied'])
            lan.sync_dns(self.value(),state)
            self.assertEqual(len(calls),2)

    def test_address_change_updates_existing_record_without_deleting_others(self):
        calls=[]
        def api(value,method,path,body=None):
            calls.append((method,path,body));return [{'id':'owned-record','type':'A','content':'192.168.0.10','proxied':False,'ttl':60}]
        with tempfile.TemporaryDirectory() as directory,patch.object(lan,'cloudflare',api),patch.object(lan,'atomic',lambda p,v,*a,**k:Path(p).write_text(v)):
            lan.sync_dns(self.value(),Path(directory)/'state.json')
        self.assertEqual(calls[1][0:2],('PUT','/dns_records/owned-record'))
        self.assertEqual(calls[1][2]['content'],'192.168.0.76')

    def test_conflicting_dns_records_are_rejected_without_mutation(self):
        calls=[]
        def api(value,method,path,body=None):
            calls.append(method);return [{'id':'unrelated','type':'CNAME'}]
        with tempfile.TemporaryDirectory() as directory,patch.object(lan,'cloudflare',api):
            with self.assertRaises(ValueError):lan.sync_dns(self.value(),Path(directory)/'state.json')
        self.assertEqual(calls,['GET'])

    def test_unrelated_certificate_renewal_cannot_replace_the_lan_certificate(self):
        with patch.dict(os.environ,{'RENEWED_LINEAGE':'/etc/letsencrypt/live/unrelated.example.test'}),patch.object(lan.subprocess,'run') as run:
            self.assertFalse(lan.deploy(self.value()))
            run.assert_not_called()

    def test_interface_address_is_read_without_following_default_vpn_route(self):
        output=json.dumps([{'addr_info':[{'scope':'global','local':'192.168.0.77'}]}])
        with patch.object(lan.subprocess,'run',return_value=subprocess.CompletedProcess([],0,stdout=output)) as run:
            self.assertEqual(lan.address_for(dict(self.value(),interface='wlo1')),'192.168.0.77')
            self.assertEqual(run.call_args.args[0][-2:],['dev','wlo1'])


if __name__=='__main__':unittest.main()

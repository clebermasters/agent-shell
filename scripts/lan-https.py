#!/usr/bin/env python3
"""Provision/renew trusted LAN HTTPS and maintain one DNS-only Cloudflare record.

Installed root-owned by install.sh. Credentials and private keys stay outside Git.
"""
import argparse
import configparser
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import pwd
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


def validate(value):
    hostname = value['hostname']
    if len(hostname) > 253 or not re.fullmatch(r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}', hostname):
        raise ValueError('LAN hostname must be a fully qualified DNS name')
    private_address(value['address'])
    if not isinstance(value.get('httpsPort'), int) or not 1 <= value['httpsPort'] <= 65535:
        raise ValueError('Invalid HTTPS port')
    if value.get('interface') and not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.:-]{0,14}', value['interface']):
        raise ValueError('Invalid LAN network interface')
    if not isinstance(value.get('zoneId'), str) or not re.fullmatch(r'[a-f0-9]{32}', value['zoneId']):
        raise ValueError('Invalid Cloudflare zone ID')
    pwd.getpwnam(value['serviceUser'])
    return value


def private_address(value):
    address = ipaddress.IPv4Address(value)
    if not any(address in ipaddress.IPv4Network(net) for net in ['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16']):
        raise ValueError('The LAN address must be a private IPv4 address')
    return str(address)


def address_for(value):
    if not value.get('interface'):
        return private_address(value['address'])
    result = subprocess.run(['ip', '-j', '-4', 'addr', 'show', 'dev', value['interface']], capture_output=True, text=True, check=True)
    addresses = [item['local'] for link in json.loads(result.stdout) for item in link.get('addr_info', []) if item.get('scope') == 'global']
    for address in addresses:
        try:
            return private_address(address)
        except ValueError:
            pass
    raise ValueError('The configured interface has no private LAN IPv4 address')


def atomic(path, data, mode=0o600, gid=0):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        os.fchown(fd, 0, gid)
        with os.fdopen(fd, 'wb') as output:
            output.write(data if isinstance(data, bytes) else data.encode())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def cloudflare(value, method, path, body=None):
    credentials = configparser.ConfigParser(interpolation=None)
    credentials.read_string('[credentials]\n' + Path(value['credentials']).read_text())
    auth = credentials['credentials']
    headers = {'Content-Type': 'application/json'}
    if auth.get('dns_cloudflare_api_token'):
        headers['Authorization'] = 'Bearer ' + auth['dns_cloudflare_api_token']
    else:
        headers.update({'X-Auth-Email': auth['dns_cloudflare_email'], 'X-Auth-Key': auth['dns_cloudflare_api_key']})
    request = urllib.request.Request('https://api.cloudflare.com/client/v4/zones/' + value['zoneId'] + path,
        data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        raise ValueError('Cloudflare request failed (HTTP ' + str(error.code) + ')') from None
    if not result.get('success'):
        raise ValueError('Cloudflare rejected the LAN DNS request')
    return result['result']


def sync_dns(value, state_path=Path('/var/lib/agentshell-lan/dns-state.json'), force=False):
    address = address_for(value)
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    if not force and state.get('hostname') == value['hostname'] and state.get('address') == address and time.time() - state.get('checkedAt', 0) < 3600:
        return address
    records = cloudflare(value, 'GET', '/dns_records?' + urllib.parse.urlencode({'name': value['hostname']}))
    if any(record['type'] != 'A' for record in records) or len(records) > 1:
        raise ValueError('The LAN hostname has conflicting DNS records; no records were changed')
    body = {'type': 'A', 'name': value['hostname'], 'content': address, 'ttl': 60, 'proxied': False,
            'comment': 'AgentShell direct LAN HTTPS; managed by the local installer'}
    if records:
        record = records[0]
        if record.get('content') != address or record.get('proxied') or record.get('ttl') != 60:
            cloudflare(value, 'PUT', '/dns_records/' + record['id'], body)
    else:
        cloudflare(value, 'POST', '/dns_records', body)
    atomic(state_path, json.dumps({'hostname': value['hostname'], 'address': address, 'checkedAt': time.time()}))
    return address


def deploy(value, reload_service=True, lineage_root=Path('/etc/letsencrypt/live'), tls_root=Path('/etc/agentshell/lan-tls')):
    lineage = lineage_root / value['hostname']
    renewed = os.environ.get('RENEWED_LINEAGE')
    if renewed and Path(renewed).resolve() != lineage.resolve():
        return False
    subprocess.run(['openssl', 'x509', '-in', str(lineage / 'cert.pem'), '-noout', '-checkhost', value['hostname'], '-checkend', '604800'], check=True, capture_output=True)
    certificate = subprocess.run(['openssl', 'x509', '-in', str(lineage / 'cert.pem'), '-pubkey', '-noout'], check=True, capture_output=True).stdout
    key = subprocess.run(['openssl', 'pkey', '-in', str(lineage / 'privkey.pem'), '-pubout'], check=True, capture_output=True).stdout
    if certificate != key:
        raise ValueError('Issued certificate and private key do not match')
    subprocess.run(['openssl', 'verify', '-CApath', '/etc/ssl/certs', '-untrusted', str(lineage / 'chain.pem'), str(lineage / 'cert.pem')], check=True, capture_output=True)
    target = tls_root / value['hostname']
    target.mkdir(parents=True, exist_ok=True)
    gid = pwd.getpwnam(value['serviceUser']).pw_gid
    os.chown(target, 0, gid)
    os.chmod(target, 0o750)
    chain, secret = (lineage / 'fullchain.pem').read_bytes(), (lineage / 'privkey.pem').read_bytes()
    if (target / 'cert.pem').exists() and (target / 'key.pem').exists() and (target / 'cert.pem').read_bytes() == chain and (target / 'key.pem').read_bytes() == secret:
        return False
    atomic(target / 'key.pem', secret, 0o640, gid)
    atomic(target / 'cert.pem', chain, 0o644, gid)
    if reload_service and subprocess.run(['systemctl', 'is-active', '--quiet', 'agentshell.service']).returncode == 0:
        subprocess.run(['systemctl', 'reload', 'agentshell.service'], check=True, capture_output=True)
    return True


def provision(value):
    zone = cloudflare(value, 'GET', '')['name']
    if value['hostname'] == zone or not value['hostname'].endswith('.' + zone):
        raise ValueError('The LAN hostname must be a subdomain of the configured Cloudflare zone')
    # DNS-01 validation needs no Internet-facing web server and opens no ports.
    command = ['certbot', 'certonly', '--non-interactive', '--agree-tos', '--no-eff-email',
        '--dns-cloudflare', '--dns-cloudflare-credentials', value['credentials'], '--dns-cloudflare-propagation-seconds', '30',
        '--cert-name', value['hostname'], '-d', value['hostname'], '--keep-until-expiring', '--no-directory-hooks']
    if value.get('email'):
        command.extend(['--email', value['email']])
    else:
        command.append('--register-unsafely-without-email')
    result = subprocess.run(command, capture_output=True)
    log = Path('/var/log/agentshell-lan-https')
    log.mkdir(mode=0o700, parents=True, exist_ok=True)
    atomic(log / 'certbot.log', result.stdout + result.stderr)
    if result.returncode:
        raise ValueError('Certificate issuance failed; details are in the root-only LAN HTTPS log')
    deploy(value, reload_service=False)
    address = sync_dns(value, force=True)
    print(json.dumps({'ready': True, 'hostname': value['hostname'], 'address': address, 'httpsPort': value['httpsPort']}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['provision', 'sync', 'deploy'])
    parser.add_argument('--config', type=Path, default=Path('/etc/agentshell/lan-https.json'))
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.exit(1, 'LAN HTTPS maintenance must run as root.\n')
    try:
        value = validate(json.loads(args.config.read_text()))
        if args.command == 'provision':
            provision(value)
        elif args.command == 'sync':
            sync_dns(value)
        else:
            deploy(value)
    except Exception as error:
        # Do not emit exception bodies from HTTP/Certbot; they can contain credentials.
        parser.exit(1, 'LAN HTTPS ' + args.command + ' failed (' + type(error).__name__ + '). See the private provisioning log.\n')


if __name__ == '__main__':
    main()

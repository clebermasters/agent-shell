#!/usr/bin/python3
"""Desktop viewer for the existing managed Chrome; never restarts/moves Chrome.

The API token stays in Python. WebKit receives only an ephemeral, scoped cookie.
Local commands use a same-user Unix socket, not an unauthenticated HTTP listener.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import fcntl
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import socket
import ssl
import stat
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

MESSAGES = {
    'CONNECTED': 'Chrome is open on this computer.',
    'PHONE': 'Ready on your phone. Open Browser in AgentShell; reconnect if it was already open.',
    'CLOSED': 'Desktop viewer closed. Chrome keeps running.',
    'NOT_RUNNING': 'The desktop viewer is closed. Run agentshell-browser open.',
    'CONTROLLER_IN_USE': 'Chrome is in use by another viewer. Choose Return to computer to take control.',
    'CERTIFICATE_ERROR': 'The HTTPS certificate could not be verified. Check the configured address and trust store.',
    'CONNECTION_FAILED': 'Unable to reach the browser service. Check the daemon and its HTTPS address.',
    'BROWSER_NOT_CONFIGURED': 'Install and configure Chrome streaming with install.sh --with-browser kasmvnc.',
    'HANDOFF_NOT_SUPPORTED': 'Update the backend and desktop viewer together using install.sh.',
    'PROFILE_IN_USE': 'Another Chrome owns the configured profile. Close it normally before starting the managed browser.',
    'CREDENTIAL_STORE_UNAVAILABLE': 'Unlock the computer session and its credential store, then retry.',
    'NO_DESKTOP': 'Run this command from your logged-in graphical desktop.',
    'INVALID_CONFIG': 'The private desktop-viewer configuration is missing or invalid. Re-run the browser installer.',
    'VIEWER_UNAVAILABLE': 'The desktop viewer could not start. Check the GTK/WebKit dependencies.',
    'VIEWER_TIMEOUT': 'The viewer did not connect. Check the browser service and reconnect.',
    'VIEWER_AUTHORIZATION_REQUIRED': 'Viewer access expired. Reconnect to obtain fresh access.',
    'RUNTIME_CHANGED': 'The managed Chrome session changed. Reconnect.',
    'RUNTIME_CONFIGURATION_CHANGED': 'The running browser uses different settings. Check the managed service.',
    'BROWSER_NOT_READY': 'The managed browser is not ready. Check the browser service.',
    'DESKTOP_UNAVAILABLE': 'The computer has no available desktop session. Keep using the phone and sign in on the computer.',
    'HANDOFF_EXPIRED': 'This transfer was cancelled. Keep using the current viewer.',
    'HANDOFF_SUPERSEDED': 'Another action already changed browser control.',
}


class Failure(Exception):
    def __init__(self, code):
        self.code = code if code in MESSAGES else 'CONNECTION_FAILED'
        super().__init__(self.code)


def private_file(path):
    """Reject shared, foreign-owned and linked credential/configuration files."""
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise Failure('INVALID_CONFIG')
    if info.st_size > 65536:
        raise Failure('INVALID_CONFIG')
    return path.read_text()


def private_directory(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise Failure('INVALID_CONFIG')


class Config:
    def __init__(self, path):
        try:
            self.path = path.absolute()
            value = json.loads(private_file(self.path))
            self.origin = value['origin']
            url = urllib.parse.urlsplit(self.origin)
            if (url.scheme != 'https' or not url.hostname or url.username or url.password or
                    url.path or url.query or url.fragment or self.origin.endswith('/') or
                    not 1 <= (url.port or 443) <= 65535):
                raise ValueError()
            self.host, self.port = url.hostname, url.port or 443
            self.runtime_env = Path(value['runtimeEnv'])
            self.state = Path(value['stateDir'])
            if not self.runtime_env.is_absolute() or not self.state.is_absolute():
                raise ValueError()
            private_directory(self.state)
            self.socket = self.state / 'viewer.sock'
            if len(os.fsencode(self.socket)) > 100:
                raise ValueError()
            self.ca = value.get('caFile')
            self.tls = ssl.create_default_context(cafile=self.ca)
        except (KeyError, ValueError, OSError, TypeError):
            raise Failure('INVALID_CONFIG') from None

    def token(self):
        # Literal dotenv parser; never source a shell or log its contents.
        for line in private_file(self.runtime_env).splitlines():
            name, separator, value = line.strip().removeprefix('export ').partition('=')
            if separator and name == 'AUTH_TOKEN':
                value = value.strip()
                if value.startswith('"'):
                    value = json.loads(value)
                elif value.startswith("'") and value.endswith("'"):
                    value = value[1:-1]
                if not isinstance(value, str) or not value or '\n' in value or '\r' in value:
                    break
                return value
        raise Failure('INVALID_CONFIG')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class Api:
    def __init__(self, config):
        self.config = config
        self.deadline = None
        # Never follow an API redirect with the administrative header attached.
        self.opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=config.tls))

    def timeout(self, limit=25):
        remaining = limit if self.deadline is None else min(limit, self.deadline - time.monotonic())
        if remaining <= 0:
            raise Failure('CONNECTION_FAILED')
        return remaining

    def request(self, path, body=None, bootstrap=False):
        headers = {}
        if bootstrap:
            data = urllib.parse.urlencode(body).encode()
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        else:
            headers['X-Auth-Token'] = self.config.token()
            data = None if body is None else json.dumps(body).encode()
            if data is not None:
                headers['Content-Type'] = 'application/json'
        try:
            request = urllib.request.Request(self.config.origin + path, data=data, headers=headers)
            try:
                response = self.opener.open(request, timeout=self.timeout())
            except urllib.error.HTTPError as error:
                response = error
            with response:
                payload = response.read(65537)
                if len(payload) > 65536:
                    raise Failure('CONNECTION_FAILED')
                if bootstrap and response.status == 303:
                    return response.headers
                value = json.loads(payload)
                if response.status != 200:
                    raise Failure(value.get('code', 'CONNECTION_FAILED'))
                return value
        except Failure:
            raise
        except urllib.error.URLError as error:
            raise Failure('CERTIFICATE_ERROR' if isinstance(error.reason, ssl.SSLError) else 'CONNECTION_FAILED') from None
        except (OSError, ValueError, TypeError):
            raise Failure('CONNECTION_FAILED') from None

    def session(self, start=False):
        capabilities = self.request('/api/browser/capabilities')
        if not capabilities.get('available'):
            raise Failure('BROWSER_NOT_CONFIGURED')
        if capabilities.get('viewerOrigin') != self.config.origin or not capabilities.get('desktopHandoff'):
            raise Failure('HANDOFF_NOT_SUPPORTED')
        session = capabilities['session']
        if session.get('state') != 'ready' and start:
            session = self.request('/api/browser/sessions', {})
        if session.get('state') != 'ready':
            raise Failure(session.get('code') or 'BROWSER_NOT_READY')
        try:
            sid = str(uuid.UUID(session['sessionId']))
            generation = session['runtimeGeneration']
            if not isinstance(generation, str) or not 1 <= len(generation) <= 256:
                raise ValueError()
            return sid, generation
        except (ValueError, KeyError, TypeError):
            raise Failure('CONNECTION_FAILED') from None

    def release(self, viewer, sid, generation):
        self.request('/api/browser/sessions/' + sid + '/release-viewer',
                     {'viewerId': viewer, 'runtimeGeneration': generation})

    def launch(self, viewer, sid, generation, takeover, handoff=None):
        body = {'viewerId': viewer, 'runtimeGeneration': generation, 'takeControl': takeover}
        if handoff:
            body['handoffId'] = handoff
        grant = self.request('/api/browser/sessions/' + sid + '/viewer-grants', body)['grant']
        headers = self.request('/browser/' + sid + '/bootstrap', {'grant': grant}, bootstrap=True)
        location = headers.get('Location', '')
        parsed = urllib.parse.urlsplit(location)
        if parsed.scheme or parsed.netloc or parsed.fragment or parsed.path != '/browser/' + sid + '/view/vnc.html':
            raise Failure('VIEWER_AUTHORIZATION_REQUIRED')
        cookie = SimpleCookie()
        cookie.load(headers.get('Set-Cookie', ''))
        entry = cookie.get('agentshell_browser')
        if (len(cookie) != 1 or entry is None or not re.fullmatch('[0-9a-f]{64}', entry.value) or
                not entry['secure'] or not entry['httponly'] or entry['path'] != '/browser/' + sid + '/' or
                entry['samesite'].lower() != 'strict' or entry['domain']):
            raise Failure('VIEWER_AUTHORIZATION_REQUIRED')
        # An explicitly configured CA is validated with both chain and hostname
        # checks. WebKit gets only that verified peer certificate for this host.
        certificate = None
        if self.config.ca:
            with socket.create_connection((self.config.host, self.config.port), timeout=self.timeout(10)) as raw:
                with self.config.tls.wrap_socket(raw, server_hostname=self.config.host) as tls:
                    certificate = ssl.DER_cert_to_PEM_cert(tls.getpeercert(binary_form=True))
        return self.config.origin + location, headers['Set-Cookie'], certificate


def viewer_id(config):
    path = config.state / 'viewer-id'
    with (config.state / 'identity.lock').open('a') as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        if path.exists():
            return str(uuid.UUID(private_file(path).strip()))
        value = str(uuid.uuid4())
        with path.open('x') as output:
            os.chmod(path, 0o600)
            output.write(value + '\n')
        return value


def receive(sock):
    data = bytearray()
    while len(data) < 4096:
        chunk = sock.recv(4096 - len(data))
        if not chunk:
            break
        data.extend(chunk)
        if b'\n' in data:
            return json.loads(data.split(b'\n', 1)[0])
    raise Failure('VIEWER_UNAVAILABLE')


def call(config, command, handoff=None, generation=None):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(55)
        client.connect(str(config.socket))
        client.sendall(json.dumps({'command': command, 'handoffId': handoff, 'generation': generation}).encode() + b'\n')
        return receive(client)


def serve(config):
    if not (os.environ.get('DISPLAY') or os.environ.get('WAYLAND_DISPLAY')):
        raise Failure('NO_DESKTOP')
    lock = (config.state / 'viewer.lock').open('a')
    os.chmod(lock.name, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return
    import gi
    gi.require_version('Gtk', '3.0')
    gi.require_version('WebKit2', '4.0')
    gi.require_version('Soup', '2.4')
    from gi.repository import Gtk, WebKit2, Soup, GLib, Gio
    if not Gtk.init_check()[0]:
        raise Failure('NO_DESKTOP')
    api = Api(config)
    identity = viewer_id(config)
    executor = ThreadPoolExecutor(max_workers=1)
    connected = threading.Event()
    paused = threading.Event()
    paused.set()
    current = {'code': 'NOT_RUNNING', 'pid': os.getpid(), 'connected': False, 'connectionCount': 0, 'protocolVersion': 2}
    session = [None, None]
    quitting = threading.Event()
    manager = WebKit2.UserContentManager()
    manager.register_script_message_handler('state')
    manager.add_script(WebKit2.UserScript.new('''window.BrowserHost={postMessage:value=>window.webkit.messageHandlers.state.postMessage(value)};
window.AgentShellViewportOptions={adaptive:true,fullscreen:true,keyboardVisible:false};''',
        WebKit2.UserContentInjectedFrames.TOP_FRAME, WebKit2.UserScriptInjectionTime.START, None, None))
    context = WebKit2.WebContext.new_ephemeral()
    context.set_cache_model(WebKit2.CacheModel.DOCUMENT_VIEWER)
    view = WebKit2.WebView(web_context=context, user_content_manager=manager)
    view.connect('size-allocate', lambda _, allocation: current.update(viewport=[allocation.width, allocation.height]))
    window = Gtk.Window(title='AgentShell Chrome')
    window.set_default_size(1280, 850)
    window.set_icon_name('google-chrome')
    window.set_wmclass('agentshell-browser', 'AgentShellBrowser')
    layout = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
    bar = Gtk.Box(spacing=10)
    bar.set_border_width(8)
    label = Gtk.Label(label='Connect to continue your Chrome session.')
    label.set_line_wrap(True)
    bar.pack_start(label, True, True, 0)
    layout.pack_start(bar, False, False, 0)
    layout.pack_start(view, True, True, 0)
    window.add(layout)

    def ui(function, wait=False):
        done = threading.Event()
        def run():
            try:
                function()
            finally:
                done.set()
            return False
        GLib.idle_add(run)
        if wait and not done.wait(3):
            raise Failure('VIEWER_UNAVAILABLE')

    def display(code):
        current['code'] = code
        ui(lambda: label.set_text(MESSAGES.get(code, MESSAGES['CONNECTION_FAILED'])))

    def message(_, result):
        try:
            event = json.loads(result.get_js_value().to_string())
            if event.get('type') != 'connection':
                return
            is_connected = event.get('state') == 'connected'
            if is_connected and not paused.is_set() and not current['connected']:
                current['connectionCount'] += 1
            current['connected'] = is_connected and not paused.is_set()
            if is_connected and not paused.is_set():
                display('CONNECTED')
                connected.set()
            elif not is_connected:
                connected.clear()
                if not paused.is_set() and event.get('state') == 'disconnected':
                    display('CONNECTION_FAILED')
                    # Reconnect once after a transport/daemon restart. A handoff
                    # returns CONTROLLER_IN_USE; never take control implicitly.
                    GLib.timeout_add(2000, lambda: (submit('reconnect') if not paused.is_set() else None, False)[1])
        except (ValueError, TypeError):
            pass
    manager.connect('script-message-received::state', message)

    def decide_policy(_, decision, kind):
        if kind in (WebKit2.PolicyDecisionType.NAVIGATION_ACTION, WebKit2.PolicyDecisionType.NEW_WINDOW_ACTION):
            uri = decision.get_navigation_action().get_request().get_uri()
            sid = session[0]
            if kind == WebKit2.PolicyDecisionType.NEW_WINDOW_ACTION or not sid or not uri.startswith(config.origin + '/browser/' + sid + '/view/'):
                decision.ignore()
                return True
        return False
    view.connect('decide-policy', decide_policy)
    view.connect('permission-request', lambda _, request: (request.deny(), True)[1])
    def renderer_ended(*_):
        paused.set()
        connected.clear()
        current['connected'] = False
        display('VIEWER_UNAVAILABLE')
    view.connect('web-process-terminated', renderer_ended)

    def pause_view():
        paused.set()
        connected.clear()
        current['connected'] = False
        completed = threading.Event()
        def run():
            view.run_javascript('window.AgentShellViewer?.pause()', None,
                                lambda *_: completed.set(), None)
        ui(run)
        completed.wait(2)

    def load(launch):
        if quitting.is_set() or paused.is_set():
            return
        url, header, certificate = launch
        if certificate:
            context.allow_tls_certificate_for_host(Gio.TlsCertificate.new_from_pem(certificate, -1), config.host)
        cookie = Soup.Cookie.parse(header, Soup.URI.new(config.origin))
        def added(cookie_manager, result, *_):
            try:
                cookie_manager.add_cookie_finish(result)
                if quitting.is_set() or paused.is_set():
                    return
                view.load_uri(url)
                window.show_all()
                window.deiconify()
                window.present()
            except Exception:
                display('VIEWER_AUTHORIZATION_REQUIRED')
        context.get_cookie_manager().add_cookie(cookie, None, added, None)

    def command(name, handoff=None, generation=None):
        if name == 'status':
            return dict(current)
        if name == 'reconnect' and paused.is_set():
            return dict(current)
        if quitting.is_set():
            raise Failure('VIEWER_UNAVAILABLE')
        if name == 'quit':
            pause_view()
            if session[0]:
                try:
                    api.release(identity, *session)
                except Failure:
                    pass
            ui(lambda: window.destroy())
            return {'code': 'CLOSED', 'connected': False}
        if name == 'phone':
            pause_view()
            if not session[0]:
                session[:] = api.session()
            api.release(identity, *session)
            display('PHONE')
            ui(lambda: window.iconify())
            return dict(current)
        if name not in ('open', 'desktop', 'reconnect'):
            raise Failure('VIEWER_UNAVAILABLE')
        if connected.is_set() and not paused.is_set() and not handoff:
            ui(lambda: (window.deiconify(), window.present()))
            return dict(current)
        pause_view()
        session[:] = api.session(start=not handoff)
        if handoff and session[1] != generation:
            raise Failure('RUNTIME_CHANGED')
        try:
            launch = api.launch(identity, *session, takeover=name == 'desktop', handoff=handoff)
            if quitting.is_set():
                raise Failure('VIEWER_UNAVAILABLE')
            connected.clear()
            paused.clear()
            ui(lambda: load(launch))
            expires = min(time.monotonic() + 18, api.deadline)
            while not connected.wait(0.2):
                if quitting.is_set():
                    raise Failure('VIEWER_UNAVAILABLE')
                if time.monotonic() >= expires:
                    raise Failure('VIEWER_TIMEOUT')
            return dict(current)
        except Failure:
            paused.set()
            # A failed bootstrap/load must not leave a control reservation that
            # unnecessarily blocks the phone for its full expiry period.
            try:
                api.deadline = time.monotonic() + 5
                api.release(identity, *session)
            except Failure:
                pass
            raise

    def safe_command(name, handoff=None, generation=None):
        api.deadline = time.monotonic() + 40
        try:
            return command(name, handoff, generation)
        except Failure as error:
            display(error.code)
            return {'code': error.code, 'connected': False, 'pid': os.getpid()}
        except Exception:
            display('VIEWER_UNAVAILABLE')
            return {'code': 'VIEWER_UNAVAILABLE', 'connected': False, 'pid': os.getpid()}
        finally:
            api.deadline = None

    def submit(name, handoff=None, generation=None):
        return executor.submit(safe_command, name, handoff, generation)
    for title, action in [('Reconnect', 'open'), ('Return to computer', 'desktop'), ('Send to phone', 'phone')]:
        button = Gtk.Button(label=title)
        button.connect('clicked', lambda _, action=action: submit(action))
        bar.pack_start(button, False, False, 0)
    fullscreen = [False]
    def keys(_, event):
        if event.keyval == 65480:  # F11 belongs to the viewer, not remote Chrome.
            fullscreen[0] = not fullscreen[0]
            window.fullscreen() if fullscreen[0] else window.unfullscreen()
            return True
        return False
    window.connect('key-press-event', keys)
    window.connect('destroy', lambda *_: (paused.set(), quitting.set(), connected.clear(), Gtk.main_quit()))

    config.socket.unlink(missing_ok=True)
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(str(config.socket))
    config.socket.chmod(0o600)
    listener.listen(4)
    listener.settimeout(0.5)
    def control():
        while not quitting.is_set():
            try:
                client, _ = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            with client:
                client.settimeout(55)
                try:
                    _, uid, _ = struct.unpack('3i', client.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
                    if uid != os.getuid():
                        continue
                    request = receive(client)
                    name = request.get('command')
                    if name not in ('open', 'desktop', 'phone', 'status', 'quit'):
                        continue
                    handoff = request.get('handoffId')
                    generation = request.get('generation')
                    if handoff is not None:
                        handoff = str(uuid.UUID(handoff))
                        if name != 'desktop' or not isinstance(generation, str) or not 1 <= len(generation) <= 256:
                            continue
                    result = dict(current) if name == 'status' else submit(name, handoff, generation).result(timeout=50)
                    client.sendall(json.dumps(result).encode() + b'\n')
                except (OSError, ValueError, Failure, TimeoutError):
                    pass
    threading.Thread(target=control, daemon=True).start()
    window.show_all()
    try:
        Gtk.main()
    finally:
        quitting.set()
        listener.close()
        config.socket.unlink(missing_ok=True)
        # Closing the native window disconnects only the viewer. Chrome and its
        # profile remain owned by the independent managed runtime.
        if session[0]:
            try:
                cleanup = Api(config)
                cleanup.deadline = time.monotonic() + 5
                cleanup.release(identity, *session)
            except Failure:
                pass
        executor.shutdown(wait=False, cancel_futures=True)
        lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs='?', default='open', choices=['open', 'phone', 'desktop', 'status', 'quit'])
    parser.add_argument('--config', type=Path, default=Path.home() / '.config/agentshell/browser-desktop.json')
    parser.add_argument('--json', action='store_true', help='Machine-readable status without credentials or browsing data')
    parser.add_argument('--serve', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--from-app', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--handoff-id', help=argparse.SUPPRESS)
    parser.add_argument('--generation', help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        config = Config(args.config)
        if args.handoff_id:
            args.handoff_id = str(uuid.UUID(args.handoff_id))
            if args.command != 'desktop' or not args.generation or len(args.generation) > 256:
                raise Failure('HANDOFF_EXPIRED')
        if args.serve:
            serve(config)
            return
        try:
            if args.handoff_id:
                previous = call(config, 'status')
                if previous.get('protocolVersion') != 2:
                    # Replace only an obsolete viewer, never the independently
                    # supervised Chrome. Its old IPC cannot enforce transfer IDs.
                    call(config, 'quit')
                    for _ in range(60):
                        if not config.socket.exists():break
                        time.sleep(.1)
            result = call(config, args.command, args.handoff_id, args.generation)
        except (FileNotFoundError, ConnectionRefusedError):
            if args.from_app:
                # The daemon has no graphical DISPLAY. Ask the service started
                # by the user's graphical login, never guess a display number.
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                    client.settimeout(54)
                    try:
                        client.connect(str(config.state / 'desktop-agent.sock'))
                    except (FileNotFoundError, ConnectionRefusedError):
                        raise Failure('DESKTOP_UNAVAILABLE') from None
                    client.sendall(json.dumps({'command':'desktop','handoffId':args.handoff_id,'generation':args.generation}).encode()+b'\n')
                    result = receive(client)
            elif args.command in ('status', 'quit'):
                result = {'code': 'NOT_RUNNING', 'connected': False}
            elif args.command == 'phone':
                # Also release stale access after the viewer process has died.
                api = Api(config)
                api.deadline = time.monotonic() + 40
                api.release(viewer_id(config), *api.session())
                result = {'code': 'PHONE', 'connected': False}
            else:
                if not (os.environ.get('DISPLAY') or os.environ.get('WAYLAND_DISPLAY')):
                    raise Failure('NO_DESKTOP')
                with (config.state / 'viewer.log').open('ab') as log:
                    os.chmod(log.name, 0o600)
                    process = subprocess.Popen(['/usr/bin/python3', str(Path(__file__).resolve()), '--config', str(config.path), '--serve'],
                                               stdout=log, stderr=log, start_new_session=True)
                for _ in range(80):
                    try:
                        result = call(config, args.command, args.handoff_id, args.generation)
                        break
                    except (FileNotFoundError, ConnectionRefusedError):
                        if process.poll() is not None:
                            raise Failure('VIEWER_UNAVAILABLE')
                        time.sleep(0.1)
                else:
                    raise Failure('VIEWER_UNAVAILABLE')
        code = result['code']
    except Failure as error:
        code = error.code
        result = {'code': code, 'connected': False}
    except Exception:
        code = 'VIEWER_UNAVAILABLE'
        result = {'code': code, 'connected': False}
    print(json.dumps(result) if args.json else MESSAGES.get(code, MESSAGES['CONNECTION_FAILED']))
    if code not in ('CONNECTED', 'PHONE', 'CLOSED', 'NOT_RUNNING') and not (args.command == 'status' and result.get('pid')):
        sys.exit(1)


if __name__ == '__main__':
    main()

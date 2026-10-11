#!/usr/bin/env python3
"""Exercise publication routing and HTTP failures through the stdio adapter."""
import importlib.util
import io
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


adapter = load('ui_adapter', 'agent-ui-mcp.py')
installer = load('ui_installer', 'install-ui-mcp.py')


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.response = (409, b'The publisher has no terminal identity')
        self.received = []
        test = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                test.received.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                status, body = test.response
                self.send_response(status)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.token = 'private-test-token'
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.patches = [
            patch.object(adapter, 'runtime_config', return_value=(self.url, self.token)),
            patch.object(adapter, 'owner', return_value=(123, 456)),
            patch.object(adapter, 'Path'),
            patch.dict(adapter.os.environ, {}, clear=True),
        ]
        for item in self.patches:
            item.start()
        adapter.Path.return_value.__truediv__.return_value.__truediv__.return_value.read_text.return_value = 'codex'

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_native_thread_routes_without_inherited_tmux(self):
        self.response = (200, b'{"widgetId":"test-widget"}')
        result = adapter.publish('Calculator', '<p>UI</p>', {'threadId': 'native-thread', 'progressToken': 1})
        self.assertEqual(result['widgetId'], 'test-widget')
        self.assertEqual(self.received[0]['codexThreadId'], 'native-thread')
        self.assertNotIn('paneId', self.received[0])

    def test_backend_reason_survives_stdio_error(self):
        request = {'id': 1, 'method': 'tools/call', 'params': {
            'name': 'render_interactive_ui', 'arguments': {'title': 'Calculator', 'html': '<p>UI</p>'},
            '_meta': {'threadId': 'native-thread'},
        }}
        output = io.StringIO()
        with patch.object(adapter.sys, 'stdin', io.StringIO(json.dumps(request) + '\n')), patch.object(adapter.sys, 'stdout', output):
            adapter.run()
        error = json.loads(output.getvalue())['error']['message']
        self.assertIn('HTTP 409', error)
        self.assertIn('The publisher has no terminal identity', error)
        self.assertNotIn(self.token, error)
        self.assertNotIn(self.url, error)
        self.assertEqual(self.received[0]['codexThreadId'], 'native-thread')

    def test_credential_is_redacted_and_error_is_bounded(self):
        self.response = (502, (self.token + '\n' + 'x' * 20000).encode())
        with self.assertRaises(adapter.PublicationError) as caught:
            adapter.publish('Calculator', '<p>UI</p>')
        error = str(caught.exception)
        self.assertIn('[redacted]', error)
        self.assertIn('[truncated]', error)
        self.assertNotIn(self.token, error)
        self.assertLess(len(error), 8300)

    def test_empty_backend_body_keeps_status_and_reason(self):
        self.response = (409, b'')
        with self.assertRaisesRegex(adapter.PublicationError, r'HTTP 409.*Conflict'):
            adapter.publish('Calculator', '<p>UI</p>')

    def test_invalid_metadata_never_publishes(self):
        with self.assertRaisesRegex(ValueError, 'Invalid MCP metadata'):
            adapter.publish('Calculator', '<p>UI</p>', ['invalid'])
        self.assertEqual(self.received, [])


class InstallationTests(unittest.TestCase):
    def test_adapter_update_changes_config_revision_but_not_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / 'adapter.py'
            config = Path(directory) / 'private-runtime.json'
            script.write_text('version one')
            first = installer.server_environment(script, config)
            self.assertEqual(first, installer.server_environment(script, config))
            script.write_text('version two')
            second = installer.server_environment(script, config)
            self.assertNotEqual(first['AGENTSHELL_UI_REVISION'], second['AGENTSHELL_UI_REVISION'])
            self.assertEqual(second['AGENTSHELL_UI_CONFIG'], str(config))
            self.assertEqual(set(second), {'AGENTSHELL_UI_CONFIG', 'AGENTSHELL_UI_REVISION'})

    def test_codex_registration_includes_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / 'adapter.py'
            script.write_text('adapter')
            argv = ['install-ui-mcp.py', '--script', str(script), '--user-home', directory, '--tools', 'codex']
            with patch('sys.argv', argv), \
                 patch.dict(installer.os.environ, {'AUTH_TOKEN': 'private-test-token'}), \
                 patch.object(installer.shutil, 'which', return_value='/fake/codex'), \
                 patch.object(installer.subprocess, 'run') as run, patch('sys.stdout', io.StringIO()):
                installer.main()
            command = run.call_args.args[0]
            revision = installer.server_environment(script, Path(directory) / '.config/agentshell/interactive-ui.json')['AGENTSHELL_UI_REVISION']
            self.assertIn('AGENTSHELL_UI_REVISION=' + revision, command)
            self.assertNotIn('private-test-token', ' '.join(command))
            self.assertEqual((Path(directory) / '.config/agentshell/interactive-ui.json').stat().st_mode & 0o777, 0o600)


if __name__ == '__main__':
    unittest.main()

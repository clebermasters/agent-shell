#!/usr/bin/env python3
"""Exercise publication routing and HTTP failures through the stdio adapter."""
import importlib.util
import base64
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
            patch.object(adapter, 'process_name', return_value='codex'),
            patch.dict(adapter.os.environ, {}, clear=True),
        ]
        for item in self.patches:
            item.start()

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


class FilePublicationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.file = Path(self.directory.name) / 'chart.png'
        self.file.write_bytes(b'attachment-bytes')
        self.received = []
        self.receipt = {'published': True, 'bindingId': 'lease-a', 'conversationKey': 'conversation:codex:one',
                        'sessionName': 'arbitrary-renamed-session', 'windowIndex': 3, 'fileIds': ['stored-file']}
        self.response = (200, json.dumps(self.receipt).encode())
        test = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                test.received.append((self.path, json.loads(self.rfile.read(int(self.headers['Content-Length'])))))
                self.send_response(test.response[0])
                self.end_headers()
                self.wfile.write(test.response[1])

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.config = patch.object(adapter, 'runtime_config', return_value=(f'http://127.0.0.1:{self.server.server_port}', 'private-test-token'))
        self.identity = patch.object(adapter, 'publisher_identity', return_value={'agentPid': 123, 'agentStart': 456, 'codexThreadId': 'native-thread'})
        self.config.start()
        self.identity_mock = self.identity.start()

    def tearDown(self):
        self.identity.stop()
        self.config.stop()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def test_file_uses_assistant_publication_and_current_native_identity(self):
        result = adapter.publish_chat_file(str(self.file), 'Requested chart', {'threadId': 'native-thread'})
        endpoint, payload = self.received[0]
        self.assertEqual(endpoint, '/api/chat/publish')
        self.assertEqual(result, self.receipt)
        self.identity_mock.assert_called_once_with({'threadId': 'native-thread'})
        self.assertNotIn('sessionName', payload)
        self.assertNotIn('paneId', payload)
        self.assertNotIn('type', payload)
        self.assertEqual(payload['text'], 'Requested chart')
        self.assertEqual(payload['files'][0]['filename'], 'chart.png')
        self.assertEqual(payload['files'][0]['mimeType'], 'image/png')
        self.assertEqual(base64.b64decode(payload['files'][0]['data']), b'attachment-bytes')

    def test_arbitrary_broadcast_or_incomplete_receipt_is_not_success(self):
        for response in ({'type': 'sessions-list', 'sessions': []}, {'published': True}, dict(self.receipt, published=False)):
            self.response = (200, json.dumps(response).encode())
            with self.assertRaisesRegex(adapter.PublicationError, 'did not confirm'):
                adapter.publish_chat_file(str(self.file))

    def test_explicit_session_and_window_are_guards_not_routing_overrides(self):
        adapter.publish_chat_file(str(self.file), expected_session_name='arbitrary-renamed-session', expected_window_index=3)
        self.assertEqual(self.received[0][1]['expectedSessionName'], 'arbitrary-renamed-session')
        self.assertEqual(self.received[0][1]['expectedWindowIndex'], 3)
        with self.assertRaisesRegex(adapter.PublicationError, 'different session'):
            adapter.publish_chat_file(str(self.file), expected_session_name='another-chat')

    def test_stdio_file_tool_forwards_per_call_metadata_and_returns_destination(self):
        request = {'id': 1, 'method': 'tools/call', 'params': {'name': 'send_file_to_chat',
            'arguments': {'path': str(self.file), 'description': 'Requested image'}, '_meta': {'threadId': 'native-thread'}}}
        output = io.StringIO()
        with patch.object(adapter.sys, 'stdin', io.StringIO(json.dumps(request) + '\n')), patch.object(adapter.sys, 'stdout', output):
            adapter.run()
        result = json.loads(output.getvalue())['result']
        self.identity_mock.assert_called_once_with({'threadId': 'native-thread'})
        self.assertEqual(result['structuredContent'], self.receipt)
        self.assertIn('arbitrary-renamed-session:3', result['content'][0]['text'])

    def test_failed_publication_never_claims_the_file_was_saved(self):
        self.response = (409, b'The terminal switched conversations')
        with self.assertRaisesRegex(adapter.PublicationError, 'HTTP 409.*switched conversations'):
            adapter.publish_chat_file(str(self.file))

    def test_tool_inventory_includes_conversation_scoped_file_delivery(self):
        output = io.StringIO()
        with patch.object(adapter.sys, 'stdin', io.StringIO('{"id":1,"method":"tools/list"}\n')), patch.object(adapter.sys, 'stdout', output):
            adapter.run()
        names = {tool['name'] for tool in json.loads(output.getvalue())['result']['tools']}
        self.assertEqual(names, {'get_ui_design_system', 'render_interactive_ui', 'send_file_to_chat'})


if __name__ == '__main__':
    unittest.main()

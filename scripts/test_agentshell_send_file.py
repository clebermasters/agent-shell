#!/usr/bin/env python3
"""CLI and legacy-wrapper checks; never send a live notification."""

from contextlib import redirect_stderr, redirect_stdout
import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


cli = module("agentshell_send_file", "agentshell-send-file.py")


class SendFileTests(unittest.TestCase):
    def receipt(self, **changes):
        return {"published": True, "bindingId": "binding-current", "conversationKey": "conversation-current",
                "sessionName": "renamed-session", "windowIndex": 3, "fileIds": ["file-id"], **changes}

    def invoke(self, args, response=None, error=None, environment=None):
        publisher = mock.Mock(return_value=self.receipt() if response is None else response, side_effect=error)
        output, errors = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, environment or {}, clear=True), \
                mock.patch.object(cli, "load_adapter", return_value=SimpleNamespace(publish_chat_file=publisher)), \
                redirect_stdout(output), redirect_stderr(errors):
            result = cli.main(args)
        return result, output.getvalue(), errors.getvalue(), publisher

    def test_default_publishes_to_current_thread_without_named_or_zero_window_fallback(self):
        result, output, error, publisher = self.invoke(["/tmp/chart.png", "--prompt", "Chart ready", "--json"], environment={
            "CODEX_THREAD_ID": "actual-thread", "CODEX_SESSION_ID": "older-thread", "TMUX_PANE": "%0",
            "WEBMUX_TMUX_SESSION": "obsolete-fallback", "WEBMUX_ACP_SESSION_ID": "other-session",
        })
        self.assertEqual(0, result)
        publisher.assert_called_once_with("/tmp/chart.png", prompt="Chart ready", metadata={"threadId": "actual-thread"},
                                          expected_session_name=None, expected_window_index=None)
        self.assertEqual(self.receipt(), json.loads(output))
        self.assertEqual("", error)

    def test_session_fallback_thread_identity_is_read_for_each_invocation(self):
        with mock.patch.dict(os.environ, {"CODEX_SESSION_ID": "first"}, clear=True):
            self.assertEqual({"threadId": "first"}, cli.current_metadata())
            os.environ["CODEX_SESSION_ID"] = "second"
            self.assertEqual({"threadId": "second"}, cli.current_metadata())
        with mock.patch.dict(os.environ, {"TMUX_PANE": "%7"}, clear=True):
            self.assertIsNone(cli.current_metadata())

    def test_explicit_session_and_window_are_only_verification_guards(self):
        result, _, _, publisher = self.invoke(["/tmp/report.pdf", "--mode", "tmux", "--session-name", "renamed-session", "--window-index", "3"])
        self.assertEqual(0, result)
        self.assertEqual("renamed-session", publisher.call_args.kwargs["expected_session_name"])
        self.assertEqual(3, publisher.call_args.kwargs["expected_window_index"])
        result, output, error, _ = self.invoke(["/tmp/report.pdf", "--session-name", "another-session"])
        self.assertEqual(4, result)
        self.assertEqual("", output)
        self.assertIn("expected session", error)

    def test_success_requires_a_complete_file_publication_receipt(self):
        invalid = [
            {"type": "sessions-list", "sessions": []}, {"received": True, "id": "notification-id"},
            self.receipt(published=False), self.receipt(bindingId=""), self.receipt(fileIds=[]),
            self.receipt(windowIndex=True), self.receipt(windowIndex=-1),
        ]
        for response in invalid:
            with self.subTest(response=response):
                result, output, errors, _ = self.invoke(["/tmp/chart.png"], response=response)
                self.assertEqual(4, result)
                self.assertEqual("", output)
                self.assertIn("File publication failed", errors)

    def test_legacy_acp_and_skip_verification_never_publish(self):
        for args in (["--mode", "acp"], ["--list-sessions"], ["/tmp/file", "--skip-verify"]):
            with self.subTest(args=args), mock.patch.object(cli, "load_adapter") as load, redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    cli.main(args)
                self.assertEqual(2, error.exception.code)
                load.assert_not_called()

    def test_rejected_old_token_argument_is_not_echoed(self):
        errors = io.StringIO()
        with redirect_stderr(errors), self.assertRaises(SystemExit):
            cli.main(["/tmp/file", "--token", "private-command-line-value"])
        self.assertNotIn("private-command-line-value", errors.getvalue())

    def test_publication_failure_redacts_environment_credentials(self):
        result, output, errors, _ = self.invoke(["/tmp/file"], error=RuntimeError("rejected private-env-value"),
                                               environment={"AGENTSHELL_UI_TOKEN": "private-env-value"})
        self.assertEqual(4, result)
        self.assertEqual("", output)
        self.assertNotIn("private-env-value", errors)
        self.assertIn("[redacted]", errors)

    def test_real_adapter_interface_publishes_file_with_per_call_identity_and_guards(self):
        adapter = cli.load_adapter()
        with tempfile.TemporaryDirectory() as temporary:
            file = Path(temporary) / "diagram.png"
            file.write_bytes(b"image-bytes\x00")
            output = io.StringIO()
            with mock.patch.dict(os.environ, {"CODEX_THREAD_ID": "calling-thread", "TMUX_PANE": "%stale"}, clear=True), \
                    mock.patch.object(cli, "load_adapter", return_value=adapter), \
                    mock.patch.object(adapter, "owner", return_value=(100, 200)), \
                    mock.patch.object(adapter, "process_name", return_value="codex") as process_name, \
                    mock.patch.object(adapter, "post_publication", return_value=self.receipt()) as post, \
                    redirect_stdout(output):
                result = cli.main([str(file), "--prompt", "Diagram ready", "--session-name", "renamed-session", "--window-index", "3", "--json"])
            self.assertEqual(0, result)
            process_name.assert_called_once_with(100)
            endpoint, payload = post.call_args.args
            self.assertEqual("/api/chat/publish", endpoint)
            self.assertEqual({"timeout": 30}, post.call_args.kwargs)
            self.assertEqual("calling-thread", payload["codexThreadId"])
            self.assertNotIn("paneId", payload)
            self.assertEqual("renamed-session", payload["expectedSessionName"])
            self.assertEqual(3, payload["expectedWindowIndex"])
            self.assertEqual("Diagram ready", payload["text"])
            self.assertEqual("image/png", payload["files"][0]["mimeType"])
            self.assertEqual(b"image-bytes\x00", base64.b64decode(payload["files"][0]["data"]))
            self.assertEqual(self.receipt(), json.loads(output.getvalue()))

    def test_real_adapter_error_never_becomes_a_successful_cli_receipt(self):
        adapter = cli.load_adapter()
        with tempfile.TemporaryDirectory() as temporary:
            file = Path(temporary) / "report.txt"
            file.write_text("report")
            output, errors = io.StringIO(), io.StringIO()
            with mock.patch.object(cli, "load_adapter", return_value=adapter), \
                    mock.patch.object(adapter, "publisher_identity", return_value={"agentPid": 100, "agentStart": 200, "paneId": "%1"}), \
                    mock.patch.object(adapter, "post_publication", return_value={"received": True, "id": "wrong-protocol"}), \
                    redirect_stdout(output), redirect_stderr(errors):
                result = cli.main([str(file)])
            self.assertEqual(4, result)
            self.assertEqual("", output.getvalue())
            self.assertIn("did not confirm", errors.getvalue())


if __name__ == "__main__":
    unittest.main()

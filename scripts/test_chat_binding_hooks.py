#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


hook = module("binding_hook", "chat-binding-hook.py")
installer = module("binding_hook_installer", "install-chat-binding-hooks.py")


class HookTests(unittest.TestCase):
    def test_metadata_never_contains_prompt_or_transcript_content(self):
        payload = hook.metadata({"hook_event_name": "SessionStart", "session_id": "conversation-one", "cwd": "/project",
            "prompt": "private prompt", "messages": [{"content": "private reply"}], "model": "model"}, "codex", "%2", 100, 200)
        self.assertEqual(payload["sessionId"], "conversation-one")
        self.assertNotIn("private", json.dumps(payload))
        self.assertNotIn("messages", payload)

    def test_hook_merge_preserves_existing_hooks_and_is_idempotent(self):
        original = {"permissions": {"allow": ["Read"]}, "hooks": {"Stop": [{"hooks": [{"command": "existing-stop"}]}],
            "SessionStart": [{"matcher": "startup", "hooks": [{"command": "existing-start"}]}]}}
        command = "/usr/bin/python3 /opt/agentshell/hooks/chat-binding-hook.py --tool codex"
        once = installer.merge_hook(original, command)
        twice = installer.merge_hook(once, command)
        self.assertEqual(once, twice)
        self.assertEqual(original["permissions"], twice["permissions"])
        self.assertEqual(original["hooks"]["Stop"], twice["hooks"]["Stop"])
        self.assertIn("existing-start", json.dumps(twice))

    def test_private_record_is_server_scoped_and_replaces_only_same_pane(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "bindings"
            first = {"paneId": "%1", "serverPid": 100, "serverStart": 200, "sessionId": "one"}
            second = {"paneId": "%1", "serverPid": 101, "serverStart": 201, "sessionId": "two"}
            hook.save_record(first, directory)
            hook.save_record(second, directory)
            self.assertEqual(2, len(list(directory.glob("*.json"))))
            self.assertEqual(0, (directory / "100-200-1.json").stat().st_mode & 0o077)
            first["sessionId"] = "resumed"
            hook.save_record(first, directory)
            self.assertEqual("resumed", json.loads((directory / "100-200-1.json").read_text())["sessionId"])
            self.assertEqual("two", json.loads((directory / "101-201-1.json").read_text())["sessionId"])


if __name__ == "__main__":
    unittest.main()

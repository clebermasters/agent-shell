#!/usr/bin/env python3
"""Verify installation preserves custom Codex preferences and is repeatable."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("binding_installer", Path(__file__).with_name("install-chat-binding-hooks.py"))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class FooterInstallationTest(unittest.TestCase):
    def configure(self, source):
        root = tempfile.TemporaryDirectory()
        self.addCleanup(root.cleanup)
        path = Path(root.name)/"config.toml"
        path.write_text(source)
        installer.configure_codex_footer(path)
        result = path.read_text()
        installer.configure_codex_footer(path)
        self.assertEqual(result, path.read_text())
        return result

    def test_missing_footer_keeps_unrelated_configuration(self):
        source = 'model = "keep-model"\n[features]\ngoals = true\n'
        result = self.configure(source)
        self.assertTrue(result.startswith(source.rstrip()))
        self.assertIn('status_line = ["session-id",', result)

    def test_custom_footer_items_and_following_tables_remain(self):
        source = '[tui]\nstatus_line = ["current-dir", "session-id", "model"]\nnotify = true\n[mcp_servers.keep]\ncommand = "keep-server"\n'
        result = self.configure(source)
        self.assertIn('status_line = ["session-id", "current-dir", "model"]', result)
        self.assertIn('notify = true\n[mcp_servers.keep]\ncommand = "keep-server"', result)

    def test_modern_thread_id_alias_is_not_duplicated(self):
        result = self.configure('[tui]\nstatus_line = ["model", "thread-id", "session-id"]\n')
        self.assertIn('status_line = ["thread-id", "model"]', result)

    def test_multiline_array_and_comments(self):
        result = self.configure("[tui]\nstatus_line = [\n 'model', # selected\n 'current-dir',\n]\n")
        self.assertIn('status_line = ["session-id", "model", "current-dir"]', result)


if __name__ == "__main__":
    unittest.main()
